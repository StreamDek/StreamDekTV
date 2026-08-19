package com.streamdek.tv.nativeapp.debrid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebridDisplayTest {
  @Test
  fun `cache attribution uses display names and middle dots`() {
    assertEquals("Cached · Deepbrid", cachedAvailabilityLabel(listOf("deepbrid")))
    assertEquals(
      "Cached · Real-Debrid · Deepbrid",
      cachedAvailabilityLabel(listOf("realdebrid", "deepbrid")),
    )
  }

  @Test
  fun `the streams list names the service promising an instant start`() {
    assertEquals("Ready · Premiumize", readyServiceLabel(listOf("premiumize")))
    assertEquals("Ready · Real-Debrid · TorBox", readyServiceLabel(listOf("realdebrid", "torbox")))
    // Provider ids arrive spelled several ways; the tag shows the service's own name either way.
    assertEquals("Ready · AllDebrid", readyServiceLabel(listOf("all-debrid")))
    assertEquals("Ready · Debrid-Link", readyServiceLabel(listOf("DEBRID_LINK")))
  }

  @Test
  fun `a row nobody could vouch for carries no tag at all`() {
    // Absence means the question could not be asked, not that the row will fail — so the row
    // falls back to saying how it arrives rather than claiming it is not ready.
    assertNull(readyServiceLabel(emptyList()))
    assertNull(readyServiceLabel(listOf("", "   ")))
  }

  @Test
  fun `one service listed twice is named once`() {
    assertEquals("Ready · Real-Debrid", readyServiceLabel(listOf("realdebrid", "real-debrid", "rd")))
  }
}
