package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plex and Jellyfin as sources for catalogue titles, and where their Continue Watching shows. */
class MediaServerUnifiedTest {
    @Test
    fun catalogueIdsComeFromTheIdAndTheDetail() {
        assertEquals(TitleIds(603, "tt0133093"), TitleIds.ofCatalogue("603", null, "tt0133093"))
        assertEquals(TitleIds(603, null), TitleIds.ofCatalogue("tmdb:603", 0, null))
        assertEquals(TitleIds(77, "tt0133093"), TitleIds.ofCatalogue("tt0133093", 77, null))
        assertTrue(TitleIds.ofCatalogue("kitsu:1", 0, null).isEmpty)
    }

    @Test
    fun aSharedIdIsAMatch() {
        val wanted = TitleIds(603, "tt0133093")
        assertTrue(isSameServerTitle("movie", wanted, "movie", TitleIds(603, null)))
        assertTrue(isSameServerTitle("movie", wanted, "movie", TitleIds(null, "TT0133093")))
        assertTrue(isSameServerTitle("series", TitleIds(1399, null), "tv", TitleIds(1399, null)))
    }

    @Test
    fun differentIdsOrKindsAreNeverAMatch() {
        assertFalse(isSameServerTitle("movie", TitleIds(603, "tt0133093"), "movie", TitleIds(10000, "tt9999999")))
        assertFalse(isSameServerTitle("movie", TitleIds(603, null), "movie", TitleIds(null, null)))
        assertFalse(isSameServerTitle("movie", TitleIds(1399, null), "tv", TitleIds(1399, null)))
    }

    @Test
    fun theLocationSettingDefaultsToStreamDek() {
        assertEquals(MediaServerContinueLocation.StreamDek, MediaServerContinueLocation.fromKey(null))
        assertEquals(MediaServerContinueLocation.StreamDek, MediaServerContinueLocation.fromKey("nonsense"))
        assertEquals(MediaServerContinueLocation.ServerLibrary, MediaServerContinueLocation.fromKey("server"))
        val locations = MediaServerContinueLocations.from(HomePreferences(jellyfinContinueWatchingLocation = "server"))
        assertEquals(MediaServerContinueLocation.StreamDek, locations.of("plex"))
        assertEquals(MediaServerContinueLocation.ServerLibrary, locations.of("jellyfin"))
        assertEquals("jellyfinContinueWatchingLocation", MediaServerContinueLocations.keyFor("jellyfin"))
    }
}
