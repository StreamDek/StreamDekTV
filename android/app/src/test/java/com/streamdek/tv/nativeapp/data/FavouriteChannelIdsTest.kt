package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FavouriteChannelIdsTest {
    private fun channel(id: String, url: String? = null, headers: Map<String, String> = emptyMap()) =
        MediaItem(id = id, title = "Channel", type = "live", directStreamUrl = url, requestHeaders = headers)

    private val fullId = "cs:" + "a".repeat(700)
    private val cutId = fullId.take(ACCOUNT_FAVOURITE_ID_LIMIT)

    @Test
    fun `cut id matches the channel it was cut from`() {
        assertTrue(favouriteChannelIdMatches(cutId, fullId))
        assertTrue(favouriteChannelIdMatches(fullId, fullId))
        assertFalse(favouriteChannelIdMatches(cutId, "cs:" + "b".repeat(700)))
        // A short id is only ever an exact match: it was never cut, so a prefix means another channel.
        assertFalse(favouriteChannelIdMatches("m3u:1", "m3u:12"))
    }

    @Test
    fun `restores full ids from known channels and collapses duplicates`() {
        val favourites = listOf(channel(cutId), channel(cutId), channel("m3u:1"))
        val restored = restoreTruncatedFavouriteIds(favourites, listOf(channel(fullId)))
        assertEquals(listOf(fullId, "m3u:1"), restored.map { it.id })
    }

    @Test
    fun `returns the same list when nothing needs restoring`() {
        val favourites = listOf(channel(cutId))
        assertSame(favourites, restoreTruncatedFavouriteIds(favourites, listOf(channel("cs:other"))))
    }

    @Test
    fun `account copy keeps the TV full ids, stream links and headers`() {
        val local = listOf(channel(fullId), channel("m3u:1", url = "https://stream", headers = mapOf("Referer" to "x")))
        val merged = mergeAccountFavourites(listOf(channel(cutId), channel("m3u:1")), local)
        assertEquals(listOf(fullId, "m3u:1"), merged.map { it.id })
        assertEquals("https://stream", merged[1].directStreamUrl)
        assertEquals(mapOf("Referer" to "x"), merged[1].requestHeaders)
    }
}
