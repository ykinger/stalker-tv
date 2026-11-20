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
        compose.onNode(hasContentDescription("Movies") and hasClickAction()).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Empty category") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Empty category") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNode(hasText("No Movies on this page")).assertExists()
    }

    @Test fun showsUseTheSameVodBrowseControlsAsMovies() {
        val repository = object : ContentRepository {
            override suspend fun catalogue() = ProviderResult.Success(Catalogue(
                listOf(Category("drama", "Drama")),
                listOf(Title("series:1", "A Show", ContentKind.SERIES, "drama"))))
            override suspend fun details(id: String): ProviderResult<Title> = ProviderResult.Unsupported
            override suspend fun seasons(seriesId: String): ProviderResult<List<Season>> = ProviderResult.Unsupported
            override suspend fun programmes(channelId: String): ProviderResult<List<Programme>> = ProviderResult.Unsupported
            override suspend fun resolve(id: String): ProviderResult<PlaybackSource> = ProviderResult.Unsupported
        }
        compose.setContent { ConnectedTvScreen(AppContainer(repository)) {} }
        compose.onNode(hasContentDescription("Shows") and hasClickAction()).performClick()
        compose.onNode(hasText("Drama") and hasClickAction()).performClick()
        compose.onNode(hasContentDescription("Search Shows")).assertExists()
        compose.onNode(hasContentDescription("Sort Shows")).assertExists()
        compose.onNodeWithText("1 / 1 shows").assertExists()
    }

    @Test fun showDetailsPresentEpisodesAsPosters() {
        val show = Title("series:1", "A Show", ContentKind.SERIES, "drama", artworkKey = "mountain")
        val episode = Title("episode:1", "Pilot", ContentKind.EPISODE, "season:1")
        val repository = object : ContentRepository {
            override suspend fun catalogue() = ProviderResult.Success(Catalogue(
                listOf(Category("drama", "Drama")), listOf(show)))
            override suspend fun details(id: String): ProviderResult<Title> = ProviderResult.Unsupported
            override suspend fun seasons(seriesId: String) = ProviderResult.Success(listOf(
                Season("season:1", show.id, 1, emptyList()),
                Season("season:2", show.id, 2, emptyList())))
            override suspend fun episodes(seriesId: String, seasonId: String, page: Int): ProviderResult<EpisodePage> {
                kotlinx.coroutines.delay(300)
                return ProviderResult.Success(EpisodePage(listOf(episode)))
            }
            override suspend fun programmes(channelId: String): ProviderResult<List<Programme>> = ProviderResult.Unsupported
            override suspend fun resolve(id: String): ProviderResult<PlaybackSource> = ProviderResult.Unsupported
        }
        compose.setContent { ConnectedTvScreen(AppContainer(repository)) {} }
        compose.onNode(hasContentDescription("Shows") and hasClickAction()).performClick()
        compose.onNode(hasText("Drama") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("A Show").performClick()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Episode 1: Pilot").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Season 1").assertExists()
        compose.onAllNodesWithContentDescription("Episode 1: Pilot")[0].assertHasClickAction()
        compose.onNodeWithText("Back to Shows").assertDoesNotExist()
        compose.onNodeWithText("A Show").assertIsDisplayed()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Play S1 E1").assertIsFocused()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithContentDescription("Show details").assertIsFocused()
        compose.onNodeWithText("A Show").assertIsDisplayed()
    }
}
