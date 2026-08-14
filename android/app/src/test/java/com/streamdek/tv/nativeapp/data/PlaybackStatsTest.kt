package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The info panel's claims about a stream, which are the kind that go wrong quietly: a torrent
 * described as "Direct HTTP" is not obviously a bug on screen, it just reads as a plausible fact.
 */
class PlaybackStatsTest {
    @Test
    fun `an info hash is a torrent even when the engine is handed a loopback url`() {
        val torrent = AddonStream(addonId = "torrentio", addonName = "Torrentio", infoHash = "abc123")

        assertEquals(StreamTransport.Torrent, streamTransport(torrent, "http://127.0.0.1:8080/stream/0"))
    }

    @Test
    fun `a usenet post is named by its nzb rather than by the local server serving it`() {
        val usenet = AddonStream(
            addonId = "aio",
            addonName = "AIOStreams",
            nzbUrl = "https://indexer.example/get.nzb",
            servers = listOf("news.example:563"),
        )

        assertEquals(StreamTransport.Usenet, streamTransport(usenet, "http://127.0.0.1:9090/file.mkv"))
    }

    @Test
    fun `a playlist url is HLS rather than plain HTTP`() {
        val live = AddonStream(addonId = "iptv", addonName = "IPTV", url = "https://cdn.example/live.m3u8")

        assertEquals(StreamTransport.Hls, streamTransport(live, "https://cdn.example/live.m3u8?token=x"))
    }

    @Test
    fun `the debrid service holding a source is named alongside the addon that found it`() {
        val cached = AddonStream(addonId = "torrentio", addonName = "Torrentio", cachedBy = listOf("Real-Debrid"))

        assertEquals("Torrentio · Real-Debrid", streamProviderLabel(cached, fallback = null))
    }

    @Test
    fun `an uncached source names only the addon`() {
        val plain = AddonStream(addonId = "torrentio", addonName = "Torrentio")

        assertEquals("Torrentio", streamProviderLabel(plain, fallback = "ignored"))
    }

    @Test
    fun `nothing measured yet prints nothing rather than a zero`() {
        assertNull(formatTransferRate(null))
        assertNull(formatTransferRate(0.0))
        assertNull(formatBitrate(Double.NaN))
    }

    @Test
    fun `transfer rates cross into the unit a viewer would use`() {
        assertEquals("4.2 MB/s", formatTransferRate(4_200_000.0))
        assertEquals("820 KB/s", formatTransferRate(820_000.0))
    }

    @Test
    fun `an rfc 6381 codec string is reduced to the family it names`() {
        // Media3 reports "avc1.640028"; printing that verbatim answers a question nobody asked.
        assertEquals("H.264", prettyCodecName("avc1.640028"))
        assertEquals("HEVC", prettyCodecName("hvc1.2.4.L153.B0"))
        assertEquals("E-AC-3", prettyCodecName("ec-3"))
    }

    @Test
    fun `a resolution carries the shorthand rather than only the pixel count`() {
        assertEquals("1920 × 1080 (1080p)", formatResolution(1920, 1080))
        assertNull(formatResolution(0, 1080))
    }
}
