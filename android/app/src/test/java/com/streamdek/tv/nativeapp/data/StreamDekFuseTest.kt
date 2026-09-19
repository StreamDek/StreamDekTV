package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamDekFuseTest {
    private fun rail(id: String, live: Boolean = false) =
        HomeRail(id, id, listOf(MediaItem(id = "$id-1", title = "Title", type = if (live) "live" else "movie")), isLive = live)

    @Test
    fun `source rows are the add-on and CloudStream rows of media types`() {
        assertTrue(isFuseSourceHomeRail("addon:iptv:tv:sports:0"))
        assertTrue(isFuseSourceHomeRail("addon:cloudstream.news:live:row:0"))
        assertTrue(isFuseSourceHomeRail("addon:cinemeta:movie:top:1"))
        assertFalse(isFuseSourceHomeRail("addon:weird:other:thing:0"))
        assertFalse(isFuseSourceHomeRail("streaming_networks"))
    }

    @Test
    fun `switched off, Home is unchanged and shows no Fuse row`() {
        val rails = listOf(rail("continue-watching"), rail(FUSE_HOME_RAIL_ID), rail("addon:iptv:tv:sports:0", live = true))
        assertEquals(listOf("continue-watching", "addon:iptv:tv:sports:0"), applyFuseToHomeRails(rails, enabled = false).map { it.id })
    }

    @Test
    fun `switched on, the Fuse replaces the source rows under the personal rows`() {
        val rails = listOf(
            rail("continue-watching"), rail("new-episodes"), rail("streaming_networks"),
            rail("addon:iptv:tv:sports:0", live = true), rail("addon:cinemeta:movie:top:1"), rail(FUSE_HOME_RAIL_ID),
        )
        assertEquals(
            listOf("continue-watching", "new-episodes", FUSE_HOME_RAIL_ID, "streaming_networks"),
            applyFuseToHomeRails(rails, enabled = true).map { it.id },
        )
    }

    @Test
    fun `Home follows the phone - personal, live, networks, then the rest`() {
        val rails = listOf(
            rail("trending"), rail("streaming_networks"), rail("new-episodes"),
            rail("addon:iptv:tv:sports:0", live = true), rail("continue-watching"), rail("addon:cinemeta:movie:top:1"),
        )
        assertEquals(
            listOf("continue-watching", "new-episodes", "addon:iptv:tv:sports:0", "streaming_networks", "trending", "addon:cinemeta:movie:top:1"),
            arrangeHomeRails(rails, setOf("networks", "streaming_networks")).map { it.id },
        )
    }

    @Test
    fun `New Movies and New Series lead Home, ahead of the Fuse, live rows and Streaming Networks`() {
        val rails = listOf(
            rail("streaming_networks"), rail(FUSE_HOME_RAIL_ID), rail("addon:iptv:tv:sports:0", live = true),
            rail("trending_series"), rail("new_series"), rail("continue-watching"), rail("new_movies"),
        )
        assertEquals(
            listOf("continue-watching", "new_movies", "new_series", FUSE_HOME_RAIL_ID, "addon:iptv:tv:sports:0", "streaming_networks", "trending_series"),
            arrangeHomeRails(rails, setOf("streaming_networks")).map { it.id },
        )
        // Switched off in the layout, a row is simply absent; nothing stands in for it.
        assertEquals(
            listOf("new_series", FUSE_HOME_RAIL_ID, "streaming_networks"),
            arrangeHomeRails(listOf(rail("streaming_networks"), rail(FUSE_HOME_RAIL_ID), rail("new_series")), setOf("streaming_networks")).map { it.id },
        )
    }

    @Test
    fun `the Fuse row takes the live rows' place`() {
        val rails = listOf(rail("streaming_networks"), rail(FUSE_HOME_RAIL_ID), rail("continue-watching"))
        assertEquals(
            listOf("continue-watching", FUSE_HOME_RAIL_ID, "streaming_networks"),
            arrangeHomeRails(rails, setOf("streaming_networks")).map { it.id },
        )
    }

    @Test
    fun `a catalogue that cannot search shares one listing across queries`() {
        // An add-on catalogue without search. CloudStream rows used to be the example here; they are
        // searched through PluginCatalogSearch now (see PluginCatalogSearchTest).
        val browse = FuseCatalog("c", "s", "Source", "Row", live = true, origin = FuseOrigin.Addon)
        val searchable = browse.copy(key = "a", origin = FuseOrigin.Addon, searchable = true)
        assertEquals(fusePageKey(browse, ""), fusePageKey(browse, "news"))
        assertFalse(fusePageKey(searchable, "") == fusePageKey(searchable, "news"))
    }

    @Test
    fun `the Fuse card keeps its artwork until every source row is in`() {
        val sky = listOf(MediaItem(id = "sky", title = "Sky 1", type = "live", poster = "sky.png"))
        val news = listOf(MediaItem(id = "cnn", title = "CNN", type = "live", poster = "cnn.png"))
        fun content(preview: List<MediaItem>) = HomeContent(
            featured = null,
            rails = listOf(HomeRail(FUSE_HOME_RAIL_ID, "Fuse", listOf(MediaItem(id = "f", title = "Fuse", type = FUSE_PORTAL_ITEM_TYPE)), previewItems = preview)),
        )
        // A refresh that has only CloudStream's rows so far draws what the card last showed.
        val (partial, heldOver) = steadyFusePreview(content(news), sourcesResolved = false, stable = sky)
        assertEquals(listOf("sky"), partial.rails.single().previewItems.map { it.id })
        assertEquals(listOf("sky"), (partial.shelves.single() as HomeShelfSlot.Loaded).rail.previewItems.map { it.id })
        assertEquals(sky, heldOver)
        // Once every source is back, the real preview is shown and becomes the one held.
        val (complete, next) = steadyFusePreview(content(news), sourcesResolved = true, stable = sky)
        assertEquals(listOf("cnn"), complete.rails.single().previewItems.map { it.id })
        assertEquals(news, next)
        // With nothing held yet, a partial load shows no artwork rather than a stand-in set.
        assertEquals(emptyList<MediaItem>(), steadyFusePreview(content(news), sourcesResolved = false, stable = null).first.rails.single().previewItems)
    }
}
