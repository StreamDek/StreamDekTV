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
}
