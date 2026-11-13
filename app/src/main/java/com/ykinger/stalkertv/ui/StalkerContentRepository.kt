package com.ykinger.stalkertv.ui

import com.ykinger.stalkertv.core.*
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Stalker transport types and command references remain inside the adapter. */
internal class StalkerContentRepository(
    private val profile: PortalProfile,
    private val token: String,
) : ContentRepository {
    private var titles = emptyList<Title>()
    private var commands = emptyMap<String, String>()

    private suspend fun <T> safely(block: suspend () -> ProviderResult<T>): ProviderResult<T> =
        withContext(Dispatchers.IO) {
            try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { ProviderResult.Failure("Portal request failed. Check the server or reconnect your profile.") }
        }

    override suspend fun catalogue(): ProviderResult<Catalogue> = safely {
        val genres = portalRequest(profile, "type=itv&action=get_genres", token) as JSONArray
        val categories = (0 until genres.length()).map {
            val row = genres.getJSONObject(it)
            Category(row.getString("id"), row.optString("title", "Other"))
        }.toMutableList()
        val found = linkedMapOf<String, Title>()
        val references = mutableMapOf<String, String>()
        var page = 1
        while (true) {
            currentCoroutineContext().ensureActive()
            check(page <= 1000) { "Pagination limit reached" }
            val result = portalRequest(profile, "type=itv&action=get_ordered_list&genre=*&p=$page", token) as JSONObject
            val rows = result.getJSONArray("data")
            val total = result.getInt("total_items")
            check(total >= 0)
            val previousCount = found.size
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val id = row.getString("id")
                val command = row.getString("cmd")
                check(id.isNotBlank() && command.isNotBlank())
                val categoryId = row.optString("tv_genre_id", "other")
                if (categories.none { it.id == categoryId }) categories.add(Category(categoryId, "Other"))
                found[id] = Title(id, row.optString("name").ifBlank { "Channel $id" }, ContentKind.CHANNEL, categoryId)
                references[id] = command
            }
            if (found.size >= total) break
            check(found.size > previousCount) { "Incomplete or repeated page" }
            page++
        }
        titles = found.values.toList()
        commands = references
        if (titles.isEmpty()) ProviderResult.Empty else ProviderResult.Success(Catalogue(categories, titles))
    }

    override suspend fun resolve(id: String): ProviderResult<PlaybackSource> = safely {
        val command = commands[id] ?: return@safely ProviderResult.Empty
        val result = portalRequest(profile, "type=itv&action=create_link&cmd=${URLEncoder.encode(command, "UTF-8")}", token) as JSONObject
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
    override suspend fun seasons(seriesId: String): ProviderResult<List<Season>> = ProviderResult.Unsupported
    override suspend fun programmes(channelId: String): ProviderResult<List<Programme>> = ProviderResult.Unsupported
}
