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
                "Nothing here yet. Try another section."
            )
        ProviderResult.Unsupported ->
            LoadState.Message("This content is unavailable.")
        is ProviderResult.Failure -> LoadState.Message(message)
    }

class CatalogueViewModel(private val container: AppContainer) : ViewModel() {
    private data class LoadedPage(val catalogue: Catalogue, val page: Int)
    // Shows use a different server query; never reuse a Movies page for Shows.
    private data class PageKey(val kind: ContentKind, val category: String?, val search: String, val sort: String)
    private fun pageKey(section: ContentKind, category: String?, search: String, sort: String) =
        PageKey(section, category, search, sort)
    private val loadedPages = mutableMapOf<PageKey, LoadedPage>()
    private var displayedPage: Pair<PageKey, Catalogue>? = null
    private val _state = MutableStateFlow<LoadState<Catalogue>>(LoadState.Loading)
    val state = _state.asStateFlow()
    private var request: Job? = null
    private var nextPage: Job? = null
    private var requestVersion = 0L
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
        get() = container.repository

    /** A category change may compose before the new StateFlow value reaches the screen. */
    fun displaysPage(section: ContentKind, category: String?, query: String, ordering: String,
        catalogue: Catalogue): Boolean = repository !is PagedContentRepository ||
        displayedPage?.first == pageKey(section, category, query, ordering)

    init {
        refresh()
    }

    fun refresh() {
        request?.cancel()
        nextPage?.cancel()
        val version = ++requestVersion
        _loadingNext.value = false
        _pageFailed.value = false
        page = 1
        loadedPages.remove(pageKey(kind, categoryId, search, sort))
        displayedPage = null
        _state.value = LoadState.Loading
        val requested = pageKey(kind, categoryId, search, sort)
        request =
            viewModelScope.launch {
                val source = repository
                val loaded = if (source is PagedContentRepository) {
                    val result = source.loadPage(requested.kind, requested.category, 1,
                        requested.search, requested.sort)
                    if (version != requestVersion || requested != pageKey(kind, categoryId, search, sort))
                        return@launch
                    if (requested.kind != ContentKind.CHANNEL && requested.category == null &&
                        result is ProviderResult.Success && !result.value.hasMore)
                        _vodKinds.value = result.value.titles.map { it.kind }.toSet()
                    if (result is ProviderResult.Success)
                        displayedPage = requested to result.value
                    result.toLoadState()
                } else source.catalogue().toLoadState()
                if (version == requestVersion && requested == pageKey(kind, categoryId, search, sort))
                    _state.value = loaded
            }
    }

    fun loadSection(section: ContentKind, category: String? = null, force: Boolean = false,
        query: String = "", ordering: String = "") {
        if (repository !is PagedContentRepository) return
        if (!force && kind == section && categoryId == category && search == query && sort == ordering &&
            ((_state.value as? LoadState.Ready)?.value?.let {
                displaysPage(section, category, query, ordering, it)
            } == true || _state.value is LoadState.Message || request?.isActive == true)) return
        (_state.value as? LoadState.Ready)?.value?.let {
            displayedPage?.takeIf { displayed -> displayed.second === it }?.let { displayed ->
                loadedPages[displayed.first] = LoadedPage(it, page)
            }
        }
        kind = section
        categoryId = category
        search = query
        sort = ordering
        displayedPage = null
        val cached = if (force) null else loadedPages[pageKey(section, category, search, sort)]
        if (cached == null) refresh()
        else {
            request?.cancel()
            nextPage?.cancel()
            requestVersion++
            _loadingNext.value = false
            _pageFailed.value = false
            page = cached.page
            displayedPage = pageKey(section, category, search, sort) to cached.catalogue
            _state.value = LoadState.Ready(cached.catalogue)
        }
    }

    fun loadNextPage() {
        val source = repository as? PagedContentRepository ?: return
        val current = (_state.value as? LoadState.Ready)?.value ?: return
        if (!current.hasMore || _loadingNext.value) return
        val next = page + 1
        val version = requestVersion
        val requested = pageKey(kind, categoryId, search, sort)
        _loadingNext.value = true
        _pageFailed.value = false
        nextPage = viewModelScope.launch {
            try {
                when (val result = source.loadPage(requested.kind, requested.category, next,
                    requested.search, requested.sort)) {
                    is ProviderResult.Success -> {
                        if (version != requestVersion || requested != pageKey(kind, categoryId, search, sort))
                            return@launch
                        val latest = (_state.value as? LoadState.Ready)?.value ?: return@launch
                        val incoming = result.value
                        val merged = (latest.titles + incoming.titles).distinctBy { it.id }
                        val updated = latest.copy(titles = merged,
                            // An unfiltered All page can contain only the other
                            // VOD kind. Keep paging even when this page adds no rows.
                            hasMore = incoming.hasMore)
                        displayedPage = requested to updated
                        _state.value = LoadState.Ready(updated)
                        if (requested.kind != ContentKind.CHANNEL && requested.category == null && !incoming.hasMore)
                            _vodKinds.value = merged.map { it.kind }.toSet()
                        page = next
                    }
                    is ProviderResult.Failure -> {
                        if (version != requestVersion || requested != pageKey(kind, categoryId, search, sort))
                            return@launch
                        _pageFailed.value = true
                        if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG, "stage=catalogue.page failed=true page=$next")
                    }
                    else -> {
                        if (version != requestVersion || requested != pageKey(kind, categoryId, search, sort))
                            return@launch
                        val updated = current.copy(hasMore = false)
                        displayedPage = requested to updated
                        _state.value = LoadState.Ready(updated)
                    }
                }
            } finally {
                if (version == requestVersion && requested == pageKey(kind, categoryId, search, sort))
                    _loadingNext.value = false
            }
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
