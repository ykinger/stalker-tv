package com.ykinger.stalkertv

import com.ykinger.stalkertv.core.*
import com.ykinger.stalkertv.ui.CatalogueViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogueViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun moviesAndShowsHaveSeparatePagesButReuseTheirOwnCache() {
        val calls = mutableListOf<Pair<ContentKind, String?>>()
        val repository = object : PagedContentRepository {
            override suspend fun catalogue() = ProviderResult.Unsupported
            override suspend fun loadPage(kind: ContentKind, categoryId: String?, page: Int): ProviderResult<Catalogue> {
                calls += kind to categoryId
                return ProviderResult.Success(Catalogue(listOf(Category("*", "All")),
                    listOf(Title("movie:1", "Demo", ContentKind.MOVIE, "1"))))
            }
            override suspend fun details(id: String): ProviderResult<Title> = ProviderResult.Unsupported
            override suspend fun seasons(seriesId: String): ProviderResult<List<Season>> = ProviderResult.Unsupported
            override suspend fun programmes(channelId: String): ProviderResult<List<Programme>> = ProviderResult.Unsupported
            override suspend fun resolve(id: String): ProviderResult<PlaybackSource> = ProviderResult.Unsupported
        }
        val model = CatalogueViewModel(AppContainer(repository))
        model.loadSection(ContentKind.MOVIE)
        model.loadSection(ContentKind.SERIES)
        model.loadSection(ContentKind.MOVIE)
        assertEquals(listOf(ContentKind.CHANNEL to null, ContentKind.MOVIE to null,
            ContentKind.SERIES to null), calls)
        model.loadSection(ContentKind.MOVIE, force = true)
        assertEquals(4, calls.size)
    }

    @Test fun allVodCanCrossAPageContainingOnlyTheOtherKind() {
        val pages = mutableListOf<Int>()
        val repository = object : PagedContentRepository {
            override suspend fun catalogue(): ProviderResult<Catalogue> = ProviderResult.Unsupported
            override suspend fun loadPage(kind: ContentKind, categoryId: String?, page: Int): ProviderResult<Catalogue> {
                if (kind != ContentKind.SERIES) return ProviderResult.Unsupported
                pages += page
                return ProviderResult.Success(when (page) {
                    1, 2 -> Catalogue(emptyList(), emptyList(), hasMore = true)
                    else -> Catalogue(emptyList(), listOf(
                        Title("series:1", "Found Show", ContentKind.SERIES, "42")), hasMore = false)
                })
            }
            override suspend fun details(id: String): ProviderResult<Title> = ProviderResult.Unsupported
            override suspend fun seasons(seriesId: String): ProviderResult<List<Season>> = ProviderResult.Unsupported
            override suspend fun programmes(channelId: String): ProviderResult<List<Programme>> = ProviderResult.Unsupported
            override suspend fun resolve(id: String): ProviderResult<PlaybackSource> = ProviderResult.Unsupported
        }
        val model = CatalogueViewModel(AppContainer(repository))
        model.loadSection(ContentKind.SERIES)
        model.loadNextPage()
        model.loadNextPage()
        assertEquals(listOf(1, 2, 3), pages)
        assertEquals(listOf("Found Show"),
            (model.state.value as com.ykinger.stalkertv.ui.LoadState.Ready).value.titles.map { it.name })
    }

    @Test fun staleAllShowsResponseCannotReplaceSelectedCategory() {
        val releaseAll = CompletableDeferred<Unit>()
        val repository = object : PagedContentRepository {
            override suspend fun catalogue(): ProviderResult<Catalogue> = ProviderResult.Unsupported
            override suspend fun loadPage(kind: ContentKind, categoryId: String?, page: Int): ProviderResult<Catalogue> {
                if (kind == ContentKind.CHANNEL)
                    return ProviderResult.Success(Catalogue(emptyList(), emptyList()))
                if (kind == ContentKind.SERIES && categoryId == null) {
                    withContext(NonCancellable) { releaseAll.await() }
                    return ProviderResult.Success(Catalogue(emptyList(), listOf(
                        Title("series:all", "Stale All Show", ContentKind.SERIES, "all"))))
                }
                return ProviderResult.Success(Catalogue(emptyList(), listOf(
                    Title("series:drama", "Drama Show", ContentKind.SERIES, "drama"))))
            }
            override suspend fun details(id: String): ProviderResult<Title> = ProviderResult.Unsupported
            override suspend fun seasons(seriesId: String): ProviderResult<List<Season>> = ProviderResult.Unsupported
            override suspend fun programmes(channelId: String): ProviderResult<List<Programme>> = ProviderResult.Unsupported
            override suspend fun resolve(id: String): ProviderResult<PlaybackSource> = ProviderResult.Unsupported
        }
        val model = CatalogueViewModel(AppContainer(repository))

        model.loadSection(ContentKind.SERIES)
        model.loadSection(ContentKind.SERIES, "drama")
        assertEquals(listOf("Drama Show"),
            (model.state.value as com.ykinger.stalkertv.ui.LoadState.Ready).value.titles.map { it.name })

        releaseAll.complete(Unit)
        assertEquals(listOf("Drama Show"),
            (model.state.value as com.ykinger.stalkertv.ui.LoadState.Ready).value.titles.map { it.name })
    }
}
