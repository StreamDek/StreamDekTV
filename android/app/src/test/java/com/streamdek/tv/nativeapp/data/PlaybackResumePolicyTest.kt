package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackResumePolicyTest {
    @Test fun `the previous episode position cannot leak into the next episode`() {
        assertNull(contentScopedResumePosition("tv", null, null, 2520.0, 1, 4, 1, 5))
    }

    @Test fun `existing progress for the target episode is retained`() {
        assertEquals(450.0, contentScopedResumePosition("tv", null, 450.0, 2520.0, 1, 4, 1, 5)!!, 0.0)
    }

    @Test fun `the exact continue watching episode resumes`() {
        assertEquals(1122.0, contentScopedResumePosition("tv", null, null, 1122.0, 2, 4, 2, 4)!!, 0.0)
    }
    @Test fun `play again zero overrides completed progress and continue watching`() {
        assertEquals(0.0, contentScopedResumePosition("movie", 0.0, 5900.0, 5900.0, null, null, null, null)!!, 0.0)
    }

    @Test fun `partial movies retain their exact saved position`() {
        assertEquals(1500.0, contentScopedResumePosition("movie", null, 1500.0, 1400.0, null, null, null, null)!!, 0.0)
    }

    @Test fun `invalid explicit positions do not replace valid saved progress`() {
        assertEquals(1500.0, contentScopedResumePosition("movie", Double.NaN, 1500.0, null, null, null, null, null)!!, 0.0)
    }
}
