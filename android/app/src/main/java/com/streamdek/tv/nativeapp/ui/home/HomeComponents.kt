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
import androidx.compose.ui.layout.ContentScale
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
import com.streamdek.tv.nativeapp.ui.ProgressMeter
import com.streamdek.tv.nativeapp.ui.TvMediaCardVariant
import com.streamdek.tv.nativeapp.ui.TvMotion
import com.streamdek.tv.nativeapp.ui.TvScroll
import com.streamdek.tv.nativeapp.ui.TvSkeletonBox
import com.streamdek.tv.nativeapp.ui.TvSpacing
import com.streamdek.tv.nativeapp.ui.tvCardLongPress

/** Page inset. The nav rail already reserves its own gutter, so this is measured from content. */
internal val HomeInset = TvSpacing.ScreenHorizontal

/**
 * Height the spotlight occupies. Fixed and known, which is the point: the shelves below take the
 * remaining height with a weight, so nothing depends on guessing where the hero ends.
 *
 * Sized so exactly two shelves fit underneath — the focused one at full height and the next
 * collapsed. A third partially visible row read as clutter rather than as an affordance.
 */
internal val SpotlightHeight = 276.dp

/**
 * Card geometry. Every card of a given shape is the same size everywhere on the screen and stays
 * that size when focused — a grid whose focused tile grows shoves its neighbours around, and on a
 * TV that reads as the row twitching as you travel along it.
 */
internal data class HomeCardSize(val width: Dp, val height: Dp)

internal fun homeCardSize(item: MediaItem, portrait: Boolean, compact: Boolean, dense: Boolean): HomeCardSize {
    val scale = (if (compact) 0.72f else 1f) * (if (dense) 0.9f else 1f)
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
    modifier: Modifier = Modifier,
) {
    if (item == null) {
        Box(modifier.height(SpotlightHeight))
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
            .height(SpotlightHeight)
            .fillMaxWidth(0.68f)
            .padding(start = HomeInset, top = 34.dp, end = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item.sourceAddonName?.takeIf { it.isNotBlank() && item.type == "live" }?.let {
            Text(
                text = it.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (detail?.rating ?: item.rating)?.takeIf { it > 0 }?.let {
                SpotlightChip("★ %.1f".format(it), emphasised = true)
            }
            (detail?.year ?: item.year)?.takeIf { it.isNotBlank() }?.let { SpotlightChip(it) }
            detail?.runtime?.takeIf { it > 0 }?.let { SpotlightChip("${it / 60}h ${it % 60}m") }
            detail?.genreNames?.take(2)?.forEach { SpotlightChip(it) }
        }

        synopsis?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Continue Watching cards carry a position; showing it here means the viewer can tell how
        // far in they are without opening the title.
        item.progress?.takeIf { it > 0.0 }?.let { progress ->
            ProgressMeter(progress, Modifier.width(260.dp).height(4.dp))
        }
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
    firstCardRequester: FocusRequester?,
    focusItemKey: String?,
    onFocusItemHandled: () -> Unit,
    onItemFocused: (Int, MediaItem) -> Unit,
    onItemPressed: (MediaItem) -> Unit,
    onItemMenu: (MediaItem, FocusRequester) -> Unit,
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
        targetValue = if (compact) 0.72f else 1f,
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
            modifier = Modifier.fillMaxWidth().focusGroup(),
            contentPadding = PaddingValues(horizontal = HomeInset),
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
        ) {
            itemsIndexed(row.items, key = { _, item -> "${row.id}:${homeItemKey(item)}" }) { index, item ->
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
                    PremiumMediaCard(
                        item = item,
                        variant = when {
                            item.type == "live" -> TvMediaCardVariant.Live
                            row.id == "continue-watching" -> TvMediaCardVariant.ContinueWatching
                            portraitCards -> TvMediaCardVariant.Poster
                            else -> TvMediaCardVariant.Landscape
                        },
                        modifier = Modifier
                            .focusRequester(effective)
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

/** Backdrop wash. Two linear passes; radial shaders are the expensive kind on a stick. */
internal fun homeScrim(backgroundColor: Color): Pair<Brush, Brush> {
    val readingScrim = Brush.horizontalGradient(
        colorStops = arrayOf(
            0f to backgroundColor.copy(alpha = 0.96f),
            0.46f to backgroundColor.copy(alpha = 0.72f),
            1f to Color.Transparent,
        ),
    )
    val baseFade = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color.Transparent,
            0.34f to backgroundColor.copy(alpha = 0.34f),
            0.62f to backgroundColor.copy(alpha = 0.88f),
            1f to backgroundColor,
        ),
    )
    return readingScrim to baseFade
}
