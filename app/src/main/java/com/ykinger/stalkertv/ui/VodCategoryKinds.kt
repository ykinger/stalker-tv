package com.ykinger.stalkertv.ui

import android.content.Context
import android.util.Log
import com.ykinger.stalkertv.AppContainer
import com.ykinger.stalkertv.BuildConfig
import com.ykinger.stalkertv.core.Category
import com.ykinger.stalkertv.core.ContentKind
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Classifies a VOD category from the movie/show flag on its first page.
 * Categories without a flag stay unclassified and are hidden until a manual refresh.
 */
internal fun classifyVodCategory(rows: JSONArray?): ContentKind? {
    if (rows == null) return null
    var movies = 0
    var shows = 0
    for (index in 0 until rows.length()) {
        when (rows.optJSONObject(index)?.optInt("is_series", -1)) {
            0 -> movies++
            1 -> shows++
        }
    }
    return when {
        movies == 0 && shows == 0 -> null
        // Mixed categories follow the majority. A tie keeps the show label,
        // since the series rows still represent a valid show browse entry.
        shows >= movies -> ContentKind.SERIES
        else -> ContentKind.MOVIE
    }
}

internal data class ScannedVodCategories(
    val categories: List<Category>,
    val kinds: Map<String, ContentKind>,
)

/**
 * Reads every VOD category's first page once. This runs only while the playlist
 * connection is being tested, never while browsing or on ordinary page loads.
 */
internal suspend fun scanVodCategoryKinds(profile: PortalProfile, verified: VerifiedPortal,
    container: AppContainer, onProgress: (scanned: Int, total: Int) -> Unit = { _, _ -> }):
    ScannedVodCategories {
    val apiProfile = profile.copy(host = verified.apiHost)
    val categories = withContext(Dispatchers.IO) {
        val list = portalRequest(apiProfile, "type=vod&action=get_categories&JsHttpRequest=1-xml",
            verified.token, container) as? JSONArray ?: return@withContext emptyList()
        (0 until list.length()).mapNotNull { index ->
            val row = list.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id").takeUnless { it.isBlank() || it == "null" }
                ?: return@mapNotNull null
            Category(id, row.optString("title").ifBlank { "Other" })
        }.distinctBy { it.id }
    }
    val categoryIds = categories.map { it.id }.filter { it != "*" }
    onProgress(0, categoryIds.size)
    val result = mutableMapOf<String, ContentKind>()
    for ((index, categoryId) in categoryIds.withIndex()) {
        currentCoroutineContext().ensureActive()
        val rows = withContext(Dispatchers.IO) {
            val response = portalRequest(apiProfile,
                "type=vod&action=get_ordered_list&category=${URLEncoder.encode(categoryId, "UTF-8")}" +
                    "&genre=0&sortby=&p=1&JsHttpRequest=1-xml", verified.token, container)
            when (response) {
                is JSONObject -> response.optJSONArray("data")
                is JSONArray -> response
                else -> null
            }
        }
        classifyVodCategory(rows)?.let { result[categoryId] = it }
        onProgress(index + 1, categoryIds.size)
    }
    if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
        "stage=vod.category_scan classified=${result.size} scanned=${categoryIds.size}")
    return ScannedVodCategories(categories, result)
}

internal fun encodeCategoryKinds(kinds: Map<String, ContentKind>): String = JSONObject().apply {
    kinds.forEach { (categoryId, kind) ->
        when (kind) {
            ContentKind.MOVIE -> put(categoryId, "movie")
            ContentKind.SERIES -> put(categoryId, "series")
            else -> Unit
        }
    }
}.toString()

internal fun decodeCategoryKinds(raw: String): Map<String, ContentKind> = try {
    val json = JSONObject(raw)
    buildMap {
        json.keys().forEach { categoryId ->
            when (json.optString(categoryId)) {
                "movie" -> put(categoryId, ContentKind.MOVIE)
                "series" -> put(categoryId, ContentKind.SERIES)
            }
        }
    }
} catch (_: Exception) {
    emptyMap()
}

internal fun encodeVodCategories(categories: List<Category>): String = JSONArray().apply {
    categories.distinctBy { it.id }.forEach { category ->
        put(JSONObject().put("id", category.id).put("name", category.name))
    }
}.toString()

internal fun decodeVodCategories(raw: String): List<Category> = try {
    val data = JSONArray(raw)
    (0 until data.length()).mapNotNull { index ->
        val row = data.optJSONObject(index) ?: return@mapNotNull null
        val id = row.optString("id").takeIf { it.isNotBlank() && it != "null" }
            ?: return@mapNotNull null
        Category(id, row.optString("name").ifBlank { "Other" })
    }.distinctBy { it.id }
} catch (_: Exception) {
    emptyList()
}

/** The stored key is a one-way digest, so credentials never enter the plain preferences. */
internal fun categoryKindKey(profile: PortalProfile): String {
    val identity = "${profile.host}\n${profile.mac}\n${profile.serial}"
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
    return "playlist:" + digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/** Plain app-private storage: category IDs, names, and movie/show labels only. */
internal class VodCategoryKindStore(context: Context) {
    private val prefs = context.getSharedPreferences("vod-category-kinds", Context.MODE_PRIVATE)

    /** Null means no completed scan exists yet; an empty map is a completed scan with no matches. */
    fun load(profile: PortalProfile): Map<String, ContentKind>? {
        val key = categoryKindKey(profile)
        if (!prefs.contains(key)) return null
        return decodeCategoryKinds(prefs.getString(key, null) ?: return null)
    }

    fun loadCategories(profile: PortalProfile): List<Category>? {
        val key = "${categoryKindKey(profile)}:categories"
        if (!prefs.contains(key)) return null
        return decodeVodCategories(prefs.getString(key, null) ?: return null)
    }

    fun save(profile: PortalProfile, scan: ScannedVodCategories) {
        val key = categoryKindKey(profile)
        check(prefs.edit()
            .putString(key, encodeCategoryKinds(scan.kinds))
            .putString("$key:categories", encodeVodCategories(scan.categories))
            .commit())
    }

    fun saveCategories(profile: PortalProfile, categories: List<Category>) {
        val key = "${categoryKindKey(profile)}:categories"
        check(prefs.edit().putString(key, encodeVodCategories(categories)).commit())
    }

    fun clear() {
        check(prefs.edit().clear().commit())
    }
}
