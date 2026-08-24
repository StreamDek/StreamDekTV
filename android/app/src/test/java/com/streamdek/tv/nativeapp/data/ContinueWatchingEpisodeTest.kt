package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueWatchingEpisodeTest {
    @Test
    fun `mobile top-level episode fields become an exact TV episode`() {
        val item = ContinueWatchingItem(
            id = "125359",
            title = "Lioness",
            type = "tv",
            seasonNumber = 3,
            episodeNumber = 4,
            positionSec = 1324.0,
        )

        assertEquals(EpisodeContext(3, 4), item.exactEpisode())
    }

    @Test
    fun `enriched nested episode identity is preserved`() {
        val nested = EpisodeContext(3, 4, title = "Spear and Fang")
        val item = ContinueWatchingItem(
            id = "125359",
            title = "Lioness",
            type = "tv",
            episode = nested,
            seasonNumber = 1,
            episodeNumber = 1,
        )

        assertEquals(nested, item.exactEpisode())
    }

    @Test
    fun `series-only rows do not invent an episode`() {
        val item = ContinueWatchingItem(id = "125359", title = "Lioness", type = "tv")

        assertNull(item.exactEpisode())
    }
}
