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
    private val categoryKinds: Map<String, ContentKind> = emptyMap(),
) : PagedContentRepository, VodCategoryKindSource {
    override fun categoryKind(categoryId: String): ContentKind? = categoryKinds[categoryId]

    private var titles = emptyList<Title>()
    private var commands = emptyMap<String, String>()
    private var episodeParents = emptyMap<String, Pair<String, String>>()
    private val categoriesMutex = Mutex()
    private val categoryCache = mutableMapOf<String, List<Category>>()

    private suspend fun <T> safely(stage: String, block: suspend () -> ProviderResult<T>): ProviderResult<T> =
        withContext(Dispatchers.IO) {
            try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG, "stage=$stage failure=${error.javaClass.simpleName}")
                ProviderResult.Failure("Portal request failed. Check the server or reconnect your profile.")
            }
        }

    override suspend fun catalogue(): ProviderResult<Catalogue> = loadPage(ContentKind.CHANNEL, null, 1)

    /** Fetch only the currently visible section/category/page; navigation requests the rest. */
    override suspend fun loadPage(kind: ContentKind, categoryId: String?, page: Int): ProviderResult<Catalogue> =
        loadPage(kind, categoryId, page, "", "")

    override suspend fun loadPage(kind: ContentKind, categoryId: String?, page: Int,
        search: String, sort: String): ProviderResult<Catalogue> =
        safely("catalogue.${kind.name.lowercase()}") {
            require(page in 1..250)
            require(sort in setOf("", "added", "rating", "name"))
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
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG, "stage=catalogue.$type page=$page categories=${categories.size}")
            val category = URLEncoder.encode(categoryId ?: "*", "UTF-8")
            val filter = if (kind == ContentKind.CHANNEL) "genre" else "category"
            val extras = if (kind == ContentKind.CHANNEL) "&sortby=number&fav=0&search=" else
                (if (categoryId == null) "" else if (kind == ContentKind.SERIES) "&is_series=1" else "&is_series=0") +
                    (if (sort.isBlank()) "" else "&sortby=$sort") +
                    (if (search.isBlank()) "" else "&search=${URLEncoder.encode(search.trim(), "UTF-8")}")
            val response = portalRequest(profile,
                "type=$type&action=get_ordered_list&$filter=$category&p=$page$extras&JsHttpRequest=1-xml", token, container)
            // Some portals return js:[] for an empty page instead of an object
            // containing data:[]. Keep the categories so users can select one.
            if (response is JSONArray && response.length() == 0) {
                if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                    "stage=catalogue.$type page=$page result=emptyArray categorySelected=${categoryId != null}")
                return@safely ProviderResult.Success(Catalogue(categories, emptyList(), false))
            }
            val result = response as? JSONObject ?: return@safely ProviderResult.Unsupported
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
                val artwork = listOf("pic", "screenshot_uri").firstNotNullOfOrNull { field ->
                    row.optString(field).takeUnless { it.isBlank() || it in setOf("null", "0", "false") }
                        ?.let { posterAddress(profile.host, it) }
                }
                Title(id, row.optString("name").ifBlank { "Title $rawId" }, itemKind, itemCategory,
                    row.optString("description").ifBlank { null }, artwork,
                    row.optString("year").toIntOrNull())
            }.filter { kind == ContentKind.CHANNEL || categoryId != null || it.kind == kind }
            titles = (titles + pageTitles).distinctBy { it.id }
            val total = result.optInt("total_items", -1)
            val pageSize = result.optInt("max_page_items", 14).takeIf { it > 0 } ?: 14
            val hasMore = rows.length() > 0 && page < 250 &&
                (if (total >= 0) page.toLong() * pageSize < total else rows.length() >= pageSize)
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                "stage=catalogue.$type page=$page rows=${rows.length()} titles=${pageTitles.size} total=$total hasMore=$hasMore")
            // The portal ignores is_series for All, so its total is combined and
            // cannot be shown as a Movies or Shows total. Selected categories do
            // honor the filter and retain their reported total.
            val visibleTotal = total.takeIf { it >= 0 && (kind == ContentKind.CHANNEL || categoryId != null) }
            ProviderResult.Success(Catalogue(categories, pageTitles, hasMore, visibleTotal))
        }

    override suspend fun resolve(id: String): ProviderResult<PlaybackSource> = safely("playback.resolve") {
        val catalogueCommand = commands[id] ?: if (id.startsWith("episode:")) {
            val (seriesId, seasonId) = episodeParents[id] ?: return@safely ProviderResult.Empty
            val episodeId = id.removePrefix("episode:")
            val detail = portalRequest(profile,
                "type=vod&action=get_ordered_list&movie_id=${URLEncoder.encode(seriesId, "UTF-8")}" +
                    "&season_id=${URLEncoder.encode(seasonId, "UTF-8")}" +
                    "&episode_id=${URLEncoder.encode(episodeId, "UTF-8")}&p=1&JsHttpRequest=1-xml",
                token, container)
            val detailCommand = selectedEpisodeCommand(detail, episodeId, seriesId)
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                "stage=vod.episode_detail rows=${(detail as? JSONObject)?.optJSONArray("data")?.length() ?: -1} " +
                    "commandFound=${detailCommand != null}")
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) (detail as? JSONObject)?.optJSONArray("data")?.let { rows ->
                for (index in 0 until minOf(rows.length(), 3)) {
                    val row = rows.optJSONObject(index) ?: continue
                    Log.d(PORTAL_TAG, "stage=vod.episode_detail row=$index " +
                        "idMatches=${row.optString("id") == episodeId} " +
                        "idNumeric=${row.optString("id").matches(Regex("[0-9]{1,20}"))} " +
                        "isFile=${row.optInt("is_file") == 1 || row.optBoolean("is_file")} " +
                        "videoMatchesEpisode=${row.optString("video_id") == episodeId} " +
                        "videoMatchesSeries=${row.optString("video_id") == seriesId} " +
                        "episodeMatches=${row.optString("episode_id") == episodeId} " +
                        "hasPath=${row.has("path")} hasCmd=${row.has("cmd")}")
                }
            }
            detailCommand?.also { commands = commands + (id to it) }
        } else null
        val availableCommand = catalogueCommand ?: run {
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG, "stage=playback.resolve source=missing")
            return@safely ProviderResult.Empty
        }
        val type = if (id.startsWith("movie:") || id.startsWith("episode:")) "vod" else "itv"
        val movie = id.startsWith("movie:")
        val fileCommand = if (movie) try {
            val movieId = id.removePrefix("movie:")
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                "stage=vod.movie_detail request=selectedMovie action=get_ordered_list")
            val detail = portalRequest(profile,
                "type=vod&action=get_ordered_list&movie_id=${URLEncoder.encode(movieId, "UTF-8")}",
                token, container)
            movieFileLinkCommand(detail, movieId).also {
                if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                    "stage=vod.movie_detail fileCommandFound=${it != null}")
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG,
                "stage=vod.movie_detail failure=${error.javaClass.simpleName}")
            null
        } else if (id.startsWith("episode:") &&
            availableCommand.matches(Regex("/media/file_[0-9]{1,20}\\.mpg"))) availableCommand else null
        // Never use the file row's direct URL or storage path as a stream URL.
        val command = fileCommand ?: availableCommand
        val movieLinkOptions = movie || fileCommand != null
        // File rows use the observed working movie request; other episodes and TV
        // retain their existing create_link options.
        val series = if (id.startsWith("episode:")) 1 else 0
        if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
            "stage=playback.resolve source=${mediaCommandForm(command)} type=$type " +
                "fileCommand=${fileCommand != null} track=${if (movieLinkOptions) "omitted" else series}")
        val query = "type=$type&action=create_link&cmd=${URLEncoder.encode(command, "UTF-8")}" +
            if (movieLinkOptions) "&force_ch_link_check=0" else
                "&series=$series&forced_storage=undefined&disable_ad=0&download=0&JsHttpRequest=1-xml"
        if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING && type == "vod") Log.d(PORTAL_TAG,
            "stage=vod.create_link request=redacted source=${mediaCommandForm(command)} " +
                "options=${if (movieLinkOptions) "movie" else "series"}")
        val result = portalRequest(profile, query, token, container) as JSONObject
        val raw = result.optString("cmd")
        if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING && type == "vod") Log.d(PORTAL_TAG,
            "stage=vod.create_link response=redacted hasCmd=${result.has("cmd")} " +
                "cmdForm=${mediaCommandForm(raw)} errorKind=${portalErrorKind(result.opt("error"))}")
        val url = playbackAddress(raw, profile.host) ?: run {
            val portalError = result.opt("error")
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                "stage=playback.resolve link=unsupported hasCmd=${result.has("cmd")} " +
                    "cmdType=${when (result.opt("cmd")) {
                        null, JSONObject.NULL -> "null"
                        is String -> "string"
                        else -> "other"
                    }} form=${mediaCommandForm(raw)} errorType=${jsonValueType(portalError)} " +
                    "errorKind=${portalErrorKind(portalError)} hasUrl=${result.has("url")} " +
                    "hasLink=${result.has("link")} hasStream=${result.has("stream_url")}")
            return@safely ProviderResult.Unsupported
        }
        if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG, "stage=playback.resolve link=accepted type=$type")
        // The working movie player sends no portal token, MAC cookie or Referer
        // to the media host. Only its observed media identity is used here.
        ProviderResult.Success(PlaybackSource(url, titles.first { it.id == id }.name,
            if (movieLinkOptions) mapOf("User-Agent" to "Lavf53.32.100", "Connection" to "Keep-Alive",
                "Accept-Encoding" to "gzip") else emptyMap()))
    }
    override suspend fun details(id: String): ProviderResult<Title> =
        titles.find { it.id == id }?.let { ProviderResult.Success(it) } ?: ProviderResult.Empty
    override suspend fun seasons(seriesId: String): ProviderResult<List<Season>> = safely("catalogue.seasons") {
        if (titles.none { it.id == seriesId && it.kind == ContentKind.SERIES }) return@safely ProviderResult.Empty
        val rawId = seriesId.removePrefix("series:")
        val seasons = mutableListOf<Season>()
        for (page in 1..10) {
            currentCoroutineContext().ensureActive()
            // The series row's cmd is a media command, not a detail URL. Stalker
            // exposes children through get_ordered_list with a parent movie_id.
            val result = portalRequest(profile,
                "type=vod&action=get_ordered_list&movie_id=${URLEncoder.encode(rawId, "UTF-8")}" +
                    "&season_id=0&episode_id=0&p=$page&JsHttpRequest=1-xml", token, container) as? JSONObject
                ?: return@safely ProviderResult.Unsupported
            val rows = result.optJSONArray("data") ?: return@safely ProviderResult.Unsupported
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                if (row.optInt("is_season") != 1 && !row.optBoolean("is_season")) continue
                val id = row.optString("id").takeIf { it.isNotBlank() } ?: continue
                seasons += Season(id, seriesId, row.optInt("season_number", seasons.size + 1), emptyList())
            }
            val total = result.optInt("total_items", -1)
            val size = result.optInt("max_page_items", 14).takeIf { it > 0 } ?: 14
            if (rows.length() == 0 || (total >= 0 && page.toLong() * size >= total) ||
                (total < 0 && rows.length() < size)) break
        }
        if (seasons.isEmpty()) ProviderResult.Empty else ProviderResult.Success(seasons)
    }

    override suspend fun episodes(seriesId: String, seasonId: String, page: Int): ProviderResult<EpisodePage> =
        safely("catalogue.episodes") {
            if (page !in 1..250 || titles.none { it.id == seriesId && it.kind == ContentKind.SERIES })
                return@safely ProviderResult.Empty
            val rawId = URLEncoder.encode(seriesId.removePrefix("series:"), "UTF-8")
            val rawSeason = URLEncoder.encode(seasonId, "UTF-8")
            val result = portalRequest(profile,
                "type=vod&action=get_ordered_list&movie_id=$rawId&season_id=$rawSeason" +
                    "&episode_id=0&p=$page&JsHttpRequest=1-xml", token, container) as? JSONObject
                ?: return@safely ProviderResult.Unsupported
            val rows = result.optJSONArray("data") ?: return@safely ProviderResult.Unsupported
            var playableCommands = 0
            val items = (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val rawEpisode = row.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val id = "episode:$rawEpisode"
                episodeParents = episodeParents + (id to (seriesId.removePrefix("series:") to seasonId))
                row.optString("cmd").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { commands = commands + (id to it); playableCommands++ }
                val artwork = listOf("pic", "screenshot_uri").firstNotNullOfOrNull { field ->
                    row.optString(field).takeUnless { it.isBlank() || it in setOf("null", "0", "false") }
                        ?.let { posterAddress(profile.host, it) }
                }
                Title(id, row.optString("name").ifBlank { "Episode ${row.optInt("series_number", index + 1)}" },
                    ContentKind.EPISODE, seasonId, artworkKey = artwork)
            }
            titles = (titles + items).distinctBy { it.id }
            val total = result.optInt("total_items", -1)
            val size = result.optInt("max_page_items", 14).takeIf { it > 0 } ?: 14
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                "stage=catalogue.episodes page=$page rows=${rows.length()} commands=$playableCommands")
            ProviderResult.Success(EpisodePage(items, rows.length() > 0 && page < 250 &&
                (if (total >= 0) page.toLong() * size < total else rows.length() >= size)))
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

/** The episode-filtered detail may identify its file by a separate row ID linked to the show. */
internal fun selectedEpisodeCommand(detail: Any?, episodeId: String, seriesId: String): String? {
    val result = detail as? JSONObject ?: return null
    val rows = result.optJSONArray("data") ?: return null
    val selected = (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        if (row.optString("id") != episodeId && row.optString("video_id") != episodeId &&
            row.optString("episode_id") != episodeId) return@mapNotNull null
        row.optString("cmd").takeUnless { it.isBlank() || it == "null" }
    }.distinct()
    if (selected.isNotEmpty()) return selected.singleOrNull()
    // The observed episode_id-filtered response contained one file row linked
    // to the show. Use the same file command already verified for movie files,
    // never the file's storage path or an invented stream URL.
    return if (rows.length() == 1) movieFileLinkCommand(result, seriesId) else null
}

/** Two observed successful requests used /media/file_<file row ID>.mpg as a create_link command. */
internal fun movieFileLinkCommand(detail: Any?, movieId: String): String? {
    val rows = (detail as? JSONObject)?.optJSONArray("data") ?: return null
    val files = (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        if (row.optInt("is_file") != 1 && !row.optBoolean("is_file")) return@mapNotNull null
        if (row.optString("video_id") != movieId) return@mapNotNull null
        row.optString("id").takeIf { it.matches(Regex("[0-9]{1,20}")) }
    }.distinct()
    return files.singleOrNull()?.let { "/media/file_$it.mpg" }
}

/** Resolve portal-relative artwork without treating media commands as image addresses. */
internal fun posterAddress(apiHost: String, address: String): String? = try {
    val base = URI(apiHost)
    val resolved = base.resolve(address.trim())
    resolved.toString().takeIf {
        resolved.scheme in listOf("http", "https") && !resolved.host.isNullOrBlank() &&
            resolved.userInfo == null && resolved.fragment == null &&
            !(base.scheme == "https" && resolved.scheme == "http")
    }
} catch (_: Exception) { null }

/** The portal may return a full stream URL or a server-relative media path. */
internal fun playbackAddress(command: String, apiHost: String): String? = try {
    val raw = command.trim().let {
        when {
            it.startsWith("ffmpeg ") -> it.removePrefix("ffmpeg ").trim()
            it.startsWith("auto ") -> it.removePrefix("auto ").trim()
            else -> it
        }
    }
    val parsed = URI(raw)
    val base = URI(apiHost)
    val uri = if (parsed.isAbsolute) parsed else {
        if (!raw.startsWith("/") && (!raw.contains('/') || raw.startsWith("../"))) return null
        base.resolve(parsed)
    }
    uri.toString().takeIf {
        uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.fragment == null && (parsed.isAbsolute || base.scheme != "https" || uri.scheme == "https")
    }
} catch (_: Exception) { null }

private fun mediaCommandForm(command: String): String = when {
    command.isBlank() || command == "null" -> "missing"
    command.startsWith("http://") || command.startsWith("https://") -> "absolute"
    command.startsWith("/") -> "relative"
    command.startsWith("ffmpeg ") || command.startsWith("auto ") -> "prefixed"
    else -> "other"
}

private fun jsonValueType(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "missing"
    is String -> "string"
    is Number -> "number"
    is Boolean -> "boolean"
    is JSONObject -> "object"
    is JSONArray -> "array"
    else -> "other"
}

/** Fixed labels only: portal error messages may include credentials or media URLs. */
internal fun portalErrorKind(value: Any?): String {
    if (value == null || value == JSONObject.NULL) return "none"
    if (value !is String) return "nonText"
    val error = value.lowercase()
    return when {
        "not found" in error || "not exist" in error -> "notFound"
        "access" in error || "permission" in error || "denied" in error -> "access"
        "expire" in error || "session" in error || "token" in error -> "session"
        "limit" in error || "too many" in error -> "limit"
        "storage" in error -> "storage"
        "file" in error -> "file"
        "unavailable" in error || "not available" in error -> "unavailable"
        else -> "other"
    }
}
