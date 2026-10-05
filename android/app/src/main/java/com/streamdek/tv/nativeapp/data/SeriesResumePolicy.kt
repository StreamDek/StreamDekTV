package com.streamdek.tv.nativeapp.data

data class SeriesEpisodeSlot(val seasonNumber: Int, val episodeNumber: Int)

data class SeriesProgressEvent(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val positionSec: Double = 0.0,
    val progress: Double = 0.0,
    val status: String = "in-progress",
    val updatedAtMillis: Long = 0L,
)

data class SeriesResumeState(
    val target: SeriesEpisodeSlot?,
    val watchedEpisodeKeys: Set<String>,
    val resumePositionSec: Double = 0.0,
)

private fun resumeWatchedKey(seasonNumber: Int, episodeNumber: Int): String = "s$seasonNumber:e$episodeNumber"

/** One deterministic continuation rule for detail selection, watched controls, and Play. */
fun getSeriesResumeState(
    episodes: List<SeriesEpisodeSlot>,
    progressEvents: List<SeriesProgressEvent>,
    providerWatchedKeys: Set<String> = emptySet(),
): SeriesResumeState {
    val ordered = episodes.distinct().sortedWith(compareBy(SeriesEpisodeSlot::seasonNumber, SeriesEpisodeSlot::episodeNumber))
    if (ordered.isEmpty()) return SeriesResumeState(null, providerWatchedKeys)

    val latestByEpisode = progressEvents.groupBy { it.seasonNumber to it.episodeNumber }
        .mapValues { (_, events) -> events.maxByOrNull(SeriesProgressEvent::updatedAtMillis)!! }
    val explicitUnwatched = latestByEpisode.values.filter { it.status == "unwatched" }
        .map { resumeWatchedKey(it.seasonNumber, it.episodeNumber) }.toSet()
    val watched = (providerWatchedKeys + latestByEpisode.values.filter { it.status == "completed" }
        .map { resumeWatchedKey(it.seasonNumber, it.episodeNumber) }) - explicitUnwatched

    val latestEvent = latestByEpisode.values.maxByOrNull(SeriesProgressEvent::updatedAtMillis)
    if (latestEvent != null && latestEvent.status in setOf("in-progress", "unwatched")) {
        val target = SeriesEpisodeSlot(latestEvent.seasonNumber, latestEvent.episodeNumber)
            .takeIf { it in ordered }
        if (target != null) {
            return SeriesResumeState(
                target = target,
                watchedEpisodeKeys = watched,
                resumePositionSec = latestEvent.positionSec.takeIf { latestEvent.status == "in-progress" } ?: 0.0,
            )
        }
    }

    val highestWatched = ordered.indexOfLast { resumeWatchedKey(it.seasonNumber, it.episodeNumber) in watched }
    val target = when {
        highestWatched < 0 -> ordered.first()
        highestWatched + 1 < ordered.size -> ordered[highestWatched + 1]
        else -> ordered.last()
    }
    return SeriesResumeState(target, watched)
}

/** Where a series stands for a list of its episodes: what is finished, and how far the rest have got. */
data class SeriesEpisodeStanding(
    /** Watched episodes, in the same `s1:e4` keys [SeriesResumeState.watchedEpisodeKeys] uses. */
    val watchedEpisodeKeys: Set<String> = emptySet(),
    /** How far in the viewer got, 0 to 1, for each episode stopped part way. Same keys. */
    val progressFractions: Map<String, Float> = emptyMap(),
)

/**
 * The per-episode view of the same events [getSeriesResumeState] reduces to one target.
 *
 * Deliberately the same rule for watched - the newest event for an episode wins, an explicit
 * "unwatched" retires a completion - so an episode list can never disagree with the resume point
 * about which episodes are done. Part watched is what the rest of the app calls it: past the first
 * moments and short of the end.
 */
fun getSeriesEpisodeStanding(
    progressEvents: List<SeriesProgressEvent>,
    providerWatchedKeys: Set<String> = emptySet(),
): SeriesEpisodeStanding {
    val latestByEpisode = progressEvents.groupBy { it.seasonNumber to it.episodeNumber }
        .mapValues { (_, events) -> events.maxByOrNull(SeriesProgressEvent::updatedAtMillis)!! }
    val explicitUnwatched = latestByEpisode.values.filter { it.status == "unwatched" }
        .map { resumeWatchedKey(it.seasonNumber, it.episodeNumber) }.toSet()
    val watched = (providerWatchedKeys + latestByEpisode.values.filter { it.status == "completed" }
        .map { resumeWatchedKey(it.seasonNumber, it.episodeNumber) }) - explicitUnwatched
    val fractions = latestByEpisode.values
        .filter { it.status == "in-progress" && it.progress > 1.0 && it.progress < 95.0 }
        .associate { resumeWatchedKey(it.seasonNumber, it.episodeNumber) to (it.progress / 100.0).toFloat() }
        .filterKeys { it !in watched }
    return SeriesEpisodeStanding(watched, fractions)
}

fun seriesEpisodeSlots(seasons: List<SeasonRef>): List<SeriesEpisodeSlot> = seasons.flatMap { season ->
    (1..season.episodeCount.coerceAtLeast(0)).map { episode -> SeriesEpisodeSlot(season.seasonNumber, episode) }
}
