package com.ykinger.stalkertv.ui

import com.ykinger.stalkertv.AppContainer
import com.ykinger.stalkertv.core.ContentKind
import com.ykinger.stalkertv.core.ProviderResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelSearchTest {
    private fun profile(host: String = "http://portal.example/c") = PortalProfile(
        serial = "serial", host = host, mac = "00:1A:79:00:00:01", name = "Test",
    )

    @Test fun channelIndexStoreRoundTripsAndRejectsCorruption() {
        val directory = Files.createTempDirectory("channel-index").toFile()
        try {
            val store = ChannelIndexStore(directory)
            val savedProfile = profile()
            val entries = listOf(
                ChannelIndexEntry("1", "AF AZAM CNN", "10", null, "ffmpeg /ch/1.ts"),
                ChannelIndexEntry("2", "AL | A2 CNN", "10", "http://portal/logo.png", null),
            )
            assertNull(store.load(savedProfile))
            store.save(savedProfile, entries)
            assertEquals(entries, store.load(savedProfile))

            val file = File(directory, "${playlistStorageKey(savedProfile)}.jsonl")
            file.writeText(file.readText().replace("\"count\":2", "\"count\":3"))
            assertNull(store.load(savedProfile))

            store.save(savedProfile, entries)
            store.remove(savedProfile)
            assertNull(store.load(savedProfile))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun failedAtomicWriteLeavesThePreviousIndexUntouched() {
        val directory = Files.createTempDirectory("channel-index").toFile()
        try {
            val target = File(directory, "index.jsonl")
            target.writeText("previous")
            assertThrows(IllegalStateException::class.java) {
                writeAtomically(target) { writer ->
                    writer.write("partial")
                    error("boom")
                }
            }
            assertEquals("previous", target.readText())
            assertFalse(File(directory, "index.jsonl.tmp").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun channelIndexStreamsCompleteListForMidNameSearch() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val rows = JSONArray()
                .put(JSONObject().put("id", "1").put("name", "AF AZAM CNN")
                    .put("tv_genre_id", "10").put("cmd", "ffmpeg /ch/1.ts"))
                .put(JSONObject().put("id", "2").put("name", "AL | A2 CNN")
                    .put("tv_genre_id", "10"))
                .put(JSONObject().put("id", "3").put("name", "BBC World").put("tv_genre_id", "20"))
            server.enqueue(MockResponse().setBody(
                JSONObject().put("js", JSONObject().put("data", rows)).toString()))
            val apiHost = server.url("/server/load.php").toString()
            val progress = mutableListOf<Int>()
            val entries = scanChannelIndex(profile(apiHost), VerifiedPortal(apiHost, "token", null),
                AppContainer()) { progress += it }

            assertEquals(3, entries.size)
            assertEquals(3, progress.last())
            assertTrue(server.takeRequest().path.orEmpty().contains("action=get_all_channels"))
            assertTrue(entries.any { it.name.contains("cnn", ignoreCase = true) })
        } finally {
            server.shutdown()
        }
    }

    @Test fun repositorySearchesIndexedChannelsAndSplitsServerVodResults() = runBlocking {
        val rows = JSONArray()
            .put(JSONObject().put("id", "101").put("name", "The Movie")
                .put("is_series", 0).put("cmd", "movie command"))
            .put(JSONObject().put("id", "202").put("name", "The Show")
                .put("is_series", 1).put("cmd", "series command"))
        val session = PortalSessionHolder(
            initialToken = "token",
            healthQuery = "",
            transport = { _, _ -> JSONObject().put("data", rows)
                .put("total_items", 2).put("max_page_items", 14) },
            refreshSession = { "token" },
        )
        val repository = StalkerContentRepository(profile(), session,
            initialChannelIndex = listOf(
                ChannelIndexEntry("1", "AF AZAM CNN", "10", null, "ffmpeg /ch/1.ts"),
                ChannelIndexEntry("2", "BBC World", "20", null, null),
            ))
        val channels = repository.searchChannels("cnn") as ProviderResult.Success
        assertEquals(listOf("1"), channels.value.titles.map { it.id })
        assertFalse(channels.value.hasMore)
        assertEquals(listOf("1", "2"), repository.navigationChannels(null).map { it.id })
        assertEquals(listOf("2"), repository.navigationChannels("20").map { it.id })

        val vod = repository.searchVod("the", 1) as ProviderResult.Success
        assertEquals(listOf("movie:101"), vod.value.titles.filter { it.kind == ContentKind.MOVIE }.map { it.id })
        assertEquals(listOf("series:202"), vod.value.titles.filter { it.kind == ContentKind.SERIES }.map { it.id })
        assertFalse(vod.value.hasMore)

        // Search results must be playable without first browsing their category.
        val movie = vod.value.titles.first { it.kind == ContentKind.MOVIE }
        assertEquals(movie, (repository.details(movie.id) as ProviderResult.Success).value)
        val channel = channels.value.titles.single()
        assertEquals("AF AZAM CNN", (repository.details(channel.id) as ProviderResult.Success).value.name)
    }
}
