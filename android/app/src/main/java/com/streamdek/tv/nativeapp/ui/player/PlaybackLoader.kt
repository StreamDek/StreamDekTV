package com.streamdek.tv.nativeapp.ui.player

import android.content.res.Resources

import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.data.AddonStream
import com.streamdek.tv.nativeapp.data.ContinueWatchingItem
import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.Perf
import com.streamdek.tv.nativeapp.data.PlaybackProgressRecord
import com.streamdek.tv.nativeapp.data.PlaybackPreferences
import com.streamdek.tv.nativeapp.data.PlaybackRequest
import com.streamdek.tv.nativeapp.data.PlaybackSegment
import com.streamdek.tv.nativeapp.data.ResolvedPlaybackCandidate
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import com.streamdek.tv.nativeapp.data.contentScopedResumePosition
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The data-only playback preparation steps live outside the Compose screen so ART can compile
 * them as small methods instead of interpreting one oversized PlayerScreen$loadPlayback state
 * machine on every playback start.
 */
internal data class InitialPlaybackSource(
    val selectedStream: AddonStream?,
    val chosenStream: AddonStream?,
    val viewerChoseSource: Boolean,
    val rememberedSource: Boolean,
    val candidate: ResolvedPlaybackCandidate?,
)

internal suspend fun selectInitialPlaybackSource(
    repository: StreamDekRepository,
    request: PlaybackRequest,
    episode: EpisodeContext?,
    queuedStream: AddonStream?,
    queuedStreams: List<AddonStream>,
    streamKeyOverride: String?,
    forceRefresh: Boolean,
    isLive: Boolean,
): InitialPlaybackSource {
    val selectedStream = queuedStream ?: request.selectedStream?.takeIf {
        episode == request.episode && streamKeyOverride == request.selectedStreamKey
    }
    val chosenStream = queuedStream ?: request.selectedStream
    val viewerChoseSource = queuedStream != null ||
        streamKeyOverride != null ||
        request.selectedStreamKey != null
    val rememberedSource = if (
        !isLive &&
        !forceRefresh &&
        !viewerChoseSource &&
        (request.mediaType != "tv" || episode != null)
    ) {
        repository.rememberedPlaybackSource(request.mediaType, request.mediaId, episode)
    } else {
        null
    }
    val candidate = rememberedSource?.let(repository::candidateFromRememberedSource)
        ?: selectedStream?.let { stream ->
            val availableStreams = queuedStreams.ifEmpty { request.availableStreams }
            if (queuedStream != null) {
                withTimeoutOrNull(NextEpisodeSourceResolveTimeoutMs) {
                    repository.resolveSelectedPlayback(
                        request = request.copy(episode = episode),
                        stream = stream,
                        streams = availableStreams,
                        forceRefresh = forceRefresh,
                    )
                } ?: ResolvedPlaybackCandidate(null, stream, availableStreams)
            } else {
                repository.resolveSelectedPlayback(
                    request = request.copy(episode = episode),
                    stream = stream,
                    streams = availableStreams,
                    forceRefresh = forceRefresh,
                )
            }
        }
    return InitialPlaybackSource(
        selectedStream = selectedStream,
        chosenStream = chosenStream,
        viewerChoseSource = viewerChoseSource,
        rememberedSource = rememberedSource != null,
        candidate = candidate,
    )
}

internal data class PlaybackPreflight(
    val detail: MediaDetail?,
    val episode: EpisodeContext?,
    val inWatchlist: Boolean,
    val continueWatchingItem: ContinueWatchingItem?,
    val progress: PlaybackProgressRecord?,
    val effectiveImdbId: String?,
    val resumePositionSec: Double?,
    val resumeContentKey: String,
)

internal suspend fun loadPlaybackPreflight(
    repository: StreamDekRepository,
    request: PlaybackRequest,
    initialEpisode: EpisodeContext?,
    isLive: Boolean,
    perf: Perf.Span,
): PlaybackPreflight {
    val detail = if (isLive) null else repository.fetchDetail(request.mediaId, request.mediaType)
    perf.mark("preflight.detail")
    val inWatchlist = if (isLive) false else runCatching {
        repository.fetchLibrary().watchlist.any { it.id == request.mediaId && it.type == request.mediaType }
    }.getOrDefault(false)
    perf.mark("preflight.library")
    val continueWatchingItem = if (request.mediaType == "tv") {
        repository.fetchContinueWatchingItem(request.mediaType, request.mediaId, initialEpisode)
    } else {
        null
    }
    perf.mark("preflight.continueWatching")
    val episode = if (request.mediaType == "tv" && initialEpisode == null) {
        val firstSeason = detail?.seasons?.firstOrNull()?.seasonNumber
        val season = firstSeason?.let { repository.fetchSeason(request.mediaId, it) }
        continueWatchingItem?.exactEpisode() ?: season?.episodes?.firstOrNull()?.let {
            EpisodeContext(
                seasonNumber = season.seasonNumber,
                episodeNumber = it.episodeNumber,
                title = it.name,
                overview = it.overview,
                still = it.still,
                runtime = it.runtime,
                airDate = it.airDate,
                tmdbEpisodeId = it.id,
            )
        }
    } else {
        initialEpisode
    }
    perf.mark("preflight.season")
    val progress = if (isLive) null else repository.fetchProgress(request.mediaType, request.mediaId, episode)
    perf.mark("preflight.progress")
    val contentKey = listOf(
        request.mediaType,
        request.mediaId,
        episode?.seasonNumber ?: -1,
        episode?.episodeNumber ?: -1,
    ).joinToString(":")
    val resumePositionSec = if (isLive) null else contentScopedResumePosition(
        mediaType = request.mediaType,
        explicitPosition = request.startPositionSec,
        exactProgressPosition = progress?.positionSec,
        continuePosition = continueWatchingItem?.positionSec ?: continueWatchingItem?.resumeAt,
        continueSeason = continueWatchingItem?.episode?.seasonNumber ?: continueWatchingItem?.seasonNumber,
        continueEpisode = continueWatchingItem?.episode?.episodeNumber ?: continueWatchingItem?.episodeNumber,
        targetSeason = episode?.seasonNumber,
        targetEpisode = episode?.episodeNumber,
    )
    return PlaybackPreflight(
        detail = detail,
        episode = episode,
        inWatchlist = inWatchlist,
        continueWatchingItem = continueWatchingItem,
        progress = progress,
        effectiveImdbId = request.imdbId ?: detail?.imdbId,
        resumePositionSec = resumePositionSec,
        resumeContentKey = contentKey,
    )
}

internal data class ResolvedPlaybackDecision(
    val candidate: ResolvedPlaybackCandidate,
    val continueSourceNotice: String?,
)

internal suspend fun resolvePlaybackSource(
    repository: StreamDekRepository,
    request: PlaybackRequest,
    episode: EpisodeContext?,
    effectiveImdbId: String?,
    initialCandidate: ResolvedPlaybackCandidate?,
    rememberedSource: Boolean,
    rememberedKey: String?,
    autoContinueResume: Boolean,
    viewerChoseSource: Boolean,
    chosenStream: AddonStream?,
    streamKeyOverride: String?,
    forceRefresh: Boolean,
    currentNotice: String?,
    perf: Perf.Span,
    /**
     * For the notice below, which a viewer reads while they wait. Passed in because this file is
     * deliberately outside the Compose screen and has no composition to resolve a resource from.
     */
    resources: Resources,
): ResolvedPlaybackDecision {
    val rememberedAttempt = autoContinueResume && rememberedKey != null
    val resolved = initialCandidate ?: withTimeout(if (rememberedAttempt) 16_000L else 60_000L) {
        repository.resolvePlayback(
            request.mediaType,
            request.mediaId,
            effectiveImdbId,
            episode,
            preferredStreamKey = streamKeyOverride,
            forceRefresh = forceRefresh,
            streamType = request.streamType,
            directStreamUrl = request.directStreamUrl,
            requestHeaders = request.requestHeaders,
            sourceAddonId = request.sourceAddonId,
            sourceAddonName = request.sourceAddonName,
        )
    }.also {
        perf.mark("discovery", "playable=${it.source != null} pool=${it.streams.size}")
    }
    val notice = when {
        rememberedSource -> currentNotice
        autoContinueResume -> {
            val selectedKey = resolved.stream?.let(repository::streamSelectionKey)
            when {
                rememberedKey != null && selectedKey == rememberedKey ->
                    resources.getString(R.string.playback_resuming_with_remembered)
                rememberedKey != null && selectedKey != null -> {
                    repository.forgetRememberedStream(request.mediaType, request.mediaId, episode)
                    // The add-on's own name where it has one; only the sentence around it is ours.
                    resources.getString(
                        R.string.playback_remembered_expired_trying,
                        resolved.stream?.addonName?.takeIf { it.isNotBlank() }
                            ?: resources.getString(R.string.playback_a_new_source),
                    )
                }
                rememberedKey == null && selectedKey != null -> resources.getString(R.string.playback_found_new_source)
                else -> null
            }
        }
        viewerChoseSource -> {
            val selectedKey = resolved.stream?.let(repository::streamSelectionKey)
            val chosenKey = chosenStream?.let(repository::streamSelectionKey) ?: streamKeyOverride
            if (chosenKey != null && selectedKey != null && selectedKey != chosenKey) {
                resources.getString(
                    R.string.playback_chosen_failed_trying,
                    resolved.stream?.addonName?.takeIf { it.isNotBlank() }
                        ?: resources.getString(R.string.playback_another_source),
                )
            } else {
                currentNotice
            }
        }
        else -> currentNotice
    }
    return ResolvedPlaybackDecision(resolved, notice)
}

internal data class PlaybackFallbackResult(
    val candidate: ResolvedPlaybackCandidate,
    val failedStreamKeys: Set<String>,
)

internal suspend fun recoverPlaybackSource(
    repository: StreamDekRepository,
    request: PlaybackRequest,
    episode: EpisodeContext?,
    resolved: ResolvedPlaybackCandidate,
    selectedStream: AddonStream?,
    failedStreamKeys: Set<String>,
    onAttemptNotice: (String) -> Unit,
    /** For the notice naming which source is being tried next; see [resolvePlaybackSource]. */
    resources: Resources,
): PlaybackFallbackResult {
    var updatedFailedKeys = failedStreamKeys
    var failureText: String? = (selectedStream ?: resolved.stream)?.let { failed ->
        updatedFailedKeys = updatedFailedKeys + repository.streamSelectionKey(failed)
        "${repository.streamDeliveryLabel(failed)} resolver failure"
    }
    val recovered = repository.resolveFirstPlayableSource(
        request = request.copy(episode = episode),
        streams = resolved.streams,
        skipKeys = updatedFailedKeys,
        onAttempt = { next ->
            val target = next.addonName.ifBlank { resources.getString(R.string.playback_the_next_source) }
            onAttemptNotice(
                listOfNotNull(failureText, resources.getString(R.string.playback_trying_named, target))
                    .joinToString(". "),
            )
        },
        onAttemptFailed = { failed, key ->
            updatedFailedKeys = updatedFailedKeys + key
            failureText = "${repository.streamDeliveryLabel(failed)} resolver failure"
        },
    )
    return PlaybackFallbackResult(recovered ?: resolved, updatedFailedKeys)
}

internal data class PreparedPlayback(
    val initialSource: InitialPlaybackSource,
    val preflight: PlaybackPreflight,
    val autoContinueResume: Boolean,
    val continueSourceNotice: String?,
    val sourceChoiceError: String? = null,
    val resolved: ResolvedPlaybackCandidate? = null,
    val nextEpisode: EpisodeContext? = null,
    val segments: List<PlaybackSegment> = emptyList(),
    val watched: Boolean = false,
    val failedStreamKeys: Set<String> = emptySet(),
)

/** Runs the suspending playback-start sequence while the screen only applies observable phases. */
internal suspend fun preparePlayback(
    repository: StreamDekRepository,
    request: PlaybackRequest,
    initialEpisode: EpisodeContext?,
    queuedStream: AddonStream?,
    queuedStreams: List<AddonStream>,
    streamKeyOverride: String?,
    streamLabelOverride: String?,
    forceRefresh: Boolean,
    isLive: Boolean,
    playbackPreferences: PlaybackPreferences,
    failedStreamKeys: Set<String>,
    perf: Perf.Span,
    onInitialSource: (InitialPlaybackSource) -> Unit,
    onPreflight: (PlaybackPreflight) -> Unit,
    onResolved: (ResolvedPlaybackCandidate) -> Unit,
    onFallbackNotice: (String) -> Unit,
    /** For the notices this reports while it works; see [resolvePlaybackSource]. */
    resources: Resources,
): PreparedPlayback {
    val initialSource = selectInitialPlaybackSource(
        repository = repository,
        request = request,
        episode = initialEpisode,
        queuedStream = queuedStream,
        queuedStreams = queuedStreams,
        streamKeyOverride = streamKeyOverride,
        forceRefresh = forceRefresh,
        isLive = isLive,
    )
    perf.mark(
        "sourceDecision",
        "remembered=${initialSource.rememberedSource} chosen=${initialSource.selectedStream != null} resolved=${initialSource.candidate?.source != null}",
    )
    onInitialSource(initialSource)

    val preflight = loadPlaybackPreflight(repository, request, initialEpisode, isLive, perf)
    onPreflight(preflight)
    val episode = preflight.episode
    val autoContinueResume = request.fromContinueWatching && !initialSource.viewerChoseSource
    val rememberedKey = if (autoContinueResume) {
        repository.rememberedStreamKey(request.mediaType, request.mediaId, episode)
    } else null
    var continueNotice = when {
        initialSource.rememberedSource -> resources.getString(R.string.playback_resuming_remembered)
        initialSource.viewerChoseSource -> {
            val chosenLabel = streamLabelOverride
                ?: request.selectedStreamLabel
                ?: initialSource.chosenStream?.addonName?.takeIf { it.isNotBlank() }
            if (chosenLabel.isNullOrBlank()) {
                resources.getString(R.string.playback_opening_chosen)
            } else {
                resources.getString(R.string.playback_opening_named, chosenLabel)
            }
        }
        autoContinueResume && rememberedKey == null -> resources.getString(R.string.playback_no_remembered_finding)
        autoContinueResume -> resources.getString(R.string.playback_checking_remembered)
        else -> null
    }
    val continueOriginPlatform = preflight.continueWatchingItem?.lastPlatform ?: preflight.progress?.lastPlatform
    if (
        autoContinueResume &&
        !initialSource.rememberedSource &&
        rememberedKey == null &&
        continueWatchingCameFromAnotherPlatform(continueOriginPlatform, destination = "tv")
    ) {
        return PreparedPlayback(
            initialSource = initialSource,
            preflight = preflight,
            autoContinueResume = true,
            continueSourceNotice = null,
            sourceChoiceError = crossDeviceContinueNotice(
                mediaType = request.mediaType,
                seasonNumber = episode?.seasonNumber,
                episodeNumber = episode?.episodeNumber,
            ),
        )
    }

    val resolvedDecision = resolvePlaybackSource(
        repository = repository,
        request = request,
        episode = episode,
        effectiveImdbId = preflight.effectiveImdbId,
        initialCandidate = initialSource.candidate,
        rememberedSource = initialSource.rememberedSource,
        rememberedKey = rememberedKey,
        autoContinueResume = autoContinueResume,
        viewerChoseSource = initialSource.viewerChoseSource,
        chosenStream = initialSource.chosenStream,
        streamKeyOverride = streamKeyOverride,
        forceRefresh = forceRefresh,
        currentNotice = continueNotice,
        perf = perf,
        resources = resources,
    )
    var resolved = resolvedDecision.candidate
    continueNotice = resolvedDecision.continueSourceNotice
    onResolved(resolved)

    val nextEpisode = resolveNextEpisode(repository, request, preflight.detail, episode)
    val segments = repository.resolvePlaybackTimingSegments(
        mediaType = request.mediaType,
        tmdbId = preflight.detail?.tmdbId?.takeIf { it > 0 } ?: request.mediaId.toIntOrNull() ?: 0,
        imdbId = preflight.effectiveImdbId,
        season = episode?.seasonNumber,
        episode = episode?.episodeNumber,
        durationSec = preflight.detail?.runtime?.takeIf { it > 0 }?.times(60.0),
        preferences = playbackPreferences,
    )
    val watched = isLive || repository.isWatched(
        mediaType = request.mediaType,
        mediaId = request.mediaId,
        episode = episode,
        forceRefresh = true,
    )
    var updatedFailedKeys = failedStreamKeys
    if (resolved.source == null && !isLive && queuedStream == null) {
        val fallback = recoverPlaybackSource(
            repository = repository,
            request = request,
            episode = episode,
            resolved = resolved,
            selectedStream = initialSource.selectedStream,
            failedStreamKeys = updatedFailedKeys,
            onAttemptNotice = onFallbackNotice,
            resources = resources,
        )
        resolved = fallback.candidate
        updatedFailedKeys = fallback.failedStreamKeys
    }
    return PreparedPlayback(
        initialSource = initialSource,
        preflight = preflight,
        autoContinueResume = autoContinueResume,
        continueSourceNotice = continueNotice,
        resolved = resolved,
        nextEpisode = nextEpisode,
        segments = segments,
        watched = watched,
        failedStreamKeys = updatedFailedKeys,
    )
}
