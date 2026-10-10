package com.streamdek.tv.nativeapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.streamdek.tv.nativeapp.data.CardArtworkScaling
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.PosterShape
import com.streamdek.tv.nativeapp.data.cardArtworkUrl
import com.streamdek.tv.nativeapp.data.chooseCardArtworkScaling

/**
 * Hides the small Plex mark on cards. Provided by the Plex pages themselves, where every card is
 * Plex's and the mark would only repeat what the page header already says.
 */
val LocalHideMediaServerMark = androidx.compose.runtime.staticCompositionLocalOf { false }

enum class TvMediaCardVariant { Landscape, Poster, Episode, Live, ContinueWatching, Compact }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PremiumMediaCard(
    item: MediaItem,
    variant: TvMediaCardVariant,
    modifier: Modifier = Modifier,
    favourite: Boolean = false,
    showProvider: Boolean = item.type == "live",
    /**
     * Draw the title and meta overlay. Off for collapsed rows, where the card is too small for
     * text and a clipped word reads as breakage rather than as a minified card.
     */
    showLabels: Boolean = true,
    /**
     * Show year and rating along the top and drop the title entirely.
     *
     * For rows where the artwork is the identifier — recommendations, mostly. A poster is
     * recognisable on its own, and a title block covers the bottom third of it to say something
     * the picture already said.
     */
    metaOnTop: Boolean = false,
    /** Where the top meta line sits. Centred reads better on a bare poster with no title under it. */
    metaOnTopAlignment: Alignment = Alignment.TopStart,
    /**
     * The artwork's shape when the row decided one -- see [com.streamdek.tv.nativeapp.data.resolveRowPosterShape].
     * Null keeps the shape the variant implies: portrait for [TvMediaCardVariant.Poster], wide otherwise.
     */
    artworkShape: PosterShape? = null,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    onFocused: () -> Unit = {},
) {
    val portrait = variant == TvMediaCardVariant.Poster
    val artShape = artworkShape ?: if (portrait) PosterShape.Poster else PosterShape.Landscape
    val shape = if (artShape != PosterShape.Landscape) RoundedCornerShape(12.dp) else AppCardShape
    val image = remember(item.poster, item.backdrop, item.landscapePoster, item.posterShape, artShape) {
        homeCardArtwork(item, artShape)
    }
    val focusScale = TvMotion.focusScale()
    val context = LocalContext.current
    val imageRequest = remember(context, image, artShape) { cardImageRequest(context, image, artShape) }
    var focused by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val metaLine = remember(item.rating, item.year, configuration) {
        listOfNotNull(
            item.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) },
            item.year,
        ).joinToString("  ·  ")
    }
    val topMeta = metaOnTop && metaLine.isNotBlank()
    val spokenDescription = remember(item.title, item.year, item.rating, item.progress, favourite, configuration) {
        buildString {
            append(item.title)
            item.year?.let { append(", $it") }
            item.rating?.let { append(", rated %.1f".format(it)) }
            if (favourite) append(", favourite")
            if ((item.progress ?: 0.0) > 0.0) append(", ${item.progress?.toInt()} percent watched")
        }
    }
    val bottomMeta = remember(item.year, item.rating, configuration) {
        listOfNotNull(item.year, item.rating?.let { "★ %.1f".format(it) }).joinToString("  •  ")
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .semantics { contentDescription = spokenDescription }
            .tvCardLongPress(onLongPress)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            pressedContainerColor = MaterialTheme.colorScheme.surface,
        ),
        border = CardDefaults.border(
            // No resting outline. Artwork defines its own edge; a translucent white hairline over
            // it just reads as a rendering artefact, especially while the card is resizing.
            border = Border.None,
            focusedBorder = Border(BorderStroke(if (LocalTvExperienceSettings.current.highContrast) 3.dp else 2.dp, MaterialTheme.colorScheme.primary), shape = shape),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = focusScale),
    ) {
        Box(Modifier.fillMaxSize().clip(shape).background(MaterialTheme.colorScheme.surface)) {
            TvCardArtwork(imageRequest, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        colorStops = if (metaOnTop) {
                            arrayOf(0f to Color(0x8C000000), 0.34f to Color.Transparent, 1f to Color(0x66000000))
                        } else {
                            arrayOf(0f to Color.Transparent, 0.52f to Color(0x18000000), 1f to Color(0xEE000000))
                        },
                    ),
                ),
            )
            Row(
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (favourite) CardBadge("★")
                if (variant == TvMediaCardVariant.Live) CardBadge("LIVE")
            }
            // Where a title comes from matters when there are two ways to play it: a Plex or Jellyfin
            // copy beside a streaming result. A small mark in the corner says so without a label.
            val serverMark = com.streamdek.tv.nativeapp.mediaserver.MediaServerReference.providerOfSource(item.sourceAddonId)
            if (!LocalHideMediaServerMark.current && serverMark != null) {
                val brand = mediaServerBrand(serverMark)
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color.Black.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        brand.mark,
                        contentDescription = null,
                        tint = brand.markTint,
                        modifier = Modifier.size(if (serverMark == com.streamdek.tv.nativeapp.mediaserver.PLEX_PROVIDER_ID) 14.dp else 12.dp),
                    )
                }
            }
            // Plain text, no pill: the badge shape competed with the poster art it sits on.
            if (topMeta) {
                Text(
                    text = metaLine,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                    color = Color.White.copy(alpha = if (focused) 1f else 0.86f),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.align(metaOnTopAlignment).padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            if (showLabels && !topMeta) Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (showProvider) item.sourceAddonName?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black), color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                item.cardSubtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (item.cardHighlight) FontWeight.Black else FontWeight.Normal,
                        ),
                        color = if (item.cardHighlight) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                item.episode?.title?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(item.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = if (portrait) 2 else 1, overflow = TextOverflow.Ellipsis)
                if (bottomMeta.isNotBlank()) Text(bottomMeta, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.72f), maxLines = 1)
                if ((item.progress ?: 0.0) > 0.0) {
                    ProgressMeter(item.progress, Modifier.width(if (portrait) 92.dp else 132.dp).height(4.dp))
                    item.positionSec?.let { Text(formatPlaybackClock(it), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.78f)) }
                }
            }
        }
    }
}

/** The picture a card of [shape] shows for [item], at the resolution TV cards want. */
internal fun homeCardArtwork(item: MediaItem, shape: PosterShape): String? = highResolutionCardArtwork(
    cardArtworkUrl(item.poster, item.backdrop, item.landscapePoster, item.declaredPosterShape(), shape),
    portrait = shape != PosterShape.Landscape,
)

/**
 * The one request a card's artwork is loaded with, shared by Home's preloading so the card finds
 * the image already decoded.
 *
 * Decoded for the card, not at the source artwork's multi-megapixel size. Besides wasting memory,
 * full-size decodes queued behind one another and made Home artwork appear card by card on
 * lower-powered televisions. RGB_565 is allowed only for wide thumbnails, and Coil only ever uses
 * it for an image with no alpha channel, so transparent artwork keeps its transparency.
 */
internal fun cardImageRequest(context: android.content.Context, image: String?, shape: PosterShape): ImageRequest {
    val (width, height) = when (shape) {
        PosterShape.Poster -> 360 to 540
        PosterShape.Square -> 400 to 400
        PosterShape.Landscape -> 480 to 270
    }
    return ImageRequest.Builder(context)
        .data(image)
        .memoryCacheKey(image)
        .diskCacheKey(image)
        .allowHardware(true)
        .allowRgb565(shape == PosterShape.Landscape)
        .size(width, height)
        .crossfade(false)
        .build()
}

/**
 * A card's picture, placed to suit what it turns out to be: cropped to fill when it fits the card,
 * as before; shown whole when it is another shape, over a dimmed copy of itself when opaque and on
 * the card's own surface when transparent. One decode serves both layers, and the choice is made
 * before the image is first drawn, so focus and layout never move when artwork arrives.
 */
@Composable
private fun TvCardArtwork(request: ImageRequest, modifier: Modifier) {
    val painter = rememberAsyncImagePainter(model = request, contentScale = ContentScale.Crop)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val scaling = (painter.state as? AsyncImagePainter.State.Success)?.let { success ->
            val size = success.painter.intrinsicSize
            val hasAlpha = (success.result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.hasAlpha() == true
            chooseCardArtworkScaling(size.width, size.height, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), hasAlpha)
        } ?: CardArtworkScaling.Crop
        if (scaling == CardArtworkScaling.FitOverBackdrop) {
            androidx.compose.foundation.Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(18.dp).alpha(0.38f),
            )
        }
        androidx.compose.foundation.Image(
            painter = painter,
            contentDescription = null,
            contentScale = if (scaling == CardArtworkScaling.Crop) ContentScale.Crop else ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .then(if (scaling == CardArtworkScaling.Fit) Modifier.padding(maxWidth * 0.08f) else Modifier),
        )
    }
}

private val legacyCardArtworkSize = Regex("/t/p/w(?:92|154|185|300|342|500)/")

/** Upgrades legacy TMDB thumbnail URLs when a poster is rendered at TV-card size. */
internal fun highResolutionCardArtwork(url: String?, portrait: Boolean): String? {
    if (!portrait || url.isNullOrBlank() || !url.contains("image.tmdb.org/t/p/")) return url
    return url.replace(legacyCardArtworkSize, "/t/p/w780/")
}

@Composable
private fun CardBadge(label: String) {
    Box(
        modifier = Modifier.clip(AppPillShape).background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 7.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        // One line always. "2025 · ★ 7.3" wrapping to two lines inside a pill reads as broken
        // rather than as a badge, and the poster is narrow enough that it will try.
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false,
        )
    }
}
