package com.streamdek.tv.nativeapp.data

import com.google.gson.Gson
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

    /**
     * The Home spotlight hides its synopsis unless the account says otherwise, and an account that
     * predates the setting sends no field at all. Pinned because the default only survives if Gson
     * runs the constructor default for the missing key — allocate the object any other way and it
     * silently reverts to showing the paragraph.
     */
    @Test
    fun `home synopsis stays hidden when the payload does not mention it`() {
        val fromOlderPayload = Gson().fromJson("""{"theme":"cinema-blue"}""", AppPreferences::class.java)

        assertTrue(AppPreferences().hideHomeSynopsis)
        assertTrue(fromOlderPayload.hideHomeSynopsis)
        // An account that has turned it off is still honoured.
        assertFalse(
            Gson().fromJson("""{"hideHomeSynopsis":false}""", AppPreferences::class.java).hideHomeSynopsis,
        )
    }
}