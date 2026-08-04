package com.streamdek.tv.nativeapp.ui.live

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamdek.tv.nativeapp.data.LiveCatalogSection
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.tvCardLongPress
import kotlinx.coroutines.delay

private fun liveBrowseKey(item: MediaItem): String = "${item.sourceAddonId}:${item.sourceCatalogId}:${item.id}"

@Composable
fun LiveBrowseScreen(
    sections: List<LiveCatalogSection>,
    initialAddonId: String? = null,
    initialCatalogId: String? = null,
    favouriteKeys: Set<String>,
    onToggleFavourite: (MediaItem) -> Unit,
    onPlayLive: (MediaItem) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val allItems = remember(sections) {
        sections.flatMap { it.rails }.flatMap { it.items }.distinctBy(::liveBrowseKey)
    }
    val addons = remember(sections) {
        sections.mapNotNull { section ->
            val item = section.rails.asSequence().flatMap { it.items.asSequence() }.firstOrNull()
            item?.sourceAddonId?.let { it to (item.sourceAddonName ?: section.title) }
        }.distinctBy { it.first }
    }
    var selectedAddonId by remember(initialAddonId, sections) {
        mutableStateOf(initialAddonId?.takeIf { wanted -> addons.any { it.first == wanted } })
    }
    val catalogs = remember(allItems, selectedAddonId) {
        allItems.asSequence()
            .filter { selectedAddonId == null || it.sourceAddonId == selectedAddonId }
            .mapNotNull { item -> item.sourceCatalogId?.let { it to (item.sourceCatalogName ?: "Live TV") } }
            .distinctBy { it.first }
            .toList()
    }
    var selectedCatalogId by remember(initialCatalogId, selectedAddonId) {
        mutableStateOf(initialCatalogId?.takeIf { wanted -> catalogs.any { it.first == wanted } })
    }
    var query by remember { mutableStateOf("") }
    var favouritesOnly by remember { mutableStateOf(false) }
    var searchEditing by remember { mutableStateOf(false) }
    val searchRequester = remember { FocusRequester() }
    val firstCardRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val normalizedQuery = query.trim()
    val filteredItems = remember(allItems, selectedAddonId, selectedCatalogId, normalizedQuery, favouritesOnly, favouriteKeys) {
        allItems.filter { item ->
            (!favouritesOnly || liveBrowseKey(item) in favouriteKeys) &&
                (selectedAddonId == null || item.sourceAddonId == selectedAddonId) &&
                (selectedCatalogId == null || item.sourceCatalogId == selectedCatalogId) &&
                (normalizedQuery.isBlank() || sequenceOf(item.title, item.description.orEmpty(), item.sourceCatalogName.orEmpty())
                    .any { it.contains(normalizedQuery, ignoreCase = true) })
        }
    }

    LaunchedEffect(Unit) {
        delay(160)
        runCatching { searchRequester.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .width(218.dp)
                .fillMaxSize()
                .background(Color.Transparent)
                .padding(start = 20.dp, end = 12.dp, top = 72.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("LIVE TV", color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black))
            Text("Find a channel", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { androidx.compose.material3.Text("Search") },
                singleLine = true,
                readOnly = !searchEditing,
                shape = RoundedCornerShape(16.dp),
                keyboardActions = KeyboardActions(onDone = { searchEditing = false; focusManager.clearFocus(force = true) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF171B23), unfocusedContainerColor = Color(0xFF11141B),
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary, unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth().height(52.dp).focusRequester(searchRequester)
                    .focusProperties { if (filteredItems.isNotEmpty()) right = firstCardRequester }
                    .onPreviewKeyEvent { event ->
                        if (!searchEditing && event.type == KeyEventType.KeyUp && (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                            searchEditing = true; true
                        } else false
                    },
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
                item { Text("QUICK FILTERS", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)) }
                item { FilterPill(if (favouritesOnly) "Favourites only" else "Show favourites", favouritesOnly, Modifier.fillMaxWidth()) { favouritesOnly = !favouritesOnly } }
                item { Text("SOURCES", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)) }
                items(listOf(null to "All sources") + addons, key = { "source:" + (it.first ?: "all") }) { option ->
                    FilterPill(option.second, option.first == selectedAddonId, Modifier.fillMaxWidth()) { selectedAddonId = option.first; selectedCatalogId = null }
                }
                item { Text("COLLECTIONS", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 6.dp)) }
                items(listOf(null to "All channels") + catalogs, key = { "catalog:" + (it.first ?: "all") }) { option ->
                    FilterPill(option.second, option.first == selectedCatalogId, Modifier.fillMaxWidth()) { selectedCatalogId = option.first }
                }
            }
        }
        Column(modifier = Modifier.padding(start = 250.dp, top = 88.dp)) {
            Text(if (favouritesOnly) "Favourite Channels" else "All Live Channels", style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black))
            Text("${filteredItems.size} channels  •  Hold OK to add or remove a favourite", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f))
        }

        if (filteredItems.isEmpty()) {
            Text(
                "No live channels match these filters.",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.fillMaxSize().padding(start = 218.dp, top = 158.dp),
                contentPadding = PaddingValues(start = 32.dp, end = 48.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(filteredItems, key = ::liveBrowseKey) { item ->
                    val favourite = liveBrowseKey(item) in favouriteKeys
                    LiveBrowseCard(
                        item = item,
                        favourite = favourite,
                        modifier = if (item == filteredItems.first()) Modifier.focusRequester(firstCardRequester) else Modifier,
                        onClick = { onPlayLive(item) },
                        onLongPress = { onToggleFavourite(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveFilterRail(
    label: String,
    options: List<Pair<String?, String>>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(label.uppercase(), color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
        options.take(5).forEach { option ->
            FilterPill(option.second, option.first == selectedId, Modifier.fillMaxWidth()) { onSelect(option.first) }
        }
    }
}
@Composable
private fun LiveFilterRow(
    label: String,
    options: List<Pair<String?, String>>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.width(76.dp), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
        LazyRow(modifier = Modifier.fillMaxWidth().focusGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(options, key = { it.first ?: "all:$label" }) { option ->
                FilterPill(option.second, option.first == selectedId) { onSelect(option.first) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilterPill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        colors = CardDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
        ),
        scale = CardDefaults.scale(focusedScale = 1.04f),
    ) {
        Box(Modifier.padding(horizontal = 15.dp), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (selected) FontWeight.Black else FontWeight.Medium), maxLines = 1)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveBrowseCard(
    item: MediaItem,
    favourite: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(250.dp).tvCardLongPress(onLongPress),
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(containerColor = Color(0xFF181A1F), focusedContainerColor = Color(0xFF20242C)),
        border = CardDefaults.border(focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape = AppCardShape)),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = 1.025f),
    ) {
        Box(Modifier.fillMaxSize().clip(AppCardShape)) {
            AsyncImage(item.poster ?: item.backdrop, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000)))))
            if (favourite) {
                Icon(Icons.Filled.Star, "Favourite channel", tint = Color(0xFFFACC15), modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(24.dp))
            } else {
                Icon(Icons.Outlined.StarOutline, "Hold OK to favourite", tint = Color.White.copy(alpha = 0.62f), modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(22.dp))
            }
            Column(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.sourceAddonName ?: "Live", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                Text(item.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}