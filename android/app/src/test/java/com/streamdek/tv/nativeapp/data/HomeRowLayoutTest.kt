package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout rule, and the two reasons it did nothing on the television before.
 *
 * These mirror `HomeCatalogRowPersistenceTest` on the phone. The two clients have to agree, because
 * one saved arrangement is read by both — and they did not: the television applied the layout only
 * to the built-in registry rows, and compared add-on ids exactly when the two clients number them
 * differently.
 */
class HomeRowLayoutTest {

    private fun rail(id: String) = HomeRail(id = id, title = id, items = listOf(item(id)))

    private fun item(id: String) = MediaItem(id = id, type = "movie", title = id)

    private fun saved(id: String, position: Int, enabled: Boolean = true) =
        HomeCatalogRowPreference(id = id, enabled = enabled, position = position, title = id)

    private fun addonRow(catalogId: String, index: Int, addonId: String = "xperience", type: String = "movie") =
        "addon:$addonId:$type:$catalogId:$index"

    // ── The match key ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `the match key ignores the manifest position and nothing else`() {
        assertEquals(
            homeCatalogRowMatchKey(addonRow("trending", 0)),
            homeCatalogRowMatchKey(addonRow("trending", 17)),
        )
        assertTrue(homeCatalogRowMatchKey(addonRow("trending", 0)) != homeCatalogRowMatchKey(addonRow("popular", 0)))
        assertTrue(
            homeCatalogRowMatchKey(addonRow("trending", 0)) !=
                homeCatalogRowMatchKey(addonRow("trending", 0, addonId = "other")),
        )
        assertTrue(
            homeCatalogRowMatchKey(addonRow("trending", 0)) !=
                homeCatalogRowMatchKey(addonRow("trending", 0, type = "series")),
        )
        assertEquals("trending_movies", homeCatalogRowMatchKey("trending_movies"))
    }

    @Test
    fun `the add-on behind a row is read from its id`() {
        assertEquals("xperience", homeCatalogRowAddonId(addonRow("trending", 0)))
        assertEquals(null, homeCatalogRowAddonId("trending_movies"))
    }

    // ── Applying the layout to fetched rows ─────────────────────────────────────────────────────

    @Test
    fun `an add-on row switched off elsewhere is hidden here`() {
        // The reported failure: this used to come back untouched, because only registry rows were
        // ever filtered.
        val rails = listOf(rail("trending_movies"), rail(addonRow("trending", 17)))
        val layout = listOf(saved("trending_movies", 0), saved(addonRow("trending", 3), 1, enabled = false))

        val visible = applyHomeRowLayout(rails, layout)

        assertEquals(listOf("trending_movies"), visible.map { it.id })
    }

    @Test
    fun `the phone's numbering and the television's name the same row`() {
        // The phone counts catalogues inside one add-on; the television counts across all of them.
        val rails = listOf(rail(addonRow("trending", 17)))
        val layout = listOf(saved(addonRow("trending", 3), 0, enabled = false))

        assertTrue(applyHomeRowLayout(rails, layout).isEmpty())
    }

    @Test
    fun `rows follow the saved order`() {
        val rails = listOf(rail("a"), rail("b"), rail("c"))
        val layout = listOf(saved("c", 0), saved("a", 1), saved("b", 2))

        assertEquals(listOf("c", "a", "b"), applyHomeRowLayout(rails, layout).map { it.id })
    }

    @Test
    fun `a row the layout says nothing about is shown, after the ones it does`() {
        // A catalogue the viewer has never seen is new, not unwanted. An add-on they just installed
        // appearing to do nothing would be worse than an extra row.
        val rails = listOf(rail("a"), rail("brand-new"), rail("b"))
        val layout = listOf(saved("b", 0), saved("a", 1))

        assertEquals(listOf("b", "a", "brand-new"), applyHomeRowLayout(rails, layout).map { it.id })
    }

    @Test
    fun `an empty layout leaves the rows exactly as they were`() {
        val rails = listOf(rail("a"), rail("b"))

        assertEquals(rails, applyHomeRowLayout(rails, emptyList()))
    }

    @Test
    fun `a layout that has not loaded yet cannot hide anything`() {
        // Preferences arrive after the first home assembly. Treating "no layout" as "everything is
        // off" would blank Home for a moment on every start.
        val rails = listOf(rail("a"), rail(addonRow("trending", 0)))

        assertEquals(2, applyHomeRowLayout(rails, emptyList()).size)
    }

    // ── Building the editor's list ──────────────────────────────────────────────────────────────

    private fun definition(id: String, title: String = id, mediaType: String = "movie") = CatalogDefinition(
        id = id,
        title = title,
        mediaType = mediaType,
        group = "default",
        previewLimit = 20,
        maxItems = null,
        paginated = true,
    )

    private fun addon(id: String, catalogIds: List<String>, enabled: Boolean = true, position: Int = 0) =
        AddonManifest(
            id = id,
            enabled = enabled,
            position = position,
            manifest = AddonManifestMeta(
                id = id,
                name = id,
                catalogs = catalogIds.map { AddonCatalogRef(type = "movie", id = it, name = it) },
            ),
        )

    @Test
    fun `the editor lists built-in and add-on rows together`() {
        val options = homeRowOptions(
            listOf(definition("trending_movies")),
            listOf(addon("xperience", listOf("trending", "popular"))),
            emptyList(),
        )

        assertEquals(3, options.size)
        assertTrue(options.any { it.builtin && it.id == "trending_movies" })
        assertEquals(2, options.count { !it.builtin })
    }

    @Test
    fun `the editor numbers add-on rows the way the phone does`() {
        // Written in the phone's spelling so a layout saved here stays readable by an older phone
        // build that still compares ids exactly.
        val options = homeRowOptions(
            emptyList(),
            listOf(addon("xperience", listOf("trending", "popular"))),
            emptyList(),
        )

        assertEquals(
            listOf("addon:xperience:movie:trending:0", "addon:xperience:movie:popular:1"),
            options.map { it.id },
        )
    }

    @Test
    fun `the editor shows the saved switches and order`() {
        val options = homeRowOptions(
            listOf(definition("trending_movies")),
            listOf(addon("xperience", listOf("trending"))),
            listOf(saved(addonRow("trending", 9), 0, enabled = false), saved("trending_movies", 1)),
        )

        assertEquals("addon:xperience:movie:trending:0", options.first().id)
        assertFalse(options.first().enabled)
        assertTrue(options.last().enabled)
    }

    @Test
    fun `a row the viewer has never seen is offered switched on`() {
        val options = homeRowOptions(listOf(definition("brand_new")), emptyList(), listOf(saved("other", 0)))

        assertTrue(options.single { it.id == "brand_new" }.enabled)
    }

    @Test
    fun `a switched-off add-on contributes no rows to the editor`() {
        val options = homeRowOptions(
            emptyList(),
            listOf(addon("xperience", listOf("trending"), enabled = false)),
            emptyList(),
        )

        assertTrue(options.isEmpty())
    }

    @Test
    fun `a catalogue type the app cannot render is not offered`() {
        val addon = AddonManifest(
            id = "odd",
            manifest = AddonManifestMeta(
                id = "odd",
                name = "Odd",
                catalogs = listOf(
                    AddonCatalogRef(type = "movie", id = "films", name = "Films"),
                    AddonCatalogRef(type = "podcast", id = "casts", name = "Casts"),
                ),
            ),
        )

        val options = homeRowOptions(emptyList(), listOf(addon), emptyList())

        // Only the film catalogue survives. The title carries the add-on's name in front of the
        // catalogue's, which is how a row says where it came from.
        assertEquals(listOf("addon:odd:movie:films:0"), options.map { it.id })
        assertTrue(options.single().title.contains("Films"))
    }

    // ── Saving ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `saving numbers the rows by their order on screen`() {
        val options = listOf(
            HomeRowOption("b", "B", "", builtin = true, enabled = false),
            HomeRowOption("a", "A", "", builtin = true, enabled = true),
        )

        val layout = homeRowLayoutOf(options)

        assertEquals(listOf("b", "a"), layout.map { it.id })
        assertEquals(listOf(0, 1), layout.map { it.position })
        assertEquals(listOf(false, true), layout.map { it.enabled })
    }

    @Test
    fun `a saved layout round-trips through the editor unchanged`() {
        val definitions = listOf(definition("a"), definition("b"))
        val first = homeRowOptions(definitions, emptyList(), emptyList())
        val flipped = first.map { if (it.id == "a") it.copy(enabled = false) else it }

        val reloaded = homeRowOptions(definitions, emptyList(), homeRowLayoutOf(flipped))

        assertEquals(flipped.map { it.id }, reloaded.map { it.id })
        assertEquals(flipped.map { it.enabled }, reloaded.map { it.enabled })
    }
}
