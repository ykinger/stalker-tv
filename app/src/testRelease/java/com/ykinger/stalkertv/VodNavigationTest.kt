package com.ykinger.stalkertv

import com.ykinger.stalkertv.ui.vodGridBottomEdge
import com.ykinger.stalkertv.ui.vodGridLeftEdge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodNavigationTest {
    @Test fun leftMovesToCategoriesOnlyFromFirstColumn() {
        assertTrue(vodGridLeftEdge(0, 4))
        assertTrue(vodGridLeftEdge(8, 4))
        assertFalse(vodGridLeftEdge(9, 4))
        assertFalse(vodGridLeftEdge(-1, 4))
    }

    @Test fun bottomStopsAtLastPosterRowWhenThereIsNoNextPage() {
        assertFalse(vodGridBottomEdge(7, 14, 4))
        assertTrue(vodGridBottomEdge(10, 14, 4))
        assertTrue(vodGridBottomEdge(13, 14, 4))
        assertFalse(vodGridBottomEdge(-1, 14, 4))
    }
}
