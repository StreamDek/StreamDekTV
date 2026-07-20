package com.streamdek.tv.nativeapp.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.streamdek.tv.nativeapp.data.LibraryResponse
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.BrowseItemActionMenu
import com.streamdek.tv.nativeapp.ui.ProgressMeter
import com.streamdek.tv.nativeapp.ui.formatPlaybackClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class BrowseActionState(
    val item: MediaItem,
    val restoreFocusRequester: FocusRequester,
)

private fun mediaItemStableKey(item: MediaItem): String {
    val episodeSuffix = item.episode?.let { ":s${it.seasonNumber}:e${it.episodeNumber}" }.orEmpty()
    return "${item.type}:${item.id}$episodeSuffix"
}

@Composable
fun LibraryScreen(
    repository: StreamDekRepository,
    entryFocusRequester: FocusRequester? = null,
    onOpenDetail: (String, String) -> Unit,
) {
    val session by repository.session.collectAsState()
    val bootstrap by repository.bootstrap.collectAsState()
    val compactMode = bootstrap?.preferences?.app?.compactMode == true
    val scope = rememberCoroutineScope()
    var library by remember { mutableStateOf<LibraryResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var actionState by remember { mutableStateOf<BrowseActionState?>(null) }
    val localInitialCardRequester = remember { FocusRequester() }
    val initialCardRequester = entryFocusRequester ?: localInitialCardRequester
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(session?.user?.uid, repository.activeStreamProfile(bootstrap)?.id) {
        suspend fun refresh() {
            error = null
            try {
                val result = repository.fetchLibrary(forceRefresh = true)
                library = result
                TvDebugLogger.i("LibraryUi", "library loaded continue=${result.continueWatching.size} watchlist=${result.watchlist.size}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                library = null
                error = failure.message
                TvDebugLogger.e("LibraryUi", "library failed to load", failure)
            }
        }
        error = null
        refresh()
    }

    LaunchedEffect(library) {
        val hasItems = library?.continueWatching?.isNotEmpty() == true || library?.watchlist?.isNotEmpty() == true
        if (hasItems) {
            kotlinx.coroutines.delay(180)
            initialCardRequester.requestFocus()
        }
    }

    LaunchedEffect(library) {
        buildList {
            library?.continueWatching?.forEach {
                it.backdrop?.let(::add)
                it.poster?.let(::add)
            }
            library?.watchlist?.forEach {
                it.backdrop?.let(::add)
                it.poster?.let(::add)
            }
        }.distinct().take(12).forEach { url ->
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (compactMode) 36.dp else 48.dp,
                    end = if (compactMode) 36.dp else 48.dp,
                    top = if (compactMode) 36.dp else 48.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Library",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Continue watching and your watchlist, all in one place.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            )
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = if (compactMode) 148.dp else 168.dp),
            contentPadding = PaddingValues(bottom = 180.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            val continueWatching = library?.continueWatching.orEmpty().map {
                MediaItem(
                    id = it.id,
                    tmdbId = it.tmdbId,
                    title = it.title,
                    type = it.type,
                    poster = it.poster,
                    backdrop = it.backdrop,
                    description = it.description,
                    rating = it.rating,
                    year = it.year,
                    progress = it.progress,
                    positionSec = it.positionSec ?: it.resumeAt,
                    durationSec = it.durationSec,
                    episode = it.episode,
                )
            }
            if (continueWatching.isNotEmpty()) {
                item {
                    LibraryRow(
                        title = "Continue Watching",
                        items = continueWatching,
                        initialFocusRequester = initialCardRequester,
                        onOpenDetail = onOpenDetail,
                        onOpenActions = { item, requester -> actionState = BrowseActionState(item, requester) },
                    )
                }
            }

            val watchlist = library?.watchlist.orEmpty()
            if (watchlist.isNotEmpty()) {
                item {
                    LibraryRow(
                        title = "My Watchlist",
                        items = watchlist,
                        initialFocusRequester = if (continueWatching.isEmpty()) initialCardRequester else null,
                        onOpenDetail = onOpenDetail,
                        onOpenActions = { item, requester -> actionState = BrowseActionState(item, requester) },
                    )
                }
            }

            if (continueWatching.isEmpty() && watchlist.isEmpty()) {
                item {
                    Text(
                        text = if (session == null) "Sign in to load your synced library." else "Your library is empty right now.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                        modifier = Modifier.padding(start = 48.dp, end = 48.dp),
                    )
                }
            }
        }

        actionState?.let { state ->
            BrowseItemActionMenu(
                repository = repository,
                item = state.item,
                onDismiss = {
                    val restoreRequester = state.restoreFocusRequester
                    actionState = null
                    scope.launch {
                        kotlinx.coroutines.delay(40)
                        runCatching { restoreRequester.requestFocus() }
                    }
                },
                onOpenDetail = { onOpenDetail(state.item.type, state.item.id) },
                onChanged = {
                    val result = repository.fetchLibrary(forceRefresh = true)
                    library = result
                },
            )
        }
    }
}

@Composable
private fun LibraryRow(
    title: String,
    items: List<MediaItem>,
    initialFocusRequester: FocusRequester? = null,
    onOpenDetail: (String, String) -> Unit,
    onOpenActions: (MediaItem, FocusRequester) -> Unit,
) {
    val requesters = remember(title) { mutableMapOf<String, FocusRequester>() }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 48.dp, end = 48.dp),
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(items, key = { _, item -> mediaItemStableKey(item) }) { _, item ->
                val key = mediaItemStableKey(item)
                val requester = requesters.getOrPut(key) { FocusRequester() }
                val effectiveRequester = if (initialFocusRequester != null && item == items.firstOrNull()) initialFocusRequester else requester
                LibraryCard(
                    item = item,
                    modifier = Modifier.focusRequester(effectiveRequester),
                    onPressed = { onOpenDetail(item.type, item.id) },
                    onMenuPressed = { onOpenActions(item, effectiveRequester) },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryCard(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onPressed: () -> Unit,
    onMenuPressed: () -> Unit,
) {
    Card(
        onClick = onPressed,
        modifier = modifier
            .size(width = 260.dp, height = 150.dp)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.Menu) {
                    onMenuPressed()
                    true
                } else {
                    false
                }
            },
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(
            containerColor = Color(0xFF181A1F),
            focusedContainerColor = Color(0xFF181A1F),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                shape = AppCardShape,
            ),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(AppCardShape),
        ) {
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
                            colors = listOf(Color.Transparent, Color(0xD9000000)),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                item.episode?.title?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.year?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                    )
                }
                if ((item.progress ?: 0.0) > 0.0) {
                    ProgressMeter(
                        progress = item.progress,
                        modifier = Modifier
                            .width(152.dp)
                            .height(4.dp),
                    )
                    Text(
                        text = formatPlaybackClock(item.positionSec),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                    )
                }
            }
        }
    }
}
