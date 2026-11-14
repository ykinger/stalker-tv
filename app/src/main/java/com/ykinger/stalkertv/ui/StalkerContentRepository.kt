package com.ykinger.stalkertv.ui

import com.ykinger.stalkertv.core.*
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
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
        // VOD is optional on Stalker portals. An unavailable VOD endpoint must not
        // prevent a working live-TV playlist from opening.
        try {
            val vodCategories = portalRequest(profile, "type=vod&action=get_categories", token) as JSONArray
            val vodFound = linkedMapOf<String, Title>()
            val vodReferences = mutableMapOf<String, String>()
            val extraCategories = mutableListOf<Category>()
            for (i in 0 until vodCategories.length()) {
                val row = vodCategories.getJSONObject(i)
                extraCategories.add(Category(row.getString("id"), row.optString("title", "Other")))
            }
            page = 1
            var total: Int
            do {
                currentCoroutineContext().ensureActive()
                check(page <= 1000)
                val result = portalRequest(profile, "type=vod&action=get_ordered_list&category=*&p=$page", token) as JSONObject
                val rows = result.getJSONArray("data")
                total = result.getInt("total_items")
                check(total >= 0 && (rows.length() > 0 || vodFound.size >= total))
                val previousCount = vodFound.size
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val kind = if (row.optInt("is_series") == 1 || row.optString("movie_type") == "series")
                        ContentKind.SERIES else ContentKind.MOVIE
                    val prefix = if (kind == ContentKind.SERIES) "series" else "movie"
                    val id = "$prefix:" + row.getString("id")
                    vodFound[id] = Title(id, row.optString("name"), kind, row.optString("category_id"),
                        row.optString("description").ifBlank { null }, row.optString("screenshot_uri").ifBlank { null },
                        row.optString("year").toIntOrNull())
                    if (kind == ContentKind.MOVIE) vodReferences[id] = row.getString("cmd")
                }
                check(vodFound.size > previousCount || vodFound.size >= total) { "Incomplete VOD page" }
                page++
            } while (vodFound.size < total)
            categories.addAll(extraCategories.filter { extra -> categories.none { it.id == extra.id } })
            found.putAll(vodFound)
            references.putAll(vodReferences)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep live TV available when VOD is absent. */ }
        titles = found.values.toList()
        commands = references
        if (titles.isEmpty()) ProviderResult.Empty else ProviderResult.Success(Catalogue(categories, titles))
    }

    override suspend fun resolve(id: String): ProviderResult<PlaybackSource> = safely {
        val command = commands[id] ?: return@safely ProviderResult.Empty
        val type = if (id.startsWith("movie:") || id.startsWith("episode:")) "vod" else "itv"
        val result = portalRequest(profile, "type=$type&action=create_link&cmd=${URLEncoder.encode(command, "UTF-8")}", token) as JSONObject
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
    override suspend fun seasons(seriesId: String): ProviderResult<List<Season>> = safely {
        if (titles.none { it.id == seriesId && it.kind == ContentKind.SERIES }) return@safely ProviderResult.Empty
        val rawId = seriesId.removePrefix("series:")
        val rows = (portalRequest(profile, "type=vod&action=get_seasons&series_id=${URLEncoder.encode(rawId, "UTF-8")}", token) as JSONObject)
            .getJSONArray("data")
        val seasons = (0 until rows.length()).map { i ->
            currentCoroutineContext().ensureActive()
            val row = rows.getJSONObject(i)
            val seasonId = row.getString("id")
            val episodes = (portalRequest(profile,
                "type=vod&action=get_episodes&series_id=${URLEncoder.encode(rawId, "UTF-8")}&season_id=${URLEncoder.encode(seasonId, "UTF-8")}", token) as JSONObject)
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
    override suspend fun programmes(channelId: String): ProviderResult<List<Programme>> = safely {
        if (titles.none { it.id == channelId && it.kind == ContentKind.CHANNEL }) return@safely ProviderResult.Empty
        val rows = (portalRequest(profile,
            "type=itv&action=get_short_epg&ch_id=${URLEncoder.encode(channelId, "UTF-8")}&size=4", token) as JSONObject)
            .getJSONArray("data")
        val result = (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            Programme(row.getString("id"), channelId, row.optString("name"),
                Instant.ofEpochSecond(row.getLong("start_timestamp")), Instant.ofEpochSecond(row.getLong("stop_timestamp")))
        }
        if (result.isEmpty()) ProviderResult.Empty else ProviderResult.Success(result)
    }
}
