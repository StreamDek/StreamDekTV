package com.streamdek.tv.nativeapp.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.data.CastMember
import com.streamdek.tv.nativeapp.data.EpisodeRange
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
import com.streamdek.tv.nativeapp.ui.animateToAnchoredItem
import com.streamdek.tv.nativeapp.ui.tvCardLongPress
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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

    /**
     * Indices whose card is in composition right now, and whose requester therefore has a node
     * behind it.
     *
     * A LazyRow composes a window, not a list, so the remembered index routinely names a card that
     * has been scrolled out — and handing that requester to a focus search is not a quiet no-op.
     * `FocusRequester.focus` throws IllegalStateException("FocusRequester is not initialized")
     * straight out of the key dispatch, which takes the app down. Everything below exists so that
     * this class can only ever name a card that is actually there.
     */
    private val attached = mutableSetOf<Int>()

    fun requester(index: Int): FocusRequester = requesters.getOrPut(index) { FocusRequester() }

    fun remember(index: Int) { focusedIndex = index }

    fun attach(index: Int) { attached += index }

    fun detach(index: Int) { attached -= index }

    /**
     * Where focus should land when it enters this row: the remembered card when it is on screen,
     * the nearest one that is on screen otherwise.
     *
     * Null when the row has composed nothing at all — a season still showing its skeleton, a band
     * collapsed to compact. Null means "let ordinary focus search decide", which is the honest
     * answer; aiming at a card that does not exist is what crashed.
     */
    fun entryRequester(): FocusRequester? = when {
        focusedIndex in attached -> requester(focusedIndex)
        else -> attached.minByOrNull { abs(it - focusedIndex) }?.let(::requester)
    }
}

private val RowFocusMemorySaver = Saver<RowFocusMemory, Int>(
    save = { it.focusedIndex },
    restore = { RowFocusMemory(it) },
)

/**
 * [initialIndex] is only consulted when [key] changes, which is exactly when a row's contents have
 * been replaced - a different block of a long season, say - and the position it should open on is
 * therefore something the caller knows and the memory cannot.
 */
@Composable
internal fun rememberRowFocus(key: Any?, initialIndex: Int = 0): RowFocusMemory =
    rememberSaveable(key, saver = RowFocusMemorySaver) { RowFocusMemory(initialIndex) }

/**
 * Vertical entry uses the first card until the viewer deliberately moves within this row. The
 * remembered requester then becomes the entry point when they return from another section.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.rowFocusEntry(memory: RowFocusMemory): Modifier =
    focusGroup().focusProperties { enter = { memory.entryRequester() ?: FocusRequester.Default } }

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

@Composable
internal fun Modifier.rowFocusItem(memory: RowFocusMemory, index: Int): Modifier {
    // Registering here rather than leaving the memory to guess is the whole of the fix: the row is
    // the only thing that knows which of its cards are currently composed, and it knows it exactly.
    DisposableEffect(memory, index) {
        memory.attach(index)
        onDispose { memory.detach(index) }
    }
    return this.focusRequester(memory.requester(index))
        .onFocusChanged { if (it.isFocused) memory.remember(index) }
}

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
    /** The first episode not yet watched. Only marked in a season long enough to lose your place in. */
    nextUp: Boolean = false,
    /** How far in the viewer got, when they stopped part way. Null otherwise. */
    progress: Float? = null,
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
            if (nextUp) append(", next up")
            progress?.let { append(", ${(it * 100).toInt()} percent watched") }
            if (!released) append(", not released yet")
        },
    ) {
        Box(Modifier.fillMaxWidth().height(stillHeight).background(Color.Black.copy(alpha = 0.5f))) {
                if (!episode.still.isNullOrBlank()) {
                    AsyncImage(
                        model = episode.still,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Row(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MetaChip(
                        if (showSeason) "S${seasonNumber}E${episode.episodeNumber}"
                        else "E${episode.episodeNumber}",
                    )
                    if (!compact && watched) MetaChip("WATCHED", emphasised = true)
                    if (!compact && nextUp && !watched) MetaChip("NEXT UP", emphasised = true)
                    if (!compact && !released) MetaChip("SOON")
                }
                if (!compact) episode.runtime?.takeIf { it > 0 }?.let {
                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { MetaChip(formatRuntime(it)) }
                }
                if (!compact) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // Copy occupies only the lower part of the still. The upper artwork remains
                        // unobscured, while removing the old synopsis block below shortens the row.
                        .fillMaxWidth()
                        .height(stillHeight * 0.48f)
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(0f to Color.Transparent, 0.32f to Color(0xB3000000), 1f to Color(0xF0000000)),
                            ),
                        )
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.Bottom,
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
            progress?.takeIf { !compact }?.let { fraction ->
                // Along the bottom edge of the still rather than inside the copy block: it belongs
                // to the episode as a whole, and putting it in the column would shift the synopsis
                // about depending on whether somebody had started watching.
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.55f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                            .background(MaterialTheme.colorScheme.primary),
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
    watchedSeasonNumbers: Set<Int> = emptySet(),
    firstChipRequester: FocusRequester?,
    /** Attached to the row itself, so the band above can send focus here without naming a chip. */
    rowRequester: FocusRequester? = null,
    /**
     * The row's focus memory, when the caller needs to name the chip focus is actually sitting on.
     * Hoisted rather than always private because a row below has to be able to aim at a chip that
     * is certainly composed, and only the memory knows which one that is.
     */
    rowFocus: RowFocusMemory? = null,
    /** Where up out of the chips goes, so the whole page is reachable in a few presses. */
    upRequester: FocusRequester? = null,
    /** Where down out of the chips goes. Null while there is nothing below to land on. */
    downRequester: FocusRequester? = null,
    onSelect: (Int) -> Unit,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val selectedIndex = seasons.indexOfFirst { it.seasonNumber == selected }.coerceAtLeast(0)
    // Keyed on the length of the list, so a series whose seasons arrive late rebuilds the memory
    // rather than holding an index that no longer points at anything.
    val ownRowFocus = rememberRowFocus(key = "seasons:${seasons.size}", initialIndex = selectedIndex)
    val rowFocus = rowFocus ?: ownRowFocus

    // The selected season changes on its own as the viewer scrolls the episode row past a season
    // boundary, so the chip for it has to be brought into view rather than assumed to be on screen.
    LaunchedEffect(selected, seasons.size) {
        if (selectedIndex in seasons.indices) rowFocus.remember(selectedIndex)
    }
    // ...and the row follows the highlight as it travels, which is what was missing: a season list
    // wider than the screen could be looked at but never walked along.
    LaunchedEffect(rowFocus.focusedIndex, seasons.size) {
        listState.animateToAnchoredItem(
            focusedIndex = rowFocus.focusedIndex,
            itemCount = seasons.size,
            leadingItems = 1,
        )
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .then(rowRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            // Names the chip focus should land on when it arrives from anywhere, rather than
            // leaving Compose to pick whichever one happens to be directly above or below the
            // thing focus came from. That guess is what made "up" work from one spot in the
            // episode row and nowhere else.
            .rowFocusEntry(rowFocus)
            // Named rather than left to spatial search: a chip that happens to sit under a gutter
            // in the row above otherwise finds nothing at all.
            .focusProperties {
                if (upRequester != null) up = upRequester
                if (downRequester != null) down = downRequester
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DetailInset),
    ) {
        itemsIndexed(seasons, key = { _, season -> season.seasonNumber }) { index, season ->
            val active = season.seasonNumber == selected
            val watched = season.seasonNumber in watchedSeasonNumbers
            DetailFocusCard(
                onClick = { onSelect(season.seasonNumber) },
                shape = AppPillShape,
                modifier = Modifier
                    .then(
                        if (index == 0 && firstChipRequester != null) Modifier.focusRequester(firstChipRequester) else Modifier,
                    )
                    .rowFocusItem(rowFocus, index),
                description = if (watched) "${season.name}, watched" else season.name,
            ) {
                Text(
                    text = if (watched) "${season.name}  ✓" else season.name,
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
        markingSeason -> "Updating season..."
        seasonWatched -> "Mark Season as Unwatched"
        else -> "Mark Season as Watched"
    }
    Row(modifier = Modifier.padding(horizontal = DetailInset)) {
        DetailFocusCard(
            onClick = { if (!markingSeason) onMarkSeasonWatched() },
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
 * Blocks of twenty, for a season too long to travel with a d-pad.
 *
 * Deliberately the same control as [SeasonChipRow], down to the focus wiring: a viewer who has
 * learned that the strip of chips above the cards picks the season should not have to learn
 * anything new to discover that the next strip down picks the part of it. Up and down are named
 * rather than left to spatial search, for the same reason they are named there - a chip sitting
 * under a gutter in the row above otherwise finds nothing and the press is simply swallowed.
 *
 * The selected block is scrolled into view rather than assumed to be on screen, because it moves on
 * its own: opening a season lands on whichever block holds the episode the viewer is up to.
 */
@Composable
internal fun EpisodeRangeChipRow(
    ranges: List<EpisodeRange>,
    selectedIndex: Int,
    rowRequester: FocusRequester? = null,
    /**
     * The row above, as its focus memory rather than as a requester, so up can name the chip that
     * row is actually parked on. Read only when a press is being answered, which keeps the season
     * row's highlight moving from recomposing the whole band.
     */
    upRowFocus: RowFocusMemory? = null,
    upRequester: FocusRequester? = null,
    /** Where down out of the blocks goes. Null while there is nothing below to land on. */
    downRequester: FocusRequester? = null,
    onSelect: (Int) -> Unit,
    onJump: () -> Unit,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // The jump chip is the last item, so it is part of what the row can be focused on and counts
    // towards how far the row has to scroll.
    val itemCount = ranges.size + 1
    val rowFocus = rememberRowFocus(key = "ranges:${ranges.size}", initialIndex = selectedIndex)
    // Resolved at press time, never during composition: the season row's focused index moves as the
    // viewer walks along it, and depending on it here would rebuild this row and the episode cards
    // below it on every one of those presses.
    val upTarget: () -> FocusRequester? = {
        // Only ever a chip that is on screen. Falling back to the row itself when the season row has
        // composed nothing means the press becomes an ordinary search rather than a crash.
        upRowFocus?.entryRequester() ?: upRequester
    }
    LaunchedEffect(selectedIndex, ranges.size) {
        if (selectedIndex in ranges.indices) rowFocus.remember(selectedIndex)
    }
    LaunchedEffect(rowFocus.focusedIndex, itemCount) {
        listState.animateToAnchoredItem(
            focusedIndex = rowFocus.focusedIndex,
            itemCount = itemCount,
            leadingItems = 1,
        )
    }
    LazyRow(
        state = listState,
        modifier = Modifier
            .then(rowRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .rowFocusEntry(rowFocus)
            // Up is answered here rather than left to the focus search that the row-level property
            // above sets up. That search resolves against whatever the season row will accept, and
            // for the blocks nearest the start of the row - the ones sitting under the left-hand
            // end of a season strip that has been scrolled along - it was resolving against nothing
            // and swallowing the press: the viewer had to walk right to a later block before up
            // did anything at all. Naming the chip the season row is actually parked on removes the
            // search from the question entirely, and it is a chip that is on screen by
            // construction, because the row anchors its scroll to exactly that index.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionUp) {
                    false
                } else {
                    upTarget()?.let { target -> runCatching { target.requestFocus() }.isSuccess } ?: false
                }
            }
            .focusProperties {
                upTarget()?.let { up = it }
                if (downRequester != null) down = downRequester
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DetailInset),
    ) {
        itemsIndexed(ranges, key = { _, range -> "range-${range.fromIndex}" }) { index, range ->
            val active = index == selectedIndex
            DetailFocusCard(
                onClick = { onSelect(index) },
                shape = AppPillShape,
                modifier = Modifier.rowFocusItem(rowFocus, index),
                description = stringResource(R.string.detail_episodes_range, range.firstEpisodeNumber, range.lastEpisodeNumber),
            ) {
                Text(
                    text = range.label,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
        // Last, so travelling right through the blocks arrives at it rather than having to be
        // hunted for. It is the answer for "I know the number", where the blocks answer "somewhere
        // around here".
        item("jump") {
            DetailFocusCard(
                onClick = onJump,
                shape = AppPillShape,
                modifier = Modifier.rowFocusItem(rowFocus, ranges.size),
                description = stringResource(R.string.detail_go_to_specific_episode),
            ) {
                Text(
                    text = stringResource(R.string.detail_go_to_episode),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
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
    watchedEpisodeKeys: Set<String>,
    /** The first unwatched episode of the active season, or null when the season is finished. */
    nextUnwatchedEpisodeNumber: Int? = null,
    /** The episode the viewer stopped part way through, and how far in they got. */
    inProgressEpisode: Pair<Int, Float>? = null,
    seasonWatched: Boolean,
    watchedSeasonNumbers: Set<Int>,
    markingSeason: Boolean,
    firstChipRequester: FocusRequester?,
    /** The hero's play button — where up out of this band leads. */
    upRequester: FocusRequester?,
    onSelectSeason: (Int) -> Unit,
    onMarkSeasonWatched: () -> Unit,
    onEpisodeFocused: (Int, SeasonEpisodeEntry) -> Unit,
    onEpisodePressed: (SeasonEpisodeEntry) -> Unit,
    onEpisodeMenu: (SeasonEpisodeEntry) -> Unit,
    /** Blocks the active season is cut into. Empty for a season short enough not to need cutting. */
    episodeRanges: List<EpisodeRange> = emptyList(),
    selectedRangeIndex: Int = 0,
    onSelectRange: (Int) -> Unit = {},
    onJumpToEpisode: () -> Unit = {},
    /** Where in the current block the highlight should open, after a jump named an episode. */
    initialFocusPosition: Int = 0,
    /** Bumped to ask the row to take focus - after a jump, when the dialog has nothing to hand it back to. */
    focusRowSignal: Int = 0,
) {
    val scale = detailBandScale(compact)
    val cardWidth = 268.dp
    val stillHeight = 150.dp
    val ranged = episodeRanges.isNotEmpty()
    // The cards on show, carrying the index they hold in the full run: the screen keys its selected
    // episode off that index, so handing it a position within a slice would select the wrong one.
    val visible = remember(entries, episodeRanges, selectedRangeIndex, ranged, activeSeasonNumber) {
        if (!ranged) {
            entries.withIndex().toList()
        } else {
            val range = episodeRanges.getOrNull(selectedRangeIndex) ?: episodeRanges.first()
            entries.withIndex()
                .filter { it.value.seasonNumber == activeSeasonNumber }
                .drop(range.fromIndex)
                .take(range.size)
        }
    }
    // Focus memory is per block as well as per run, so changing block starts at the first card
    // rather than restoring a position that belonged to a different set of twenty.
    val rowFocus = rememberRowFocus(
        key = if (ranged) rowFocusKey to selectedRangeIndex else rowFocusKey,
        // A jump names an episode, so the new block opens with the highlight already on it rather
        // than at the start of the twenty it happens to sit in.
        initialIndex = if (ranged) initialFocusPosition.coerceIn(0, (visible.size - 1).coerceAtLeast(0)) else 0,
    )
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val chipRowRequester = remember { FocusRequester() }
    // Held here rather than inside the season row so the blocks below can aim at the chip the
    // season row is parked on, instead of at the row and whatever entry search it settles on.
    val seasonRowFocus = rememberRowFocus(
        key = "seasons:${seasons.size}",
        initialIndex = seasons.indexOfFirst { it.seasonNumber == activeSeasonNumber }.coerceAtLeast(0),
    )
    val rangeRowRequester = remember { FocusRequester() }
    val episodeRowRequester = remember { FocusRequester() }
    val markSeasonRequester = remember { FocusRequester() }
    val spansSeasons = remember(visible) { visible.distinctBy { it.value.seasonNumber }.size > 1 }
    AnchorRowToFocus(rowState, rowFocus, visible.size)
    LaunchedEffect(focusRowSignal) {
        if (focusRowSignal <= 0) return@LaunchedEffect
        // Long enough for the new block to have composed and the anchored scroll to have run: a
        // card that is not on screen yet has no requester attached to fail against. Best-effort by
        // design - if it does not land, focus simply stays where it was rather than disappearing.
        delay(220)
        runCatching { episodeRowRequester.requestFocus() }
    }
    Column(
        modifier = Modifier.onFocusChanged { onFocusChanged(it.hasFocus) },
        verticalArrangement = Arrangement.spacedBy(detailBandSpacing(compact)),
    ) {
        DetailSectionHeader(
            title = stringResource(R.string.detail_episodes),
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
                watchedSeasonNumbers = watchedSeasonNumbers,
                firstChipRequester = firstChipRequester,
                rowRequester = chipRowRequester,
                rowFocus = seasonRowFocus,
                upRequester = markSeasonRequester,
                // Down is named for the same reason up is. The episode row's requester is only
                // attached once there are cards to attach it to, so while the season is still
                // loading its skeleton this stays null and the press falls back to spatial search
                // rather than being cancelled outright against a requester with nothing behind it.
                downRequester = when {
                    ranged -> rangeRowRequester
                    visible.isNotEmpty() -> episodeRowRequester
                    else -> null
                },
                onSelect = onSelectSeason,
            )
            if (ranged) {
                EpisodeRangeChipRow(
                    ranges = episodeRanges,
                    selectedIndex = selectedRangeIndex,
                    rowRequester = rangeRowRequester,
                    // The season chip itself, not the row: the chip that row is parked on is on
                    // screen by construction, because the row anchors its scroll to exactly that
                    // index, where the row as a whole resolved against whatever entry search it
                    // settled on and for the first blocks resolved against nothing.
                    upRowFocus = seasonRowFocus,
                    upRequester = chipRowRequester,
                    downRequester = episodeRowRequester.takeIf { visible.isNotEmpty() },
                    onSelect = onSelectRange,
                    onJump = onJumpToEpisode,
                )
            }
        }
        if (visible.isEmpty()) {
            // The season list arrives before its episodes do; a skeleton here stops the band
            // collapsing to nothing and then shoving the sections below it back down.
            Row(
                horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                modifier = Modifier.padding(horizontal = DetailInset).detailBandScale(scale, compact),
            ) {
                repeat(4) { TvSkeletonBox(Modifier.width(cardWidth).height(stillHeight)) }
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
                    .focusRequester(episodeRowRequester)
                    .rowFocusEntry(rowFocus)
                    .focusProperties {
                        // Only while the chips are actually on screen — pointing at a requester
                        // nothing is attached to cancels the move outright, which would be the very
                        // dead end this exists to prevent. Up goes to whichever strip is directly
                        // above: the blocks when a long season has them, the seasons otherwise.
                        // Both are drawn only while the band is expanded, which is the same
                        // condition, so neither can ever be named while it is absent.
                        if (!compact) up = if (ranged) rangeRowRequester else chipRowRequester
                    }
                    .detailBandScale(scale, compact),
                state = rowState,
                horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DetailInset),
            ) {
                itemsIndexed(
                    visible,
                    key = { _, indexed -> "s${indexed.value.seasonNumber}:${indexed.value.episode.id}" },
                ) { position, indexed ->
                    val entry = indexed.value
                    EpisodeCard(
                        modifier = Modifier.rowFocusItem(rowFocus, position),
                        compact = compact,
                        cardWidth = cardWidth,
                        stillHeight = stillHeight,
                        episode = entry.episode,
                        seasonNumber = entry.seasonNumber,
                        watched = watchedEpisodeKey(entry.seasonNumber, entry.episode.episodeNumber) in watchedEpisodeKeys,
                        released = isEpisodeReleased(entry.episode.airDate),
                        showSeason = spansSeasons,
                        // Only in a season long enough to have lost your place in: a season of ten
                        // does not need telling you which one is next.
                        nextUp = ranged &&
                            entry.seasonNumber == activeSeasonNumber &&
                            entry.episode.episodeNumber == nextUnwatchedEpisodeNumber,
                        progress = inProgressEpisode
                            ?.takeIf { entry.seasonNumber == activeSeasonNumber && entry.episode.episodeNumber == it.first }
                            ?.second,
                        // The index into the whole run, not the position in this block.
                        onFocused = { onEpisodeFocused(indexed.index, entry) },
                        onClick = { onEpisodePressed(entry) },
                        onLongPress = { onEpisodeMenu(entry) },
                    )
                }
                // A block of a long season has a definite end, so there is nothing after it to
                // append; the skeleton belongs to the continuous run only.
                if (loadingNextSeason && !ranged) {
                    // Deliberately not focusable: it holds the place of the season being fetched
                    // without ever becoming somewhere the D-pad can get stuck.
                    item("next-season") {
                        TvSkeletonBox(Modifier.width(cardWidth).height(stillHeight))
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
internal fun SimilarBandSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DetailSectionHeader("More Like This")
        Row(
            modifier = Modifier.padding(horizontal = DetailInset),
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
        ) {
            repeat(7) { TvSkeletonBox(Modifier.width(106.dp).height(159.dp)) }
        }
    }
}

@Composable
internal fun CastBand(
    cast: List<CastMember>,
    compact: Boolean = false,
    onFocusChanged: (Boolean) -> Unit = {},
    onOpen: (CastMember) -> Unit,
) {
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
                    onClick = { onOpen(member) },
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
    onClick: () -> Unit,
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
                .clickable(onClick = onClick),
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
                    description = stringResource(R.string.detail_review_by, comment.author),
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
