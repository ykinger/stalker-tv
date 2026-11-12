package com.ykinger.stalkertv.ui

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
    private val _scenario = MutableStateFlow(MockScenario.SUCCESS)
    val scenario = _scenario.asStateFlow()
    private val _state = MutableStateFlow<LoadState<Catalogue>>(LoadState.Loading)
    val state = _state.asStateFlow()
    private var request: Job? = null
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
        request =
            viewModelScope.launch {
                _state.value = LoadState.Loading
                _state.value = repository.catalogue().toLoadState()
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
