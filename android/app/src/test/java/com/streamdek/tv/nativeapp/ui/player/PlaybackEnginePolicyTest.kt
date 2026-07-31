package com.streamdek.tv.nativeapp.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackEnginePolicyTest {
  @Test
  fun `auto and media3 start with media3 while mpv starts with mpv`() {
    assertEquals(ActivePlaybackEngine.Media3, initialPlaybackEngine("Auto"))
    assertEquals(ActivePlaybackEngine.Media3, initialPlaybackEngine("Media3"))
    assertEquals(ActivePlaybackEngine.MPV, initialPlaybackEngine("MPV"))
  }

  @Test
  fun `auto falls back only once and only from media3`() {
    assertTrue(shouldAutoFallbackToMpv("Auto", ActivePlaybackEngine.Media3, fallbackUsed = false))
    assertFalse(shouldAutoFallbackToMpv("Auto", ActivePlaybackEngine.Media3, fallbackUsed = true))
    assertFalse(shouldAutoFallbackToMpv("Auto", ActivePlaybackEngine.MPV, fallbackUsed = false))
    assertFalse(shouldAutoFallbackToMpv("Media3", ActivePlaybackEngine.Media3, fallbackUsed = false))
    assertFalse(shouldAutoFallbackToMpv("MPV", ActivePlaybackEngine.MPV, fallbackUsed = false))
  }

  @Test
  fun `live retry reloads then refetches before giving up`() {
    assertEquals(LiveRetryAction.Reload, liveRetryAction(1))
    assertEquals(LiveRetryAction.Reload, liveRetryAction(2))
    assertEquals(LiveRetryAction.Refetch, liveRetryAction(3))
    assertEquals(LiveRetryAction.Refetch, liveRetryAction(6))
    assertEquals(LiveRetryAction.GiveUp, liveRetryAction(7))
  }
  @Test
  fun `stored player names are normalized safely`() {
    assertEquals("Auto", normalizePlayerEngineSetting("unknown"))
    assertEquals("Auto", normalizePlayerEngineSetting("auto"))
    assertEquals("Media3", normalizePlayerEngineSetting("ExoPlayer"))
    assertEquals("Media3", normalizePlayerEngineSetting("media3"))
    assertEquals("MPV", normalizePlayerEngineSetting("mpv"))
  }
}
