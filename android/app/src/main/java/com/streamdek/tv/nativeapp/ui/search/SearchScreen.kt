package com.streamdek.tv.nativeapp.ui.search

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import coil.imageLoader
import coil.request.ImageRequest
import com.streamdek.tv.nativeapp.data.GenreItem
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.BrowseItemActionMenu
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.PremiumMediaCard
import com.streamdek.tv.nativeapp.ui.TvEmptyState
import com.streamdek.tv.nativeapp.ui.TvMediaCardVariant
import com.streamdek.tv.nativeapp.ui.TvSkeletonGrid
import com.streamdek.tv.nativeapp.ui.TvSpacing
import com.streamdek.tv.nativeapp.ui.tvCardLongPress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Year
import com.streamdek.tv.nativeapp.ui.LocalSideNavOwnsFocus

private data class BrowseActionState(
    val item: MediaItem,
    val restoreFocusRequester: FocusRequester,
)

private enum class SearchScope(val label: String) {
    All("Everything"), Movies("Movies"), Series("Series"), Live("Live TV")
}

private enum class OpenTray { None, Type, Genre, Year }

private val DiscoverTypes = listOf("movie", "tv", "documentary")

private fun discoverTypeLabel(value: String): String = when (value) {
    "tv" -> "Series"
    "documentary" -> "Documentaries"
    else -> "Films"
}

private data class YearOption(val label: String, val value: String?)

private fun buildYearOptions(): List<YearOption> {
    val now = Year.now().value
    return buildList {
        add(YearOption("Any Year", null))
        (0..14).forEach { add(YearOption("${now - it}", "${now - it}")) }
        add(YearOption("Before 2010", "before:2009"))
        add(YearOption("Before 2000", "before:1999"))
    }
}

/**
 * Search and Discover.
 *
 * Rebuilt as one vertical surface — query, controls, then results — instead of a collapsing left
 * column beside a grid. Three things drove that:
 *
 *  - **The old rail hid its own contents.** It only drew while it had focus, so the moment the
 *    viewer moved right into the results they could no longer see what they had searched for,
 *    which scope was applied, or how many matches there were.
 *  - **Mode changed invisibly.** The rail silently switched between Discover filters and result
 *    scopes on `query.length >= 2`, so the same column meant different things with no signal.
 *  - **Filters opened modal dialogs**, which on a remote costs a focus teleport out and back and
 *    hides the very results the filter is about to change. They expand in place now.
 *
 * The controls stay on screen and in one position throughout, so what is applied is always
 * readable next to what it produced.
 */
@Composable
fun SearchScreen(
    repository: StreamDekRepository,
    onOpenDetail: (String, String) -> Unit,
    onPlayLive: (MediaItem) -> Unit,
    entryFocusRequester: FocusRequester? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val gridColumns = LocalTvExperienceSettings.current.gridColumns

    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var queryFocused by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    /** Add-on catalog matches, kept apart so they can land after the faster TMDB pass. */
    var addonResults by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var addonSearching by remember { mutableStateOf(false) }
    var searchScope by remember { mutableStateOf(SearchScope.All) }
    var actionState by remember { mutableStateOf<BrowseActionState?>(null) }
    var openTray by remember { mutableStateOf(OpenTray.None) }

    var discoverType by remember { mutableStateOf("movie") }
    var discoverGenreId by remember { mutableStateOf<Int?>(null) }
    var discoverYear by remember { mutableStateOf<String?>(null) }
    var discoverGenres by remember { mutableStateOf<List<GenreItem>>(emptyList()) }
    var discoverItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var discoverPage by remember { mutableIntStateOf(1) }
    var discoverTotalPages by remember { mutableIntStateOf(1) }
    var discoverLoading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }

    val localQueryRequester = remember { FocusRequester() }
    val queryRequester = entryFocusRequester ?: localQueryRequester
    val firstChipRequester = remember { FocusRequester() }
    val voiceRequester = remember { FocusRequester() }
    val firstCardRequester = remember { FocusRequester() }
    val trayRequester = remember { FocusRequester() }
    val cardRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val gridState = rememberLazyGridState()
    val yearOptions = remember { buildYearOptions() }
    val voiceSearchLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { spoken ->
                    query = spoken
                    editing = false
                }
        }
    }
    val voiceSearchIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Search StreamDek")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
    }

    val searchHistory = remember {
        context.getSharedPreferences("streamdek_tv_search", android.content.Context.MODE_PRIVATE)
    }
    var recentSearches by remember {
        mutableStateOf(
            searchHistory.getString("recent", "").orEmpty()
                .split('').filter { it.isNotBlank() }.take(6),
        )
    }

    val hasQuery = query.trim().length >= 2
    // Add-on matches follow the TMDB ones rather than interleaving: the ordering stays stable as
    // the slower add-on pass lands, so nothing shifts under a viewer already moving through the grid.
    val allResults = remember(results, addonResults) { results + addonResults }
    val visibleResults = remember(allResults, searchScope) {
        allResults.filter {
            when (searchScope) {
                SearchScope.All -> true
                SearchScope.Movies -> it.type == "movie"
                SearchScope.Series -> it.type == "tv"
                SearchScope.Live -> it.type == "live"
            }
        }
    }
    val rawItems = if (hasQuery) visibleResults else discoverItems
    // Only a spinner while nothing is on screen yet. Once the TMDB pass has landed, add-ons still
    // answering must not blank out results the viewer can already act on.
    val searchingAnything = searching || addonSearching
    // Same guard as Library: a repeated entry must not take the screen down.
    val items = remember(rawItems) {
        rawItems.distinctBy { listOf(it.type, it.sourceAddonId.orEmpty(), it.sourceCatalogId.orEmpty(), it.id) }
    }
    val loading = if (hasQuery) searchingAnything && items.isEmpty() else discoverLoading
    // A query with no matches leaves nothing holding [firstCardRequester], and focus search
    // resolving to an unclaimed requester throws rather than doing nothing. Default hands the press
    // back to ordinary search, which finds nothing below the chips and stays put.
    val gridFocusTarget = if (items.isEmpty()) FocusRequester.Default else firstCardRequester

    LaunchedEffect(discoverType) {
        discoverGenreId = null
        discoverGenres = if (discoverType == "documentary") {
            emptyList()
        } else {
            runCatching { repository.fetchGenres(discoverType) }.getOrDefault(emptyList())
        }
    }

    LaunchedEffect(discoverType, discoverGenreId, discoverYear) {
        discoverLoading = true
        val payload = runCatching {
            repository.fetchDiscover(discoverType, page = 1, genreId = discoverGenreId, year = discoverYear)
        }.getOrNull()
        discoverItems = payload?.results.orEmpty().distinctBy { "${it.type}:${it.id}" }
        discoverPage = payload?.page ?: 1
        discoverTotalPages = payload?.total_pages ?: 1
        discoverLoading = false
    }

    // Endless discover: fetch the next page a little before the viewer reaches the end.
    LaunchedEffect(gridState, discoverItems.size, discoverPage, discoverTotalPages, hasQuery) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                if (hasQuery || discoverLoading || loadingMore) return@collect
                if (discoverPage >= discoverTotalPages || discoverItems.isEmpty()) return@collect
                if (lastVisible < discoverItems.size - gridColumns * 2) return@collect
                loadingMore = true
                val payload = runCatching {
                    repository.fetchDiscover(
                        discoverType, page = discoverPage + 1,
                        genreId = discoverGenreId, year = discoverYear,
                    )
                }.getOrNull()
                if (payload != null) {
                    discoverItems = (discoverItems + payload.results).distinctBy { "${it.type}:${it.id}" }
                    discoverPage = payload.page
                    discoverTotalPages = payload.total_pages
                }
                loadingMore = false
            }
    }

    val sideNavOwnsFocus = LocalSideNavOwnsFocus.current

    LaunchedEffect(Unit) {
        delay(180)
        // Not while the side navigation owns the D-pad — see LocalSideNavOwnsFocus.
        if (sideNavOwnsFocus) return@LaunchedEffect
        runCatching { queryRequester.requestFocus() }
    }

    LaunchedEffect(query) {
        val normalized = query.trim()
        if (normalized.length < 2) {
            results = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(260)
        results = repository.searchMedia(normalized)
        if (results.isNotEmpty()) {
            recentSearches = (listOf(normalized) + recentSearches.filterNot { it.equals(normalized, true) }).take(6)
            searchHistory.edit().putString("recent", recentSearches.joinToString("")).apply()
        }
        searching = false
    }

    // Add-on catalogs answer the same query in their own effect, so one slow provider delays only
    // its own results. Cancelled and restarted with the query like the pass above.
    LaunchedEffect(query) {
        val normalized = query.trim()
        if (normalized.length < 2) {
            addonResults = emptyList()
            addonSearching = false
            return@LaunchedEffect
        }
        addonSearching = true
        delay(260)
        addonResults = runCatching { repository.searchAddonCatalogs(normalized) }.getOrDefault(emptyList())
        addonSearching = false
    }

    LaunchedEffect(items.size) {
        items.take(8).mapNotNull { it.poster ?: it.backdrop }.distinct().forEach { url ->
            context.imageLoader.enqueue(
                ImageRequest.Builder(context).data(url).memoryCacheKey(url).diskCacheKey(url)
                    .crossfade(false).allowHardware(true).allowRgb565(true).build(),
            )
        }
    }

    // Opening a tray moves focus into it so the first option is one press away.
    LaunchedEffect(openTray) {
        if (openTray == OpenTray.None) return@LaunchedEffect
        delay(60)
        runCatching { trayRequester.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // ── Query ────────────────────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = SearchInset, end = SearchInset, top = 34.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SearchQueryDisplay(
                    query = query,
                    editing = editing,
                    focused = queryFocused,
                    modifier = Modifier.weight(1f),
                )
            }

            // The real input sits under the display, one line tall, carrying the IME. Keeping it
            // separate lets the display above stay large and legible from the sofa.
            Row(
                modifier = Modifier
                    .padding(horizontal = SearchInset)
                    .fillMaxWidth(0.62f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    readOnly = !editing,
                    shape = AppPillShape,
                    keyboardActions = KeyboardActions(
                        onDone = {
                            editing = false
                            runCatching { queryRequester.requestFocus() }
                        },
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(alpha = 0.10f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .focusRequester(queryRequester)
                        .focusProperties {
                            right = voiceRequester
                            down = firstChipRequester
                        }
                        .onFocusChanged {
                            queryFocused = it.isFocused
                            if (!it.isFocused) editing = false
                        }
                        .onPreviewKeyEvent { event ->
                            val select = event.key == Key.DirectionCenter || event.key == Key.Enter ||
                                event.key == Key.NumPadEnter
                            if (!editing && event.type == KeyEventType.KeyUp && select) {
                                editing = true
                                true
                            } else {
                                false
                            }
                        },
                )
                SearchChip(
                    label = "Voice search",
                    leading = "MIC",
                    selected = false,
                    modifier = Modifier
                        .width(180.dp)
                        .height(52.dp)
                        .focusRequester(voiceRequester)
                        .focusProperties {
                            left = queryRequester
                            down = firstChipRequester
                        },
                    onClick = {
                        runCatching { voiceSearchLauncher.launch(voiceSearchIntent) }
                            .onFailure {
                                editing = true
                                runCatching { queryRequester.requestFocus() }
                            }
                    },
                )
            }

            // ── Controls ─────────────────────────────────────────────────────────────────────
            //
            // Always present, always in the same place, whether the grid is showing matches or
            // recommendations. What they mean changes with the mode, but the mode is stated in
            // the heading below rather than left to be inferred.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .focusGroup()
                    .padding(horizontal = SearchInset, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasQuery) {
                    SearchScope.entries.forEachIndexed { index, option ->
                        SearchChip(
                            label = option.label,
                            selected = searchScope == option,
                            modifier = (if (index == 0) Modifier.focusRequester(firstChipRequester) else Modifier)
                                .focusProperties { up = queryRequester; down = gridFocusTarget },
                            onClick = { searchScope = option },
                        )
                    }
                } else {
                    SearchChip(
                        label = discoverTypeLabel(discoverType),
                        selected = openTray == OpenTray.Type,
                        leading = "Show",
                        modifier = Modifier.focusRequester(firstChipRequester)
                            .focusProperties { up = queryRequester; down = gridFocusTarget },
                        onClick = { openTray = if (openTray == OpenTray.Type) OpenTray.None else OpenTray.Type },
                    )
                    val genreLabel = discoverGenres.firstOrNull { it.id == discoverGenreId }?.name ?: "All Genres"
                    SearchChip(
                        label = if (discoverGenres.isEmpty()) "No genres" else genreLabel,
                        selected = openTray == OpenTray.Genre,
                        leading = "Genre",
                        modifier = Modifier.focusProperties { up = queryRequester; down = gridFocusTarget },
                        onClick = {
                            if (discoverGenres.isNotEmpty()) {
                                openTray = if (openTray == OpenTray.Genre) OpenTray.None else OpenTray.Genre
                            }
                        },
                    )
                    SearchChip(
                        label = yearOptions.firstOrNull { it.value == discoverYear }?.label ?: "Any Year",
                        selected = openTray == OpenTray.Year,
                        leading = "Year",
                        modifier = Modifier.focusProperties { up = queryRequester; down = gridFocusTarget },
                        onClick = { openTray = if (openTray == OpenTray.Year) OpenTray.None else OpenTray.Year },
                    )
                    recentSearches.take(3).forEach { recent ->
                        SearchChip(
                            label = recent,
                            selected = false,
                            leading = "Recent",
                            modifier = Modifier.focusProperties { up = queryRequester; down = gridFocusTarget },
                            onClick = { query = recent },
                        )
                    }
                }
            }

            if (openTray != OpenTray.None) {
                SearchFilterTray(
                    firstOptionRequester = trayRequester,
                    onDismiss = { openTray = OpenTray.None },
                    options = when (openTray) {
                        OpenTray.Type -> DiscoverTypes.map { value ->
                            SearchFilterOption(discoverTypeLabel(value), discoverType == value) { discoverType = value }
                        }
                        OpenTray.Genre -> buildList {
                            add(SearchFilterOption("All Genres", discoverGenreId == null) { discoverGenreId = null })
                            discoverGenres.forEach { genre ->
                                add(SearchFilterOption(genre.name, genre.id == discoverGenreId) { discoverGenreId = genre.id })
                            }
                        }
                        OpenTray.Year -> yearOptions.map { option ->
                            SearchFilterOption(option.label, option.value == discoverYear) { discoverYear = option.value }
                        }
                        OpenTray.None -> emptyList()
                    },
                )
            }

            SearchResultsHeading(
                title = if (hasQuery) "Results" else "Discover",
                detail = when {
                    loading -> "Searching…"
                    hasQuery -> "${visibleResults.size} for \"${query.trim()}\""
                    else -> "${discoverItems.size} titles"
                },
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
            )

            // ── Grid ─────────────────────────────────────────────────────────────────────────
            when {
                loading && items.isEmpty() -> TvSkeletonGrid(columns = gridColumns, rows = 3)

                items.isEmpty() -> TvEmptyState(
                    title = if (hasQuery) "No matches" else "Nothing to show",
                    message = if (hasQuery) {
                        "Try a shorter title, or part of a channel name."
                    } else {
                        "Widen the genre or year and try again."
                    },
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    state = gridState,
                    modifier = Modifier.weight(1f).fillMaxWidth().focusGroup(),
                    contentPadding = PaddingValues(
                        start = SearchInset, end = SearchInset, top = 2.dp, bottom = 72.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                    verticalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                ) {
                    itemsIndexed(
                        items,
                        key = { _, item ->
                            listOf(item.type, item.sourceAddonId.orEmpty(), item.sourceCatalogId.orEmpty(), item.id)
                                .joinToString(":")
                        },
                    ) { index, item ->
                        val key = listOf(item.type, item.sourceAddonId.orEmpty(), item.id).joinToString(":")
                        val requester = cardRequesters.getOrPut(key) { FocusRequester() }
                        val effective = if (index == 0) firstCardRequester else requester
                        PremiumMediaCard(
                            item = item,
                            variant = if (item.type == "live") TvMediaCardVariant.Live else TvMediaCardVariant.Poster,
                            modifier = Modifier
                                .focusRequester(effective)
                                .width(SearchCardWidth)
                                .height(SearchCardHeight)
                                .focusProperties { if (index < gridColumns) up = firstChipRequester }
                                .tvCardLongPress {
                                    if (item.type != "live") actionState = BrowseActionState(item, effective)
                                },
                            onClick = {
                                if (item.type == "live") onPlayLive(item) else onOpenDetail(item.type, item.detailLookupId())
                            },
                            onLongPress = {
                                if (item.type != "live") actionState = BrowseActionState(item, effective)
                            },
                        )
                    }
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
                        delay(40)
                        runCatching { restoreRequester.requestFocus() }
                    }
                },
                onOpenDetail = { onOpenDetail(state.item.type, state.item.detailLookupId()) },
                onChanged = {
                    if (hasQuery) results = repository.searchMedia(query.trim(), forceRefresh = true)
                },
            )
        }
    }
}
