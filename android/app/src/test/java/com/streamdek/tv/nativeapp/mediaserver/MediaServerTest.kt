package com.streamdek.tv.nativeapp.mediaserver

import com.streamdek.tv.nativeapp.data.ContinueWatchingItem
import com.streamdek.tv.nativeapp.data.reconcileContinueWatching
import com.streamdek.tv.nativeapp.mediaserver.plex.PlexConnectionRanking
import com.streamdek.tv.nativeapp.mediaserver.plex.PlexDeviceCaps
import com.streamdek.tv.nativeapp.mediaserver.plex.PlexMapping
import com.streamdek.tv.nativeapp.mediaserver.plex.PlexMappingContext
import com.streamdek.tv.nativeapp.mediaserver.plex.PlexMediaFacts
import com.streamdek.tv.nativeapp.mediaserver.plex.PlexMetadata
import com.streamdek.tv.nativeapp.mediaserver.plex.PlexPlaybackMode
import com.streamdek.tv.nativeapp.mediaserver.plex.PlexPlaybackPlanner
import com.streamdek.tv.nativeapp.mediaserver.plex.PlexTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaServerReferenceTest {
    @Test fun `a reference survives navigation and is opaque`() {
        val ref = MediaServerReference("plex", "abc123", "4567")
        assertEquals(ref, MediaServerReference.decode(ref.encode()))
        assertFalse(ref.encode().contains("4567"))
        assertFalse(ref.encode().contains('/'))
        for (id in listOf("tt0111161", "sd-addon:a:b:c", "sd-media:bad", "sd-media:a:b", null)) assertNull(MediaServerReference.decode(id))
        assertEquals("plex", MediaServerReference.providerOfSource(ref.sourceId))
        assertNull(MediaServerReference.providerOfSource("addon.example"))
    }

    @Test fun `home row ids take the add-on shape and never collide with add-ons`() {
        val id = mediaServerHomeRowId("plex", "srv", "tv", "recent:1", 3)
        assertEquals("addon:mediaserver.plex.srv:series:recent-1:3", id)
        assertTrue(isMediaServerHomeRowSource(id.split(":")[1]))
    }

    @Test fun `the endpoint never prints its token`() {
        val endpoint = MediaServerEndpoint("s", "http://192.168.1.2:32400", true, false, "secret-token")
        assertFalse(endpoint.toString().contains("secret-token"))
    }
}

class PlexPlanningTest {
    private val tv4k = PlexDeviceCaps(mapOf("h264" to (3840 to 2160), "hevc" to (3840 to 2160)), "Auto")
    private val hd = PlexDeviceCaps(mapOf("h264" to (1920 to 1080)), "Auto")

    @Test fun `playable media on the home network plays directly with fallbacks behind it`() {
        val plan = PlexPlaybackPlanner.plan(PlexMediaFacts("mkv", "hevc", "truehd", 3840, 2160, 60_000), tv4k, MediaServerRoute.Local, 8_000)
        assertEquals(listOf(PlexPlaybackMode.DirectPlay, PlexPlaybackMode.DirectStream, PlexPlaybackMode.Transcode, PlexPlaybackMode.Transcode), plan.map { it.mode })
    }

    @Test fun `a codec the device cannot decode is never tried directly`() {
        val plan = PlexPlaybackPlanner.plan(PlexMediaFacts("mkv", "hevc", "aac", 3840, 2160, 30_000), hd, MediaServerRoute.Local, null)
        assertEquals(PlexPlaybackMode.Transcode, plan.first().mode)
    }

    @Test fun `software-friendly video still direct plays through mpv`() {
        val plan = PlexPlaybackPlanner.plan(PlexMediaFacts("avi", "mpeg4", "mp3", 720, 400, 1_500), hd, MediaServerRoute.Local, null)
        assertEquals(PlexPlaybackMode.DirectPlay, plan.first().mode)
    }

    @Test fun `a container ExoPlayer cannot open is direct streamed when ExoPlayer is forced`() {
        val plan = PlexPlaybackPlanner.plan(PlexMediaFacts("avi", "h264", "aac", 1280, 720, 3_000), hd.copy(engine = "ExoPlayer"), MediaServerRoute.Local, null)
        assertEquals(PlexPlaybackMode.DirectStream, plan.first().mode)
    }

    @Test fun `remote bandwidth limits transcode, relay is capped`() {
        val remote = PlexPlaybackPlanner.plan(PlexMediaFacts("mkv", "h264", "aac", 1920, 1080, 12_000), hd, MediaServerRoute.Remote, 8_000)
        assertEquals(PlexPlaybackMode.Transcode, remote.first().mode)
        assertEquals(8_000, remote.first().maxBitrateKbps)
        val relay = PlexPlaybackPlanner.plan(PlexMediaFacts("mkv", "h264", "aac", 1920, 1080, 12_000), hd, MediaServerRoute.Relay, null)
        assertEquals(PlexPlaybackPlanner.RELAY_MAX_KBPS, relay.first().maxBitrateKbps)
    }

    @Test fun `connections are tried local first, https first, remembered first`() {
        val endpoints = listOf(
            MediaServerEndpoint("s", "https://relay.plex.direct", false, true, null),
            MediaServerEndpoint("s", "https://remote.plex.direct", false, false, null),
            MediaServerEndpoint("s", "http://192.168.1.2:32400", true, false, null),
            MediaServerEndpoint("s", "https://192-168-1-2.plex.direct:32400", true, false, null),
        )
        assertEquals(
            listOf("https://192-168-1-2.plex.direct:32400", "http://192.168.1.2:32400", "https://remote.plex.direct", "https://relay.plex.direct"),
            PlexConnectionRanking.order(endpoints, null).map { it.uri },
        )
        assertEquals("https://remote.plex.direct", PlexConnectionRanking.order(endpoints, "https://remote.plex.direct").first().uri)
    }
}

class PlexMappingTest {
    private val context = PlexMappingContext("srv", "http://192.168.1.2:32400", "Plex")

    @Test fun `a film keeps its external ids and gets token-free artwork`() {
        val item = PlexMapping.item(
            PlexMetadata(ratingKey = "10", type = "movie", title = "Heat", year = 1995, thumb = "/library/metadata/10/thumb/1",
                guids = listOf(PlexTag(id = "imdb://tt0113277"), PlexTag(id = "tmdb://949"))),
            context,
        )!!
        assertEquals(949, item.tmdbId)
        assertEquals("tt0113277", item.imdbId)
        assertEquals("movie", item.type)
        assertTrue(item.poster!!.startsWith("http://192.168.1.2:32400/photo/:/transcode"))
        assertFalse(item.poster!!.contains("Token", ignoreCase = true))
        assertEquals("mediaserver:plex:srv", item.sourceAddonId)
    }

    @Test fun `an episode becomes its series`() {
        val item = PlexMapping.item(PlexMetadata(ratingKey = "99", type = "episode", title = "Pilot", grandparentTitle = "Show", grandparentRatingKey = "7"), context)!!
        assertEquals("Show", item.title)
        assertEquals("tv", item.type)
        assertEquals("7", MediaServerReference.decode(item.id)!!.itemKey)
    }

    @Test fun `an in-progress episode resumes at its position`() {
        val resume = PlexMapping.resume(
            PlexMetadata(type = "episode", grandparentRatingKey = "7", grandparentTitle = "Show", parentIndex = 2, index = 5, viewOffset = 600_000, duration = 1_800_000, lastViewedAt = 1_800_000_000),
            context,
            seriesGuids = PlexMetadata(guids = listOf(PlexTag(id = "tmdb://1399"))),
        )!!
        assertEquals(1399, resume.tmdbId)
        assertEquals(600.0, resume.item.positionSec!!, 0.01)
        assertEquals(2, resume.item.seasonNumber)
        assertEquals(5, resume.item.episodeNumber)
    }
}

class ContinueWatchingReconcileTest {
    private fun row(id: String, tmdb: Int, type: String, at: String?) = ContinueWatchingItem(id = id, tmdbId = tmdb, title = id, type = type, updatedAt = at)
    private fun server(id: String, tmdb: Int?, type: String, atMs: Long) =
        MediaServerResume(ContinueWatchingItem(id = id, tmdbId = tmdb ?: 0, title = id, type = type), atMs, tmdb, null)

    @Test fun `the same title appears once, newest wins`() {
        val streamDek = listOf(row("949", 949, "movie", "2026-09-01T00:00:00Z"))
        val newer = reconcileContinueWatching(streamDek, listOf(server("sd-media:x", 949, "movie", java.time.Instant.parse("2026-09-02T00:00:00Z").toEpochMilli())))
        assertEquals(listOf("sd-media:x"), newer.map { it.id })
        val older = reconcileContinueWatching(streamDek, listOf(server("sd-media:x", 949, "movie", java.time.Instant.parse("2026-08-01T00:00:00Z").toEpochMilli())))
        assertEquals(listOf("949"), older.map { it.id })
    }

    @Test fun `new server titles are placed by recency without reordering the rest`() {
        val streamDek = listOf(row("a", 1, "movie", "2026-09-03T00:00:00Z"), row("b", 2, "movie", "2026-09-01T00:00:00Z"))
        val merged = reconcileContinueWatching(streamDek, listOf(server("p", 3, "movie", java.time.Instant.parse("2026-09-02T00:00:00Z").toEpochMilli())))
        assertEquals(listOf("a", "p", "b"), merged.map { it.id })
    }

    @Test fun `a series is one card whichever episode each side is on`() {
        val streamDek = listOf(row("1399", 1399, "tv", "2026-09-01T00:00:00Z"))
        val merged = reconcileContinueWatching(streamDek, listOf(server("sd-media:s", 1399, "tv", java.time.Instant.parse("2026-09-05T00:00:00Z").toEpochMilli())))
        assertEquals(1, merged.size)
    }
}
