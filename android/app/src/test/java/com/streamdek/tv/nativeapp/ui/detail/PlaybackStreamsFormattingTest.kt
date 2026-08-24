package com.streamdek.tv.nativeapp.ui.detail

import com.streamdek.tv.nativeapp.data.AddonStream
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStreamsFormattingTest {
    @Test
    fun `quality column extracts only the quality token from malformed provider fields`() {
        val stream = AddonStream(quality = "1080p 🍿 The End of Oak Street (2026)")

        assertEquals("1080P", streamQualityLabel(stream, "fallback title"))
    }

    @Test
    fun `size column extracts only the size token from provider fields`() {
        val stream = AddonStream(size = "2.1 GB | The End of Oak Street")

        assertEquals("2.1 GB", streamSizeLabel(stream, "fallback title"))
    }

    @Test
    fun `release size wins over a smaller provider response size`() {
        val stream = AddonStream(
            size = "9.6 MB",
            title = "The.End.of.Oak.Street.2026.1080p [6.34 GB]",
        )

        assertEquals("6.34 GB", streamSizeLabel(stream, "HdHub 1080p 6.34 GB"))
    }

    @Test
    fun `bitrate is never displayed as file size`() {
        val stream = AddonStream(title = "Movie 1080p 9.6 Mbps")

        assertEquals(null, streamSizeLabel(stream, "Movie 1080p 9.6 Mbps"))
    }

    @Test
    fun `raw mode preserves addon line breaks and wording`() {
        val stream = AddonStream(
            addonName = "AIOStreams",
            name = "Deepbrid\n2160p",
            title = "Cached release\nDV | Atmos\n17.5 GB",
        )

        assertEquals(
            "Deepbrid\n2160p" to "Cached release\nDV | Atmos\n17.5 GB",
            rawAddonStreamText(stream),
        )
    }

    @Test
    fun `raw mode uses filename only when addon sent no display text`() {
        assertEquals(
            "Movie.Release.mkv" to null,
            rawAddonStreamText(AddonStream(filename = "Movie.Release.mkv")),
        )
    }
}
