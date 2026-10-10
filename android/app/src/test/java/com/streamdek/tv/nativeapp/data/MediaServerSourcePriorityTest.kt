package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** "Prefer media server source" on the television: the same tiers and in-tier order as the phone. */
class MediaServerSourcePriorityTest {
    private fun stream(title: String, addonId: String, source: String? = null, quality: String? = null, size: String? = null) =
        AddonStream(addonId = addonId, addonName = addonId, title = title, url = "https://x/$title", quality = quality, size = size, source = source)

    private val plexPlay4k = stream("Plex DP", "mediaserver:plex:s1", "plex:directplay", "4K", "60 GB")
    private val jellyfinPlay1080 = stream("Jellyfin DP", "mediaserver:jellyfin:s2", "jellyfin:directplay", "1080p", "12 GB")
    private val embyStream4k = stream("Emby DS", "mediaserver:emby:s3", "emby:directstream", "4K")
    private val plexTranscode = stream("Plex transcode", "mediaserver:plex:s1", "plex:transcode", "1080p")
    private val addon = stream("Movie 2160p DV REMUX", "addon.a")

    /** What rankStreams puts first: tier, then in-tier quality. */
    private fun order(streams: List<AddonStream>, enabled: Boolean = true, preferredQuality: String = "best"): List<AddonStream> {
        val priority = MediaServerPriority.of(streams, enabled, preferredQuality)
        return streams.sortedWith(compareBy<AddonStream> { priority.tier(it) }.thenByDescending { priority.quality(it) })
    }

    @Test fun `server play methods lead in tiers and transcodes trail`() {
        assertEquals(listOf(plexPlay4k, jellyfinPlay1080, embyStream4k, addon, plexTranscode), order(listOf(plexTranscode, addon, embyStream4k, jellyfinPlay1080, plexPlay4k)))
    }

    @Test fun `a compatible Direct Play outranks a higher resolution Direct Stream`() {
        assertEquals(jellyfinPlay1080, order(listOf(embyStream4k, jellyfinPlay1080)).first())
    }

    @Test fun `the preferred quality decides between copies that play the same way`() {
        assertEquals(jellyfinPlay1080, order(listOf(plexPlay4k, jellyfinPlay1080), preferredQuality = "1080p").first())
    }

    @Test fun `off, nothing is reordered`() {
        val input = listOf(plexTranscode, addon, embyStream4k)
        assertEquals(input, order(input, enabled = false))
    }

    @Test fun `classified by the planner's play method, and only for server sources`() {
        assertEquals(SourceTier.ServerDirectPlay, sourceTierOf(plexPlay4k))
        assertEquals(SourceTier.ServerDirectStream, sourceTierOf(embyStream4k))
        assertEquals(SourceTier.ServerTranscode, sourceTierOf(plexTranscode))
        assertEquals(SourceTier.AddonOrPlugin, sourceTierOf(stream("x", "addon.a", "plex:directplay")))
    }
}
