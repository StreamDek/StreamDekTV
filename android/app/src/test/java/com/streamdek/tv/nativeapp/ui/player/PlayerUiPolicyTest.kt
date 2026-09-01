package com.streamdek.tv.nativeapp.ui.player

import com.streamdek.tv.nativeapp.data.AddonStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUiPolicyTest {
    @Test
    fun `source favourite identity ignores expiring playback urls and request headers`() {
        val first = AddonStream(
            addonId = "Example.Addon",
            source = "Provider",
            name = "Release Name",
            quality = "1080P",
            url = "https://cdn.example/first?token=secret-one",
            requestHeaders = mapOf("Authorization" to "secret-one"),
        )
        val refreshed = first.copy(
            url = "https://cdn.example/second?token=secret-two",
            requestHeaders = mapOf("Authorization" to "secret-two"),
        )

        assertEquals(stableSourceFavouriteKey(first), stableSourceFavouriteKey(refreshed))
        assertFalse(stableSourceFavouriteKey(first).contains("secret", ignoreCase = true))
    }

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
    fun `cross device notice matches mobile wording`() {
        assertEquals(
            "You started this movie on another device. Choose a source to continue watching from where you left off.",
            crossDeviceContinueNotice("movie"),
        )
        assertEquals(
            "You started this series on another device. Choose a source for Season 2, Episode 3 to continue watching from where you left off.",
            crossDeviceContinueNotice("tv", seasonNumber = 2, episodeNumber = 3),
        )
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

    @Test
    fun `next episode handoff rejects the same or an earlier episode`() {
        assertFalse(isForwardEpisodeTransition(3, 2, 3, 2))
        assertFalse(isForwardEpisodeTransition(3, 2, 3, 1))
        assertTrue(isForwardEpisodeTransition(3, 2, 3, 3))
        assertTrue(isForwardEpisodeTransition(3, 8, 4, 1))
    }
}
