package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity rule behind Continue Watching removals sticking.
 *
 * These mirror `continueWatchingRemoval.test.ts` on the backend and `MediaIdentityTest` on mobile
 * case for case. The three implementations have to agree, because a removal is recorded by one and
 * judged by the others; when they disagreed, an explicitly removed title came back.
 */
class MediaIdentityTest {
    private val duneTmdb = 438631
    private val duneImdb = "tt1160419"

    @Test
    fun `the same film written three different ways is one title`() {
        val fromCatalogueCard = mediaIdentityOf("movie", duneTmdb.toString())
        val fromPrefixedAddon = mediaIdentityOf("movie", "tmdb:$duneTmdb")
        val fromCinemetaAddon = mediaIdentityOf("movie", duneImdb)
        val fromProvider = mediaIdentityOf("movie", "", duneTmdb, duneImdb)

        assertTrue(sameMediaIdentity(fromCatalogueCard, fromPrefixedAddon))
        assertTrue(sameMediaIdentity(fromCinemetaAddon, fromProvider))
        assertTrue(sameMediaIdentity(fromCatalogueCard, fromProvider))
    }

    @Test
    fun `an id belonging to another service is not read as a TMDB id`() {
        assertFalse(
            sameMediaIdentity(
                mediaIdentityOf("movie", "trakt:$duneTmdb"),
                mediaIdentityOf("movie", duneTmdb.toString()),
            ),
        )
    }

    @Test
    fun `a film and a series sharing a number are different titles`() {
        assertFalse(
            sameMediaIdentity(mediaIdentityOf("movie", "1399"), mediaIdentityOf("tv", "1399")),
        )
    }

    @Test
    fun `series aliases canonicalise to one type`() {
        assertEquals(listOf("tv", "tv", "tv"), listOf("tv", "series", "show").map(::canonicalMediaType))
        assertEquals("movie", canonicalMediaType("movie"))
    }

    @Test
    fun `an add-on slug matches itself and nothing numeric`() {
        val slug = mediaIdentityOf("movie", "punchplay-obsession-2026")
        assertEquals(listOf("movie:raw:punchplay-obsession-2026"), slug.keys())
        assertFalse(sameMediaIdentity(slug, mediaIdentityOf("movie", "2026")))
    }

    @Test
    fun `an imdb id embedded in a compound add-on id is still found`() {
        assertTrue(
            sameMediaIdentity(
                mediaIdentityOf("tv", "$duneImdb:1:2"),
                mediaIdentityOf("tv", "", imdbId = duneImdb),
            ),
        )
    }

    @Test
    fun `a title level removal covers every episode`() {
        assertTrue(removalCoversEpisode(null, null, candidateSeason = 3, candidateEpisode = 7))
    }

    @Test
    fun `an episode level removal covers only that episode`() {
        assertTrue(removalCoversEpisode(2, 4, candidateSeason = 2, candidateEpisode = 4))
        assertFalse(removalCoversEpisode(2, 4, candidateSeason = 2, candidateEpisode = 5))
    }
}
