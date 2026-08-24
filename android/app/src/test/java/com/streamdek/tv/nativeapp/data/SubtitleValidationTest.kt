package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleValidationTest {
    @Test fun `supported subtitle formats require timed cues`() {
        assertTrue(subtitleTextHasTimedCues("WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nBonjour", "vtt"))
        assertTrue(subtitleTextHasTimedCues("1\n00:00:01,000 --> 00:00:03,000\nBonjour", "srt"))
        assertTrue(subtitleTextHasTimedCues("[Events]\nDialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Bonjour", "ass"))
    }

    @Test fun `an html error response is not a subtitle`() {
        assertFalse(subtitleTextHasTimedCues("<html><body>Access denied</body></html>", "srt"))
    }
}
