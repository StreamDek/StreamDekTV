package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPreferencesTest {
    @Test
    fun `vnext defaults remain safe for older preference payloads`() {
        val preferences = AppPreferences()
        assertEquals("comfortable", preferences.cardDensity)
        assertEquals("normal", preferences.animationSpeed)
        assertEquals("adaptive", preferences.navigationStyle)
        assertEquals(5, preferences.gridSize)
        assertTrue(preferences.backgroundBlur)
        assertFalse(preferences.highContrast)
        assertFalse(preferences.largeText)
        assertFalse(preferences.reducedMotion)
    }
}