package com.streamdek.tv.nativeapp.data

import java.time.LocalDate

/** A season tab represents something the viewer can actually open and watch. */
fun isSeasonAvailable(season: SeasonRef, today: LocalDate = LocalDate.now()): Boolean {
    if (season.seasonNumber <= 0 || season.episodeCount <= 0) return false
    val firstAirDate = season.airDate?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return firstAirDate == null || !firstAirDate.isAfter(today)
}

fun availableSeasons(seasons: List<SeasonRef>, today: LocalDate = LocalDate.now()): List<SeasonRef> =
    seasons.filter { isSeasonAvailable(it, today) }
