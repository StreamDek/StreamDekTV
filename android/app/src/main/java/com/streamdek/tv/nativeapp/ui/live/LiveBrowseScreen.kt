package com.streamdek.tv.nativeapp.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.zIndex
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
import com.streamdek.tv.nativeapp.ui.PremiumMediaCard
import com.streamdek.tv.nativeapp.ui.TvMediaCardVariant
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
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
    var controlsHaveFocus by remember { mutableStateOf(true) }
    val controlRailWidth by animateDpAsState(if (controlsHaveFocus) 218.dp else 40.dp, label = "live-browse-control-rail")
    val contentStart by animateDpAsState(if (controlsHaveFocus) 250.dp else 22.dp, label = "live-browse-content-start")
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
                .width(controlRailWidth)
                .fillMaxSize()
                .clipToBounds()
                .background(Color(0xF207090D)).drawWithContent { if (controlsHaveFocus) drawContent() }
                .zIndex(3f)
                .onFocusChanged { controlsHaveFocus = it.hasFocus }
                .padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 24.dp),
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
                item { Text("QUICK FILTERS", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)) }
                item { FilterPill(if (favouritesOnly) "Favourites only" else "Show favourites", favouritesOnly, Modifier.fillMaxWidth()) { favouritesOnly = !favouritesOnly } }
                item { Text("SOURCES", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)) }
                items(listOf(null to "All sources") + addons, key = { "source:" + (it.first ?: "all") }) { option ->
                    FilterPill(option.second, option.first == selectedAddonId, Modifier.fillMaxWidth()) { selectedAddonId = option.first; selectedCatalogId = null }
                }
                item { Text("COLLECTIONS", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 6.dp)) }
                items(listOf(null to "All channels") + catalogs, key = { "catalog:" + (it.first ?: "all") }) { option ->
                    FilterPill(option.second, option.first == selectedCatalogId, Modifier.fillMaxWidth()) { selectedCatalogId = option.first }
                }
            }
        }
        Column(modifier = Modifier.padding(start = contentStart, top = 18.dp)) {
            Text(if (favouritesOnly) "Favourite Channels" else "All Live Channels", color = Color.White, style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black))
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
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(start = contentStart, top = 88.dp),
                contentPadding = PaddingValues(end = 92.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(filteredItems, key = { _, item -> liveBrowseKey(item) }) { index, item ->
                    val favourite = liveBrowseKey(item) in favouriteKeys
                    LiveBrowseCard(
                        item = item,
                        favourite = favourite,
                        modifier = (if (index == 0) Modifier.focusRequester(firstCardRequester) else Modifier).focusProperties { if (index % 3 == 0) left = searchRequester },
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
        Text(label.uppercase(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
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
    PremiumMediaCard(
        item = item,
        variant = TvMediaCardVariant.Live,
        favourite = favourite,
        modifier = modifier.fillMaxWidth().height(250.dp),
        onClick = onClick,
        onLongPress = onLongPress,
    )
}