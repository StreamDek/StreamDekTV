package com.streamdek.tv.nativeapp.ui.home

import com.streamdek.tv.nativeapp.data.HomeRail
import com.streamdek.tv.nativeapp.data.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFocusPolicyTest {
    @Test
    fun `first available row skips absent optional rows`() {
        val rows = listOf(
            HomeRail("continue-watching", "Continue Watching", emptyList()),
            HomeRail("new-episodes", "New Episodes", emptyList()),
            HomeRail("popular-movies", "Popular Movies", listOf(MediaItem(id = "1", type = "movie", title = "Film"))),
        )

        assertEquals(2, firstFocusableHomeRowIndex(rows))
    }

    @Test
    fun `continue watching wins when it has a focusable card`() {
        val rows = listOf(
            HomeRail("continue-watching", "Continue Watching", listOf(MediaItem(id = "1", type = "movie", title = "Film"))),
            HomeRail("new-episodes", "New Episodes", listOf(MediaItem(id = "2", type = "tv", title = "Show"))),
        )

        assertEquals(0, firstFocusableHomeRowIndex(rows))
    }

    @Test
    fun `home without continue watching still has an entry row`() {
        val rows = listOf(
            HomeRail("popular-movies", "Popular Movies", listOf(MediaItem(id = "1", type = "movie", title = "Film"))),
            HomeRail("trending", "Trending", listOf(MediaItem(id = "2", type = "tv", title = "Show"))),
            HomeRail("recently-added", "Recently Added", listOf(MediaItem(id = "3", type = "movie", title = "New"))),
        )

        assertEquals(0, firstFocusableHomeRowIndex(rows))
    }

    @Test
    fun `no row can take the highlight when every row is empty`() {
        val rows = listOf(
            HomeRail("continue-watching", "Continue Watching", emptyList()),
            HomeRail("trending", "Trending", emptyList()),
        )

        assertEquals(-1, firstFocusableHomeRowIndex(rows))
    }

    /** The row the viewer left is still there: a return goes back to it, not to the top. */
    @Test
    fun `the remembered row is kept when it survives`() {
        assertEquals(3, nearestFocusableHomeRowIndex(populatedRows, preferred = 3))
    }

    /** Its neighbour below is preferred, because a removed row is replaced by the one under it. */
    @Test
    fun `a row that emptied falls forward to its neighbour`() {
        val rows = populatedRows.toMutableList().also {
            it[2] = HomeRail("popular-movies", "Popular Movies", emptyList())
        }

        assertEquals(3, nearestFocusableHomeRowIndex(rows, preferred = 2))
    }

    /** Nothing below survived, so the search turns back rather than jumping to the first row. */
    @Test
    fun `the row above is taken when nothing below survives`() {
        val rows = populatedRows.mapIndexed { index, row ->
            if (index >= 3) row.copy(items = emptyList()) else row
        }

        assertEquals(2, nearestFocusableHomeRowIndex(rows, preferred = 4))
    }

    /** Everything near it went: fall back to the ordinary entry row rather than nowhere. */
    @Test
    fun `an emptied neighbourhood falls back to the first available row`() {
        val rows = listOf(
            HomeRail("continue-watching", "Continue Watching", item("1")),
            HomeRail("new-episodes", "New Episodes", emptyList()),
            HomeRail("trending", "Trending", emptyList()),
        )

        assertEquals(0, nearestFocusableHomeRowIndex(rows, preferred = 2))
    }

    /** A remembered index that now points past the end of a shorter row set. */
    @Test
    fun `an out of range remembered row still resolves`() {
        assertEquals(4, nearestFocusableHomeRowIndex(populatedRows, preferred = 11))
    }

    @Test
    fun `no row resolves when the shelves are empty`() {
        assertEquals(-1, nearestFocusableHomeRowIndex(emptyList(), preferred = 2))
    }

    private fun item(id: String) = listOf(MediaItem(id = id, type = "movie", title = "Title $id"))

    private val populatedRows = listOf(
        HomeRail("continue-watching", "Continue Watching", item("1")),
        HomeRail("new-episodes", "New Episodes", item("2")),
        HomeRail("popular-movies", "Popular Movies", item("3")),
        HomeRail("trending", "Trending Shows", item("4")),
        HomeRail("recently-added", "Recently Added", item("5")),
    )
}
