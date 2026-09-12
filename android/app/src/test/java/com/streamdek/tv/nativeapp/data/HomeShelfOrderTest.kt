package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order Home draws its rows in while it is still filling.
 *
 * The behaviour under test is layout stability: a row that resolves late must land in the space
 * already being held for it, never be inserted above rows that are already on screen. Continue
 * Watching is the case that matters, because it is reserved first and built from an account read
 * that is routinely slower than the catalogue requests beside it.
 */
class HomeShelfOrderTest {
    private val slotOrder = listOf("continue-watching", "new-episodes", "trending", "addon-catalogs")

    private fun item(id: String) = MediaItem(id = id, type = "movie", title = "Title $id")

    private fun rail(id: String) = HomeRail(id, id, listOf(item(id)))

    private fun pending(id: String) = PendingRail(id, id)

    /** Stands in for the repository's own ordering, which these cases do not exercise. */
    private val unordered: (List<HomeRail>) -> List<HomeRail> = { it }

    @Test
    fun `a reserved row keeps its place above rows that have arrived`() {
        val shelves = homeShelfOrder(
            slotOrder = slotOrder,
            resolved = mapOf("trending" to listOf(rail("trending"))),
            pending = mapOf("continue-watching" to pending("continue-watching")),
            orderRails = unordered,
        )

        assertEquals(listOf("continue-watching", "trending"), shelves.map { it.id })
        assertTrue(shelves.first() is HomeShelfSlot.Pending)
    }

    @Test
    fun `a row that resolves replaces its own slot rather than moving`() {
        val whileLoading = homeShelfOrder(
            slotOrder = slotOrder,
            resolved = mapOf("trending" to listOf(rail("trending"))),
            pending = mapOf("continue-watching" to pending("continue-watching")),
            orderRails = unordered,
        )
        val afterArrival = homeShelfOrder(
            slotOrder = slotOrder,
            resolved = mapOf(
                "continue-watching" to listOf(rail("continue-watching")),
                "trending" to listOf(rail("trending")),
            ),
            pending = emptyMap(),
            orderRails = unordered,
        )

        // Same positions before and after: nothing on screen moved, the skeleton became the row.
        assertEquals(whileLoading.map { it.id }, afterArrival.map { it.id })
        assertTrue(whileLoading.first() is HomeShelfSlot.Pending)
        assertTrue(afterArrival.first() is HomeShelfSlot.Loaded)
    }

    @Test
    fun `a slot that resolved to nothing leaves no gap`() {
        val shelves = homeShelfOrder(
            slotOrder = slotOrder,
            resolved = mapOf(
                "continue-watching" to listOf(HomeRail("continue-watching", "Continue Watching", emptyList())),
                "trending" to listOf(rail("trending")),
            ),
            pending = mapOf("addon-catalogs" to pending("addon-catalogs")),
            orderRails = unordered,
        )

        assertEquals(listOf("trending", "addon-catalogs"), shelves.map { it.id })
    }

    @Test
    fun `reserved slots are ordered by the caller's row ordering too`() {
        // Reversing stands in for a saved layout: the reserved slot has to move with it, otherwise
        // the row would arrive somewhere its skeleton never was.
        val shelves = homeShelfOrder(
            slotOrder = slotOrder,
            resolved = mapOf("trending" to listOf(rail("trending"))),
            pending = mapOf("addon-catalogs" to pending("addon-catalogs")),
            orderRails = { rails -> rails.reversed() },
        )

        assertEquals(listOf("addon-catalogs", "trending"), shelves.map { it.id })
    }

    @Test
    fun `an empty home with nothing left to fetch is settled`() {
        val nothingToShow = HomeContent(featured = null, rails = emptyList(), pendingRails = emptyList())
        val stillFetching = HomeContent(
            featured = null,
            rails = emptyList(),
            pendingRails = listOf(pending("continue-watching")),
            shelves = listOf(HomeShelfSlot.Pending(pending("continue-watching"))),
        )

        assertTrue(nothingToShow.priorityResolved)
        assertFalse(stillFetching.priorityResolved)
    }

    @Test
    fun `everything arrived is every slot loaded`() {
        val shelves = homeShelfOrder(
            slotOrder = slotOrder,
            resolved = mapOf(
                "continue-watching" to listOf(rail("continue-watching")),
                "trending" to listOf(rail("trending")),
            ),
            pending = emptyMap(),
            orderRails = unordered,
        )

        assertTrue(shelves.all { it is HomeShelfSlot.Loaded })
        assertEquals(listOf("continue-watching", "trending"), shelves.map { it.id })
    }

    @Test
    fun `priority is unresolved only while a row above the entry row is outstanding`() {
        val awaitingContinueWatching = HomeContent(
            featured = null,
            rails = listOf(rail("trending")),
            pendingRails = listOf(pending("continue-watching")),
            shelves = listOf(
                HomeShelfSlot.Pending(pending("continue-watching")),
                HomeShelfSlot.Loaded(rail("trending")),
            ),
        )
        val awaitingRowsBelowOnly = HomeContent(
            featured = null,
            rails = listOf(rail("continue-watching"), rail("trending")),
            pendingRails = listOf(pending("new-episodes")),
            shelves = listOf(
                HomeShelfSlot.Loaded(rail("continue-watching")),
                HomeShelfSlot.Pending(pending("new-episodes")),
                HomeShelfSlot.Loaded(rail("trending")),
            ),
        )

        // A row still resolving above the entry row can still become the entry row.
        assertFalse(awaitingContinueWatching.priorityResolved)
        // One resolving below it cannot, so Home commits its first frame and its highlight now and
        // lets the rest stream into the slots held for it.
        assertTrue(awaitingRowsBelowOnly.priorityResolved)
        assertFalse(awaitingRowsBelowOnly.isComplete)
    }
}
