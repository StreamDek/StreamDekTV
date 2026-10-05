package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesEpisodeStandingTest {
    @Test
    fun completedAndProviderWatchedEpisodesAreWatched() {
        val standing = getSeriesEpisodeStanding(
            progressEvents = listOf(SeriesProgressEvent(1, 1, progress = 100.0, status = "completed", updatedAtMillis = 10)),
            providerWatchedKeys = setOf("s1:e2"),
        )

        assertEquals(setOf("s1:e1", "s1:e2"), standing.watchedEpisodeKeys)
        assertTrue(standing.progressFractions.isEmpty())
    }

    @Test
    fun partWatchedEpisodeCarriesItsFraction() {
        val standing = getSeriesEpisodeStanding(
            listOf(SeriesProgressEvent(2, 3, positionSec = 600.0, progress = 40.0, updatedAtMillis = 10)),
        )

        assertEquals(0.4f, standing.progressFractions.getValue("s2:e3"), 0.001f)
        assertFalse("s2:e3" in standing.watchedEpisodeKeys)
    }

    @Test
    fun newestEventForAnEpisodeWins() {
        val standing = getSeriesEpisodeStanding(
            listOf(
                SeriesProgressEvent(1, 1, progress = 100.0, status = "completed", updatedAtMillis = 10),
                SeriesProgressEvent(1, 1, progress = 0.0, status = "unwatched", updatedAtMillis = 20),
                SeriesProgressEvent(1, 2, progress = 30.0, updatedAtMillis = 10),
                SeriesProgressEvent(1, 2, progress = 100.0, status = "completed", updatedAtMillis = 20),
            ),
        )

        assertEquals(setOf("s1:e2"), standing.watchedEpisodeKeys)
        assertTrue(standing.progressFractions.isEmpty())
    }

    @Test
    fun barelyStartedAndNearlyFinishedAreNotPartWatched() {
        val standing = getSeriesEpisodeStanding(
            listOf(
                SeriesProgressEvent(1, 1, progress = 0.5, updatedAtMillis = 10),
                SeriesProgressEvent(1, 2, progress = 97.0, updatedAtMillis = 10),
            ),
        )

        assertTrue(standing.progressFractions.isEmpty())
    }
}
