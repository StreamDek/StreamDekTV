package com.streamdek.tv.nativeapp.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which catalogs Discover may put a query to, read off the manifest as add-ons actually write it.
 *
 * Search asks every catalog that advertises it, so reading this wrong is expensive in both
 * directions: miss a catalog and its titles are unfindable, include one that cannot search and it
 * answers with its default listing, which looks like a wrong result rather than no result.
 */
class AddonCatalogSearchTest {
    private val gson = Gson()

    private fun catalog(json: String): AddonCatalogRef =
        gson.fromJson(json, AddonCatalogRef::class.java)

    @Test
    fun `search support is read from the structured extra form`() {
        val parsed = catalog(
            """{"type":"movie","id":"top","name":"Top","extra":[{"name":"search","isRequired":false}]}""",
        )

        assertTrue(parsed.supportsSearch)
    }

    @Test
    fun `search support is read from the older flat form`() {
        val parsed = catalog(
            """{"type":"series","id":"popular","extraSupported":["search","skip"]}""",
        )

        assertTrue(parsed.supportsSearch)
    }

    @Test
    fun `a catalog that advertises no search is left alone`() {
        val parsed = catalog("""{"type":"movie","id":"featured","extraSupported":["skip"]}""")

        assertFalse(parsed.supportsSearch)
        assertFalse(catalog("""{"type":"movie","id":"featured"}""").supportsSearch)
    }

    @Test
    fun `a manifest listing extras as plain strings still parses`() {
        // Not the spec, but some installed add-ons write it this way. One of them must not take
        // down the parse of the whole manifest.
        val parsed = catalog("""{"type":"tv","id":"channels","extra":["search"]}""")

        assertTrue(parsed.supportsSearch)
    }

    @Test
    fun `only a required genre is filled in`() {
        val required = catalog(
            """{"type":"other","id":"net","extra":[{"name":"genre","isRequired":true,"options":["Netflix","Hulu"]},{"name":"search"}]}""",
        )
        assertTrue(required.requiresGenre)
        assertEquals("Netflix", required.defaultGenre)

        // Optional genre: sending one would narrow the search to a slice of the catalog.
        val optional = catalog(
            """{"type":"movie","id":"top","extra":[{"name":"genre","options":["Action"]},{"name":"search"}]}""",
        )
        assertFalse(optional.requiresGenre)
        assertNull(optional.defaultGenre)
    }

    @Test
    fun `required genre options are read from the older top-level list`() {
        val parsed = catalog(
            """{"type":"other","id":"net","genres":["Netflix","Hulu"],"extraRequired":["genre"],"extraSupported":["search"]}""",
        )

        assertTrue(parsed.requiresGenre)
        assertEquals("Netflix", parsed.defaultGenre)
    }
}
