package com.streamdek.tv.nativeapp.debrid

import org.junit.Assert.assertEquals
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
}
