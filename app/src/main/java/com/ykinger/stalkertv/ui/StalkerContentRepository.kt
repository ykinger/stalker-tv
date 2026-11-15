package com.ykinger.stalkertv.ui

import android.util.Log
import com.ykinger.stalkertv.AppContainer
import com.ykinger.stalkertv.BuildConfig
import com.ykinger.stalkertv.core.*
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Stalker transport types and command references remain inside the adapter. */
internal class StalkerContentRepository(
    private val profile: PortalProfile,
    private val token: String,
    private val container: AppContainer = AppContainer(),
) : PagedContentRepository {
    private var titles = emptyList<Title>()
    private var commands = emptyMap<String, String>()
    private val categoriesMutex = Mutex()
    private val categoryCache = mutableMapOf<String, List<Category>>()

    private suspend fun <T> safely(stage: String, block: suspend () -> ProviderResult<T>): ProviderResult<T> =
        withContext(Dispatchers.IO) {
            try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (BuildConfig.DEBUG) Log.w(PORTAL_TAG, "stage=$stage failure=${error.javaClass.simpleName}")
                ProviderResult.Failure("Portal request failed. Check the server or reconnect your profile.")
            }
        }

    override suspend fun catalogue(): ProviderResult<Catalogue> = loadPage(ContentKind.CHANNEL, null, 1)

    /** Fetch only the currently visible section/category/page; navigation requests the rest. */
    override suspend fun loadPage(kind: ContentKind, categoryId: String?, page: Int): ProviderResult<Catalogue> =
        safely("catalogue.${kind.name.lowercase()}") {
            require(page in 1..250)
            val type = when (kind) {
                ContentKind.CHANNEL -> "itv"
                ContentKind.MOVIE, ContentKind.SERIES -> "vod"
                else -> return@safely ProviderResult.Unsupported
            }
            val categoryAction = if (kind == ContentKind.CHANNEL) "get_genres" else "get_categories"
            val categories = if (page == 1) categoriesMutex.withLock {
                categoryCache[type] ?: run {
                    val list = portalRequest(profile, "type=$type&action=$categoryAction&JsHttpRequest=1-xml", token, container) as JSONArray
                    (0 until list.length()).mapNotNull { index ->
                        val row = list.optJSONObject(index) ?: return@mapNotNull null
                        val id = row.optString("id").takeUnless { it.isBlank() || it == "null" } ?: return@mapNotNull null
                        Category(id, row.optString("title").ifBlank { "Other" })
                    }.distinctBy { it.id }.also { categoryCache[type] = it }
                }
            } else emptyList()
            if (BuildConfig.DEBUG) Log.d(PORTAL_TAG, "stage=catalogue.$type page=$page categories=${categories.size}")
            val category = URLEncoder.encode(categoryId ?: "*", "UTF-8")
            val filter = if (kind == ContentKind.CHANNEL) "genre" else "category"
            val extras = if (kind == ContentKind.CHANNEL) "&sortby=number&fav=0&search=" else ""
            val result = portalRequest(profile,
                "type=$type&action=get_ordered_list&$filter=$category&p=$page$extras&JsHttpRequest=1-xml", token, container) as JSONObject
            val rows = result.optJSONArray("data") ?: error("Portal page has no data array.")
            val pageTitles = (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val rawId = row.optString("id").takeUnless { it.isBlank() || it == "null" } ?: return@mapNotNull null
                val itemKind = if (kind == ContentKind.CHANNEL) kind else if (
                    row.optInt("is_series") == 1 || row.optString("movie_type") == "series") ContentKind.SERIES
                    else ContentKind.MOVIE
                val id = if (kind == ContentKind.CHANNEL) rawId else
                    "${if (itemKind == ContentKind.SERIES) "series" else "movie"}:$rawId"
                row.optString("cmd").takeIf { it.isNotBlank() && it != "null" }?.let { commands = commands + (id to it) }
                val itemCategory = row.optString(if (kind == ContentKind.CHANNEL) "tv_genre_id" else "category_id")
                    .takeIf { it.isNotBlank() && it != "null" } ?: categoryId.orEmpty()
                Title(id, row.optString("name").ifBlank { "Title $rawId" }, itemKind, itemCategory,
                    row.optString("description").ifBlank { null }, row.optString("screenshot_uri").ifBlank { null },
                    row.optString("year").toIntOrNull())
            }
            titles = (titles + pageTitles).distinctBy { it.id }
            val total = result.optInt("total_items", -1)
            val pageSize = result.optInt("max_page_items", 14).takeIf { it > 0 } ?: 14
            val hasMore = rows.length() > 0 && page < 250 &&
                (if (total >= 0) page.toLong() * pageSize < total else rows.length() >= pageSize)
            if (BuildConfig.DEBUG) Log.d(PORTAL_TAG,
                "stage=catalogue.$type page=$page rows=${rows.length()} titles=${pageTitles.size} total=$total hasMore=$hasMore")
            ProviderResult.Success(Catalogue(categories, pageTitles, hasMore))
        }

    override suspend fun resolve(id: String): ProviderResult<PlaybackSource> = safely("playback.resolve") {
        val command = commands[id] ?: return@safely ProviderResult.Empty
        val type = if (id.startsWith("movie:") || id.startsWith("episode:")) "vod" else "itv"
        val result = portalRequest(profile, "type=$type&action=create_link&cmd=${URLEncoder.encode(command, "UTF-8")}" +
            "&series=0&forced_storage=undefined&disable_ad=0&download=0&JsHttpRequest=1-xml", token, container) as JSONObject
        val raw = result.getString("cmd").trim()
        val url = if (raw.startsWith("ffmpeg ")) raw.removePrefix("ffmpeg ").trim() else raw
        val uri = URI(url)
        check(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null)
        // Do not forward portal tokens to arbitrary stream hosts. Provider-specific
        // playback headers will be added only after their requirements are verified.
        ProviderResult.Success(PlaybackSource(url, titles.first { it.id == id }.name))
    }
    override suspend fun details(id: String): ProviderResult<Title> =
        titles.find { it.id == id }?.let { ProviderResult.Success(it) } ?: ProviderResult.Empty
    override suspend fun seasons(seriesId: String): ProviderResult<List<Season>> = safely("catalogue.seasons") {
        if (titles.none { it.id == seriesId && it.kind == ContentKind.SERIES }) return@safely ProviderResult.Empty
        val rawId = seriesId.removePrefix("series:")
        val rows = (portalRequest(profile, "type=vod&action=get_seasons&series_id=${URLEncoder.encode(rawId, "UTF-8")}", token, container) as JSONObject)
            .getJSONArray("data")
        val seasons = (0 until rows.length()).map { i ->
            currentCoroutineContext().ensureActive()
            val row = rows.getJSONObject(i)
            val seasonId = row.getString("id")
            val episodes = (portalRequest(profile,
                "type=vod&action=get_episodes&series_id=${URLEncoder.encode(rawId, "UTF-8")}&season_id=${URLEncoder.encode(seasonId, "UTF-8")}", token, container) as JSONObject)
                .getJSONArray("data")
            val episodeTitles = (0 until episodes.length()).map { index ->
                val episode = episodes.getJSONObject(index)
                val id = "episode:" + episode.getString("id")
                val title = Title(id, episode.optString("name").ifBlank { "Episode ${episode.optInt("episode_number")}" },
                    ContentKind.EPISODE, seasonId)
                commands = commands + (id to episode.getString("cmd"))
                titles = titles.filterNot { it.id == id } + title
                title
            }
            Season(seasonId, seriesId, row.optInt("season_number"), episodeTitles)
        }
        if (seasons.isEmpty()) ProviderResult.Empty else ProviderResult.Success(seasons)
    }
    override suspend fun programmes(channelId: String): ProviderResult<List<Programme>> = safely("guide.programmes") {
        if (titles.none { it.id == channelId && it.kind == ContentKind.CHANNEL }) return@safely ProviderResult.Empty
        val encodedId = URLEncoder.encode(channelId, "UTF-8")
        val rows = try {
            (portalRequest(profile, "type=epg&action=get_simple_data_table&ch_id=$encodedId&JsHttpRequest=1-xml", token, container) as JSONObject)
                .getJSONArray("data")
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Older panels expose the channel guide via itv instead of epg.
            (portalRequest(profile, "type=itv&action=get_short_epg&ch_id=$encodedId&size=4", token, container) as JSONObject)
                .getJSONArray("data")
        }
        val result = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            Programme(row.getString("id"), channelId, row.optString("name"),
                Instant.ofEpochSecond(row.getLong("start_timestamp")), Instant.ofEpochSecond(row.getLong("stop_timestamp")))
        }
        if (result.isEmpty()) ProviderResult.Empty else ProviderResult.Success(result)
    }
}
