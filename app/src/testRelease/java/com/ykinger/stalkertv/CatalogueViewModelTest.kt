package com.ykinger.stalkertv

import com.ykinger.stalkertv.core.*
import com.ykinger.stalkertv.ui.CatalogueViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    @Test fun switchingViewsOfVodDoesNotRefetchTheSamePage() {
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
        assertEquals(listOf(ContentKind.CHANNEL to null, ContentKind.MOVIE to null), calls)
        model.loadSection(ContentKind.MOVIE, force = true)
        assertEquals(3, calls.size)
    }
}
