package com.streamdek.tv.nativeapp.mediaserver

import com.streamdek.tv.nativeapp.data.HomePreferences
import com.streamdek.tv.nativeapp.data.MediaServerContinueLocation
import com.streamdek.tv.nativeapp.data.MediaServerContinueLocations
import com.streamdek.tv.nativeapp.mediaserver.jellyfin.JellyfinClientIdentity
import com.streamdek.tv.nativeapp.mediaserver.jellyfin.MediaBrowserFlavor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Emby as the second member of the Jellyfin family: where the two differ, and where they must not. */
class EmbyFlavorTest {
    private val identity = JellyfinClientIdentity(client = "StreamDek", deviceName = "TV", deviceId = "dev-1", version = "2.3.0")

    @Test fun `Emby is told who StreamDek is in its own header, with the token apart`() {
        val headers = MediaBrowserFlavor.Emby.requestHeaders(identity, "secret")
        assertTrue(headers.getValue("Authorization").startsWith("Emby Client=\"StreamDek\""))
        assertFalse(headers.getValue("Authorization").contains("secret"))
        assertEquals("secret", headers["X-Emby-Token"])
    }

    @Test fun `Jellyfin keeps its single MediaBrowser header`() {
        val headers = MediaBrowserFlavor.Jellyfin.requestHeaders(identity, "secret")
        assertEquals(setOf("Authorization"), headers.keys)
        assertTrue(headers.getValue("Authorization").startsWith("MediaBrowser "))
        assertTrue(headers.getValue("Authorization").contains("Token=\"secret\""))
    }

    @Test fun `media requests carry Emby's token header and never a token in the URL`() {
        assertEquals("X-Emby-Token" to "secret", MediaBrowserFlavor.Emby.mediaHeader(identity, "secret"))
        assertEquals("Authorization", MediaBrowserFlavor.Jellyfin.mediaHeader(identity, "secret").first)
    }

    @Test fun `a server of the other kind is recognised by the name it gives itself`() {
        assertTrue(MediaBrowserFlavor.Emby.isSibling("Jellyfin Server"))
        assertTrue(MediaBrowserFlavor.Jellyfin.isSibling("Emby Server"))
        assertFalse(MediaBrowserFlavor.Emby.isSibling("Emby Server"))
        assertFalse(MediaBrowserFlavor.Emby.isSibling(null))
    }

    @Test fun `only Jellyfin offers Quick Connect and only Emby offers Emby Connect`() {
        assertTrue(MediaBrowserFlavor.Jellyfin.quickConnect)
        assertFalse(MediaBrowserFlavor.Emby.quickConnect)
        assertTrue(MediaBrowserFlavor.Emby.embyConnect)
        assertFalse(MediaBrowserFlavor.Emby.currentRoutes)
        assertEquals(MediaBrowserFlavor.Emby, MediaBrowserFlavor.of(EMBY_PROVIDER_ID))
        assertNull(MediaBrowserFlavor.of(PLEX_PROVIDER_ID))
    }

    @Test fun `Emby's Continue Watching location is its own setting`() {
        val locations = MediaServerContinueLocations.from(HomePreferences(embyContinueWatchingLocation = "server"))
        assertEquals(MediaServerContinueLocation.ServerLibrary, locations.of(EMBY_PROVIDER_ID))
        assertEquals(MediaServerContinueLocation.StreamDek, locations.of(JELLYFIN_PROVIDER_ID))
        assertEquals("embyContinueWatchingLocation", MediaServerContinueLocations.keyFor(EMBY_PROVIDER_ID))
    }
}
