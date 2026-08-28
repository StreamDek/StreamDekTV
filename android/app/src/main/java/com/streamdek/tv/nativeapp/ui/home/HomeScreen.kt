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
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import com.streamdek.tv.nativeapp.data.HomeRail
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.BrowseItemActionMenu
import com.streamdek.tv.nativeapp.ui.SuppressBringIntoView
import com.streamdek.tv.nativeapp.ui.TvMotion
import com.streamdek.tv.nativeapp.ui.TvNavRailInset
import com.streamdek.tv.nativeapp.ui.TvSpacing
import com.streamdek.tv.nativeapp.ui.glideToItem
import com.streamdek.tv.nativeapp.ui.LocalSideNavOwnsFocus
import com.streamdek.tv.nativeapp.ui.highResolutionCardArtwork
import com.streamdek.tv.nativeapp.ui.requestFocusOrFalse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The single policy used by launch, login, reset-to-top and side-nav entry into Home. */
internal fun firstFocusableHomeRowIndex(rows: List<HomeRail>): Int =
    rows.indexOfFirst { row -> row.items.isNotEmpty() }

/**
 * The row nearest [preferred] that can actually take the highlight.
 *
 * Only reached when the shelves changed under an open menu and the row the viewer left is gone or
 * has emptied. Searching outward from where they were keeps them roughly where they were looking;
 * forward first, because a row that vanished is usually replaced by the one that moved up into its
 * place. Falls back to the ordinary entry row when nothing near it survived.
 */
internal fun nearestFocusableHomeRowIndex(rows: List<HomeRail>, preferred: Int): Int {
    fun focusable(index: Int) = rows.getOrNull(index)?.items?.isNotEmpty() == true
    if (focusable(preferred)) return preferred
    var offset = 1
    while (preferred - offset >= 0 || preferred + offset <= rows.lastIndex) {
        if (focusable(preferred + offset)) return preferred + offset
        if (focusable(preferred - offset)) return preferred - offset
        offset++
    }
    return firstFocusableHomeRowIndex(rows)
}

/** Why the shell is asking Home to take the highlight. The two answers are not the same. */
enum class HomeEntryMode {
    /** Launch, login, or choosing Home from the menu: the leading card of the first row. */
    Fresh,

    /**
     * Coming straight back out of a menu opened from Home: where the viewer already was.
     *
     * Opening the menu is not leaving the page. Home stays composed underneath it with its shelf
     * and card positions intact, so this is a request to hand the highlight back to the card it
     * came off, not to re-enter the screen.
     */
    Resume,
}

/** One request from the shell for Home to take the highlight. [token] is bumped per request. */
data class HomeEntryRequest(
    val token: Int = 0,
    val mode: HomeEntryMode = HomeEntryMode.Fresh,
)

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
    /**
     * The shell asking Home to take the highlight, and what kind of entry it is.
     *
     * Home cannot be entered by aiming a requester at it from outside. Its entry card belongs to
     * whichever row first has items, and the shelf list only keeps that row composed while it is
     * near the viewport — so for a viewer who had scrolled down before opening the menu, the entry
     * requester is attached to nothing and a single press at it does nothing. Home answers the
     * transfer itself instead: see the effect this request drives.
     */
    navEntry: HomeEntryRequest = HomeEntryRequest(),
    onPositionChanged: (rowId: String, itemKey: String) -> Unit = { _, _ -> },
    onOpenNavigation: () -> Unit = {},
) {
    /**
     * Home places focus on a card several times as it settles — an opening focus, a restored
     * position, a row that finished loading — and each of those is a retry loop. While the side
     * navigation owns the D-pad, none of them may reach for focus.
     */
    val contentFocusSuspended = LocalSideNavOwnsFocus.current
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
    /**
     * Where the active shelf sat when it was last focused.
     *
     * The id above is authoritative while the row exists; this is only the starting point for
     * [nearestFocusableHomeRowIndex] when it does not, so that a row removed while the menu was
     * open sends the viewer to its neighbour rather than back to the top of the page.
     */
    var activeRowIndex by remember { mutableIntStateOf(-1) }
    var focusedItem by remember { mutableStateOf<MediaItem?>(null) }
    /** Full "rowId:itemKey" of the card that should take focus once the shelves compose. */
    var pendingRestoreKey by remember { mutableStateOf<String?>(null) }
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

    // Whether anything on this page holds the highlight. See the focus floor further down.
    var homeHasFocus by remember { mutableStateOf(false) }

    // Home's one entry point, for the shell and for the rail. It sits on the leading card of the
    // first row that has items — or, when there are no rows to show, on the message state's own
    // button, so there is always somewhere for the menu to hand the highlight back to.
    val localFirstCard = remember { FocusRequester() }
    val firstCardRequester = entryFocusRequester ?: localFirstCard

    /**
     * The menu handing the D-pad back to Home.
     *
     * This is not a plain focus request, because Home has no fixed node to aim at. Its entry card
     * belongs to whichever row first has items, and the shelf list only keeps that row attached
     * while it is near the viewport — so a viewer who had travelled down to a lower shelf before
     * opening the menu left it detached, a single request at it did nothing, and the menu stayed
     * open. That read as Right working only while the top row happened to be on screen, and since
     * Continue Watching is usually the top row, as Right depending on Continue Watching existing.
     *
     * The two entry modes land in different places, which is the whole reason Home is asked rather
     * than aimed at:
     *
     * - [HomeEntryMode.Fresh] — launch, login, choosing Home from the menu — takes the leading
     *   card of the first row that has items, whichever row that is.
     * - [HomeEntryMode.Resume] — the menu the viewer opened *from* Home, closed again — hands the
     *   highlight back to the card it came off. Opening the menu is not leaving Home: nothing was
     *   disposed and nothing scrolled, so there is a real position to return to, and sending them
     *   to the top of the page instead throws away where they were.
     *
     * Seeded with the incoming token, so a request from an earlier visit cannot fire on re-entry
     * and pull the highlight off a position the shell is restoring.
     */
    var handledNavEntryToken by remember { mutableIntStateOf(navEntry.token) }
    // Keyed on the answer rather than on the content, so the fifteen-second refresh — which hands
    // back an equal but new row set — cannot cancel a transfer that is still placing focus.
    val firstEntryRowIndex = remember(content?.rails) {
        firstFocusableHomeRowIndex(content?.rails.orEmpty())
    }
    LaunchedEffect(navEntry, firstEntryRowIndex, content?.isComplete, screenState.isLoading) {
        if (navEntry.token == handledNavEntryToken) return@LaunchedEffect
        val rows = content?.rails.orEmpty()
        // Where the viewer was, if this is a return and that place still exists. The row is looked
        // up by id rather than by index because rows stream in and reorder; the remembered index is
        // only the starting point for finding a neighbour when the row itself has gone.
        val resumeRowIndex = rows.indexOfFirst { it.id == activeRowId }
            .takeIf { it >= 0 && rows[it].items.isNotEmpty() }
        val targetIndex = when {
            navEntry.mode == HomeEntryMode.Fresh -> firstEntryRowIndex
            resumeRowIndex != null -> resumeRowIndex
            activeRowIndex >= 0 -> nearestFocusableHomeRowIndex(rows, activeRowIndex)
            else -> firstEntryRowIndex
        }
        val targetRow = rows.getOrNull(targetIndex)
        // Nothing can take the highlight yet. While rows are still arriving, leave the press
        // outstanding and answer it with the first row that lands rather than spending it on a
        // screen that is still a skeleton; the menu stays open in the meantime, which is honest.
        if (targetRow == null && content?.isComplete != true && screenState.isLoading) {
            return@LaunchedEffect
        }
        handledNavEntryToken = navEntry.token
        if (targetRow != null) {
            val resumingInPlace = navEntry.mode == HomeEntryMode.Resume && resumeRowIndex == targetIndex
            // Only the row the viewer actually left keeps its card. A fallback row is one they have
            // not been on, so it opens at its start rather than at a position borrowed from a
            // different row — and the coerce covers the card itself having gone while they were in
            // the menu, which lands on the nearest surviving one.
            val itemIndex = if (resumingInPlace) {
                (rowFocusIndices[targetRow.id] ?: 0).coerceIn(0, targetRow.items.lastIndex)
            } else {
                0
            }
            activeRowId = targetRow.id
            activeRowIndex = targetIndex
            focusedItem = targetRow.items[itemIndex]
            rowFocusIndices[targetRow.id] = itemIndex
            // A resume that lands where it started is already laid out: the shelves did not move
            // while the menu was open, and scrolling them again is precisely the jump being fixed.
            if (!resumingInPlace) {
                rowStates[targetRow.id]?.scrollToItem(itemIndex)
                shelfListState.scrollToItem(targetIndex)
            }
            if (itemIndex > 0 || targetIndex != firstEntryRowIndex) {
                // Any card other than the one the shell's entry requester is attached to is reached
                // through the shelf's own registry, which retries while the card composes.
                pendingRestoreKey = "${targetRow.id}:${homeItemKey(targetRow.items[itemIndex])}"
                repeat(24) {
                    delay(40)
                    if (homeHasFocus) return@LaunchedEffect
                }
                pendingRestoreKey = null
            }
        }
        // The leading card of the entry row, and the floor under a restore that found nothing:
        // retried while the shelves settle, since a row only just scrolled back into the viewport
        // attaches its cards a frame or two later.
        repeat(20) {
            if (homeHasFocus || firstCardRequester.requestFocusOrFalse()) return@LaunchedEffect
            delay(40)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .onFocusChanged { homeHasFocus = it.hasFocus }
            .focusGroup(),
    ) {
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
                entryRequester = firstCardRequester,
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
                entryRequester = firstCardRequester,
            )

            content != null -> {
                val rows = content.rails
                val firstFocusableRowIndex = remember(rows) { firstFocusableHomeRowIndex(rows) }
                val firstFocusableRow = rows.getOrNull(firstFocusableRowIndex)
                // Complete is the normal readiness signal. A partial load that has stopped after
                // an error is also ready enough to navigate; waiting forever for vanished pending
                // rows would leave an otherwise useful Home screen with no focus owner.
                val homeEntryReady = content.isComplete || !screenState.isLoading

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

                var restoreHandledToken by remember { mutableIntStateOf(0) }
                var restoreApplied by remember { mutableStateOf(false) }

                LaunchedEffect(resetToTopToken, homeEntryReady, firstFocusableRow?.id) {
                    if (resetToTopToken <= 0 || !homeEntryReady) return@LaunchedEffect
                    val firstRow = firstFocusableRow ?: return@LaunchedEffect
                    val firstItem = firstRow.items.first()
                    activeRowId = firstRow.id
                    focusedItem = firstItem
                    rowFocusIndices.clear()
                    rowFocusIndices[firstRow.id] = 0
                    rowStates.values.forEach { it.scrollToItem(0) }
                    shelfListState.scrollToItem(0)
                    delay(180)
                    if (!contentFocusSuspended) firstCardRequester.requestFocusOrFalse()
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
                    if (contentFocusSuspended) return@LaunchedEffect
                    pendingRestoreKey = "${row.id}:${homeItemKey(row.items[itemIndex])}"
                }

                // A restore in flight when the viewer opens the menu is abandoned, not deferred.
                // Its retry loop would otherwise spend the next half second pulling the highlight
                // back out of the drawer, one attempt at a time.
                LaunchedEffect(contentFocusSuspended) {
                    if (contentFocusSuspended) pendingRestoreKey = null
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
                LaunchedEffect(homeEntryReady, firstFocusableRow?.id, canRestore) {
                    if (openingFocusApplied) return@LaunchedEffect
                    if (canRestore || restoreApplied || pendingRestoreKey != null) return@LaunchedEffect
                    if (!homeEntryReady || firstFocusableRow == null) return@LaunchedEffect
                    delay(150)
                    if (contentFocusSuspended) return@LaunchedEffect
                    // Marked only once the request was actually made, so a first attempt against a
                    // requester that is not attached yet does not spend the single shot.
                    if (firstCardRequester.requestFocusOrFalse()) openingFocusApplied = true
                }

                // The floor under all three of the focus placements above.
                //
                // Each of them can legitimately come to nothing: a saved card whose row has since
                // been reordered out of the content, an opening shot spent before the shelves were
                // attached, a restore that ran out of retries. Individually that is fine. Together
                // it meant Home could settle with no focus owner at all — the page was drawn and
                // the remote did nothing, which is what returning quickly from the player used to
                // produce. Nothing here competes with the placements above; it only notices that
                // the page has ended up with no highlight and puts one back.
                LaunchedEffect(homeHasFocus, homeEntryReady, firstFocusableRow?.id, contentFocusSuspended) {
                    if (homeHasFocus || !homeEntryReady || firstFocusableRow == null || contentFocusSuspended) {
                        return@LaunchedEffect
                    }
                    repeat(12) {
                        delay(140)
                        if (homeHasFocus || contentFocusSuspended) return@LaunchedEffect
                        // A restore is still working through its own retries. Let it finish.
                        if (pendingRestoreKey == null && firstCardRequester.requestFocusOrFalse()) {
                            return@LaunchedEffect
                        }
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
                                firstCardRequester = if (rowIndex == firstFocusableRowIndex) firstCardRequester else null,
                                focusItemKey = pendingRestoreKey?.takeIf { it.startsWith("${row.id}:") },
                                onFocusItemHandled = { pendingRestoreKey = null },
                                onItemFocused = { index, item ->
                                    rowFocusIndices[row.id] = index
                                    focusedItem = item
                                    activeRowId = row.id
                                    activeRowIndex = rowIndex
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
                                onOpenNavigation = onOpenNavigation,
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
                // A removed Continue Watching card cannot safely receive focus again. Home's
                // refreshed canonical rail will choose the surviving card during recomposition.
                onDismissAfterRemoval = { actionState = null },
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
    /** Home's shared entry target, so the side navigation has somewhere to hand focus back to. */
    entryRequester: FocusRequester? = null,
) {
    val localPrimary = remember { FocusRequester() }
    val primaryRequester = entryRequester ?: localPrimary

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
