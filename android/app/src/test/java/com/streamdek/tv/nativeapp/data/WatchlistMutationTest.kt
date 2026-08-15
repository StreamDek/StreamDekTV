package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchlistMutationTest {
    private val dune = MediaItem(id = "tmdb:438631", tmdbId = 438631, title = "Dune", type = "movie")
    private val silo = MediaItem(id = "125988", tmdbId = 125988, title = "Silo", type = "tv")

    @Test
    fun `remove only deletes the matching title`() {
        val result = mutateWatchlistSnapshot(listOf(dune, silo), dune.copy(id = "438631"), remove = true)

        assertEquals(listOf(silo), result)
    }

    @Test
    fun `series and tv spellings share one watchlist identity`() {
        assertTrue(sameWatchlistTitle(silo, silo.copy(type = "series", id = "tmdb:125988", tmdbId = 0)))
    }

    @Test
    fun `adding an existing title does not duplicate it`() {
        val current = listOf(dune, silo)

        val result = mutateWatchlistSnapshot(current, dune.copy(id = "438631"), remove = false)

        assertSame(current, result)
    }
}
