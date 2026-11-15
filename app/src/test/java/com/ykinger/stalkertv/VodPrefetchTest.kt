package com.ykinger.stalkertv

import com.ykinger.stalkertv.ui.nearVodEnd
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodPrefetchTest {
    @Test fun fetchesBeforeLastPosterIsVisible() {
        assertFalse(nearVodEnd(lastVisible = 5, visibleItems = 6, columns = 3, posters = 20))
        assertTrue(nearVodEnd(lastVisible = 13, visibleItems = 6, columns = 3, posters = 20))
    }

    @Test fun emptyOrUnmeasuredGridDoesNotFetch() {
        assertFalse(nearVodEnd(lastVisible = -1, visibleItems = 0, columns = 1, posters = 4))
        assertFalse(nearVodEnd(lastVisible = 0, visibleItems = 1, columns = 1, posters = 0))
    }
}
