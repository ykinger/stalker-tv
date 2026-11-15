package com.ykinger.stalkertv

import com.ykinger.stalkertv.ui.endpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortalAddressTest {
    @Test fun keepsBareHostWithoutAddingAnEndpoint() {
        assertEquals("http://example.test", endpoint("http://example.test"))
        assertEquals("http://example.test", endpoint("example.test"))
        assertEquals("https://example.test:8443", endpoint("https://example.test:8443"))
    }

    @Test fun rejectsPortalPathsInUserInput() {
        for (path in listOf("/portal.php", "/server/load.php", "/c/", "/stalker_portal/c/", "/")) {
            assertThrows(IllegalArgumentException::class.java) { endpoint("http://example.test$path") }
        }
    }

    @Test fun rejectsCredentialsAndQueryParametersInTheAddress() {
        assertThrows(IllegalArgumentException::class.java) { endpoint("http://user:pass@example.test") }
        assertThrows(IllegalArgumentException::class.java) { endpoint("http://example.test?token=private") }
        assertThrows(IllegalArgumentException::class.java) { endpoint("ftp://example.test") }
    }
}
