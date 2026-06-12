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

    fun load(loadKey: String) {
        if (lastLoadKey == loadKey && (_uiState.value.content != null || _uiState.value.error != null)) {
            return
        }
        forceRefresh(loadKey)
    }

    fun forceRefresh(loadKey: String) {
        lastLoadKey = loadKey
        viewModelScope.launch {
            val cachedContent = _uiState.value.content
            _uiState.value = _uiState.value.copy(
                isLoading = cachedContent == null,
                error = null,
            )
            runCatching { repository.fetchHomeContent(forceRefresh = true) }
                .onSuccess { content ->
                    TvDebugLogger.i("HomeVm", "load ok rails=${content.rails.size}")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        content = content,
                        error = null,
                    )
                }
                .onFailure { error ->
                    TvDebugLogger.e("HomeVm", "load failed", error)
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message ?: "Could not load home",
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
