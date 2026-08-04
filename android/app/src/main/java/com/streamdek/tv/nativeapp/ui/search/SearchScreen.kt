package com.streamdek.tv.nativeapp.ui.search

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Border
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.BrowseItemActionMenu
import com.streamdek.tv.nativeapp.ui.tvCardLongPress
import com.streamdek.tv.nativeapp.ui.animateToAnchoredItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class BrowseActionState(
    val item: MediaItem,
    val restoreFocusRequester: FocusRequester,
)

private data class DiscoverYearOption(val label: String, val value: String?)

/** Which Discover filter picker is open, if any. */
private enum class DiscoverFilter { Type, Genre, Year }

private val DiscoverTypes = listOf("movie", "tv", "documentary")

private fun discoverTypeLabel(value: String): String = when (value) {
    "tv" -> "Series"
    "documentary" -> "Documentaries"
    else -> "Movies"
}

private fun buildYearOptions(): List<DiscoverYearOption> {
    val currentYear = java.time.LocalDate.now().year
    return buildList {
        add(DiscoverYearOption("Any Year", null))
        for (year in currentYear downTo (currentYear - 19)) {
            add(DiscoverYearOption(year.toString(), year.toString()))
        }
        add(DiscoverYearOption("Before 2000", "before:1999"))
    }
}

@Composable
fun SearchScreen(
    repository: StreamDekRepository,
    onOpenDetail: (String, String) -> Unit,
) {
    val bootstrap by repository.bootstrap.collectAsState()
    val compactMode = bootstrap?.preferences?.app?.compactMode == true
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searchEditing by remember { mutableStateOf(false) }
    var actionState by remember { mutableStateOf<BrowseActionState?>(null) }
    val scope = rememberCoroutineScope()
    val searchBoxRequester = remember { FocusRequester() }
    val firstResultRequester = remember { FocusRequester() }
    val cardRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var anchoredIndex by remember { mutableIntStateOf(0) }
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val focusManager = LocalFocusManager.current

    // Discover: the browse experience shown whenever the query is empty, matching the
    // mobile app's Search tab.
    var discoverType by remember { mutableStateOf("movie") }
    var discoverGenreId by remember { mutableStateOf<Int?>(null) }
    var discoverYear by remember { mutableStateOf<String?>(null) }
    var discoverGenres by remember { mutableStateOf<List<com.streamdek.tv.nativeapp.data.GenreItem>>(emptyList()) }
    var discoverItems by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var discoverPage by remember { mutableIntStateOf(1) }
    var discoverTotalPages by remember { mutableIntStateOf(1) }
    var discoverLoading by remember { mutableStateOf(true) }
    var discoverLoadingMore by remember { mutableStateOf(false) }
    var openFilter by remember { mutableStateOf<DiscoverFilter?>(null) }
    val yearOptions = remember { buildYearOptions() }
    val discoverGridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val discoverFirstCardRequester = remember { FocusRequester() }
    val typeFieldRequester = remember { FocusRequester() }
    val discoverCardRequesters = remember { mutableMapOf<String, FocusRequester>() }

    val discoverGenreLabel = discoverGenres.firstOrNull { it.id == discoverGenreId }?.name ?: "All Genres"
    val discoverYearLabel = yearOptions.firstOrNull { it.value == discoverYear }?.label ?: "Any Year"
    val showDiscover = query.trim().length < 2

    // Documentaries are a fixed genre, so the genre picker does not apply to them.
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
        discoverPage = 1
        discoverTotalPages = 1
        val payload = runCatching {
            repository.fetchDiscover(discoverType, page = 1, genreId = discoverGenreId, year = discoverYear)
        }.getOrNull()
        discoverItems = payload?.results.orEmpty().distinctBy { "${it.type}-${it.id}" }
        discoverPage = payload?.page ?: 1
        discoverTotalPages = payload?.total_pages ?: 1
        discoverLoading = false
    }

    // Endless browse: pull the next page as the viewer nears the end of the grid.
    LaunchedEffect(discoverGridState, discoverItems.size, discoverPage, discoverTotalPages, showDiscover) {
        androidx.compose.runtime.snapshotFlow {
            discoverGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }.collect { lastVisible ->
            if (!showDiscover || discoverLoading || discoverLoadingMore) return@collect
            if (discoverPage >= discoverTotalPages || discoverItems.isEmpty()) return@collect
            if (lastVisible < discoverItems.size - 8) return@collect
            discoverLoadingMore = true
            val nextPage = discoverPage + 1
            val payload = runCatching {
                repository.fetchDiscover(discoverType, page = nextPage, genreId = discoverGenreId, year = discoverYear)
            }.getOrNull()
            if (payload != null) {
                discoverItems = (discoverItems + payload.results).distinctBy { "${it.type}-${it.id}" }
                discoverPage = payload.page
                discoverTotalPages = payload.total_pages
            }
            discoverLoadingMore = false
        }
    }

    LaunchedEffect(Unit) {
        delay(180)
        searchBoxRequester.requestFocus()
    }

    LaunchedEffect(query) {
        val normalized = query.trim()
        if (normalized.length < 2) {
            results = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        delay(220)
        results = repository.searchMedia(normalized)
        loading = false
    }

    LaunchedEffect(results) {
        results.take(8).flatMap { listOfNotNull(it.backdrop, it.poster) }.distinct().take(10).forEach { url ->
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

    LaunchedEffect(searchEditing) {
        if (searchEditing) {
            delay(40)
            searchBoxRequester.requestFocus()
        }
    }

    LaunchedEffect(anchoredIndex, results.size) {
        rowState.animateToAnchoredItem(
            focusedIndex = anchoredIndex,
            itemCount = results.size,
            leadingItems = 1,
        )
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Search",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Search for a title, or browse Discover below.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { androidx.compose.material3.Text("Title, actor, or genre") },
                singleLine = true,
                readOnly = !searchEditing,
                shape = RoundedCornerShape(999.dp),
                keyboardActions = KeyboardActions(
                    onDone = {
                        searchEditing = false
                        focusManager.clearFocus(force = true)
                    },
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF11141B),
                    unfocusedContainerColor = Color(0xFF11141B),
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = Color(0x3311161D),
                    focusedTextColor = Color(0xFFF5F1E8),
                    unfocusedTextColor = Color(0xFFF5F1E8),
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = Color(0xB3F5F1E8),
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier
                    .width(640.dp)
                    .height(56.dp)
                    .focusRequester(searchBoxRequester)
                    .focusProperties {
                        if (results.isNotEmpty()) down = firstResultRequester
                    }
                    .onPreviewKeyEvent { event ->
                        if (
                            !searchEditing &&
                            event.type == KeyEventType.KeyUp &&
                            (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                        ) {
                            searchEditing = true
                            true
                        } else {
                            false
                        }
                    }
                    .onFocusChanged {
                        if (!it.isFocused) {
                            searchEditing = false
                        }
                    },
            )
        }

        if (showDiscover) {
            DiscoverSection(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = if (compactMode) 176.dp else 198.dp),
                gridState = discoverGridState,
                items = discoverItems,
                loading = discoverLoading,
                loadingMore = discoverLoadingMore,
                typeLabel = discoverTypeLabel(discoverType),
                genreLabel = discoverGenreLabel,
                yearLabel = discoverYearLabel,
                genreEnabled = discoverType != "documentary" && discoverGenres.isNotEmpty(),
                typeFieldRequester = typeFieldRequester,
                firstCardRequester = discoverFirstCardRequester,
                upRequester = searchBoxRequester,
                onOpenFilter = { openFilter = it },
                cardRequesterFor = { key -> discoverCardRequesters.getOrPut(key) { FocusRequester() } },
                onOpenDetail = onOpenDetail,
                onItemMenu = { item, requester -> actionState = BrowseActionState(item, requester) },
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(top = if (compactMode) 176.dp else 198.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = if (loading) "Searching…" else "${results.size} results for “${query.trim()}”",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 48.dp, end = 48.dp),
                )
                if (results.isNotEmpty()) {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(6),
                        modifier = Modifier.fillMaxSize().focusGroup(),
                        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 180.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        itemsIndexed(results, key = { _, item -> "${item.type}:${item.id}" }) { index, item ->
                            val key = "${item.type}:${item.id}"
                            val requester = cardRequesters.getOrPut(key) { FocusRequester() }
                            DiscoverPosterCard(
                                item = item,
                                modifier = if (index == 0) Modifier.focusRequester(firstResultRequester).focusProperties { up = searchBoxRequester } else Modifier.focusRequester(requester),
                                onPressed = { onOpenDetail(item.type, item.id) },
                                onMenuPressed = { actionState = BrowseActionState(item, if (index == 0) firstResultRequester else requester) },
                            )
                        }
                    }
                }
            }
        }

        openFilter?.let { filter ->
            DiscoverFilterDialog(
                filter = filter,
                options = when (filter) {
                    DiscoverFilter.Type -> DiscoverTypes.map { value ->
                        DiscoverOption(discoverTypeLabel(value), discoverType == value) {
                            discoverType = value
                            openFilter = null
                        }
                    }
                    DiscoverFilter.Genre -> buildList {
                        add(
                            DiscoverOption("All Genres", discoverGenreId == null) {
                                discoverGenreId = null
                                openFilter = null
                            },
                        )
                        discoverGenres.forEach { genre ->
                            add(
                                DiscoverOption(genre.name, genre.id == discoverGenreId) {
                                    discoverGenreId = genre.id
                                    openFilter = null
                                },
                            )
                        }
                    }
                    DiscoverFilter.Year -> yearOptions.map { option ->
                        DiscoverOption(option.label, option.value == discoverYear) {
                            discoverYear = option.value
                            openFilter = null
                        }
                    }
                },
                onDismiss = {
                    openFilter = null
                    scope.launch {
                        delay(40)
                        runCatching { typeFieldRequester.requestFocus() }
                    }
                },
            )
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
                    // Only the active list needs refreshing; in Discover mode the query
                    // is empty and a search call would be meaningless.
                    if (!showDiscover) {
                        results = repository.searchMedia(query.trim(), forceRefresh = true)
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchResultCard(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onPressed: () -> Unit,
    onMenuPressed: () -> Unit,
) {
    Card(
        onClick = onPressed,
        modifier = modifier
            .size(width = 260.dp, height = 150.dp)
            .tvCardLongPress(onMenuPressed)
            .onFocusChanged { if (it.isFocused) onFocused() },
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
                Text(
                    text = if (item.type == "tv") "Series" else "Movie",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private data class DiscoverOption(
    val label: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
)

/**
 * Browse experience shown while the search box is empty: three filter fields over a
 * paged poster grid, mirroring the mobile app's Discover section.
 */
@Composable
private fun DiscoverSection(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    items: List<MediaItem>,
    loading: Boolean,
    loadingMore: Boolean,
    typeLabel: String,
    genreLabel: String,
    yearLabel: String,
    genreEnabled: Boolean,
    typeFieldRequester: FocusRequester,
    firstCardRequester: FocusRequester,
    upRequester: FocusRequester,
    onOpenFilter: (DiscoverFilter) -> Unit,
    cardRequesterFor: (String) -> FocusRequester,
    onOpenDetail: (String, String) -> Unit,
    onItemMenu: (MediaItem, FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier.width(286.dp).fillMaxSize().background(Color(0xD90B0E14)).padding(start = 38.dp, end = 22.dp, top = 22.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("BROWSE & FILTER", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = MaterialTheme.colorScheme.onBackground)
            Text("Shape the catalogue with the remote, then move right into results.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f))
            DiscoverField(
                label = "Content",
                value = typeLabel,
                onClick = { onOpenFilter(DiscoverFilter.Type) },
                modifier = Modifier.focusRequester(typeFieldRequester).focusProperties { up = upRequester; right = firstCardRequester },
            )
            DiscoverField(
                label = "Genre",
                value = if (genreEnabled) genreLabel else "Not available",
                enabled = genreEnabled,
                onClick = { onOpenFilter(DiscoverFilter.Genre) },
                modifier = Modifier.focusProperties { up = upRequester; right = firstCardRequester },
            )
            DiscoverField(
                label = "Release year",
                value = yearLabel,
                onClick = { onOpenFilter(DiscoverFilter.Year) },
                modifier = Modifier.focusProperties { up = upRequester; right = firstCardRequester },
            )
            Text("${items.size} titles loaded", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.fillMaxSize().padding(start = 318.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Trending for you", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = MaterialTheme.colorScheme.onBackground)
            when {
                loading -> Text("Loading titles…", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                items.isEmpty() -> Text("No titles match these filters.", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(5),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().focusGroup(),
                    contentPadding = PaddingValues(end = 48.dp, bottom = 140.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(items, key = { _, item -> "${item.type}:${item.id}" }) { index, item ->
                        val key = "${item.type}:${item.id}"
                        val requester = cardRequesterFor(key)
                        DiscoverPosterCard(
                            item = item,
                            modifier = if (index == 0) Modifier.focusRequester(firstCardRequester).focusProperties { left = typeFieldRequester; up = upRequester }
                                else Modifier.focusRequester(requester).then(if (index < 5) Modifier.focusProperties { up = upRequester } else Modifier),
                            onPressed = { onOpenDetail(item.type, item.id) },
                            onMenuPressed = { onItemMenu(item, if (index == 0) firstCardRequester else requester) },
                        )
                    }
                    if (loadingMore) item { Text("Loading…", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiscoverField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
        colors = CardDefaults.colors(
            containerColor = Color(0xFF11141B),
            contentColor = Color(0xFFF5F1E8),
            focusedContainerColor = Color(0xFF1B2029),
            focusedContentColor = Color(0xFFF5F1E8),
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(14.dp),
            ),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 0.62f else 0.35f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onBackground.copy(alpha = if (enabled) 1f else 0.45f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiscoverPosterCard(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onPressed: () -> Unit,
    onMenuPressed: () -> Unit,
) {
    Card(
        onClick = onPressed,
        modifier = modifier
            .fillMaxWidth()
            .height(210.dp)
            .tvCardLongPress(onMenuPressed),
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(
            // Transparent container so no light rim can leak at the rounded corners.
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Color.White,
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                shape = AppCardShape,
            ),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = 1.04f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(AppCardShape)
                .background(Color(0xFF101216)),
        ) {
            AsyncImage(
                model = item.poster ?: item.backdrop,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0x00000000), Color(0xE6000000)),
                        ),
                    ),
            )
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiscoverFilterDialog(
    filter: DiscoverFilter,
    options: List<DiscoverOption>,
    onDismiss: () -> Unit,
) {
    val firstOptionRequester = remember(filter) { FocusRequester() }

    LaunchedEffect(filter) {
        delay(60)
        runCatching { firstOptionRequester.requestFocus() }
    }

    androidx.activity.compose.BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF11141B))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = when (filter) {
                    DiscoverFilter.Type -> "Type"
                    DiscoverFilter.Genre -> "Genre"
                    DiscoverFilter.Year -> "Year"
                },
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onBackground,
            )
            LazyColumn(
                modifier = Modifier
                    .height(360.dp)
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(options) { index, option ->
                    DiscoverOptionRow(
                        option = option,
                        modifier = if (index == 0) Modifier.focusRequester(firstOptionRequester) else Modifier,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiscoverOptionRow(
    option: DiscoverOption,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = option.onSelect,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        colors = CardDefaults.colors(
            containerColor = if (option.selected) Color(0x268B5CF6) else Color(0x10FFFFFF),
            contentColor = Color.White,
            focusedContainerColor = if (option.selected) Color(0x338B5CF6) else Color(0x22FFFFFF),
            focusedContentColor = Color.White,
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(12.dp),
            ),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (option.selected) {
                Text(
                    text = "Selected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
