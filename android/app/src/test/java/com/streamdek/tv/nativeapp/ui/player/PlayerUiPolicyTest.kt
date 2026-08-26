package com.streamdek.tv.nativeapp.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUiPolicyTest {
    @Test
    fun `remote seeking uses a predictable ten second step for episodes`() {
        assertEquals(10.0, tvSeekStepSeconds(2_700.0), 0.0)
    }

    @Test
    fun `long playback gets a larger but bounded seek step`() {
        assertEquals(12.0, tvSeekStepSeconds(5_400.0), 0.0)
        assertEquals(20.0, tvSeekStepSeconds(8_000.0), 0.0)
    }

    @Test
    fun `mobile progress is cross platform on television`() {
        assertTrue(continueWatchingCameFromAnotherPlatform("mobile", "tv"))
        assertTrue(continueWatchingCameFromAnotherPlatform("android", "tv"))
    }

    @Test
    fun `television progress is local on television`() {
        assertFalse(continueWatchingCameFromAnotherPlatform("androidtv", "tv"))
        assertFalse(continueWatchingCameFromAnotherPlatform("firetv", "tv"))
    }

    @Test
    fun `modal layers outrank drawers seeking and controls`() {
        assertEquals(
            PlayerInteractionLayer.Dialog,
            playerInteractionLayer(dialogVisible = true, drawerVisible = true, seeking = true, controlsVisible = true),
        )
        assertEquals(
            PlayerInteractionLayer.Drawer,
            playerInteractionLayer(dialogVisible = false, drawerVisible = true, seeking = true, controlsVisible = true),
        )
    }
}
