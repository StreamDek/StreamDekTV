package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SeasonAvailabilityTest {
    private val today = LocalDate.of(2026, 8, 24)

    @Test fun `released season is shown`() {
        assertTrue(isSeasonAvailable(SeasonRef(3, "Season 3", 10, "2025-10-01"), today))
    }

    @Test fun `future and empty seasons are hidden`() {
        assertFalse(isSeasonAvailable(SeasonRef(4, "Season 4", 10, "2027-01-01"), today))
        assertFalse(isSeasonAvailable(SeasonRef(4, "Season 4", 0, null), today))
    }

    @Test fun `unknown date remains visible when playable episodes exist`() {
        assertTrue(isSeasonAvailable(SeasonRef(2, "Season 2", 8, null), today))
    }

    @Test fun `filter preserves released season order`() {
        assertEquals(listOf(1, 2), availableSeasons(listOf(
            SeasonRef(1, "Season 1", 10, "2024-01-01"),
            SeasonRef(2, "Season 2", 10, "2025-01-01"),
            SeasonRef(3, "Season 3", 10, "2027-01-01"),
        ), today).map { it.seasonNumber })
    }
}
