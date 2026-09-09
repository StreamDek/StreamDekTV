package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewEpisodePolicyTest {
    @Test fun `provider watched episode is excluded`() {
        assertTrue(isNewEpisodeWatched(100, 2, 4, setOf("tv:100:s2:e4"), emptyList()))
    }

    @Test fun `syncdek completion and explicit unwatched override provider history`() {
        val completed = PlaybackProgressRecord(
            positionSec = 0.0, durationSec = 0.0, progress = 100.0,
            seasonNumber = 2, episodeNumber = 4, updatedAt = "2026-09-09T10:00:00Z",
            status = "completed", entityType = "tv", entityId = "100", tmdbId = 100,
        )
        val unwatched = completed.copy(status = "unwatched", updatedAt = "2026-09-09T11:00:00Z")
        assertTrue(isNewEpisodeWatched(100, 2, 4, emptySet(), listOf(completed)))
        assertFalse(isNewEpisodeWatched(100, 2, 4, setOf("tv:100:s2:e4"), listOf(completed, unwatched)))
    }
}
