package com.streamdek.tv.nativeapp.ui.network

import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.PagedRailResponse
import org.junit.Assert.*
import org.junit.Test

class NetworkCatalogPagesTest {
    private fun item(id: String, type: String = "movie") = MediaItem(id = id, title = id, type = type)
    @Test fun clearingSearchCanRestoreTheUntouchedCatalogueAndPage() {
        val catalogue = NetworkCatalogPages().append(PagedRailResponse(results = listOf(item("1")), page = 1, total_pages = 20))
        val search = NetworkCatalogPages().append(PagedRailResponse(results = listOf(item("99")), page = 1, total_pages = 1))
        assertEquals(listOf(item("1")), catalogue.items)
        assertEquals(20, catalogue.totalPages)
        assertEquals(listOf(item("99")), search.items)
    }
    @Test fun emptySearchPagesStillAllowLaterMatches() {
        val empty = NetworkCatalogPages().append(PagedRailResponse(page = 21, total_pages = 30))
        assertTrue(empty.hasMore)
        assertEquals(listOf(item("99")), empty.append(PagedRailResponse(results = listOf(item("99")), page = 22, total_pages = 30)).items)
    }
    @Test fun duplicatesAreRemovedWithoutCombiningMovieAndTvIds() {
        val first = NetworkCatalogPages().append(PagedRailResponse(results = listOf(item("1")), page = 1, total_pages = 2))
        assertEquals(2, first.append(PagedRailResponse(results = listOf(item("1"), item("1", "tv")), page = 2, total_pages = 2)).items.size)
        assertFalse(first.append(PagedRailResponse(page = 1, total_pages = 10)).hasMore)
    }
}
