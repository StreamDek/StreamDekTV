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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.streamdek.tv.nativeapp.data.HomeContent
import com.streamdek.tv.nativeapp.data.HomeRail
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.ProgressMeter
import com.streamdek.tv.nativeapp.ui.formatPlaybackClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private val HomeRailsTop = 360.dp

private sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Ready(val content: HomeContent) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

@Composable
fun HomeScreen(
    repository: StreamDekRepository,
    onOpenDetail: (String, String) -> Unit,
    onOpenNetwork: (String, String) -> Unit,
    onOpenAccount: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val session by repository.session.collectAsState()
    val bootstrap by repository.bootstrap.collectAsState()
    var uiState by remember { mutableStateOf<HomeUiState>(HomeUiState.Loading) }
    val verticalListState = rememberLazyListState()
    val horizontalListStates = remember { mutableMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }
    val rowFocusIndices = remember { mutableStateMapOf<String, Int>() }
    var activeRowIndex by remember { mutableIntStateOf(0) }
    var focusedItem by remember { mutableStateOf<MediaItem?>(null) }
    var heroDetail by remember { mutableStateOf<MediaDetail?>(null) }

    LaunchedEffect(session?.user?.uid, repository.activeStreamProfile(bootstrap)?.id) {
        uiState = HomeUiState.Loading
        try {
            uiState = HomeUiState.Ready(repository.fetchHomeContent())
            TvDebugLogger.i("HomeUi", "home loaded")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            TvDebugLogger.e("HomeUi", "home failed to load", error)
            uiState = HomeUiState.Error(error.message ?: "Could not load home")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val content = (uiState as? HomeUiState.Ready)?.content
        val hero = focusedItem ?: content?.featured ?: content?.rails?.firstOrNull()?.items?.firstOrNull()
        val heroBackdrop = hero?.backdrop ?: hero?.poster

        LaunchedEffect(hero?.id, hero?.type) {
            heroDetail = if (hero != null && (hero.type == "movie" || hero.type == "tv")) {
                repository.fetchDetail(hero.id, hero.type)
            } else {
                null
            }
        }

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

        when (val state = uiState) {
            HomeUiState.Loading -> HomeLoading()
            is HomeUiState.Error -> HomeError(state.message)
            is HomeUiState.Ready -> {
                LaunchedEffect(state.content) {
                    val preloadUrls = buildList {
                        state.content.featured?.backdrop?.let(::add)
                        state.content.featured?.titleLogo?.let(::add)
                        state.content.rails.forEach { rail ->
                            rail.items.take(8).forEach { item ->
                                item.backdrop?.let(::add)
                                item.poster?.let(::add)
                                item.titleLogo?.let(::add)
                            }
                        }
                    }.distinct()
                    preloadUrls.forEach { url ->
                        context.imageLoader.enqueue(
                            ImageRequest.Builder(context)
                                .data(url)
                                .memoryCacheKey(url)
                                .diskCacheKey(url)
                                .build(),
                        )
                    }
                }

                val rows = remember(state.content.rails) {
                    val list = state.content.rails.toMutableList()
                    val netIdx = list.indexOfFirst { rail -> rail.items.any { it.type == "network" } }
                    if (netIdx > 0) {
                        val target = minOf(3, list.size - 1)
                        if (netIdx != target) {
                            val netRail = list.removeAt(netIdx)
                            list.add(target, netRail)
                        }
                    }
                    list.toList()
                }
                val homeFirstCardRequester = remember { FocusRequester() }

                LaunchedEffect(state.content) {
                    delay(150)
                    try { homeFirstCardRequester.requestFocus() } catch (_: Exception) { }
                }

                HeroBlock(
                    item = hero,
                    detail = heroDetail,
                    compact = focusedItem != null,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 48.dp, top = 56.dp, end = 48.dp)
                        .fillMaxWidth(0.43f),
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = HomeRailsTop)
                        .clipToBounds(),
                ) {
                    LazyColumn(
                        state = verticalListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
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
                                },
                                onItemPressed = { item ->
                                    if (item.type == "network") {
                                        onOpenNetwork(item.id, item.title)
                                    } else {
                                        onOpenDetail(item.type, item.id)
                                    }
                                },
                                firstCardRequester = if (rowIndex == 0) homeFirstCardRequester else null,
                                requestVerticalPlacement = {
                                    if (activeRowIndex != rowIndex) {
                                        activeRowIndex = rowIndex
                                    }
                                },
                            )
                        }
                    }
                }

                LaunchedEffect(activeRowIndex) {
                    if (rows.isNotEmpty()) {
                        delay(40)
                        verticalListState.animateScrollToItem(activeRowIndex.coerceIn(0, rows.lastIndex))
                    }
                }
            }
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
private fun HomeError(message: String) {
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
    }
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
                else -> "Movie"
            },
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFFF0BA66),
        )

        val titleLogo = detail?.titleLogo ?: item.titleLogo
        if (!titleLogo.isNullOrBlank()) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(titleLogo)
                    .crossfade(300)
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
            Text(
                text = description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
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
    firstCardRequester: FocusRequester? = null,
    requestVerticalPlacement: () -> Unit,
) {
    val requesters = remember(row.id) { mutableMapOf<String, FocusRequester>() }
    val noScrollResponder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = localRect
            override suspend fun bringChildIntoView(localRect: () -> Rect?) { }
        }
    }

    LaunchedEffect(anchoredIndex, row.items.size) {
        val targetIndex = anchoredIndex.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
        if (row.items.isNotEmpty() && rowState.firstVisibleItemIndex != targetIndex) {
            rowState.scrollToItem(targetIndex)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = row.title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 52.dp),
        )

        // BringIntoViewResponder no-op stops focus-driven horizontal scroll from
        // propagating up and causing the LazyColumn to jump to a different row.
        Box(modifier = Modifier.bringIntoViewResponder(noScrollResponder)) {
        LazyRow(
            state = rowState,
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = 52.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(row.items, key = { _, item -> "${row.id}:${item.id}" }) { index, item ->
                val key = "${row.id}:${item.id}"
                val requester = requesters.getOrPut(key) { FocusRequester() }
                val effectiveRequester = if (index == 0 && firstCardRequester != null) firstCardRequester else requester

                MediaPosterCard(
                    item = item,
                    modifier = Modifier.focusRequester(effectiveRequester),
                    onFocused = {
                        requestVerticalPlacement()
                        onItemFocused(index, item)
                    },
                    onPressed = { onItemPressed(item) },
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
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onPressed: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val isNetworkCard = item.type == "network"
    val networkStyle = remember(item.title) { networkSurfaceStyle(item.title) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Card(
        onClick = onPressed,
        modifier = modifier
            .size(
                width = if (isNetworkCard) 226.dp else 200.dp,
                height = if (isNetworkCard) 118.dp else 118.dp,
            )
            .onFocusChanged { state ->
                isFocused = state.isFocused
                if (state.isFocused) {
                    onFocused()
                }
            },
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(
            containerColor = if (isNetworkCard) Color.White else Color(0xFF181A1F),
            focusedContainerColor = if (isNetworkCard) Color.White else Color(0xFF181A1F),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFF0BA66)),
                shape = AppCardShape,
            ),
        ),
        scale = CardDefaults.scale(focusedScale = 1.03f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(AppCardShape)
                .background(if (isNetworkCard && isFocused) networkHeroBrush(item.title) else Brush.linearGradient(listOf(networkStyle.background, networkStyle.background))),
        ) {
            if (isNetworkCard) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.titleLogo ?: item.poster)
                        .allowRgb565(false)
                        .crossfade(200)
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
                    model = item.backdrop ?: item.poster,
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
