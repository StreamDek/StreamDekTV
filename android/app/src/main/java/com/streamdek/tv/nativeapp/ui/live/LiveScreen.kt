package com.streamdek.tv.nativeapp.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamdek.tv.nativeapp.data.LiveCatalogSection
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.tvCardLongPress

private fun liveItemStableKey(item: MediaItem, index: Int): String {
    return "${item.type}:${item.id}:${item.streamType.orEmpty()}:$index"
}

@Composable
fun LiveScreen(
    sections: List<LiveCatalogSection>,
    isLoading: Boolean,
    compactMode: Boolean = false,
    entryFocusRequester: FocusRequester? = null,
    restoreFocusedItemKey: String? = null,
    restoreFocusToken: Int = 0,
    favouriteKeys: Set<String> = emptySet(),
    onItemFocused: (String) -> Unit = {},
    onToggleFavourite: (MediaItem) -> Unit = {},
    onViewAll: (String?, String?) -> Unit = { _, _ -> },
    onPlayLive: (MediaItem) -> Unit,
) {
    val localEntryRequester = remember { FocusRequester() }
    val sidebarEntryRequester = entryFocusRequester ?: localEntryRequester
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val cardRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val allItems = remember(sections) {
        sections.flatMap { it.rails }.flatMap { it.items }
            .distinctBy { "${it.sourceAddonId}:${it.sourceCatalogId}:${it.id}" }
    }
    var selectedSourceId by remember(sections) { mutableStateOf<String?>(null) }
    var favouritesOnly by remember { mutableStateOf(false) }
    val visibleItems = remember(allItems, selectedSourceId, favouritesOnly, favouriteKeys) {
        allItems.filter { item ->
            (!favouritesOnly || "${item.sourceAddonId}:${item.sourceCatalogId}:${item.id}" in favouriteKeys) &&
                (selectedSourceId == null || sections.firstOrNull { it.id == selectedSourceId }?.rails?.any { rail ->
                    rail.items.any { candidate -> candidate.id == item.id && candidate.sourceAddonId == item.sourceAddonId }
                } == true)
        }
    }
    val selectedTitle = when {
        favouritesOnly -> "Favourite channels"
        selectedSourceId != null -> sections.firstOrNull { it.id == selectedSourceId }?.title ?: "Live TV"
        else -> "All live channels"
    }

    LaunchedEffect(isLoading) {
        if (!isLoading) {
            kotlinx.coroutines.delay(160)
            runCatching { sidebarEntryRequester.requestFocus() }
        }
    }
    LaunchedEffect(restoreFocusToken, restoreFocusedItemKey, visibleItems) {
        if (restoreFocusToken <= 0 || restoreFocusedItemKey.isNullOrBlank()) return@LaunchedEffect
        val index = visibleItems.indexOfFirst { item ->
            val sourceIndex = allItems.indexOf(item).coerceAtLeast(0)
            liveItemStableKey(item, sourceIndex) == restoreFocusedItemKey
        }
        if (index >= 0) {
            gridState.scrollToItem(index)
            kotlinx.coroutines.delay(120)
            runCatching { cardRequesters[restoreFocusedItemKey]?.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .width(if (compactMode) 204.dp else 218.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 12.dp, top = 72.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("LIVE TV", color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black), modifier = Modifier.padding(bottom = 4.dp))
            LiveSidebarButton(
                label = "All channels",
                supporting = allItems.size.toString(),
                selected = !favouritesOnly && selectedSourceId == null,
                onClick = { favouritesOnly = false; selectedSourceId = null },
                modifier = Modifier.focusRequester(sidebarEntryRequester),
            )
            LiveSidebarButton(
                label = "Favourites",
                supporting = favouriteKeys.size.toString(),
                selected = favouritesOnly,
                onClick = { favouritesOnly = true; selectedSourceId = null },
            )
            Text("SOURCES", color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(start = 10.dp, top = 5.dp, bottom = 2.dp))
            sections.forEach { section ->
                val count = section.rails.sumOf { it.items.size }
                LiveSidebarButton(
                    label = section.title,
                    supporting = count.toString(),
                    selected = !favouritesOnly && selectedSourceId == section.id,
                    onClick = { favouritesOnly = false; selectedSourceId = section.id },
                )
            }
            LiveSidebarButton(
                label = "More filters",
                supporting = "Search and collections",
                onClick = { onViewAll(selectedSourceId?.removePrefix("live:"), null) },
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(start = if (compactMode) 234.dp else 250.dp, top = 88.dp, end = 44.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(selectedTitle, color = Color.White, style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black))
            Text(
                "${visibleItems.size} channels  •  Hold OK to add or remove a favourite",
                color = Color.White.copy(alpha = 0.70f),
                style = MaterialTheme.typography.bodyLarge,
            )
            when {
                isLoading && allItems.isEmpty() -> LivePageState("Loading live channels…")
                visibleItems.isEmpty() -> LivePageState(if (favouritesOnly) "No favourite channels yet." else "No channels match this source.")
                else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(5),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().focusGroup(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 140.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    gridItemsIndexed(
                        items = visibleItems,
                        key = { _, item -> "${item.sourceAddonId}:${item.sourceCatalogId}:${item.id}" },
                    ) { _, item ->
                        val sourceIndex = allItems.indexOf(item).coerceAtLeast(0)
                        val key = liveItemStableKey(item, sourceIndex)
                        val requester = cardRequesters.getOrPut(key) { FocusRequester() }
                        LiveChannelCard(
                            item = item,
                            favourite = "${item.sourceAddonId}:${item.sourceCatalogId}:${item.id}" in favouriteKeys,
                            modifier = Modifier.focusRequester(requester),
                            onFocused = { onItemFocused(key) },
                            onLongPress = { onToggleFavourite(item) },
                            onPressed = { onPlayLive(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LivePageState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = Color.White.copy(alpha = 0.76f), style = MaterialTheme.typography.titleLarge)
    }
}
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveSidebarButton(label: String, supporting: String, selected: Boolean = false, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(40.dp),
        shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
        colors = CardDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent, focusedContainerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else Color(0xFF202630)),
        border = CardDefaults.border(focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp))),
        scale = CardDefaults.scale(focusedScale = 1.035f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (selected) FontWeight.Black else FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(supporting, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.58f), maxLines = 1)
        }
    }
}

@Composable
private fun LiveRailRow(
    railId: String,
    title: String,
    items: List<MediaItem>,
    rowState: LazyListState,
    anchoredIndex: Int,
    initialFocusRequester: FocusRequester? = null,
    restoreFocusedItemKey: String? = null,
    restoreFocusToken: Int = 0,
    favouriteKeys: Set<String>,
    onItemFocused: (Int, String) -> Unit,
    onToggleFavourite: (MediaItem) -> Unit,
    onViewAll: () -> Unit,
    onPlayLive: (MediaItem) -> Unit,
) {
    val requesters = remember(railId) { mutableMapOf<String, FocusRequester>() }
    var appliedRestoreToken by remember(railId) { mutableIntStateOf(0) }

    LaunchedEffect(restoreFocusToken, restoreFocusedItemKey, items) {
        if (restoreFocusToken <= 0 || restoreFocusedItemKey.isNullOrBlank() || appliedRestoreToken == restoreFocusToken) {
            return@LaunchedEffect
        }
        val restoreIndex = items.indexOfFirst { item ->
            liveItemStableKey(item, items.indexOf(item)) == restoreFocusedItemKey
        }
        if (restoreIndex >= 0) {
            appliedRestoreToken = restoreFocusToken
            rowState.scrollToItem(restoreIndex)
            kotlinx.coroutines.delay(180)
            val key = liveItemStableKey(items[restoreIndex], restoreIndex)
            runCatching { requesters[key]?.requestFocus() }
        }
    }

    LaunchedEffect(anchoredIndex, items.size) {
        if (items.isNotEmpty()) {
            rowState.scrollToItem(anchoredIndex.coerceIn(0, items.lastIndex))
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            OutlinedButton(onClick = onViewAll) { Text("View All") }
        }
        LazyRow(
            state = rowState,
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(items, key = { index, item -> liveItemStableKey(item, index) }) { index, item ->
                val key = liveItemStableKey(item, index)
                val requester = requesters.getOrPut(key) { FocusRequester() }
                val effectiveRequester = if (initialFocusRequester != null && index == 0) initialFocusRequester else requester
                LiveChannelCard(
                    item = item,
                    favourite = "${item.sourceAddonId}:${item.sourceCatalogId}:${item.id}" in favouriteKeys,
                    modifier = Modifier.focusRequester(effectiveRequester),
                    onFocused = { onItemFocused(index, key) },
                    onLongPress = { onToggleFavourite(item) },
                    onPressed = { onPlayLive(item) },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveChannelCard(
    item: MediaItem,
    favourite: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onLongPress: () -> Unit,
    onPressed: () -> Unit,
) {
    Card(
        onClick = onPressed,
        modifier = modifier
            .fillMaxWidth()
            .height(250.dp)
            .tvCardLongPress(onLongPress)
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
                            colors = listOf(Color.Transparent, Color(0x22000000), Color(0xE0000000)),
                        ),
                    ),
            )
            if (favourite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Favourite channel",
                    tint = Color(0xFFFACC15),
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(22.dp),
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.streamType?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } ?: "Live",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        text = description,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.74f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}


