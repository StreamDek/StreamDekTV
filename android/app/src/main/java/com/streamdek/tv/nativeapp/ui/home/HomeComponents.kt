package com.streamdek.tv.nativeapp.ui.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.streamdek.tv.nativeapp.ui.animateToAnchoredItem
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
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
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.streamdek.tv.nativeapp.data.HomeRail
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.PendingRail
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.PremiumMediaCard
import com.streamdek.tv.nativeapp.ui.TvMediaCardVariant
import com.streamdek.tv.nativeapp.ui.TvMotion
import com.streamdek.tv.nativeapp.ui.TvScroll
import com.streamdek.tv.nativeapp.ui.TvSkeletonBox
import com.streamdek.tv.nativeapp.ui.TvSpacing
import com.streamdek.tv.nativeapp.ui.tvCardLongPress
import kotlin.math.roundToInt

/**
 * Page inset.
 *
 * Tighter than the app-wide [TvSpacing.ScreenHorizontal], because on Home it is not the only gutter
 * in play: the nav rail already holds its own 68dp back from the edge, and stacking the standard
 * 48dp on top of that pushed the shelves a long way inboard and left a channel of dead space beside
 * the rail. 24dp is the 5% overscan margin, measured from where the rail leaves off.
 */
internal val HomeInset = 24.dp

/**
 * Title line above each shelf, the gap under it, and the gap between shelves.
 *
 * The title height is the line box a shelf heading actually occupies, measured off the running app
 * rather than taken from the 24sp line height the type scale nominally asks for — the two differ by
 * enough (6dp a shelf, twice over) to turn a third of a card into half of one.
 */
private val ShelfTitleHeight = 18.dp
private val ShelfTitleGap = 10.dp
internal val ShelfSpacing = 16.dp

/** How far a shelf the viewer is not on shrinks. Matches the scale [HomeShelf] animates to. */
internal const val CompactShelfScale = 0.72f

/**
 * Height the spotlight occupies. The shelves below take the rest with a weight, so nothing depends
 * on guessing where the hero ends.
 *
 * Derived rather than fixed, because it is really a statement about the shelves: show one complete
 * and a third of the next. Two essentially complete rows left the screen bottom-heavy and gave the
 * hero no room to breathe; a sliver of the row below still says "there is more down here", which is
 * the only job it has. A constant could only be right for one combination — card shape and density
 * are both settings the viewer can change, and a 174dp poster shelf needs 57dp more than a 117dp
 * landscape one — so the arithmetic is done here instead: measure what the shelves need, and the
 * hero gets what is left.
 */
@Composable
internal fun spotlightHeight(portraitCards: Boolean): Dp {
    val dense = LocalTvExperienceSettings.current.denseCards
    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val cardHeight = (if (portraitCards) 174.dp else 117.dp) * (if (dense) 0.9f else 1f)
    val fullShelf = ShelfTitleHeight + ShelfTitleGap + cardHeight
    val trailingShelf = ShelfTitleHeight + ShelfTitleGap + (cardHeight * CompactShelfScale) / 3f
    // Bounded so an unusual screen cannot squeeze the hero out or let it swallow the shelves.
    return (screenHeight - (fullShelf + ShelfSpacing + trailingShelf)).coerceIn(180.dp, 420.dp)
}

/**
 * Card geometry. Every card of a given shape is the same size everywhere on the screen and stays
 * that size when focused — a grid whose focused tile grows shoves its neighbours around, and on a
 * TV that reads as the row twitching as you travel along it.
 */
internal data class HomeCardSize(val width: Dp, val height: Dp)

internal fun homeCardSize(item: MediaItem, portrait: Boolean, compact: Boolean, dense: Boolean): HomeCardSize {
    val scale = (if (compact) CompactShelfScale else 1f) * (if (dense) 0.9f else 1f)
    return when {
        item.type == "network" -> HomeCardSize(190.dp * scale, 104.dp * scale)
        portrait -> HomeCardSize(116.dp * scale, 174.dp * scale)
        else -> HomeCardSize(208.dp * scale, 117.dp * scale)
    }
}

/**
 * The panel above the rails, driven by whatever card has focus.
 *
 * The screen this replaces positioned its hero as a floating overlay and started the rails at a
 * hardcoded offset below it. That offset had to assume a title length, and the hero collapsed the
 * instant any card took focus — which is immediately, since a card grabs focus on load — so the
 * full treatment was never actually seen. Here the spotlight is a real row in the layout with a
 * known height, and it stays legible because it only ever shows what fits.
 */
@Composable
internal fun HomeSpotlight(
    item: MediaItem?,
    detail: MediaDetail?,
    height: Dp,
    hideSynopsis: Boolean,
    modifier: Modifier = Modifier,
) {
    if (item == null) {
        Box(modifier.height(height))
        return
    }

    val synopsis = detail?.description?.takeIf { it.isNotBlank() } ?: item.description?.takeIf { it.isNotBlank() }
    val logo = detail?.titleLogo ?: item.titleLogo
    val context = LocalContext.current
    val logoRequest = remember(logo) {
        logo?.let {
            ImageRequest.Builder(context)
                .data(it)
                .memoryCacheKey(it)
                .diskCacheKey(it)
                .size(640, 180)
                .crossfade(90)
                .allowHardware(true)
                .allowRgb565(false)
                .build()
        }
    }

    Column(
        modifier = modifier
            .height(height)
            .fillMaxWidth(0.68f)
            .padding(start = HomeInset, top = 34.dp, end = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Every slot below has a fixed height and every one of them is always present, so the copy
        // holds still as the viewer travels the rows. Previously the kind label existed only for
        // live channels, the metadata row grew a chip at a time as the detail fetch landed, and the
        // title swapped between a logo and two lines of text — so the block shuffled on nearly
        // every highlight. What changes now is the content of each slot, never its size.
        //
        // With the synopsis hidden there is spare room, and it is split above and below so the
        // block sits in the middle of the band rather than stranded at the top.
        if (hideSynopsis) Spacer(Modifier.weight(1f))

        Box(modifier = Modifier.height(BadgeSlotHeight).fillMaxWidth()) {
            spotlightKindLabel(item, detail)?.let { label ->
                SpotlightChip(label, emphasised = true)
            }
        }

        // The logo arrives with the detail fetch, a beat after the card takes focus. Showing the
        // written title until it lands means the spotlight is never a blank gap where the name of
        // the thing should be — the reserved height alone left the panel looking broken.
        val titleText = @Composable {
            Text(
                text = item.title,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Keep a fixed title slot while detail metadata supplies a logo. Without this container,
        // the fallback text and the 68dp logo measured at different heights and the hero copy
        // visibly jumped as users moved quickly across cards.
        Box(modifier = Modifier.height(68.dp).fillMaxWidth()) {
            if (logoRequest != null) {
                SubcomposeAsyncImage(
                    model = logoRequest,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    loading = { titleText() },
                    error = { titleText() },
                )
            } else {
                titleText()
            }
        }

        Box(modifier = Modifier.height(MetaSlotHeight).fillMaxWidth()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxSize(),
            ) {
                (detail?.rating ?: item.rating)?.takeIf { it > 0 }?.let {
                    SpotlightChip("★ %.1f".format(it), emphasised = true)
                }
                (detail?.year ?: item.year)?.takeIf { it.isNotBlank() }?.let { SpotlightChip(it) }
                detail?.runtime?.takeIf { it > 0 }?.let { SpotlightChip("${it / 60}h ${it % 60}m") }
                detail?.genreNames?.take(2)?.forEach { SpotlightChip(it) }
            }
        }

        if (!hideSynopsis) {
            // The chips and the synopsis are different kinds of information and were reading as one
            // block; the gap is what separates "facts about this title" from "what it is about".
            Spacer(Modifier.height(8.dp))

            // Nothing focusable lives in the spotlight: it is a description of whatever card has
            // the highlight, not somewhere the remote travels to. The full synopsis is on the
            // detail page, which is one press away.
            synopsis?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(Modifier.weight(1f))
    }
}

/** Fixed slots either side of the title, so the block never resizes as metadata arrives. */
private val BadgeSlotHeight = 24.dp
private val MetaSlotHeight = 30.dp

/**
 * What kind of thing the spotlight is describing.
 *
 * Genre wins over the raw type where it is more use — "Documentary" says more than "Movie" — and a
 * live channel names its source, which is where the addon label above the title used to live.
 */
private fun spotlightKindLabel(item: MediaItem, detail: MediaDetail?): String? {
    val genres = detail?.genreNames.orEmpty()
    return when {
        item.type == "live" -> item.sourceAddonName?.takeIf { it.isNotBlank() }?.uppercase() ?: "LIVE"
        item.type == "network" -> "STREAMING SERVICE"
        genres.any { it.equals("Documentary", ignoreCase = true) } -> "DOCUMENTARY"
        genres.any { it.equals("Reality", ignoreCase = true) } -> "REALITY"
        genres.any { it.equals("Talk", ignoreCase = true) } -> "TALK SHOW"
        genres.any { it.equals("News", ignoreCase = true) } -> "NEWS"
        item.type == "tv" -> "SERIES"
        item.type == "movie" -> "MOVIE"
        else -> null
    }
}

@Composable
private fun SpotlightChip(label: String, emphasised: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(AppPillShape)
            .background(
                if (emphasised) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                else Color.White.copy(alpha = 0.10f),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = if (emphasised) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.84f),
        )
    }
}

/**
 * One shelf.
 *
 * [compact] shrinks every row the viewer is not on. With a spotlight taking the top of the screen
 * there is only room for roughly one and a half full rows, so without this the row below the
 * active one is a sliver and the screen reads as though it ends there.
 */
@Composable
internal fun HomeShelf(
    row: HomeRail,
    rowState: LazyListState,
    compact: Boolean,
    portraitCards: Boolean,
    /** Drop the title overlay from poster cards. See AppPreferences.hideHomeCardTitles. */
    hideCardTitles: Boolean = false,
    firstCardRequester: FocusRequester?,
    focusItemKey: String?,
    onFocusItemHandled: () -> Unit,
    onItemFocused: (Int, MediaItem) -> Unit,
    onItemPressed: (MediaItem) -> Unit,
    onItemMenu: (MediaItem, FocusRequester) -> Unit,
    onOpenNavigation: () -> Unit,
) {
    val requesters = remember(row.id) { mutableMapOf<String, FocusRequester>() }
    val dense = LocalTvExperienceSettings.current.denseCards
    // Duplicate keys in a lazy row are fatal, and a catalogue can legitimately repeat a title.
    val rowItems = remember(row.items) { row.items.distinctBy(::homeItemKey) }

    // Cards register their requesters as they compose, so a restore target that has not been laid
    // out yet is retried briefly rather than dropped.
    LaunchedEffect(focusItemKey, row.items.size) {
        val target = focusItemKey ?: return@LaunchedEffect
        repeat(8) {
            val requester = requesters[target]
            if (requester != null && runCatching { requester.requestFocus() }.isSuccess) {
                onFocusItemHandled()
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(60)
        }
        onFocusItemHandled()
    }

    // Keeps the focused card at the leading edge as the viewer travels the row, on the same
    // curve the vertical movement uses, so horizontal and vertical motion feel like one system.
    var focusedIndex by androidx.compose.runtime.remember(row.id) { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(focusedIndex, rowItems.size) {
        rowState.animateToAnchoredItem(
            focusedIndex = focusedIndex,
            itemCount = rowItems.size,
            leadingItems = 0,
        )
    }

    val titleAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (compact) 0.55f else 1f,
        animationSpec = TvScroll.spec(TvMotion.duration(220)),
        label = "shelf-title",
    )

    val shelfScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (compact) CompactShelfScale else 1f,
        animationSpec = TvScroll.spec(TvMotion.duration(220)),
        label = "shelf-scale",
    )
    // GPU scaling keeps row-to-row motion smooth, but the lazy row still reserves each card's
    // full unscaled width. Translate each successive card by the animated unused width so scale
    // and spacing move together; changing the LazyRow spacing itself would snap before the scale
    // animation finishes and briefly make the exiting row overlap.
    val representativeWidth = rowItems.firstOrNull()
        ?.let { homeCardSize(it, portraitCards, compact = false, dense = dense).width }
        ?: 208.dp
    val representativeWidthPx = with(LocalDensity.current) { representativeWidth.toPx() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = row.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = titleAlpha),
            modifier = Modifier.padding(start = HomeInset),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxWidth().compactShelfViewport(compact).focusGroup(),
            contentPadding = PaddingValues(horizontal = HomeInset),
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
        ) {
            // rowItems, not row.items: the de-duplication above is the whole point, and iterating
            // the raw list threw it away. Continue Watching can hold the same title twice (two
            // entries for one series, neither carrying an episode), and a repeated key is a hard
            // crash — reliably reproduced by pressing up, because focus search composes items
            // beyond the visible window and reaches the duplicate.
            itemsIndexed(rowItems, key = { _, item -> "${row.id}:${homeItemKey(item)}" }) { index, item ->
                val key = "${row.id}:${homeItemKey(item)}"
                val requester = requesters.getOrPut(key) { FocusRequester() }
                val effective = if (index == 0 && firstCardRequester != null) firstCardRequester else requester
                val cardSize = homeCardSize(item, portraitCards, compact = false, dense = dense)
                val size = cardSize.width
                val cardHeight = cardSize.height

                if (item.type == "network") {
                    NetworkCard(
                        item = item,
                        modifier = Modifier
                            .focusRequester(effective)
                            .openNavigationFromFirstCard(index, onOpenNavigation)
                            .width(size)
                            .height(cardHeight)
                            .graphicsLayer {
                                scaleX = shelfScale
                                scaleY = shelfScale
                                translationX = -index * representativeWidthPx * (1f - shelfScale)
                                transformOrigin = TransformOrigin(0f, 0f)
                            },
                        onFocused = {
                            focusedIndex = index
                            onItemFocused(index, item)
                        },
                        onPressed = { onItemPressed(item) },
                    )
                } else {
                    val variant = when {
                        item.type == "live" -> TvMediaCardVariant.Live
                        row.id == "continue-watching" -> TvMediaCardVariant.ContinueWatching
                        portraitCards -> TvMediaCardVariant.Poster
                        else -> TvMediaCardVariant.Landscape
                    }
                    PremiumMediaCard(
                        item = item,
                        variant = variant,
                        // Plain posters only. A live card is identified by its channel name and a
                        // Continue Watching card carries the episode and the progress bar in the
                        // same block -- dropping it there would take those with it, which is not
                        // what "the poster already says the title" means.
                        metaOnTop = hideCardTitles && variant == TvMediaCardVariant.Poster,
                        modifier = Modifier
                            .focusRequester(effective)
                            .openNavigationFromFirstCard(index, onOpenNavigation)
                            .width(size)
                            .height(cardHeight)
                            .graphicsLayer {
                                scaleX = shelfScale
                                scaleY = shelfScale
                                translationX = -index * representativeWidthPx * (1f - shelfScale)
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                            .tvCardLongPress { onItemMenu(item, effective) },
                        onClick = { onItemPressed(item) },
                        onLongPress = { onItemMenu(item, effective) },
                        onFocused = {
                            focusedIndex = index
                            onItemFocused(index, item)
                        },
                    )
                }
            }
        }
    }
}

/**
 * The leading card is the content/rail boundary. Consume both key edges here so Compose never
 * parks focus on the collapsed rail container before the shell has made the rail authoritative.
 */
private fun Modifier.openNavigationFromFirstCard(index: Int, onOpenNavigation: () -> Unit): Modifier {
    if (index != 0) return this
    return onPreviewKeyEvent { event ->
        if (event.key != Key.DirectionLeft) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) onOpenNavigation()
        true
    }
}

/**
 * Measures a collapsed shelf against the width it will actually occupy once scaled.
 *
 * A collapsed row draws at 72% through a graphics layer, but the lazy row underneath still reserves
 * each card's full unscaled width — so it composes only as many cards as the *unscaled* viewport
 * holds, and the scaled result stops short of the screen edge. That trailing gap is a card's worth
 * of empty space, which reads as a row missing its last item. Measuring the row wider makes it
 * compose the extra card; the surplus width is off-screen and the list above clips it.
 *
 * Keyed to the discrete compact flag rather than the animated scale, so this is one extra
 * measurement when a row collapses, not a re-measure every frame of the animation.
 */
private fun Modifier.compactShelfViewport(compact: Boolean): Modifier = layout { measurable, constraints ->
    val childWidth = if (compact && constraints.hasBoundedWidth) {
        (constraints.maxWidth / CompactShelfScale).roundToInt()
    } else {
        constraints.maxWidth
    }
    val childConstraints = if (constraints.hasBoundedWidth) {
        constraints.copy(minWidth = childWidth, maxWidth = childWidth)
    } else {
        constraints
    }
    val placeable = measurable.measure(childConstraints)
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else placeable.width
    layout(width, placeable.height) { placeable.placeRelative(0, 0) }
}

/** Streaming-service tile. Logos are supplied on white, so the surface stays light. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NetworkCard(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onPressed: () -> Unit,
) {
    Card(
        onClick = onPressed,
        modifier = modifier.androidxOnFocus(onFocused),
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(
            containerColor = Color.White,
            focusedContainerColor = Color.White,
            pressedContainerColor = Color.White,
        ),
        border = CardDefaults.border(
            // No resting outline on any card in the app.
            border = Border.None,
            focusedBorder = Border(
                BorderStroke(
                    if (LocalTvExperienceSettings.current.highContrast) 3.dp else 2.dp,
                    MaterialTheme.colorScheme.primary,
                ),
                shape = AppCardShape,
            ),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = TvMotion.focusScale()),
    ) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            val art = item.poster ?: item.backdrop
            if (!art.isNullOrBlank()) {
                AsyncImage(
                    model = art,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black),
                    color = Color(0xFF111111),
                    maxLines = 1,
                )
            }
        }
    }
}

private fun Modifier.androidxOnFocus(onFocused: () -> Unit): Modifier =
    this.onFocusChanged { if (it.isFocused) onFocused() }

/** Placeholder shelf for a row that has not resolved yet. Deliberately not focusable. */
@Composable
internal fun HomeSkeletonShelf(pending: PendingRail, portraitCards: Boolean) {
    val dense = LocalTvExperienceSettings.current.denseCards
    val scale = if (dense) 0.9f else 1f
    val width = (if (pending.portrait || portraitCards) 116.dp else 208.dp) * scale
    val height = (if (pending.portrait || portraitCards) 174.dp else 117.dp) * scale
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = pending.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
            modifier = Modifier.padding(start = HomeInset),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
            modifier = Modifier.padding(horizontal = HomeInset),
        ) {
            repeat(6) { TvSkeletonBox(Modifier.width(width).height(height)) }
        }
    }
}

/** Stable identity for a card, including the episode so two rows of the same show differ. */
internal fun homeItemKey(item: MediaItem): String {
    val episode = item.episode?.let { ":s${it.seasonNumber}:e${it.episodeNumber}" }.orEmpty()
    return "${item.type}:${item.id}$episode"
}

/**
 * Backdrop wash. Two linear passes; radial shaders are the expensive kind on a stick.
 *
 * Each pass only has to cover what sits on top of it. The horizontal one exists for the copy, which
 * ends around 68% of the width, so it now clears completely by 76% instead of easing all the way to
 * the right edge — the artwork's top right corner is the part of the frame with nothing over it at
 * all, and it was being dimmed for no reason. The vertical one exists for the shelves, so it holds
 * its strength at the bottom and starts later than it did, leaving the upper third of the image
 * close to untouched.
 */
internal fun homeScrim(backgroundColor: Color): Pair<Brush, Brush> {
    val readingScrim = Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to backgroundColor.copy(alpha = 0.92f),
            0.44f to backgroundColor.copy(alpha = 0.58f),
            0.76f to Color.Transparent,
        ),
    )
    val baseFade = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color.Transparent,
            0.46f to backgroundColor.copy(alpha = 0.20f),
            0.68f to backgroundColor.copy(alpha = 0.82f),
            1f to backgroundColor,
        ),
    )
    return readingScrim to baseFade
}
