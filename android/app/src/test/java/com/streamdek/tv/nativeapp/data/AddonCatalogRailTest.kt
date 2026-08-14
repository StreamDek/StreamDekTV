package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog handling, against the manifest shapes real add-ons actually publish.
 *
 * The examples are taken from installed add-ons rather than invented: AIOStreams ships ten
 * catalogs under both `movie` and `series` with identical names and ids, and Xperience publishes
 * search- and genre-gated catalogs alongside ordinary ones. Both patterns produced visible
 * problems on the home screen and neither was covered.
 */
class AddonCatalogRailTest {

    @Test
    fun `anime catalogs are shown rather than dropped`() {
        // Series-shaped in every respect that matters, and published as its own type by a good
        // number of add-ons. It used to map to null, which silently discarded the whole row.
        assertEquals("tv", mapAddonCatalogType("anime"))
        assertEquals("tv", mapAddonCatalogType("series"))
        assertEquals("movie", mapAddonCatalogType("movie"))
        assertEquals("live", mapAddonCatalogType("tv"))
    }

    @Test
    fun `a catalog published under one type carries no type of its own`() {
        // Unchanged from before: short enough to say who it came from, and nothing appended.
        val title = buildAddonRailTitle("AIOStreams Stable", "Netflix")
        assertEquals("AIOStreams Stable - Netflix", title)
        assertFalse(title.endsWith("Movies"))
        assertFalse(title.endsWith("Series"))
    }

    @Test
    fun `the same catalog under two types says which is which`() {
        // AIOStreams' "Netflix" pair, which arrived as two identical rows stacked together. The
        // add-on prefix is what gives way to make room — it is the same on both, so it is not the
        // part carrying the distinction.
        assertEquals("Netflix Movies", buildAddonRailTitle("AIOStreams Stable", "Netflix", "Movies"))
        assertEquals("Netflix Series", buildAddonRailTitle("AIOStreams Stable", "Netflix", "Series"))
    }

    @Test
    fun `the differentiator survives a title long enough to be truncated`() {
        val title = buildAddonRailTitle("Xperience", "An Extremely Long Catalogue Name That Runs On", "Series")
        assertTrue("expected the suffix to be kept, got: $title", title.endsWith("Series"))
    }

    @Test
    fun `a search-gated catalog is not a browsable row`() {
        // Xperience's `xperience.search` and AIOStreams' `aicat_search_*`: they answer an empty
        // list without a term, so as a home row they cost a request to show nothing.
        val searchOnly = AddonCatalogRef(
            type = "movie",
            id = "xperience.search",
            name = "Results",
            extra = listOf(mapOf("name" to "search", "isRequired" to true)),
        )
        assertTrue(searchOnly.requiresSearch)
        assertFalse(searchOnly.requiresGenre)
    }

    @Test
    fun `a catalog that merely accepts a search term is still browsable`() {
        val browsable = AddonCatalogRef(
            type = "movie",
            id = "snoak_top100_movies",
            name = "Top 100 Today",
            extra = listOf(
                mapOf("name" to "genre", "isRequired" to false),
                mapOf("name" to "skip", "isRequired" to false),
            ),
            extraSupported = listOf("genre", "skip", "search"),
        )
        assertTrue(browsable.supportsSearch)
        assertFalse(browsable.requiresSearch)
        assertFalse(browsable.requiresGenre)
        assertNull(browsable.defaultGenre)
    }

    @Test
    fun `a genre-gated catalog is asked with a genre it declared`() {
        // Xperience's `discover_all_movies`. The genre has to come from the catalog's own options:
        // anything else is a guess the add-on is entitled to reject.
        val gated = AddonCatalogRef(
            type = "movie",
            id = "discover_all_movies",
            name = "Movies",
            extra = listOf(
                mapOf("name" to "genre", "isRequired" to true, "options" to listOf("Action", "Comedy")),
                mapOf("name" to "skip", "isRequired" to false),
            ),
        )
        assertTrue(gated.requiresGenre)
        assertFalse(gated.requiresSearch)
        assertEquals("Action", gated.defaultGenre)
    }

    @Test
    fun `older manifests spell their requirements beside the extras`() {
        // The flat spelling, still in use: `extraRequired` rather than per-entry isRequired.
        val legacy = AddonCatalogRef(
            type = "series",
            id = "legacy",
            name = "Legacy",
            extraSupported = listOf("genre", "search"),
            extraRequired = listOf("search"),
        )
        assertTrue(legacy.requiresSearch)
        assertFalse(legacy.requiresGenre)
    }
}
