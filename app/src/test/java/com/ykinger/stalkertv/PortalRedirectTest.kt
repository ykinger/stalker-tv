package com.ykinger.stalkertv

import com.ykinger.stalkertv.ui.portalRedirect
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortalRedirectTest {
    private val request = URI("http://example.test/?type=stb&action=handshake")

    @Test fun followsServersRelativePathAndExactQuery() {
        assertEquals("http://example.test/portal.php?type=stb&action=handshake",
            portalRedirect(request, "/portal.php?type=stb&action=handshake").toString())
        assertEquals("http://example.test/stalker_portal/c/",
            portalRedirect(request, "/stalker_portal/c/").toString())
    }

    @Test fun permitsHttpsUpgradeOnTheSameHost() {
        assertEquals("https://example.test/portal.php",
            portalRedirect(request, "https://example.test/portal.php#section").toString())
    }

    @Test fun doesNotForwardMacOrTokenToOtherServersOrPorts() {
        listOf("https://other.test/", "http://example.test:8080/",
            "https://user:pass@example.test/", "ftp://example.test/", null).forEach { location ->
            assertThrows(IllegalArgumentException::class.java) { portalRedirect(request, location) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            portalRedirect(URI("https://example.test/"), "http://example.test/")
        }
    }
}
