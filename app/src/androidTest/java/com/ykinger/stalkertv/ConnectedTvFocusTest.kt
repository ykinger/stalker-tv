package com.ykinger.stalkertv

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ykinger.stalkertv.core.*
import com.ykinger.stalkertv.ui.ConnectedTvScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectedTvFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectingCategoryWithoutVisibleMoviesDoesNotRequestUnattachedFocus() {
        val repository = object : ContentRepository {
            override suspend fun catalogue() = ProviderResult.Success(Catalogue(
                listOf(Category("empty", "Empty category")),
                listOf(Title("movie:1", "Other movie", ContentKind.MOVIE, "other"))))
            override suspend fun details(id: String): ProviderResult<Title> = ProviderResult.Unsupported
            override suspend fun seasons(seriesId: String): ProviderResult<List<Season>> = ProviderResult.Unsupported
            override suspend fun programmes(channelId: String): ProviderResult<List<Programme>> = ProviderResult.Unsupported
            override suspend fun resolve(id: String): ProviderResult<PlaybackSource> = ProviderResult.Unsupported
        }
        compose.setContent { ConnectedTvScreen(AppContainer(repository)) {} }
        compose.onNode(hasText("Movies") and hasClickAction()).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Empty category") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Empty category") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("No Movies on this page")).assertExists()
    }
}
