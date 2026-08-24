package com.streamdek.tv.nativeapp.ui.home

import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeItemKeyTest {
    @Test
    fun `different episodes of one series keep distinct home keys`() {
        val episodeOne = mediaItem(EpisodeContext(seasonNumber = 1, episodeNumber = 1))
        val episodeTwo = mediaItem(EpisodeContext(seasonNumber = 1, episodeNumber = 2))

        assertNotEquals(homeItemKey(episodeOne), homeItemKey(episodeTwo))
        assertEquals(2, listOf(episodeOne, episodeTwo).distinctBy(::homeItemKey).size)
    }

    @Test
    fun `exact duplicate cards still share a key for defensive deduplication`() {
        val episode = EpisodeContext(seasonNumber = 1, episodeNumber = 2)

        val duplicates = listOf(mediaItem(episode), mediaItem(episode))

        assertEquals(homeItemKey(duplicates[0]), homeItemKey(duplicates[1]))
        assertEquals(1, duplicates.distinctBy(::homeItemKey).size)
    }

    private fun mediaItem(episode: EpisodeContext) = MediaItem(
        id = "113962",
        title = "Example",
        type = "tv",
        episode = episode,
    )
}
