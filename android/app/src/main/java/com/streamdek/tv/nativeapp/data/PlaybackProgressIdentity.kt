package com.streamdek.tv.nativeapp.data

private val episodeKeyPattern = Regex("""^s0*(\d+)[^0-9]*e0*(\d+)$""", RegexOption.IGNORE_CASE)

/** Canonical key used by detail-page cards, regardless of the SyncDek response generation. */
fun PlaybackProgressRecord.compactEpisodeKey(): String? {
    val season = seasonNumber
    val episode = episodeNumber
    if (season != null && episode != null) return "s$season:e$episode"

    val match = episodeKey?.trim()?.let(episodeKeyPattern::matchEntire) ?: return null
    val parsedSeason = match.groupValues[1].toIntOrNull() ?: return null
    val parsedEpisode = match.groupValues[2].toIntOrNull() ?: return null
    return "s$parsedSeason:e$parsedEpisode"
}

fun PlaybackProgressRecord.episodeNumbers(): Pair<Int, Int>? {
    val compact = compactEpisodeKey() ?: return null
    val match = Regex("""^s(\d+):e(\d+)$""").matchEntire(compact) ?: return null
    return (match.groupValues[1].toIntOrNull() ?: return null) to
        (match.groupValues[2].toIntOrNull() ?: return null)
}
