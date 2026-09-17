package com.streamdek.tv.nativeapp.data

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * Next Up in Continue Watching: once an episode is finished, the series card moves on to the
 * episode after it -- but only to that immediate episode, only once it has aired, and never over
 * an episode the viewer is still part-way through. The same rules as StreamDek Mobile's NextUp.kt.
 */

/** Strict eligibility: unknown dates never create a Continue Watching invitation. */
internal fun nextUpHasReleased(date: String?, now: Instant = Instant.now(), today: LocalDate = LocalDate.now()): Boolean {
    val value = date?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return if (value.length == 10) runCatching { !LocalDate.parse(value).isAfter(today) }.getOrDefault(false)
    else runCatching { !OffsetDateTime.parse(value).toInstant().isAfter(now) }.getOrDefault(false)
}

internal fun progressUpdatedAtMillis(value: String?): Long =
    value?.trim()?.takeIf { it.isNotEmpty() }?.let {
        runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
    } ?: 0L

/** Start of the air date in the viewer's zone, or the exact instant when the source gives one. */
internal fun nextUpReleaseMillis(date: String?, zone: ZoneId = ZoneId.systemDefault()): Long? {
    val value = date?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return if (value.length == 10) runCatching { LocalDate.parse(value).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull()
    else runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
}

/**
 * Continue Watching order: the latest meaningful moment for each card, newest first.
 *
 * A resume is as recent as its last playback. A Next Up card is as recent as the later of finishing
 * the previous episode and the next one airing, so a series the viewer caught up on last week moves
 * back to the front on the day its new episode lands. The same rule as StreamDek Mobile.
 */
internal fun continueWatchingRecency(item: ContinueWatchingItem, zone: ZoneId = ZoneId.systemDefault()): Long =
    maxOf(
        progressUpdatedAtMillis(item.updatedAt),
        if (item.nextUp) nextUpReleaseMillis(item.exactEpisode()?.airDate, zone) ?: 0L else 0L,
    )

/**
 * A tracking service's row that this account has since overruled for the same episode or film:
 * finished, marked unwatched, or removed. Trakt keeps a paused session after all three, so without
 * this a few minutes of an old start come back as "Resume" and hold the series back from Next Up.
 */
internal fun continueRowSupersededByProgress(item: ContinueWatchingItem, records: List<PlaybackProgressRecord>): Boolean {
    val identity = mediaIdentityOf(item.type, item.id, item.tmdbId)
    val episode = item.exactEpisode()
    val rowTime = progressUpdatedAtMillis(item.updatedAt)
    return records.any { record ->
        (record.hasStatus("completed") || record.hasStatus("unwatched") || record.hasStatus("dismissed")) &&
            record.seasonNumber == episode?.seasonNumber && record.episodeNumber == episode?.episodeNumber &&
            progressUpdatedAtMillis(record.updatedAt) >= rowTime &&
            sameMediaIdentity(mediaIdentityOf(record.entityType, record.entityId, record.tmdbId, record.imdbId), identity)
    }
}

private fun isSeriesRecord(record: PlaybackProgressRecord): Boolean =
    record.entityType?.trim()?.lowercase(Locale.US) in setOf("tv", "series", "show")

private fun PlaybackProgressRecord.hasStatus(value: String) = status.equals(value, ignoreCase = true)

internal fun nextUpSeriesKey(record: PlaybackProgressRecord): String =
    mediaIdentityOf("tv", record.entityId, record.tmdbId, record.imdbId).keys().firstOrNull() ?: "tv:raw:${record.entityId}"

/** Never skip a missing, watched or unaired episode to find something further ahead. */
internal fun immediateNextUpEpisode(
    season: Int,
    episode: Int,
    seasons: List<SeasonRef>,
    episodesBySeason: Map<Int, List<SeasonEpisode>>,
): EpisodeContext? {
    episodesBySeason[season].orEmpty().firstOrNull { it.episodeNumber == episode + 1 }?.let { return it.toContext(season) }
    val count = seasons.firstOrNull { it.seasonNumber == season }?.episodeCount ?: return null
    if (count <= 0 || episode != count) return null
    val nextSeason = seasons.filter { it.seasonNumber > season && it.seasonNumber > 0 }.minByOrNull { it.seasonNumber } ?: return null
    return episodesBySeason[nextSeason.seasonNumber].orEmpty().firstOrNull { it.episodeNumber == 1 }?.toContext(nextSeason.seasonNumber)
}

private fun SeasonEpisode.toContext(season: Int) = EpisodeContext(
    seasonNumber = season, episodeNumber = episodeNumber, title = name, overview = overview,
    still = still, runtime = runtime, airDate = airDate, tmdbEpisodeId = id,
)

/** The finished episode each series should advance from, newest series first. */
internal fun nextUpAnchors(records: List<PlaybackProgressRecord>): List<PlaybackProgressRecord> =
    records.filter { isSeriesRecord(it) && !it.entityId.isNullOrBlank() }
        .groupBy(::nextUpSeriesKey).values.mapNotNull { events ->
            // Reconcile each episode before choosing the series position. An explicit unwatched
            // write retires that episode's completion; it is not playback of a newer episode and
            // must not hide the invitation following the last completed episode (E4 -> unwatched E5).
            val current = events.groupBy { it.seasonNumber to it.episodeNumber }
                .map { (_, versions) -> versions.maxBy { progressUpdatedAtMillis(it.updatedAt) } }
            // A start abandoned within the first 1% is not somewhere to resume from, so it does not
            // retire Next Up either -- backing straight out of the episode must not empty the card.
            val latest = current.filter { it.hasStatus("dismissed") || (!it.hasStatus("unwatched") && (it.hasStatus("completed") || it.progress > 1.0)) }
                .maxWithOrNull(compareBy<PlaybackProgressRecord> { progressUpdatedAtMillis(it.updatedAt) }
                    .thenBy { it.seasonNumber ?: 0 }.thenBy { it.episodeNumber ?: 0 }) ?: return@mapNotNull null
            latest.takeIf { !it.hasStatus("dismissed") && (it.hasStatus("completed") || it.progress >= 95.0) &&
                it.seasonNumber != null && it.episodeNumber != null }
        }.sortedByDescending { progressUpdatedAtMillis(it.updatedAt) }

/** A newer explicit unwatched event overrides provider history, but never a dismissal. */
internal fun nextUpTargetIsWatched(latest: PlaybackProgressRecord?, providerWatched: Boolean): Boolean =
    latest?.hasStatus("dismissed") == true || (latest?.hasStatus("unwatched") != true &&
        (latest?.hasStatus("completed") == true || (latest?.progress ?: 0.0) >= 95.0 || providerWatched))

private fun continueSeriesKey(item: ContinueWatchingItem): String =
    mediaIdentityOf(item.type, item.id, item.tmdbId).keys().firstOrNull() ?: "${item.type}:${item.id}"

/**
 * Every unfinished position among the account's progress records, as resume rows.
 *
 * `/sync/library` caps its own Continue Watching list at 30, while the phone reads the progress
 * records themselves; building from the same records is what makes the two apps list the same titles.
 * A row with no position is not somewhere to resume from, which is the server's own rule too.
 */
internal fun unfinishedPositions(records: List<PlaybackProgressRecord>): List<ContinueWatchingItem> =
    records.filter { it.hasStatus("in-progress") && it.positionSec > 0.0 && !it.entityId.isNullOrBlank() }
        .map { record ->
            val isSeries = isSeriesRecord(record)
            ContinueWatchingItem(
                id = record.entityId.orEmpty(),
                tmdbId = record.tmdbId ?: 0,
                title = record.title?.takeIf { it.isNotBlank() } ?: "Untitled",
                type = if (isSeries) "tv" else record.entityType?.trim()?.lowercase(Locale.US) ?: "movie",
                poster = record.poster,
                backdrop = record.backdrop,
                description = record.description,
                year = record.year,
                progress = record.progress,
                positionSec = record.positionSec,
                durationSec = record.durationSec.takeIf { it > 0.0 },
                resumeAt = record.positionSec,
                episodeKey = record.episodeKey,
                seasonNumber = record.seasonNumber.takeIf { isSeries },
                episodeNumber = record.episodeNumber.takeIf { isSeries },
                updatedAt = record.updatedAt,
                lastDevice = record.lastDevice,
                lastPlatform = record.lastPlatform,
            )
        }

/**
 * One resume card per title, as on the phone: finished rows go, a series-level row gives way to an
 * episode-specific one, and the most recently played unfinished episode represents the series.
 */
internal fun oneResumeCardPerTitle(rows: List<ContinueWatchingItem>): List<ContinueWatchingItem> {
    val unfinished = rows.filter { (it.progress ?: 0.0) < 95.0 }
    val withEpisode = unfinished.filter { it.exactEpisode() != null }.mapTo(hashSetOf(), ::continueSeriesKey)
    return unfinished
        .filterNot { it.exactEpisode() == null && continueSeriesKey(it) in withEpisode && it.type.lowercase(Locale.US) != "movie" }
        .sortedWith(compareByDescending<ContinueWatchingItem> { progressUpdatedAtMillis(it.updatedAt) }.thenByDescending { it.progress ?: 0.0 })
        .distinctBy(::continueSeriesKey)
}

/**
 * One card per series, with Next Up where it applies.
 *
 * A real resume wins when it is the episode Next Up would open, or when it is at least as recent as
 * the completion behind Next Up. An older half-watched episode does not hold a series back after
 * the viewer finished a later one, and finished rows for that series give way to Next Up. The row
 * is then ordered by [continueWatchingRecency].
 */
internal fun mergeNextUpContinueWatching(
    rows: List<ContinueWatchingItem>,
    next: List<ContinueWatchingItem>,
): List<ContinueWatchingItem> {
    val resume = oneResumeCardPerTitle(rows)
    val nextBySeries = next.associateBy(::continueSeriesKey)
    val resumeWins = mutableSetOf<String>()
    resume.forEach { item ->
        val upNext = nextBySeries[continueSeriesKey(item)] ?: return@forEach
        val progress = item.progress ?: 0.0
        val episode = item.exactEpisode()
        if (progress > 0.0 && progress < 95.0 && (
                (episode?.seasonNumber == upNext.seasonNumber && episode?.episodeNumber == upNext.episodeNumber) ||
                    progressUpdatedAtMillis(item.updatedAt) >= progressUpdatedAtMillis(upNext.updatedAt))
        ) resumeWins += continueSeriesKey(item)
    }
    val kept = resume.filter { item ->
        val key = continueSeriesKey(item)
        key !in nextBySeries || key in resumeWins
    }
    return (kept + next.filterNot { continueSeriesKey(it) in resumeWins })
        .sortedByDescending { continueWatchingRecency(it) }
}

/** "Next up · S1 E5" or "Resume · S1 E4": the one line that tells the two kinds of card apart. */
internal fun continueWatchingCardSubtitle(item: ContinueWatchingItem, nextUpLabel: String, resumeLabel: String): String {
    val episode = item.exactEpisode()?.let { "S${it.seasonNumber} E${it.episodeNumber}" }
    return listOfNotNull(if (item.nextUp) nextUpLabel else resumeLabel, episode).joinToString(" · ")
}
