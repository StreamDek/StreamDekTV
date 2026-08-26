package com.streamdek.tv.nativeapp.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.imageLoader
import coil.request.ImageRequest
import com.streamdek.tv.nativeapp.data.LibraryResponse
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import com.streamdek.tv.nativeapp.ui.BrowseItemActionMenu
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.PremiumMediaCard
import com.streamdek.tv.nativeapp.ui.TvEmptyState
import com.streamdek.tv.nativeapp.ui.TvMediaCardVariant
import com.streamdek.tv.nativeapp.ui.TvSkeletonGrid
import com.streamdek.tv.nativeapp.ui.TvSpacing
import com.streamdek.tv.nativeapp.ui.search.SearchChip
import com.streamdek.tv.nativeapp.ui.tvCardLongPress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class BrowseActionState(
    val item: MediaItem,
    val restoreFocusRequester: FocusRequester,
)

private enum class LibrarySection(val label: String) {
    Continue("Continue Watching"), Watchlist("Watchlist")
}

private val LibraryInset = TvSpacing.ScreenHorizontal
private val LibraryCardWidth = 132.dp
private val LibraryCardHeight = 198.dp

/**
 * Down from the filter row into the grid, without betting that the grid is there to be entered.
 *
 * This was `focusProperties { down = ... }`, which resolves at focus-search time and throws when
 * its target is not attached to anything. Two moments make that certain rather than unlikely: the
 * window between switching section and the new grid placing its first item, which is exactly when
 * a viewer who has just pressed a tab presses Down; and a section that is simply empty, where the
 * empty state renders instead of the grid and the target never exists at all.
 *
 * Failing here falls through to Compose's ordinary focus search rather than crashing, so the worst
 * case is a press that does nothing.
 */
private fun Modifier.dpadDownInto(requester: FocusRequester): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionDown) return@onPreviewKeyEvent false
    runCatching { requester.requestFocus() }.isSuccess
}

private fun libraryItemKey(item: MediaItem): String {
    val episode = item.episode?.let { ":s${it.seasonNumber}:e${it.episodeNumber}" }.orEmpty()
    return "${item.type}:${item.id}$episode"
}

/**
 * Library.
 *
 * Rebuilt as a grid rather than a hero over horizontal rails. A library is a finite, already-known
 * collection — the job is scanning it, not being enticed by it — and rails show perhaps five items
 * per screen where a grid shows twenty. The 330dp featured banner and the `padding(top = 270.dp)`
 * that positioned the rails under it are both gone with it.
 *
 * The controls also stay on screen. The column they replace drew itself only while it held focus,
 * so the moment the viewer moved into the content the active type filter vanished and there was no
 * way to tell what was being filtered out.
 */
@Composable
fun LibraryScreen(
    repository: StreamDekRepository,
    entryFocusRequester: FocusRequester? = null,
    onOpenDetail: (String, String) -> Unit,
) {
    val session by repository.session.collectAsState()
    val bootstrap by repository.bootstrap.collectAsState()
    val gridColumns = LocalTvExperienceSettings.current.gridColumns
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var library by remember { mutableStateOf<LibraryResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var actionState by remember { mutableStateOf<BrowseActionState?>(null) }

    val viewStore = remember {
        context.getSharedPreferences("streamdek_tv_library", android.content.Context.MODE_PRIVATE)
    }
    var section by remember {
        mutableStateOf(
            runCatching { LibrarySection.valueOf(viewStore.getString("section", null) ?: "") }
                .getOrDefault(LibrarySection.Continue),
        )
    }
    var typeFilter by remember { mutableStateOf(viewStore.getString("type", "all") ?: "all") }

    val localFirstChip = remember { FocusRequester() }
    val firstChipRequester = entryFocusRequester ?: localFirstChip
    val firstCardRequester = remember { FocusRequester() }
    val cardRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val gridState = rememberLazyGridState()

    LaunchedEffect(session?.user?.uid, repository.activeStreamProfile(bootstrap)?.id, reloadToken) {
        loading = true
        error = null
        try {
            library = repository.fetchLibrary(forceRefresh = true)
            TvDebugLogger.i(
                "LibraryUi",
                "loaded continue=${library?.continueWatching?.size} watchlist=${library?.watchlist?.size}",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            library = null
            error = "Your library could not be loaded. Check the connection and try again."
            TvDebugLogger.e("LibraryUi", "library failed to load", failure)
        }
        loading = false
    }

    val continueItems = remember(library, typeFilter) {
        library?.continueWatching.orEmpty()
            .map { entry ->
                MediaItem(
                    id = entry.id, tmdbId = entry.tmdbId, title = entry.title, type = entry.type,
                    poster = entry.poster, backdrop = entry.backdrop, description = entry.description,
                    rating = entry.rating, year = entry.year, progress = entry.progress,
                    positionSec = entry.positionSec ?: entry.resumeAt, durationSec = entry.durationSec,
                    episode = entry.episode,
                )
            }
            .filter { typeFilter == "all" || it.type == typeFilter }
            .distinctBy(::libraryItemKey)
    }
    val watchlistItems = remember(library, typeFilter) {
        library?.watchlist.orEmpty()
            .filter { typeFilter == "all" || it.type == typeFilter }
            // A tracking service can hand back the same title twice, and two grid items sharing a
            // key is a hard crash in Compose rather than a cosmetic duplicate.
            .distinctBy(::libraryItemKey)
    }
    val items = if (section == LibrarySection.Continue) continueItems else watchlistItems

    // A position held from a longer list has no meaning in a shorter one, and leaving the grid
    // scrolled where the previous section was reads as the new section having lost its first rows.
    LaunchedEffect(section, typeFilter) {
        runCatching { gridState.scrollToItem(0) }
    }

    LaunchedEffect(loading, error) {
        if (loading) return@LaunchedEffect
        delay(160)
        runCatching { firstChipRequester.requestFocus() }
    }

    LaunchedEffect(items.size) {
        items.take(10).mapNotNull { it.poster ?: it.backdrop }.distinct().forEach { url ->
            context.imageLoader.enqueue(
                ImageRequest.Builder(context).data(url).memoryCacheKey(url).diskCacheKey(url)
                    .crossfade(false).allowHardware(true).allowRgb565(true).build(),
            )
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = LibraryInset, top = 34.dp, end = LibraryInset),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = when {
                        loading -> "Loading…"
                        else -> "${items.size} ${if (items.size == 1) "title" else "titles"}"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            // Section and type filters live together in one always-visible row, so what is being
            // shown and what is being filtered out can be read at the same time as the results.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .focusGroup()
                    .padding(horizontal = LibraryInset, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LibrarySection.entries.forEachIndexed { index, option ->
                    val count = if (option == LibrarySection.Continue) continueItems.size else watchlistItems.size
                    SearchChip(
                        label = option.label,
                        selected = section == option,
                        leading = count.toString(),
                        modifier = (if (index == 0) Modifier.focusRequester(firstChipRequester) else Modifier)
                            .dpadDownInto(firstCardRequester),
                        onClick = {
                            section = option
                            viewStore.edit().putString("section", option.name).apply()
                        },
                    )
                }
                Box(Modifier.width(16.dp))
                listOf("all" to "Everything", "movie" to "Films", "tv" to "Series").forEach { (value, label) ->
                    SearchChip(
                        label = label,
                        selected = typeFilter == value,
                        modifier = Modifier.dpadDownInto(firstCardRequester),
                        onClick = {
                            typeFilter = value
                            viewStore.edit().putString("type", value).apply()
                        },
                    )
                }
            }

            when {
                loading && items.isEmpty() -> TvSkeletonGrid(columns = gridColumns, rows = 3)

                error != null -> TvEmptyState(
                    title = "Library unavailable",
                    message = error,
                    actionLabel = "Try Again",
                    onAction = { reloadToken++ },
                )

                items.isEmpty() -> TvEmptyState(
                    title = when {
                        session == null -> "Sign in to see your library"
                        section == LibrarySection.Continue -> "Nothing in progress"
                        else -> "Your watchlist is empty"
                    },
                    message = when {
                        session == null -> "Your continue-watching and watchlist sync to the active profile."
                        section == LibrarySection.Continue -> "Titles you start appear here so you can pick them back up."
                        else -> "Hold OK on any title to add it to your watchlist."
                    },
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    state = gridState,
                    modifier = Modifier.weight(1f).fillMaxWidth().focusGroup(),
                    contentPadding = PaddingValues(
                        start = LibraryInset, end = LibraryInset, top = 2.dp, bottom = 72.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                    verticalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                ) {
                    itemsIndexed(items, key = { _, item -> libraryItemKey(item) }) { index, item ->
                        val requester = cardRequesters.getOrPut(libraryItemKey(item)) { FocusRequester() }
                        val effective = if (index == 0) firstCardRequester else requester
                        val watchlistPoster = section == LibrarySection.Watchlist
                        PremiumMediaCard(
                            item = item,
                            variant = if (watchlistPoster) {
                                TvMediaCardVariant.Poster
                            } else {
                                TvMediaCardVariant.ContinueWatching
                            },
                            // Watchlist entries carry no progress and need no title: the poster is
                            // the identifier, so only the year and rating sit over it.
                            showLabels = !watchlistPoster,
                            metaOnTop = watchlistPoster,
                            metaOnTopAlignment = androidx.compose.ui.Alignment.TopCenter,
                            modifier = Modifier
                                .focusRequester(effective)
                                .width(LibraryCardWidth)
                                .height(LibraryCardHeight)
                                .focusProperties { if (index < gridColumns) up = firstChipRequester }
                                .tvCardLongPress { actionState = BrowseActionState(item, effective) },
                            onClick = { onOpenDetail(item.type, item.detailLookupId()) },
                            onLongPress = { actionState = BrowseActionState(item, effective) },
                        )
                    }
                }
            }
        }

        actionState?.let { state ->
            BrowseItemActionMenu(
                repository = repository,
                item = state.item,
                showRemoveFromContinueWatching = section == LibrarySection.Continue,
                onDismiss = {
                    val restoreRequester = state.restoreFocusRequester
                    actionState = null
                    scope.launch {
                        delay(40)
                        runCatching { restoreRequester.requestFocus() }
                    }
                },
                onOpenDetail = { onOpenDetail(state.item.type, state.item.detailLookupId()) },
                onChanged = {
                    // The repository has already applied the confirmed mutation to its cache.
                    // Do not immediately ask an eventually-consistent provider for the old list.
                    val refreshed = repository.fetchLibrary()
                    library = refreshed
                    if (section == LibrarySection.Watchlist &&
                        refreshed.watchlist.none { libraryItemKey(it) == libraryItemKey(state.item) }
                    ) {
                        // The old requester belongs to the card just removed. Land on a target that
                        // survives the mutation so Down from the filters cannot enter a disposed
                        // lazy-grid item and crash Compose's focus search.
                        kotlinx.coroutines.delay(80)
                        val remaining = refreshed.watchlist
                            .filter { typeFilter == "all" || it.type == typeFilter }
                            .distinctBy(::libraryItemKey)
                        runCatching {
                            if (remaining.isEmpty()) firstChipRequester.requestFocus()
                            else firstCardRequester.requestFocus()
                        }
                    }
                },
            )
        }
    }
}
