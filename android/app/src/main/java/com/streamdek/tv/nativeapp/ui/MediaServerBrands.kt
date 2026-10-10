package com.streamdek.tv.nativeapp.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.mediaserver.EMBY_PROVIDER_ID
import com.streamdek.tv.nativeapp.mediaserver.JELLYFIN_PROVIDER_ID
import com.streamdek.tv.nativeapp.mediaserver.PLEX_PROVIDER_ID
import com.streamdek.tv.nativeapp.ui.plex.jellyfinAmbientGlow
import com.streamdek.tv.nativeapp.ui.plex.plexAmbientGlow

/**
 * How each personal media server looks wherever the television names it - its name, accent, mark,
 * logo and the few sentences that name it - so screens ask for "this provider's" rather than branch
 * on which provider it is. The phone keeps the same table; a new provider is one more entry.
 */
internal data class MediaServerBrand(
    val provider: String,
    @StringRes val name: Int,
    val accent: Color,
    /** The small mark on cards, a little lighter than [accent] so it reads on dark artwork. */
    val markTint: Color,
    val mark: ImageVector,
    /** A full-colour logo the app may show, or null to draw [mark] in [accent]. */
    @DrawableRes val logo: Int?,
    @StringRes val pageOfflineTitle: Int,
    @StringRes val pageEmptyNote: Int,
    @StringRes val pageServerRefused: Int,
    @StringRes val removeServerBody: Int,
)

internal val EmbyBrandGreen = Color(0xFF52B54B)

internal fun mediaServerBrand(provider: String?): MediaServerBrand = when (provider) {
    JELLYFIN_PROVIDER_ID -> MediaServerBrand(
        provider = JELLYFIN_PROVIDER_ID, name = R.string.media_server_jellyfin, accent = Color(0xFFAA5CC3), markTint = Color(0xFF8E7CE6),
        mark = JellyfinIcons.Mark, logo = R.drawable.jellyfin_logo, pageOfflineTitle = R.string.jellyfin_page_offline_title,
        pageEmptyNote = R.string.jellyfin_page_empty_note, pageServerRefused = R.string.jellyfin_page_server_refused,
        removeServerBody = R.string.jellyfin_remove_server_body,
    )
    EMBY_PROVIDER_ID -> MediaServerBrand(
        provider = EMBY_PROVIDER_ID, name = R.string.media_server_emby, accent = EmbyBrandGreen, markTint = Color(0xFF6CCB64),
        mark = EmbyIcons.Mark, logo = R.drawable.emby_logo, pageOfflineTitle = R.string.emby_page_offline_title,
        pageEmptyNote = R.string.emby_page_empty_note, pageServerRefused = R.string.emby_page_server_refused,
        removeServerBody = R.string.emby_remove_server_body,
    )
    else -> MediaServerBrand(
        provider = PLEX_PROVIDER_ID, name = R.string.media_server_plex, accent = Color(0xFFE5A00D), markTint = Color(0xFFE5A00D),
        mark = PlexIcons.Chevron, logo = R.drawable.plex_logo, pageOfflineTitle = R.string.plex_page_offline_title,
        pageEmptyNote = R.string.plex_page_empty_note, pageServerRefused = R.string.plex_page_server_refused,
        removeServerBody = R.string.media_server_remove_server_body,
    )
}

/** A provider's logo at [size]: its full-colour logo where it has one the app may show, its mark in its accent otherwise. */
@Composable
internal fun MediaServerLogo(provider: String?, size: Dp, modifier: Modifier = Modifier) {
    val brand = mediaServerBrand(provider)
    val logo = brand.logo
    if (logo != null) {
        Image(painterResource(logo), contentDescription = null, modifier = modifier.size(size))
    } else {
        androidx.compose.material3.Icon(brand.mark, contentDescription = null, tint = brand.accent, modifier = modifier.size(size))
    }
}

/** The provider's colour wash behind its page and lists. */
internal fun Modifier.mediaServerAmbientGlow(provider: String?): Modifier = when (provider) {
    JELLYFIN_PROVIDER_ID -> jellyfinAmbientGlow()
    EMBY_PROVIDER_ID -> embyAmbientGlow()
    else -> plexAmbientGlow()
}

/**
 * Emby's colour wash: green fields of light rising out of black. Held still, as the others are on a
 * television, and drawn behind the content only.
 */
internal fun Modifier.embyAmbientGlow(): Modifier = drawBehind {
    drawRect(Color.Black.copy(alpha = 0.55f))
    val w = size.width
    val h = size.height
    val radius = maxOf(w, h) * 0.55f
    fun glow(color: Color, x: Float, y: Float, scale: Float = 1f, strength: Float = 0.30f) {
        val center = Offset(x * w, y * h)
        drawCircle(
            brush = Brush.radialGradient(listOf(color.copy(alpha = strength), color.copy(alpha = 0f)), center = center, radius = radius * scale),
            radius = radius * scale,
            center = center,
        )
    }
    glow(Color(0xFF52B54B), 0.14f, 0.10f, 1.05f)
    glow(Color(0xFF14B8A6), 0.88f, 0.18f, 1f, 0.24f)
    glow(Color(0xFF84CC16), 0.22f, 0.72f, 0.9f, 0.21f)
    glow(Color(0xFF15803D), 0.84f, 0.84f, 0.95f)
}

/**
 * Emby's logo as a single-colour mark, the play symbol cut out of it, so it takes the same tint and
 * focus treatment as the other destination marks. The full-colour logo is R.drawable.emby_logo.
 */
internal object EmbyIcons {
    private const val SHAPE = "M97.1,132.4l26.5,26.5L0,282.5l132.4,132.4l26.5,-26.5L282.5,512l141.2,-141.2l-26.5,-26.5L512,229.5L379.6,97.1l-26.5,26.5L229.5,0z" +
        "M196.8,351.2V158.2L366,254.7L281.4,303z"

    val Mark: ImageVector by lazy {
        ImageVector.Builder(name = "EmbyMark", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 512f, viewportHeight = 512f)
            .addPath(addPathNodes(SHAPE), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.White))
            .build()
    }
}
