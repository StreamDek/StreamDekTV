package com.streamdek.tv.nativeapp.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import com.streamdek.tv.nativeapp.ui.animateToAnchoredItem
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamdek.tv.nativeapp.data.CastMember
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.SeasonEpisode
import com.streamdek.tv.nativeapp.data.SeasonRef
import com.streamdek.tv.nativeapp.data.TraktCommentItem
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.PremiumMediaCard
import com.streamdek.tv.nativeapp.ui.ProgressMeter
import com.streamdek.tv.nativeapp.ui.TvMediaCardVariant
import com.streamdek.tv.nativeapp.ui.TvMotion
import com.streamdek.tv.nativeapp.ui.TvSkeletonBox
import com.streamdek.tv.nativeapp.ui.TvSpacing
import com.streamdek.tv.nativeapp.ui.tvCardLongPress
import kotlin.math.roundToInt

/** Shared page inset. Matches the rest of the app rather than the bespoke value this replaced. */
internal val DetailInset = TvSpacing.ScreenHorizontal

/**
 * How far a band shrinks once the viewer has moved past it.
 *
 * Every row below the hero collapses to this fraction of its full size, and expands again when it
 * takes focus. With four bands competing for the space under the hero, only the one being used is
 * worth full height; the rest just need to stay identifiable as somewhere to go back to.
 */
internal const val DetailCompactScale = 0.4f

/**
 * A single GPU transform per band. Card geometry stays fixed, so focus movement no longer causes
 * every lazy item to be measured on every animation frame.
 */
@Composable
internal fun detailBandScale(compact: Boolean): Float {
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (compact) DetailCompactScale else 1f,
        // The same length the hero collapses over. These two are one movement — the hero giving up
        // its height and the bands taking it — and running them at 170 and 280 made the second half
        // of the travel happen with the first already finished, which is what read as a stutter.
        animationSpec = TvMotion.standardSpec(TvMotion.Expand),
        label = "detail-band-scale",
    )
    return scale
}

/**
 * The gap between a band's header and its row.
 *
 * Six device-independent pixels, but they used to jump in a single frame while the scale beside
 * them eased, and the header visibly ticked as the row moved. Eased on the same curve it is part of
 * the same movement instead.
 */
@Composable
internal fun detailBandSpacing(compact: Boolean): Dp {
    val spacing by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (compact) 8.dp else 14.dp,
        animationSpec = TvMotion.standardSpec(TvMotion.Expand),
        label = "detail-band-spacing",
    )
    return spacing
}

internal fun Modifier.detailBandScale(scale: Float, compact: Boolean): Modifier =
    layout { measurable, constraints ->
        // A scaled LazyRow otherwise composes only one unscaled viewport, which becomes 40% of the
        // screen and looks clipped on the right. Compact rows get one wider measurement up front;
        // the animation itself remains a single GPU layer with no per-card transforms.
        val contentWidth = if (compact && constraints.hasBoundedWidth) {
            (constraints.maxWidth / DetailCompactScale).roundToInt()
        } else {
            constraints.maxWidth
        }
        val childConstraints = if (constraints.hasBoundedWidth) {
            constraints.copy(minWidth = contentWidth, maxWidth = contentWidth)
        } else {
            constraints
        }
        val placeable = measurable.measure(childConstraints)
        val layoutWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
        layout(layoutWidth, (placeable.height * scale).roundToInt()) {
            placeable.placeRelativeWithLayer(0, 0) {
                scaleX = scale
                scaleY = scale
                val insetFraction = if (placeable.width > 0) {
                    (DetailInset.toPx() / placeable.width).coerceIn(0f, 1f)
                } else {
                    0f
                }
                transformOrigin = TransformOrigin(insetFraction, 0f)
            }
        }
    }

/**
 * One focusable surface used by every card on this screen, so focus reads identically everywhere:
 * a ring in the accent colour and a restrained lift. The old screen gave each section its own
 * treatment, which made focus position harder to track at a glance across a room.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DetailFocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = AppCardShape,
    description: String? = null,
    onFocused: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val highContrast = LocalTvExperienceSettings.current.highContrast
    Card(
        onClick = onClick,
        modifier = modifier
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            pressedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                BorderStroke(if (highContrast) 3.dp else 2.dp, MaterialTheme.colorScheme.primary),
                shape = shape,
            ),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = TvMotion.focusScale()),
        content = { content() },
    )
}


/**
 * Focus behaviour for one row.
 *
 * Entering a row always lands on its first card, and the row then scrolls so the focused card
 * stays pinned at the leading edge as the viewer travels along it. Compose's own behaviour is
 * "nearest child in the direction of travel", which lands wherever the row happened to be scrolled
 * to — so moving down the page arrived at a different horizontal position in every row.
 */
internal class RowFocusMemory(initialIndex: Int = 0) {
    /** Index the row is currently on, used to drive the anchored scroll. */
    var focusedIndex by mutableIntStateOf(initialIndex)
        private set
    private val requesters = mutableMapOf<Int, FocusRequester>()

    fun requester(index: Int): FocusRequester = requesters.getOrPut(index) { FocusRequester() }

    fun remember(index: Int) { focusedIndex = index }

}

private val RowFocusMemorySaver = Saver<RowFocusMemory, Int>(
    save = { it.focusedIndex },
    restore = { RowFocusMemory(it) },
)

@Composable
internal fun rememberRowFocus(key: Any?): RowFocusMemory =
    rememberSaveable(key, saver = RowFocusMemorySaver) { RowFocusMemory() }

/**
 * Vertical entry uses the first card until the viewer deliberately moves within this row. The
 * remembered requester then becomes the entry point when they return from another section.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.rowFocusEntry(memory: RowFocusMemory): Modifier =
    focusGroup().focusProperties { enter = { memory.requester(memory.focusedIndex) } }

/**
 * Keeps the focused card at the leading edge, so travelling right scrolls the row under a fixed
 * highlight rather than moving the highlight across a static row.
 */
@Composable
internal fun AnchorRowToFocus(state: LazyListState, memory: RowFocusMemory, itemCount: Int) {
    LaunchedEffect(memory.focusedIndex, itemCount) {
        state.animateToAnchoredItem(
            focusedIndex = memory.focusedIndex,
            itemCount = itemCount,
            leadingItems = 0,
        )
    }
}

internal fun Modifier.rowFocusItem(memory: RowFocusMemory, index: Int): Modifier =
    this.focusRequester(memory.requester(index))
        .onFocusChanged { if (it.isFocused) memory.remember(index) }

/** Section header shared by every band below the hero. */
@Composable
internal fun DetailSectionHeader(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = DetailInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
        )
        trailing?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
            )
        }
    }
}

/** Small capsule used for year, runtime, rating and genre. */
@Composable
internal fun MetaChip(label: String, emphasised: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(AppPillShape)
            .background(
                if (emphasised) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                else Color.White.copy(alpha = 0.09f),
            )
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = if (emphasised) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.86f),
        )
    }
}

/**
 * Poster beside the copy. The screen this replaced showed only a backdrop, which left nothing for
 * the eye to anchor on and made every title look alike; the poster is the artwork viewers actually
 * recognise. Purely decorative, so it is not focusable and never costs a D-pad press.
 */
internal val HeroPosterWidth = 188.dp
internal val HeroPosterHeight = 282.dp

@Composable
internal fun HeroPoster(detail: MediaDetail, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(HeroPosterWidth)
            .height(HeroPosterHeight)
            .clip(AppCardShape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        val art = detail.poster ?: detail.backdrop
        if (!art.isNullOrBlank()) {
            AsyncImage(
                model = art,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** Episode still with its number, name and watched tick. */
@Composable
internal fun EpisodeCard(
    compact: Boolean = false,
    cardWidth: Dp,
    stillHeight: Dp,
    episode: SeasonEpisode,
    seasonNumber: Int,
    watched: Boolean,
    released: Boolean,
    /** Set once the row spans more than one season, so a card says which season it belongs to. */
    showSeason: Boolean = false,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    DetailFocusCard(
        onClick = { if (released) onClick() },
        modifier = modifier.width(cardWidth).tvCardLongPress(onLongPress),
        onFocused = onFocused,
        description = buildString {
            append("Season $seasonNumber episode ${episode.episodeNumber}, ${episode.name}")
            if (watched) append(", watched")
            if (!released) append(", not released yet")
        },
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(stillHeight).background(Color.Black.copy(alpha = 0.5f))) {
                if (!episode.still.isNullOrBlank()) {
                    AsyncImage(
                        model = episode.still,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(0f to Color.Transparent, 1f to Color(0xCC000000)),
                        ),
                    ),
                )
                Row(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MetaChip(
                        if (showSeason) "S${seasonNumber}E${episode.episodeNumber}"
                        else "E${episode.episodeNumber}",
                    )
                    if (!compact && watched) MetaChip("WATCHED", emphasised = true)
                    if (!compact && !released) MetaChip("SOON")
                }
                if (!compact) episode.runtime?.takeIf { it > 0 }?.let {
                    Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { MetaChip(formatRuntime(it)) }
                }
            }
            if (!compact) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = episode.name,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = episode.overview?.takeIf { it.isNotBlank() } ?: "No synopsis available.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Season selector. Chips rather than a dropdown: one press per season, no menu to open.
 *
 * Its own focus group, and — importantly — not inside the band's. The band used to declare a single
 * entry point that redirected any incoming focus straight to an episode card, which meant these
 * chips could be seen but never landed on: the D-pad went round them every time.
 */
@Composable
internal fun SeasonChipRow(
    seasons: List<SeasonRef>,
    selected: Int,
    firstChipRequester: FocusRequester?,
    /** Attached to the row itself, so the band above can send focus here without naming a chip. */
    rowRequester: FocusRequester? = null,
    /** Where up out of the chips goes, so the whole page is reachable in a few presses. */
    upRequester: FocusRequester? = null,
    onSelect: (Int) -> Unit,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // The selected season changes on its own as the viewer scrolls the episode row past a season
    // boundary, so the chip for it has to be brought into view rather than assumed to be on screen.
    LaunchedEffect(selected, seasons.size) {
        val index = seasons.indexOfFirst { it.seasonNumber == selected }
        if (index >= 0) {
            listState.animateToAnchoredItem(
                focusedIndex = index,
                itemCount = seasons.size,
                leadingItems = 1,
            )
        }
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .then(rowRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .focusGroup()
            // Named rather than left to spatial search: a chip that happens to sit under a gutter
            // in the row above otherwise finds nothing at all.
            .focusProperties { if (upRequester != null) up = upRequester },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DetailInset),
    ) {
        itemsIndexed(seasons, key = { _, season -> season.seasonNumber }) { index, season ->
            val active = season.seasonNumber == selected
            DetailFocusCard(
                onClick = { onSelect(season.seasonNumber) },
                shape = AppPillShape,
                modifier = Modifier.then(
                    if (index == 0 && firstChipRequester != null) Modifier.focusRequester(firstChipRequester) else Modifier,
                ),
                description = season.name,
            ) {
                Text(
                    text = season.name,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Marking the season off, on a row of its own above the season chips.
 *
 * It used to be the first chip in that row, which put it behind however many seasons the viewer
 * had travelled past — on a long-running series that meant scrolling all the way back to the left
 * to reach it, on the one screen where the whole point is not having to. Up out of the chips now
 * lands here, so it is always one press away from wherever the season list has got to.
 */
@Composable
private fun MarkSeasonWatchedRow(
    seasonWatched: Boolean,
    markingSeason: Boolean,
    requester: FocusRequester,
    /** The hero above — where up out of this row goes, as it used to from the chips. */
    upRequester: FocusRequester?,
    onMarkSeasonWatched: () -> Unit,
) {
    val label = when {
        seasonWatched -> "Season watched"
        markingSeason -> "Marking season..."
        else -> "Mark season watched"
    }
    Row(modifier = Modifier.padding(horizontal = DetailInset)) {
        DetailFocusCard(
            onClick = { if (!seasonWatched && !markingSeason) onMarkSeasonWatched() },
            shape = AppPillShape,
            modifier = Modifier
                .focusRequester(requester)
                .focusProperties { if (upRequester != null) up = upRequester },
            description = label,
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = if (seasonWatched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

/**
 * Episodes for the season the viewer is on — and the seasons after it.
 *
 * [entries] is a single continuous run of episodes rather than one season's worth: the screen
 * appends the next season as the end of the row comes into reach, so travelling right off the last
 * episode of a season carries straight on into the next one instead of stopping at a wall. The
 * chips follow the row rather than driving it, so [activeSeasonNumber] is whichever season the
 * focused card belongs to.
 */
@Composable
internal fun EpisodesBand(
    compact: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    seasons: List<SeasonRef>,
    activeSeasonNumber: Int,
    entries: List<SeasonEpisodeEntry>,
    /** Identity of the run of episodes. Focus memory resets when this changes, not on every append. */
    rowFocusKey: Any?,
    loadingNextSeason: Boolean,
    isEpisodeWatched: (SeasonEpisodeEntry) -> Boolean,
    seasonWatched: Boolean,
    markingSeason: Boolean,
    firstChipRequester: FocusRequester?,
    /** The hero's play button — where up out of this band leads. */
    upRequester: FocusRequester?,
    onSelectSeason: (Int) -> Unit,
    onMarkSeasonWatched: () -> Unit,
    onEpisodeFocused: (Int, SeasonEpisodeEntry) -> Unit,
    onEpisodePressed: (SeasonEpisodeEntry) -> Unit,
    onEpisodeMenu: (SeasonEpisodeEntry) -> Unit,
) {
    val scale = detailBandScale(compact)
    val cardWidth = 268.dp
    val stillHeight = 150.dp
    val rowFocus = rememberRowFocus(rowFocusKey)
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val chipRowRequester = remember { FocusRequester() }
    val markSeasonRequester = remember { FocusRequester() }
    val spansSeasons = remember(entries) { entries.distinctBy { it.seasonNumber }.size > 1 }
    AnchorRowToFocus(rowState, rowFocus, entries.size)
    Column(
        modifier = Modifier.onFocusChanged { onFocusChanged(it.hasFocus) },
        verticalArrangement = Arrangement.spacedBy(detailBandSpacing(compact)),
    ) {
        DetailSectionHeader(
            title = "Episodes",
            trailing = entries.count { it.seasonNumber == activeSeasonNumber }
                .takeIf { it > 0 }
                ?.let { "$it episodes" },
        )
        if (!compact) {
            MarkSeasonWatchedRow(
                seasonWatched = seasonWatched,
                markingSeason = markingSeason,
                requester = markSeasonRequester,
                upRequester = upRequester,
                onMarkSeasonWatched = onMarkSeasonWatched,
            )
            SeasonChipRow(
                seasons = seasons,
                selected = activeSeasonNumber,
                firstChipRequester = firstChipRequester,
                rowRequester = chipRowRequester,
                upRequester = markSeasonRequester,
                onSelect = onSelectSeason,
            )
        }
        if (entries.isEmpty()) {
            // The season list arrives before its episodes do; a skeleton here stops the band
            // collapsing to nothing and then shoving the sections below it back down.
            Row(
                horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                modifier = Modifier.padding(horizontal = DetailInset).detailBandScale(scale, compact),
            ) {
                repeat(4) { TvSkeletonBox(Modifier.width(cardWidth).height(stillHeight + 82.dp)) }
            }
        } else {
            LazyRow(
                // The entry point lives here rather than on the band, so focus arriving from above
                // lands on the season chips first and only reaches the episodes on the way down.
                //
                // Up is named too. A card is a tall tile and the chips are a thin strip above it,
                // and leaving that to spatial search is what left the viewer stuck down here with
                // no way back to the hero.
                modifier = Modifier
                    .rowFocusEntry(rowFocus)
                    .focusProperties {
                        // Only while the chips are actually on screen — pointing at a requester
                        // nothing is attached to cancels the move outright, which would be the very
                        // dead end this exists to prevent.
                        if (!compact) up = chipRowRequester
                    }
                    .detailBandScale(scale, compact),
                state = rowState,
                horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DetailInset),
            ) {
                itemsIndexed(
                    entries,
                    key = { _, entry -> "s${entry.seasonNumber}:${entry.episode.id}" },
                ) { index, entry ->
                    EpisodeCard(
                        modifier = Modifier.rowFocusItem(rowFocus, index),
                        compact = compact,
                        cardWidth = cardWidth,
                        stillHeight = stillHeight,
                        episode = entry.episode,
                        seasonNumber = entry.seasonNumber,
                        watched = isEpisodeWatched(entry),
                        released = isEpisodeReleased(entry.episode.airDate),
                        showSeason = spansSeasons,
                        onFocused = { onEpisodeFocused(index, entry) },
                        onClick = { onEpisodePressed(entry) },
                        onLongPress = { onEpisodeMenu(entry) },
                    )
                }
                if (loadingNextSeason) {
                    // Deliberately not focusable: it holds the place of the season being fetched
                    // without ever becoming somewhere the D-pad can get stuck.
                    item("next-season") {
                        TvSkeletonBox(Modifier.width(cardWidth).height(stillHeight + 82.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun SimilarBand(
    items: List<MediaItem>,
    compact: Boolean = false,
    onFocusChanged: (Boolean) -> Unit = {},
    onOpen: (MediaItem) -> Unit,
) {
    val scale = detailBandScale(compact)
    val cardWidth = 106.dp
    val cardHeight = 159.dp
    val rowFocus = rememberRowFocus(items)
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    AnchorRowToFocus(rowState, rowFocus, items.size)
    Column(
        modifier = Modifier.rowFocusEntry(rowFocus).onFocusChanged { onFocusChanged(it.hasFocus) },
        verticalArrangement = Arrangement.spacedBy(detailBandSpacing(compact)),
    ) {
        DetailSectionHeader("More Like This")
        LazyRow(
            modifier = Modifier.detailBandScale(scale, compact),
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DetailInset),
        ) {
            itemsIndexed(items, key = { _, item -> "${item.type}:${item.id}" }) { index, item ->
                // The same card Home and Library use, so a title looks identical wherever it turns
                // up. The old screen had a bespoke card here for no reason.
                PremiumMediaCard(
                    item = item,
                    variant = TvMediaCardVariant.Poster,
                    showLabels = false,
                    metaOnTop = !compact,
                    modifier = Modifier.rowFocusItem(rowFocus, index).width(cardWidth).height(cardHeight),
                    onClick = { onOpen(item) },
                )
            }
        }
    }
}

@Composable
internal fun CastBand(cast: List<CastMember>, compact: Boolean = false, onFocusChanged: (Boolean) -> Unit = {}) {
    // Cast shrinks once the viewer has moved past it. Nobody is reading character names at that
    // point, and the space buys the row below enough height to sit fully on screen.
    // 15% down on the full-size portrait: the row was heavier than the section warranted next to
    // the recommendations below it. The collapsed size follows from this automatically.
    val scale = detailBandScale(compact)
    val photoSize = 82.dp
    val columnWidth = 99.dp
    val rowFocus = rememberRowFocus(cast)
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    AnchorRowToFocus(rowState, rowFocus, cast.size)
    Column(
        modifier = Modifier.rowFocusEntry(rowFocus).onFocusChanged { onFocusChanged(it.hasFocus) },
        verticalArrangement = Arrangement.spacedBy(detailBandSpacing(compact)),
    ) {
        DetailSectionHeader("Cast")
        LazyRow(
            modifier = Modifier.detailBandScale(scale, compact),
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
            contentPadding = PaddingValues(horizontal = DetailInset),
        ) {
            itemsIndexed(cast, key = { _, member -> member.id }) { index, member ->
                CastPortrait(
                    modifier = Modifier.rowFocusItem(rowFocus, index),
                    member = member,
                    photoSize = photoSize,
                    columnWidth = columnWidth,
                    compact = compact,
                )
            }
        }
    }
}

/**
 * One cast member.
 *
 * The portrait itself is the focus target — no card behind it. Wrapping the column in a circular
 * surface put a ring around the name as well as the face and clipped the whole thing at the
 * collapsed size; the picture is the thing being pointed at, so it is the thing that highlights.
 */
@Composable
private fun CastPortrait(
    modifier: Modifier = Modifier,
    member: CastMember,
    photoSize: Dp,
    columnWidth: Dp,
    compact: Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    val highContrast = LocalTvExperienceSettings.current.highContrast
    val ring by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (focused) (if (highContrast) 3.dp else 2.dp) else 0.dp,
        animationSpec = TvMotion.instantSpec(),
        label = "cast-ring",
    )

    Column(
        modifier = Modifier.width(columnWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(photoSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .then(
                    // Applied only while focused: a 0.dp stroke still rasterises a faint ring.
                    if (ring > 0.dp) Modifier.border(ring, MaterialTheme.colorScheme.primary, CircleShape) else Modifier,
                )
                .semantics { contentDescription = listOfNotNull(member.name, member.character).joinToString(", ") }
                .then(modifier)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .clickable { },
        ) {
            if (!member.photo.isNullOrBlank()) {
                AsyncImage(
                    model = member.photo,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alpha = if (focused) 1f else 0.86f,
                )
            }
        }
        if (!compact) {
            Text(
                text = member.name,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            member.character?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.58f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun CommentsBand(comments: List<TraktCommentItem>, compact: Boolean = false, onFocusChanged: (Boolean) -> Unit = {}) {
    val scale = detailBandScale(compact)
    val cardWidth = 340.dp
    val cardHeight = 150.dp
    val rowFocus = rememberRowFocus(comments)
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    AnchorRowToFocus(rowState, rowFocus, comments.size)
    Column(
        modifier = Modifier.rowFocusEntry(rowFocus).onFocusChanged { onFocusChanged(it.hasFocus) },
        verticalArrangement = Arrangement.spacedBy(detailBandSpacing(compact)),
    ) {
        DetailSectionHeader("Reviews", trailing = "${comments.size}")
        LazyRow(
            modifier = Modifier.detailBandScale(scale, compact),
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DetailInset),
        ) {
            itemsIndexed(comments, key = { _, comment -> comment.id }) { index, comment ->
                // Focusable like every other row, so the D-pad can reach the reviews instead of
                // stopping dead at the recommendations above them.
                // Fixed size regardless of content. A row of boxes that each stop at a different
                // height reads as broken layout, and on a remote the varying hit targets make the
                // row harder to travel along than the reviews are worth.
                DetailFocusCard(
                    onClick = { },
                    modifier = Modifier.rowFocusItem(rowFocus, index).width(cardWidth).height(cardHeight),
                    description = "Review by ${comment.author}",
                ) {
                Column(
                    modifier = Modifier
                        .width(cardWidth)
                        .height(cardHeight)
                        .clip(AppCardShape)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = comment.author,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        comment.userRating?.let { MetaChip("★ $it", emphasised = true) }
                    }
                    Text(
                        // Spoilers stay collapsed: there is no hover on a remote, so a reveal
                        // control would be one more thing to focus past on every review.
                        text = if (comment.spoiler) "Hidden — this review is marked as a spoiler." else comment.comment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (comment.spoiler) 0.48f else 0.76f,
                        ),
                        maxLines = if (compact) 2 else 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                }
            }
        }
    }
}

/** Loading state shaped like the finished screen, so nothing jumps when the data lands. */
@Composable
internal fun DetailSkeleton() {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 96.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = DetailInset),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            TvSkeletonBox(Modifier.width(188.dp).height(282.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TvSkeletonBox(Modifier.width(420.dp).height(44.dp))
                TvSkeletonBox(Modifier.width(300.dp).height(20.dp))
                TvSkeletonBox(Modifier.width(560.dp).height(16.dp))
                TvSkeletonBox(Modifier.width(520.dp).height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvSkeletonBox(Modifier.width(168.dp).height(48.dp), AppPillShape)
                    repeat(3) { TvSkeletonBox(Modifier.size(48.dp), AppPillShape) }
                }
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = DetailInset),
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
        ) {
            repeat(5) { TvSkeletonBox(Modifier.width(136.dp).height(204.dp)) }
        }
    }
}

/** Progress under the play button, with the position read out beside it. */
@Composable
internal fun ResumeProgress(progressFraction: Float, label: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ProgressMeter(
            progress = (progressFraction * 100.0),
            modifier = Modifier.width(280.dp).height(4.dp),
        )
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.66f),
            )
        }
    }
}
