package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndOfPlaybackRecommendationsTest {
    @Test fun `credits metadata outranks structural metadata`() {
        val result = AdaptiveEndOfPlaybackTrigger.estimate(3600.0, RecommendationTiming.Standard, 3300.0, 3200.0)!!
        assertEquals(MeaningfulEndSignal.CreditsMetadata, result.signal)
        assertEquals(3300.0, result.triggerPositionSec, 0.01)
    }

    @Test fun `invalid structural metadata falls back without failing playback`() {
        val result = AdaptiveEndOfPlaybackTrigger.estimate(3000.0, RecommendationTiming.Standard, structuralOutroStartSec = 100.0)!!
        assertEquals(MeaningfulEndSignal.RemainingTime, result.signal)
        assertEquals(2820.0, result.triggerPositionSec, 0.01)
    }

    @Test fun `timing changes structure offset rather than exposing a percentage`() {
        val early = AdaptiveEndOfPlaybackTrigger.estimate(3600.0, RecommendationTiming.Early, structuralOutroStartSec = 3300.0)!!
        val late = AdaptiveEndOfPlaybackTrigger.estimate(3600.0, RecommendationTiming.Late, structuralOutroStartSec = 3300.0)!!
        assertEquals(3270.0, early.triggerPositionSec, 0.01)
        assertEquals(3330.0, late.triggerPositionSec, 0.01)
    }

    @Test fun `very short content is ineligible`() {
        assertEquals(null, AdaptiveEndOfPlaybackTrigger.estimate(120.0, RecommendationTiming.Standard))
    }

    @Test fun `reached is safe for missing and non finite positions`() {
        val estimate = AdaptiveEndOfPlaybackTrigger.estimate(7200.0, RecommendationTiming.Standard)!!
        assertFalse(AdaptiveEndOfPlaybackTrigger.isReached(Double.NaN, estimate))
        assertFalse(AdaptiveEndOfPlaybackTrigger.isReached(estimate.triggerPositionSec - 1.0, estimate))
        assertTrue(AdaptiveEndOfPlaybackTrigger.isReached(estimate.triggerPositionSec, estimate))
    }

    @Test fun `next episode is primary and duplicate recommendations are removed`() {
        val result = EndOfPlaybackCoordinator.decide("series:7:2:6", "series:7", listOf("series:7:2:6", "movie:9", "movie:9"), 2)!!
        assertEquals(UpNextKind.NextEpisode, result.primaryKind)
        assertEquals(listOf("movie:9"), result.alternativeIds)
    }

    @Test fun `recommendation is primary when no episode follows`() {
        val result = EndOfPlaybackCoordinator.decide(null, "movie:1", listOf("movie:1", "movie:2", "movie:3"), 2)!!
        assertEquals(UpNextKind.Recommendation, result.primaryKind)
        assertEquals("movie:2", result.primaryId)
        assertEquals(listOf("movie:3"), result.alternativeIds)
    }

    @Test fun `backward seek rearms only outside the trigger region`() {
        assertTrue(EndOfPlaybackCoordinator.shouldResetAfterSeek(2800.0, 2900.0))
        assertFalse(EndOfPlaybackCoordinator.shouldResetAfterSeek(2890.0, 2900.0))
    }
}
