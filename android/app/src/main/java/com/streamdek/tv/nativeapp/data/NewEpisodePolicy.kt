package com.streamdek.tv.nativeapp.data

internal fun isNewEpisodeWatched(
    tmdbId: Int,
    seasonNumber: Int,
    episodeNumber: Int,
    providerWatchedKeys: Set<String>,
    progressRecords: List<PlaybackProgressRecord>,
): Boolean {
    val latest = progressRecords
        .filter {
            it.entityType.equals("tv", ignoreCase = true) &&
                (it.tmdbId == tmdbId || it.entityId?.toIntOrNull() == tmdbId) &&
                it.seasonNumber == seasonNumber && it.episodeNumber == episodeNumber
        }
        .maxByOrNull { it.updatedAt.orEmpty() }
    return when (latest?.status?.lowercase()) {
        "unwatched" -> false
        "completed" -> true
        else -> newEpisodeWatchedKey(tmdbId, seasonNumber, episodeNumber) in providerWatchedKeys
    }
}

private fun newEpisodeWatchedKey(tmdbId: Int, seasonNumber: Int, episodeNumber: Int): String =
    "tv:$tmdbId:s$seasonNumber:e$episodeNumber"
