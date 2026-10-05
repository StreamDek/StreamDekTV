package com.streamdek.tv.nativeapp.ui.plex

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The Plex colour wash: purple, blue, red and green fields of light behind the Plex page and its
 * lists, the same four the phone uses. Held still on a TV - a full-screen wash redrawn every frame
 * is GPU time a streaming box needs for scrolling artwork - and drawn behind the content only.
 */
internal fun Modifier.plexAmbientGlow(): Modifier = drawBehind {
    val w = size.width
    val h = size.height
    val radius = maxOf(w, h) * 0.55f
    fun glow(color: Color, x: Float, y: Float, scale: Float = 1f) {
        val center = Offset(x * w, y * h)
        drawCircle(
            brush = Brush.radialGradient(listOf(color.copy(alpha = AmbientStrength), color.copy(alpha = 0f)), center = center, radius = radius * scale),
            radius = radius * scale,
            center = center,
        )
    }
    glow(PlexAmbientPurple, 0.12f, 0.08f, 1.05f)
    glow(PlexAmbientBlue, 0.90f, 0.14f)
    glow(PlexAmbientRed, 0.20f, 0.62f, 0.9f)
    glow(PlexAmbientGreen, 0.84f, 0.80f, 0.95f)
}

private const val AmbientStrength = 0.30f
private val PlexAmbientPurple = Color(0xFF8B5CF6)
private val PlexAmbientBlue = Color(0xFF3B82F6)
private val PlexAmbientRed = Color(0xFFEF4444)
private val PlexAmbientGreen = Color(0xFF22C55E)

/**
 * Jellyfin's colour wash: orange and red fields of light rising out of black. The page is first
 * taken down toward black, so the two colours glow rather than tint grey. Held still, as Plex's is.
 */
internal fun Modifier.jellyfinAmbientGlow(): Modifier = drawBehind {
    drawRect(Color.Black.copy(alpha = 0.55f))
    val w = size.width
    val h = size.height
    val radius = maxOf(w, h) * 0.55f
    fun glow(color: Color, x: Float, y: Float, scale: Float = 1f, strength: Float = AmbientStrength) {
        val center = Offset(x * w, y * h)
        drawCircle(
            brush = Brush.radialGradient(listOf(color.copy(alpha = strength), color.copy(alpha = 0f)), center = center, radius = radius * scale),
            radius = radius * scale,
            center = center,
        )
    }
    glow(JellyfinAmbientOrange, 0.14f, 0.10f, 1.05f)
    glow(JellyfinAmbientRed, 0.88f, 0.18f)
    glow(JellyfinAmbientEmber, 0.22f, 0.72f, 0.9f, AmbientStrength * 0.8f)
    glow(JellyfinAmbientCrimson, 0.84f, 0.84f, 0.95f)
}

private val JellyfinAmbientOrange = Color(0xFFF97316)
private val JellyfinAmbientRed = Color(0xFFEF4444)
private val JellyfinAmbientEmber = Color(0xFFEA580C)
private val JellyfinAmbientCrimson = Color(0xFFB91C1C)

/**
 * StreamDek Fuse's colour wash: the viewer's accent colour with a cool blue and a violet beside it
 * and a warm ember low down - several sources of light meeting on one page, which is what the Fuse
 * is. The accent leads, so the page follows the theme rather than fighting it. Held still, as the
 * other two are, and drawn behind the content only.
 */
internal fun Modifier.fuseAmbientGlow(accent: Color): Modifier = drawBehind {
    drawRect(Color.Black.copy(alpha = 0.35f))
    val w = size.width
    val h = size.height
    val radius = maxOf(w, h) * 0.55f
    fun glow(color: Color, x: Float, y: Float, scale: Float = 1f, strength: Float = AmbientStrength) {
        val center = Offset(x * w, y * h)
        drawCircle(
            brush = Brush.radialGradient(listOf(color.copy(alpha = strength), color.copy(alpha = 0f)), center = center, radius = radius * scale),
            radius = radius * scale,
            center = center,
        )
    }
    glow(accent, 0.10f, 0.06f, 1.1f, AmbientStrength * 1.1f)
    glow(FuseAmbientBlue, 0.90f, 0.12f)
    glow(FuseAmbientViolet, 0.86f, 0.82f, 0.95f, AmbientStrength * 0.9f)
    glow(FuseAmbientEmber, 0.18f, 0.78f, 0.85f, AmbientStrength * 0.55f)
}

private val FuseAmbientBlue = Color(0xFF38BDF8)
private val FuseAmbientViolet = Color(0xFFA855F7)
private val FuseAmbientEmber = Color(0xFFF59E0B)
