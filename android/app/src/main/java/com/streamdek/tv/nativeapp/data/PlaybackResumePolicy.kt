package com.streamdek.tv.nativeapp.data

/** Chooses a position only when it belongs to the exact movie/episode being loaded. */
internal fun contentScopedResumePosition(
    mediaType: String,
    explicitPosition: Double?,
    exactProgressPosition: Double?,
    continuePosition: Double?,
    continueSeason: Int?,
    continueEpisode: Int?,
    targetSeason: Int?,
    targetEpisode: Int?,
): Double? {
    explicitPosition?.takeIf { it > 0.0 }?.let { return it }
    exactProgressPosition?.takeIf { it > 0.0 }?.let { return it }
    if (mediaType == "tv" && (continueSeason != targetSeason || continueEpisode != targetEpisode)) return null
    return continuePosition?.takeIf { it > 0.0 }
}
