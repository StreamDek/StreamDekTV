package com.streamdek.tv.nativeapp.ui.live

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamdek.tv.nativeapp.data.LiveCatalogSection
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.PremiumMediaCard
import com.streamdek.tv.nativeapp.ui.TvEmptyState
import com.streamdek.tv.nativeapp.ui.TvMediaCardVariant
import com.streamdek.tv.nativeapp.ui.TvSpacing
import com.streamdek.tv.nativeapp.ui.search.SearchChip
import com.streamdek.tv.nativeapp.ui.search.SearchFilterOption
import com.streamdek.tv.nativeapp.ui.search.SearchFilterTray
import com.streamdek.tv.nativeapp.ui.search.SearchQueryDisplay
import com.streamdek.tv.nativeapp.ui.tvCardLongPress
import kotlinx.coroutines.delay

private enum class OpenTray { None, Source, Catalogue }

private val BrowseInset = TvSpacing.ScreenHorizontal

private fun liveBrowseKey(item: MediaItem): String = "${item.sourceAddonId}:${item.sourceCatalogId}:${item.id}"

/**
 * Search across every live channel.
 *
 * Rebuilt to match the rest of the app: one vertical surface, controls that stay on screen, and
 * filters that expand in place. The screen it replaces had the same collapsing left column as the
 * old Search and Live — it drew only while focused, so moving into the results hid the query, the
 * selected source and the channel count all at once, on a screen whose entire purpose is narrowing
 * eight hundred channels down to one.
 */
@Composable
fun LiveBrowseScreen(
    sections: List<LiveCatalogSection>,
    initialAddonId: String? = null,
    initialCatalogId: String? = null,
    favouriteKeys: Set<String>,
    /**
     * Where the shell sends focus when this screen is the one on display.
     *
     * The navigation rail is on this page now, and the rail hands focus back by name. Without a
     * target of its own the page could be entered but not returned to, so pressing right out of the
     * menu fell through to whatever spatial search happened to find.
     */
    entryFocusRequester: FocusRequester? = null,
    onToggleFavourite: (MediaItem) -> Unit,
    onPlayLive: (MediaItem) -> Unit,
    onBack: () -> Unit,
) {
    val gridColumns = LocalTvExperienceSettings.current.gridColumns.coerceAtMost(5)

    val allItems = remember(sections) {
        sections.flatMap { it.rails }.flatMap { it.items }.distinctBy(::liveBrowseKey)
    }
    val addons = remember(sections) {
        allItems.mapNotNull { item ->
            item.sourceAddonId?.let { it to (item.sourceAddonName ?: "Source") }
        }.distinctBy { it.first }
    }
    var selectedAddonId by remember(initialAddonId, sections) {
        mutableStateOf(initialAddonId?.takeIf { wanted -> addons.any { it.first == wanted } })
    }
    val catalogues = remember(allItems, selectedAddonId) {
        allItems.asSequence()
            .filter { selectedAddonId == null || it.sourceAddonId == selectedAddonId }
            .mapNotNull { item -> item.sourceCatalogId?.let { it to (item.sourceCatalogName ?: "Live TV") } }
            .distinctBy { it.first }
            .toList()
    }
    var selectedCatalogId by remember(initialCatalogId, selectedAddonId) {
        mutableStateOf(initialCatalogId?.takeIf { wanted -> catalogues.any { it.first == wanted } })
    }

    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var queryFocused by remember { mutableStateOf(false) }
    var favouritesOnly by remember { mutableStateOf(false) }
    var openTray by remember { mutableStateOf(OpenTray.None) }

    val localQueryRequester = remember { FocusRequester() }
    // The search field is what this page focuses itself, so it is also where the rail returns to.
    val queryRequester = entryFocusRequester ?: localQueryRequester
    val firstChipRequester = remember { FocusRequester() }
    val firstCardRequester = remember { FocusRequester() }
    val trayRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()

    val normalizedQuery = query.trim()
    val filteredItems = remember(
        allItems, selectedAddonId, selectedCatalogId, normalizedQuery, favouritesOnly, favouriteKeys,
    ) {
        allItems.filter { item ->
            (!favouritesOnly || liveBrowseKey(item) in favouriteKeys) &&
                (selectedAddonId == null || item.sourceAddonId == selectedAddonId) &&
                (selectedCatalogId == null || item.sourceCatalogId == selectedCatalogId) &&
                (
                    normalizedQuery.isBlank() ||
                        sequenceOf(item.title, item.description.orEmpty(), item.sourceCatalogName.orEmpty())
                            .any { it.contains(normalizedQuery, ignoreCase = true) }
                    )
        }
    }

    // A search or filter empties this grid as often as not, and [firstCardRequester] is only
    // attached while there are cards. Pointing "down" at it regardless left focus search resolving
    // to a requester no node had claimed, which throws rather than doing nothing — the same crash
    // the Live page's sidebar had. Default falls back to ordinary focus search.
    val gridFocusTarget = if (filteredItems.isEmpty()) FocusRequester.Default else firstCardRequester

    LaunchedEffect(Unit) {
        delay(160)
        runCatching { queryRequester.requestFocus() }
    }

    LaunchedEffect(openTray) {
        if (openTray == OpenTray.None) return@LaunchedEffect
        delay(60)
        runCatching { trayRequester.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = BrowseInset, end = BrowseInset, top = 30.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SearchQueryDisplay(
                    query = query,
                    editing = editing,
                    focused = queryFocused,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${filteredItems.size} of ${allItems.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    modifier = Modifier.padding(bottom = 6.dp),
                    maxLines = 1,
                )
            }

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
                    .padding(horizontal = BrowseInset)
                    .fillMaxWidth(0.46f)
                    .height(52.dp)
                    .focusRequester(queryRequester)
                    .focusProperties { down = firstChipRequester }
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .focusGroup()
                    .padding(horizontal = BrowseInset, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchChip(
                    label = addons.firstOrNull { it.first == selectedAddonId }?.second ?: "All sources",
                    selected = openTray == OpenTray.Source,
                    leading = "Source",
                    modifier = Modifier.focusRequester(firstChipRequester)
                        .focusProperties { up = queryRequester; down = gridFocusTarget },
                    onClick = { openTray = if (openTray == OpenTray.Source) OpenTray.None else OpenTray.Source },
                )
                SearchChip(
                    label = catalogues.firstOrNull { it.first == selectedCatalogId }?.second ?: "All collections",
                    selected = openTray == OpenTray.Catalogue,
                    leading = "Collection",
                    modifier = Modifier.focusProperties { up = queryRequester; down = gridFocusTarget },
                    onClick = {
                        if (catalogues.isNotEmpty()) {
                            openTray = if (openTray == OpenTray.Catalogue) OpenTray.None else OpenTray.Catalogue
                        }
                    },
                )
                SearchChip(
                    label = if (favouritesOnly) "Favourites only" else "All channels",
                    selected = favouritesOnly,
                    leading = favouriteKeys.size.toString(),
                    modifier = Modifier.focusProperties { up = queryRequester; down = gridFocusTarget },
                    onClick = { favouritesOnly = !favouritesOnly },
                )
            }

            if (openTray != OpenTray.None) {
                SearchFilterTray(
                    firstOptionRequester = trayRequester,
                    onDismiss = { openTray = OpenTray.None },
                    options = when (openTray) {
                        OpenTray.Source -> buildList {
                            add(SearchFilterOption("All sources", selectedAddonId == null) {
                                selectedAddonId = null
                                selectedCatalogId = null
                            })
                            addons.forEach { (id, name) ->
                                add(SearchFilterOption(name, selectedAddonId == id) {
                                    selectedAddonId = id
                                    selectedCatalogId = null
                                })
                            }
                        }
                        OpenTray.Catalogue -> buildList {
                            add(SearchFilterOption("All collections", selectedCatalogId == null) { selectedCatalogId = null })
                            catalogues.forEach { (id, name) ->
                                add(SearchFilterOption(name, selectedCatalogId == id) { selectedCatalogId = id })
                            }
                        }
                        OpenTray.None -> emptyList()
                    },
                )
            }

            if (filteredItems.isEmpty()) {
                TvEmptyState(
                    title = if (favouritesOnly) "No favourite channels" else "No channels match",
                    message = if (favouritesOnly) {
                        "Hold OK on any channel to add it to your favourites."
                    } else {
                        "Try a shorter search, or widen the source and collection."
                    },
                    actionLabel = "Clear filters",
                    onAction = {
                        query = ""
                        selectedAddonId = null
                        selectedCatalogId = null
                        favouritesOnly = false
                    },
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    state = gridState,
                    modifier = Modifier.weight(1f).fillMaxWidth().focusGroup(),
                    contentPadding = PaddingValues(
                        start = BrowseInset, end = BrowseInset, top = 4.dp, bottom = 72.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                    verticalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                ) {
                    itemsIndexed(filteredItems, key = { _, item -> liveBrowseKey(item) }) { index, item ->
                        val key = liveBrowseKey(item)
                        PremiumMediaCard(
                            item = item,
                            variant = TvMediaCardVariant.Live,
                            favourite = key in favouriteKeys,
                            showProvider = true,
                            modifier = Modifier
                                .then(if (index == 0) Modifier.focusRequester(firstCardRequester) else Modifier)
                                .height(122.dp)
                                .focusProperties { if (index < gridColumns) up = firstChipRequester }
                                .tvCardLongPress { onToggleFavourite(item) },
                            onClick = { onPlayLive(item) },
                            onLongPress = { onToggleFavourite(item) },
                        )
                    }
                }
            }
        }
    }
}
