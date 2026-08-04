package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlaybackHandoffTest {
    @Test
    fun `handoff preserves source headers quality episode and position`() {
        val request = playbackRequestFromHandoff(
            PlaybackHandoffPayload(
                mediaId = "603",
                mediaType = "tv",
                imdbId = "tt0133093",
                title = "The Matrix",
                seasonNumber = 2,
                episodeNumber = 4,
                episodeTitle = "Example",
                positionSeconds = 812.5,
                sourceLabel = "Source A",
                quality = "1080p",
                stream = AddonStream(
                    addonId = "addon-a",
                    addonName = "Source A",
                    url = "https://video.example/matrix.m3u8",
                    quality = "1080p",
                    requestHeaders = mapOf("Referer" to "https://video.example/"),
                ),
            ),
        )

        assertEquals(812.5, request.startPositionSec!!, 0.001)
        assertEquals("https://video.example/matrix.m3u8", request.directStreamUrl)
        assertEquals("1080p", request.selectedStream?.quality)
        assertEquals("https://video.example/", request.requestHeaders["Referer"])
        assertEquals(2, request.episode?.seasonNumber)
        assertEquals(4, request.episode?.episodeNumber)
        assertNotNull(request.selectedStream)
    }
}