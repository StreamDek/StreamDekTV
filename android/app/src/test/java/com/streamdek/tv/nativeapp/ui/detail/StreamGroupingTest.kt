package com.streamdek.tv.nativeapp.ui.detail

import com.streamdek.tv.nativeapp.data.AddonStream
import com.streamdek.tv.nativeapp.data.addonStreamDisplayLabel
import com.streamdek.tv.nativeapp.data.streamTextNamesTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamGroupingTest {
    private fun stream(
        addonName: String = "Torrentio",
        name: String? = null,
        title: String? = null,
        quality: String? = null,
        size: String? = null,
    ) = AddonStream(
        addonId = "addon",
        addonName = addonName,
        name = name,
        title = title,
        url = "https://example.test/a.mkv",
        quality = quality,
        size = size,
    )

    @Test
    fun `quality comes from the source's own field before its text`() {
        assertEquals(
            StreamQualityTier.Hd,
            streamQualityTier(stream(quality = "720p", title = "Movie.2160p.sample.mkv")),
        )
    }

    @Test
    fun `quality words are matched whole, so UHD and HDR do not read as HD`() {
        assertEquals(StreamQualityTier.Uhd, streamQualityTier(stream(title = "Movie 2026 UHD BluRay")))
        assertEquals(StreamQualityTier.Fhd, streamQualityTier(stream(title = "Movie 2026 1080p HDR x265")))
        assertEquals(StreamQualityTier.Hd, streamQualityTier(stream(title = "Movie 2026 720p WEB-DL")))
        assertEquals(StreamQualityTier.Cam, streamQualityTier(stream(title = "Movie 2026 1080p HDCAM")))
        assertEquals(StreamQualityTier.Unknown, streamQualityTier(stream(name = "Server 3")))
    }

    @Test
    fun `size reads gigabytes and is not fooled by a bitrate`() {
        assertEquals(6.0, streamSizeGigabytes(stream(size = "6 GB"))!!, 0.001)
        assertEquals(2048.0, streamSizeGigabytes(stream(size = "2 TB"))!!, 0.001)
        assertEquals(null, streamSizeGigabytes(stream(title = "Movie 1080p ~7.71 Mbps")))
    }

    @Test
    fun `bands run highest first on Auto and lead with the chosen quality otherwise`() {
        val tiers = listOf(StreamQualityTier.Uhd, StreamQualityTier.Fhd, StreamQualityTier.Hd)
        assertEquals(tiers, tiers.sortedBy { streamQualityBandOrder(it, "Auto") })
        assertEquals(
            listOf(StreamQualityTier.Fhd, StreamQualityTier.Uhd, StreamQualityTier.Hd),
            tiers.sortedBy { streamQualityBandOrder(it, "1080p") },
        )
    }

    @Test
    fun `entries carry a heading per source and per band, largest file first`() {
        val entries = buildStreamListEntries(
            streams = listOf(
                stream(addonName = "Torrentio", title = "Movie 1080p", size = "3 GB"),
                stream(addonName = "Torrentio", title = "Movie 2160p", size = "20 GB"),
                stream(addonName = "Torrentio", title = "Movie 1080p", size = "9 GB"),
                stream(addonName = "Comet", title = "Movie 1080p", size = "6 GB"),
            ),
            preferredQuality = "Auto",
            includeSourceHeadings = true,
        )
        assertEquals(
            listOf(
                "source:Torrentio(3)",
                "band:4K(1)", "20 GB",
                "band:1080p(2)", "9 GB", "3 GB",
                "source:Comet(1)",
                "band:1080p(1)", "6 GB",
            ),
            entries.map { entry ->
                when (entry) {
                    is StreamListEntry.SourceHeading -> "source:${entry.source}(${entry.count})"
                    is StreamListEntry.QualityHeading -> "band:${entry.tier.label}(${entry.count})"
                    is StreamListEntry.Result -> entry.stream.size.orEmpty()
                }
            },
        )
    }

    @Test
    fun `a single-source list can be built without source headings`() {
        val entries = buildStreamListEntries(
            streams = listOf(stream(title = "Movie 1080p", size = "3 GB")),
            preferredQuality = "Auto",
            includeSourceHeadings = false,
        )
        assertTrue(entries.none { it is StreamListEntry.SourceHeading })
    }

    @Test
    fun `an unknown size sorts to the end of its band rather than to the front`() {
        val entries = buildStreamListEntries(
            streams = listOf(
                stream(title = "Movie 1080p"),
                stream(title = "Movie 1080p", size = "4 GB"),
            ),
            preferredQuality = "Auto",
            includeSourceHeadings = false,
        )
        assertEquals(
            listOf("4 GB", ""),
            entries.filterIsInstance<StreamListEntry.Result>().map { it.stream.size.orEmpty() },
        )
    }

    @Test
    fun `a result described only by resolution and size is named after the title being watched`() {
        assertEquals(
            "Dune Part Two | 1080p",
            addonStreamDisplayLabel(stream(name = "1080p"), "Dune Part Two"),
        )
        assertEquals(
            "Dune.Part.Two.2024.2160p.WEB-DL",
            addonStreamDisplayLabel(stream(name = "Dune.Part.Two.2024.2160p.WEB-DL"), "Fallback"),
        )
    }

    @Test
    fun `descriptor-only text is recognised as naming nothing`() {
        assertFalse(streamTextNamesTitle("1080p"))
        assertFalse(streamTextNamesTitle("2160p | 12.4 GB | HEVC"))
        assertFalse(streamTextNamesTitle(null))
        assertTrue(streamTextNamesTitle("Sinners 2025 1080p"))
    }
}
