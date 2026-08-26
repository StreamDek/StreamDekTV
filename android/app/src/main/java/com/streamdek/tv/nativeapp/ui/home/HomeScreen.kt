package com.streamdek.tv.nativeapp.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.streamdek.tv.nativeapp.data.ApiReachability
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.BrowseItemActionMenu
import com.streamdek.tv.nativeapp.ui.SuppressBringIntoView
import com.streamdek.tv.nativeapp.ui.TvMotion
import com.streamdek.tv.nativeapp.ui.TvNavRailInset
import com.streamdek.tv.nativeapp.ui.TvSpacing
import com.streamdek.tv.nativeapp.ui.glideToItem
import com.streamdek.tv.nativeapp.ui.highResolutionCardArtwork
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private data class BrowseActionState(
    val item: MediaItem,
    val restoreFocusRequester: FocusRequester,
    val fromContinueWatching: Boolean = false,
)

/** Stable, null-safe AnimatedContent key for addon items whose decoded fields may be absent. */
private class HomeHeroPresentation(val item: MediaItem?) {
    private val itemKey = "${item?.type.orEmpty()}:${item?.id.orEmpty()}"

    override fun equals(other: Any?): Boolean =
        other is HomeHeroPresentation && itemKey == other.itemKey

    override fun hashCode(): Int = itemKey.hashCode()
}

/**
 * Home.
 *
 * Rebuilt around one structural change: the spotlight and the shelves are rows of a single
 * [Column], not a floating hero over rails that begin at a hardcoded offset. The old arrangement
 * had to guess how tall the hero would be, and it collapsed the hero as soon as any card took
 * focus — which happens on load — so the cinematic treatment it was built for never appeared.
 *
 * The other change is density. Cards were shrunk to 174x103 to buy room for that fixed offset,
 * which is too small to read across a room. With the layout no longer fighting itself the cards
 * go back up to a legible size, and rows the viewer is not on shrink instead, so more of the
 * screen is doing useful work at any moment.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    repository: StreamDekRepository,
    entryFocusRequester: FocusRequester? = null,
    onOpenDetail: (String, String) -> Unit,
    onOpenNetwork: (String, String) -> Unit,
    onOpenAccount: () -> Unit,
    onPlayLive: (MediaItem) -> Unit = {},
    onResumePlayback: (MediaItem) -> Unit = {},
    /** Rail the viewer last had highlighted, restored when they come back to Home. */
    restoreRowId: String? = null,
    /** Card within [restoreRowId] that was last highlighted. */
    restoreItemKey: String? = null,
    /** Bumped by the host to request a position restore. */
    restoreToken: Int = 0,
    /** Bumped after profile selection to discard all remembered shelf/card position. */
    resetToTopToken: Int = 0,
    onPositionChanged: (rowId: String, itemKey: String) -> Unit = { _, _ -> },
) {
    val homeViewModel: HomeViewModel = viewModel(factory = remember(repository) { HomeViewModelFactory(repository) })
    val screenState by homeViewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val session by repository.session.collectAsState()
    val bootstrap by repository.bootstrap.collectAsState()
    val libraryRevision by repository.libraryRevision.collectAsState()
    val reachability by repository.reachability.collectAsState()
    val appPrefs = bootstrap?.preferences?.app
    val portraitCards = appPrefs?.homeRowCardStyle == "portrait"
    // Whatever the shelves do not need. See [spotlightHeight].
    val heroHeight = spotlightHeight(portraitCards)
    // Default-on, so it also holds before preferences have loaded: starting hidden and staying
    // hidden beats showing a paragraph that vanishes a moment later.
    val hideHomeSynopsis = appPrefs?.hideHomeSynopsis != false
    // Portrait only, whatever the stored value says. The preference syncs, and another client --
    // or this one before the rows were switched back to landscape -- can leave it on for a layout
    // where the title is the only thing identifying the card.
    val hideCardTitles = portraitCards && appPrefs?.hideHomeCardTitles == true

    val shelfListState = rememberLazyListState()
    val rowStates = remember { mutableMapOf<String, LazyListState>() }
    val rowFocusIndices = remember { mutableStateMapOf<String, Int>() }
    val scope = rememberCoroutineScope()
    /**
     * The active shelf is tracked by id, not position.
     *
     * Rows stream in, so a shelf that resolves above the focused one shifts every index below it.
     * An index held across that shift silently points at the wrong shelf — which showed up as the
     * focused row drawing compact while the row above it, no longer focused, stayed full size.
     */
    var activeRowId by remember { mutableStateOf<String?>(null) }
    var focusedItem by remember { mutableStateOf<MediaItem?>(null) }
    var actionState by remember { mutableStateOf<BrowseActionState?>(null) }

    val homeContentConfiguration = remember(bootstrap) {
        val builtIns = bootstrap?.preferences?.home?.defaultAppCatalogsEnabled != false
        val addons = bootstrap?.integrations?.addons?.items.orEmpty().joinToString("|") {
            "${it.id}:${it.enabled}:${it.position}"
        }
        "$builtIns:$addons"
    }
    val loadKey = remember(session?.user?.uid, repository.activeStreamProfile(bootstrap)?.id, homeContentConfiguration) {
        "${session?.user?.uid ?: "guest"}:${repository.activeStreamProfile(bootstrap)?.id ?: "default"}:$homeContentConfiguration"
    }
    LaunchedEffect(loadKey) {
        // A retained HomeViewModel must not turn its first snapshot into a session-long cache.
        // Refresh immediately when Home is re-entered, then poll only while this screen is visible
        // so progress written by another device appears without restarting the TV app.
        if (homeViewModel.uiState.value.content == null) homeViewModel.load(loadKey)
        else homeViewModel.forceRefresh(loadKey)
        while (true) {
            delay(15_000L)
            homeViewModel.forceRefresh(loadKey)
        }
    }
    LaunchedEffect(loadKey, libraryRevision) {
        if (libraryRevision > 0L) homeViewModel.forceRefresh(loadKey)
    }

    val content = screenState.content
    val spotlightItem = focusedItem ?: content?.featured ?: content?.rails?.firstOrNull()?.items?.firstOrNull()
    var initialArtworkReady by remember(loadKey) { mutableStateOf(false) }
    val initialArtworkUrls = remember(content, portraitCards) {
        buildList {
            content?.featured?.let { featured ->
                (featured.backdrop ?: featured.poster)?.let(::add)
                featured.titleLogo?.let(::add)
            }
            // Two shelves cover the first TV viewport. Warming more here competes with the hero
            // and first row for bandwidth without improving what is initially visible.
            content?.rails.orEmpty().take(2).forEach { rail ->
                rail.items.take(6).forEach { item ->
                    highResolutionCardArtwork(
                        if (portraitCards) item.poster ?: item.backdrop else item.backdrop ?: item.poster,
                        portrait = portraitCards,
                    )?.let(::add)
                }
            }
        }.distinct()
    }
    val initialArtworkKey = initialArtworkUrls.joinToString("|")

    LaunchedEffect(loadKey, initialArtworkKey) {
        if (initialArtworkReady || content == null || initialArtworkUrls.isEmpty()) {
            if (content != null && initialArtworkUrls.isEmpty()) initialArtworkReady = true
            return@LaunchedEffect
        }
        // Warm the complete first viewport in parallel. A bounded wait keeps a slow or broken
        // artwork host from delaying Home, while successful requests let the shelves fade in as
        // one composed presentation instead of exposing individual Coil completions.
        withTimeoutOrNull(2_200L) {
            coroutineScope {
                initialArtworkUrls.mapIndexed { index, url ->
                    async {
                        context.imageLoader.execute(
                            ImageRequest.Builder(context)
                                .data(url)
                                .memoryCacheKey(url)
                                .diskCacheKey(url)
                                .size(if (index <= 1) 1280 else if (portraitCards) 360 else 480,
                                    if (index <= 1) 720 else if (portraitCards) 540 else 270)
                                .crossfade(false)
                                .allowHardware(true)
                                .allowRgb565(index > 1 && !portraitCards)
                                .build(),
                        )
                    }
                }.awaitAll()
            }
        }
        initialArtworkReady = true
    }
    val contentRevealAlpha by animateFloatAsState(
        targetValue = if (initialArtworkReady) 1f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "home-initial-reveal",
    )

    LaunchedEffect(spotlightItem?.id, spotlightItem?.type) {
        homeViewModel.setHeroCandidate(spotlightItem)
    }

    val backgroundColor = MaterialTheme.colorScheme.background
    // Hoisted out of the transitionSpec lambdas, which are not composable and so cannot read the
    // viewer's motion settings themselves. Both hero crossfades share these, so the artwork and the
    // copy over it change on exactly the same curve rather than nearly the same one.
    val heroFadeIn = TvMotion.fadeInSpec(TvMotion.Quick)
    val heroFadeOut = TvMotion.fadeOutSpec(TvMotion.Instant)

    Box(Modifier.fillMaxSize().background(backgroundColor)) {
        val art = (spotlightItem?.backdrop ?: spotlightItem?.poster)
            ?.takeIf { initialArtworkReady }
            ?.takeIf { spotlightItem?.type != "network" }
        AnimatedContent(
            targetState = art,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { heroFadeIn togetherWith heroFadeOut },
            label = "home-hero-backdrop",
        ) { imageUrl ->
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageUrl)
                        .memoryCacheKey(imageUrl)
                        .diskCacheKey(imageUrl)
                        .crossfade(false)
                        .allowHardware(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        Box(
            Modifier.fillMaxSize().drawWithCache {
                val (readingScrim, baseFade) = homeScrim(backgroundColor)
                onDrawBehind {
                    drawRect(readingScrim)
                    drawRect(baseFade)
                }
            },
        )

        // The artwork above runs to the edge of the screen; everything the viewer can reach starts
        // clear of the navigation rail. Insetting the whole route instead — which is what the shell
        // used to do — left a band of flat background down the left for the rail to sit on, so a
        // transparent rail had nothing to be transparent against and read as solid.
        Box(Modifier.fillMaxSize().padding(start = TvNavRailInset)) {
        if (reachability == ApiReachability.Cached && content != null) {
            OfflineNotice(
                Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = HomeInset),
            )
        }

        when {
            (screenState.isLoading && content == null) || (content != null && !initialArtworkReady) -> HomeFirstLoad(portraitCards)

            screenState.error != null && content == null -> HomeMessage(
                title = "Home failed to load",
                message = screenState.error ?: "Could not load home",
                primaryLabel = if (screenState.isLoading) "Retrying…" else "Try Again",
                primaryEnabled = !screenState.isLoading,
                onPrimary = { homeViewModel.forceRefresh(loadKey) },
            )

            content != null && content.rails.isEmpty() && content.isComplete -> HomeMessage(
                title = "Nothing to show yet",
                message = "This profile has no home rows turned on and no add-on catalogues to " +
                    "fill them. Enable an add-on or turn the built-in catalogues back on.",
                primaryLabel = if (screenState.isLoading) "Refreshing…" else "Refresh",
                primaryEnabled = !screenState.isLoading,
                onPrimary = { homeViewModel.forceRefresh(loadKey) },
                secondaryLabel = "Open Settings",
                onSecondary = onOpenAccount,
            )

            content != null -> {
                val rows = content.rails
                val localFirstCard = remember { FocusRequester() }
                val firstCardRequester = entryFocusRequester ?: localFirstCard

                LaunchedEffect(content.rails.size, screenState.prefetchedTitleLogos) {
                    // Only the first few images of the visible rows; a stick has little memory to
                    // spend speculatively and the rest arrive as the viewer travels.
                    val artwork = buildList {
                        content.featured?.backdrop?.let(::add)
                        rows.take(3).forEach { rail ->
                            rail.items.take(5).forEach { item ->
                                (if (portraitCards) item.poster else item.backdrop)?.let(::add)
                            }
                        }
                    }.distinct().take(14)
                    val titleLogos = buildList {
                        content.featured?.titleLogo?.let(::add)
                        rows.take(3).forEach { rail ->
                            rail.items.take(6).forEach { item -> item.titleLogo?.let(::add) }
                        }
                        addAll(screenState.prefetchedTitleLogos)
                    }.distinct().take(20)
                    titleLogos.forEach { url ->
                        context.imageLoader.enqueue(
                            ImageRequest.Builder(context)
                                .data(url).memoryCacheKey(url).diskCacheKey(url)
                                .size(640, 180)
                                .crossfade(false).allowHardware(true).allowRgb565(false).build(),
                        )
                    }
                    artwork.forEach { url ->
                        context.imageLoader.enqueue(
                            ImageRequest.Builder(context)
                                .data(url).memoryCacheKey(url).diskCacheKey(url)
                                .crossfade(false).allowHardware(true).allowRgb565(true).build(),
                        )
                    }
                }

                LaunchedEffect(content.rails.size) {
                    homeViewModel.prefetchHeroCandidates(
                        buildList {
                            content.featured?.let(::add)
                            rows.take(3).forEach { rail -> addAll(rail.items.take(6)) }
                        },
                    )
                }

                // Full "rowId:itemKey" of the card that should take focus once the shelves compose.
                var pendingRestoreKey by remember { mutableStateOf<String?>(null) }
                var restoreHandledToken by remember { mutableIntStateOf(0) }
                var restoreApplied by remember { mutableStateOf(false) }

                LaunchedEffect(resetToTopToken, rows) {
                    if (resetToTopToken <= 0 || rows.isEmpty()) return@LaunchedEffect
                    val firstRow = rows.first()
                    val firstItem = firstRow.items.firstOrNull() ?: return@LaunchedEffect
                    activeRowId = firstRow.id
                    focusedItem = firstItem
                    rowFocusIndices.clear()
                    rowFocusIndices[firstRow.id] = 0
                    rowStates.values.forEach { it.scrollToItem(0) }
                    shelfListState.scrollToItem(0)
                    delay(180)
                    runCatching { firstCardRequester.requestFocus() }
                }

                val canRestore = restoreToken > 0 &&
                    restoreToken != restoreHandledToken &&
                    !restoreRowId.isNullOrBlank() &&
                    !restoreItemKey.isNullOrBlank() &&
                    rows.any { it.id == restoreRowId }

                LaunchedEffect(restoreToken, rows) {
                    if (!canRestore) return@LaunchedEffect
                    restoreHandledToken = restoreToken
                    restoreApplied = true
                    val rowIndex = rows.indexOfFirst { it.id == restoreRowId }
                    if (rowIndex < 0) return@LaunchedEffect
                    val row = rows[rowIndex]
                    val itemIndex = row.items.indexOfFirst { homeItemKey(it) == restoreItemKey }
                        .takeIf { it >= 0 } ?: 0
                    activeRowId = row.id
                    rowFocusIndices[row.id] = itemIndex
                    focusedItem = row.items.getOrNull(itemIndex)
                    shelfListState.scrollToItem(rowIndex)
                    // Let the navigation transition finish restoring its outgoing focus before
                    // applying Home's saved card. Otherwise the rail can win the final focus pass
                    // and remain expanded even though the viewer has already returned Home.
                    delay(320)
                    pendingRestoreKey = "${row.id}:${homeItemKey(row.items[itemIndex])}"
                }

                // Home places focus on its first card when it opens, and only then.
                //
                // This used to re-run every time `canRestore` flipped, and it flips whenever the
                // rows reload and the saved row is briefly absent from them — which happens on any
                // refresh, long after the page opened. A viewer who was in the navigation rail at
                // that moment had focus taken off them 150ms later: the rail collapsed mid-press,
                // and because re-entering restores the last focused item, the highlight sat one
                // step further down each time. Down, collapse, re-open one lower, over and over.
                var openingFocusApplied by remember { mutableStateOf(false) }
                LaunchedEffect(rows.isNotEmpty(), canRestore) {
                    if (openingFocusApplied) return@LaunchedEffect
                    if (canRestore || restoreApplied || pendingRestoreKey != null) return@LaunchedEffect
                    if (rows.isEmpty()) return@LaunchedEffect
                    delay(150)
                    // Marked only once the request was actually made, so a first attempt against a
                    // requester that is not attached yet does not spend the single shot.
                    if (runCatching { firstCardRequester.requestFocus(); true }.getOrDefault(false)) {
                        openingFocusApplied = true
                    }
                }

                Column(Modifier.fillMaxSize().graphicsLayer { alpha = contentRevealAlpha }) {
                    AnimatedContent(
                        // The focused item owns the transition. Detail metadata arrives shortly
                        // afterwards and must update in place; treating it as a second target made
                        // the title/logo replay the whole hero crossfade and visibly flicker.
                        targetState = HomeHeroPresentation(spotlightItem),
                        transitionSpec = { heroFadeIn togetherWith heroFadeOut },
                        label = "home-hero-copy",
                    ) { hero ->
                        val heroItem = hero.item
                        val matchingDetail = screenState.heroDetail?.takeIf { detail ->
                            heroItem != null && (
                                detail.id == heroItem.id ||
                                    (detail.tmdbId > 0 && detail.tmdbId == heroItem.tmdbId) ||
                                    detail.id == heroItem.detailLookupId()
                                )
                        }
                        HomeSpotlight(
                            item = heroItem,
                            detail = matchingDetail,
                            height = heroHeight,
                            hideSynopsis = hideHomeSynopsis,
                        )
                    }

                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides SuppressBringIntoView,
                    ) {
                    LazyColumn(
                        state = shelfListState,
                        // weight, not fillMaxSize: as a Column child, filling the parent would put
                        // the list viewport partly off-screen and the lower shelves out of reach.
                        modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
                        // Enough trailing range for even the final shelf to align with the top of
                        // this viewport. A short footer makes LazyColumn clamp near the end and
                        // leaves the preceding shelf visible, regardless of later scroll retries.
                        contentPadding = PaddingValues(bottom = heroHeight + 48.dp),
                        verticalArrangement = Arrangement.spacedBy(ShelfSpacing),
                    ) {
                        itemsIndexed(rows, key = { _, row -> row.id }) { rowIndex, row ->
                            val rowState = rowStates.getOrPut(row.id) {
                                LazyListState(firstVisibleItemIndex = rowFocusIndices[row.id] ?: 0)
                            }
                            HomeShelf(
                                row = row,
                                rowState = rowState,
                                compact = activeRowId != null && activeRowId != row.id,
                                portraitCards = portraitCards,
                                hideCardTitles = hideCardTitles,
                                firstCardRequester = if (rowIndex == 0) firstCardRequester else null,
                                focusItemKey = pendingRestoreKey?.takeIf { it.startsWith("${row.id}:") },
                                onFocusItemHandled = { pendingRestoreKey = null },
                                onItemFocused = { index, item ->
                                    rowFocusIndices[row.id] = index
                                    focusedItem = item
                                    activeRowId = row.id
                                    onPositionChanged(row.id, homeItemKey(item))
                                },
                                onItemPressed = { item ->
                                    when {
                                        item.type == "network" -> onOpenNetwork(item.id, item.title)
                                        // Live broadcasts skip detail and play straight away.
                                        item.type == "live" -> onPlayLive(item)
                                        row.id == "continue-watching" -> onResumePlayback(item)
                                        else -> onOpenDetail(item.type, item.detailLookupId())
                                    }
                                },
                                onItemMenu = { item, requester ->
                                    if (item.type != "network" && item.type != "live") {
                                        actionState = BrowseActionState(
                                            item,
                                            requester,
                                            fromContinueWatching = row.id == "continue-watching",
                                        )
                                    }
                                },
                            )
                        }

                        // Rows still loading, held in the position they will occupy so the shelves
                        // above keep their place when one lands.
                        items(content.pendingRails, key = { it.id }) { pending ->
                            HomeSkeletonShelf(pending = pending, portraitCards = portraitCards)
                        }
                    }
                    }
                }

                LaunchedEffect(activeRowId, rows.size) {
                    if (rows.isEmpty()) return@LaunchedEffect
                    val target = rows.indexOfFirst { it.id == activeRowId }.takeIf { it >= 0 }
                        ?: return@LaunchedEffect
                    // The focused shelf is pinned to the top of the viewport, so the row being
                    // left goes fully out of view rather than lingering half on screen. Anything
                    // partly visible above the active row reads as clutter on a TV and makes the
                    // row you are actually using look like it is somewhere in the middle.
                    //
                    // Safe to do unconditionally now that the active shelf is tracked by id: the
                    // earlier version chased a shifting index while rows were still arriving.
                    // One uninterrupted curve owns the vertical move. Shelf geometry stays fixed
                    // while compact/full-size presentation runs as a GPU scale, so no correction
                    // or layout snap is needed after the animation.
                    shelfListState.glideToItem(target)
                }
            }

            else -> HomeFirstLoad(portraitCards)
        }

        actionState?.let { state ->
            BrowseItemActionMenu(
                repository = repository,
                item = state.item,
                showRemoveFromContinueWatching = state.fromContinueWatching,
                onDismiss = {
                    val restoreRequester = state.restoreFocusRequester
                    actionState = null
                    scope.launch {
                        delay(40)
                        runCatching { restoreRequester.requestFocus() }
                    }
                },
                onOpenDetail = { onOpenDetail(state.item.type, state.item.detailLookupId()) },
                onChanged = { homeViewModel.forceRefresh(loadKey) },
            )
        }
        }
    }
}

/**
 * Cold start. Shaped like the finished screen rather than a spinner, so the first frame already
 * tells the viewer where things will be — and because rows stream in, most of this is replaced
 * within a second or two.
 */
@Composable
private fun HomeFirstLoad(portraitCards: Boolean) {
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .height(spotlightHeight(portraitCards))
                .fillMaxWidth(0.56f)
                .padding(start = HomeInset, top = 34.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            com.streamdek.tv.nativeapp.ui.TvSkeletonBox(Modifier.fillMaxWidth(0.8f).height(52.dp))
            com.streamdek.tv.nativeapp.ui.TvSkeletonBox(Modifier.fillMaxWidth(0.45f).height(20.dp))
            com.streamdek.tv.nativeapp.ui.TvSkeletonBox(Modifier.fillMaxWidth(0.95f).height(16.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(ShelfSpacing)) {
            repeat(2) {
                HomeSkeletonShelf(
                    pending = com.streamdek.tv.nativeapp.data.PendingRail(
                        id = "loading-$it",
                        title = " ",
                        portrait = portraitCards,
                    ),
                    portraitCards = portraitCards,
                )
            }
        }
    }
}

/** Failure and empty states share one shape, both with somewhere for the remote to land. */
@Composable
private fun HomeMessage(
    title: String,
    message: String,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val primaryRequester = remember { FocusRequester() }

    LaunchedEffect(title) {
        delay(120)
        runCatching { primaryRequester.requestFocus() }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = HomeInset),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth(0.62f),
        )
        Row(
            modifier = Modifier.padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onPrimary,
                enabled = primaryEnabled,
                modifier = Modifier.focusRequester(primaryRequester),
                shape = ButtonDefaults.shape(AppPillShape),
            ) { Text(primaryLabel) }
            if (secondaryLabel != null && onSecondary != null) {
                OutlinedButton(onClick = onSecondary, shape = ButtonDefaults.shape(AppPillShape)) {
                    Text(secondaryLabel)
                }
            }
        }
    }
}

/** Quiet marker that the network is gone and these shelves came out of the cache. */
@Composable
private fun OfflineNotice(modifier: Modifier = Modifier) {
    Text(
        text = "Offline — showing saved content",
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = Color(0xFF0B0B0B),
        modifier = modifier
            .background(Color(0xF2E9C46A), AppPillShape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}
