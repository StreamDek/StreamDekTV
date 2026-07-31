package com.streamdek.tv.nativeapp.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
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
    val localInitialCardRequester = remember { FocusRequester() }
    val initialCardRequester = entryFocusRequester ?: localInitialCardRequester
    val hasItems = sections.any { section -> section.rails.any { rail -> rail.items.isNotEmpty() } }
    val horizontalListStates = remember { mutableMapOf<String, LazyListState>() }
    val rowFocusIndices = remember { mutableStateMapOf<String, Int>() }
    val verticalListState = rememberLazyListState()
    val railColumnIndices = remember(sections) {
        buildMap {
            var columnIndex = 0
            sections.forEach { section ->
                columnIndex += 1 // section heading
                section.rails.forEach { rail ->
                    put(rail.id, columnIndex)
                    columnIndex += 1
                }
            }
        }
    }
    var initialFocusApplied by remember { mutableStateOf(false) }

    LaunchedEffect(isLoading, hasItems, restoreFocusToken) {
        if (!isLoading && hasItems && restoreFocusToken == 0 && !initialFocusApplied) {
            initialFocusApplied = true
            kotlinx.coroutines.delay(180)
            runCatching { initialCardRequester.requestFocus() }
        }
    }

    LaunchedEffect(restoreFocusToken, restoreFocusedItemKey, sections) {
        if (restoreFocusToken <= 0 || restoreFocusedItemKey.isNullOrBlank()) return@LaunchedEffect
        val rail = sections.asSequence().flatMap { it.rails.asSequence() }.firstOrNull { candidate ->
            candidate.items.withIndex().any { (index, item) -> liveItemStableKey(item, index) == restoreFocusedItemKey }
        } ?: return@LaunchedEffect
        railColumnIndices[rail.id]?.let { verticalListState.scrollToItem(it) }
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
                text = "Live",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Browse live channels by source and catalog.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            )
        }

        when {
            isLoading && !hasItems -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(48.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "Loading live channels",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
            !hasItems -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(48.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "No live channels are available right now.",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "Enable a live TV addon with populated catalogs to show this page.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            else -> {
                LazyColumn(
                    state = verticalListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = if (compactMode) 148.dp else 168.dp),
                    contentPadding = PaddingValues(bottom = 180.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    sections.forEachIndexed { sectionIndex, section ->
                        if (section.rails.isEmpty()) return@forEachIndexed
                        item("section:${section.id}") {
                            Text(
                                text = section.title,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 48.dp, end = 48.dp),
                            )
                        }
                        section.rails.forEachIndexed { railIndex, rail ->
                            item("rail:${rail.id}") {
                                val rowState = horizontalListStates.getOrPut(rail.id) {
                                    LazyListState(firstVisibleItemIndex = rowFocusIndices[rail.id] ?: 0)
                                }
                                LiveRailRow(
                                    railId = rail.id,
                                    title = rail.title,
                                    items = rail.items,
                                    rowState = rowState,
                                    anchoredIndex = rowFocusIndices[rail.id] ?: 0,
                                    initialFocusRequester = if (sectionIndex == 0 && railIndex == 0 && restoreFocusToken == 0) initialCardRequester else null,
                                    restoreFocusedItemKey = restoreFocusedItemKey,
                                    restoreFocusToken = restoreFocusToken,
                                    favouriteKeys = favouriteKeys,
                                    onItemFocused = { index, key ->
                                        rowFocusIndices[rail.id] = index
                                        onItemFocused(key)
                                    },
                                    onToggleFavourite = onToggleFavourite,
                                    onViewAll = { onViewAll(section.id.removePrefix("live:"), rail.id) },
                                    onPlayLive = onPlayLive,
                                )
                            }
                        }
                    }
                }
            }
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
            .size(width = 260.dp, height = 146.dp)
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


