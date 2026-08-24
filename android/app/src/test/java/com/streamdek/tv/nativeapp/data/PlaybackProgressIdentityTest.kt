package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressIdentityTest {
    @Test
    fun `uses explicit episode numbers when present`() {
        val record = record(seasonNumber = 3, episodeNumber = 4, episodeKey = "s01e02")
        assertEquals("s3:e4", record.compactEpisodeKey())
    }

    @Test
    fun `normalizes padded backend episode keys`() {
        assertEquals("s1:e2", record(episodeKey = "s01e02").compactEpisodeKey())
    }

    @Test
    fun `normalizes compact client episode keys`() {
        assertEquals("s12:e7", record(episodeKey = "s12:e7").compactEpisodeKey())
    }

    private fun record(
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeKey: String? = null,
    ) = PlaybackProgressRecord(
        positionSec = 0.0,
        durationSec = 0.0,
        progress = 0.0,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeKey = episodeKey,
    )
}
