package com.ykinger.stalkertv

import com.ykinger.stalkertv.ui.PortalProfile
import com.ykinger.stalkertv.ui.portalRequest
import com.ykinger.stalkertv.ui.StalkerContentRepository
import com.ykinger.stalkertv.ui.posterAddress
import com.ykinger.stalkertv.core.ContentKind
import com.ykinger.stalkertv.core.ProviderResult
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.runBlocking

/** JVM contract tests run in release so Android Log's debug stubs are never invoked. */
class PortalClientTest {
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

    @Test fun movieAndShowUsePicBeforeScreenshot() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"js":[{"id":"*","title":"All"}]}"""))
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"1","name":"Film","pic":"/posters/film.jpg","screenshot_uri":"/frames/film.jpg"},{"id":"2","name":"Show","is_series":1,"pic":"","screenshot_uri":"/frames/show.jpg"}],"total_items":2}}"""))
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            val repository = StalkerContentRepository(profile, "demo-token")
            val titles = (repository.loadPage(ContentKind.MOVIE, null, 1) as ProviderResult.Success).value.titles
            assertEquals(server.url("/posters/film.jpg").toString(), titles[0].artworkKey)
            assertEquals(server.url("/frames/show.jpg").toString(), titles[1].artworkKey)
        } finally { server.shutdown() }
    }

    @Test fun movieAndShowPagesShareCategoriesWithinASession() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"js":[{"id":"*","title":"All"}]}"""))
            repeat(2) {
                server.enqueue(MockResponse().setBody(
                    """{"js":{"data":[{"id":"1","name":"Example","is_series":0}],"total_items":1,"max_page_items":14}}"""))
            }
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            val repository = StalkerContentRepository(profile, "demo-token")
            assertEquals(1, (repository.loadPage(ContentKind.MOVIE, null, 1) as ProviderResult.Success).value.categories.size)
            assertEquals(1, (repository.loadPage(ContentKind.SERIES, null, 1) as ProviderResult.Success).value.categories.size)
            assertEquals(3, server.requestCount)
            assertEquals("/server/load.php?type=vod&action=get_categories&JsHttpRequest=1-xml", server.takeRequest().path)
            assertEquals("/server/load.php?type=vod&action=get_ordered_list&category=*&p=1&JsHttpRequest=1-xml",
                server.takeRequest().path)
            assertEquals("/server/load.php?type=vod&action=get_ordered_list&category=*&p=1&JsHttpRequest=1-xml",
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
            server.enqueue(MockResponse().setBody("""{"js":{"data":[{"id":"401","name":"First Episode","cmd":"/media/401.mpg","is_episode":true}],"total_items":2,"max_page_items":1}}"""))
            val profile = PortalProfile("", server.url("/server/load.php").toString(), "00:1A:79:00:00:01")
            val repository = StalkerContentRepository(profile, "demo-token")
            val show = (repository.loadPage(ContentKind.SERIES, null, 1) as ProviderResult.Success).value.titles.single()
            val seasons = (repository.seasons(show.id) as ProviderResult.Success).value
            assertEquals(1, seasons.size)
            assertEquals(0, seasons.single().episodes.size)
            val episodes = (repository.episodes(show.id, seasons.single().id, 1) as ProviderResult.Success).value
            assertEquals("First Episode", episodes.episodes.single().name)
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
