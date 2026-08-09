package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncServiceTest {

    @Test
    fun `an unset or unrecognised service falls back to trakt`() {
        assertEquals(SyncServiceId.TRAKT, SyncServiceId.normalize(null))
        assertEquals(SyncServiceId.TRAKT, SyncServiceId.normalize(""))
        assertEquals(SyncServiceId.TRAKT, SyncServiceId.normalize("letterboxd"))
    }

    @Test
    fun `service ids are matched regardless of casing or padding`() {
        assertEquals(SyncServiceId.SIMKL, SyncServiceId.normalize(" Simkl "))
        assertEquals(SyncServiceId.MDBLIST, SyncServiceId.normalize("MDBList"))
    }

    @Test
    fun `mdblist reports no playback support`() {
        // MDBList is a list manager with no resume points, so asking it for them is pointless.
        assertFalse(SyncServiceCapabilities.of(SyncServiceId.MDBLIST).playback)
        assertTrue(SyncServiceCapabilities.of(SyncServiceId.MDBLIST).watchlist)
        assertTrue(SyncServiceCapabilities.of(SyncServiceId.SIMKL).playback)
    }

    @Test
    fun `only trakt claims scrobbling and history`() {
        assertTrue(SyncServiceCapabilities.of(SyncServiceId.TRAKT).traktOnlyFeatures)
        assertFalse(SyncServiceCapabilities.of(SyncServiceId.SIMKL).traktOnlyFeatures)
    }

    @Test
    fun `every service is labelled for display`() {
        assertEquals("Trakt", SyncServiceId.label(SyncServiceId.TRAKT))
        assertEquals("Simkl", SyncServiceId.label(SyncServiceId.SIMKL))
        assertEquals("MDBList", SyncServiceId.label(SyncServiceId.MDBLIST))
    }
}
