package com.ykinger.stalkertv

import com.ykinger.stalkertv.ui.nearVodEnd
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodNavigationTest {
    @Test fun horizontalRowFetchesMoreOnlyNearItsEnd() {
        assertFalse(nearVodEnd(3, 4, 1, 20))
        assertTrue(nearVodEnd(17, 4, 1, 20))
        assertFalse(nearVodEnd(-1, 0, 1, 20))
    }

    @Test fun emptyRowDoesNotFetchAnotherPage() {
        assertFalse(nearVodEnd(0, 1, 1, 0))
    }
}
