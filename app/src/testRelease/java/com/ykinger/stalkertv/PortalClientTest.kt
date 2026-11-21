package com.ykinger.stalkertv

import com.ykinger.stalkertv.ui.PortalProfile
import com.ykinger.stalkertv.ui.portalRequest
import com.ykinger.stalkertv.ui.portalHeaders
import com.ykinger.stalkertv.ui.vodHeaders
import com.ykinger.stalkertv.ui.testPortal
import com.ykinger.stalkertv.ui.portalCounts
import com.ykinger.stalkertv.ui.PortalCounts
import com.ykinger.stalkertv.ui.VerifiedPortal
import com.ykinger.stalkertv.ui.StalkerContentRepository
import com.ykinger.stalkertv.ui.movieFileLinkCommand
import com.ykinger.stalkertv.ui.selectedEpisodeCommand
import com.ykinger.stalkertv.ui.posterAddress
import com.ykinger.stalkertv.ui.playbackAddress
import com.ykinger.stalkertv.ui.portalErrorKind
import com.ykinger.stalkertv.ui.scanVodCategoryKinds
import com.ykinger.stalkertv.ui.classifyVodCategory
import com.ykinger.stalkertv.ui.encodeCategoryKinds
import com.ykinger.stalkertv.ui.decodeCategoryKinds
import com.ykinger.stalkertv.ui.CapturedIdentity
import com.ykinger.stalkertv.ui.capturedHandshakeQuery
import com.ykinger.stalkertv.ui.capturedProfileQuery
import com.ykinger.stalkertv.ui.capturedHeaders
import com.ykinger.stalkertv.core.ContentKind
import com.ykinger.stalkertv.core.ProviderResult
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.runBlocking

/** JVM contract tests run in release so Android Log's debug stubs are never invoked. */
class PortalClientTest {
    @Test fun categoryScanClassifiesFirstPagesAndReportsProgress() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"js":[
                {"id":"*","title":"All"},{"id":"10","title":"Mixed"},
                {"id":"20","title":"Shows"},{"id":"30","title":"Empty"}]}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[
                {"is_series":"0"},{"is_series":"0"},{"is_series":"1"}]}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"is_series":"1"}]}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[]}}"""))
            val profile = PortalProfile("serial", server.url("/server/load.php").toString(),
                "00:1A:79:00:00:01")
            val verified = VerifiedPortal(profile.host, "demo-token", null)
            val progress = mutableListOf<Pair<Int, Int>>()

            val result = scanVodCategoryKinds(profile, verified, AppContainer()) { done, total ->
                progress += done to total
            }

            assertEquals(mapOf("10" to ContentKind.MOVIE, "20" to ContentKind.SERIES), result)
            assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), progress)
            assertEquals("get_categories", server.takeRequest().requestUrl?.queryParameter("action"))
            listOf("10", "20", "30").forEach { category ->
                val request = server.takeRequest().requestUrl!!
                assertEquals(category, request.queryParameter("category"))
                assertEquals("1", request.queryParameter("p"))
                assertEquals(null, request.queryParameter("is_series"))
            }
        } finally { server.shutdown() }
    }

    @Test fun categoryKindSerializationKeepsOnlyMovieAndShowLabels() {
        val encoded = encodeCategoryKinds(mapOf("10" to ContentKind.MOVIE,
            "20" to ContentKind.SERIES, "30" to ContentKind.CHANNEL))
        assertEquals(mapOf("10" to ContentKind.MOVIE, "20" to ContentKind.SERIES),
            decodeCategoryKinds(encoded))
        assertEquals(null, classifyVodCategory(JSONArray()))
        assertEquals(ContentKind.SERIES, classifyVodCategory(JSONArray(
            """[{"is_series":0},{"is_series":1}]""")))
    }

    @Test fun vodApiGzipIsDecompressedBeforeParsingCategories() {
        val server = MockWebServer()
        server.start()
        try {
            val body = ByteArrayOutputStream().also { bytes ->
                GZIPOutputStream(bytes).use { it.write("""{"js":[{"id":"11","title":"Movies"}]}""".toByteArray()) }
            }.toByteArray()
            server.enqueue(MockResponse().setBody(Buffer().write(body))
                .addHeader("Content-Encoding", "gzip").addHeader("Content-Type", "application/json"))
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            val result = portalRequest(profile, "type=vod&action=get_categories&JsHttpRequest=1-xml", "demo-token") as JSONArray
            assertEquals("Movies", result.getJSONObject(0).getString("title"))
            val request = server.takeRequest()
            assertEquals("gzip", request.getHeader("Accept-Encoding"))
            assertEquals(null, vodHeaders(profile, "demo-token")["Accept-Encoding"])
        } finally { server.shutdown() }
    }

    @Test fun catalogueSummaryUsesOnlyReportedFirstPageTotals() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"js":{"data":[],"total_items":"123"}}"""))
            server.enqueue(MockResponse().setBody("""{"js":[]}"""))
            server.enqueue(MockResponse().setBody("""{"js":[]}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[],"total_items":456}}"""))
            val profile = PortalProfile("", server.url("/c").toString(), "00:1A:79:00:00:01")
            val verified = VerifiedPortal(server.url("/server/load.php").toString(), "demo-token", null)
            assertEquals(PortalCounts(123, null), portalCounts(profile, verified, AppContainer()))
            assertEquals(PortalCounts(null, 456), portalCounts(profile, verified, AppContainer()))
            assertEquals(4, server.requestCount)
            repeat(2) {
                val tv = server.takeRequest().requestUrl!!
                assertEquals("/server/load.php", tv.encodedPath)
                assertEquals("itv", tv.queryParameter("type"))
                assertEquals("*", tv.queryParameter("genre"))
                assertEquals("1", tv.queryParameter("p"))
                val vod = server.takeRequest().requestUrl!!
                assertEquals("vod", vod.queryParameter("type"))
                assertEquals("*", vod.queryParameter("category"))
                assertEquals(null, vod.queryParameter("is_series"))
                assertEquals("1", vod.queryParameter("p"))
            }
        } finally { server.shutdown() }
    }

    @Test fun emptyLivePagePreservesCategoriesForSelectingASpecificGenre() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"js":[{"id":"42","title":"Example genre"}]}"""))
            server.enqueue(MockResponse().setBody("""{"js":[]}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"1","name":"Channel"}],"total_items":1}}"""))
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            val repository = StalkerContentRepository(profile, "demo-token")
            val all = (repository.loadPage(ContentKind.CHANNEL, null, 1) as ProviderResult.Success).value
            assertEquals(listOf("42"), all.categories.map { it.id })
            assertEquals(emptyList<Any>(), all.titles)
            assertEquals(false, all.hasMore)
            val selected = (repository.loadPage(ContentKind.CHANNEL, "42", 1) as ProviderResult.Success).value
            assertEquals("Channel", selected.titles.single().name)
            server.takeRequest()
            assertEquals("*", server.takeRequest().requestUrl?.queryParameter("genre"))
            assertEquals("42", server.takeRequest().requestUrl?.queryParameter("genre"))
        } finally { server.shutdown() }
    }

    @Test fun emptyVodPageIsNotAClassCastFailure() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"js":[{"id":"42","title":"Example"}]}"""))
            server.enqueue(MockResponse().setBody("""{"js":[]}"""))
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            val page = (StalkerContentRepository(profile, "demo-token")
                .loadPage(ContentKind.MOVIE, null, 1) as ProviderResult.Success).value
            assertEquals(1, page.categories.size)
            assertEquals(emptyList<Any>(), page.titles)
            assertEquals(false, page.hasMore)
            assertEquals(null, page.totalItems)
        } finally { server.shutdown() }
    }

    @Test fun movieSearchAndSortStayWithinRequestedCategoryAndPage() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"js":[]}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[],"total_items":0}}"""))
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            val page = (StalkerContentRepository(profile, "demo-token")
                .loadPage(ContentKind.MOVIE, "42", 1, "space hero", "rating") as ProviderResult.Success).value
            assertEquals(0, page.totalItems)
            server.takeRequest()
            val query = server.takeRequest().requestUrl!!
            assertEquals("42", query.queryParameter("category"))
            assertEquals("1", query.queryParameter("p"))
            assertEquals("space hero", query.queryParameter("search"))
            assertEquals("rating", query.queryParameter("sortby"))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun movieCategoryPageCarriesReportedVodTotal() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"js":[{"id":"42","title":"Movies"}]}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"1","name":"First","category_id":"42"}],"max_page_items":1,"total_items":"200"}}"""))
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            val page = (StalkerContentRepository(profile, "demo-token")
                .loadPage(ContentKind.MOVIE, "42", 1) as ProviderResult.Success).value
            assertEquals(200, page.totalItems)
            assertEquals(true, page.hasMore)
            assertEquals("get_categories", server.takeRequest().requestUrl?.queryParameter("action"))
            assertEquals("42", server.takeRequest().requestUrl?.queryParameter("category"))
        } finally { server.shutdown() }
    }

    @Test fun enteredBrowserUrlFallsBackOnlyAfterInvalidProfile() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val action = request.requestUrl?.queryParameter("action")
                return when {
                    request.path?.startsWith("/c?") == true && action == "handshake" ->
                        MockResponse().setBody("""{"js":{"token":"demo-token"}}""")
                    request.path?.startsWith("/c?") == true && action == "get_profile" ->
                        MockResponse().setBody("""{"js":{"status":2}}""")
                    action == "handshake" -> MockResponse().setBody("""{"js":{"token":"demo-token"}}""")
                    action == "get_profile" -> MockResponse().setBody("""{"js":{"id":"1"}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val address = server.url("/c").toString()
            val result = testPortal(PortalProfile("", address, "00:1A:79:00:00:01"), AppContainer())
            assertEquals(server.url("/stalker_portal/server/load.php").toString(), result.apiHost)
            assertEquals("/c?type=stb&action=handshake&token=&JsHttpRequest=1-xml", server.takeRequest().path)
            assertEquals("get_profile", server.takeRequest().requestUrl?.queryParameter("action"))
            assertEquals("/stalker_portal/server/load.php?type=stb&action=handshake&token=&JsHttpRequest=1-xml",
                server.takeRequest().path)
            assertEquals("get_profile", server.takeRequest().requestUrl?.queryParameter("action"))
            assertEquals(4, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun capturedDeviceIdentityMatchesSetupRequestShapeWithoutPersistingSecrets() {
        val identity = CapturedIdentity("00:1A:79:00:00:01", "sample-serial", "sample-adid",
            "sample-random", "sample-hw", "sample-handshake", "sample-profile")
        val handshake = ("http://example.test/?" + capturedHandshakeQuery(identity)).toHttpUrl()
        assertEquals(listOf("type", "action", "token", "prehash"), handshake.queryParameterNames.toList())
        assertEquals("", handshake.queryParameter("token"))
        assertEquals("sample-handshake", handshake.queryParameter("prehash"))

        val profile = ("http://example.test/?" + capturedProfileQuery(identity, 123456L)).toHttpUrl()
        assertEquals(listOf("type", "action", "ver", "sn", "stb_type", "client_type",
            "image_version", "device_id", "device_id2", "auth_second_step", "hw_version",
            "not_valid_token", "metrics", "hw_version_2", "timestamp", "api_signature",
            "prehash"), profile.queryParameterNames.toList())
        assertEquals("MAG270", profile.queryParameter("stb_type"))
        assertEquals("0.2.18", profile.queryParameter("image_version"))
        assertEquals("", profile.queryParameter("device_id"))
        assertEquals("", profile.queryParameter("device_id2"))
        assertEquals("123456", profile.queryParameter("timestamp"))
        assertEquals("sample-profile", profile.queryParameter("prehash"))
        assertEquals("sample-random", JSONObject(profile.queryParameter("metrics")!!).getString("random"))
        assertEquals("00:1A:79:00:00:01", JSONObject(profile.queryParameter("metrics")!!).getString("mac"))
        assertEquals("ImageDescription: 0.2.18-r22-pub-270; ImageDate: Tue Dec 19 11:33:53 EET 2017; PORTAL version: 5.6.1; API Version: JS API version: 328; STB API version: 134; Player Engine version: 0x566",
            profile.queryParameter("ver"))

        val request = capturedHeaders(Request.Builder().url("http://example.test/"), identity).build()
        assertEquals("mac=00:1A:79:00:00:01; timezone=America%2FMontreal; adid=sample-adid",
            request.header("Cookie"))
        assertEquals("Model: MAG270; Link: WiFi", request.header("X-User-Agent"))
        assertEquals("Keep-Alive", request.header("Connection"))
        assertEquals(null, request.header("Accept"))
        assertEquals(null, request.header("Authorization"))
    }

    @Test fun capturedRegistrationHeadersMatchHandshakeAndProfileOnWire() {
        val server = MockWebServer()
        server.start()
        try {
            val identity = CapturedIdentity("00:1A:79:00:00:01", "sample-serial", "sample-adid",
                "sample-random", "sample-hw", "sample-handshake", "sample-profile")
            val client = AppContainer().handshakeClient
            repeat(2) { index ->
                server.enqueue(MockResponse().setBody("""{"js":{"id":"1"}}"""))
                val builder = Request.Builder().url(server.url("/server/load.php"))
                capturedHeaders(builder, identity)
                if (index == 1) builder.header("Authorization", "Bearer sample-token")
                client.newCall(builder.build()).execute().use { assertEquals(200, it.code) }
                val wire = server.takeRequest()
                assertEquals("Keep-Alive", wire.getHeader("Connection"))
                assertEquals("gzip", wire.getHeader("Accept-Encoding"))
                assertEquals("Model: MAG270; Link: WiFi", wire.getHeader("X-User-Agent"))
                assertEquals("mac=00:1A:79:00:00:01; timezone=America%2FMontreal; adid=sample-adid",
                    wire.getHeader("Cookie"))
                assertEquals(if (index == 1) "Bearer sample-token" else null,
                    wire.getHeader("Authorization"))
                assertEquals(null, wire.getHeader("Referrer"))
                assertEquals(null, wire.getHeader("Referer"))
            }
        } finally { server.shutdown() }
    }

    @Test fun vodBrowserHeadersUseApiOriginWithoutHardcodingStreamHost() {
        val profile = PortalProfile("", "http://example.test/stalker_portal/server/load.php", "00:1A:79:00:00:01")
        val headers = vodHeaders(profile, "demo-token")
        assertEquals("http://example.test/stalker_portal/c/", headers["Referer"])
        assertEquals(null, headers["Referrer"])
        assertEquals(null, headers["Host"])
        assertEquals(null, headers["Accept-Encoding"]) // OkHttp adds gzip and decompresses the response.
        assertEquals("Bearer demo-token", headers["Authorization"])
        assertEquals(null, vodHeaders(profile.copy(host = "http://example.test/custom.php"), "demo-token")["Referer"])
    }

    @Test fun sharesConnectionPoolAndPreservesAuthenticationThroughSameServerRedirect() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/server/load.php"))
            server.enqueue(MockResponse().setBody("""{"js":{"token":"demo"}}"""))
            val container = AppContainer()
            assertEquals(container.portalClient.connectionPool, container.handshakeClient.connectionPool)
            val profile = PortalProfile("", server.url("/portal.php").toString(), "00:1A:79:00:00:01")
            val result = portalRequest(profile, "type=stb&action=handshake", "demo-token", container) as JSONObject
            assertEquals("demo", result.getString("token"))
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("/portal.php?type=stb&action=handshake", first.path)
            assertEquals("/server/load.php", second.path)
            assertEquals("Bearer demo-token", second.getHeader("Authorization"))
            assertEquals(first.getHeader("Cookie"), second.getHeader("Cookie"))
            portalHeaders(profile, "demo-token").forEach { (name, value) ->
                assertEquals(value, second.getHeader(name))
            }
        } finally { server.shutdown() }
    }

    @Test fun rateLimitIsNotAutomaticallyRetried() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(429))
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            assertThrows(IllegalStateException::class.java) {
                portalRequest(profile, "type=stb&action=get_profile", "demo-token")
            }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun posterAddressesResolvePortalPathsWithoutDowngradingHttps() {
        val base = "https://example.test/stalker_portal/server/load.php"
        assertEquals("https://example.test/stalker_portal/misc/poster.jpg",
            posterAddress(base, "/stalker_portal/misc/poster.jpg"))
        assertEquals("https://example.test/stalker_portal/server/poster.jpg",
            posterAddress(base, "poster.jpg"))
        assertEquals(null, posterAddress(base, "http://example.test/poster.jpg"))
        assertEquals(null, posterAddress(base, "javascript:alert(1)"))
    }

    @Test fun playbackCommandsAcceptFullUrlsOrMediaPathsButNotOpaqueCommands() {
        val base = "https://example.test/stalker_portal/server/load.php"
        assertEquals("https://cdn.example.test/movie/123.m3u8?key=sample",
            playbackAddress("ffmpeg https://cdn.example.test/movie/123.m3u8?key=sample", base))
        assertEquals("https://example.test/media/movie/123.m3u8",
            playbackAddress("/media/movie/123.m3u8", base))
        assertEquals("https://example.test/stalker_portal/server/media/123.m3u8",
            playbackAddress("auto media/123.m3u8", base))
        assertEquals(null, playbackAddress("123", base))
        assertEquals(null, playbackAddress("ffmpeg http://", base))
        assertEquals(null, playbackAddress("/../../media/123.m3u8", "invalid"))
        assertEquals(null, playbackAddress("http://user:secret@example.test/media/123.m3u8", base))
    }

    @Test fun portalErrorsAreCategorizedWithoutReturningTheirText() {
        assertEquals("notFound", portalErrorKind("Media not found: private details"))
        assertEquals("access", portalErrorKind("Access denied for device"))
        assertEquals("other", portalErrorKind("Unrecognized provider error"))
        assertEquals("none", portalErrorKind(null))
    }

    @Test fun moviePlaybackUsesSelectedMovieFileIdWithObservedCreateLinkShape() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"js":[{"id":"*","title":"All"}]}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"101","name":"Movie","cmd":"/media/101.mpg","path":"storage/101.mpg"},{"id":"102","name":"Other Movie","cmd":"/media/102.mpg"}],"total_items":2}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"501","video_id":"101","is_file":1,"url":"https://cdn.example.test/other.m3u8","cmd":"https://cdn.example.test/other.m3u8","path":"storage/other.mpg"}],"total_items":1}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"cmd":"https://cdn.example.test/101.m3u8"}}"""))
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            val repository = StalkerContentRepository(profile, "demo-token")
            val movie = (repository.loadPage(ContentKind.MOVIE, null, 1) as ProviderResult.Success).value.titles.first()
            val source = (repository.resolve(movie.id) as ProviderResult.Success).value
            assertEquals("https://cdn.example.test/101.m3u8", source.uri)
            assertEquals(mapOf("User-Agent" to "Lavf53.32.100", "Connection" to "Keep-Alive",
                "Accept-Encoding" to "gzip"), source.headers)
            assertEquals(null, source.headers["Authorization"])
            assertEquals(null, source.headers["Cookie"])
            assertEquals(null, source.headers["Referer"])
            assertEquals(null, source.headers["Host"])
            assertEquals("gzip", source.headers["Accept-Encoding"])
            assertEquals(4, server.requestCount)
            server.takeRequest()
            server.takeRequest()
            val detail = server.takeRequest().requestUrl!!
            assertEquals(listOf("type", "action", "movie_id"), detail.queryParameterNames.toList())
            assertEquals("get_ordered_list", detail.queryParameter("action"))
            assertEquals("101", detail.queryParameter("movie_id"))
            val linkRequest = server.takeRequest()
            val query = linkRequest.requestUrl!!
            assertEquals(server.hostName + ":" + server.port, linkRequest.getHeader("Host"))
            assertEquals(null, linkRequest.getHeader("Referer"))
            assertEquals(null, linkRequest.getHeader("Referrer"))
            assertEquals("gzip", linkRequest.getHeader("Accept-Encoding"))
            assertEquals(listOf("type", "action", "cmd", "force_ch_link_check"), query.queryParameterNames.toList())
            assertEquals("vod", query.queryParameter("type"))
            assertEquals("create_link", query.queryParameter("action"))
            assertEquals("/media/file_501.mpg", query.queryParameter("cmd"))
            assertEquals("0", query.queryParameter("force_ch_link_check"))
            assertEquals(null, query.queryParameter("series"))
            assertEquals("Bearer demo-token", linkRequest.getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun movieWithoutFileRowUsesCatalogueCommandAndOneLinkCall() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"js":[]}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"101","name":"Movie","cmd":"/media/101.mpg"}],"total_items":1}}"""))
            server.enqueue(MockResponse().setBody("""{"js":false}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"error":"nothing_to_play","cmd":""}}"""))
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            val repository = StalkerContentRepository(profile, "demo-token")
            val movie = (repository.loadPage(ContentKind.MOVIE, null, 1) as ProviderResult.Success).value.titles.single()
            assertEquals(ProviderResult.Unsupported, repository.resolve(movie.id))
            assertEquals(4, server.requestCount)
            server.takeRequest()
            server.takeRequest()
            assertEquals("101", server.takeRequest().requestUrl!!.queryParameter("movie_id"))
            val request = server.takeRequest()
            val query = request.requestUrl!!
            assertEquals(listOf("type", "action", "cmd", "force_ch_link_check"), query.queryParameterNames.toList())
            assertEquals("vod", query.queryParameter("type"))
            assertEquals("create_link", query.queryParameter("action"))
            assertEquals("/media/101.mpg", query.queryParameter("cmd"))
            assertEquals("0", query.queryParameter("force_ch_link_check"))
            assertEquals(null, query.queryParameter("series"))
            assertEquals(null, request.getHeader("X-User-Agent"))
            assertEquals("Bearer demo-token", request.getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun fileCommandRequiresOneMarkedFileBelongingToSelectedMovie() {
        fun file(json: String) = movieFileLinkCommand(JSONObject(json), "101")
        assertEquals(null, file("""{"data":[{"id":"501","video_id":"102","is_file":1}]}"""))
        assertEquals(null, file("""{"data":[{"id":"501","video_id":"101","cmd":"/media/501.mpg"}]}"""))
        assertEquals(null, file("""{"data":[{"id":"file-501","video_id":"101","is_file":1}]}"""))
        assertEquals(null, file("""{"data":[{"id":"501","video_id":"101","is_file":1},{"id":"502","video_id":"101","is_file":1}]}"""))
        assertEquals("/media/file_501.mpg", file("""{"data":[{"id":"501","video_id":"101","is_file":true,"path":"storage/other.mpg"}]}"""))
    }

    @Test fun liveTvPlaybackKeepsExistingRequestFlags() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"js":[{"id":"*","title":"All"}]}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"1","name":"Channel","cmd":"ffmpeg http://localhost/ch/1"}],"total_items":1}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"cmd":"ffmpeg https://example.test/live.m3u8"}}"""))
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            val repository = StalkerContentRepository(profile, "demo-token")
            val channel = (repository.loadPage(ContentKind.CHANNEL, null, 1) as ProviderResult.Success).value.titles.single()
            assertEquals("https://example.test/live.m3u8",
                (repository.resolve(channel.id) as ProviderResult.Success).value.uri)
            server.takeRequest()
            server.takeRequest()
            val request = server.takeRequest()
            val query = request.requestUrl!!
            assertEquals("0", query.queryParameter("series"))
            assertEquals("undefined", query.queryParameter("forced_storage"))
            assertEquals("0", query.queryParameter("disable_ad"))
            assertEquals("0", query.queryParameter("download"))
            assertEquals("1-xml", query.queryParameter("JsHttpRequest"))
            assertEquals(null, request.getHeader("X-User-Agent"))
        } finally { server.shutdown() }
    }

    @Test fun movieAndShowUsePicBeforeScreenshot() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"js":[{"id":"*","title":"All"}]}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"1","name":"Film","pic":"/posters/film.jpg","screenshot_uri":"/frames/film.jpg"},{"id":"2","name":"Show","is_series":1,"pic":"","screenshot_uri":"/frames/show.jpg"}],"total_items":2}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"1","name":"Film","pic":"/posters/film.jpg","screenshot_uri":"/frames/film.jpg"},{"id":"2","name":"Show","is_series":1,"pic":"","screenshot_uri":"/frames/show.jpg"}],"total_items":2}}"""))
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            val repository = StalkerContentRepository(profile, "demo-token")
            val movie = (repository.loadPage(ContentKind.MOVIE, null, 1) as ProviderResult.Success).value.titles.single()
            val show = (repository.loadPage(ContentKind.SERIES, null, 1) as ProviderResult.Success).value.titles.single()
            assertEquals(server.url("/posters/film.jpg").toString(), movie.artworkKey)
            assertEquals(server.url("/frames/show.jpg").toString(), show.artworkKey)
        } finally { server.shutdown() }
    }

    @Test fun allMovieAndShowPagesShareCategoriesAndFilterMixedRowsLocally() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"js":[{"id":"*","title":"All"}]}"""))
            repeat(2) { server.enqueue(MockResponse().setBody(
                """{"js":{"data":[{"id":"1","name":"Film","is_series":0},{"id":"2","name":"Show","is_series":1}],"total_items":200,"max_page_items":14}}""")) }
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            val repository = StalkerContentRepository(profile, "demo-token")
            val movies = (repository.loadPage(ContentKind.MOVIE, null, 1) as ProviderResult.Success).value
            val shows = (repository.loadPage(ContentKind.SERIES, null, 1) as ProviderResult.Success).value
            assertEquals(listOf("Film"), movies.titles.map { it.name })
            assertEquals(listOf("Show"), shows.titles.map { it.name })
            assertEquals(null, movies.totalItems)
            assertEquals(null, shows.totalItems)
            assertEquals(true, movies.hasMore)
            assertEquals(true, shows.hasMore)
            assertEquals(3, server.requestCount)
            assertEquals("/server/load.php?type=vod&action=get_categories&JsHttpRequest=1-xml", server.takeRequest().path)
            assertEquals("/server/load.php?type=vod&action=get_ordered_list&category=*&p=1&JsHttpRequest=1-xml",
                server.takeRequest().path)
            assertEquals("/server/load.php?type=vod&action=get_ordered_list&category=*&p=1&JsHttpRequest=1-xml",
                server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test fun selectedShowCategorySendsSeriesFilterWithOrdering() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"js":[{"id":"42","title":"Hindi | Comedy"}]}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"201","name":"Example","is_series":1}],"total_items":1}}"""))
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            val repository = StalkerContentRepository(profile, "demo-token")
            assertEquals(1, (repository.loadPage(ContentKind.SERIES, "42", 1, "", "added")
                as ProviderResult.Success).value.titles.size)
            server.takeRequest() // categories, cached for subsequent pages
            assertEquals("/server/load.php?type=vod&action=get_ordered_list&category=42&p=1&is_series=1&sortby=added&JsHttpRequest=1-xml",
                server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test fun selectedMovieCategorySendsSeriesFilterWithOrderingAndSearch() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"js":[{"id":"42","title":"Hindi | Comedy"}]}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"201","name":"Example","is_series":0}],"total_items":1}}"""))
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            val repository = StalkerContentRepository(profile, "demo-token")
            assertEquals(1, (repository.loadPage(ContentKind.MOVIE, "42", 1, "Hindi comedy", "added")
                as ProviderResult.Success).value.titles.size)
            server.takeRequest() // categories
            assertEquals("/server/load.php?type=vod&action=get_ordered_list&category=42&p=1&is_series=0&sortby=added&search=Hindi+comedy&JsHttpRequest=1-xml",
                server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test fun seriesUsesParentAndSeasonOrderedListsInsteadOfInventedSeasonsEndpoint() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"js":[{"id":"*","title":"All"}]}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"201","name":"Example Show","is_series":1,"cmd":"/media/201.mpg","series":[]}],"total_items":1}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"301","name":"Season 1","is_season":true,"season_number":1}],"total_items":1}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"401","name":"First Episode","cmd":"/media/401.mpg","is_episode":true,"pic":"/posters/401.jpg"}],"total_items":2,"max_page_items":1}}"""))
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            val repository = StalkerContentRepository(profile, "demo-token")
            val show = (repository.loadPage(ContentKind.SERIES, null, 1) as ProviderResult.Success).value.titles.single()
            val seasons = (repository.seasons(show.id) as ProviderResult.Success).value
            assertEquals(1, seasons.size)
            assertEquals(0, seasons.single().episodes.size)
            val episodes = (repository.episodes(show.id, seasons.single().id, 1) as ProviderResult.Success).value
            assertEquals("First Episode", episodes.episodes.single().name)
            assertEquals(server.url("/posters/401.jpg").toString(), episodes.episodes.single().artworkKey)
            assertEquals(true, episodes.hasMore)
            assertEquals(4, server.requestCount)
            server.takeRequest() // categories
            server.takeRequest() // initial VOD page
            val seasonRequest = server.takeRequest().requestUrl!!
            assertEquals("get_ordered_list", seasonRequest.queryParameter("action"))
            assertEquals("201", seasonRequest.queryParameter("movie_id"))
            assertEquals("0", seasonRequest.queryParameter("season_id"))
            val episodeRequest = server.takeRequest().requestUrl!!
            assertEquals("301", episodeRequest.queryParameter("season_id"))
        } finally { server.shutdown() }
    }

    @Test fun selectedEpisodeUsesOnlyItsReturnedCommand() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"js":[{"id":"*","title":"All"}]}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"201","name":"Show","is_series":1}],"total_items":1}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"301","name":"Season","is_season":true}],"total_items":1}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"401","name":"Episode"}],"total_items":1}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"501","video_id":"201","is_file":true,"cmd":"selected-command"}]}}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"cmd":"http://example.test/stream.m3u8"}}"""))
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            val repository = StalkerContentRepository(profile, "demo-token")
            val show = (repository.loadPage(ContentKind.SERIES, null, 1) as ProviderResult.Success).value.titles.single()
            val season = (repository.seasons(show.id) as ProviderResult.Success).value.single()
            val episode = (repository.episodes(show.id, season.id, 1) as ProviderResult.Success).value.episodes.single()
            assertEquals("http://example.test/stream.m3u8",
                (repository.resolve(episode.id) as ProviderResult.Success).value.uri)
            repeat(4) { server.takeRequest() }
            val detail = server.takeRequest().requestUrl!!
            assertEquals("201", detail.queryParameter("movie_id"))
            assertEquals("301", detail.queryParameter("season_id"))
            assertEquals("401", detail.queryParameter("episode_id"))
            val link = server.takeRequest().requestUrl!!
            assertEquals("/media/file_501.mpg", link.queryParameter("cmd"))
            assertEquals("0", link.queryParameter("force_ch_link_check"))
            assertEquals(null, link.queryParameter("series"))
        } finally { server.shutdown() }
        assertEquals(null, selectedEpisodeCommand(JSONObject("""{"data":[{"id":"402","cmd":"other-command"}]}"""), "401", "201"))
        assertEquals("/media/file_501.mpg", selectedEpisodeCommand(JSONObject("""{"data":[{"id":"501","video_id":"201","is_file":true,"cmd":"file-command"}]}"""), "401", "201"))
        assertEquals(null, selectedEpisodeCommand(JSONObject("""{"data":[{"id":"501","video_id":"202","is_file":true,"cmd":"other-show"}]}"""), "401", "201"))
        assertEquals(null, selectedEpisodeCommand(JSONObject("""{"data":[{"id":"501","video_id":"201","is_file":true,"cmd":"one"},{"id":"502","video_id":"201","is_file":true,"cmd":"two"}]}"""), "401", "201"))
    }

    @Test fun unsupportedSeriesResponseDoesNotBecomeAConnectionError() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"js":[{"id":"*","title":"All"}]}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"201","name":"Example Show","is_series":1}],"total_items":1}}"""))
            server.enqueue(MockResponse().setBody("""{"js":false}"""))
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            val repository = StalkerContentRepository(profile, "demo-token")
            val show = (repository.loadPage(ContentKind.SERIES, null, 1) as ProviderResult.Success).value.titles.single()
            assertEquals(ProviderResult.Unsupported, repository.seasons(show.id))
            assertEquals(3, server.requestCount)
        } finally { server.shutdown() }
    }
}
