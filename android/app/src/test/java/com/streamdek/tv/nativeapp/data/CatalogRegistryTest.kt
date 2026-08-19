package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backend catalog registry as Home consumes it.
 *
 * What is worth covering here is the order Home ends up in, because that is the part with no
 * visible failure: a wrong order still renders thirty rows and still scrolls, it just is not the
 * screen the viewer arranged — or, on an upgrade, buries a whole backend deploy's worth of rows
 * beneath five the viewer never ranked.
 */
class CatalogRegistryTest {

    private fun definition(id: String, mediaType: String = "movie") = CatalogDefinition(
        id = id,
        title = id.replace('_', ' ').replaceFirstChar { it.uppercase() },
        mediaType = mediaType,
        group = "default",
        previewLimit = 20,
        maxItems = null,
        paginated = mediaType != "network",
    )

    private val registry = listOf(
        definition("trending_movies"),
        definition("trending_series", "tv"),
        definition("new_movies"),
        definition("streaming_networks", "network"),
        definition("horror_movies"),
    )

    private fun row(id: String, position: Int, enabled: Boolean = true) =
        HomeCatalogRowPreference(id = id, enabled = enabled, position = position)

    @Test
    fun `with no saved layout the registry order is the home order`() {
        assertEquals(registry.map { it.id }, orderCatalogRows(registry, emptyList()).map { it.id })
    }

    @Test
    fun `an arrangement the viewer made is preserved`() {
        val layout = listOf(
            row("horror_movies", 0),
            row("trending_movies", 1),
            row("trending_series", 2),
            row("new_movies", 3),
            row("streaming_networks", 4),
        )

        assertEquals(
            listOf("horror_movies", "trending_movies", "trending_series", "new_movies", "streaming_networks"),
            orderCatalogRows(registry, layout).map { it.id },
        )
    }

    @Test
    fun `rows the viewer switched off are left off the home screen`() {
        val layout = listOf(
            row("trending_movies", 0),
            row("trending_series", 1, enabled = false),
            row("new_movies", 2),
            row("streaming_networks", 3),
            row("horror_movies", 4),
        )

        val ordered = orderCatalogRows(registry, layout).map { it.id }

        assertTrue("trending_series" !in ordered)
        assertEquals(listOf("trending_movies", "new_movies", "streaming_networks", "horror_movies"), ordered)
    }

    @Test
    fun `a row added by a later deploy lands beside its registry neighbours`() {
        // The saved layout predates horror_movies and streaming_networks entirely, and it moved
        // new_movies to the top — an arrangement the registry knows nothing about.
        val layout = listOf(row("new_movies", 0), row("trending_movies", 1), row("trending_series", 2))

        val ordered = orderCatalogRows(registry, layout).map { it.id }

        // Each unseen row follows the row the registry puts immediately ahead of it, wherever the
        // viewer has since moved that row to — streaming_networks after new_movies, horror_movies
        // after streaming_networks — rather than being dumped at the bottom of the screen.
        assertEquals(
            listOf("new_movies", "streaming_networks", "horror_movies", "trending_movies", "trending_series"),
            ordered,
        )
        // Whatever else moved, the rows the viewer did rank keep their relative order.
        assertTrue(ordered.indexOf("trending_movies") < ordered.indexOf("trending_series"))
        assertEquals("new_movies", ordered.first())
    }

    @Test
    fun `a row the registry has dropped disappears from the layout`() {
        val layout = listOf(
            row("trending_movies", 0),
            row("peacock_popular_movies", 1),
            row("new_movies", 2),
        )

        val ordered = orderCatalogRows(registry, layout).map { it.id }

        assertTrue("peacock_popular_movies" !in ordered)
        assertEquals("trending_movies", ordered.first())
    }

    @Test
    fun `titles follow the registry rather than whatever was saved`() {
        val layout = listOf(row("trending_movies", 0))

        assertEquals("Trending movies", orderCatalogRows(registry, layout).first().title)
    }

    @Test
    fun `manifest entries without an id or a title are skipped`() {
        val parsed = parseCatalogDefinitions(
            CatalogManifestResponse(
                version = 3,
                catalogs = listOf(
                    CatalogManifestEntry(id = "trending_movies", title = "Trending Movies", media_type = "movie", preview_limit = 20),
                    CatalogManifestEntry(id = "  ", title = "Nameless"),
                    CatalogManifestEntry(id = "no_title", title = ""),
                    CatalogManifestEntry(id = "top_100_movies", title = "Top 100 Movies", max_items = 100),
                ),
            ),
        )

        assertEquals(listOf("trending_movies", "top_100_movies"), parsed.map { it.id })
        assertEquals(100, parsed.last().maxItems)
        // preview_limit is absent on the second entry; a row still has to render something.
        assertEquals(20, parsed.last().previewLimit)
    }

    @Test
    fun `a missing manifest is an empty registry rather than a failure`() {
        assertEquals(emptyList<CatalogDefinition>(), parseCatalogDefinitions(null))
    }

    @Test
    fun `a network tile is read from name and logo rather than title and poster`() {
        val card = CatalogSectionItem(id = "8", name = "Netflix", logo = "https://img/netflix.png")
            .toMediaItem(sectionMediaType = "network")

        assertEquals("Netflix", card.title)
        assertEquals("network", card.type)
        assertEquals("https://img/netflix.png", card.poster)
        assertEquals("https://img/netflix.png", card.titleLogo)
        assertEquals(8, card.tmdbId)
    }

    @Test
    fun `a title row keeps the section media type when the item omits its own`() {
        val card = CatalogSectionItem(
            id = "1399",
            tmdbId = 1399,
            title = "Game of Thrones",
            poster = "https://img/got.jpg",
            year = "2011",
        ).toMediaItem(sectionMediaType = "tv")

        assertEquals("tv", card.type)
        assertEquals("Game of Thrones", card.title)
        assertEquals("2011", card.year)
        assertNull(card.description)
    }
}
