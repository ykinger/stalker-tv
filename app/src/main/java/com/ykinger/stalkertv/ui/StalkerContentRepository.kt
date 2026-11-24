package com.ykinger.stalkertv.ui

import android.util.Log
import com.ykinger.stalkertv.BuildConfig
import com.ykinger.stalkertv.core.*
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.util.Locale
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
    private val session: PortalSessionHolder,
    private val categoryKinds: Map<String, ContentKind> = emptyMap(),
    initialVodCategories: List<Category>? = null,
    private val onVodCategoriesLoaded: (List<Category>) -> Unit = {},
    initialChannelIndex: List<ChannelIndexEntry>? = null,
) : PagedContentRepository, VodCategoryKindSource, SearchContentRepository {
    private data class EpisodeReference(val seriesId: String, val seasonId: String,
        val episodeId: String, val seriesNumber: String?)
    override fun categoryKind(categoryId: String): ContentKind? = categoryKinds[categoryId]

    private var titles = emptyList<Title>()
    private var commands = emptyMap<String, String>()
    private var episodeParents = emptyMap<String, EpisodeReference>()
    private val channelIndex: List<ChannelIndexEntry> = initialChannelIndex.orEmpty()
    private val searchableChannels: List<Pair<ChannelIndexEntry, String>> =
        initialChannelIndex?.map { it to it.name.lowercase(Locale.ROOT) }.orEmpty()

    init {
        initialChannelIndex?.forEach { entry ->
            entry.command?.let { commands = commands + (entry.id to it) }
        }
    }

    private val categoriesMutex = Mutex()
    private val titlesMutex = Mutex()
    private val categoryCache = mutableMapOf<String, List<Category>>().apply {
        if (initialVodCategories != null) put("vod", initialVodCategories)
    }

    private suspend fun rememberTitles(newTitles: List<Title>) = titlesMutex.withLock {
        titles = (titles + newTitles).distinctBy { it.id }
    }

    private suspend fun rememberTitle(id: String): Title? = titlesMutex.withLock {
        titles.firstOrNull { it.id == id }
    }

    private suspend fun displayName(id: String): String =
        rememberTitle(id)?.name ?: channelIndex.firstOrNull { it.id == id }?.name ?: id

    private suspend fun isKnownChannel(id: String): Boolean =
        rememberTitle(id)?.kind == ContentKind.CHANNEL || channelIndex.any { it.id == id }

    private suspend fun <T> safely(stage: String, block: suspend () -> ProviderResult<T>): ProviderResult<T> =
        withContext(Dispatchers.IO) {
            try { block() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: PortalSessionExpiredException) {
                ProviderResult.Failure(PORTAL_SESSION_EXPIRED_MESSAGE)
            }
            catch (error: Exception) {
                if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG, "stage=$stage failure=${error.javaClass.simpleName}")
                ProviderResult.Failure("Portal request failed. Check the server or reconnect your profile.")
            }
        }

    override suspend fun catalogue(): ProviderResult<Catalogue> = loadPage(ContentKind.CHANNEL, null, 1)

    override suspend fun searchChannels(query: String, limit: Int): ProviderResult<SearchBatch> =
        safely("search.channels") {
            val term = query.trim()
            if (term.isBlank()) return@safely ProviderResult.Success(SearchBatch(emptyList()))
            if (searchableChannels.isEmpty()) return@safely ProviderResult.Failure(
                "Channel index is not available. Refresh the playlist to build it.")
            val lowered = term.lowercase(Locale.ROOT)
            val matches = searchableChannels.filter { it.second.contains(lowered) }
                .map { it.first.toTitle() }
            ProviderResult.Success(SearchBatch(matches.take(limit), matches.size > limit))
        }

    /** One mixed VOD query per page; TiviMate's duplicate movie/show request is unnecessary. */
    override suspend fun searchVod(query: String, page: Int): ProviderResult<SearchBatch> =
        safely("search.vod") {
            require(page in 1..250)
            val term = query.trim().ifBlank { return@safely ProviderResult.Success(SearchBatch(emptyList())) }
            val response = session.request(
                "type=vod&action=get_ordered_list&category=0&sortby=name" +
                    "&search=${URLEncoder.encode(term, "UTF-8")}&p=$page&JsHttpRequest=1-xml")
            val result = response as? JSONObject ?: return@safely ProviderResult.Unsupported
            val rows = result.optJSONArray("data") ?: return@safely ProviderResult.Unsupported
            val pageTitles = parseRows(rows, kindHint = null, categoryId = null)
            val total = result.optInt("total_items", -1)
            val pageSize = result.optInt("max_page_items", 14).takeIf { it > 0 } ?: 14
            val hasMore = rows.length() > 0 && page < 250 &&
                (if (total >= 0) page.toLong() * pageSize < total else rows.length() >= pageSize)
            ProviderResult.Success(SearchBatch(pageTitles, hasMore))
        }

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
                    val list = session.request("type=$type&action=$categoryAction&JsHttpRequest=1-xml") as JSONArray
                    (0 until list.length()).mapNotNull { index ->
                        val row = list.optJSONObject(index) ?: return@mapNotNull null
                        val id = row.optString("id").takeUnless { it.isBlank() || it == "null" } ?: return@mapNotNull null
                        Category(id, row.optString("title").ifBlank { "Other" })
                    }.distinctBy { it.id }.also {
                        categoryCache[type] = it
                        if (type == "vod") try {
                            onVodCategoriesLoaded(it)
                        } catch (error: Exception) {
                            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG,
                                "stage=catalogue.vod categoryCachePersist=${error.javaClass.simpleName}")
                        }
                    }
                }
            } else emptyList()
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG, "stage=catalogue.$type page=$page categories=${categories.size}")
            val category = URLEncoder.encode(categoryId ?: "*", "UTF-8")
            val filter = if (kind == ContentKind.CHANNEL) "genre" else "category"
            // The reference client leaves sortby empty for All. On this portal a
            // sorted All page contains only the newest kind (series), so Movies
            // would never receive a row it can display.
            val extras = if (kind == ContentKind.CHANNEL) "&sortby=number&fav=0&search=" else
                (if (categoryId == null) "" else if (kind == ContentKind.SERIES) "&is_series=1" else "&is_series=0") +
                    (if (sort.isBlank() || categoryId == null) "" else "&sortby=$sort") +
                    (if (search.isBlank()) "" else "&search=${URLEncoder.encode(search.trim(), "UTF-8")}")
            val response = session.request(
                "type=$type&action=get_ordered_list&$filter=$category&p=$page$extras&JsHttpRequest=1-xml")
            // Some portals return js:[] for an empty page instead of an object
            // containing data:[]. Keep the categories so users can select one.
            if (response is JSONArray && response.length() == 0) {
                if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                    "stage=catalogue.$type page=$page result=emptyArray categorySelected=${categoryId != null}")
                return@safely ProviderResult.Success(Catalogue(categories, emptyList(), false))
            }
            val result = response as? JSONObject ?: return@safely ProviderResult.Unsupported
            val rows = result.optJSONArray("data") ?: error("Portal page has no data array.")
            val pageTitles = parseRows(rows, kindHint = kind, categoryId = categoryId)
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

    /**
     * Shared row mapping for browsing and global VOD search. Search passes a null
     * hint because one server query mixes movies and shows; the UI splits them.
     */
    private suspend fun parseRows(rows: JSONArray, kindHint: ContentKind?,
        categoryId: String?): List<Title> {
        val parsed = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val rawId = row.optString("id").takeUnless { it.isBlank() || it == "null" } ?: return@mapNotNull null
            val isChannelRow = kindHint == ContentKind.CHANNEL
            val itemKind = if (isChannelRow) ContentKind.CHANNEL else if (
                row.optInt("is_series") == 1 || row.optBoolean("is_series") ||
                    row.optString("movie_type") == "series") ContentKind.SERIES else ContentKind.MOVIE
            val id = if (isChannelRow) rawId else
                "${if (itemKind == ContentKind.SERIES) "series" else "movie"}:$rawId"
            val command = row.optString("cmd").takeIf { it.isNotBlank() && it != "null" }
            val itemCategory = row.optString(if (isChannelRow) "tv_genre_id" else "category_id")
                .takeIf { it.isNotBlank() && it != "null" } ?: categoryId.orEmpty()
            val artwork = listOf("pic", "screenshot_uri").firstNotNullOfOrNull { field ->
                row.optString(field).takeUnless { it.isBlank() || it in setOf("null", "0", "false") }
                    ?.let { posterAddress(profile.host, it) }
            }
            val title = Title(id, row.optString("name").ifBlank { "Title $rawId" }, itemKind, itemCategory,
                row.optString("description").ifBlank { null }, artwork,
                row.optString("year").toIntOrNull())
            title to command
        }
        // All VOD categories are kind-specific, but an unfiltered All page is
        // mixed. Only the latter needs the requested kind applied locally.
        val visible = if (kindHint == null || kindHint == ContentKind.CHANNEL || categoryId != null) parsed else
            parsed.filter { it.first.kind == kindHint }
        titlesMutex.withLock {
            visible.forEach { (title, command) ->
                if (command != null) commands = commands + (title.id to command)
            }
            titles = (titles + visible.map { it.first }).distinctBy { it.id }
        }
        return visible.map { it.first }
    }

    override suspend fun resolve(id: String): ProviderResult<PlaybackSource> = safely("playback.resolve") {
        val episodeReference = episodeParents[id]
        // Episode-list rows may all carry the same show-level command. Always
        // resolve the selected episode through its episode-filtered detail page.
        val catalogueCommand = if (id.startsWith("episode:")) {
            val reference = episodeReference ?: return@safely ProviderResult.Empty
            val seriesId = reference.seriesId
            val seasonId = reference.seasonId
            val episodeId = reference.episodeId
            val detail = session.request(
                "type=vod&action=get_ordered_list&movie_id=${URLEncoder.encode(seriesId, "UTF-8")}" +
                    "&season_id=${URLEncoder.encode(seasonId, "UTF-8")}" +
                    "&episode_id=${URLEncoder.encode(episodeId, "UTF-8")}")
            val detailCommand = movieFileLinkCommand(detail, seriesId)
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                "stage=vod.episode_detail rows=${(detail as? JSONObject)?.optJSONArray("data")?.length() ?: -1} " +
                    "commandFound=${detailCommand != null}")
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) (detail as? JSONObject)?.optJSONArray("data")?.let { rows ->
                for (index in 0 until minOf(rows.length(), 3)) {
                    val row = rows.optJSONObject(index) ?: continue
                    Log.d(PORTAL_TAG, "stage=vod.episode_detail row=$index " +
                        "idNumeric=${row.optString("id").matches(Regex("[0-9]{1,20}"))} " +
                        "isFile=${row.optInt("is_file") == 1 || row.optBoolean("is_file")} " +
                        "videoMatchesSeries=${row.optString("video_id") == seriesId} " +
                        "hasPath=${row.has("path")} hasCmd=${row.has("cmd")}")
                }
            }
            detailCommand
        } else commands[id]
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
            val detail = session.request(
                "type=vod&action=get_ordered_list&movie_id=${URLEncoder.encode(movieId, "UTF-8")}")
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
        val episodeSeries = episodeReference?.seriesNumber
        if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
            "stage=playback.resolve source=${mediaCommandForm(command)} type=$type " +
                "fileCommand=${fileCommand != null} track=${episodeSeries ?: if (movie) "omitted" else "0"}")
        val query = "type=$type&action=create_link&cmd=${URLEncoder.encode(command, "UTF-8")}" +
            when {
                movie -> "&force_ch_link_check=0"
                fileCommand != null && episodeSeries != null ->
                    "&series=${URLEncoder.encode(episodeSeries, "UTF-8")}&force_ch_link_check=0"
                fileCommand != null -> "&force_ch_link_check=0"
                else -> "&series=${episodeSeries ?: "0"}" +
                    "&forced_storage=undefined&disable_ad=0&download=0&JsHttpRequest=1-xml"
            }
        if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING && type == "vod") Log.d(PORTAL_TAG,
            "stage=vod.create_link request=redacted source=${mediaCommandForm(command)} " +
                "options=${if (movieLinkOptions) "movie" else "series"}")
        val result = session.request(query) as JSONObject
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
        ProviderResult.Success(PlaybackSource(url, displayName(id),
            if (movieLinkOptions) mapOf("User-Agent" to "Lavf53.32.100", "Connection" to "Keep-Alive",
                "Accept-Encoding" to "gzip") else emptyMap()))
    }

    override suspend fun details(id: String): ProviderResult<Title> {
        val known = rememberTitle(id)
        if (known != null) return ProviderResult.Success(known)
        return channelIndex.firstOrNull { it.id == id }?.toTitle()
            ?.let { ProviderResult.Success(it) } ?: ProviderResult.Empty
    }

    override suspend fun seasons(seriesId: String): ProviderResult<List<Season>> = safely("catalogue.seasons") {
        if (rememberTitle(seriesId)?.kind != ContentKind.SERIES) return@safely ProviderResult.Empty
        val rawId = seriesId.removePrefix("series:")
        val seasons = mutableListOf<Season>()
        for (page in 1..10) {
            currentCoroutineContext().ensureActive()
            // The series row's cmd is a media command, not a detail URL. Stalker
            // exposes children through get_ordered_list with a parent movie_id.
            val result = session.request(
                    "type=vod&action=get_ordered_list&movie_id=${URLEncoder.encode(rawId, "UTF-8")}" +
                        "&p=$page") as? JSONObject
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
            if (page !in 1..250 || rememberTitle(seriesId)?.kind != ContentKind.SERIES)
                return@safely ProviderResult.Empty
            val rawId = URLEncoder.encode(seriesId.removePrefix("series:"), "UTF-8")
            val rawSeason = URLEncoder.encode(seasonId, "UTF-8")
            val result = session.request(
                "type=vod&action=get_ordered_list&movie_id=$rawId&season_id=$rawSeason" +
                    "&p=$page") as? JSONObject
                ?: return@safely ProviderResult.Unsupported
            val rows = result.optJSONArray("data") ?: return@safely ProviderResult.Unsupported
            val items = (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val rawEpisode = row.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val id = "episode:$rawEpisode"
                val seriesNumber = row.optString("series_number")
                    .takeIf { it.matches(Regex("[0-9]{1,6}")) && it != "0" }
                episodeParents = episodeParents + (id to EpisodeReference(
                    seriesId.removePrefix("series:"), seasonId, rawEpisode, seriesNumber))
                val artwork = listOf("pic", "screenshot_uri").firstNotNullOfOrNull { field ->
                    row.optString(field).takeUnless { it.isBlank() || it in setOf("null", "0", "false") }
                        ?.let { posterAddress(profile.host, it) }
                }
                Title(id, row.optString("name").ifBlank { "Episode ${row.optInt("series_number", index + 1)}" },
                    ContentKind.EPISODE, seasonId, artworkKey = artwork)
            }
            rememberTitles(items)
            val total = result.optInt("total_items", -1)
            val size = result.optInt("max_page_items", 14).takeIf { it > 0 } ?: 14
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                "stage=catalogue.episodes page=$page rows=${rows.length()}")
            ProviderResult.Success(EpisodePage(items, rows.length() > 0 && page < 250 &&
                (if (total >= 0) page.toLong() * size < total else rows.length() >= size)))
        }
    override suspend fun programmes(channelId: String): ProviderResult<List<Programme>> = safely("guide.programmes") {
        if (!isKnownChannel(channelId)) return@safely ProviderResult.Empty
        val encodedId = URLEncoder.encode(channelId, "UTF-8")
        val rows = try {
            (session.request("type=epg&action=get_simple_data_table&ch_id=$encodedId&JsHttpRequest=1-xml") as JSONObject)
                .getJSONArray("data")
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Older panels expose the channel guide via itv instead of epg.
            (session.request("type=itv&action=get_short_epg&ch_id=$encodedId&size=4") as JSONObject)
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

/** Movies and episodes both resolve create_link from the detail file object id. */
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
