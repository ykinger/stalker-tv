package com.ykinger.stalkertv.ui

import android.util.Log
import com.ykinger.stalkertv.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ykinger.stalkertv.AppContainer
import com.ykinger.stalkertv.core.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>

    data class Ready<T>(val value: T) : LoadState<T>

    data class Message(val text: String) : LoadState<Nothing>
}

fun <T> ProviderResult<T>.toLoadState(): LoadState<T> =
    when (this) {
        is ProviderResult.Success -> LoadState.Ready(value)
        ProviderResult.Empty ->
            LoadState.Message(
                "Nothing here yet. Try another section or change the mock scenario in Settings."
            )
        ProviderResult.Unsupported ->
            LoadState.Message("This content is unavailable in the selected mock scenario.")
        is ProviderResult.Failure -> LoadState.Message(message)
    }

class CatalogueViewModel(private val container: AppContainer) : ViewModel() {
    private data class LoadedPage(val catalogue: Catalogue, val page: Int)
    // Movies and Shows are two views over the same VOD pages.
    private data class PageKey(val kind: ContentKind, val category: String?, val search: String, val sort: String)
    private fun pageKey(section: ContentKind, category: String?, search: String, sort: String) =
        PageKey(if (section == ContentKind.SERIES) ContentKind.MOVIE else section, category, search, sort)
    private val loadedPages = mutableMapOf<PageKey, LoadedPage>()
    private val _scenario = MutableStateFlow(MockScenario.SUCCESS)
    val scenario = _scenario.asStateFlow()
    private val _state = MutableStateFlow<LoadState<Catalogue>>(LoadState.Loading)
    val state = _state.asStateFlow()
    private var request: Job? = null
    private var nextPage: Job? = null
    private val _loadingNext = MutableStateFlow(false)
    val loadingNext = _loadingNext.asStateFlow()
    private val _pageFailed = MutableStateFlow(false)
    val pageFailed = _pageFailed.asStateFlow()
    private val _vodKinds = MutableStateFlow<Set<ContentKind>?>(null)
    val vodKinds = _vodKinds.asStateFlow()
    private var kind = ContentKind.CHANNEL
    private var categoryId: String? = null
    private var search = ""
    private var sort = ""
    private var page = 1
    val repository: ContentRepository
        get() = container.repository(_scenario.value)

    init {
        refresh()
    }

    fun setScenario(value: MockScenario) {
        _scenario.value = value
        refresh()
    }

    fun refresh() {
        request?.cancel()
        nextPage?.cancel()
        _loadingNext.value = false
        _pageFailed.value = false
        page = 1
        loadedPages.remove(pageKey(kind, categoryId, search, sort))
        _state.value = LoadState.Loading
        request =
            viewModelScope.launch {
                val source = repository
                _state.value = if (source is PagedContentRepository) {
                    val result = source.loadPage(kind, categoryId, 1, search, sort)
                    if (kind != ContentKind.CHANNEL && categoryId == null &&
                        result is ProviderResult.Success && !result.value.hasMore)
                        _vodKinds.value = result.value.titles.map { it.kind }.toSet()
                    result.toLoadState()
                } else source.catalogue().toLoadState()
            }
    }

    fun loadSection(section: ContentKind, category: String? = null, force: Boolean = false,
        query: String = "", ordering: String = "") {
        if (repository !is PagedContentRepository) return
        if (!force && kind == section && categoryId == category && search == query && sort == ordering &&
            (_state.value is LoadState.Ready || request?.isActive == true)) return
        (_state.value as? LoadState.Ready)?.value?.let {
            loadedPages[pageKey(kind, categoryId, search, sort)] = LoadedPage(it, page)
        }
        kind = section
        categoryId = category
        search = query
        sort = ordering
        val cached = if (force) null else loadedPages[pageKey(section, category, search, sort)]
        if (cached == null) refresh()
        else {
            request?.cancel()
            nextPage?.cancel()
            _loadingNext.value = false
            _pageFailed.value = false
            page = cached.page
            _state.value = LoadState.Ready(cached.catalogue)
        }
    }

    fun loadNextPage() {
        val source = repository as? PagedContentRepository ?: return
        val current = (_state.value as? LoadState.Ready)?.value ?: return
        if (!current.hasMore || _loadingNext.value) return
        val next = page + 1
        _loadingNext.value = true
        _pageFailed.value = false
        nextPage = viewModelScope.launch {
            try {
                when (val result = source.loadPage(kind, categoryId, next, search, sort)) {
                    is ProviderResult.Success -> {
                        val latest = (_state.value as? LoadState.Ready)?.value ?: return@launch
                        val incoming = result.value
                        val merged = (latest.titles + incoming.titles).distinctBy { it.id }
                        _state.value = LoadState.Ready(latest.copy(titles = merged,
                            hasMore = incoming.hasMore && merged.size > latest.titles.size))
                        if (kind != ContentKind.CHANNEL && categoryId == null && !incoming.hasMore)
                            _vodKinds.value = merged.map { it.kind }.toSet()
                        page = next
                    }
                    is ProviderResult.Failure -> {
                        _pageFailed.value = true
                        if (BuildConfig.DEBUG) Log.w(PORTAL_TAG, "stage=catalogue.page failed=true page=$next")
                    }
                    else -> _state.value = LoadState.Ready(current.copy(hasMore = false))
                }
            } finally { _loadingNext.value = false }
        }
    }
}

data class DetailData(val title: Title, val seasons: List<Season>, val programmes: List<Programme>)

class DetailViewModel(private val id: String, private val repository: ContentRepository) :
    ViewModel() {
    private val _state = MutableStateFlow<LoadState<DetailData>>(LoadState.Loading)
    val state = _state.asStateFlow()
    private var request: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        request?.cancel()
        request =
            viewModelScope.launch {
                _state.value = LoadState.Loading
                when (val result = repository.details(id)) {
                    is ProviderResult.Success -> {
                        val title = result.value
                        val seasons =
                            if (title.kind == ContentKind.SERIES)
                                (repository.seasons(id) as? ProviderResult.Success)?.value.orEmpty()
                            else emptyList()
                        val programmes =
                            if (title.kind == ContentKind.CHANNEL)
                                (repository.programmes(id) as? ProviderResult.Success)
                                    ?.value
                                    .orEmpty()
                            else emptyList()
                        _state.value = LoadState.Ready(DetailData(title, seasons, programmes))
                    }
                    else ->
                        _state.value =
                            when (result) {
                                ProviderResult.Empty ->
                                    LoadState.Message("This title is unavailable.")
                                ProviderResult.Unsupported ->
                                    LoadState.Message("Details are unsupported.")
                                is ProviderResult.Failure -> LoadState.Message(result.message)
                                else -> LoadState.Message("Unable to load details.")
                            }
                }
            }
    }
}

class PlaybackViewModel(private val id: String, private val repository: ContentRepository) :
    ViewModel() {
    private val _state = MutableStateFlow<LoadState<PlaybackSource>>(LoadState.Loading)
    val state = _state.asStateFlow()
    private var request: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        request?.cancel()
        request =
            viewModelScope.launch {
                _state.value = LoadState.Loading
                _state.value = repository.resolve(id).toLoadState()
            }
    }
}
