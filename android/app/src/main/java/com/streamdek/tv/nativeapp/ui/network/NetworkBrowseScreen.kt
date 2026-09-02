package com.streamdek.tv.nativeapp.ui.network

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.imageLoader
import coil.request.ImageRequest
import com.streamdek.tv.nativeapp.data.GenreItem
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.ui.BrowseItemActionMenu
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.PremiumMediaCard
import com.streamdek.tv.nativeapp.ui.TvEmptyState
import com.streamdek.tv.nativeapp.ui.TvContentPhase
import com.streamdek.tv.nativeapp.ui.TvContentSwap
import com.streamdek.tv.nativeapp.ui.TvMediaCardVariant
import com.streamdek.tv.nativeapp.ui.TvSkeletonGrid
import com.streamdek.tv.nativeapp.ui.TvSpacing
import com.streamdek.tv.nativeapp.ui.search.SearchChip
import com.streamdek.tv.nativeapp.ui.search.SearchFilterOption
import com.streamdek.tv.nativeapp.ui.search.SearchFilterTray
import com.streamdek.tv.nativeapp.ui.tvCardLongPress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Year
import com.streamdek.tv.nativeapp.ui.LocalSideNavOwnsFocus

private data class BrowseActionState(
    val item: MediaItem,
    val restoreFocusRequester: FocusRequester,
)

private enum class OpenTray { None, Type, Year, Genre, Rating }

private val NetworkInset = TvSpacing.ScreenHorizontal

/**
 * One streaming service's catalogue.
 *
 * Rebuilt around the same filter idiom as Search, and as a real grid. Points that needed fixing:
 *
 *  - **Results were chunked into rows of three by hand** (`visibleResults.chunked(3)`) inside a
 *    lazy column, which both fixed the density at three enormous cards per row and defeated
 *    per-card recycling — the lazy layout could only recycle whole rows.
 *  - **Only the first page ever loaded.** The endpoint is paged and the screen never asked for
 *    page two, so a large catalogue silently stopped a screen and a half in.
 *  - **The rating filter lied.** It filtered the loaded page client-side while presenting itself
 *    as a catalogue filter, so "7+" meant "7+ among the first page" — now labelled as such.
 *  - Filters were dropdown menus; they expand in place, matching Search.
 */
@Composable
fun NetworkBrowseScreen(
    repository: StreamDekRepository,
    networkId: String,
    networkName: String,
    /**
     * Where the shell sends focus when this screen is the one on display.
     *
     * The navigation rail is on this page now, and the rail hands focus back by name. Without a
     * target of its own the page could be entered but not returned to, so pressing right out of the
     * menu fell through to whatever spatial search happened to find.
     */
    entryFocusRequester: FocusRequester? = null,
    onBack: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val gridColumns = LocalTvExperienceSettings.current.gridColumns

    var mediaType by remember { mutableStateOf("all") }
    var year by remember { mutableStateOf<String?>(null) }
    var genreId by remember { mutableStateOf<Int?>(null) }
    var minRating by remember { mutableStateOf<Int?>(null) }
    var genres by remember { mutableStateOf<List<GenreItem>>(emptyList()) }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var totalPages by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var openTray by remember { mutableStateOf(OpenTray.None) }
    var actionState by remember { mutableStateOf<BrowseActionState?>(null) }

    val localChipRequester = remember { FocusRequester() }
    // The filter chips are the top of this page, so they are what "back to the page" means here.
    val firstChipRequester = entryFocusRequester ?: localChipRequester
    val firstCardRequester = remember { FocusRequester() }
    val trayRequester = remember { FocusRequester() }
    val cardRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val gridState = rememberLazyGridState()
    val yearOptions = remember { listOf<String?>(null) + (0..14).map { (Year.now().value - it).toString() } }

    LaunchedEffect(networkId, mediaType, year, genreId, reloadToken) {
        loading = true
        failed = false
        genres = runCatching { repository.fetchGenres(if (mediaType == "tv") "tv" else "movie") }
            .getOrDefault(emptyList())
        val payload = runCatching {
            repository.fetchNetworkCatalog(
                networkId = networkId, type = mediaType, year = year,
                genreId = genreId, sort = "year", page = 1, forceRefresh = true,
            )
        }.getOrNull()
        if (payload == null) {
            failed = true
            results = emptyList()
        } else {
            results = payload.results.distinctBy { "${it.type}:${it.id}" }
            page = payload.page
            totalPages = payload.total_pages
        }
        loading = false
    }

    // Paged catalogue: pull the next page a couple of rows before the viewer reaches the end.
    LaunchedEffect(gridState, results.size, page, totalPages) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                if (loading || loadingMore || page >= totalPages || results.isEmpty()) return@collect
                if (lastVisible < results.size - gridColumns * 2) return@collect
                loadingMore = true
                val payload = runCatching {
                    repository.fetchNetworkCatalog(
                        networkId = networkId, type = mediaType, year = year,
                        genreId = genreId, sort = "year", page = page + 1,
                    )
                }.getOrNull()
                if (payload != null) {
                    results = (results + payload.results).distinctBy { "${it.type}:${it.id}" }
                    page = payload.page
                    totalPages = payload.total_pages
                }
                loadingMore = false
            }
    }

    val visibleResults = remember(results, minRating) {
        results.filter { minRating == null || (it.rating ?: 0.0) >= minRating!!.toDouble() }
    }

    val sideNavOwnsFocus = LocalSideNavOwnsFocus.current

    LaunchedEffect(loading, failed) {
        if (loading) return@LaunchedEffect
        delay(160)
        // Not while the side navigation owns the D-pad — see LocalSideNavOwnsFocus.
        if (!sideNavOwnsFocus) runCatching { firstChipRequester.requestFocus() }
    }

    LaunchedEffect(openTray) {
        if (openTray == OpenTray.None) return@LaunchedEffect
        delay(60)
        runCatching { trayRequester.requestFocus() }
    }

    LaunchedEffect(visibleResults.size) {
        visibleResults.take(10).mapNotNull { it.poster ?: it.backdrop }.distinct().forEach { url ->
            context.imageLoader.enqueue(
                ImageRequest.Builder(context).data(url).memoryCacheKey(url).diskCacheKey(url)
                    .crossfade(false).allowHardware(true).allowRgb565(true).build(),
            )
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // A single tinted wash keyed off the service's brand, rather than a brand gradient plus a
        // second full-screen scrim over it.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to networkTint(networkName).copy(alpha = 0.34f),
                        0.5f to MaterialTheme.colorScheme.background.copy(alpha = 0.92f),
                        1f to MaterialTheme.colorScheme.background,
                    ),
                ),
            ),
        )

        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = NetworkInset, end = NetworkInset, top = 34.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = networkName,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = when {
                        loading -> "Loading…"
                        else -> "${visibleResults.size} titles" + if (page < totalPages) " so far" else ""
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .focusGroup()
                    .padding(horizontal = NetworkInset, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchChip(
                    label = when (mediaType) { "movie" -> "Films"; "tv" -> "Series"; else -> "Everything" },
                    selected = openTray == OpenTray.Type,
                    leading = "Show",
                    modifier = Modifier.focusRequester(firstChipRequester)
                        .focusProperties { down = firstCardRequester },
                    onClick = { openTray = if (openTray == OpenTray.Type) OpenTray.None else OpenTray.Type },
                )
                SearchChip(
                    label = year ?: "Any Year",
                    selected = openTray == OpenTray.Year,
                    leading = "Year",
                    modifier = Modifier.focusProperties { down = firstCardRequester },
                    onClick = { openTray = if (openTray == OpenTray.Year) OpenTray.None else OpenTray.Year },
                )
                SearchChip(
                    label = genres.firstOrNull { it.id == genreId }?.name ?: "All Genres",
                    selected = openTray == OpenTray.Genre,
                    leading = "Genre",
                    modifier = Modifier.focusProperties { down = firstCardRequester },
                    onClick = {
                        if (genres.isNotEmpty()) {
                            openTray = if (openTray == OpenTray.Genre) OpenTray.None else OpenTray.Genre
                        }
                    },
                )
                SearchChip(
                    // Named for what it actually does: this one narrows what has been loaded, it
                    // does not ask the catalogue for highly rated titles.
                    label = minRating?.let { "$it+" } ?: "Any",
                    selected = openTray == OpenTray.Rating,
                    leading = "Rated (loaded)",
                    modifier = Modifier.focusProperties { down = firstCardRequester },
                    onClick = { openTray = if (openTray == OpenTray.Rating) OpenTray.None else OpenTray.Rating },
                )
            }

            if (openTray != OpenTray.None) {
                SearchFilterTray(
                    firstOptionRequester = trayRequester,
                    onDismiss = { openTray = OpenTray.None },
                    options = when (openTray) {
                        OpenTray.Type -> listOf("all" to "Everything", "movie" to "Films", "tv" to "Series")
                            .map { (value, label) ->
                                SearchFilterOption(label, mediaType == value) { mediaType = value; genreId = null }
                            }
                        OpenTray.Year -> yearOptions.map { option ->
                            SearchFilterOption(option ?: "Any Year", year == option) { year = option }
                        }
                        OpenTray.Genre -> buildList {
                            add(SearchFilterOption("All Genres", genreId == null) { genreId = null })
                            genres.forEach { genre ->
                                add(SearchFilterOption(genre.name, genre.id == genreId) { genreId = genre.id })
                            }
                        }
                        OpenTray.Rating -> listOf<Int?>(null, 6, 7, 8, 9).map { value ->
                            SearchFilterOption(value?.let { "$it+" } ?: "Any", minRating == value) { minRating = value }
                        }
                        OpenTray.None -> emptyList()
                    },
                )
            }

            val contentPhase = when {
                loading && results.isEmpty() -> TvContentPhase.Loading
                failed -> TvContentPhase.Error
                visibleResults.isEmpty() -> TvContentPhase.Empty
                else -> TvContentPhase.Content
            }
            TvContentSwap(phase = contentPhase, modifier = Modifier.weight(1f).fillMaxWidth()) { phase ->
            when (phase) {
                TvContentPhase.Loading -> TvSkeletonGrid(columns = gridColumns, rows = 3)

                TvContentPhase.Error -> TvEmptyState(
                    title = "Could not load $networkName",
                    message = "The catalogue could not be reached. Check the connection and try again.",
                    actionLabel = "Try Again",
                    onAction = { reloadToken++ },
                )

                TvContentPhase.Empty -> TvEmptyState(
                    title = "Nothing matches these filters",
                    message = "Widen the year, genre or rating and try again.",
                    actionLabel = "Clear Filters",
                    onAction = { mediaType = "all"; year = null; genreId = null; minRating = null },
                )

                TvContentPhase.Content -> LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().focusGroup(),
                    contentPadding = PaddingValues(
                        start = NetworkInset, end = NetworkInset, top = 2.dp, bottom = 72.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                    verticalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                ) {
                    itemsIndexed(visibleResults, key = { _, item -> "${item.type}:${item.id}" }) { index, item ->
                        val requester = cardRequesters.getOrPut("${item.type}:${item.id}") { FocusRequester() }
                        val effective = if (index == 0) firstCardRequester else requester
                        PremiumMediaCard(
                            item = item,
                            variant = TvMediaCardVariant.Poster,
                            modifier = Modifier
                                .focusRequester(effective)
                                .width(132.dp)
                                .height(198.dp)
                                .focusProperties { if (index < gridColumns) up = firstChipRequester }
                                .tvCardLongPress { actionState = BrowseActionState(item, effective) },
                            onClick = { onOpenDetail(item.type, item.detailLookupId()) },
                            onLongPress = { actionState = BrowseActionState(item, effective) },
                        )
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
                    val restore = state.restoreFocusRequester
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

/** Brand wash for the header. Only a hint of colour; the grid below stays neutral. */
private fun networkTint(name: String): Color = when {
    name.contains("netflix", true) -> Color(0xFF8E0E16)
    name.contains("prime", true) -> Color(0xFF0F4C81)
    name.contains("apple", true) -> Color(0xFF6E7681)
    name.contains("hbo", true) -> Color(0xFF4A3DC7)
    name.contains("disney", true) -> Color(0xFF1B3E88)
    name.contains("hulu", true) -> Color(0xFF1CE783)
    name.contains("paramount", true) -> Color(0xFF0064FF)
    name.contains("peacock", true) -> Color(0xFF7B3FE4)
    else -> Color(0xFF1D2430)
}
