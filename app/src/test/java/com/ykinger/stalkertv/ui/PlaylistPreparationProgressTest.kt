package com.ykinger.stalkertv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistPreparationProgressTest {
    @Test fun combinedProgressWaitsForBothScans() {
        assertEquals(0.05f, playlistPreparationProgress(true, true, 0, 0))
        assertEquals(0.45f, playlistPreparationProgress(true, true, 5, 10))
        assertEquals(0.9f, playlistPreparationProgress(false, true, 10, 10))
        assertEquals(0.5f, playlistPreparationProgress(true, false, 5, 10))
        assertNull(playlistPreparationProgress(false, false, 10, 10))
    }

    @Test fun combinedStatusKeepsBothCountsInOneLine() {
        assertEquals("5 of 10 categories scanned • 300 channels indexed",
            playlistPreparationDetail(true, true, 5, 10, 300))
        assertEquals("300 channels indexed",
            playlistPreparationDetail(false, true, 10, 10, 300))
    }
}
