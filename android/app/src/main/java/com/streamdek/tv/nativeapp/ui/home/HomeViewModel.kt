package com.streamdek.tv.nativeapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.streamdek.tv.nativeapp.data.AdultContentFilter
import com.streamdek.tv.nativeapp.data.HomeContent
import com.streamdek.tv.nativeapp.data.withoutAdult
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.Perf
import com.streamdek.tv.nativeapp.data.sharingRowsWith
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class HomeScreenUiState(
    val isLoading: Boolean = true,
    val content: HomeContent? = null,
    val error: String? = null,
    val heroDetail: MediaDetail? = null,
    val prefetchedTitleLogos: List<String> = emptyList(),
    /**
     * Whose Home [content] is: the account and profile it was loaded for. The screen draws content
     * only when this matches who is signed in now, so a profile switch can never show the previous
     * profile's shelves while the new one's are read.
     */
    val identity: String? = null,
    /**
     * True while [content] is the copy saved from the last session, not yet confirmed by a fresh
     * read. It is drawn exactly like the real thing; this only says a replacement is on its way.
     */
    val fromSnapshot: Boolean = false,
)

/**
 * Home's one source of truth.
 *
 * Everything that can change what Home shows arrives here as a request, and this decides what, if
 * anything, happens on screen. That is the whole design, and it replaces an arrangement in which
 * the screen keyed its own state - "has the first artwork loaded", "which row holds the entry
 * card" - on a string that also named every CloudStream source, the Fuse switch and the media
 * server revision. Each of those settles a few seconds into a cold start, each change of the
 * string reset that state, and each reset swapped the finished page for its skeleton and drew it
 * again with the highlight back on the first card. That was Home "loading three times".
 *
 * The rules now:
 *
 *  - **Who** Home belongs to (account and profile) is its identity. Only a change of identity
 *    starts Home over.
 *  - **What** it is built from (add-ons, CloudStream sources, the Fuse, media servers, the
 *    library) can change at any time. A change before the first page is up is folded into the
 *    load in flight where it can be, and otherwise restarts a load nobody has seen yet. A change
 *    after it is a refresh: read in the background, swapped in as one step, and only the rows that
 *    actually differ are new objects.
 *  - **Refreshes are coalesced.** However many things ask - a poll, a library write, three
 *    revisions in a second - at most one read runs and at most one more follows it.
 *  - **A returning viewer starts from the page they left.** The last finished Home is kept on
 *    disk and shown at once; the fresh read replaces it in place.
 */
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

        /**
         * How long refresh requests are gathered before one read is made for all of them. Sources
         * that settle together - a media server and its libraries, a batch of CloudStream
         * providers - announce themselves a few hundred milliseconds apart.
         */
        const val RefreshSettleMs = 600L
    }

    private val _uiState = MutableStateFlow(HomeScreenUiState())
    val uiState: StateFlow<HomeScreenUiState> = _uiState

    private var identity: String? = null
    private var layout: String? = null
    private var sources: String? = null

    private var heroKey: String? = null
    private var heroDetailJob: Job? = null
    /** The read in flight, cold or refresh. There is never more than one. */
    private var loadJob: Job? = null
    /** True while the first load of this Home is running. */
    private var coldLoadActive = false
    /** Something asked for a refresh while a read was in flight; one follows it. */
    private var refreshQueued = false
    private var settleJob: Job? = null
    private var loadsStarted = 0
    private val heroDetailRequests = mutableMapOf<String, Deferred<MediaDetail?>>()
    private val heroDetailCache = mutableMapOf<String, MediaDetail>()

    init {
        // A policy published while Home is showing takes effect on what is already drawn at once;
        // the repository then reloads Home through the library revision.
        viewModelScope.launch {
            AdultContentFilter.changes.drop(1).collect {
                val swept = _uiState.value.content?.withoutAdult() ?: return@collect
                // The spotlight must not keep describing a title the sweep just removed.
                val heroShown = heroKey == null || swept.rails.any { rail -> rail.items.any { heroItemKey(it) == heroKey } }
                _uiState.value = _uiState.value.copy(content = swept, heroDetail = _uiState.value.heroDetail.takeIf { heroShown })
            }
        }
    }

    /**
     * Tells Home who it is for and what it is built from. Called whenever either changes; calling
     * it again with the same values does nothing.
     *
     * [layout] is what decides which rows exist - the add-ons and built-in catalogues. [sources]
     * is what settles late and only fills rows in - CloudStream providers, the Fuse, media servers.
     */
    fun bind(identity: String, layout: String, sources: String) {
        if (identity != this.identity) {
            TvDebugLogger.i("HomeVm", "bind: new identity; starting Home over")
            this.identity = identity
            this.layout = layout
            this.sources = sources
            settleJob?.cancel()
            loadJob?.cancel()
            refreshQueued = false
            coldLoadActive = false
            heroKey = null
            heroDetailJob?.cancel()
            _uiState.value = HomeScreenUiState(identity = identity)
            startColdLoad()
            return
        }
        val layoutChanged = layout != this.layout
        val sourcesChanged = sources != this.sources
        this.layout = layout
        this.sources = sources
        if (!layoutChanged && !sourcesChanged) {
            // Nothing changed. Only a Home with nothing on it and nothing on its way needs anything.
            if (_uiState.value.content == null && _uiState.value.error == null && loadJob?.isActive != true) startColdLoad()
            return
        }
        when {
            // The first load is still running. A late source is not worth cancelling it for: it
            // carries the library, progress and Next Up reads, and starting those again is what
            // used to put Continue Watching ten seconds behind the rest of the page. The load
            // finishes and one refresh follows. A different set of rows is a different page, and
            // nobody has seen this one yet, so that does start again.
            coldLoadActive && loadJob?.isActive == true -> {
                if (layoutChanged && confirmedContent() == null) {
                    TvDebugLogger.i("HomeVm", "bind: row layout changed before the first page; restarting the first load")
                    startColdLoad()
                } else {
                    TvDebugLogger.i("HomeVm", "bind: ${if (layoutChanged) "layout" else "sources"} changed during the first load; refresh queued behind it")
                    refreshQueued = true
                }
            }
            _uiState.value.content == null -> startColdLoad()
            else -> requestRefresh(if (layoutChanged) "layout" else "sources")
        }
    }

    /**
     * Asks for what is on screen to be brought up to date. Never clears it, and never restarts a
     * read that is already running: that read is left to finish and a single refresh follows.
     *
     * [immediate] is for something the viewer just did and is waiting to see - removing a card,
     * pressing Try again - and skips the short wait that gathers background requests together.
     */
    fun requestRefresh(reason: String, immediate: Boolean = false) {
        if (identity == null) return
        if (loadJob?.isActive == true) {
            if (!refreshQueued) TvDebugLogger.i("HomeVm", "refresh($reason): a read is in flight; one will follow it")
            refreshQueued = true
            return
        }
        settleJob?.cancel()
        settleJob = viewModelScope.launch {
            if (!immediate) delay(RefreshSettleMs)
            if (loadJob?.isActive == true) {
                refreshQueued = true
                return@launch
            }
            if (_uiState.value.content == null) startColdLoad() else startRefresh(reason)
        }
    }

    /** What is on screen that a fresh read has confirmed, as opposed to last session's copy. */
    private fun confirmedContent(): HomeContent? = _uiState.value.content.takeUnless { _uiState.value.fromSnapshot }

    /**
     * The first load of this Home.
     *
     * A returning viewer is shown the page they left while it runs, and the fresh read replaces
     * that page in one step. With nothing saved, rows are applied as they land instead - held back
     * only until the ones that decide the page's order and its opening highlight have arrived.
     */
    private fun startColdLoad() {
        settleJob?.cancel()
        loadJob?.cancel()
        refreshQueued = false
        coldLoadActive = true
        val number = ++loadsStarted
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            if (_uiState.value.content == null) {
                val saved = runCatching { repository.loadHomeSnapshot() }.getOrNull()
                if (saved != null && _uiState.value.content == null) {
                    TvDebugLogger.i("HomeVm", "load#$number: showing the saved page rails=${saved.rails.size}")
                    Perf.startupMark("home.firstContent", "saved")
                    _uiState.value = _uiState.value.copy(content = saved, fromSnapshot = true, isLoading = true, error = null)
                }
            }
            // Over the saved page the fresh one is swapped in whole; over nothing it streams in.
            val progressive = _uiState.value.content == null
            TvDebugLogger.i("HomeVm", "load#$number: first load progressive=$progressive")
            collectHome(forceRefresh = false, progressive = progressive, readName = "load#$number")
            coldLoadActive = false
        }.also(::followWithQueuedRefresh)
    }

    private fun startRefresh(reason: String) {
        val number = ++loadsStarted
        TvDebugLogger.i("HomeVm", "load#$number: refresh ($reason)")
        loadJob = viewModelScope.launch {
            collectHome(forceRefresh = true, progressive = false, readName = "load#$number")
        }.also(::followWithQueuedRefresh)
    }

    /**
     * Runs the one refresh that was asked for while [read] was in flight, once it has finished.
     * Not when it was cancelled: whatever cancelled it has started the read that replaces it.
     */
    private fun followWithQueuedRefresh(read: Job) {
        read.invokeOnCompletion { cause ->
            if (cause != null || loadJob !== read || !refreshQueued) return@invokeOnCompletion
            refreshQueued = false
            requestRefresh("queued", immediate = true)
        }
    }

    private suspend fun collectHome(forceRefresh: Boolean, progressive: Boolean, readName: String) = coroutineScope {
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
                held?.let { publishContent(it, readName) }
            }
        } else {
            null
        }
        runCatching {
            repository.homeContentStream(forceRefresh = forceRefresh).collect { content ->
                if (!progressive && !content.isComplete) return@collect
                // The personal rows are held for as well, not only the entry row. Continue Watching
                // settling the highlight used to publish the page with New Episodes still a
                // skeleton beneath it, which filled in a second later and read as Home loading a
                // second time. Both are one account read apart, well inside the budget.
                if (progressive && _uiState.value.content == null &&
                    !content.isComplete && (!content.priorityResolved || content.personalRowsPending()) &&
                    !priorityBudgetSpent
                ) {
                    held = content
                    return@collect
                }
                publishContent(content, readName)
            }
        }
            .also { budget?.cancel() }
            .onSuccess {
                val content = _uiState.value.content
                TvDebugLogger.i("HomeVm", "$readName: ok rails=${content?.rails?.size ?: 0} forceRefresh=$forceRefresh")
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
            .onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                TvDebugLogger.e("HomeVm", "$readName: failed", error)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    // A partial screen is better than an error page, so the failure is only
                    // surfaced when nothing at all arrived - and the saved page counts as
                    // something. Only what the failure actually said: a view model has no
                    // resources, and the screen supplies the wording when this is null, which is
                    // where it can be said in the viewer's language.
                    error = if (_uiState.value.content?.rails.isNullOrEmpty()) {
                        error.message
                    } else {
                        null
                    },
                )
            }
    }

    private fun HomeContent.personalRowsPending(): Boolean =
        shelves.any { it is com.streamdek.tv.nativeapp.data.HomeShelfSlot.Pending && (it.id == "continue-watching" || it.id == "new-episodes") }

    /**
     * Puts a page on screen. Rows that are card-for-card what is already there keep the object
     * that is already there, so only what changed is drawn again; a read that changed nothing
     * changes nothing.
     */
    private fun publishContent(content: HomeContent, readName: String) {
        val before = _uiState.value
        val shared = content.sharingRowsWith(before.content)
        if (shared !== before.content) {
            val changed = shared.rails.count { rail -> before.content?.rails?.none { it === rail } != false }
            TvDebugLogger.i("HomeVm", "$readName: publish rails=${shared.rails.size} changedRows=$changed pending=${shared.pendingRails.size} overSaved=${before.fromSnapshot}")
        }
        _uiState.value = before.copy(
            isLoading = !shared.isComplete,
            content = shared,
            error = null,
            fromSnapshot = false,
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

        if (item == null || item.type == "network" || item.type == "live" || item.type == com.streamdek.tv.nativeapp.data.FUSE_PORTAL_ITEM_TYPE) {
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
            .filter {
                it.type != "network" && it.type != "live" &&
                    it.type != com.streamdek.tv.nativeapp.data.FUSE_PORTAL_ITEM_TYPE && it.titleLogo.isNullOrBlank()
            }
            .distinctBy(::heroItemKey)
            .take(8)
            .toList()
        if (candidates.isEmpty()) return

        viewModelScope.launch {
            val pending = candidates.map { item -> item to requestHeroDetail(item) }
            val found = mutableListOf<String>()
            pending.forEach { (item, request) ->
                val detail = request.await() ?: return@forEach
                val key = heroItemKey(item)
                heroDetailCache[key] = detail
                detail.titleLogo?.takeIf { it.isNotBlank() }?.let(found::add)
                if (heroKey == key) {
                    _uiState.value = _uiState.value.copy(heroDetail = detail)
                }
            }
            // Published once, not once per title: each update of this state is the whole of Home
            // being looked at again, and eight of them landed in the second after it appeared.
            val known = _uiState.value.prefetchedTitleLogos
            val added = found.filterNot { it in known }.distinct()
            if (added.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(prefetchedTitleLogos = (known + added).takeLast(20))
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

