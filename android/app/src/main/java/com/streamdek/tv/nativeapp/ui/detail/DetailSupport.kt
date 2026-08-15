package com.streamdek.tv.nativeapp.ui.detail

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.SeasonEpisode
import com.streamdek.tv.nativeapp.data.SeasonDetail
import com.streamdek.tv.nativeapp.data.SeasonRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

internal sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Ready(val detail: MediaDetail) : DetailUiState
    data class Error(val message: String) : DetailUiState
}

/** Two tones pulled from the artwork, used to tint the backdrop scrims. */
internal data class AmbientBackdropPalette(
    val leftGlow: Color,
    val rightGlow: Color,
    val accentGlow: Color,
)

internal fun SeasonEpisode.toEpisodeContext(seasonNumber: Int): EpisodeContext =
    EpisodeContext(seasonNumber, episodeNumber, name, overview, still, runtime, airDate, id)

/**
 * One episode plus the season it came from.
 *
 * The episode row is no longer one season's worth of cards: it keeps going into the next season as
 * the viewer reaches the end of the current one, so every card has to carry its own season rather
 * than inheriting it from a single selected value.
 */
internal data class SeasonEpisodeEntry(
    val seasonNumber: Int,
    val episode: SeasonEpisode,
)

/** Key used to test an episode against the watched set, which spans every loaded season. */
internal fun watchedEpisodeKey(seasonNumber: Int, episodeNumber: Int): String = "s$seasonNumber:e$episodeNumber"

/** Converts account-wide Trakt history keys into the compact keys used by one series page. */
internal fun seriesWatchedEpisodeKeys(historyKeys: Set<String>, mediaId: String): Set<String> {
    val prefix = "tv:$mediaId:"
    return historyKeys.mapNotNull { key ->
        key.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.takeIf { it.matches(Regex("s\\d+:e\\d+")) }
    }.toSet()
}

/** Seasons whose complete episode set is present in watched history. */
internal fun watchedSeasonNumbers(
    seasons: List<SeasonRef>,
    loadedSeasons: List<SeasonDetail>,
    watchedEpisodeKeys: Set<String>,
): Set<Int> = seasons.mapNotNull { season ->
    val loadedNumbers = loadedSeasons.firstOrNull { it.seasonNumber == season.seasonNumber }
        ?.episodes.orEmpty().map { it.episodeNumber }
    val episodeNumbers = loadedNumbers.ifEmpty {
        if (season.episodeCount > 0) (1..season.episodeCount).toList() else emptyList()
    }
    season.seasonNumber.takeIf {
        episodeNumbers.isNotEmpty() && episodeNumbers.all { episode ->
            watchedEpisodeKey(season.seasonNumber, episode) in watchedEpisodeKeys
        }
    }
}.toSet()

internal fun isEpisodeReleased(airDate: String?): Boolean {
    val parsed = airDate?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: return true
    return !parsed.isAfter(LocalDate.now())
}

internal fun formatTime(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val remainder = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder) else "%d:%02d".format(minutes, remainder)
}

internal fun formatRuntime(minutes: Int): String {
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours > 0 && remainder > 0 -> "${hours}h ${remainder}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

internal fun formatReleaseDate(raw: String): String = try {
    LocalDate.parse(raw).format(DateTimeFormatter.ofPattern("d MMM yyyy"))
} catch (_: DateTimeParseException) {
    raw
}

/**
 * Which episode a play press should act on.
 *
 * A part-watched episode wins, because resuming is almost always what the button is for. Otherwise
 * it is whatever the episode list has highlighted, and for a film there is no episode at all.
 */
internal fun playbackEpisodeContext(
    detail: MediaDetail,
    progressFraction: Float?,
    resumeEpisodeContext: EpisodeContext?,
    selectedEpisode: EpisodeContext?,
): EpisodeContext? {
    if (detail.type != "tv") return null
    val partWatched = (progressFraction ?: 0f) > 0f
    return if (partWatched && resumeEpisodeContext != null) resumeEpisodeContext else selectedEpisode
}

/**
 * Pulls two dominant tones out of the artwork so the backdrop scrims pick up the title's own
 * colours instead of a fixed grey. Decoded at thumbnail size — the palette only needs the broad
 * strokes, and a full-size decode on a Fire TV Stick is a real stall.
 */
internal suspend fun extractAmbientPalette(
    context: android.content.Context,
    imageUrl: String,
): AmbientBackdropPalette = withContext(Dispatchers.IO) {
    val request = ImageRequest.Builder(context)
        .data(imageUrl)
        .allowHardware(false)
        .crossfade(false)
        .size(320, 180)
        .build()
    val result = context.imageLoader.execute(request).drawable
    val bitmap = requireNotNull(result?.toBitmap(config = Bitmap.Config.ARGB_8888)) {
        "Could not decode ambient artwork"
    }
    val palette = Palette.Builder(bitmap).clearFilters().maximumColorCount(12).generate()

    val fallback = Color(0xFF1A2633)
    val swatches = palette.swatches.sortedByDescending { it.population }
    val primary = swatches.firstOrNull()?.rgb?.let(::Color) ?: fallback
    val secondary = swatches.drop(1)
        .firstOrNull { colorDistance(primary, Color(it.rgb)) > 0.12f }?.rgb?.let(::Color)
        ?: swatches.getOrNull(1)?.rgb?.let(::Color)
        ?: primary

    AmbientBackdropPalette(
        leftGlow = primary.ambientize(boost = 1.04f),
        rightGlow = secondary.ambientize(),
        accentGlow = lerpColor(primary, secondary, 0.5f).ambientize(boost = 1.10f),
    )
}

private fun Color.ambientize(boost: Float = 1f): Color {
    val lightMix = if (luminance() < 0.24f) 0.20f else 0.10f
    return lerpColor(this, Color.White, lightMix)
        .let { lerpColor(it, Color.Black, 0.42f) }
        .copy(alpha = 1f)
        .saturate(boost)
}

private fun Color.saturate(factor: Float): Color {
    val grey = (red + green + blue) / 3f
    return Color(
        red = (grey + (red - grey) * factor).coerceIn(0f, 1f),
        green = (grey + (green - grey) * factor).coerceIn(0f, 1f),
        blue = (grey + (blue - grey) * factor).coerceIn(0f, 1f),
        alpha = alpha,
    )
}

private fun lerpColor(start: Color, end: Color, fraction: Float): Color {
    val clamped = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * clamped,
        green = start.green + (end.green - start.green) * clamped,
        blue = start.blue + (end.blue - start.blue) * clamped,
        alpha = start.alpha + (end.alpha - start.alpha) * clamped,
    )
}

private fun colorDistance(a: Color, b: Color): Float {
    val dr = a.red - b.red
    val dg = a.green - b.green
    val db = a.blue - b.blue
    return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble()).toFloat()
}
