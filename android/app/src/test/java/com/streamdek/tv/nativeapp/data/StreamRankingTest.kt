package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamRankingTest {
    @Test
    fun `cached results stay ahead then preferred quality orders within each group`() {
        val cached720 = AddonStream(title = "Release 720p", cachedBy = listOf("TorBox"))
        val cached1080 = AddonStream(title = "Release 1080p", cachedBy = listOf("Deepbrid"))
        val cached4k = AddonStream(filename = "Release.2160p.mkv", cachedBy = listOf("Real-Debrid"))
        val uncached1080 = AddonStream(name = "Torrentio\n1080p")

        val ranked = listOf(uncached1080, cached720, cached4k, cached1080)
            .sortedWith(cacheThenQualityComparator("1080p"))

        assertEquals(listOf(cached1080, cached720, cached4k, uncached1080), ranked)
    }

    @Test
    fun `quality is inferred from addon text when dedicated field is absent`() {
        assertEquals("2160p", inferredStreamQuality(AddonStream(title = "Movie WEB-DL 2160p Atmos")))
        assertEquals("1080p", inferredStreamQuality(AddonStream(filename = "Movie.1080p.mkv")))
    }
}
