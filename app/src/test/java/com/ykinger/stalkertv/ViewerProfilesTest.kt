package com.ykinger.stalkertv

import com.ykinger.stalkertv.ui.PortalProfile
import com.ykinger.stalkertv.ui.playlistsForViewer
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerProfilesTest {
    @Test fun viewerSeesGlobalAndOwnedPlaylistsOnly() {
        val global = PortalProfile("", "http://global.test", "00:1A:79:00:00:01",
            "Global", "global", null)
        val alice = PortalProfile("", "http://alice.test", "00:1A:79:00:00:02",
            "Alice", "alice", "viewer-alice")
        val bob = PortalProfile("", "http://bob.test", "00:1A:79:00:00:03",
            "Bob", "bob", "viewer-bob")

        assertEquals(listOf(global, alice),
            playlistsForViewer(listOf(global, alice, bob), "viewer-alice"))
        assertEquals(listOf(global, bob),
            playlistsForViewer(listOf(global, alice, bob), "viewer-bob"))
    }
}
