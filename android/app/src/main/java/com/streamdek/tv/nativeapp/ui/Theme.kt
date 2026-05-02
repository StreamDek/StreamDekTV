package com.streamdek.tv.nativeapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val StreamDekColors = darkColorScheme(
    primary = Color(0xFFF0BA66),
    onPrimary = Color(0xFF17120B),
    secondary = Color(0xFF2A2D36),
    onSecondary = Color(0xFFF5F1E8),
    surface = Color(0xFF090A0D),
    onSurface = Color(0xFFF5F1E8),
    background = Color(0xFF040404),
    onBackground = Color(0xFFF5F1E8),
)

@Composable
fun StreamDekTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StreamDekColors,
        content = content,
    )
}
