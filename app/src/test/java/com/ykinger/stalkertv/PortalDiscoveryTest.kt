package com.ykinger.stalkertv

import com.ykinger.stalkertv.ui.portalApiCandidates
import org.junit.Assert.assertEquals
import org.junit.Test

class PortalDiscoveryTest {
    @Test fun bareHostKeepsInputAndProbesKnownRoutesOnSameOrigin() {
        assertEquals(listOf("http://example.test/stalker_portal/server/load.php",
            "http://example.test", "http://example.test/portal.php",
            "http://example.test/server/load.php"),
            portalApiCandidates("http://example.test"))
    }

    @Test fun browserEntryPointProbesItsMatchingApiDirectory() {
        assertEquals(listOf("https://example.test/stalker_portal/c/",
            "https://example.test/stalker_portal/portal.php",
            "https://example.test/stalker_portal/server/load.php",
            "https://example.test/portal.php", "https://example.test/server/load.php"),
            portalApiCandidates("https://example.test/stalker_portal/c/"))
    }

    @Test fun rootBrowserEntryPointIsTriedBeforeDiscoveredRoutes() {
        assertEquals(listOf("http://example.test/c", "http://example.test/stalker_portal/server/load.php",
            "http://example.test/portal.php", "http://example.test/server/load.php"),
            portalApiCandidates("http://example.test/c"))
    }

    @Test fun explicitEndpointIsNotReplaced() {
        assertEquals(listOf("http://10.0.2.2:8080/server/load.php"),
            portalApiCandidates("http://10.0.2.2:8080/server/load.php"))
    }
}
