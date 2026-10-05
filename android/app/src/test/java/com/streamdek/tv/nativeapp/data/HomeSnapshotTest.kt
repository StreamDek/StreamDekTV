package com.streamdek.tv.nativeapp.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSnapshotTest {
    private fun card(id: String, title: String = "Title $id") = MediaItem(id = id, title = title, type = "movie")

    private fun rail(id: String, vararg cards: MediaItem) = HomeRail(id, "Row $id", cards.toList())

    private fun page(vararg rails: HomeRail) = HomeContent(featured = rails.firstOrNull()?.items?.firstOrNull(), rails = rails.toList())

    private fun store(directory: File, versionCode: Int = 7, clock: () -> Long = { 1_000_000L }) =
        HomeSnapshotStore(directory, versionCode, clock)

    private fun tempDirectory(): File = File.createTempFile("home-snapshot", "").let { it.delete(); it.mkdirs(); it }

    @Test
    fun `an unchanged refresh hands back the page already on screen`() {
        val shown = page(rail("a", card("1")), rail("b", card("2")))
        val fresh = page(rail("a", card("1")), rail("b", card("2")))
        assertSame(shown, fresh.sharingRowsWith(shown))
    }

    @Test
    fun `only the row that changed is a new object`() {
        val shown = page(rail("continue-watching", card("1")), rail("popular", card("2"), card("3")))
        val fresh = page(rail("continue-watching", card("1"), card("9")), rail("popular", card("2"), card("3")))
        val shared = fresh.sharingRowsWith(shown)
        assertEquals(fresh, shared)
        assertNotSame(shown.rails[0], shared.rails[0])
        assertSame(shown.rails[1], shared.rails[1])
        assertSame(shown.shelves[1], shared.shelves[1])
        assertSame(shared.rails[1], (shared.shelves[1] as HomeShelfSlot.Loaded).rail)
    }

    @Test
    fun `a row that moved keeps its identity`() {
        val shown = page(rail("a", card("1")), rail("b", card("2")))
        val fresh = page(rail("new", card("7")), rail("a", card("1")), rail("b", card("2")))
        val shared = fresh.sharingRowsWith(shown)
        assertEquals(listOf("new", "a", "b"), shared.rails.map { it.id })
        assertSame(shown.rails[0], shared.rails[1])
        assertSame(shown.rails[1], shared.rails[2])
    }

    @Test
    fun `a finished page comes back as it was saved`() {
        val directory = tempDirectory()
        val saved = page(rail("continue-watching", card("1")), rail("popular", card("2"), card("3")))
        store(directory).write("user:profile", saved)
        assertEquals(saved, store(directory).read("user:profile"))
        assertNull(store(directory).read("user:other-profile"))
    }

    @Test
    fun `a card keeps the subtitle and highlight it was drawn with`() {
        val directory = tempDirectory()
        val resuming = card("1").copy(cardSubtitle = "S1 E3 - 20 min left", cardHighlight = true)
        val saved = page(rail("continue-watching", resuming, card("2")))
        store(directory).write("user:profile", saved)
        val read = store(directory).read("user:profile")
        assertEquals(saved, read)
        assertEquals("S1 E3 - 20 min left", read?.rails?.single()?.items?.first()?.cardSubtitle)
    }

    @Test
    fun `live channels and cards that carry their own stream are not written to disk`() {
        val directory = tempDirectory()
        val channel = MediaItem(id = "ch", title = "Channel", type = "live")
        val direct = card("d").copy(directStreamUrl = "https://example.invalid/stream?key=secret")
        val withHeaders = card("h").copy(requestHeaders = mapOf("Authorization" to "Bearer secret"))
        val live = HomeRail("live-row", "Live", listOf(card("x")), isLive = true)
        store(directory).write("user:profile", page(rail("mixed", card("1"), channel, direct, withHeaders), live))
        val read = store(directory).read("user:profile")
        assertEquals(listOf("mixed"), read?.rails?.map { it.id })
        assertEquals(listOf("1"), read?.rails?.single()?.items?.map { it.id })
        assertTrue(directory.listFiles()!!.single().readText().contains("secret").not())
    }

    @Test
    fun `another build's copy and an old copy are not used`() {
        val directory = tempDirectory()
        store(directory, versionCode = 7).write("user:profile", page(rail("a", card("1"))))
        assertNull(store(directory, versionCode = 8).read("user:profile"))
        val monthLater = 1_000_000L + 31L * 24 * 60 * 60 * 1000
        assertNull(store(directory, versionCode = 7, clock = { monthLater }).read("user:profile"))
    }

    @Test
    fun `a page still loading is never saved and long rows are cut`() {
        val directory = tempDirectory()
        val loading = HomeContent(featured = null, rails = listOf(rail("a", card("1"))), pendingRails = listOf(PendingRail("b", "Row b")))
        store(directory).write("user:profile", loading)
        assertNull(store(directory).read("user:profile"))
        val long = page(rail("a", *(1..60).map { card(it.toString()) }.toTypedArray()))
        store(directory).write("user:profile", long)
        assertEquals(20, store(directory).read("user:profile")?.rails?.single()?.items?.size)
    }

    @Test
    fun `a file that does not parse is treated as absent and signing out removes everything`() {
        val directory = tempDirectory()
        val snapshots = store(directory)
        snapshots.write("user:profile", page(rail("a", card("1"))))
        directory.listFiles()!!.single().writeText("{ not json")
        assertNull(store(directory).read("user:profile"))
        snapshots.clear()
        assertTrue(directory.listFiles().isNullOrEmpty())
    }
}
