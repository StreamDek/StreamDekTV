package com.streamdek.tv.nativeapp.ui.home

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
import com.streamdek.tv.nativeapp.ui.TvSpacing
import com.streamdek.tv.nativeapp.ui.glideToItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class BrowseActionState(
    val item: MediaItem,
    val restoreFocusRequester: FocusRequester,
)

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
    onPositionChanged: (rowId: String, itemKey: String) -> Unit = { _, _ -> },
) {
    val homeViewModel: HomeViewModel = viewModel(factory = remember(repository) { HomeViewModelFactory(repository) })
    val screenState by homeViewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val session by repository.session.collectAsState()
    val bootstrap by repository.bootstrap.collectAsState()
    val reachability by repository.reachability.collectAsState()
    val appPrefs = bootstrap?.preferences?.app
    val portraitCards = appPrefs?.homeRowCardStyle == "portrait"

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

    val loadKey = remember(session?.user?.uid, repository.activeStreamProfile(bootstrap)?.id) {
        "${session?.user?.uid ?: "guest"}:${repository.activeStreamProfile(bootstrap)?.id ?: "default"}"
    }
    LaunchedEffect(loadKey) { homeViewModel.load(loadKey) }

    val content = screenState.content
    val spotlightItem = focusedItem ?: content?.featured ?: content?.rails?.firstOrNull()?.items?.firstOrNull()

    LaunchedEffect(spotlightItem?.id, spotlightItem?.type) {
        homeViewModel.setHeroCandidate(spotlightItem)
    }

    val backgroundColor = MaterialTheme.colorScheme.background

    Box(Modifier.fillMaxSize().background(backgroundColor)) {
        val art = spotlightItem?.backdrop ?: spotlightItem?.poster
        if (!art.isNullOrBlank() && spotlightItem?.type != "network") {
            AsyncImage(
                model = art,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
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

        if (reachability == ApiReachability.Cached && content != null) {
            OfflineNotice(
                Modifier.align(Alignment.TopEnd).padding(top = 22.dp, end = HomeInset),
            )
        }

        when {
            screenState.isLoading && content == null -> HomeFirstLoad(portraitCards)

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

                LaunchedEffect(content.rails.size) {
                    // Only the first few images of the visible rows; a stick has little memory to
                    // spend speculatively and the rest arrive as the viewer travels.
                    buildList {
                        content.featured?.backdrop?.let(::add)
                        rows.take(3).forEach { rail ->
                            rail.items.take(5).forEach { item ->
                                (if (portraitCards) item.poster else item.backdrop)?.let(::add)
                            }
                        }
                    }.distinct().take(14).forEach { url ->
                        context.imageLoader.enqueue(
                            ImageRequest.Builder(context)
                                .data(url).memoryCacheKey(url).diskCacheKey(url)
                                .crossfade(false).allowHardware(true).allowRgb565(true).build(),
                        )
                    }
                }

                // Full "rowId:itemKey" of the card that should take focus once the shelves compose.
                var pendingRestoreKey by remember { mutableStateOf<String?>(null) }
                var restoreHandledToken by remember { mutableIntStateOf(0) }
                var restoreApplied by remember { mutableStateOf(false) }

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
                    pendingRestoreKey = "${row.id}:${homeItemKey(row.items[itemIndex])}"
                }

                LaunchedEffect(rows.isNotEmpty(), canRestore) {
                    if (canRestore || restoreApplied || pendingRestoreKey != null) return@LaunchedEffect
                    if (rows.isEmpty()) return@LaunchedEffect
                    delay(150)
                    runCatching { firstCardRequester.requestFocus() }
                }

                Column(Modifier.fillMaxSize()) {
                    HomeSpotlight(item = spotlightItem, detail = screenState.heroDetail)

                    LazyColumn(
                        state = shelfListState,
                        // weight, not fillMaxSize: as a Column child, filling the parent would put
                        // the list viewport partly off-screen and the lower shelves out of reach.
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 64.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
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
                                        else -> onOpenDetail(item.type, item.id)
                                    }
                                },
                                onItemMenu = { item, requester ->
                                    if (item.type != "network" && item.type != "live") {
                                        actionState = BrowseActionState(item, requester)
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
                    shelfListState.glideToItem(target)
                }
            }

            else -> HomeFirstLoad(portraitCards)
        }

        actionState?.let { state ->
            BrowseItemActionMenu(
                repository = repository,
                item = state.item,
                onDismiss = {
                    val restoreRequester = state.restoreFocusRequester
                    actionState = null
                    scope.launch {
                        delay(40)
                        runCatching { restoreRequester.requestFocus() }
                    }
                },
                onOpenDetail = { onOpenDetail(state.item.type, state.item.id) },
                onChanged = { homeViewModel.forceRefresh(loadKey) },
            )
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
                .height(SpotlightHeight)
                .fillMaxWidth(0.56f)
                .padding(start = HomeInset, top = 34.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            com.streamdek.tv.nativeapp.ui.TvSkeletonBox(Modifier.fillMaxWidth(0.8f).height(52.dp))
            com.streamdek.tv.nativeapp.ui.TvSkeletonBox(Modifier.fillMaxWidth(0.45f).height(20.dp))
            com.streamdek.tv.nativeapp.ui.TvSkeletonBox(Modifier.fillMaxWidth(0.95f).height(16.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
