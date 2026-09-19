package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matching that plugin search and the Fuse share.
 *
 * Plugin titles arrive written however the provider writes them - accents, punctuation, a hyphen in
 * "Spider-Man" - and a viewer types none of that. These are the spellings that must still meet.
 */
class PluginCatalogSearchTest {

    @Test
    fun `an exact title ranks above a prefix, which ranks above a word inside`() {
        val exact = PluginCatalogSearch.matchRank("Dune", "dune")!!
        val prefix = PluginCatalogSearch.matchRank("Dune: Part Two", "dune")!!
        val word = PluginCatalogSearch.matchRank("The Dune Chronicles", "dune")!!
        assertTrue(exact < prefix)
        assertTrue(prefix < word)
    }

    @Test
    fun `accents, case and punctuation do not stop a match`() {
        assertNotNull(PluginCatalogSearch.matchRank("Amélie", "amelie"))
        assertNotNull(PluginCatalogSearch.matchRank("Spider-Man: No Way Home", "spiderman"))
        assertNotNull(PluginCatalogSearch.matchRank("BBC ONE HD", "bbc one"))
    }

    @Test
    fun `every word of the query has to be there, in any order`() {
        assertNotNull(PluginCatalogSearch.matchRank("Sky Sports Premier League", "premier sky"))
        assertNull(PluginCatalogSearch.matchRank("Sky News", "sky sports"))
    }

    @Test
    fun `an empty query or title matches nothing`() {
        assertNull(PluginCatalogSearch.matchRank("Dune", "  "))
        assertNull(PluginCatalogSearch.matchRank("", "dune"))
    }

    @Test
    fun `a query puts a plugin's rows on one shared page, and browsing keeps them apart`() {
        fun row(id: String) = FuseCatalog(
            key = id, sourceKey = "cloudstream.cncverse", sourceName = "CNC Verse", title = id,
            live = false, origin = FuseOrigin.CloudStream, searchable = true, cloudRowId = id,
        )
        val trending = row("addon:cloudstream.cncverse:movie:trending:0")
        val latest = row("addon:cloudstream.cncverse:movie:latest:1")
        assertEquals(fusePageKey(trending, "dune"), fusePageKey(latest, "dune"))
        assertNotEquals(fusePageKey(trending, ""), fusePageKey(latest, ""))
    }
}
