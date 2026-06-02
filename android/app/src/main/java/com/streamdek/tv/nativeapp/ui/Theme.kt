package com.streamdek.tv.nativeapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.darkColorScheme

private fun streamDekColorScheme(themeKey: String?): ColorScheme = when (themeKey) {
    "streamdek" -> darkColorScheme(
        primary = Color(0xFFF0BA66),
        onPrimary = Color(0xFF17120B),
        secondary = Color(0xFF2A2D36),
        onSecondary = Color(0xFFF5F1E8),
        surface = Color(0xFF090A0D),
        onSurface = Color(0xFFF5F1E8),
        background = Color(0xFF040404),
        onBackground = Color(0xFFF5F1E8),
    )
    "carbon-gold" -> darkColorScheme(
        primary = Color(0xFFE7B75D),
        onPrimary = Color(0xFF15110A),
        secondary = Color(0xFF332A1B),
        onSecondary = Color(0xFFF7F0E1),
        surface = Color(0xFF0B0A08),
        onSurface = Color(0xFFF7F0E1),
        background = Color(0xFF050505),
        onBackground = Color(0xFFF7F0E1),
    )
    "frost-neon" -> darkColorScheme(
        primary = Color(0xFF7CE9FF),
        onPrimary = Color(0xFF071419),
        secondary = Color(0xFF15303A),
        onSecondary = Color(0xFFE8FBFF),
        surface = Color(0xFF071014),
        onSurface = Color(0xFFE8FBFF),
        background = Color(0xFF03080B),
        onBackground = Color(0xFFE8FBFF),
    )
    "ember-red" -> darkColorScheme(
        primary = Color(0xFFFF8C6A),
        onPrimary = Color(0xFF1D0C08),
        secondary = Color(0xFF3A1914),
        onSecondary = Color(0xFFFFEEE8),
        surface = Color(0xFF140907),
        onSurface = Color(0xFFFFEEE8),
        background = Color(0xFF0B0403),
        onBackground = Color(0xFFFFEEE8),
    )
    "aurora-green" -> darkColorScheme(
        primary = Color(0xFF82F2BF),
        onPrimary = Color(0xFF08150F),
        secondary = Color(0xFF173127),
        onSecondary = Color(0xFFEBFFF5),
        surface = Color(0xFF07110D),
        onSurface = Color(0xFFEBFFF5),
        background = Color(0xFF030805),
        onBackground = Color(0xFFEBFFF5),
    )
    "violet-pulse" -> darkColorScheme(
        primary = Color(0xFFC6A3FF),
        onPrimary = Color(0xFF12091D),
        secondary = Color(0xFF29193C),
        onSecondary = Color(0xFFF5EDFF),
        surface = Color(0xFF0D0814),
        onSurface = Color(0xFFF5EDFF),
        background = Color(0xFF05030A),
        onBackground = Color(0xFFF5EDFF),
    )
    else -> darkColorScheme(
        primary = Color(0xFF7FB7FF),
        onPrimary = Color(0xFF08111D),
        secondary = Color(0xFF1A2D42),
        onSecondary = Color(0xFFF1F7FF),
        surface = Color(0xFF071019),
        onSurface = Color(0xFFF1F7FF),
        background = Color(0xFF03070C),
        onBackground = Color(0xFFF1F7FF),
    )
}

@Composable
fun StreamDekTvTheme(
    themeKey: String? = null,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = streamDekColorScheme(themeKey),
        content = content,
    )
}
