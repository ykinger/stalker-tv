package com.ykinger.stalkertv

import com.ykinger.stalkertv.ui.PortalProfile
import com.ykinger.stalkertv.ui.portalRequest
import com.ykinger.stalkertv.ui.StalkerContentRepository
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
}
