package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalWatchlistTest {
    private fun film(id: Int, title: String = "Film $id") = MediaItem(id = id.toString(), tmdbId = id, title = title, type = "movie")

    @Test fun `adding keeps the first time and removing matches any spelling`() {
        val added = mutateLocalWatchlist(emptyList(), film(1), remove = false, now = 10)
        assertEquals(listOf(LocalWatchlistEntry(film(1), 10)), mutateLocalWatchlist(added, film(1), remove = false, now = 20))
        val otherSpelling = MediaItem(id = "tmdb:1", title = "Film 1", type = "movie")
        assertEquals(emptyList<LocalWatchlistEntry>(), mutateLocalWatchlist(added, otherSpelling, remove = true, now = 30))
    }

    @Test fun `local-only titles lead newest first and the service copy wins where both have it`() {
        val service = listOf(film(3, "Service copy"), film(4))
        val local = listOf(LocalWatchlistEntry(film(1), 100), LocalWatchlistEntry(film(2), 200), LocalWatchlistEntry(film(3, "Local copy"), 300))
        val merged = mergeWatchlistWithLocal(service, local)
        assertEquals(listOf("2", "1", "3", "4"), merged.map { it.id })
        assertEquals("Service copy", merged[2].title)
    }

    @Test fun `an unreachable service still shows what this television saved`() {
        assertEquals(listOf("1"), mergeWatchlistWithLocal(emptyList(), listOf(LocalWatchlistEntry(film(1), 1))).map { it.id })
    }
}
