package com.streamdek.tv.nativeapp.ui.detail

import com.streamdek.tv.nativeapp.data.AddonStream
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackStreamsFormattingTest {
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
