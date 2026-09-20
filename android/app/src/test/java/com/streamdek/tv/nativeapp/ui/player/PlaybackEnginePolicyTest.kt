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
  fun `a source that has played is recovered in place rather than handed to mpv`() {
    assertFalse(
      shouldAutoFallbackToMpv(
        "Auto",
        ActivePlaybackEngine.Media3,
        fallbackUsed = false,
        sourceHasPlayed = true,
      ),
    )
    assertTrue(
      shouldAutoFallbackToMpv(
        "Auto",
        ActivePlaybackEngine.Media3,
        fallbackUsed = false,
        sourceHasPlayed = false,
      ),
    )
  }

  @Test
  fun `only a stored source that never opened counts as expired`() {
    assertTrue(shouldTreatAsExpiredStoredSource(startedFromRememberedSource = true, sourceHasPlayed = false))
    // The reported fault: an episode several minutes in, back in loading for an engine swap, was
    // being read as an expired stored link and interrupted to say so.
    assertFalse(shouldTreatAsExpiredStoredSource(startedFromRememberedSource = true, sourceHasPlayed = true))
    assertFalse(shouldTreatAsExpiredStoredSource(startedFromRememberedSource = false, sourceHasPlayed = false))
    assertFalse(shouldTreatAsExpiredStoredSource(startedFromRememberedSource = false, sourceHasPlayed = true))
  }

  @Test
  fun `a source that has played waits longest for its picture to come back`() {
    assertEquals(
      RememberedSourceStartTimeoutMs,
      sourceStartWindowMs(startedFromRememberedSource = true, sourceHasPlayed = false),
    )
    assertEquals(
      SourceStartTimeoutMs,
      sourceStartWindowMs(startedFromRememberedSource = false, sourceHasPlayed = false),
    )
    assertEquals(
      SourceReopenTimeoutMs,
      sourceStartWindowMs(startedFromRememberedSource = true, sourceHasPlayed = true),
    )
    assertEquals(
      SourceReopenTimeoutMs,
      sourceStartWindowMs(startedFromRememberedSource = false, sourceHasPlayed = true),
    )
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
