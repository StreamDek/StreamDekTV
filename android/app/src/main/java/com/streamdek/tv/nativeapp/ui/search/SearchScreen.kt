package com.streamdek.tv.nativeapp.ui.search

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
private enum class SearchScope(val label: String) { All("Everything"), Movies("Movies"), Series("Series"), Live("Live TV") }

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
    onPlayLive: (MediaItem) -> Unit,
    entryFocusRequester: FocusRequester? = null,
) {
    val bootstrap by repository.bootstrap.collectAsState()
    val compactMode = bootstrap?.preferences?.app?.compactMode == true
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searchEditing by remember { mutableStateOf(false) }
    var searchScope by remember { mutableStateOf(SearchScope.All) }
    var actionState by remember { mutableStateOf<BrowseActionState?>(null) }
    val scope = rememberCoroutineScope()
    val localSearchRequester = remember { FocusRequester() }
    val searchBoxRequester = entryFocusRequester ?: localSearchRequester
    val firstResultRequester = remember { FocusRequester() }
    val cardRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val context = androidx.compose.ui.platform.LocalContext.current

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
    val visibleResults = remember(results, searchScope) {
        results.filter { item ->
            when (searchScope) {
                SearchScope.All -> true
                SearchScope.Movies -> item.type == "movie"
                SearchScope.Series -> item.type == "tv"
                SearchScope.Live -> item.type == "live"
            }
        }
    }

    LaunchedEffect(discoverType) {
        discoverGenreId = null
        discoverGenres = if (discoverType == "documentary") emptyList()
        else runCatching { repository.fetchGenres(discoverType) }.getOrDefault(emptyList())
    }

    LaunchedEffect(discoverType, discoverGenreId, discoverYear) {
        discoverLoading = true
        discoverPage = 1
        discoverTotalPages = 1
        val payload = runCatching {
            repository.fetchDiscover(discoverType, page = 1, genreId = discoverGenreId, year = discoverYear)
        }.getOrNull()
        discoverItems = payload?.results.orEmpty().distinctBy { it.type + ":" + it.id }
        discoverPage = payload?.page ?: 1
        discoverTotalPages = payload?.total_pages ?: 1
        discoverLoading = false
    }

    LaunchedEffect(discoverGridState, discoverItems.size, discoverPage, discoverTotalPages, showDiscover) {
        androidx.compose.runtime.snapshotFlow {
            discoverGridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        }.collect { lastVisible ->
            if (!showDiscover || discoverLoading || discoverLoadingMore) return@collect
            if (discoverPage >= discoverTotalPages || discoverItems.isEmpty() || lastVisible < discoverItems.size - 8) return@collect
            discoverLoadingMore = true
            val payload = runCatching {
                repository.fetchDiscover(discoverType, page = discoverPage + 1, genreId = discoverGenreId, year = discoverYear)
            }.getOrNull()
            if (payload != null) {
                discoverItems = (discoverItems + payload.results).distinctBy { it.type + ":" + it.id }
                discoverPage = payload.page
                discoverTotalPages = payload.total_pages
            }
            discoverLoadingMore = false
        }
    }

    LaunchedEffect(Unit) {
        delay(180)
        runCatching { searchBoxRequester.requestFocus() }
    }

    LaunchedEffect(query) {
        val normalized = query.trim()
        if (normalized.length < 2) {
            results = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        delay(260)
        results = repository.searchMedia(normalized)
        loading = false
    }

    LaunchedEffect(results) {
        results.take(8).flatMap { listOfNotNull(it.backdrop, it.poster) }.distinct().take(10).forEach { url ->
            context.imageLoader.enqueue(
                ImageRequest.Builder(context).data(url).memoryCacheKey(url).diskCacheKey(url)
                    .crossfade(false).allowHardware(true).build(),
            )
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .width(if (compactMode) 204.dp else 218.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 12.dp, top = 72.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("SEARCH", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black), color = Color.White)
            Text("Find movies, series and channels", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { androidx.compose.material3.Text("Title or channel") },
                singleLine = true,
                readOnly = !searchEditing,
                shape = RoundedCornerShape(16.dp),
                keyboardActions = KeyboardActions(onDone = {
                    searchEditing = false
                    runCatching { searchBoxRequester.requestFocus() }
                }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF1A1F28),
                    unfocusedContainerColor = Color(0xFF11151C),
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedLabelColor = Color.White,
                    unfocusedLabelColor = Color.White.copy(alpha = 0.68f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .focusRequester(searchBoxRequester)
                    .focusProperties {
                        right = if (showDiscover) discoverFirstCardRequester else firstResultRequester
                        down = if (showDiscover) typeFieldRequester else FocusRequester.Default
                    }
                    .onPreviewKeyEvent { event ->
                        if (!searchEditing && event.type == KeyEventType.KeyUp &&
                            (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                        ) {
                            searchEditing = true
                            true
                        } else false
                    }
                    .onFocusChanged { if (!it.isFocused) searchEditing = false },
            )

            if (showDiscover) {
                SidebarSectionLabel("DISCOVER")
                DiscoverField(
                    label = "Content",
                    value = discoverTypeLabel(discoverType),
                    onClick = { openFilter = DiscoverFilter.Type },
                    modifier = Modifier.focusRequester(typeFieldRequester).focusProperties { up = searchBoxRequester; right = discoverFirstCardRequester },
                )
                DiscoverField(
                    label = "Genre",
                    value = if (discoverType != "documentary" && discoverGenres.isNotEmpty()) discoverGenreLabel else "Not available",
                    enabled = discoverType != "documentary" && discoverGenres.isNotEmpty(),
                    onClick = { openFilter = DiscoverFilter.Genre },
                    modifier = Modifier.focusProperties { right = discoverFirstCardRequester },
                )
                DiscoverField(
                    label = "Release year",
                    value = discoverYearLabel,
                    onClick = { openFilter = DiscoverFilter.Year },
                    modifier = Modifier.focusProperties { right = discoverFirstCardRequester },
                )
                Text(discoverItems.size.toString() + " titles loaded", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
            } else {
                SidebarSectionLabel("SHOW RESULTS")
                SearchScope.entries.forEach { option ->
                    SearchScopeRow(
                        label = option.label,
                        selected = searchScope == option,
                        onClick = { searchScope = option },
                        modifier = Modifier.focusProperties { right = firstResultRequester },
                    )
                }
                Text(
                    visibleResults.size.toString() + if (visibleResults.size == 1) " result" else " results",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(start = if (compactMode) 234.dp else 250.dp, top = 88.dp, end = 44.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (showDiscover) "Explore what to watch" else if (loading) "Searching…" else "Results for “" + query.trim() + "”",
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
            )
            Text(
                if (showDiscover) "Use the filters on the left, then move right to browse."
                else "Movies, series and matching Live TV addon channels appear together.",
                color = Color.White.copy(alpha = 0.70f),
                style = MaterialTheme.typography.bodyLarge,
            )

            if (showDiscover) {
                DiscoverSection(
                    gridState = discoverGridState,
                    items = discoverItems,
                    loading = discoverLoading,
                    loadingMore = discoverLoadingMore,
                    firstCardRequester = discoverFirstCardRequester,
                    leftRequester = typeFieldRequester,
                    cardRequesterFor = { key -> discoverCardRequesters.getOrPut(key) { FocusRequester() } },
                    onOpenDetail = onOpenDetail,
                    onItemMenu = { item, requester -> actionState = BrowseActionState(item, requester) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                when {
                    loading -> SearchEmptyState("Searching every StreamDek source…")
                    visibleResults.isEmpty() -> SearchEmptyState("No matching titles or live channels. Try a shorter channel name.")
                    else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(5),
                        modifier = Modifier.fillMaxSize().focusGroup(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 140.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        itemsIndexed(visibleResults, key = { _, item ->
                            listOf(item.type, item.sourceAddonId.orEmpty(), item.sourceCatalogId.orEmpty(), item.id).joinToString(":")
                        }) { index, item ->
                            val key = listOf(item.type, item.sourceAddonId.orEmpty(), item.id).joinToString(":")
                            val requester = cardRequesters.getOrPut(key) { FocusRequester() }
                            SearchResultCard(
                                item = item,
                                modifier = (if (index == 0) Modifier.focusRequester(firstResultRequester) else Modifier.focusRequester(requester))
                                    .focusProperties { left = searchBoxRequester },
                                onPressed = { if (item.type == "live") onPlayLive(item) else onOpenDetail(item.type, item.id) },
                                onMenuPressed = {
                                    if (item.type != "live") actionState = BrowseActionState(item, if (index == 0) firstResultRequester else requester)
                                },
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
                        DiscoverOption(discoverTypeLabel(value), discoverType == value) { discoverType = value; openFilter = null }
                    }
                    DiscoverFilter.Genre -> buildList {
                        add(DiscoverOption("All Genres", discoverGenreId == null) { discoverGenreId = null; openFilter = null })
                        discoverGenres.forEach { genre ->
                            add(DiscoverOption(genre.name, genre.id == discoverGenreId) { discoverGenreId = genre.id; openFilter = null })
                        }
                    }
                    DiscoverFilter.Year -> yearOptions.map { option ->
                        DiscoverOption(option.label, option.value == discoverYear) { discoverYear = option.value; openFilter = null }
                    }
                },
                onDismiss = {
                    openFilter = null
                    scope.launch { delay(40); runCatching { typeFieldRequester.requestFocus() } }
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
                    scope.launch { delay(40); runCatching { restoreRequester.requestFocus() } }
                },
                onOpenDetail = { onOpenDetail(state.item.type, state.item.id) },
                onChanged = { if (!showDiscover) results = repository.searchMedia(query.trim(), forceRefresh = true) },
            )
        }
    }
}

@Composable
private fun SidebarSectionLabel(label: String) {
    Text(
        label,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SearchEmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.titleLarge, color = Color.White.copy(alpha = 0.76f))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchScopeRow(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(40.dp),
        shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
        colors = CardDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
            focusedContainerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else Color(0xFF202630),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(14.dp)),
        ),
        scale = CardDefaults.scale(focusedScale = 1.035f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold))
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
            .fillMaxWidth()
            .height(250.dp)
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
                    text = when (item.type) { "live" -> "Live TV"; "tv" -> "Series"; else -> "Movie" },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
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
    firstCardRequester: FocusRequester,
    leftRequester: FocusRequester,
    cardRequesterFor: (String) -> FocusRequester,
    onOpenDetail: (String, String) -> Unit,
    onItemMenu: (MediaItem, FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        loading -> SearchEmptyState("Loading recommendations…")
        items.isEmpty() -> SearchEmptyState("No titles match these filters.")
        else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(5),
            state = gridState,
            modifier = modifier.focusGroup(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 140.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.type + ":" + item.id }) { index, item ->
                val key = item.type + ":" + item.id
                val requester = cardRequesterFor(key)
                DiscoverPosterCard(
                    item = item,
                    modifier = (if (index == 0) Modifier.focusRequester(firstCardRequester) else Modifier.focusRequester(requester))
                        .focusProperties { left = leftRequester },
                    onPressed = { onOpenDetail(item.type, item.id) },
                    onMenuPressed = { onItemMenu(item, if (index == 0) firstCardRequester else requester) },
                )
            }
            if (loadingMore) item { Text("Loading more…", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)) }
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
            .height(52.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color(0xFFF5F1E8),
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
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
                    Color.White.copy(alpha = if (enabled) 1f else 0.45f)
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
            .height(250.dp)
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
