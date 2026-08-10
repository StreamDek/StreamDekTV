package com.streamdek.tv.nativeapp.ui.library

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
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
import com.streamdek.tv.nativeapp.ui.PremiumMediaCard
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.TvMediaCardVariant
import com.streamdek.tv.nativeapp.ui.BrowseItemActionMenu
import com.streamdek.tv.nativeapp.ui.tvCardLongPress
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
    /** Bumped by the retry button so the load below runs again. */
    var reloadToken by remember { mutableIntStateOf(0) }
    var actionState by remember { mutableStateOf<BrowseActionState?>(null) }
    var featuredItem by remember { mutableStateOf<MediaItem?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val libraryViewStore = remember { context.getSharedPreferences("streamdek_tv_library", android.content.Context.MODE_PRIVATE) }
    var libraryTypeFilter by remember { mutableStateOf(libraryViewStore.getString("type", "all") ?: "all") }
    val localInitialCardRequester = remember { FocusRequester() }
    val initialCardRequester = entryFocusRequester ?: localInitialCardRequester
    val firstContentRequester = remember { FocusRequester() }
    var controlsHaveFocus by remember { mutableStateOf(true) }
    val controlRailWidth by animateDpAsState(if (controlsHaveFocus) 218.dp else 40.dp, label = "library-control-rail")
    val contentStart by animateDpAsState(if (controlsHaveFocus) 250.dp else 22.dp, label = "library-content-start")

    LaunchedEffect(session?.user?.uid, repository.activeStreamProfile(bootstrap)?.id, reloadToken) {
        suspend fun refresh() {
            error = null
            try {
                val result = repository.fetchLibrary(forceRefresh = true)
                library = result
                featuredItem = featuredItem ?: result.continueWatching.firstOrNull()?.let { item -> MediaItem(id = item.id, tmdbId = item.tmdbId, title = item.title, type = item.type, poster = item.poster, backdrop = item.backdrop, description = item.description, rating = item.rating, year = item.year, progress = item.progress, positionSec = item.positionSec ?: item.resumeAt, durationSec = item.durationSec, episode = item.episode) } ?: result.watchlist.firstOrNull()
                TvDebugLogger.i("LibraryUi", "library loaded continue=${result.continueWatching.size} watchlist=${result.watchlist.size}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                library = null
                error = "Your library could not be loaded. Check your connection and try again."
                TvDebugLogger.e("LibraryUi", "library failed to load", failure)
            }
        }
        error = null
        refresh()
    }

    LaunchedEffect(library, error) {
        kotlinx.coroutines.delay(160)
        runCatching { initialCardRequester.requestFocus() }
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

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier.width(controlRailWidth).fillMaxSize().clipToBounds().background(Color(0xF207090D)).drawWithContent { if (controlsHaveFocus) drawContent() }.zIndex(3f)
                .onFocusChanged { controlsHaveFocus = it.hasFocus }.verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("LIBRARY", color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black))
            Text("YOUR COLLECTION", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(bottom = 4.dp))
            listOf("all" to "Everything", "movie" to "Movies", "tv" to "Series").forEachIndexed { index, option ->
                LibraryFilterChip(
                    label = option.second,
                    selected = libraryTypeFilter == option.first,
                    onClick = { libraryTypeFilter = option.first; libraryViewStore.edit().putString("type", option.first).apply() },
                    modifier = (if (index == 0) Modifier.focusRequester(initialCardRequester) else Modifier).focusProperties { right = firstContentRequester },
                )
            }
            Text("Continue watching and saved titles stay synced to the active profile.", color = Color.White.copy(alpha = 0.48f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp), textAlign = TextAlign.Center)
        }

        featuredItem?.let { item ->
            AsyncImage(
                model = item.backdrop ?: item.poster,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(330.dp).padding(start = controlRailWidth, end = 92.dp).align(Alignment.TopEnd),
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
            )
            Box(
                Modifier.fillMaxWidth().height(330.dp).padding(start = controlRailWidth, end = 92.dp).background(
                    Brush.horizontalGradient(colorStops = arrayOf(0f to MaterialTheme.colorScheme.background, 0.40f to MaterialTheme.colorScheme.background, 0.72f to MaterialTheme.colorScheme.background.copy(alpha = 0.38f), 1f to Color.Transparent)),
                ),
            )
            Box(
                Modifier.fillMaxWidth().height(330.dp).padding(start = controlRailWidth, end = 92.dp).background(
                    Brush.verticalGradient(colorStops = arrayOf(0f to MaterialTheme.colorScheme.background.copy(alpha = 0.12f), 0.58f to Color.Transparent, 1f to MaterialTheme.colorScheme.background)),
                ),
            )
        }

        Column(
            Modifier.fillMaxWidth().padding(start = contentStart, end = 220.dp, top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("FEATURED", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black))
            Text(featuredItem?.title ?: "Your collection", color = Color.White, style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black), maxLines = 1, overflow = TextOverflow.Ellipsis)
            featuredItem?.let { item ->
                Text(
                    listOfNotNull(item.year, item.rating?.let { "★ %.1f".format(it) }, if (item.type == "tv") "Series" else "Movie").joinToString("  /  "),
                    color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
                item.description?.takeIf { it.isNotBlank() }?.let { description -> Text(description, color = Color.White.copy(alpha = 0.66f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            } ?: Text("Pick up where you stopped or revisit something saved.", color = Color.White.copy(alpha = 0.62f))
            error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { reloadToken++ }, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) { Text("Try Again") }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = 270.dp),
            contentPadding = PaddingValues(bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            val continueWatching = library?.continueWatching.orEmpty().map {
                MediaItem(id = it.id, tmdbId = it.tmdbId, title = it.title, type = it.type, poster = it.poster, backdrop = it.backdrop, description = it.description, rating = it.rating, year = it.year, progress = it.progress, positionSec = it.positionSec ?: it.resumeAt, durationSec = it.durationSec, episode = it.episode)
            }.filter { libraryTypeFilter == "all" || it.type == libraryTypeFilter }
            if (continueWatching.isNotEmpty()) item { LibraryRow("Continue Watching", continueWatching, initialCardRequester, contentStart, firstContentRequester, onOpenDetail, { item, requester -> actionState = BrowseActionState(item, requester) }, { featuredItem = it }) }
            val watchlist = library?.watchlist.orEmpty().filter { libraryTypeFilter == "all" || it.type == libraryTypeFilter }
            if (watchlist.isNotEmpty()) item { LibraryRow("My Watchlist", watchlist, initialCardRequester, contentStart, if (continueWatching.isEmpty()) firstContentRequester else null, onOpenDetail, { item, requester -> actionState = BrowseActionState(item, requester) }, { featuredItem = it }) }
            if (continueWatching.isEmpty() && watchlist.isEmpty() && error == null) {
                item { Text(if (session == null) "Sign in to load your synced library." else "Your library is empty right now.", color = Color.White.copy(alpha = 0.68f), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = contentStart, end = 48.dp, top = 18.dp)) }
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibrarySidebarItem(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(40.dp),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        colors = CardDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (selected) FontWeight.Black else FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryFilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(42.dp),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        colors = CardDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = if (selected) 0.22f else 0.14f),
        ),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (selected) FontWeight.Black else FontWeight.Medium), textAlign = TextAlign.Start, maxLines = 1)
        }
    }
}
@Composable
private fun LibraryRow(
    title: String,
    items: List<MediaItem>,
    leftRequester: FocusRequester,
    contentStart: Dp,
    initialFocusRequester: FocusRequester? = null,
    onOpenDetail: (String, String) -> Unit,
    onOpenActions: (MediaItem, FocusRequester) -> Unit,
    onFocused: (MediaItem) -> Unit,
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
            modifier = Modifier.padding(start = contentStart, end = 92.dp),
        )
        androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val contentEnd = 92.dp
            val itemSpacing = 16.dp
            val columns = 3
            val densityScale = if (LocalTvExperienceSettings.current.denseCards) 0.88f else 1f
            val cardWidth = ((maxWidth - contentStart - contentEnd - itemSpacing * (columns - 1)) / columns) * densityScale
            val cardHeight = 250.dp * densityScale
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusGroup(),
                contentPadding = PaddingValues(start = contentStart, end = contentEnd),
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
            ) {
                itemsIndexed(items, key = { _, item -> mediaItemStableKey(item) }) { index, item ->
                    val key = mediaItemStableKey(item)
                    val requester = requesters.getOrPut(key) { FocusRequester() }
                    val effectiveRequester = if (initialFocusRequester != null && item == items.firstOrNull()) initialFocusRequester else requester
                    LibraryCard(
                        item = item,
                        cardWidth = cardWidth,
                        cardHeight = cardHeight,
                        modifier = Modifier.focusRequester(effectiveRequester).focusProperties { if (index == 0) left = leftRequester },
                        onPressed = { onOpenDetail(item.type, item.id) },
                        onMenuPressed = { onOpenActions(item, effectiveRequester) },
                        onFocused = { onFocused(item) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryCard(
    item: MediaItem,
    cardWidth: androidx.compose.ui.unit.Dp,
    cardHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onPressed: () -> Unit,
    onMenuPressed: () -> Unit,
    onFocused: () -> Unit,
) {
    PremiumMediaCard(
        item = item,
        variant = if ((item.progress ?: 0.0) > 0.0) TvMediaCardVariant.ContinueWatching else TvMediaCardVariant.Poster,
        modifier = modifier.width(cardWidth).height(cardHeight),
        onClick = onPressed,
        onLongPress = onMenuPressed,
        onFocused = onFocused,
    )
}