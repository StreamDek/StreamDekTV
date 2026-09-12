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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class HomeScreenUiState(
    val isLoading: Boolean = true,
    val content: HomeContent? = null,
    val error: String? = null,
    val heroDetail: MediaDetail? = null,
    val prefetchedTitleLogos: List<String> = emptyList(),
)

class HomeViewModel(
    private val repository: StreamDekRepository,
) : ViewModel() {
    private companion object {
        /**
         * How long a cold Home holds its skeleton for the rows that decide its order and its
         * opening highlight.
         *
         * A safety valve, not a schedule: the ordinary path is decided by the data arriving, and
         * on a stick reading a populated account that lands comfortably inside this. It is set
         * above the measured worst case so the clock does not routinely pre-empt the data, and low
         * enough that a stalled account read is a pause rather than a stuck screen. When it does
         * fire, the page appears with its reserved slots in place and the late rows drop into
         * them, so the cost is a plainer first frame rather than the jump this all exists to
         * prevent.
         */
        const val PriorityBudgetMs = 3_500L
    }

    private val _uiState = MutableStateFlow(HomeScreenUiState())
    val uiState: StateFlow<HomeScreenUiState> = _uiState

    private var lastLoadKey: String? = null
    private var heroKey: String? = null
    private var heroDetailJob: Job? = null
    private var loadJob: Job? = null
    private val heroDetailRequests = mutableMapOf<String, Deferred<MediaDetail?>>()
    private val heroDetailCache = mutableMapOf<String, MediaDetail>()

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
            // The first frame a cold Home shows is the one it keeps.
            //
            // Rows are held back until the ones that decide the page's order and its opening
            // highlight have arrived or been ruled out. Publishing before that is what made Home
            // appear to load twice: the catalogue drew, the highlight landed on it, and Continue
            // Watching then had to be inserted above rows the viewer was already looking at,
            // taking the highlight and the hero with it.
            //
            // It is a hold, not a block. Everything below the priority rows still streams into the
            // slots reserved for it, and the hold is bounded: once [PriorityBudgetMs] is spent
            // whatever has arrived is shown, and a late library read drops into the space being
            // kept for it. A slow account request costs a moment, never the screen.
            var priorityBudgetSpent = false
            var held: HomeContent? = null
            val budget = if (progressive) {
                launch {
                    delay(PriorityBudgetMs)
                    priorityBudgetSpent = true
                    held?.let(::publishContent)
                }
            } else {
                null
            }
            runCatching {
                repository.homeContentStream(forceRefresh = forceRefresh).collect { content ->
                    if (!progressive && !content.isComplete) return@collect
                    if (progressive && _uiState.value.content == null &&
                        !content.isComplete && !content.priorityResolved && !priorityBudgetSpent
                    ) {
                        held = content
                        return@collect
                    }
                    publishContent(content)
                }
            }
                .also { budget?.cancel() }
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
                        // Only what the failure actually said. A view model has no resources, and
                        // the screen supplies the wording when this is null -- which is where it can
                        // be said in the viewer's language.
                        error = if (_uiState.value.content?.rails.isNullOrEmpty()) {
                            error.message
                        } else {
                            null
                        },
                    )
                }
        }
    }
    private fun publishContent(content: HomeContent) {
        _uiState.value = _uiState.value.copy(
            isLoading = !content.isComplete,
            content = content,
            error = null,
        )
    }

    fun setHeroCandidate(item: MediaItem?) {
        val nextKey = item?.let { "${it.type}:${it.id}" } ?: "none"
        if (nextKey == heroKey) return
        heroKey = nextKey
        heroDetailJob?.cancel()
        // Never let the newly focused title briefly render the previous title's metadata.
        val cachedDetail = heroDetailCache[nextKey]
        _uiState.value = _uiState.value.copy(heroDetail = cachedDetail)

        if (item == null || item.type == "network" || item.type == "live") {
            _uiState.value = _uiState.value.copy(heroDetail = null)
            return
        }
        if (cachedDetail != null) return

        heroDetailJob = viewModelScope.launch {
            // A short focus debounce prevents accidental fly-over requests without making logos
            // feel late. Coil preloads logos already present on catalogue items in parallel.
            delay(45)
            val detail = requestHeroDetail(item).await()
            if (detail != null) heroDetailCache[nextKey] = detail
            if (heroKey == nextKey) {
                _uiState.value = _uiState.value.copy(heroDetail = detail)
            }
        }
    }

    /**
     * Catalogue items often omit titleLogo and expose it only from the detail endpoint. Warm a
     * small set of likely hero candidates so both their metadata and logo URLs are ready before
     * focus reaches them. The UI feeds the discovered URLs into Coil's memory/disk cache.
     */
    fun prefetchHeroCandidates(items: List<MediaItem>) {
        val candidates = items
            .asSequence()
            .filter { it.type != "network" && it.type != "live" && it.titleLogo.isNullOrBlank() }
            .distinctBy(::heroItemKey)
            .take(8)
            .toList()
        if (candidates.isEmpty()) return

        viewModelScope.launch {
            val pending = candidates.map { item -> item to requestHeroDetail(item) }
            pending.forEach { (item, request) ->
                val detail = request.await() ?: return@forEach
                val key = heroItemKey(item)
                heroDetailCache[key] = detail
                detail.titleLogo?.takeIf { it.isNotBlank() }?.let { logo ->
                    if (logo !in _uiState.value.prefetchedTitleLogos) {
                        _uiState.value = _uiState.value.copy(
                            prefetchedTitleLogos = (_uiState.value.prefetchedTitleLogos + logo).takeLast(20),
                        )
                    }
                }
                if (heroKey == key) {
                    _uiState.value = _uiState.value.copy(heroDetail = detail)
                }
            }
        }
    }

    private fun requestHeroDetail(item: MediaItem): Deferred<MediaDetail?> {
        val key = heroItemKey(item)
        return heroDetailRequests.getOrPut(key) {
            viewModelScope.async {
                runCatching { repository.fetchDetail(item.detailLookupId(), item.type) }.getOrNull()
            }
        }
    }

    private fun heroItemKey(item: MediaItem): String = "${item.type}:${item.id}"
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

