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
