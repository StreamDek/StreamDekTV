package com.streamdek.tv.nativeapp.data

/** Canonical timing choices shared conceptually with StreamDek Mobile. */
enum class RecommendationTiming(val key: String) {
    Early("early"),
    Standard("standard"),
    Late("late");

    companion object {
        fun fromKey(value: String?): RecommendationTiming =
            entries.firstOrNull { it.key.equals(value, ignoreCase = true) } ?: Standard
    }
}

enum class MeaningfulEndSignal { CreditsMetadata, StructuralMetadata, RemainingTime, PercentageFallback }

data class MeaningfulContentEnd(
    val triggerPositionSec: Double,
    val boundaryPositionSec: Double,
    val signal: MeaningfulEndSignal,
)

/**
 * Pure, failure-tolerant policy for the point where a Next Up invitation may appear.
 * Network lookups live outside this class; invalid or stale structure data simply falls through.
 */
object AdaptiveEndOfPlaybackTrigger {
    fun estimate(
        durationSec: Double,
        timing: RecommendationTiming,
        creditsStartSec: Double? = null,
        structuralOutroStartSec: Double? = null,
    ): MeaningfulContentEnd? {
        if (!durationSec.isFinite() || durationSec < 180.0) return null

        validBoundary(creditsStartSec, durationSec)?.let { boundary ->
            return structuralEstimate(boundary, durationSec, timing, MeaningfulEndSignal.CreditsMetadata)
        }
        validBoundary(structuralOutroStartSec, durationSec)?.let { boundary ->
            return structuralEstimate(boundary, durationSec, timing, MeaningfulEndSignal.StructuralMetadata)
        }

        val desiredRemaining = when (timing) {
            RecommendationTiming.Early -> 300.0
            RecommendationTiming.Standard -> 180.0
            RecommendationTiming.Late -> 90.0
        }
        // Short programmes must not spend a large fraction of their runtime under an overlay.
        val adaptiveRemaining = desiredRemaining.coerceAtMost(durationSec * 0.12).coerceAtLeast(45.0)
        val trigger = durationSec - adaptiveRemaining
        if (trigger.isFinite() && trigger >= 0.0) {
            return MeaningfulContentEnd(trigger, durationSec, MeaningfulEndSignal.RemainingTime)
        }

        val percent = when (timing) {
            RecommendationTiming.Early -> 0.92
            RecommendationTiming.Standard -> 0.94
            RecommendationTiming.Late -> 0.96
        }
        return MeaningfulContentEnd(durationSec * percent, durationSec, MeaningfulEndSignal.PercentageFallback)
    }

    fun isReached(positionSec: Double, estimate: MeaningfulContentEnd?): Boolean =
        estimate != null && positionSec.isFinite() && positionSec >= estimate.triggerPositionSec

    private fun validBoundary(value: Double?, durationSec: Double): Double? = value?.takeIf {
        it.isFinite() && it >= durationSec * 0.2 && it <= durationSec - 5.0
    }

    private fun structuralEstimate(
        boundary: Double,
        durationSec: Double,
        timing: RecommendationTiming,
        signal: MeaningfulEndSignal,
    ): MeaningfulContentEnd {
        val offset = when (timing) {
            RecommendationTiming.Early -> -30.0
            RecommendationTiming.Standard -> 0.0
            RecommendationTiming.Late -> 30.0
        }
        return MeaningfulContentEnd(
            triggerPositionSec = (boundary + offset).coerceIn(0.0, durationSec - 5.0),
            boundaryPositionSec = boundary,
            signal = signal,
        )
    }
}
