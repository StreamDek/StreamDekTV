package com.streamdek.tv.nativeapp.ui.plex

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.data.HomeRail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.withoutAdult
import com.streamdek.tv.nativeapp.mediaserver.MediaServerReachability
import com.streamdek.tv.nativeapp.mediaserver.MediaServerReference
import com.streamdek.tv.nativeapp.mediaserver.MediaServerSort
import com.streamdek.tv.nativeapp.mediaserver.OfflineReason
import com.streamdek.tv.nativeapp.mediaserver.plex.PlexMapping
import com.streamdek.tv.nativeapp.ui.BrowseItemActionMenu
import com.streamdek.tv.nativeapp.ui.LocalSideNavOwnsFocus
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.PremiumMediaCard
import com.streamdek.tv.nativeapp.ui.TvEmptyState
import com.streamdek.tv.nativeapp.ui.TvMediaCardVariant
import com.streamdek.tv.nativeapp.ui.TvSkeletonGrid
import com.streamdek.tv.nativeapp.ui.TvSpacing
import com.streamdek.tv.nativeapp.ui.home.HomeShelf
import com.streamdek.tv.nativeapp.ui.search.SearchChip
import com.streamdek.tv.nativeapp.ui.tvCardLongPress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * The Plex destination: the viewer's own library, in StreamDek's language.
 *
 * Not Plex's interface and not an embedded one. The rows are Home's rows - the same shelves, the
 * same cards, the same motion - filled from the viewer's servers: what is in progress, what was
 * just added and each library, then what was watched lately and the collections. The library chips
 * along the top open a whole library as a grid for when the viewer knows what they are after.
 *
 * Server attribution appears only where it helps: the header names the servers when there is more
 * than one, and a server that is asleep says so in one line rather than leaving a hole.
 */

private val PlexGold = Color(0xFFE5A00D)
private val PageInset = 24.dp

/** A card's route out of this page. */
internal fun MediaItem.isPlexCollection(): Boolean = type == PlexMapping.COLLECTION_TYPE

@Composable
fun PlexScreen(
    repository: StreamDekRepository,
    entryFocusRequester: FocusRequester? = null,
    onOpenDetail: (String, String) -> Unit,
    onResume: (MediaItem) -> Unit,
    onOpenLibrary: (serverId: String, libraryKey: String, title: String) -> Unit,
    onOpenCollection: (MediaItem) -> Unit,
    onOpenNavigation: () -> Unit,
) {
    val state by repository.mediaServers.state.collectAsState()
    val revision by repository.mediaServers.revision.collectAsState()
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<HomeRail>?>(null) }
    var continueRow by remember { mutableStateOf<HomeRail?>(null) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf<Pair<MediaItem, FocusRequester>?>(null) }
    val listState = rememberLazyListState()
    val rowStates = remember { mutableStateMapOf<String, LazyListState>() }
    /** Which row and card had the highlight, so coming back from a title lands on it. */
    var lastRowId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastItemKey by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreToken by remember { mutableIntStateOf(0) }
    val localEntry = remember { FocusRequester() }
    val firstChipRequester = entryFocusRequester ?: localEntry
    val sideNavOwnsFocus = LocalSideNavOwnsFocus.current
    val continueTitle = stringResource(R.string.home_rail_continue_watching)

    LaunchedEffect(revision, reloadToken) {
        // The two halves load side by side and land as they are ready.
        launch {
            val resumes = runCatching { repository.mediaServerContinueWatching() }.getOrDefault(emptyList())
            continueRow = HomeRail("continue-watching", continueTitle, resumes).takeIf { resumes.isNotEmpty() }
        }
        rows = runCatching { repository.mediaServerPageRows() }.getOrDefault(emptyList())
            .map { row -> HomeRail(row.id, row.title, row.items.withoutAdult()) }
            .filter { it.items.isNotEmpty() }
        if (lastRowId != null) restoreToken++
    }

    LaunchedEffect(Unit) {
        delay(200)
        if (sideNavOwnsFocus || lastRowId != null) return@LaunchedEffect
        runCatching { firstChipRequester.requestFocus() }
    }

    val enabledLibraries = remember(state) {
        state.servers.filter { it.enabled }.flatMap { server -> server.libraries.filter { it.enabled }.map { server to it } }
    }
    val offline = remember(state) {
        state.servers.filter { it.enabled && it.reachability is MediaServerReachability.Offline }
    }
    val shelves = listOfNotNull(continueRow) + rows.orEmpty()

    androidx.compose.runtime.CompositionLocalProvider(com.streamdek.tv.nativeapp.ui.LocalHideMediaServerMark provides true) {
    Box(
        Modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // A faint wash of Plex's gold at the top: enough to say where the viewer is, not so
            // much that the page stops looking like the rest of StreamDek.
            .background(Brush.verticalGradient(0f to PlexGold.copy(alpha = 0.10f), 0.35f to Color.Transparent)),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().focusGroup(),
            contentPadding = PaddingValues(top = 30.dp, bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.Section),
        ) {
            item(key = "header") {
                PlexHeader(
                    serverSummary = when {
                        state.servers.count { it.enabled } > 1 -> state.servers.filter { it.enabled }.joinToString(" · ") { it.name }
                        else -> state.accountName.orEmpty()
                    },
                )
            }
            if (enabledLibraries.isNotEmpty()) {
                item(key = "libraries") {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).focusGroup().padding(horizontal = PageInset),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val multipleServers = state.servers.count { it.enabled } > 1
                        enabledLibraries.forEachIndexed { index, (server, library) ->
                            SearchChip(
                                label = if (multipleServers) "${library.title} · ${server.name}" else library.title,
                                selected = false,
                                modifier = if (index == 0) Modifier.focusRequester(firstChipRequester) else Modifier,
                                onClick = { onOpenLibrary(server.id, library.key, library.title) },
                            )
                        }
                    }
                }
            }
            if (offline.isNotEmpty()) {
                item(key = "offline") {
                    OfflineNotice(
                        names = offline.joinToString(", ") { it.name },
                        refused = offline.all { (it.reachability as? MediaServerReachability.Offline)?.reason == OfflineReason.Unauthorized },
                        onRetry = {
                            scope.launch {
                                repository.mediaServers.refresh(force = true)
                                reloadToken++
                            }
                        },
                    )
                }
            }
            when {
                rows == null -> item(key = "loading") {
                    Box(Modifier.padding(horizontal = PageInset)) { TvSkeletonGrid(columns = 6, rows = 2) }
                }
                shelves.isEmpty() -> item(key = "empty") {
                    TvEmptyState(
                        title = stringResource(if (offline.isNotEmpty()) R.string.plex_page_offline_title else R.string.plex_page_empty_title),
                        message = stringResource(if (offline.isNotEmpty()) R.string.plex_page_offline_note else R.string.plex_page_empty_note),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = { reloadToken++ },
                    )
                }
                else -> items(shelves, key = { it.id }) { rail ->
                    val rowState = rowStates.getOrPut(rail.id) { LazyListState() }
                    HomeShelf(
                        row = rail,
                        rowState = rowState,
                        compact = false,
                        portraitCards = rail.id != "continue-watching",
                        firstCardRequester = null,
                        focusItemKey = lastItemKey.takeIf { rail.id == lastRowId && restoreToken > 0 },
                        onFocusItemHandled = { restoreToken = 0 },
                        onItemFocused = { _, item ->
                            lastRowId = rail.id
                            lastItemKey = "${rail.id}:${com.streamdek.tv.nativeapp.ui.home.homeItemKey(item)}"
                        },
                        onItemPressed = { item ->
                            when {
                                item.isPlexCollection() -> onOpenCollection(item)
                                rail.id == "continue-watching" -> onResume(item)
                                else -> onOpenDetail(item.type, item.id)
                            }
                        },
                        onItemMenu = { item, requester -> if (!item.isPlexCollection()) menu = item to requester },
                        onOpenNavigation = onOpenNavigation,
                    )
                }
            }
        }

        menu?.let { (item, requester) ->
            BrowseItemActionMenu(
                repository = repository,
                item = item,
                showRemoveFromContinueWatching = continueRow?.items?.any { it.id == item.id } == true,
                onDismiss = {
                    menu = null
                    scope.launch {
                        delay(40)
                        runCatching { requester.requestFocus() }
                    }
                },
                onDismissAfterRemoval = { menu = null },
                onOpenDetail = { onOpenDetail(item.type, item.id) },
                onChanged = { reloadToken++ },
            )
        }
    }
    }
}

@Composable
private fun PlexHeader(serverSummary: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PageInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.plex_logo),
            contentDescription = null,
            modifier = Modifier.size(44.dp).clip(CircleShape),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.media_server_plex),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (serverSummary.isNotBlank()) {
                Text(
                    serverSummary,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun OfflineNotice(names: String, refused: Boolean, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PageInset)
            .background(Color(0x1FF59E0B), RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(9.dp).background(Color(0xFFF59E0B), CircleShape))
        Text(
            stringResource(if (refused) R.string.plex_page_server_refused else R.string.plex_page_server_offline, names),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
            modifier = Modifier.weight(1f),
        )
        SearchChip(label = stringResource(R.string.action_retry), selected = false, onClick = onRetry)
    }
}

/**
 * One library, or one collection, as a grid.
 *
 * Paged as it scrolls - a library of ten thousand films is read sixty at a time, starting the next
 * page when the highlight is within two rows of the end - so opening a large library is as quick
 * as opening a small one and memory follows what has been seen, not what exists.
 */
@Composable
fun PlexBrowseScreen(
    repository: StreamDekRepository,
    serverId: String,
    libraryKey: String?,
    collectionKey: String?,
    title: String,
    entryFocusRequester: FocusRequester? = null,
    onOpenDetail: (String, String) -> Unit,
    onOpenCollection: (MediaItem) -> Unit,
    onBack: () -> Unit,
) {
    val gridColumns = LocalTvExperienceSettings.current.gridColumns
    var sort by rememberSaveable { mutableStateOf(MediaServerSort.RecentlyAdded) }
    var items by remember(sort) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var nextStart by remember(sort) { mutableIntStateOf(0) }
    var end by remember(sort) { mutableStateOf(false) }
    var loading by remember(sort) { mutableStateOf(true) }
    var failed by remember(sort) { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val localEntry = remember { FocusRequester() }
    val firstChipRequester = entryFocusRequester ?: localEntry
    val firstCardRequester = remember { FocusRequester() }
    var menu by remember { mutableStateOf<Pair<MediaItem, FocusRequester>?>(null) }
    val collectionRef = collectionKey?.let { MediaServerReference(com.streamdek.tv.nativeapp.mediaserver.PLEX_PROVIDER_ID, serverId, it) }

    suspend fun loadMore() {
        if (end) return
        loading = true
        val page = runCatching {
            if (collectionRef != null) repository.mediaServerCollectionPage(collectionRef, nextStart, PAGE_SIZE)
            else repository.mediaServerLibraryPage(serverId, libraryKey.orEmpty(), nextStart, PAGE_SIZE, sort)
        }.getOrNull()
        if (page == null) {
            failed = items.isEmpty()
            end = true
        } else {
            items = (items + page.items.withoutAdult()).distinctBy { it.id }
            nextStart = page.nextStart
            end = page.end
        }
        loading = false
    }

    LaunchedEffect(sort) {
        loadMore()
        delay(120)
        runCatching { if (collectionRef == null) firstChipRequester.requestFocus() else firstCardRequester.requestFocus() }
    }
    // The next page is fetched before the viewer reaches the end, not when they hit it.
    LaunchedEffect(gridState, sort) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { last -> last >= items.size - gridColumns * 2 }
            .collect { if (!loading && !end) loadMore() }
    }

    androidx.compose.runtime.CompositionLocalProvider(com.streamdek.tv.nativeapp.ui.LocalHideMediaServerMark provides true) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = PageInset, end = PageInset, top = 30.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(painterResource(R.drawable.plex_logo), contentDescription = null, modifier = Modifier.size(30.dp).clip(CircleShape))
            Text(title, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black), color = MaterialTheme.colorScheme.onBackground)
        }
        if (collectionRef == null) {
            Row(
                Modifier.fillMaxWidth().focusGroup().padding(horizontal = PageInset, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    MediaServerSort.RecentlyAdded to R.string.plex_sort_recent,
                    MediaServerSort.Title to R.string.plex_sort_title,
                    MediaServerSort.ReleaseDate to R.string.plex_sort_release,
                ).forEachIndexed { index, (option, label) ->
                    SearchChip(
                        label = stringResource(label),
                        selected = sort == option,
                        modifier = if (index == 0) Modifier.focusRequester(firstChipRequester) else Modifier,
                        onClick = { if (sort != option) sort = option },
                    )
                }
            }
        } else {
            Box(Modifier.height(16.dp))
        }
        when {
            items.isEmpty() && loading -> Box(Modifier.padding(horizontal = PageInset)) { TvSkeletonGrid(columns = gridColumns, rows = 3) }
            items.isEmpty() -> TvEmptyState(
                title = stringResource(if (failed) R.string.plex_page_offline_title else R.string.plex_browse_empty),
                message = if (failed) stringResource(R.string.plex_page_offline_note) else null,
                actionLabel = stringResource(if (failed) R.string.action_retry else R.string.action_back),
                onAction = if (failed) {
                    { scope.launch { end = false; failed = false; loadMore() }; Unit }
                } else onBack,
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns),
                state = gridState,
                modifier = Modifier.fillMaxSize().focusGroup(),
                contentPadding = PaddingValues(start = PageInset, end = PageInset, top = 2.dp, bottom = 72.dp),
                horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                verticalArrangement = Arrangement.spacedBy(TvSpacing.Card),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    val requester = if (index == 0) firstCardRequester else remember(item.id) { FocusRequester() }
                    PremiumMediaCard(
                        item = item,
                        variant = TvMediaCardVariant.Poster,
                        modifier = Modifier
                            .focusRequester(requester)
                            .width(132.dp)
                            .height(198.dp)
                            .focusProperties { if (index < gridColumns && collectionRef == null) up = firstChipRequester }
                            .tvCardLongPress { if (!item.isPlexCollection()) menu = item to requester },
                        onClick = { if (item.isPlexCollection()) onOpenCollection(item) else onOpenDetail(item.type, item.id) },
                        onLongPress = { if (!item.isPlexCollection()) menu = item to requester },
                    )
                }
            }
        }
    }

    menu?.let { (item, requester) ->
        BrowseItemActionMenu(
            repository = repository,
            item = item,
            onDismiss = {
                menu = null
                scope.launch {
                    delay(40)
                    runCatching { requester.requestFocus() }
                }
            },
            onOpenDetail = { onOpenDetail(item.type, item.id) },
            onChanged = {},
        )
    }
    }
}

private const val PAGE_SIZE = 60
