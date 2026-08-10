package com.streamdek.tv.nativeapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.streamdek.tv.nativeapp.data.HomeContent
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class HomeScreenUiState(
    val isLoading: Boolean = true,
    val content: HomeContent? = null,
    val error: String? = null,
    val heroDetail: MediaDetail? = null,
)

class HomeViewModel(
    private val repository: StreamDekRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeScreenUiState())
    val uiState: StateFlow<HomeScreenUiState> = _uiState

    private var lastLoadKey: String? = null
    private var heroKey: String? = null
    private var heroDetailJob: Job? = null
    private var loadJob: Job? = null

    fun load(loadKey: String) {
        if (lastLoadKey == loadKey && (loadJob?.isActive == true || _uiState.value.content != null || _uiState.value.error != null)) {
            return
        }
        loadContent(loadKey, forceRefresh = false)
    }

    fun forceRefresh(loadKey: String) {
        loadContent(loadKey, forceRefresh = true)
    }

    private fun loadContent(loadKey: String, forceRefresh: Boolean) {
        lastLoadKey = loadKey
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val cachedContent = _uiState.value.content
            _uiState.value = _uiState.value.copy(
                isLoading = cachedContent == null,
                error = null,
            )
            // On a cold load each row is applied the moment it lands, so the screen fills in
            // progressively instead of waiting on the slowest source. A refresh over a screen that
            // is already populated swaps in one go instead: tearing rows out from under someone
            // who is mid-browse to rebuild them is worse than a moment of stale content.
            val progressive = cachedContent == null
            runCatching {
                repository.homeContentStream(forceRefresh = forceRefresh).collect { content ->
                    if (!progressive && !content.isComplete) return@collect
                    _uiState.value = _uiState.value.copy(
                        isLoading = !content.isComplete,
                        content = content,
                        error = null,
                    )
                }
            }
                .onSuccess {
                    val content = _uiState.value.content
                    TvDebugLogger.i("HomeVm", "load ok rails=${content?.rails?.size ?: 0} forceRefresh=$forceRefresh")
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) return@onFailure
                    TvDebugLogger.e("HomeVm", "load failed", error)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        // A partial screen is better than an error page, so the failure is only
                        // surfaced when nothing at all arrived.
                        error = if (_uiState.value.content?.rails.isNullOrEmpty()) {
                            error.message ?: "Could not load home"
                        } else {
                            null
                        },
                    )
                }
        }
    }
    fun setHeroCandidate(item: MediaItem?) {
        val nextKey = item?.let { "${it.type}:${it.id}" } ?: "none"
        if (nextKey == heroKey) return
        heroKey = nextKey
        heroDetailJob?.cancel()

        if (item == null || item.type == "network" || item.type == "live") {
            _uiState.value = _uiState.value.copy(heroDetail = null)
            return
        }

        heroDetailJob = viewModelScope.launch {
            delay(140)
            val detail = runCatching { repository.fetchDetail(item.id, item.type) }.getOrNull()
            if (heroKey == nextKey) {
                _uiState.value = _uiState.value.copy(heroDetail = detail)
            }
        }
    }
}

class HomeViewModelFactory(
    private val repository: StreamDekRepository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

