package com.streamdek.tv.nativeapp.data

import java.time.LocalDate
import kotlin.math.ceil

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

enum class EndOfPlaybackPhase {
    Idle, Armed, Presented, Countdown, UserSelected, Resolving, Transitioning, Dismissed, Completed,
}

enum class UpNextKind { NextEpisode, Recommendation }

/** Metadata state only. A missing stream is not evidence that an episode is unaired. */
enum class NextEpisodeAvailability { Aired, Unaired, None, Unknown }

object NextEpisodeAvailabilityPolicy {
    fun classify(exists: Boolean, airDate: String?, today: LocalDate = LocalDate.now()): NextEpisodeAvailability {
        if (!exists) return NextEpisodeAvailability.None
        val normalized = airDate?.trim()?.takeIf { it.isNotEmpty() } ?: return NextEpisodeAvailability.Unknown
        val parsed = runCatching { LocalDate.parse(normalized) }.getOrNull() ?: return NextEpisodeAvailability.Unknown
        return if (parsed.isAfter(today)) NextEpisodeAvailability.Unaired else NextEpisodeAvailability.Aired
    }
}

data class UpNextDecision(
    val primaryKind: UpNextKind,
    val primaryId: String?,
    val alternativeIds: List<String>,
)

/** The same deterministic ownership and deduplication policy used by Mobile. */
object EndOfPlaybackCoordinator {
    fun decide(
        nextEpisodeId: String?,
        currentMediaId: String,
        recommendationIds: List<String>,
        recommendationLimit: Int,
    ): UpNextDecision? {
        val current = currentMediaId.trim().lowercase()
        val next = nextEpisodeId?.trim()?.takeIf { it.isNotEmpty() }
        val seen = linkedSetOf<String>()
        if (current.isNotEmpty()) seen += current
        next?.lowercase()?.let(seen::add)
        val recommendations = recommendationIds.mapNotNull { raw ->
            raw.trim().takeIf { it.isNotEmpty() }?.takeIf { seen.add(it.lowercase()) }
        }.take(recommendationLimit.coerceIn(1, 2))
        return when {
            next != null -> UpNextDecision(UpNextKind.NextEpisode, next, recommendations)
            recommendations.isNotEmpty() -> UpNextDecision(UpNextKind.Recommendation, recommendations.first(), recommendations.drop(1))
            else -> null
        }
    }

    fun shouldResetAfterSeek(positionSec: Double, triggerPositionSec: Double?) =
        triggerPositionSec != null && positionSec.isFinite() && positionSec < triggerPositionSec - 15.0
}

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
        structuralOutroEndSec: Double? = null,
    ): MeaningfulContentEnd? {
        if (!durationSec.isFinite() || durationSec < 180.0) return null

        validBoundary(creditsStartSec, durationSec)?.let { boundary ->
            return structuralEstimate(boundary, boundary, durationSec, timing, MeaningfulEndSignal.CreditsMetadata)
        }
        validBoundary(structuralOutroStartSec, durationSec)?.let { start ->
            val endpoint = structuralOutroEndSec?.takeIf { it.isFinite() && it > start && it <= durationSec } ?: durationSec
            return structuralEstimate(start, endpoint, durationSec, timing, MeaningfulEndSignal.StructuralMetadata)
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

    fun countdownSeconds(positionSec: Double, estimate: MeaningfulContentEnd?): Int? {
        if (estimate == null || !positionSec.isFinite()) return null
        return ceil((estimate.boundaryPositionSec - positionSec).coerceAtLeast(0.0)).toInt()
    }

    fun isIntendedEndReached(positionSec: Double, estimate: MeaningfulContentEnd?): Boolean =
        estimate != null && positionSec.isFinite() && positionSec >= estimate.boundaryPositionSec

    private fun validBoundary(value: Double?, durationSec: Double): Double? = value?.takeIf {
        it.isFinite() && it >= durationSec * 0.2 && it <= durationSec - 5.0
    }

    private fun structuralEstimate(
        triggerBoundary: Double,
        endpoint: Double,
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
            triggerPositionSec = (triggerBoundary + offset).coerceIn(0.0, minOf(endpoint, durationSec - 5.0)),
            boundaryPositionSec = endpoint,
            signal = signal,
        )
    }
}
