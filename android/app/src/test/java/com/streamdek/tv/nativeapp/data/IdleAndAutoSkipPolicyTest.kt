package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleAndAutoSkipPolicyTest {
    @Test
    fun `auto skip controls are independent and off by default`() {
        val defaults = PlaybackPreferences()
        assertFalse(defaults.isAutoSkipEnabled("intro"))
        assertFalse(defaults.isAutoSkipEnabled("recap"))
        assertFalse(defaults.isAutoSkipEnabled("outro"))

        val mixed = defaults.copy(autoSkipIntroEnabled = true, autoSkipEndingEnabled = true)
        assertTrue(mixed.isAutoSkipEnabled("intro"))
        assertFalse(mixed.isAutoSkipEnabled("recap"))
        assertTrue(mixed.isAutoSkipEnabled("outro"))
    }

    @Test
    fun `off has no idle deadline and configured minutes convert exactly`() {
        assertNull(idleTimeoutMillis(0))
        assertEquals(15L * 60_000L, idleTimeoutMillis(15))
        assertEquals("2 hours", idleTimeoutLabel(120))
    }
}
