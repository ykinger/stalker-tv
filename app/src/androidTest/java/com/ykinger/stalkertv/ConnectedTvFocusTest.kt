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

    @Test fun tvCategoriesOpenWithRightAndLeftReturnsToSelectedCategory() {
        val requests = mutableListOf<String?>()
        val categories = listOf(Category("news", "News"), Category("sports", "Sports"))
        val repository = object : PagedContentRepository {
            override suspend fun catalogue() = loadPage(ContentKind.CHANNEL, null, 1)
            override suspend fun loadPage(kind: ContentKind, categoryId: String?, page: Int): ProviderResult<Catalogue> {
                requests += categoryId
                val channels = listOf(Title("1", "News Channel", ContentKind.CHANNEL, "news"),
                    Title("2", "Sports Channel", ContentKind.CHANNEL, "sports"))
                return ProviderResult.Success(Catalogue(categories,
                    channels.filter { categoryId == null || it.categoryId == categoryId }
                        .map { if (categoryId == null) it else it.copy(categoryId = "provider-other-genre") }))
            }
            override suspend fun details(id: String): ProviderResult<Title> = ProviderResult.Unsupported
            override suspend fun seasons(seriesId: String): ProviderResult<List<Season>> = ProviderResult.Unsupported
            override suspend fun programmes(channelId: String): ProviderResult<List<Programme>> = ProviderResult.Unsupported
            override suspend fun resolve(id: String): ProviderResult<PlaybackSource> =
                ProviderResult.Success(PlaybackSource("asset:///big_buck_bunny_sample.mp4", "Sample"))
        }
        compose.setContent { ConnectedTvScreen(AppContainer(repository)) {} }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("News").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("News").performClick()
        compose.waitUntil(5_000) { requests.lastOrNull() == "news" }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Expand TV preview").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("News Channel") and hasClickAction() and isFocused())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("News", substring = false) and hasClickAction()).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithContentDescription("TV").assertDoesNotExist()
        compose.onNodeWithText("Sports Channel").assertDoesNotExist()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP)
        compose.onNodeWithContentDescription("Expand TV preview").assertIsFocused()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithContentDescription("Expand TV preview").assertDoesNotExist()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Expand TV preview").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("News Channel") and hasClickAction() and isFocused())
                .fetchSemanticsNodes().isNotEmpty()
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("News", substring = false) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("News", substring = false) and hasClickAction()).assertIsFocused()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Sports").assertIsFocused()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.waitUntil(5_000) { requests.lastOrNull() == "sports" }
        compose.onAllNodesWithText("Sports Channel").assertCountEquals(2)
    }

    @Test fun selectingChannelKeepsGuidePositionAndFocus() {
        val repository = object : ContentRepository {
            override suspend fun catalogue() = ProviderResult.Success(Catalogue(
                listOf(Category("news", "News")),
                (1..25).map { Title("$it", "Channel $it", ContentKind.CHANNEL, "news") }))
            override suspend fun details(id: String): ProviderResult<Title> = ProviderResult.Unsupported
            override suspend fun seasons(seriesId: String): ProviderResult<List<Season>> = ProviderResult.Unsupported
            override suspend fun programmes(channelId: String): ProviderResult<List<Programme>> = ProviderResult.Unsupported
            override suspend fun resolve(id: String): ProviderResult<PlaybackSource> {
                kotlinx.coroutines.delay(650)
                return ProviderResult.Success(PlaybackSource("asset:///big_buck_bunny_sample.mp4", "Sample $id"))
            }
        }
        compose.setContent { ConnectedTvScreen(AppContainer(repository)) {} }
        compose.onNodeWithText("News").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Expand TV preview").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription("Expand TV preview") and hasClickAction())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Channel 1") and hasClickAction() and isFocused())
                .fetchSemanticsNodes().isNotEmpty()
        }
        val row = compose.onNode(hasText("Channel 18") and hasClickAction())
        var steps = 0
        while (compose.onAllNodes(hasText("Channel 18") and hasClickAction() and isFocused())
                .fetchSemanticsNodes().isEmpty() && steps < 25) {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            steps++
            org.junit.Assert.assertTrue("guide lost focus at Down $steps: " +
                compose.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.config.toString() },
                compose.onAllNodes(isFocused() and hasClickAction() and hasText("Channel", substring = true))
                    .fetchSemanticsNodes().isNotEmpty())
        }
        org.junit.Assert.assertTrue("focused after $steps Downs: " +
            compose.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.config.toString() },
            compose.onAllNodes(hasText("Channel 18") and hasClickAction() and isFocused())
                .fetchSemanticsNodes().isNotEmpty())
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Channel 18").fetchSemanticsNodes().size == 2
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasContentDescription("Expand TV preview") and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        org.junit.Assert.assertTrue("focused when preview ready: " +
            compose.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.config.toString() },
            compose.onAllNodes(hasText("Channel 18") and hasClickAction() and isFocused())
                .fetchSemanticsNodes().isNotEmpty())
        row.assertIsDisplayed()
        compose.onNode(hasContentDescription("Expand TV preview") and isFocused()).assertDoesNotExist()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Expand TV preview").fetchSemanticsNodes().isEmpty()
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Channel 18") and hasClickAction() and isFocused())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Channel 1", substring = false)).assertDoesNotExist()
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

    @Test fun changingShowCategoryNeverDisplaysPreviousPageWhileLoading() {
        val releaseCategory = kotlinx.coroutines.CompletableDeferred<Unit>()
        val requests = mutableListOf<String?>()
        val categories = listOf(Category("drama", "Drama"), Category("comedy", "Comedy"))
        val all = listOf(Title("series:1", "Drama Show", ContentKind.SERIES, "drama"),
            Title("series:2", "Comedy Show", ContentKind.SERIES, "comedy"))
        val repository = object : PagedContentRepository {
            override suspend fun catalogue() = loadPage(ContentKind.CHANNEL, null, 1)
            override suspend fun loadPage(kind: ContentKind, categoryId: String?, page: Int): ProviderResult<Catalogue> {
                if (kind != ContentKind.SERIES) return ProviderResult.Success(Catalogue(emptyList(), emptyList()))
                requests += categoryId
                if (categoryId == "drama") releaseCategory.await()
                return ProviderResult.Success(Catalogue(categories,
                    all.filter { categoryId == null || it.categoryId == categoryId }))
            }
            override suspend fun details(id: String): ProviderResult<Title> = ProviderResult.Unsupported
            override suspend fun seasons(seriesId: String): ProviderResult<List<Season>> = ProviderResult.Unsupported
            override suspend fun programmes(channelId: String): ProviderResult<List<Programme>> = ProviderResult.Unsupported
            override suspend fun resolve(id: String): ProviderResult<PlaybackSource> = ProviderResult.Unsupported
        }
        compose.setContent { ConnectedTvScreen(AppContainer(repository)) {} }
        compose.onNodeWithContentDescription("Shows").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("All Shows") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("All Shows") and hasClickAction()).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Comedy Show").fetchSemanticsNodes().isNotEmpty()
        }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Drama") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Drama") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { requests.lastOrNull() == "drama" }
        compose.onNodeWithText("Drama", substring = false).assertExists()
        compose.onNodeWithText("Loading Shows…").assertExists()
        compose.onNodeWithContentDescription("Comedy Show").assertDoesNotExist()
        compose.onNodeWithText("Retry", substring = false).assertDoesNotExist()
        releaseCategory.complete(Unit)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Drama Show").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Comedy Show").assertDoesNotExist()
        org.junit.Assert.assertEquals(listOf(null, "drama"), requests)
    }

    @Test fun failedShowCategoryOffersRetryAfterLoading() {
        val releaseCategory = kotlinx.coroutines.CompletableDeferred<Unit>()
        var attempts = 0
        val requests = mutableListOf<Pair<ContentKind, String?>>()
        val repository = object : PagedContentRepository {
            override suspend fun catalogue() = loadPage(ContentKind.CHANNEL, null, 1)
            override suspend fun loadPage(kind: ContentKind, categoryId: String?, page: Int): ProviderResult<Catalogue> {
                requests += kind to categoryId
                if (kind != ContentKind.SERIES) return ProviderResult.Success(Catalogue(emptyList(), emptyList()))
                if (categoryId == "drama") {
                    attempts++
                    if (attempts == 1) {
                        releaseCategory.await()
                        return ProviderResult.Failure("Could not load this category")
                    }
                }
                return ProviderResult.Success(Catalogue(listOf(Category("drama", "Drama")),
                    listOf(Title("series:1", "Drama Show", ContentKind.SERIES, "drama"))))
            }
            override suspend fun details(id: String): ProviderResult<Title> = ProviderResult.Unsupported
            override suspend fun seasons(seriesId: String): ProviderResult<List<Season>> = ProviderResult.Unsupported
            override suspend fun programmes(channelId: String): ProviderResult<List<Programme>> = ProviderResult.Unsupported
            override suspend fun resolve(id: String): ProviderResult<PlaybackSource> = ProviderResult.Unsupported
        }
        compose.setContent { ConnectedTvScreen(AppContainer(repository)) {} }
        compose.onNodeWithContentDescription("Shows").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Drama") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Drama") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { attempts == 1 }
        compose.onNodeWithText("Loading Shows…").assertExists()
        compose.onNodeWithText("Retry", substring = false).assertDoesNotExist()
        releaseCategory.complete(Unit)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("Retry", substring = false) and hasClickAction() and isFocused())
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Could not load this category").assertExists()
        org.junit.Assert.assertEquals("category should wait for Retry", 1, attempts)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        org.junit.Assert.assertTrue("requests after Retry: $requests", attempts >= 2)
        org.junit.Assert.assertTrue("result after retry: " +
            compose.onAllNodes(hasText("Loading Shows…") or hasText("No Shows on this page") or
                hasContentDescription("Drama Show")).fetchSemanticsNodes().map { it.config.toString() },
            compose.onAllNodesWithContentDescription("Drama Show").fetchSemanticsNodes().isNotEmpty())
        org.junit.Assert.assertEquals(2, attempts)
    }

    @Test fun movieSearchStaysFilteredWhenMovingToResult() = searchStaysFilteredWhenMovingToResult(ContentKind.MOVIE)

    @Test fun showSearchStaysFilteredWhenMovingToResult() = searchStaysFilteredWhenMovingToResult(ContentKind.SERIES)

    @OptIn(ExperimentalTestApi::class)
    private fun searchStaysFilteredWhenMovingToResult(kind: ContentKind) {
        val requests = mutableListOf<String>()
        val titles = listOf(Title("1", "Blue Title", kind, "drama"),
            Title("2", "Red Title", kind, "drama"))
        val repository = object : PagedContentRepository {
            override suspend fun catalogue() = loadPage(kind, null, 1)
            override suspend fun loadPage(kind: ContentKind, categoryId: String?, page: Int) =
                loadPage(kind, categoryId, page, "", "added")
            override suspend fun loadPage(kind: ContentKind, categoryId: String?, page: Int,
                search: String, sort: String): ProviderResult<Catalogue> {
                requests += search
                return ProviderResult.Success(Catalogue(listOf(Category("drama", "Drama")),
                    titles.filter { it.kind == kind && it.name.contains(search, ignoreCase = true) }))
            }
            override suspend fun details(id: String): ProviderResult<Title> = ProviderResult.Unsupported
            override suspend fun seasons(seriesId: String): ProviderResult<List<Season>> = ProviderResult.Unsupported
            override suspend fun programmes(channelId: String): ProviderResult<List<Programme>> = ProviderResult.Unsupported
            override suspend fun resolve(id: String): ProviderResult<PlaybackSource> = ProviderResult.Unsupported
        }
        val section = if (kind == ContentKind.MOVIE) "Movies" else "Shows"
        compose.setContent { ConnectedTvScreen(AppContainer(repository)) {} }
        compose.onNodeWithContentDescription(section).performClick()
        compose.onNode(hasText("Drama") and hasClickAction()).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("Search $section").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Search $section").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Blue")
        compose.waitUntil(5_000) {
            requests.lastOrNull() == "Blue" &&
                compose.onAllNodesWithContentDescription("Blue Title").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Red Title").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performKeyInput {
            keyDown(androidx.compose.ui.input.key.Key.DirectionDown)
            keyUp(androidx.compose.ui.input.key.Key.DirectionDown)
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Blue Title").assertExists()
        compose.onNodeWithContentDescription("Red Title").assertDoesNotExist()
        org.junit.Assert.assertTrue("focused after search Down: " +
            compose.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.config.toString() },
            compose.onAllNodes(hasContentDescription("Blue Title") and isFocused())
                .fetchSemanticsNodes().isNotEmpty())
        org.junit.Assert.assertEquals("Blue", requests.last())
        compose.onNodeWithContentDescription("Blue Title").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Blue Title").fetchSemanticsNodes().size >= 2
        }
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
