package com.streamdek.tv.nativeapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.streamdek.tv.nativeapp.data.AppPreferences

data class TvExperienceSettings(
    val reducedMotion: Boolean = false,
    val highContrast: Boolean = false,
    val largeText: Boolean = false,
    val denseCards: Boolean = false,
    val animationScale: Float = 1f,
    val gridColumns: Int = 5,
    val backgroundBlur: Boolean = true,
)

val LocalTvExperienceSettings = staticCompositionLocalOf { TvExperienceSettings() }

/** Accent shown in the theme picker; kept beside the actual schemes so the swatch cannot drift. */
internal fun streamDekThemeAccent(themeKey: String?): Color = when (themeKey) {
    "streamdek" -> Color(0xFFF0BA66)
    "carbon-gold" -> Color(0xFFE7B75D)
    "frost-neon" -> Color(0xFF7CE9FF)
    "ember-red" -> Color(0xFFFF8C6A)
    "aurora-green" -> Color(0xFF82F2BF)
    "violet-pulse" -> Color(0xFFC6A3FF)
    else -> Color(0xFF7FB7FF)
}

private fun streamDekColorScheme(themeKey: String?, highContrast: Boolean): ColorScheme {
    val scheme = when (themeKey) {
        "streamdek" -> darkColorScheme(primary = Color(0xFFF0BA66), onPrimary = Color(0xFF17120B), secondary = Color(0xFF2A2D36), onSecondary = Color(0xFFF5F1E8), surface = Color(0xFF090A0D), onSurface = Color(0xFFF5F1E8), background = Color(0xFF040404), onBackground = Color(0xFFF5F1E8))
        "carbon-gold" -> darkColorScheme(primary = Color(0xFFE7B75D), onPrimary = Color(0xFF15110A), secondary = Color(0xFF332A1B), onSecondary = Color(0xFFF7F0E1), surface = Color(0xFF0B0A08), onSurface = Color(0xFFF7F0E1), background = Color(0xFF050505), onBackground = Color(0xFFF7F0E1))
        "frost-neon" -> darkColorScheme(primary = Color(0xFF7CE9FF), onPrimary = Color(0xFF071419), secondary = Color(0xFF15303A), onSecondary = Color(0xFFE8FBFF), surface = Color(0xFF071014), onSurface = Color(0xFFE8FBFF), background = Color(0xFF03080B), onBackground = Color(0xFFE8FBFF))
        "ember-red" -> darkColorScheme(primary = Color(0xFFFF8C6A), onPrimary = Color(0xFF1D0C08), secondary = Color(0xFF3A1914), onSecondary = Color(0xFFFFEEE8), surface = Color(0xFF140907), onSurface = Color(0xFFFFEEE8), background = Color(0xFF0B0403), onBackground = Color(0xFFFFEEE8))
        "aurora-green" -> darkColorScheme(primary = Color(0xFF82F2BF), onPrimary = Color(0xFF08150F), secondary = Color(0xFF173127), onSecondary = Color(0xFFEBFFF5), surface = Color(0xFF07110D), onSurface = Color(0xFFEBFFF5), background = Color(0xFF030805), onBackground = Color(0xFFEBFFF5))
        "violet-pulse" -> darkColorScheme(primary = Color(0xFFC6A3FF), onPrimary = Color(0xFF12091D), secondary = Color(0xFF29193C), onSecondary = Color(0xFFF5EDFF), surface = Color(0xFF0D0814), onSurface = Color(0xFFF5EDFF), background = Color(0xFF05030A), onBackground = Color(0xFFF5EDFF))
        else -> darkColorScheme(primary = Color(0xFF7FB7FF), onPrimary = Color(0xFF08111D), secondary = Color(0xFF1A2D42), onSecondary = Color(0xFFF1F7FF), surface = Color(0xFF071019), onSurface = Color(0xFFF1F7FF), background = Color(0xFF03070C), onBackground = Color(0xFFF1F7FF))
    }
    if (!highContrast) return scheme
    return scheme.copy(onBackground = Color.White, onSurface = Color.White, primary = scheme.primary.copy(alpha = 1f), surface = Color(0xFF050608), background = Color.Black)
}

@Composable
fun StreamDekTvTheme(appPreferences: AppPreferences? = null, content: @Composable () -> Unit) {
    val experience = TvExperienceSettings(
        reducedMotion = appPreferences?.reducedMotion == true,
        highContrast = appPreferences?.highContrast == true,
        largeText = appPreferences?.largeText == true,
        denseCards = appPreferences?.cardDensity == "compact" || appPreferences?.compactMode == true,
        animationScale = when (appPreferences?.animationSpeed) { "slow" -> 1.35f; "fast" -> 0.72f; else -> 1f },
        gridColumns = (appPreferences?.gridSize ?: 5).coerceIn(4, 7),
        backgroundBlur = appPreferences?.backgroundBlur != false,
    )
    val density = LocalDensity.current
    val fontScale = if (experience.largeText) density.fontScale * 1.14f else density.fontScale
    CompositionLocalProvider(LocalTvExperienceSettings provides experience, LocalDensity provides Density(density.density, fontScale)) {
        MaterialTheme(colorScheme = streamDekColorScheme(appPreferences?.theme, experience.highContrast), content = content)
    }
}
