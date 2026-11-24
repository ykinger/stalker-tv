package com.ykinger.stalkertv.ui

import android.content.Context
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.ykinger.stalkertv.AppContainer
import com.ykinger.stalkertv.core.ContentKind
import com.ykinger.stalkertv.core.Title
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Only the fields global search and playback need from the complete ITV list. */
internal data class ChannelIndexEntry(
    val id: String,
    val name: String,
    val categoryId: String,
    val artwork: String?,
    val command: String?,
) {
    fun toTitle(): Title = Title(id, name, ContentKind.CHANNEL, categoryId, artworkKey = artwork)
}

internal class ChannelIndexException(message: String) : IllegalStateException(message)

/**
 * Downloads every channel once and parses one row at a time. The raw ~17 MiB
 * response is never kept as a single string or object tree.
 */
internal suspend fun scanChannelIndex(profile: PortalProfile, verified: VerifiedPortal,
    container: AppContainer, onProgress: (count: Int) -> Unit = {}): List<ChannelIndexEntry> =
    withContext(Dispatchers.IO) {
        val apiProfile = profile.copy(host = verified.apiHost)
        val query = "type=itv&action=get_all_channels&genre=*&JsHttpRequest=1-xml"
        var address = URI("${apiProfile.host}?$query")
        val visited = mutableSetOf<URI>()
        onProgress(0)

        repeat(6) { hop ->
            check(visited.add(address)) { "Portal redirect loop." }
            val request = okhttp3.Request.Builder().url(address.toString()).apply {
                portalHeaders(apiProfile, verified.token).forEach { (name, value) -> header(name, value) }
            }.build()
            container.channelIndexClient.newCall(request).execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    check(hop < 5) { "Too many portal redirects." }
                    address = portalRedirect(address, response.header("Location"))
                    return@repeat
                }
                if (response.code != 200) throw PortalHttpException(response.code)
                val body = response.body ?: throw ChannelIndexException("Portal returned an empty channel list.")
                body.charStream().use { stream ->
                    val reader = JsonReader(stream)
                    val entries = parseChannelRows(reader, apiProfile.host, onProgress)
                    reader.close()
                    return@withContext entries
                }
            }
        }
        error("Too many portal redirects.")
    }

private suspend fun parseChannelRows(reader: JsonReader, apiHost: String,
    onProgress: (count: Int) -> Unit): List<ChannelIndexEntry> {
    val entries = LinkedHashMap<String, ChannelIndexEntry>()
    var foundData = false
    var seen = 0

    reader.beginObject()
    while (reader.hasNext()) {
        if (reader.nextName() != "js") {
            reader.skipValue()
            continue
        }
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() != "data") {
                reader.skipValue()
                continue
            }
            foundData = true
            reader.beginArray()
            while (reader.hasNext()) {
                currentCoroutineContext().ensureActive()
                readChannelRow(reader, apiHost)?.let { entries[it.id] = it }
                seen++
                if (seen == 1 || seen % 250 == 0) onProgress(entries.size)
            }
            reader.endArray()
        }
        reader.endObject()
    }
    reader.endObject()

    if (!foundData || entries.isEmpty()) {
        throw ChannelIndexException("Portal returned no channels for the search index.")
    }
    onProgress(entries.size)
    return entries.values.toList()
}

private fun readChannelRow(reader: JsonReader, apiHost: String): ChannelIndexEntry? {
    var id: String? = null
    var name: String? = null
    var categoryId = ""
    var artworkSource: String? = null
    var command: String? = null

    reader.beginObject()
    while (reader.hasNext()) {
        when (reader.nextName()) {
            "id" -> id = reader.readStringValue()
            "name" -> name = reader.readStringValue()
            "tv_genre_id", "category_id" -> categoryId = reader.readStringValue().orEmpty()
            "logo", "pic", "screenshot_uri" ->
                if (artworkSource.isNullOrBlank()) artworkSource = reader.readStringValue()
            "cmd" -> command = reader.readStringValue()
            else -> reader.skipValue()
        }
    }
    reader.endObject()

    val validId = id?.takeIf { it.isNotBlank() && it != "null" } ?: return null
    return ChannelIndexEntry(
        id = validId,
        name = name?.takeIf { it.isNotBlank() } ?: "Channel $validId",
        categoryId = categoryId,
        artwork = artworkSource?.let { posterAddress(apiHost, it) },
        command = command?.takeIf { it.isNotBlank() && it != "null" },
    )
}

private fun JsonReader.readStringValue(): String? = when (peek()) {
    JsonToken.NULL -> { nextNull(); null }
    JsonToken.STRING -> nextString()
    JsonToken.NUMBER -> nextString()
    JsonToken.BOOLEAN -> nextBoolean().toString()
    else -> { skipValue(); null }
}

private const val INDEX_FORMAT_VERSION = 1

/** App-private, per-playlist storage. The final file is replaced only after a complete write. */
internal class ChannelIndexStore(directory: File) {
    constructor(context: Context) : this(File(context.filesDir, "channel-index"))

    private val directory: File = directory

    private fun fileFor(profile: PortalProfile): File =
        File(directory, "${playlistStorageKey(profile)}.jsonl")

    fun load(profile: PortalProfile): List<ChannelIndexEntry>? {
        val file = fileFor(profile)
        if (!file.exists()) return null
        return try {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                val iterator = lines.iterator()
                if (!iterator.hasNext()) return null
                val header = JSONObject(iterator.next())
                if (header.optInt("version", -1) != INDEX_FORMAT_VERSION) return null
                val expected = header.optInt("count", -1)
                val entries = mutableListOf<ChannelIndexEntry>()
                while (iterator.hasNext()) {
                    val row = JSONObject(iterator.next())
                    val id = row.optString("id").takeIf { it.isNotBlank() } ?: return null
                    val name = row.optString("name").takeIf { it.isNotBlank() } ?: return null
                    entries += ChannelIndexEntry(
                        id = id,
                        name = name,
                        categoryId = row.optString("category"),
                        artwork = row.optString("artwork").ifBlank { null },
                        command = row.optString("command").ifBlank { null },
                    )
                }
                entries.takeIf { expected == it.size }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun save(profile: PortalProfile, entries: List<ChannelIndexEntry>) {
        directory.mkdirs()
        val target = fileFor(profile)
        writeAtomically(target) { writer ->
            writer.write(JSONObject()
                .put("version", INDEX_FORMAT_VERSION)
                .put("builtAt", System.currentTimeMillis())
                .put("count", entries.size).toString())
            writer.write("\n")
            entries.forEach { entry ->
                writer.write(JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("category", entry.categoryId)
                    .apply {
                        entry.artwork?.let { put("artwork", it) }
                        entry.command?.let { put("command", it) }
                    }.toString())
                writer.write("\n")
            }
        }
    }

    fun remove(profile: PortalProfile) {
        val file = fileFor(profile)
        file.delete()
        File(directory, "${file.name}.tmp").delete()
    }

    fun clear() {
        directory.deleteRecursively()
    }
}

/**
 * Writes beside the target and moves only after the stream is flushed and synced.
 * A failed write leaves the previous file exactly as it was.
 */
internal fun <T> writeAtomically(target: File, write: (Writer) -> T): T {
    val parent = requireNotNull(target.parentFile) { "Index file needs a parent directory." }
    parent.mkdirs()
    val temporary = File(parent, "${target.name}.tmp")
    if (temporary.exists()) check(temporary.delete()) { "Could not remove stale channel index temp file." }
    val result = arrayOfNulls<Any>(1)
    try {
        FileOutputStream(temporary).use { stream ->
            BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8)).use { writer ->
                result[0] = write(writer)
                writer.flush()
                stream.channel.force(true)
            }
        }
        Files.move(temporary.toPath(), target.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        @Suppress("UNCHECKED_CAST")
        return result[0] as T
    } finally {
        if (temporary.exists()) temporary.delete()
    }
}
