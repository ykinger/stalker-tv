package com.ykinger.stalkertv

import com.ykinger.stalkertv.core.Category
import com.ykinger.stalkertv.core.ContentKind
import com.ykinger.stalkertv.core.Title
import com.ykinger.stalkertv.ui.vodCategoryVisible
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodCategoriesTest {
    private fun visible(name: String, kind: ContentKind, titles: List<Title> = emptyList()) =
        vodCategoryVisible(Category("55", name), kind, titles)

    @Test fun clearlyLabeledCategoriesAppearInTheirSection() {
        assertFalse(visible("KIDS | TV SHOWS", ContentKind.MOVIE))
        assertTrue(visible("KIDS | TV SHOWS", ContentKind.SERIES))
        assertFalse(visible("ENGLISH MOVIES | LATEST", ContentKind.SERIES))
        assertTrue(visible("ENGLISH MOVIES | LATEST", ContentKind.MOVIE))
        assertFalse(visible("DOCUMENTARIES | SERIES", ContentKind.MOVIE))
        assertFalse(visible("ENGLISH | K-DRAMAS", ContentKind.MOVIE))
        assertTrue(visible("ENGLISH | K-DRAMAS", ContentKind.SERIES))
    }

    @Test fun mixedAndAmbiguousNamesRemainAvailable() {
        for (name in listOf("DOCUMENTARIES", "MOVIES & TV SHOWS", "KIDS", "All")) {
            assertTrue(visible(name, ContentKind.MOVIE))
            assertTrue(visible(name, ContentKind.SERIES))
        }
    }

    @Test fun observedTitlesOverrideAContradictoryLabel() {
        val movie = Title("movie:1", "An unexpected movie", ContentKind.MOVIE, "55")
        assertTrue(visible("KIDS | TV SHOWS", ContentKind.MOVIE, listOf(movie)))
        assertFalse(visible("KIDS | TV SHOWS", ContentKind.MOVIE,
            listOf(Title("movie:2", "Different category", ContentKind.MOVIE, "56"))))
    }
}
