package com.streamdek.tv.nativeapp.ui.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamdek.tv.nativeapp.data.LiveCatalogSection
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.PremiumMediaCard
import com.streamdek.tv.nativeapp.ui.TvChromeSurface
import com.streamdek.tv.nativeapp.ui.TvEmptyState
import com.streamdek.tv.nativeapp.ui.TvMediaCardVariant
import com.streamdek.tv.nativeapp.ui.TvMotion
import com.streamdek.tv.nativeapp.ui.TvSkeletonGrid
import com.streamdek.tv.nativeapp.ui.TvSpacing
import com.streamdek.tv.nativeapp.ui.tvCardLongPress

private val LiveSidebarWidth = 236.dp

/**
 * One selectable entry in the sidebar: a source, or a single category within one when categories
 * are on. [sourceTitle] is the owning source, shown only when the entry is a category, so two
 * add-ons both offering "Sport" stay tellable apart.
 */
private data class LiveBucket(
    val id: String,
    val title: String,
    val sourceTitle: String?,
    val addonId: String,
    val keys: Set<String>,
)

/** Identity used for favourites and for restoring focus. */
private fun liveKey(item: MediaItem): String = "${item.sourceAddonId}:${item.sourceCatalogId}:${item.id}"

/**
 * Live TV.
 *
 * Stays two-pane — sources on the left, channels on the right — because that is what a channel
 * browser is, and collapsing the source list into the content would cost a press every time the
 * viewer changed source. Three things did change:
 *
 *  - **The sidebar no longer erases itself.** It used to draw only while focused, so moving into
 *    the grid hid which source was selected and how many channels it held. With 800-odd channels
 *    across several sources, that context is the whole point of the pane.
 *  - **Channel count per row follows the density setting** instead of a hardcoded three, so the
 *    cards are a sane size rather than enormous.
 *  - **Focus restore no longer scans the full list per card.** The old key builder called
 *    `allItems.indexOf(item)` inside the item lambda — a linear scan per card, quadratic across
 *    the grid, on a box that can least afford it.
 */
@Composable
fun LiveScreen(
    sections: List<LiveCatalogSection>,
    isLoading: Boolean,
    /** What the load is doing, shown in place of a silent skeleton while nothing is on screen. */
    loadingStatus: String? = null,
    loadingProgress: Float? = null,
    compactMode: Boolean = false,
    /** Wide channel artwork, as synced from mobile. Off uses portrait cards. */
    landscapeCards: Boolean = true,
    /** List each source's categories in the sidebar. Off lists one entry per source. */
    categoriesEnabled: Boolean = true,
    entryFocusRequester: FocusRequester? = null,
    restoreFocusedItemKey: String? = null,
    restoreFocusToken: Int = 0,
    favouriteKeys: Set<String> = emptySet(),
    onItemFocused: (String) -> Unit = {},
    onToggleFavourite: (MediaItem) -> Unit = {},
    onViewAll: (String?, String?) -> Unit = { _, _ -> },
    onPlayLive: (MediaItem) -> Unit,
) {
    val localEntry = remember { FocusRequester() }
    val sidebarEntry = entryFocusRequester ?: localEntry
    val firstCardRequester = remember { FocusRequester() }
    val cardRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val gridState = rememberLazyGridState()
    val gridColumns = LocalTvExperienceSettings.current.gridColumns.coerceAtMost(5)

    var selectedSourceId by remember(sections) { mutableStateOf<String?>(null) }
    var favouritesOnly by remember { mutableStateOf(false) }
    // Opens as a list. A channel lineup runs to thousands of entries and names are what people scan
    // for, so artwork is the deliberate choice rather than the default. Session-local, as the
    // equivalent toggle is on mobile.
    var listView by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()

    val allItems = remember(sections) {
        sections.flatMap { it.rails }.flatMap { it.items }.distinctBy(::liveKey)
    }
    // What the sidebar lists below the fixed entries: one row per category when categories are on,
    // otherwise one per source. Ids are resolved once here — the old filter walked every rail of
    // the selected section for every item on screen.
    val buckets = remember(sections, categoriesEnabled) {
        sections.flatMap { section ->
            if (categoriesEnabled) {
                section.rails.map { rail ->
                    LiveBucket(
                        id = rail.id,
                        title = rail.title,
                        sourceTitle = section.title,
                        // Taken off an item rather than off the section id: sections built from a
                        // playlist are keyed differently from add-on ones, and this is the id the
                        // browse screen matches its items against.
                        addonId = rail.items.firstOrNull()?.sourceAddonId.orEmpty(),
                        keys = rail.items.mapTo(hashSetOf(), ::liveKey),
                    )
                }
            } else {
                listOf(
                    LiveBucket(
                        id = section.id,
                        title = section.title,
                        sourceTitle = null,
                        addonId = section.rails.firstOrNull()?.items?.firstOrNull()?.sourceAddonId.orEmpty(),
                        keys = section.rails.flatMap { rail -> rail.items.map(::liveKey) }.toHashSet(),
                    ),
                )
            }
        }.filter { it.keys.isNotEmpty() }
    }

    val visibleItems = remember(allItems, selectedSourceId, favouritesOnly, favouriteKeys, buckets) {
        val bucketKeys = selectedSourceId?.let { id -> buckets.firstOrNull { it.id == id }?.keys }
        allItems.filter { item ->
            val key = liveKey(item)
            (!favouritesOnly || key in favouriteKeys) && (bucketKeys == null || key in bucketKeys)
        }
    }

    val heading = when {
        favouritesOnly -> "Favourite channels"
        selectedSourceId != null -> buckets.firstOrNull { it.id == selectedSourceId }?.title ?: "Live TV"
        else -> "All live channels"
    }

    /**
     * Where "right" goes from the sidebar.
     *
     * [firstCardRequester] is only attached while the pane on the right is actually drawing
     * channels. Pointing at it while the skeleton or the empty state is up leaves focus search
     * resolving to a requester no node has claimed, which throws rather than doing nothing —
     * pressing right on a loading or empty Live TV page took the app down. Default hands the press
     * back to ordinary focus search, which finds nothing to the right and stays put.
     */
    val contentFocusTarget = if (visibleItems.isEmpty()) FocusRequester.Default else firstCardRequester

    LaunchedEffect(isLoading) {
        if (isLoading) return@LaunchedEffect
        kotlinx.coroutines.delay(160)
        runCatching { sidebarEntry.requestFocus() }
    }

    LaunchedEffect(restoreFocusToken, restoreFocusedItemKey, visibleItems.size, listView) {
        if (restoreFocusToken <= 0 || restoreFocusedItemKey.isNullOrBlank()) return@LaunchedEffect
        val index = visibleItems.indexOfFirst { liveKey(it) == restoreFocusedItemKey }
        if (index < 0) return@LaunchedEffect
        // Whichever list is actually on screen — scrolling the grid while the list view is up left
        // the channel being restored off screen with its requester never composed.
        if (listView) listState.scrollToItem(index) else gridState.scrollToItem(index)
        kotlinx.coroutines.delay(120)
        runCatching { cardRequesters[restoreFocusedItemKey]?.requestFocus() }
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // ── Sources ──────────────────────────────────────────────────────────────────────────
        LazyColumn(
            modifier = Modifier
                .width(LiveSidebarWidth)
                .fillMaxHeight()
                .background(TvChromeSurface)
                // The requester sits on the group, not on a row inside it. Rows here are lazy: with
                // a provider playlist the category list is long, and once "All channels" scrolled
                // out of view a requester attached to it was no longer attached to anything —
                // pressing left out of the grid then resolved to nothing and took the app down.
                // A focus group hands the request to whichever of its children is composed.
                .focusRequester(sidebarEntry)
                .focusGroup()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 30.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item("heading") {
                Text(
                    text = "Live TV",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 6.dp, bottom = 12.dp),
                )
            }
            item("all") {
                LiveSourceRow(
                    label = "All channels",
                    count = allItems.size,
                    selected = !favouritesOnly && selectedSourceId == null,
                    modifier = Modifier.focusProperties { right = contentFocusTarget },
                    onClick = { favouritesOnly = false; selectedSourceId = null },
                )
            }
            item("favourites") {
                LiveSourceRow(
                    label = "Favourites",
                    count = favouriteKeys.size,
                    selected = favouritesOnly,
                    modifier = Modifier.focusProperties { right = contentFocusTarget },
                    onClick = { favouritesOnly = true; selectedSourceId = null },
                )
            }
            // Above the categories rather than below them: with a provider playlist the list under
            // this runs to hundreds of entries, and a control past all of them may as well not exist.
            item("view") {
                LiveSourceRow(
                    label = if (listView) "List view" else "Card view",
                    caption = "Press OK to switch",
                    count = null,
                    selected = false,
                    modifier = Modifier.focusProperties { right = contentFocusTarget },
                    onClick = { listView = !listView },
                )
            }
            if (buckets.isNotEmpty()) {
                item("sources-label") {
                    Text(
                        text = if (categoriesEnabled) "CATEGORIES" else "SOURCES",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 4.dp),
                    )
                }
            }
            items(buckets, key = { it.id }) { bucket ->
                LiveSourceRow(
                    label = bucket.title,
                    caption = bucket.sourceTitle,
                    count = bucket.keys.size,
                    selected = !favouritesOnly && selectedSourceId == bucket.id,
                    modifier = Modifier.focusProperties { right = contentFocusTarget },
                    onClick = { favouritesOnly = false; selectedSourceId = bucket.id },
                )
            }
            item("browse") {
                LiveSourceRow(
                    label = "Browse all",
                    count = null,
                    selected = false,
                    modifier = Modifier.padding(top = 14.dp).focusProperties { right = contentFocusTarget },
                    onClick = {
                        // Carries whatever the sidebar has narrowed to. The selected entry is a
                        // category id when categories are on, so the source comes off the bucket
                        // rather than off the selection, which is only a source id when they are off.
                        val bucket = selectedSourceId?.let { id -> buckets.firstOrNull { it.id == id } }
                        onViewAll(
                            bucket?.addonId?.takeIf { it.isNotBlank() },
                            bucket?.id?.takeIf { categoriesEnabled },
                        )
                    },
                )
            }
        }

        // ── Channels ─────────────────────────────────────────────────────────────────────────
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = TvSpacing.ScreenHorizontal, end = TvSpacing.ScreenHorizontal, top = 30.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${visibleItems.size} · hold OK to favourite",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            when {
                isLoading && allItems.isEmpty() -> Column(Modifier.fillMaxWidth()) {
                    LiveLoadingStatus(status = loadingStatus, progress = loadingProgress)
                    TvSkeletonGrid(columns = gridColumns, rows = 3, portrait = false)
                }

                visibleItems.isEmpty() -> TvEmptyState(
                    title = if (favouritesOnly) "No favourite channels yet" else "No channels here",
                    message = if (favouritesOnly) {
                        "Hold OK on any channel to add it to your favourites."
                    } else {
                        "Pick another source from the list on the left."
                    },
                )

                listView -> LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().focusGroup(),
                    contentPadding = PaddingValues(
                        start = TvSpacing.ScreenHorizontal,
                        end = TvSpacing.ScreenHorizontal,
                        top = 16.dp,
                        bottom = 72.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(visibleItems, key = { _, item -> liveKey(item) }) { index, item ->
                        val key = liveKey(item)
                        val requester = cardRequesters.getOrPut(key) { FocusRequester() }
                        val effective = if (index == 0) firstCardRequester else requester
                        LiveChannelRow(
                            item = item,
                            favourite = key in favouriteKeys,
                            modifier = Modifier
                                .focusRequester(effective)
                                .focusProperties { left = sidebarEntry }
                                .tvCardLongPress { onToggleFavourite(item) },
                            onClick = { onPlayLive(item) },
                            onLongPress = { onToggleFavourite(item) },
                            onFocused = { onItemFocused(key) },
                        )
                    }
                }

                else -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // Card height comes off the measured card width so portrait channels are a true
                    // 2:3 rather than a guess that only holds at one column count.
                    val available = maxWidth - TvSpacing.ScreenHorizontal * 2 - TvSpacing.Card * (gridColumns - 1)
                    val cardWidth = available / gridColumns
                    val cardHeight = when {
                        !landscapeCards -> cardWidth * 3f / 2f
                        compactMode -> 108.dp
                        else -> 122.dp
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(gridColumns),
                        state = gridState,
                        modifier = Modifier.fillMaxSize().focusGroup(),
                        contentPadding = PaddingValues(
                            start = TvSpacing.ScreenHorizontal,
                            end = TvSpacing.ScreenHorizontal,
                            top = 16.dp,
                            bottom = 72.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                        verticalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                    ) {
                        itemsIndexed(visibleItems, key = { _, item -> liveKey(item) }) { index, item ->
                            val key = liveKey(item)
                            val requester = cardRequesters.getOrPut(key) { FocusRequester() }
                            val effective = if (index == 0) firstCardRequester else requester
                            PremiumMediaCard(
                                item = item,
                                variant = if (landscapeCards) TvMediaCardVariant.Live else TvMediaCardVariant.Poster,
                                favourite = key in favouriteKeys,
                                showProvider = true,
                                modifier = Modifier
                                    .focusRequester(effective)
                                    .height(cardHeight)
                                    .focusProperties { if (index % gridColumns == 0) left = sidebarEntry }
                                    .tvCardLongPress { onToggleFavourite(item) },
                                onClick = { onPlayLive(item) },
                                onLongPress = { onToggleFavourite(item) },
                                onFocused = { onItemFocused(key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * What the live load is doing, above the skeleton.
 *
 * A provider playlist is tens of megabytes and hundreds of thousands of entries, so the wait is
 * long enough that a skeleton alone reads as a hang. The bar only appears when the size of the
 * download was known — the rest of the time the channel count moving is the honest signal, and a
 * made-up percentage would be worse than none.
 */
@Composable
private fun LiveLoadingStatus(status: String?, progress: Float?) {
    if (status.isNullOrBlank()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = TvSpacing.ScreenHorizontal, end = TvSpacing.ScreenHorizontal, top = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (progress != null) {
            Box(
                Modifier
                    .fillMaxWidth(0.42f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.12f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/**
 * One channel as a text row.
 *
 * The list view exists because a channel list is long: names are what a viewer scans for, and a
 * row per channel puts far more of them on screen than artwork does. The logo stays as a small
 * leading thumbnail, since it is what makes a channel recognisable at a glance.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveChannelRow(
    item: MediaItem,
    favourite: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onFocused: () -> Unit,
) {
    val highContrast = LocalTvExperienceSettings.current.highContrast
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.04f),
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
            pressedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                BorderStroke(if (highContrast) 3.dp else 2.dp, MaterialTheme.colorScheme.primary),
                shape = AppCardShape,
            ),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = 1f),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = item.poster ?: item.backdrop,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(width = 56.dp, height = 34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.06f)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.sourceCatalogName?.takeIf { it.isNotBlank() }?.let { catalog ->
                    Text(
                        text = catalog,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (favourite) {
                Text(
                    text = "★",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** One entry in the source list. Count sits on the right so the column scans as a table. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveSourceRow(
    label: String,
    count: Int?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    caption: String? = null,
    onClick: () -> Unit,
) {
    val highContrast = LocalTvExperienceSettings.current.highContrast
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(if (caption == null) 44.dp else 52.dp),
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            } else {
                Color.Transparent
            },
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
            pressedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                BorderStroke(if (highContrast) 3.dp else 2.dp, MaterialTheme.colorScheme.primary),
                shape = AppCardShape,
            ),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = TvMotion.focusScale()),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                caption?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            count?.let {
                Text(
                    text = it.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.52f),
                    maxLines = 1,
                )
            }
        }
    }
}
