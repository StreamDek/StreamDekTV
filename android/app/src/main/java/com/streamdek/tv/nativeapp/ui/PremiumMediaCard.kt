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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.streamdek.tv.nativeapp.data.MediaItem

enum class TvMediaCardVariant { Landscape, Poster, Episode, Live, ContinueWatching, Compact }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PremiumMediaCard(
    item: MediaItem,
    variant: TvMediaCardVariant,
    modifier: Modifier = Modifier,
    favourite: Boolean = false,
    showProvider: Boolean = item.type == "live",
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    onFocused: () -> Unit = {},
) {
    val portrait = variant == TvMediaCardVariant.Poster
    val shape = if (portrait) RoundedCornerShape(12.dp) else AppCardShape
    val image = if (portrait) item.poster ?: item.backdrop else item.backdrop ?: item.poster
    val focusScale = TvMotion.focusScale()
    val context = LocalContext.current
    val imageRequest = remember(image) {
        ImageRequest.Builder(context)
            .data(image)
            .memoryCacheKey(image)
            .diskCacheKey(image)
            .allowHardware(true)
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }
    val spokenDescription = buildString {
        append(item.title)
        item.year?.let { append(", $it") }
        item.rating?.let { append(", rated %.1f".format(it)) }
        if (favourite) append(", favourite")
        if ((item.progress ?: 0.0) > 0.0) append(", ${item.progress?.toInt()} percent watched")
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .semantics { contentDescription = spokenDescription }
            .tvCardLongPress(onLongPress)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            pressedContainerColor = MaterialTheme.colorScheme.surface,
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), shape = shape),
            focusedBorder = Border(BorderStroke(if (LocalTvExperienceSettings.current.highContrast) 3.dp else 2.dp, MaterialTheme.colorScheme.primary), shape = shape),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = focusScale),
    ) {
        Box(Modifier.fillMaxSize().clip(shape).background(MaterialTheme.colorScheme.surface)) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(0f to Color.Transparent, 0.52f to Color(0x18000000), 1f to Color(0xEE000000)),
                    ),
                ),
            )
            Row(
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (favourite) CardBadge("★")
                if (variant == TvMediaCardVariant.ContinueWatching) CardBadge("CONTINUE")
                if (variant == TvMediaCardVariant.Live) CardBadge("LIVE")
            }
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (showProvider) item.sourceAddonName?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black), color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                item.episode?.title?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(item.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = if (portrait) 2 else 1, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(item.year, item.rating?.let { "★ %.1f".format(it) }).joinToString("  •  ")
                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.72f), maxLines = 1)
                if ((item.progress ?: 0.0) > 0.0) {
                    ProgressMeter(item.progress, Modifier.width(if (portrait) 92.dp else 132.dp).height(4.dp))
                    item.positionSec?.let { Text(formatPlaybackClock(it), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.78f)) }
                }
            }
        }
    }
}

@Composable
private fun CardBadge(label: String) {
    Box(
        modifier = Modifier.clip(AppPillShape).background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = 7.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black), color = MaterialTheme.colorScheme.primary)
    }
}