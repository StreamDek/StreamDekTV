package com.streamdek.tv.nativeapp.data

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextUpPolicyTest {
    private val seasons = listOf(SeasonRef(1, "One", 2), SeasonRef(2, "Two", 3))
    private fun ep(e: Int, date: String? = "2020-01-01") = SeasonEpisode(id = e, episodeNumber = e, name = "E$e", airDate = date)
    private fun at(minute: Int) = "2026-09-17T10:%02d:00Z".format(minute)
    private fun record(e: Int, minute: Int = 0, status: String = "completed", progress: Double = 100.0, s: Int = 1) = PlaybackProgressRecord(
        positionSec = 0.0, durationSec = 0.0, progress = progress, seasonNumber = s, episodeNumber = e,
        updatedAt = at(minute), status = status, entityType = "tv", entityId = "95350", tmdbId = 95350,
    )
    private fun card(e: Int, minute: Int, progress: Double? = null, next: Boolean = false) = ContinueWatchingItem(
        id = "95350", tmdbId = 95350, title = "Lanterns", type = "tv", progress = progress,
        seasonNumber = 1, episodeNumber = e, updatedAt = at(minute), nextUp = next,
    )

    @Test fun `only the immediate episode, across a season boundary`() {
        assertEquals(2, immediateNextUpEpisode(1, 1, seasons, mapOf(1 to listOf(ep(2))))?.episodeNumber)
        val boundary = immediateNextUpEpisode(1, 2, seasons, mapOf(1 to listOf(ep(1), ep(2)), 2 to listOf(ep(1))))
        assertEquals(2, boundary?.seasonNumber)
        assertEquals(1, boundary?.episodeNumber)
        assertNull(immediateNextUpEpisode(1, 1, seasons, mapOf(2 to listOf(ep(1)))))
        assertNull(immediateNextUpEpisode(2, 3, seasons, mapOf(2 to listOf(ep(3)))))
    }

    @Test fun `future, unknown and malformed dates never qualify`() {
        val now = Instant.parse("2026-09-17T12:00:00Z")
        val today = LocalDate.parse("2026-09-17")
        listOf(null, "", "soon", "2026-02-30", "2026-09-18", "2026-09-17T13:00:00Z").forEach {
            assertFalse("unexpected release: $it", nextUpHasReleased(it, now, today))
        }
        assertTrue(nextUpHasReleased("2026-09-13", now, today))
        assertTrue(nextUpHasReleased("2026-09-17", now, today))
    }

    @Test fun `completed episode anchors, partial and unwatched do not`() {
        assertEquals(4, nextUpAnchors(listOf(record(4))).single().episodeNumber)
        assertTrue(nextUpAnchors(listOf(record(4), record(5, 1, "in-progress", 20.0))).isEmpty())
        assertEquals(4, nextUpAnchors(listOf(record(4), record(5, 1, "unwatched", 0.0))).single().episodeNumber)
        assertTrue(nextUpAnchors(listOf(record(4), record(5, 1, "dismissed", 0.0))).isEmpty())
        assertTrue(nextUpAnchors(listOf(record(4, status = "in-progress", progress = 94.0))).isEmpty())
    }

    @Test fun `backing out of the next episode keeps Next Up`() {
        assertEquals(4, nextUpAnchors(listOf(record(4), record(5, 1, "in-progress", 0.5))).single().episodeNumber)
    }

    @Test fun `explicit unwatched overrides provider history but dismissal hides`() {
        assertTrue(nextUpTargetIsWatched(null, providerWatched = true))
        assertFalse(nextUpTargetIsWatched(record(5, status = "unwatched", progress = 0.0), providerWatched = true))
        assertTrue(nextUpTargetIsWatched(record(5, status = "dismissed", progress = 0.0), providerWatched = false))
        assertTrue(nextUpTargetIsWatched(record(5), providerWatched = false))
        assertFalse(nextUpTargetIsWatched(null, providerWatched = false))
    }

    @Test fun `one card per series, and a current resume wins`() {
        val next = card(5, 10, next = true)
        assertEquals(listOf(next), mergeNextUpContinueWatching(listOf(card(4, 10, progress = 100.0)), listOf(next)))
        assertEquals(listOf(next), mergeNextUpContinueWatching(listOf(card(2, 1, progress = 40.0)), listOf(next)))
        val newer = card(2, 20, progress = 40.0)
        assertEquals(listOf(newer), mergeNextUpContinueWatching(listOf(newer), listOf(next)))
        val sameEpisode = card(5, 1, progress = 30.0)
        assertEquals(listOf(sameEpisode), mergeNextUpContinueWatching(listOf(sameEpisode), listOf(next)))
    }

    @Test fun `row is ordered by the latest playback, undated rows last`() {
        val other = card(1, 30, progress = 10.0).copy(id = "1", tmdbId = 1)
        val older = card(1, 5, progress = 10.0).copy(id = "2", tmdbId = 2)
        val undated = card(1, 0, progress = 10.0).copy(id = "3", tmdbId = 3, updatedAt = null)
        val next = card(5, 10, next = true)
        assertEquals(listOf(other, next, older, undated), mergeNextUpContinueWatching(listOf(undated, older, other), listOf(next)))
        assertEquals(listOf(other, older), mergeNextUpContinueWatching(listOf(older, other), emptyList()))
    }

    @Test fun `a newly aired next episode moves its series forward`() {
        val zone = java.time.ZoneId.of("Europe/London")
        val airedToday = card(5, 0, next = true).copy(
            updatedAt = "2026-09-01T10:00:00Z",
            episode = EpisodeContext(seasonNumber = 1, episodeNumber = 5, airDate = "2026-09-17"),
        )
        assertEquals(java.time.Instant.parse("2026-09-16T23:00:00Z").toEpochMilli(), continueWatchingRecency(airedToday, zone))
        val resume = card(2, 0, progress = 40.0).copy(id = "7", tmdbId = 7, updatedAt = "2026-09-10T10:00:00Z")
        assertEquals(java.time.Instant.parse("2026-09-10T10:00:00Z").toEpochMilli(), continueWatchingRecency(resume, zone))
        // Air dates never lift a Resume card, only Next Up.
        assertEquals(progressUpdatedAtMillis(resume.updatedAt), continueWatchingRecency(resume.copy(episode = airedToday.episode), zone))
    }

    @Test fun `stale provider pause loses to a newer mark on the same episode`() {
        val pause = card(5, 0, progress = 4.8).copy(updatedAt = null)
        val unwatched = record(5, 24, "unwatched", 0.0)
        assertTrue(continueRowSupersededByProgress(pause, listOf(unwatched)))
        assertTrue(continueRowSupersededByProgress(pause.copy(updatedAt = at(10)), listOf(record(5, 20))))
        assertFalse(continueRowSupersededByProgress(pause.copy(updatedAt = at(30)), listOf(unwatched)))
        assertFalse(continueRowSupersededByProgress(pause, listOf(record(4, 24))))
        assertFalse(continueRowSupersededByProgress(pause, listOf(record(5, 24, "in-progress", 20.0))))
    }

    @Test fun `one resume card per series, most recent unfinished episode`() {
        val e1 = card(1, 5, progress = 6.0)
        val e3 = card(3, 20, progress = 30.0)
        val finished = card(4, 25, progress = 97.0)
        val seriesLevel = card(0, 30, progress = 10.0).copy(seasonNumber = null, episodeNumber = null)
        val film = ContinueWatchingItem(id = "603", tmdbId = 603, title = "Film", type = "movie", progress = 20.0, updatedAt = at(1))
        assertEquals(listOf(e3, film), mergeNextUpContinueWatching(listOf(e1, finished, seriesLevel, film, e3), emptyList()))
    }

    @Test fun `only the selected service feeds Continue Watching`() {
        val connected = setOf(SyncServiceId.TRAKT, SyncServiceId.SIMKL)
        assertNull(continueWatchingPlaybackService("syncdek", connected::contains))
        assertEquals("trakt", continueWatchingPlaybackService("trakt", connected::contains))
        assertEquals("simkl", continueWatchingPlaybackService("simkl", connected::contains))
        // No Trakt backstop for a selection that is not connected.
        assertNull(continueWatchingPlaybackService("punchplay", connected::contains))
        assertTrue(traktHistoryApplies("trakt"))
        assertFalse(traktHistoryApplies("syncdek"))
        assertFalse(traktHistoryApplies("simkl"))
    }

    @Test fun `every unfinished position with a place to resume becomes a row`() {
        val film = PlaybackProgressRecord(
            positionSec = 320.0, durationSec = 5400.0, progress = 6.0, updatedAt = at(3),
            status = "in-progress", entityType = "movie", entityId = "603", tmdbId = 603,
            title = "It Ends", poster = "p.jpg", year = "2026",
        )
        val episode = record(2, 4, "in-progress", 12.0).copy(positionSec = 300.0, title = "Lanterns")
        val rows = unfinishedPositions(listOf(
            film, episode,
            record(4), record(5, 1, "unwatched", 0.0), record(6, 1, "dismissed", 0.0),
            film.copy(entityId = "604", positionSec = 0.0),
        ))
        assertEquals(listOf("603", "95350"), rows.map { it.id })
        assertEquals("It Ends", rows[0].title)
        assertEquals("movie", rows[0].type)
        assertNull(rows[0].exactEpisode())
        assertEquals(2, rows[1].exactEpisode()?.episodeNumber)
        assertEquals("tv", rows[1].type)
    }

    @Test fun `watchlist comes from the selected service only`() {
        val connected = setOf(SyncServiceId.TRAKT)
        assertEquals("syncdek", watchlistReadService("syncdek", connected::contains))
        assertEquals("syncdek", watchlistReadService("syncdek") { false })
        assertEquals("trakt", watchlistReadService("trakt", connected::contains))
        assertNull(watchlistReadService("trakt") { false })
        // Simkl selected but not connected: nothing, not Trakt's list.
        assertNull(watchlistReadService("simkl", connected::contains))
    }

    @Test fun `card subtitle tells resume and next up apart`() {
        assertEquals("Next up · S1 E5", continueWatchingCardSubtitle(card(5, 0, next = true), "Next up", "Resume"))
        assertEquals("Resume · S1 E4", continueWatchingCardSubtitle(card(4, 0, progress = 30.0), "Next up", "Resume"))
    }
}
