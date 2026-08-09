package com.streamdek.tv.nativeapp.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.lifecycle.viewmodel.compose.viewModel
import com.streamdek.tv.nativeapp.data.ApiReachability
import kotlinx.coroutines.delay
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.streamdek.tv.nativeapp.data.HomeRail
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.BrowseItemActionMenu
import com.streamdek.tv.nativeapp.ui.tvCardLongPress
import com.streamdek.tv.nativeapp.ui.ProgressMeter
import com.streamdek.tv.nativeapp.ui.animateToAnchoredItem
import com.streamdek.tv.nativeapp.ui.formatPlaybackClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Where the rails viewport begins. Sized so the hero (including its synopsis) has clear
// breathing room above, while the remaining height still fits the active row plus about
// a third of the row beneath it as a scroll affordance.
private val HomeRailsTop = 322.dp
private val HomeRailsTopCompact = 292.dp

private data class BrowseActionState(
    val item: MediaItem,
    val restoreFocusRequester: FocusRequester,
)

private fun mediaItemStableKey(item: MediaItem): String {
    val episodeSuffix = item.episode?.let { ":s${it.seasonNumber}:e${it.episodeNumber}" }.orEmpty()
    return "${item.type}:${item.id}$episodeSuffix"
}

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
    val compactMode = appPrefs?.compactMode == true
    val homeRowCardStyle = if (appPrefs?.homeRowCardStyle == "portrait") "portrait" else "landscape"
    val verticalListState = rememberLazyListState()
    val horizontalListStates = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }
    val rowFocusIndices = remember { mutableStateMapOf<String, Int>() }
    val scope = rememberCoroutineScope()
    var activeRowIndex by remember { mutableIntStateOf(0) }
    var focusedItem by remember { mutableStateOf<MediaItem?>(null) }
    var actionState by remember { mutableStateOf<BrowseActionState?>(null) }
    val heroDetail = screenState.heroDetail

    val loadKey = remember(session?.user?.uid, repository.activeStreamProfile(bootstrap)?.id) {
        "${session?.user?.uid ?: "guest"}:${repository.activeStreamProfile(bootstrap)?.id ?: "default"}"
    }
    LaunchedEffect(loadKey) {
        homeViewModel.load(loadKey)
    }

    val content = screenState.content
    val hero = focusedItem ?: content?.featured ?: content?.rails?.firstOrNull()?.items?.firstOrNull()

    LaunchedEffect(hero?.id, hero?.type) {
        homeViewModel.setHeroCandidate(hero)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val heroBackdrop = hero?.backdrop ?: hero?.poster

        if (hero?.type == "network") {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(networkHeroBrush(hero.title)),
            )
        } else if (!heroBackdrop.isNullOrBlank()) {
            AsyncImage(
                model = heroBackdrop,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val leftFade = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF040404),
                            Color(0xE6040404),
                            Color(0x90040404),
                            Color.Transparent,
                        ),
                        endX = size.width * 0.52f,
                    )
                    val bottomFade = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x66040404),
                            Color(0xE8040404),
                            Color(0xFF040404),
                        ),
                        startY = size.height * 0.62f,
                    )
                    onDrawBehind {
                        drawRect(leftFade)
                        drawRect(bottomFade)
                    }
                },
        )

        if (reachability == ApiReachability.Cached && content != null) {
            CachedContentNotice(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 24.dp, end = 40.dp),
            )
        }

        when {
            screenState.isLoading && content == null -> HomeLoading()
            screenState.error != null && content == null -> HomeError(
                message = screenState.error ?: "Could not load home",
                isRetrying = screenState.isLoading,
                onRetry = { homeViewModel.forceRefresh(loadKey) },
            )
            // Genuinely nothing to show: every built-in row is switched off for this profile and
            // no addon supplied one either. Say so, rather than leaving the screen blank.
            content != null && content.rails.isEmpty() -> HomeEmpty(
                isRetrying = screenState.isLoading,
                onRetry = { homeViewModel.forceRefresh(loadKey) },
                onOpenAccount = onOpenAccount,
            )
            content != null -> {
                LaunchedEffect(content) {
                    val preloadUrls = buildList {
                        content.featured?.backdrop?.let(::add)
                        content.featured?.titleLogo?.let(::add)
                        content.rails.forEach { rail ->
                            rail.items.take(4).forEach { item ->
                                item.backdrop?.let(::add)
                                item.poster?.let(::add)
                            }
                        }
                    }.distinct().take(16)
                    preloadUrls.forEach { url ->
                        context.imageLoader.enqueue(
                            ImageRequest.Builder(context)
                                .data(url)
                                .memoryCacheKey(url)
                                .diskCacheKey(url)
                                .crossfade(false)
                                .allowHardware(true)
                                .build(),
                        )
                    }
                }

                val rows = remember(content.rails) {
                    val list = content.rails.toMutableList()
                    val netIdx = list.indexOfFirst { rail -> rail.items.any { it.type == "network" } }
                    if (netIdx > 0) {
                        // The networks rail and the live addon rails that follow it move
                        // as one block, so live TV keeps sitting directly beneath
                        // Streaming Services exactly as it does on mobile.
                        var end = netIdx + 1
                        while (end < list.size && list[end].isLive) end++
                        val block = List(end - netIdx) { list.removeAt(netIdx) }
                        val target = minOf(3, list.size)
                        list.addAll(target, block)
                    }
                    list.toList()
                }
                val localHomeFirstCardRequester = remember { FocusRequester() }
                val homeFirstCardRequester = entryFocusRequester ?: localHomeFirstCardRequester

                // Full key ("rowId:itemKey") of the card that should take focus once the
                // rails compose, used when returning to Home from another screen.
                var pendingRestoreKey by remember { mutableStateOf<String?>(null) }
                var restoreHandledToken by remember { mutableIntStateOf(0) }
                // Once a position has been restored, the first-card grab must never run
                // again for this composition or it would steal focus back.
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
                    val itemIndex = row.items.indexOfFirst { mediaItemStableKey(it) == restoreItemKey }
                        .takeIf { it >= 0 } ?: 0
                    activeRowIndex = rowIndex
                    rowFocusIndices[row.id] = itemIndex
                    focusedItem = row.items.getOrNull(itemIndex)
                    verticalListState.scrollToItem(rowIndex)
                    pendingRestoreKey = "${row.id}:${mediaItemStableKey(row.items[itemIndex])}"
                }

                LaunchedEffect(content, canRestore) {
                    // Only grab the first card when there is no saved position to return to.
                    if (canRestore || restoreApplied || pendingRestoreKey != null) return@LaunchedEffect
                    delay(150)
                    try { homeFirstCardRequester.requestFocus() } catch (_: Exception) { }
                }

                HeroBlock(
                    item = hero,
                    detail = heroDetail,
                    compact = focusedItem != null,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            start = if (compactMode) 36.dp else 48.dp,
                            top = if (compactMode) 42.dp else 56.dp,
                            end = if (compactMode) 36.dp else 48.dp,
                        )
                        .fillMaxWidth(0.52f),
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = if (compactMode) HomeRailsTopCompact else HomeRailsTop)
                        .clipToBounds(),
                ) {
                    LazyColumn(
                        state = verticalListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compactMode) 18.dp else 22.dp),
                    ) {
                        itemsIndexed(rows, key = { _, row -> row.id }) { rowIndex, row ->
                            val rowState = horizontalListStates.getOrPut(row.id) {
                                androidx.compose.foundation.lazy.LazyListState(
                                    firstVisibleItemIndex = rowFocusIndices[row.id] ?: 0,
                                )
                            }
                            RailSection(
                                row = row,
                                rowState = rowState,
                                anchoredIndex = rowFocusIndices[row.id] ?: 0,
                                onItemFocused = { index, item ->
                                    rowFocusIndices[row.id] = index
                                    focusedItem = item
                                    if (activeRowIndex != rowIndex) {
                                        activeRowIndex = rowIndex
                                    }
                                    // Report upward so the position survives navigation away.
                                    onPositionChanged(row.id, mediaItemStableKey(item))
                                },
                                onItemPressed = { item ->
                                    when (item.type) {
                                        "network" -> onOpenNetwork(item.id, item.title)
                                        // Live broadcasts skip the detail screen and play directly.
                                        "live" -> onPlayLive(item)
                                        else -> if (row.id == "continue-watching") {
                                            onResumePlayback(item)
                                        } else {
                                            onOpenDetail(item.type, item.id)
                                        }
                                    }
                                },
                                onItemMenu = { item, requester ->
                                    if (item.type != "network" && item.type != "live") {
                                        actionState = BrowseActionState(item, requester)
                                    }
                                },
                                rowCardStyle = homeRowCardStyle,
                                firstCardRequester = if (rowIndex == 0) homeFirstCardRequester else null,
                                focusItemKey = pendingRestoreKey?.takeIf { it.startsWith("${row.id}:") },
                                onFocusItemHandled = { pendingRestoreKey = null },
                            )
                        }
                    }
                }

                LaunchedEffect(activeRowIndex) {
                    if (rows.isNotEmpty()) {
                        val targetIndex = activeRowIndex.coerceIn(0, rows.lastIndex)
                        // Pin the active row to the top of the rails viewport so the row
                        // beneath it is always partially visible, instead of only
                        // scrolling once the active row falls off-screen.
                        if (verticalListState.firstVisibleItemIndex != targetIndex ||
                            verticalListState.firstVisibleItemScrollOffset != 0
                        ) {
                            verticalListState.animateScrollToItem(targetIndex)
                        }
                    }
                }
            }
            else -> HomeLoading()
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
                onChanged = {
                    homeViewModel.forceRefresh(loadKey)
                },
            )
        }
    }
}

@Composable
private fun HomeLoading() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(
            text = "Loading StreamDek TV",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun HomeError(message: String, isRetrying: Boolean, onRetry: () -> Unit) {
    val retryRequester = remember { FocusRequester() }

    // Without focus here the screen is a dead end: there is nothing for the remote to land on and
    // no way back other than force-quitting the app.
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { retryRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Home failed to load",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(
            onClick = onRetry,
            enabled = !isRetrying,
            modifier = Modifier
                .padding(top = 24.dp)
                .focusRequester(retryRequester),
            shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
        ) {
            Text(if (isRetrying) "Retrying…" else "Try Again")
        }
    }
}

@Composable
private fun HomeEmpty(isRetrying: Boolean, onRetry: () -> Unit, onOpenAccount: () -> Unit) {
    val retryRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(120)
        runCatching { retryRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Nothing to show yet",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "This profile has no home rows turned on and no add-on catalogues to fill them. " +
                "Enable an add-on or turn the built-in catalogues back on to start browsing.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(
            modifier = Modifier.padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onRetry,
                enabled = !isRetrying,
                modifier = Modifier.focusRequester(retryRequester),
                shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
            ) {
                Text(if (isRetrying) "Refreshing…" else "Refresh")
            }
            Button(
                onClick = onOpenAccount,
                shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
            ) {
                Text("Open Settings")
            }
        }
    }
}

/** Quiet notice that the network is gone and the rows on screen came out of the cache. */
@Composable
private fun CachedContentNotice(modifier: Modifier = Modifier) {
    Text(
        text = "Offline — showing saved content",
        style = MaterialTheme.typography.labelLarge,
        color = Color(0xFF0B0B0B),
        modifier = modifier
            .background(Color(0xF2E9C46A), RoundedCornerShape(999.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun HeroBlock(
    item: MediaItem?,
    detail: MediaDetail?,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    if (item == null) return

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = when (item.type) {
                "tv" -> "Series"
                "network" -> "Streaming Service"
                "live" -> "Live"
                else -> "Movie"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        val titleLogo = detail?.titleLogo ?: item.titleLogo
        if (!titleLogo.isNullOrBlank()) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(titleLogo)
                    .crossfade(false)
                    .build(),
                contentDescription = item.title,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .width(256.dp)
                    .height(if (item.type == "network") 77.dp else 88.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val meta = buildList {
            detail?.genreNames?.take(2)?.forEach(::add)
            item.year?.let(::add)
            item.rating?.let { add("IMDb ${"%.1f".format(it)}") }
        }.joinToString("  |  ")

        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
            )
        }

        (detail?.description ?: item.description)?.takeIf { it.isNotBlank() }?.let { description ->
            // Smaller and shorter than the mobile hero: the reclaimed vertical space is
            // what lets the row below the active row peek into view.
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                // Guarantees clear separation from the first content row even when the
                // synopsis runs to its full three lines.
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun RailSection(
    row: HomeRail,
    rowState: androidx.compose.foundation.lazy.LazyListState,
    anchoredIndex: Int,
    onItemFocused: (Int, MediaItem) -> Unit,
    onItemPressed: (MediaItem) -> Unit,
    onItemMenu: (MediaItem, FocusRequester) -> Unit,
    rowCardStyle: String,
    firstCardRequester: FocusRequester? = null,
    /** Full "rowId:itemKey" of a card that should take focus, or null. */
    focusItemKey: String? = null,
    onFocusItemHandled: () -> Unit = {},
) {
    val requesters = remember(row.id) { mutableMapOf<String, FocusRequester>() }

    // Cards register their requesters as they compose, so retry briefly until the
    // target card exists rather than dropping the restore.
    LaunchedEffect(focusItemKey, row.items.size) {
        val target = focusItemKey ?: return@LaunchedEffect
        repeat(8) {
            val requester = requesters[target]
            if (requester != null && runCatching { requester.requestFocus() }.isSuccess) {
                onFocusItemHandled()
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(60)
        }
        onFocusItemHandled()
    }
    val noScrollResponder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = localRect
            override suspend fun bringChildIntoView(localRect: () -> Rect?) { }
        }
    }

    LaunchedEffect(anchoredIndex, row.items.size) {
        rowState.animateToAnchoredItem(
            focusedIndex = anchoredIndex,
            itemCount = row.items.size,
            leadingItems = 0,
        )
    }


    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewResponder(noScrollResponder),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = row.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 52.dp),
        )

        // Add a little vertical breathing room so focused-scale cards do not get
        // clipped against the viewport edges while the row stays visually stable.
        Box(modifier = Modifier.padding(vertical = 6.dp)) {
        LazyRow(
            state = rowState,
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = 52.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(row.items, key = { _, item -> "${row.id}:${mediaItemStableKey(item)}" }) { index, item ->
                val key = "${row.id}:${mediaItemStableKey(item)}"
                val requester = requesters.getOrPut(key) { FocusRequester() }
                val effectiveRequester = if (index == 0 && firstCardRequester != null) firstCardRequester else requester

                MediaPosterCard(
                    item = item,
                    cardStyle = rowCardStyle,
                    modifier = Modifier.focusRequester(effectiveRequester),
                    onFocused = {
                        onItemFocused(index, item)
                    },
                    onPressed = { onItemPressed(item) },
                    onMenuPressed = { onItemMenu(item, effectiveRequester) },
                )
            }
        }
        } // end BringIntoViewResponder wrapper Box
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MediaPosterCard(
    item: MediaItem,
    cardStyle: String,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onPressed: () -> Unit,
    onMenuPressed: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val isNetworkCard = item.type == "network"
    val networkStyle = remember(item.title) { networkSurfaceStyle(item.title) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val usePortraitCard = cardStyle == "portrait" && !isNetworkCard
    // Cards are deliberately compact: the height reclaimed here is what gives the hero
    // room to breathe above the rails while still leaving the next row peeking below.
    val cardWidth = when {
        isNetworkCard -> 196.dp
        usePortraitCard -> 74.dp
        else -> 174.dp
    }
    val cardHeight = if (usePortraitCard) 111.dp else 103.dp
    val cardShape = if (usePortraitCard) RoundedCornerShape(16.dp) else AppCardShape
    // Only network tiles use a light surface. Poster/backdrop cards keep a transparent
    // container so the card surface can never bleed a light rim past the artwork at the
    // antialiased rounded corners; the clipped inner Box supplies the placeholder fill.
    val cardContainerColor = if (isNetworkCard) networkStyle.background else Color.Transparent
    val cardPlaceholderColor = if (isNetworkCard) networkStyle.background else Color(0xFF101216)

    Card(
        onClick = onPressed,
        modifier = modifier
            .size(
                width = cardWidth,
                height = cardHeight,
            )
            .tvCardLongPress(onMenuPressed)
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) {
                    onFocused()
                }
            },
        shape = CardDefaults.shape(cardShape),
        colors = CardDefaults.colors(
            containerColor = cardContainerColor,
            contentColor = Color.White,
            focusedContainerColor = cardContainerColor,
            focusedContentColor = Color.White,
            pressedContainerColor = cardContainerColor,
            pressedContentColor = Color.White,
        ),
        border = CardDefaults.border(
            // Explicitly borderless unless focused, so no state can paint a stray rim.
            border = Border.None,
            focusedBorder = Border(
                androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                shape = cardShape,
            ),
            pressedBorder = Border.None,
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = 1.03f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Clip first, then fill: everything drawn inside is bounded by the card
                // shape, so no fill or artwork edge can spill into the corners.
                .clip(cardShape)
                .then(
                    if (isNetworkCard && isFocused) {
                        Modifier.background(networkHeroBrush(item.title))
                    } else {
                        Modifier.background(cardPlaceholderColor)
                    },
                ),
        ) {
            if (isNetworkCard) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.titleLogo ?: item.poster)
                        .allowRgb565(false)
                        .crossfade(false)
                        .build(),
                    contentDescription = item.title,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(88.dp)
                        .height(36.dp),
                    colorFilter = networkStyle.logoTint?.let { ColorFilter.tint(it) },
                    contentScale = ContentScale.Fit,
                )
            } else {
                AsyncImage(
                    model = if (usePortraitCard) item.poster ?: item.backdrop else item.backdrop ?: item.poster,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color(0x33000000),
                                    Color(0xDD000000),
                                ),
                            ),
                        ),
                )
                if (!usePortraitCard) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = if (isFocused) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.year?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.74f),
                            )
                        }
                        if ((item.progress ?: 0.0) > 0.0) {
                            ProgressMeter(
                                progress = item.progress,
                                modifier = Modifier
                                    .width(132.dp)
                                    .height(4.dp),
                            )
                            Text(
                                text = formatPlaybackClock(item.positionSec),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun networkHeroBrush(name: String): Brush {
    val colors = when {
        name.contains("netflix", ignoreCase = true) -> listOf(Color(0xFF2C0004), Color(0xFF8E0E16), Color(0xFF140202))
        name.contains("prime", ignoreCase = true) -> listOf(Color(0xFF03151F), Color(0xFF0F4C81), Color(0xFF122A39))
        name.contains("apple", ignoreCase = true) -> listOf(Color(0xFFF3F6FA), Color(0xFFE3E8EF), Color(0xFFF7F9FC))
        name.contains("hbo", ignoreCase = true) -> listOf(Color(0xFF17102B), Color(0xFF4A3DC7), Color(0xFF160B2B))
        else -> listOf(Color(0xFF111318), Color(0xFF1D2430), Color(0xFF0C0E12))
    }
    return Brush.horizontalGradient(colors)
}

private data class NetworkSurfaceStyle(
    val background: Color,
    val logoTint: Color? = null,
)

private fun networkSurfaceStyle(name: String): NetworkSurfaceStyle = NetworkSurfaceStyle(
    background = Color(0xFFFFFFFF),
    logoTint = null,
)


