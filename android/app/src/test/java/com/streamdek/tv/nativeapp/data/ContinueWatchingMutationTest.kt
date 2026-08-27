package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingMutationTest {
    @Test
    fun `removing one episode preserves every other card`() {
        val episodeTwo = continueItem("Reacher", season = 1, episode = 2)
        val episodeThree = continueItem("Reacher", season = 1, episode = 3)
        val movie = ContinueWatchingItem(id = "movie-1", title = "Dune", type = "movie")
        val selected = episodeTwo.asMediaItem()

        val result = removeContinueWatchingSnapshot(listOf(episodeTwo, episodeThree, movie), selected)

        assertEquals(listOf(episodeThree, movie), result)
    }

    @Test
    fun `identity includes canonical series id and exact episode`() {
        val entry = continueItem("Reacher", season = 4, episode = 5, tmdbId = 108978)

        assertTrue(sameContinueWatchingItem(entry, entry.asMediaItem().copy(id = "tmdb:108978", tmdbId = 0)))
        assertFalse(sameContinueWatchingItem(entry, entry.asMediaItem().copy(episode = EpisodeContext(4, 6))))
        assertFalse(sameContinueWatchingItem(entry, entry.asMediaItem().copy(id = "108979", tmdbId = 108979)))
    }

    private fun continueItem(
        title: String,
        season: Int,
        episode: Int,
        tmdbId: Int = 0,
    ) = ContinueWatchingItem(
        id = if (tmdbId > 0) tmdbId.toString() else title.lowercase(),
        tmdbId = tmdbId,
        title = title,
        type = "tv",
        episode = EpisodeContext(season, episode),
    )

    private fun ContinueWatchingItem.asMediaItem() = MediaItem(
        id = id,
        tmdbId = tmdbId,
        title = title,
        type = type,
        episode = exactEpisode(),
    )
}
