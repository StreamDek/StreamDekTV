package com.streamdek.tv.nativeapp.ui.fuse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.data.AdultContentFilter
import com.streamdek.tv.nativeapp.data.FuseCatalog
import com.streamdek.tv.nativeapp.data.withoutAdult
import com.streamdek.tv.nativeapp.data.FuseOrigin
import com.streamdek.tv.nativeapp.data.FusePage
import com.streamdek.tv.nativeapp.data.FuseViewMemory
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.PluginCatalogSearch
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.cloudStreamProviderNameFromCutId
import com.streamdek.tv.nativeapp.data.decodeCloudStreamMediaId
import com.streamdek.tv.nativeapp.data.favouriteChannelIdMatches
import com.streamdek.tv.nativeapp.data.fuseItemKey
import com.streamdek.tv.nativeapp.data.fusePageKey
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.BrowseItemActionMenu
import com.streamdek.tv.nativeapp.ui.LocalSideNavOwnsFocus
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.PremiumMediaCard
import com.streamdek.tv.nativeapp.ui.TvContentPhase
import com.streamdek.tv.nativeapp.ui.TvContentSwap
import com.streamdek.tv.nativeapp.ui.TvEmptyState
import com.streamdek.tv.nativeapp.ui.TvMediaCardVariant
import com.streamdek.tv.nativeapp.ui.TvNavRailInset
import com.streamdek.tv.nativeapp.ui.TvSkeletonGrid
import com.streamdek.tv.nativeapp.ui.TvSpacing
import com.streamdek.tv.nativeapp.ui.search.SearchChip
import com.streamdek.tv.nativeapp.ui.search.SearchFilterOption
import com.streamdek.tv.nativeapp.ui.search.SearchFilterTray
import com.streamdek.tv.nativeapp.ui.tvCardLongPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private val FuseInset = TvSpacing.ScreenHorizontal
private val SearchRowHeight = 52.dp

private enum class FuseTray { None, Source, Collection, Category }

/** One source's matching titles. */
private data class FuseGroup(val sourceKey: String, val items: List<MediaItem>)

/** What the page shows, and the filters it was worked out for. */
private data class FuseView(
    val identity: List<Any?>,
    val items: List<MediaItem>,
    val groups: List<FuseGroup>,
    val categories: List<String>,
)

private data class FuseActionState(val item: MediaItem, val restore: FocusRequester)

/**
 * A focus target that knows whether anything on screen carries it.
 *
 * Moving focus to a [FocusRequester] nothing carries crashes the app, and the Fuse page's first
 * result is not always on screen: the list builds it lazily, so it leaves once the list scrolls past
 * it, and results arriving for a search land above what is showing. Down from the search box or the
 * chips aimed at it regardless, and crashed. Moves aimed here ask [orDefault] at the moment of the
 * press, which only names [requester] while it is there.
 */
private class FuseFocusTarget {
    val requester = FocusRequester()
    var carriers = 0
    val present: Boolean get() = carriers > 0
    fun orDefault(): FocusRequester = if (present) requester else FocusRequester.Default
}

/** Carries [target]'s requester, counting itself in while it is attached. */
private fun Modifier.carries(target: FuseFocusTarget): Modifier =
    this.then(FuseFocusCarrierElement(target)).focusRequester(target.requester)

private data class FuseFocusCarrierElement(val target: FuseFocusTarget) : ModifierNodeElement<FuseFocusCarrierNode>() {
    override fun create() = FuseFocusCarrierNode(target)
    override fun update(node: FuseFocusCarrierNode) {
        if (node.target === target) return
        if (node.isAttached) { node.target.carriers--; target.carriers++ }
        node.target = target
    }
}

private class FuseFocusCarrierNode(var target: FuseFocusTarget) : Modifier.Node() {
    override fun onAttach() { target.carriers++ }
    override fun onDetach() { target.carriers-- }
}

/**
 * StreamDek Fuse on the television: every live and on-demand source in one page.
 *
 * The phone's page, rebuilt for a remote. There is no scroll-aware header to chase - the search box,
 * the view chips and the source filters sit above the results and stay one press of Back away, which
 * is how a D-pad gets back to them from deep in a list. Sources are collapsible sections, as the
 * phone's are: the first source to answer opens, the rest are one OK away, and a source still loading
 * has its heading straight away with a spinner, so a slow provider is visibly on its way.
 */
@Composable
fun FuseScreen(
    repository: StreamDekRepository,
    entryFocusRequester: FocusRequester? = null,
    onOpenNavigation: () -> Unit = {},
    onBack: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onPlayLive: (MediaItem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bootstrap by repository.bootstrap.collectAsState()
    val favouriteChannels by repository.favouriteChannels.collectAsState()
    val categoriesEnabled = bootstrap?.preferences?.home?.liveCategoriesEnabled != false
    val gridColumns = LocalTvExperienceSettings.current.gridColumns
    val sideNavOwnsFocus = LocalSideNavOwnsFocus.current

    // What this session already knows is shown at once and refreshed behind it.
    val cachedCatalogs = remember { repository.cachedFuseCatalogs() }
    var catalogs by remember { mutableStateOf(cachedCatalogs.orEmpty()) }
    var catalogsReady by remember { mutableStateOf(cachedCatalogs != null) }
    // Add-ons and CloudStream rows first, then again with playlists: a large playlist takes a while to
    // read, and the rest of the page should not wait behind it.
    var playlistsReady by remember { mutableStateOf(cachedCatalogs != null) }
    val policyRevision by AdultContentFilter.changes.collectAsState()
    LaunchedEffect(policyRevision) {
        if (cachedCatalogs == null) {
            catalogs = runCatching { repository.fuseCatalogs(includePlaylists = false) }.getOrDefault(emptyList())
            catalogsReady = true
        }
        catalogs = runCatching { repository.fuseCatalogs() }.getOrDefault(catalogs)
        catalogsReady = true
        playlistsReady = true
    }

    // Where the viewer left the page this session, restored on the way back in. See [FuseViewMemory].
    val memory = remember { repository.fuseViewMemory() }
    var mode by rememberSaveable { mutableStateOf(memory?.mode ?: "all") }
    var sourceKey by rememberSaveable { mutableStateOf(memory?.sourceKey) }
    var catalogKey by rememberSaveable { mutableStateOf(memory?.catalogKey) }
    var category by rememberSaveable { mutableStateOf(memory?.category) }
    var query by rememberSaveable { mutableStateOf(memory?.query.orEmpty()) }
    var editing by remember { mutableStateOf(false) }
    var settledQuery by remember { mutableStateOf(query.trim()) }
    var tray by remember { mutableStateOf(FuseTray.None) }
    var actionState by remember { mutableStateOf<FuseActionState?>(null) }
    val favouritesOnly = mode == "favourites"
    fun modeAllows(live: Boolean): Boolean = when (mode) {
        "all" -> true
        "vod" -> !live
        else -> live
    }

    LaunchedEffect(query) { delay(350); settledQuery = query.trim() }
    val sources = remember(catalogs) { catalogs.distinctBy { it.sourceKey } }
    val scoped = remember(catalogs, mode, sourceKey, catalogKey, settledQuery) {
        catalogs.filter { (sourceKey == null || it.sourceKey == sourceKey) && (catalogKey == null || it.key == catalogKey) && modeAllows(it.live) }
            // A query goes to a plugin once, not once per row it has on Home: see fusePageKey.
            .distinctBy { if (it.origin == FuseOrigin.CloudStream && settledQuery.isNotEmpty()) "cloudsearch:" + it.sourceKey else it.key }
    }
    val availableCatalogs = remember(catalogs, mode, sourceKey) {
        catalogs.filter { (sourceKey == null || it.sourceKey == sourceKey) && modeAllows(it.live) }
    }

    // Pages by catalogue and query, loaded four sources at a time; Load more asks for the next four.
    val pages = remember { mutableStateMapOf<String, FusePage>().apply { putAll(repository.cachedFusePages()) } }
    val inFlight = remember { mutableStateMapOf<String, Boolean>() }
    var loading by remember { mutableStateOf(false) }
    var requestRound by remember { mutableIntStateOf(0) }
    var retry by remember { mutableStateOf(false) }
    // Pages were filtered under the policy they loaded with; a new policy loads them again.
    var pagesPolicyRevision by remember { mutableStateOf(policyRevision) }
    LaunchedEffect(policyRevision) {
        if (policyRevision == pagesPolicyRevision) return@LaunchedEffect
        pagesPolicyRevision = policyRevision
        pages.clear()
        requestRound++
    }
    val requestIdentity = remember(scoped, settledQuery) { scoped.map { it.key } to settledQuery }
    LaunchedEffect(requestIdentity, requestRound) {
        loading = true
        try {
            val pending = scoped.filter { catalog ->
                val page = pages[fusePageKey(catalog, settledQuery)]
                page == null || (!page.end && (!page.failed || retry))
            }.sortedBy { pages[fusePageKey(it, settledQuery)]?.nextSkip ?: -1 }
                .let { if (settledQuery.isNotEmpty()) it else it.take(4) }
            val gate = Semaphore(4)
            supervisorScope {
                pending.map { catalog ->
                    async {
                        gate.withPermit {
                            val key = fusePageKey(catalog, settledQuery)
                            inFlight[catalog.key] = true
                            try {
                                val page = repository.loadFusePage(catalog, settledQuery, pages[key] ?: FusePage())
                                pages[key] = page
                                repository.rememberFusePage(key, page)
                            } finally {
                                inFlight.remove(catalog.key)
                            }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            if (currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true) { loading = false; retry = false }
        }
    }

    fun isFavourite(item: MediaItem): Boolean = favouriteChannels.any { favourite ->
        favouriteChannelIdMatches(favourite.id, item.id) &&
            (favourite.sourceAddonId == null || item.sourceAddonId == null || favourite.sourceAddonId == item.sourceAddonId)
    }

    // Everything that decides which titles match. While the page still shows an answer to an older set,
    // it says it is working instead, so choosing a filter never looks like it did nothing.
    val filterIdentity = listOf(mode, sourceKey, catalogKey, category, settledQuery, categoriesEnabled, requestIdentity.first, policyRevision)
    var view by remember { mutableStateOf<FuseView?>(null) }
    LaunchedEffect(filterIdentity, pages.toMap(), favouriteChannels) {
        val snapshot = pages.toMap()
        val favourites = favouriteChannels
        view = withContext(Dispatchers.Default) {
            val owners = HashMap<String, String>()
            val loaded = scoped.flatMap { catalog ->
                (catalog.localItems ?: snapshot[fusePageKey(catalog, settledQuery)]?.items.orEmpty())
                    .withoutAdult()
                    .onEach { owners.getOrPut(fuseItemKey(it)) { catalog.sourceKey } }
            }
            val loadedIds = loaded.mapTo(hashSetOf()) { it.id }
            // Favourites from sources not loaded yet are shown from the saved copy, not left out.
            val savedFavourites = if (!favouritesOnly) emptyList() else favourites.filter { favourite ->
                if (loadedIds.any { favouriteChannelIdMatches(favourite.id, it) }) return@filter false
                val provider = decodeCloudStreamMediaId(favourite.id)?.first ?: cloudStreamProviderNameFromCutId(favourite.id)
                val owner = scoped.firstOrNull { catalog ->
                    catalog.sourceKey == favourite.sourceAddonId ||
                        (catalog.origin == FuseOrigin.Playlist && favourite.id.startsWith(catalog.sourceKey.removePrefix("playlist:") + ":")) ||
                        (catalog.origin == FuseOrigin.CloudStream && provider != null && catalog.sourceName == provider)
                } ?: return@filter false
                owners.getOrPut(fuseItemKey(favourite)) { owner.sourceKey }
                true
            }
            val items = (loaded + savedFavourites).distinctBy(::fuseItemKey)
            val categoryNames = if (scoped.isNotEmpty() && scoped.all { it.live } && categoriesEnabled) {
                items.mapNotNull { it.sourceCatalogName?.takeIf(String::isNotBlank) }.distinct().sortedBy { it.lowercase() }
            } else emptyList()
            // What a plugin's own search returned is already an answer to the query - it may match on
            // a title it does not show - so it is not filtered again, only kept to the view's kind.
            val searched = if (settledQuery.isEmpty()) emptySet() else scoped
                .filter { it.origin == FuseOrigin.CloudStream }
                .flatMap { snapshot[fusePageKey(it, settledQuery)]?.items.orEmpty() }
                .mapTo(hashSetOf(), ::fuseItemKey)
            val matches = items.filter { item ->
                val fromSearch = fuseItemKey(item) in searched
                (category == null || item.sourceCatalogName == category) &&
                    (!favouritesOnly || isFavourite(item)) &&
                    (!fromSearch || modeAllows(item.type == "live")) &&
                    (
                        settledQuery.isEmpty() || fromSearch ||
                            PluginCatalogSearch.matchRank(item.title, settledQuery) != null ||
                            item.description.orEmpty().contains(settledQuery, true)
                        )
            }
            val groups = matches.groupBy { owners[fuseItemKey(it)].orEmpty() }.map { (key, owned) -> FuseGroup(key, owned) }
            FuseView(filterIdentity, matches, groups, categoryNames)
        }
    }
    val currentView = view
    val filtering = currentView == null || currentView.identity != filterIdentity
    val categories = currentView?.categories.orEmpty()
    val loadingSources = remember(inFlight.toMap(), catalogs) { catalogs.filter { inFlight[it.key] == true }.mapTo(hashSetOf()) { it.sourceKey } }
    val hasMore = scoped.any { catalog -> catalog.localItems == null && pages[fusePageKey(catalog, settledQuery)]?.let { !it.end && !it.failed } != false }
    val failed = scoped.any { pages[fusePageKey(it, settledQuery)]?.failed == true }
    // Sources that genuinely cannot answer a query: add-on catalogues without search, only matched
    // against what has loaded from them so far. A catalogue already loaded to its end is complete, so
    // matching it on the device misses nothing and is not worth a warning; nor is anything a plugin or
    // playlist holds, both of which are searched in full. Named, so the viewer knows which ones.
    val searchLimitedSources = if (settledQuery.isEmpty()) emptyList() else scoped
        .filter { !it.searchable && it.localItems == null && pages[fusePageKey(it, settledQuery)]?.end != true }
        .map { it.sourceName }
        .distinct()
    val searchLimited = searchLimitedSources.isNotEmpty()
    val groupedBySource = sourceKey == null && sources.size > 1

    // Sources in the order they answered, so a slow one joins below the ones already showing.
    val groupIdentity = listOf(mode, catalogKey, category, settledQuery).joinToString("")
    val restoredGroups = memory?.takeIf { it.groupIdentity == groupIdentity }
    var groupOrder by rememberSaveable(groupIdentity) { mutableStateOf(restoredGroups?.groupOrder.orEmpty()) }
    var openedGroups by rememberSaveable(groupIdentity) { mutableStateOf(restoredGroups?.openedGroups.orEmpty()) }
    var closedGroups by rememberSaveable(groupIdentity) { mutableStateOf(restoredGroups?.closedGroups.orEmpty()) }
    val shownGroups = remember(currentView, filtering, scoped, loadingSources, groupOrder) {
        if (currentView == null || filtering) emptyList() else {
            val byKey = currentView.groups.associateBy { it.sourceKey }
            val scopedKeys = scoped.map { it.sourceKey }.distinct()
            val filled = groupOrder.filter { byKey[it]?.items?.isNotEmpty() == true } +
                scopedKeys.filter { it !in groupOrder && byKey[it]?.items?.isNotEmpty() == true }
            filled.map { byKey.getValue(it) } +
                scopedKeys.filter { it !in filled && it in loadingSources }.map { FuseGroup(it, emptyList()) }
        }
    }
    val filledKeys = shownGroups.filter { it.items.isNotEmpty() }.map { it.sourceKey }
    LaunchedEffect(groupIdentity, filledKeys) {
        val arrived = filledKeys.filterNot { it in groupOrder }
        if (arrived.isNotEmpty()) groupOrder = groupOrder + arrived
    }
    val lead = filledKeys.firstOrNull()
    fun groupOpen(key: String) = key in openedGroups || (key == lead && key !in closedGroups)

    val favouriteCount = remember(favouriteChannels, catalogs) {
        val live = catalogs.filter { it.live }
        favouriteChannels.count { favourite ->
            val provider = decodeCloudStreamMediaId(favourite.id)?.first ?: cloudStreamProviderNameFromCutId(favourite.id)
            live.any { catalog ->
                catalog.sourceKey == favourite.sourceAddonId ||
                    (catalog.origin == FuseOrigin.Playlist && favourite.id.startsWith(catalog.sourceKey.removePrefix("playlist:") + ":")) ||
                    (catalog.origin == FuseOrigin.CloudStream && provider != null && catalog.sourceName == provider)
            }
        }
    }
    val sourceNames = remember(sources) { sources.associate { it.sourceKey to it.sourceName } }
    val originAddon = stringResource(R.string.fuse_origin_addon)
    val originPlaylist = stringResource(R.string.fuse_origin_playlist)
    val originCloudStream = stringResource(R.string.fuse_origin_cloudstream)
    val sourceOrigins = remember(sources, originAddon, originPlaylist, originCloudStream) {
        sources.associate {
            it.sourceKey to when (it.origin) {
                FuseOrigin.Addon -> originAddon
                FuseOrigin.Playlist -> originPlaylist
                FuseOrigin.CloudStream -> originCloudStream
            }
        }
    }

    val hasLiveSources = catalogs.any { it.live }
    val hasVodSources = catalogs.any { !it.live }
    val views = buildList {
        add("all")
        if (hasLiveSources && hasVodSources) add("live")
        if (hasLiveSources) add("favourites")
        if (hasLiveSources && hasVodSources) add("vod")
    }
    val hasChips = views.size > 1 || sources.size > 1 || availableCatalogs.size > 1 || categories.size > 1
    val phase = when {
        !catalogsReady -> TvContentPhase.Loading
        playlistsReady && catalogs.isEmpty() -> TvContentPhase.Empty
        currentView == null || filtering || ((loading || !playlistsReady) && currentView.items.isEmpty()) -> TvContentPhase.Loading
        currentView.items.isEmpty() -> TvContentPhase.Empty
        else -> TvContentPhase.Content
    }

    val listState = rememberLazyListState()
    // A changed filter starts from the top; arriving with the filters already set - a return - does not.
    var lastFilters by remember { mutableStateOf(listOf(mode, sourceKey, catalogKey, category, settledQuery)) }
    LaunchedEffect(mode, sourceKey, catalogKey, category, settledQuery) {
        val filters = listOf(mode, sourceKey, catalogKey, category, settledQuery)
        if (filters != lastFilters) { lastFilters = filters; runCatching { listState.scrollToItem(0) } }
    }
    var focusedItemKey by remember { mutableStateOf(memory?.focusedItemKey) }
    // Filled as cards compose, so a returning viewer's card can be found once its line is on screen.
    val cardRequesters = remember { HashMap<String, FocusRequester>() }
    DisposableEffect(Unit) {
        onDispose {
            repository.rememberFuseView(
                FuseViewMemory(
                    mode = mode, sourceKey = sourceKey, catalogKey = catalogKey, category = category, query = query,
                    groupOrder = groupOrder, openedGroups = openedGroups, closedGroups = closedGroups,
                    focusedItemKey = focusedItemKey,
                    firstVisibleIndex = listState.firstVisibleItemIndex,
                    firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                ),
            )
        }
    }

    val searchRequester = entryFocusRequester ?: remember { FocusRequester() }
    val clearRequester = remember { FocusRequester() }
    val firstChip = remember { FuseFocusTarget() }
    val trayRequester = remember { FocusRequester() }
    val firstContent = remember { FuseFocusTarget() }
    var contentFocused by remember { mutableStateOf(false) }
    // Focus only ever moves to something on the page. Down from the search box aimed at filter chips
    // that do not exist until sources arrive crashed the app, and so did Down to a first result the
    // list had not drawn, so each move is worked out when it is made, from what is on screen then.
    // With the first result off screen, Down goes to the nearest result showing instead.
    fun belowSearch(): FocusRequester = if (firstChip.present) firstChip.requester else firstContent.orDefault()
    fun belowChips(): FocusRequester = firstContent.orDefault()
    fun aboveContent(): FocusRequester = if (firstChip.present) firstChip.requester else searchRequester

    val currentPhase by rememberUpdatedState(phase)
    LaunchedEffect(Unit) {
        delay(160)
        if (sideNavOwnsFocus) return@LaunchedEffect
        val target = memory?.focusedItemKey
        if (target != null) {
            // Back to the card the viewer left from - once the list it sits in has been drawn.
            var scrolled = false
            repeat(40) {
                if (currentPhase == TvContentPhase.Content) {
                    // Scrolled once: retrying the scroll would pull the list back under a viewer who has moved.
                    if (!scrolled) {
                        runCatching { listState.scrollToItem(memory?.firstVisibleIndex ?: 0, memory?.firstVisibleOffset ?: 0) }
                        scrolled = true
                    }
                    delay(60)
                    val requester = cardRequesters[target]
                    if (requester != null && runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
                }
                delay(100)
            }
        }
        runCatching { searchRequester.requestFocus() }
    }
    LaunchedEffect(tray) {
        if (tray == FuseTray.None) return@LaunchedEffect
        delay(60)
        runCatching { trayRequester.requestFocus() }
    }
    // Deep in a long list, Back returns to the filters first; from the filters it leaves the page.
    BackHandler(enabled = contentFocused && actionState == null) {
        scope.launch {
            runCatching { listState.scrollToItem(0) }
            runCatching { aboveContent().requestFocus() }
        }
    }

    val liveColumns = gridColumns.coerceIn(3, 5)
    // Leftmost cards and headings open the navigation rail on Left, as Home's first cards do.
    fun Modifier.leftOpensNavigation(): Modifier = onPreviewKeyEvent { event ->
        if (event.key == Key.DirectionLeft) {
            if (event.type == KeyEventType.KeyDown) onOpenNavigation()
            true
        } else false
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Runs under the navigation rail, so a transparent rail has the page behind it.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                        0.45f to MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                        1f to MaterialTheme.colorScheme.background,
                    ),
                ),
            ),
        )
        Column(Modifier.fillMaxSize().padding(start = TvNavRailInset)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = FuseInset, end = FuseInset, top = 34.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.fuse_title),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                val status = when {
                    !catalogsReady || ((loading || !playlistsReady) && currentView?.items.isNullOrEmpty()) -> stringResource(R.string.fuse_loading)
                    filtering -> stringResource(R.string.fuse_updating)
                    else -> null
                }
                if (status != null || loading || !playlistsReady) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                        status?.let {
                            Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = FuseInset, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it; if (it.isNotBlank()) { catalogKey = null; category = null } },
                    singleLine = true,
                    readOnly = !editing,
                    shape = AppPillShape,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.fuse_search),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            // The TV theme's Text ignores the field's placeholder colour, so it is stated.
                            color = Color.White.copy(alpha = if (editing) 0.9f else 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, color = Color.White),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(alpha = 0.18f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.11f),
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        editing = false
                        runCatching { belowSearch().requestFocus() }
                    }),
                    modifier = Modifier.weight(1f).height(SearchRowHeight).focusRequester(searchRequester)
                        .focusProperties { right = clearRequester; down = belowSearch() }
                        .onFocusChanged { if (!it.isFocused) editing = false }
                        .onPreviewKeyEvent { event ->
                            if (!editing && event.key == Key.DirectionLeft) {
                                if (event.type == KeyEventType.KeyDown) onOpenNavigation()
                                return@onPreviewKeyEvent true
                            }
                            // A text field takes Down and Right for its own cursor even while it is read-only,
                            // so from the field they never reached the chips or Clear.
                            if (!editing && (event.key == Key.DirectionDown || event.key == Key.DirectionRight)) {
                                if (event.type == KeyEventType.KeyDown) {
                                    val target = if (event.key == Key.DirectionRight) clearRequester else belowSearch()
                                    if (target != FocusRequester.Default) runCatching { target.requestFocus() }
                                }
                                return@onPreviewKeyEvent true
                            }
                            val select = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                            if (!editing && select && event.type == KeyEventType.KeyUp) { editing = true; true } else false
                        },
                )
                SearchChip(
                    label = stringResource(R.string.action_clear), selected = false,
                    modifier = Modifier.width(132.dp).height(SearchRowHeight).focusRequester(clearRequester)
                        .focusProperties { left = searchRequester; down = belowSearch() },
                    onClick = { query = ""; editing = false; runCatching { searchRequester.requestFocus() } },
                )
            }

            // Views first - All, Live TV, Favourites, VOD - then the narrower filters.
            val favouritesLabel = stringResource(R.string.live_favourites)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).focusGroup()
                    .padding(horizontal = FuseInset, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                var first = true
                fun Modifier.firstChip(): Modifier = if (first) { first = false; carries(firstChip).focusProperties { up = searchRequester } } else this
                if (views.size > 1) views.forEach { key ->
                    SearchChip(
                        label = when (key) {
                            "live" -> stringResource(R.string.live_tv)
                            "vod" -> stringResource(R.string.fuse_vod)
                            "favourites" -> if (favouriteCount > 0) "$favouritesLabel $favouriteCount" else favouritesLabel
                            else -> stringResource(R.string.fuse_view_all)
                        },
                        selected = mode == key,
                        modifier = Modifier.firstChip().focusProperties { down = belowChips() },
                        onClick = { mode = key; catalogKey = null; category = null; tray = FuseTray.None },
                    )
                }
                if (sources.size > 1) SearchChip(
                    label = sources.firstOrNull { it.sourceKey == sourceKey }?.sourceName ?: stringResource(R.string.fuse_all_sources),
                    selected = tray == FuseTray.Source || sourceKey != null,
                    modifier = Modifier.firstChip().focusProperties { down = belowChips() },
                    onClick = { tray = if (tray == FuseTray.Source) FuseTray.None else FuseTray.Source },
                )
                if (availableCatalogs.size > 1) SearchChip(
                    label = catalogs.firstOrNull { it.key == catalogKey }?.title ?: stringResource(R.string.fuse_collections),
                    selected = tray == FuseTray.Collection || catalogKey != null,
                    modifier = Modifier.firstChip().focusProperties { down = belowChips() },
                    onClick = { tray = if (tray == FuseTray.Collection) FuseTray.None else FuseTray.Collection },
                )
                if (categories.size > 1) SearchChip(
                    label = category ?: stringResource(R.string.fuse_category),
                    selected = tray == FuseTray.Category || category != null,
                    modifier = Modifier.firstChip().focusProperties { down = belowChips() },
                    onClick = { tray = if (tray == FuseTray.Category) FuseTray.None else FuseTray.Category },
                )
            }

            val allLabel = stringResource(R.string.fuse_all_sources)
            if (tray != FuseTray.None) {
                val options = when (tray) {
                    FuseTray.Source -> listOf<Pair<String?, String>>(null to allLabel) + sources.map { it.sourceKey to it.sourceName }
                    FuseTray.Collection -> listOf<Pair<String?, String>>(null to allLabel) + availableCatalogs.map { it.key to "${it.sourceName} · ${it.title}" }
                    FuseTray.Category -> listOf<Pair<String?, String>>(null to allLabel) + categories.map { it to it }
                    FuseTray.None -> emptyList()
                }
                // The tray keys its chips by label, so two sources that share a name are told apart.
                val seen = HashMap<String, Int>()
                SearchFilterTray(
                    firstOptionRequester = trayRequester,
                    onDismiss = { tray = FuseTray.None },
                    options = options.map { (value, label) ->
                        val count = (seen[label] ?: 0) + 1
                        seen[label] = count
                        val shown = if (count > 1) "$label ($count)" else label
                        when (tray) {
                            FuseTray.Source -> SearchFilterOption(shown, sourceKey == value) { sourceKey = value; catalogKey = null; category = null }
                            FuseTray.Collection -> SearchFilterOption(shown, catalogKey == value) { catalogKey = value; category = null; query = "" }
                            else -> SearchFilterOption(shown, category == value) { category = value }
                        }
                    },
                )
            }

            TvContentSwap(phase = phase, modifier = Modifier.weight(1f).fillMaxWidth()) { shown ->
                when (shown) {
                    TvContentPhase.Loading -> TvSkeletonGrid(columns = gridColumns, rows = 2)
                    TvContentPhase.Empty, TvContentPhase.Error -> TvEmptyState(
                        title = stringResource(
                            when {
                                playlistsReady && catalogs.isEmpty() -> R.string.fuse_empty
                                favouritesOnly && settledQuery.isEmpty() -> R.string.fuse_favourites_empty
                                else -> R.string.fuse_no_results
                            },
                        ),
                    )
                    TvContentPhase.Content -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().focusGroup().onFocusChanged { contentFocused = it.hasFocus },
                        contentPadding = PaddingValues(start = FuseInset, end = FuseInset, top = 2.dp, bottom = 72.dp),
                        verticalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                    ) {
                        val view = currentView ?: return@LazyColumn
                        var firstFocusableAssigned = false
                        fun nextIsFirst(): Boolean = if (!firstFocusableAssigned) { firstFocusableAssigned = true; true } else false

                        if (searchLimited) item(key = "search_limited") {
                            Text(
                                if (searchLimitedSources.size == 1) {
                                    stringResource(R.string.fuse_search_limited_one, searchLimitedSources.first())
                                } else {
                                    stringResource(
                                        R.string.fuse_search_limited_many,
                                        searchLimitedSources.take(3).joinToString(", "),
                                        searchLimitedSources.size,
                                    )
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            )
                        }
                        if (favouritesOnly || scoped.any { it.live }) item(key = "favourite_hint") {
                            Text(
                                stringResource(R.string.fuse_favourites_hint),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                            )
                        }

                        fun linesOf(items: List<MediaItem>): List<List<MediaItem>> {
                            val lines = ArrayList<List<MediaItem>>()
                            var run = ArrayList<MediaItem>()
                            var runLive: Boolean? = null
                            items.forEach { item ->
                                val live = item.type == "live"
                                val columns = if (live) liveColumns else gridColumns
                                if (runLive != null && (runLive != live || run.size == columns)) { lines += run; run = ArrayList() }
                                runLive = live
                                run += item
                            }
                            if (run.isNotEmpty()) lines += run
                            return lines
                        }

                        fun cardLines(items: List<MediaItem>, groupKey: String) {
                            linesOf(items).forEach { line ->
                                val first = line.first()
                                val live = first.type == "live"
                                val isFirstLine = nextIsFirst()
                                item(key = "line:" + fuseItemKey(first), contentType = if (live) "live" else "poster") {
                                    val columns = if (live) liveColumns else gridColumns
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card)) {
                                        line.forEachIndexed { index, item ->
                                            val itemKey = fuseItemKey(item)
                                            val requester = remember(itemKey) { FocusRequester() }
                                            val carriesFirst = isFirstLine && index == 0
                                            val effective = if (carriesFirst) firstContent.requester else requester
                                            cardRequesters[itemKey] = effective
                                            val favourite = live && isFavourite(item)
                                            PremiumMediaCard(
                                                item = item,
                                                variant = if (live) TvMediaCardVariant.Live else TvMediaCardVariant.Poster,
                                                favourite = favourite,
                                                // The section heading already names the source.
                                                showProvider = false,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .aspectRatio(if (live) 16f / 9f else 2f / 3f)
                                                    .then(if (carriesFirst) Modifier.carries(firstContent) else Modifier.focusRequester(requester))
                                                    .then(if (isFirstLine) Modifier.focusProperties { up = aboveContent() } else Modifier)
                                                    .then(if (index == 0) Modifier.leftOpensNavigation() else Modifier)
                                                    .tvCardLongPress {
                                                        if (live) repository.toggleFavouriteChannel(item) else actionState = FuseActionState(item, effective)
                                                    },
                                                onFocused = { focusedItemKey = itemKey },
                                                onClick = { if (live) onPlayLive(item) else onOpenDetail(item.type, item.detailLookupId()) },
                                                onLongPress = {
                                                    if (live) repository.toggleFavouriteChannel(item) else actionState = FuseActionState(item, effective)
                                                },
                                            )
                                        }
                                        repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }

                        if (groupedBySource) {
                            shownGroups.forEach { group ->
                                val open = group.items.isNotEmpty() && groupOpen(group.sourceKey)
                                val isFirst = nextIsFirst()
                                item(key = "group:" + group.sourceKey, contentType = "group") {
                                    FuseSourceHeader(
                                        title = sourceNames[group.sourceKey] ?: group.sourceKey,
                                        origin = sourceOrigins[group.sourceKey],
                                        count = group.items.size,
                                        expanded = open,
                                        loading = group.sourceKey in loadingSources,
                                        modifier = Modifier
                                            .then(if (isFirst) Modifier.carries(firstContent).focusProperties { up = aboveContent() } else Modifier)
                                            .leftOpensNavigation(),
                                        onToggle = {
                                            if (open) { openedGroups = openedGroups - group.sourceKey; closedGroups = closedGroups + group.sourceKey }
                                            else { closedGroups = closedGroups - group.sourceKey; openedGroups = openedGroups + group.sourceKey }
                                        },
                                    )
                                }
                                if (open) cardLines(group.items, group.sourceKey)
                            }
                        } else {
                            cardLines(view.items, "")
                        }

                        item(key = "footer") {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (failed) {
                                    Text(
                                        stringResource(R.string.fuse_partial),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                    )
                                    SearchChip(label = stringResource(R.string.action_try_again), selected = false, onClick = { retry = true; requestRound++ })
                                }
                                if (hasMore && !loading) {
                                    SearchChip(label = stringResource(R.string.fuse_load_more), selected = false, onClick = { requestRound++ })
                                }
                                if (loading) {
                                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }

        actionState?.let { state ->
            BrowseItemActionMenu(
                repository = repository,
                item = state.item,
                onDismiss = {
                    val restore = state.restore
                    actionState = null
                    scope.launch {
                        delay(40)
                        runCatching { restore.requestFocus() }
                    }
                },
                onOpenDetail = { onOpenDetail(state.item.type, state.item.detailLookupId()) },
                onChanged = { },
            )
        }
    }
}

/** Where one source's titles begin, and OK to open or close them. */
@Composable
private fun FuseSourceHeader(
    title: String,
    origin: String?,
    count: Int,
    expanded: Boolean,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    val highContrast = LocalTvExperienceSettings.current.highContrast
    Card(
        onClick = { if (count > 0) onToggle() },
        modifier = modifier.fillMaxWidth().height(64.dp),
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.04f),
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
            pressedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(BorderStroke(if (highContrast) 3.dp else 2.dp, MaterialTheme.colorScheme.primary), shape = AppCardShape),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // A short accent stroke, as on the phone: where a source begins, without a slab.
            Box(Modifier.width(4.dp).height(30.dp).background(MaterialTheme.colorScheme.primary, AppPillShape))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                origin?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), maxLines = 1)
                }
            }
            if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            if (count > 0 || !loading) {
                Box(Modifier.background(Color.White.copy(alpha = 0.10f), AppPillShape).padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(count.toString(), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                }
            }
            if (count > 0) {
                Icon(
                    if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}
