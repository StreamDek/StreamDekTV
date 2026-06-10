package com.streamdek.tv.nativeapp.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamdek.tv.mpv.MPVView
import com.streamdek.tv.mpv.MpvTrackInfo
import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.PlaybackPreferences
import com.streamdek.tv.nativeapp.data.PlaybackSegment
import com.streamdek.tv.nativeapp.data.PlaybackRequest
import com.streamdek.tv.nativeapp.data.ResolvedPlaybackCandidate
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ControlsHideDelayMs = 3000L
private const val AutoPlayNextEpisodeCountdownSeconds = 5

private data class SegmentAction(
    val kind: SegmentActionKind,
    val segmentType: String,
    val label: String,
    val targetTimeSec: Double? = null,
)

private enum class SegmentActionKind {
    Skip,
    NextEpisode,
}

@Composable
fun PlayerScreen(
    repository: StreamDekRepository,
    request: PlaybackRequest,
    onBack: () -> Unit,
    onExitToStreams: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val bootstrap by repository.bootstrap.collectAsState()
    val playbackPreferences = bootstrap?.preferences?.playback ?: PlaybackPreferences()

    var detail by remember { mutableStateOf<MediaDetail?>(null) }
    var currentEpisode by remember(request) { mutableStateOf(request.episode) }
    var nextEpisode by remember { mutableStateOf<EpisodeContext?>(null) }
    var candidate by remember { mutableStateOf<ResolvedPlaybackCandidate?>(null) }
    var currentSourceUrl by remember { mutableStateOf<String?>(null) }
    var currentLabel by remember { mutableStateOf("Selecting stream…") }
    var paused by remember { mutableStateOf(false) }
    var positionSec by remember { mutableDoubleStateOf(0.0) }
    var durationSec by remember { mutableDoubleStateOf(0.0) }
    var audioTracks by remember { mutableStateOf<List<MpvTrackInfo>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<MpvTrackInfo>>(emptyList()) }
    var selectedAudioId by remember { mutableIntStateOf(-1) }
    var selectedSubtitleId by remember { mutableIntStateOf(-1) }
    var speed by remember { mutableDoubleStateOf(1.0) }
    var panel by remember { mutableStateOf<OverlayPanel?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var playerView: MPVView? by remember { mutableStateOf(null) }
    var loading by remember { mutableStateOf(true) }
    var controlsVisible by remember { mutableStateOf(false) }
    var controlsHideJob by remember { mutableStateOf<Job?>(null) }
    var pendingSeekJob by remember { mutableStateOf<Job?>(null) }
    var pendingResumePositionSec by remember { mutableStateOf<Double?>(null) }
    var lastWorkingSourceUrl by remember { mutableStateOf<String?>(null) }
    var lastWorkingLabel by remember { mutableStateOf<String?>(null) }
    var pauseInfoVisible by remember { mutableStateOf(false) }
    var lastSeekInputAt by remember { mutableStateOf(0L) }
    var lastSeekDirection by remember { mutableIntStateOf(0) }
    var seekBurstCount by remember { mutableIntStateOf(0) }
    var subtitlePreferenceAppliedForSource by remember { mutableStateOf<String?>(null) }
    var traktScrobbledStart by remember { mutableStateOf(false) }
    var inWatchlist by remember(request.mediaId, request.mediaType) { mutableStateOf(false) }
    var completionThresholdReached by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    var watchedMarked by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    var watchlistPromptVisible by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    var watchlistPromptShown by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    var completionExitTriggered by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    var segments by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf<List<PlaybackSegment>>(emptyList()) }
    var handledSegmentTypes by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) {
        mutableStateOf(setOf<String>())
    }
    var nextEpisodeDialogVisible by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    var nextEpisodeCandidate by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf<ResolvedPlaybackCandidate?>(null) }
    var nextEpisodeLoading by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    var nextEpisodeCountdown by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf<Int?>(null) }
    var streamKeyOverride by remember(request.mediaId, request.mediaType) { mutableStateOf(request.selectedStreamKey) }
    var streamLabelOverride by remember(request.mediaId, request.mediaType) { mutableStateOf(request.selectedStreamLabel) }

    val errorBackRequester = remember { FocusRequester() }
    val errorSourcesRequester = remember { FocusRequester() }
    val watchlistPromptKeepRequester = remember { FocusRequester() }
    val watchlistPromptRemoveRequester = remember { FocusRequester() }
    val playRequester = remember { FocusRequester() }
    val subtitlesRequester = remember { FocusRequester() }
    val audioRequester = remember { FocusRequester() }
    val sourcesRequester = remember { FocusRequester() }
    val rewindRequester = remember { FocusRequester() }
    val nextRequester = remember { FocusRequester() }
    val watchedRequester = remember { FocusRequester() }
    val speedRequester = remember { FocusRequester() }
    val progressRequester = remember { FocusRequester() }
    val panelCloseRequester = remember { FocusRequester() }
    val panelFirstItemRequester = remember { FocusRequester() }

    // Keep screen on while the player is active
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    fun hideControlsNow() {
        controlsHideJob?.cancel()
        controlsHideJob = null
        controlsVisible = false
    }

    fun scheduleSeek(targetSeconds: Double) {
        val target = targetSeconds
            .coerceAtLeast(0.0)
            .coerceAtMost(durationSec.takeIf { it > 0.0 } ?: targetSeconds)
        positionSec = target
        pendingSeekJob?.cancel()
        pendingSeekJob = scope.launch {
            delay(180)
            playerView?.seekTo(target)
        }
    }

    fun scheduleRelativeSeek(baseDeltaSeconds: Double) {
        val now = System.currentTimeMillis()
        val direction = if (baseDeltaSeconds >= 0.0) 1 else -1
        seekBurstCount = if (lastSeekDirection == direction && now - lastSeekInputAt <= 450L) {
            seekBurstCount + 1
        } else {
            0
        }
        lastSeekDirection = direction
        lastSeekInputAt = now
        val multiplier = when {
            seekBurstCount >= 10 -> 8.0
            seekBurstCount >= 6 -> 4.0
            seekBurstCount >= 3 -> 2.0
            else -> 1.0
        }
        scheduleSeek(positionSec + (baseDeltaSeconds * multiplier))
    }

    fun scheduleControlsHide() {
        controlsHideJob?.cancel()
        controlsHideJob = null
        if (paused || panel != null || loading || error != null) return
        controlsHideJob = scope.launch {
            delay(ControlsHideDelayMs)
            if (!paused && panel == null && !loading && error == null) {
                controlsVisible = false
            }
        }
    }

    fun requestPlaybackFocus() {
        scope.launch {
            delay(60)
            runCatching { playRequester.requestFocus() }
                .onFailure { TvDebugLogger.w("Player", "play focus request skipped: ${it.message}") }
        }
    }

    fun showControls(focusPlay: Boolean = false) {
        pauseInfoVisible = false
        controlsVisible = true
        scheduleControlsHide()
        if (focusPlay) requestPlaybackFocus()
    }

    fun registerInteraction() {
        pauseInfoVisible = false
        if (!controlsVisible) controlsVisible = true
        scheduleControlsHide()
    }

    fun traktProgressPercent(): Double {
        if (durationSec <= 0.0) return 0.0
        return ((positionSec / durationSec) * 100.0).coerceIn(0.0, 100.0)
    }

    fun activeSegmentAction(): SegmentAction? {
        if (!playbackPreferences.areSkipSegmentsEnabled()) return null
        val activeSegment = segments
            .filter { segment ->
                !handledSegmentTypes.contains(segment.segmentType) &&
                    positionSec >= segment.startSec &&
                    positionSec < segment.endSec
            }
            .sortedWith(
                compareBy<PlaybackSegment>(
                    { segmentPriority(it.segmentType) },
                    { it.startSec },
                    { it.endSec },
                ),
            )
            .firstOrNull() ?: return null

        return when (activeSegment.segmentType) {
            "intro" -> SegmentAction(
                kind = SegmentActionKind.Skip,
                segmentType = activeSegment.segmentType,
                label = "Skip Intro",
                targetTimeSec = activeSegment.endSec,
            )
            "recap" -> SegmentAction(
                kind = SegmentActionKind.Skip,
                segmentType = activeSegment.segmentType,
                label = "Skip Recap",
                targetTimeSec = activeSegment.endSec,
            )
            "outro" -> nextEpisode?.let {
                SegmentAction(
                    kind = SegmentActionKind.NextEpisode,
                    segmentType = activeSegment.segmentType,
                    label = "Next Episode",
                )
            }
            else -> null
        }
    }

    fun markSegmentHandled(segmentType: String) {
        handledSegmentTypes = handledSegmentTypes + segmentType
    }

    fun currentWatchlistItem(): com.streamdek.tv.nativeapp.data.MediaItem? {
        val currentDetail = detail ?: return null
        return com.streamdek.tv.nativeapp.data.MediaItem(
            id = request.mediaId,
            tmdbId = currentDetail.tmdbId,
            title = currentDetail.title,
            type = request.mediaType,
            poster = currentDetail.poster,
            backdrop = currentDetail.backdrop,
            description = currentDetail.description,
            rating = currentDetail.rating,
            year = currentDetail.year,
            titleLogo = currentDetail.titleLogo,
            progress = traktProgressPercent(),
            positionSec = positionSec,
            durationSec = durationSec,
            episode = currentEpisode,
        )
    }

    suspend fun syncProgressIfEligible() {
        if (completionThresholdReached) return
        repository.syncProgress(request.mediaType, request.mediaId, positionSec, durationSec, currentEpisode, detail)
    }

    suspend fun markWatchedAndClearProgressIfNeeded() {
        if (watchedMarked) return
        watchedMarked = true
        repository.syncProgress(request.mediaType, request.mediaId, positionSec, durationSec, currentEpisode, detail)
        repository.markWatched(
            mediaType = request.mediaType,
            mediaId = request.mediaId,
            title = detail?.title ?: request.title ?: "",
            year = detail?.year,
            episode = currentEpisode,
            imdbId = request.imdbId,
        )
        repository.clearProgress(request.mediaType, request.mediaId, currentEpisode)
    }

    suspend fun removeFromWatchlistIfNeeded() {
        if (!inWatchlist) return
        currentWatchlistItem()?.let { repository.removeFromWatchlist(it) }
        inWatchlist = false
    }

    suspend fun openNextEpisodeDialog() {
        val targetEpisode = nextEpisode ?: return
        val currentStream = candidate?.stream
        val effectiveImdbId = request.imdbId ?: detail?.imdbId
        markSegmentHandled("outro")
        paused = true
        controlsVisible = false
        nextEpisodeDialogVisible = true
        nextEpisodeLoading = true
        nextEpisodeCountdown = null
        nextEpisodeCandidate = repository.resolvePlayback(
            mediaType = request.mediaType,
            mediaId = request.mediaId,
            imdbId = effectiveImdbId,
            episode = targetEpisode,
            preferredAddonName = if (playbackPreferences.preferBingeGroupNextEpisode) currentStream?.addonName else null,
            preferredQualityGroup = if (playbackPreferences.preferBingeGroupNextEpisode) currentStream?.quality else null,
            forceRefresh = true,
        )
        nextEpisodeLoading = false
        if (nextEpisodeCandidate?.source != null && !nextEpisodeCandidate?.streams.isNullOrEmpty()) {
            nextEpisodeCountdown = AutoPlayNextEpisodeCountdownSeconds
        }
    }

    fun beginNextEpisode(streamIndex: Int? = null) {
        val targetEpisode = nextEpisode ?: return
        val selectedStream = when {
            streamIndex != null -> nextEpisodeCandidate?.streams?.getOrNull(streamIndex)
            else -> nextEpisodeCandidate?.stream ?: nextEpisodeCandidate?.streams?.firstOrNull()
        } ?: return
        streamKeyOverride = repository.streamSelectionKey(selectedStream)
        streamLabelOverride = repository.describeStreamOption(selectedStream)
        nextEpisodeDialogVisible = false
        nextEpisodeCountdown = null
        nextEpisodeCandidate = null
        paused = false
        currentEpisode = targetEpisode
    }

    fun completePlaybackAndExit() {
        if (completionExitTriggered) return
        completionExitTriggered = true
        if (traktScrobbledStart) {
            traktScrobbledStart = false
            scope.launch {
                repository.traktScrobble(
                    action = "stop",
                    mediaType = request.mediaType,
                    mediaId = request.mediaId,
                    title = detail?.title ?: request.title,
                    year = detail?.year,
                    progress = traktProgressPercent(),
                )
            }
        }
        scope.launch {
            markWatchedAndClearProgressIfNeeded()
            removeFromWatchlistIfNeeded()
            TvDebugLogger.i("Player", "completion exit to streams mediaType=${request.mediaType} mediaId=${request.mediaId}")
            onExitToStreams()
        }
    }

    fun queueTraktStop() {
        if (!traktScrobbledStart) return
        traktScrobbledStart = false
        scope.launch {
            repository.traktScrobble(
                action = "stop",
                mediaType = request.mediaType,
                mediaId = request.mediaId,
                title = detail?.title ?: request.title,
                year = detail?.year,
                progress = traktProgressPercent(),
            )
        }
    }

    suspend fun loadPlayback() {
        pendingSeekJob?.cancel()
        pendingSeekJob = null
        loading = true
        controlsVisible = false
        pauseInfoVisible = false
        watchlistPromptVisible = false
        watchlistPromptShown = false
        completionThresholdReached = false
        watchedMarked = false
        completionExitTriggered = false
        nextEpisodeDialogVisible = false
        nextEpisodeCandidate = null
        nextEpisodeLoading = false
        nextEpisodeCountdown = null
        handledSegmentTypes = emptySet()
        segments = emptyList()
        runCatching { repository.refreshBootstrap() }
        detail = repository.fetchDetail(request.mediaId, request.mediaType)
        val effectiveImdbId = request.imdbId ?: detail?.imdbId
        inWatchlist = runCatching {
            repository.fetchLibrary().watchlist.any { it.id == request.mediaId && it.type == request.mediaType }
        }.getOrDefault(false)
        val continueWatchingItem = if (request.mediaType == "tv") {
            repository.fetchContinueWatchingItem(request.mediaType, request.mediaId)
        } else {
            null
        }
        if (request.mediaType == "tv" && currentEpisode == null) {
            val firstSeason = detail?.seasons?.firstOrNull()?.seasonNumber
            val season = firstSeason?.let { repository.fetchSeason(request.mediaId, it) }
            currentEpisode = continueWatchingItem?.episode ?: season?.episodes?.firstOrNull()?.let {
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
        }
        val progress = repository.fetchProgress(request.mediaType, request.mediaId, currentEpisode)
        pendingResumePositionSec = progress?.positionSec
            ?.takeIf { it > 0.0 }
            ?: continueWatchingItem?.positionSec
            ?: continueWatchingItem?.resumeAt
        val resolved = repository.resolvePlayback(
            request.mediaType,
            request.mediaId,
            effectiveImdbId,
            currentEpisode,
            preferredStreamKey = streamKeyOverride,
        )
        candidate = resolved
        currentSourceUrl = resolved.source?.url
        currentLabel = streamLabelOverride ?: resolved.source?.label ?: "No playable stream found"
        positionSec = pendingResumePositionSec ?: 0.0
        durationSec = progress?.durationSec ?: 0.0
        nextEpisode = resolveNextEpisode(repository, request, detail, currentEpisode)
        if (request.mediaType == "tv" && currentEpisode != null && !effectiveImdbId.isNullOrBlank()) {
            segments = repository.fetchEpisodeSegments(
                imdbId = effectiveImdbId,
                season = currentEpisode!!.seasonNumber,
                episode = currentEpisode!!.episodeNumber,
            )
            TvDebugLogger.i(
                "Player",
                "segments loaded mediaId=${request.mediaId} episode=s${currentEpisode!!.seasonNumber}e${currentEpisode!!.episodeNumber} imdbId=$effectiveImdbId count=${segments.size}",
            )
        } else if (request.mediaType == "tv" && currentEpisode != null) {
            TvDebugLogger.w(
                "Player",
                "segments skipped mediaId=${request.mediaId} episode=s${currentEpisode!!.seasonNumber}e${currentEpisode!!.episodeNumber} imdbId missing",
            )
        }
        watchedMarked = repository.isWatched(
            mediaType = request.mediaType,
            mediaId = request.mediaId,
            episode = currentEpisode,
            forceRefresh = true,
        )
        streamLabelOverride = null
        if (resolved.source == null) {
            error = "No playable stream could be resolved"
            loading = false
            controlsVisible = true
        } else {
            error = null
        }
    }

    LaunchedEffect(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) {
        loadPlayback()
    }

    // Drive MPV state from LaunchedEffect so JNI calls only happen when values change,
    // not on every recomposition triggered by overlay animations.
    LaunchedEffect(currentSourceUrl) {
        subtitlePreferenceAppliedForSource = null
        if (!currentSourceUrl.isNullOrBlank()) playerView?.setSource(currentSourceUrl)
        val resumeAt = pendingResumePositionSec
        if (resumeAt != null && resumeAt > 0.0) {
            delay(1200)
            playerView?.seekTo(resumeAt)
        }
    }

    LaunchedEffect(speed) {
        playerView?.setSpeed(speed)
    }

    LaunchedEffect(paused, panel, loading, error, watchlistPromptVisible) {
        playerView?.setPaused(paused)
        if (watchlistPromptVisible) {
            pauseInfoVisible = false
        } else if (paused && panel == null && !loading && error == null) {
            pauseInfoVisible = false
            delay(2500)
            if (paused && panel == null && !loading && error == null && !watchlistPromptVisible) {
                controlsVisible = false
                pauseInfoVisible = true
            }
        } else {
            pauseInfoVisible = false
        }
    }

    LaunchedEffect(currentSourceUrl, paused, completionThresholdReached) {
        while (currentSourceUrl != null && !completionThresholdReached) {
            delay(15000)
            syncProgressIfEligible()
        }
    }

    LaunchedEffect(currentSourceUrl, paused, loading) {
        val activeSource = currentSourceUrl
        if (activeSource.isNullOrBlank() || paused || loading) return@LaunchedEffect

        if (!traktScrobbledStart) {
            traktScrobbledStart = repository.traktScrobble(
                action = "start",
                mediaType = request.mediaType,
                mediaId = request.mediaId,
                title = detail?.title ?: request.title,
                year = detail?.year,
                progress = 0.0,
            )
        }

        while (currentSourceUrl == activeSource && !paused && !loading) {
            delay(60000)
            if (currentSourceUrl == activeSource && !paused && !loading) {
                repository.traktScrobble(
                    action = "pause",
                    mediaType = request.mediaType,
                    mediaId = request.mediaId,
                    title = detail?.title ?: request.title,
                    year = detail?.year,
                    progress = traktProgressPercent(),
                )
            }
        }
    }

    LaunchedEffect(positionSec, durationSec, loading, error, watchlistPromptVisible) {
        if (loading || error != null || watchlistPromptVisible) return@LaunchedEffect
        if (completionThresholdReached || durationSec < 60.0 || positionSec < 120.0) return@LaunchedEffect
        val thresholdReached = traktProgressPercent() >= 95.0 ||
            (durationSec >= 1200.0 && (durationSec - positionSec) <= 480.0)
        if (!thresholdReached) return@LaunchedEffect
        completionThresholdReached = true
        TvDebugLogger.i(
            "Player",
            "completion threshold reached mediaType=${request.mediaType} mediaId=${request.mediaId} progress=${traktProgressPercent()} remaining=${durationSec - positionSec} inWatchlist=$inWatchlist",
        )
        scope.launch {
            markWatchedAndClearProgressIfNeeded()
        }
        if (inWatchlist && nextEpisode == null && !watchlistPromptShown) {
            watchlistPromptShown = true
            paused = true
            controlsVisible = false
            watchlistPromptVisible = true
        }
    }

    LaunchedEffect(positionSec, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber, nextEpisode, playbackPreferences.isAutoPlayNextEpisodeEnabled()) {
        val action = activeSegmentAction()
        if (!playbackPreferences.isAutoPlayNextEpisodeEnabled() || action?.kind != SegmentActionKind.NextEpisode || nextEpisodeDialogVisible) {
            return@LaunchedEffect
        }
        delay(1200)
        val refreshedAction = activeSegmentAction()
        if (playbackPreferences.isAutoPlayNextEpisodeEnabled() && refreshedAction?.kind == SegmentActionKind.NextEpisode && !nextEpisodeDialogVisible) {
            openNextEpisodeDialog()
        }
    }

    LaunchedEffect(nextEpisodeDialogVisible, nextEpisodeCountdown) {
        val countdown = nextEpisodeCountdown
        if (!nextEpisodeDialogVisible || countdown == null || countdown <= 0) return@LaunchedEffect
        delay(1000)
        if (countdown == 1) {
            beginNextEpisode()
        } else {
            nextEpisodeCountdown = countdown - 1
        }
    }

    LaunchedEffect(panel) {
        if (panel != null) {
            delay(80)
            runCatching { panelFirstItemRequester.requestFocus() }
                .onFailure { TvDebugLogger.w("Player", "panel focus request skipped: ${it.message}") }
        }
    }

    LaunchedEffect(error) {
        if (error != null) {
            delay(80)
            try { errorBackRequester.requestFocus() } catch (_: Exception) { }
        }
    }

    LaunchedEffect(watchlistPromptVisible) {
        if (watchlistPromptVisible) {
            delay(80)
            runCatching { watchlistPromptRemoveRequester.requestFocus() }
                .onFailure { TvDebugLogger.w("Player", "watchlist prompt focus skipped: ${it.message}") }
        }
    }

    DisposableEffect(request.mediaId, currentEpisode, currentSourceUrl) {
        onDispose {
            controlsHideJob?.cancel()
            pendingSeekJob?.cancel()
            queueTraktStop()
            scope.launch {
                syncProgressIfEligible()
            }
        }
    }

    BackHandler {
        if (nextEpisodeDialogVisible) {
            nextEpisodeDialogVisible = false
            nextEpisodeCountdown = null
            paused = false
            scheduleControlsHide()
        } else if (watchlistPromptVisible) {
            watchlistPromptVisible = false
            paused = false
            scheduleControlsHide()
        } else if (panel != null) {
            panel = null
            showControls(focusPlay = true)
        } else {
            TvDebugLogger.i("Player", "back exit to streams mediaType=${request.mediaType} mediaId=${request.mediaId}")
            queueTraktStop()
            scope.launch {
                syncProgressIfEligible()
            }
            onExitToStreams()
        }
    }

    val breathing = rememberInfiniteTransition(label = "player-loading")
    val logoScale by breathing.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(animation = tween(1600), repeatMode = RepeatMode.Reverse),
        label = "logo-breathe",
    )
    val logoAlpha by breathing.animateFloat(
        initialValue = 0.68f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1600), repeatMode = RepeatMode.Reverse),
        label = "logo-alpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                if (!loading && panel == null && !controlsVisible && error == null && !watchlistPromptVisible) {
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            // Reveal controls and land focus on the progress bar for scrubbing
                            controlsVisible = true
                            scheduleControlsHide()
                            scope.launch {
                                delay(60)
                                try { progressRequester.requestFocus() } catch (_: Exception) { }
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
                        Key.DirectionUp, Key.DirectionDown -> {
                            showControls(focusPlay = true)
                            return@onPreviewKeyEvent true
                        }
                        else -> {}
                    }
                }
                false
            },
    ) {
        AndroidView(
            factory = { context ->
                MPVView(context).apply {
                    setHeaders(mapOf("User-Agent" to "Mozilla/5.0 StreamDekTV"))
                    onRemoteCenterCallback = {
                        if (!controlsVisible || panel != null) {
                            showControls(focusPlay = true)
                            true
                        } else {
                            false
                        }
                    }
                    onLoadCallback = { _, _, _ ->
                        TvDebugLogger.i(
                            "Player",
                            "onLoad mediaType=${request.mediaType} mediaId=${request.mediaId} source=${currentSourceUrl ?: "none"} label=$currentLabel resume=${pendingResumePositionSec ?: 0.0}",
                        )
                        loading = false
                        error = null
                        lastWorkingSourceUrl = currentSourceUrl
                        lastWorkingLabel = currentLabel
                        showControls(focusPlay = true)
                        pendingResumePositionSec?.takeIf { it > 0.0 }?.let { seekTo(it) }
                    }
                    onProgressCallback = { position, duration ->
                        positionSec = position
                        durationSec = duration
                    }
                    onEndCallback = {
                        TvDebugLogger.i(
                            "Player",
                            "onEnd mediaType=${request.mediaType} mediaId=${request.mediaId} nextEpisode=${nextEpisode != null} position=$positionSec duration=$durationSec source=${currentSourceUrl ?: "none"} inWatchlist=$inWatchlist",
                        )
                        completePlaybackAndExit()
                    }
                    onErrorCallback = { message ->
                        TvDebugLogger.w(
                            "Player",
                            "onError mediaType=${request.mediaType} mediaId=${request.mediaId} source=${currentSourceUrl ?: "none"} label=$currentLabel position=$positionSec duration=$durationSec message=$message",
                        )
                        error = message
                        loading = false
                        showControls(focusPlay = true)
                    }
                    onTracksChangedCallback = { audio, subtitles, selectedAudioTrackId, selectedSubtitleTrackId ->
                        audioTracks = audio
                        subtitleTracks = subtitles
                        selectedAudioId = selectedAudioTrackId ?: -1
                        selectedSubtitleId = selectedSubtitleTrackId ?: -1
                        val currentSource = currentSourceUrl
                        if (
                            currentSource != null &&
                            subtitlePreferenceAppliedForSource != currentSource
                        ) {
                            subtitlePreferenceAppliedForSource = currentSource
                            preferredSubtitleTrack(
                                subtitles = subtitles,
                                preferredLanguage = repository.bootstrap.value?.preferences?.playback?.defaultSubtitleLanguage ?: "en",
                            )?.let { preferredTrack ->
                                if (selectedSubtitleTrackId != preferredTrack.id) {
                                    setSubtitleTrack(preferredTrack.id)
                                }
                            }
                        }
                    }
                    playerView = this
                }
            },
            update = { view ->
                playerView = view
                view.onRemoteCenterCallback = {
                    if (!controlsVisible || panel != null) {
                        showControls(focusPlay = true)
                        true
                    } else {
                        false
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Loading screen — backdrop + breathing logo only, no controls
        if (loading) {
            val loadingBackdrop = detail?.backdrop ?: detail?.poster
            if (!loadingBackdrop.isNullOrBlank()) {
                AsyncImage(
                    model = loadingBackdrop,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xE0000000), Color(0xAA000000), Color(0xF0000000)),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                detail?.titleLogo?.takeIf { it.isNotBlank() }?.let { logo ->
                    AsyncImage(
                        model = logo,
                        contentDescription = detail?.title ?: request.title,
                        modifier = Modifier
                            .width(340.dp)
                            .scale(logoScale)
                            .alpha(logoAlpha),
                        contentScale = ContentScale.Fit,
                    )
                } ?: Text(
                    text = detail?.title ?: request.title ?: "Loading",
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.scale(logoScale).alpha(logoAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = currentLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.60f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Error overlay — shown when playback fails so user always has a clear exit path
        if (watchlistPromptVisible && !loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000)),
                contentAlignment = Alignment.Center,
            ) {
                PlayerGlassSurface(
                    modifier = Modifier.width(560.dp),
                    contentPadding = PaddingValues(24.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        androidx.tv.material3.Text(
                            text = "Still in your watchlist",
                            style = androidx.tv.material3.MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Black),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        androidx.tv.material3.Text(
                            text = "You've finished this title. Remove it from your watchlist?",
                            style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.tv.material3.Button(
                                onClick = {
                                    scope.launch {
                                        removeFromWatchlistIfNeeded()
                                    }
                                    watchlistPromptVisible = false
                                    paused = false
                                    scheduleControlsHide()
                                },
                                modifier = Modifier.focusRequester(watchlistPromptRemoveRequester),
                            ) {
                                androidx.tv.material3.Text("Remove")
                            }
                            androidx.tv.material3.OutlinedButton(
                                onClick = {
                                    watchlistPromptVisible = false
                                    paused = false
                                    scheduleControlsHide()
                                },
                                modifier = Modifier.focusRequester(watchlistPromptKeepRequester),
                            ) {
                                androidx.tv.material3.Text("Not Now")
                            }
                        }
                    }
                }
            }
        }

        if (error != null && !loading) {
            val canResume = lastWorkingSourceUrl != null && lastWorkingSourceUrl != currentSourceUrl
            val hasMultipleStreams = (candidate?.streams?.size ?: 0) > 1
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000)),
                contentAlignment = Alignment.Center,
            ) {
                PlayerGlassSurface(
                    modifier = Modifier.width(500.dp),
                    contentPadding = PaddingValues(24.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        androidx.tv.material3.Text(
                            text = "Playback Error",
                            style = androidx.tv.material3.MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Black),
                            color = Color(0xFFFFB4AB),
                        )
                        androidx.tv.material3.Text(
                            text = error!!,
                            style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.tv.material3.Button(
                                onClick = {
                                    TvDebugLogger.i("Player", "error overlay go back to streams mediaType=${request.mediaType} mediaId=${request.mediaId}")
                                    scope.launch {
                                        syncProgressIfEligible()
                                    }
                                    onExitToStreams()
                                },
                                modifier = Modifier.focusRequester(errorBackRequester),
                            ) {
                                androidx.tv.material3.Text("Go Back")
                            }
                            if (hasMultipleStreams) {
                                androidx.tv.material3.OutlinedButton(
                                    onClick = {
                                        error = null
                                        panel = OverlayPanel.Streams
                                        controlsVisible = true
                                    },
                                    modifier = Modifier.focusRequester(errorSourcesRequester),
                                ) {
                                    androidx.tv.material3.Text("Try Another Source")
                                }
                            }
                            if (canResume) {
                                androidx.tv.material3.OutlinedButton(
                                    onClick = {
                                        error = null
                                        loading = true
                                        controlsVisible = false
                                        pendingResumePositionSec = positionSec.takeIf { it > 0.0 }
                                        currentSourceUrl = lastWorkingSourceUrl
                                        currentLabel = lastWorkingLabel ?: "Previous source"
                                    },
                                ) {
                                    androidx.tv.material3.Text(
                                        "Resume ${lastWorkingLabel?.take(24) ?: "last source"}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Playback controls — bottom bar
        if (!loading && error == null) {
            PlayerOverlayVisibility(
                visible = controlsVisible || panel != null || (paused && !pauseInfoVisible),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                PlayerBottomBar(
                    detail = detail,
                    requestTitle = request.title,
                    currentEpisode = currentEpisode,
                    currentLabel = currentLabel,
                    error = error,
                    paused = paused,
                    hasNext = nextEpisode != null,
                    positionSec = positionSec,
                    durationSec = durationSec,
                    selectedPanel = panel,
                    playRequester = playRequester,
                    subtitlesRequester = subtitlesRequester,
                    audioRequester = audioRequester,
                    sourcesRequester = sourcesRequester,
                    rewindRequester = rewindRequester,
                    nextRequester = nextRequester,
                    watchedRequester = watchedRequester,
                    speedRequester = speedRequester,
                    progressRequester = progressRequester,
                    onInteract = { registerInteraction() },
                    onPlayPause = {
                        paused = !paused
                        if (!paused) scheduleControlsHide()
                    },
                    onRewind = {
                        scheduleSeek(positionSec - 10.0)
                        registerInteraction()
                    },
                    onNext = {
                        nextEpisode?.let { currentEpisode = it }
                        registerInteraction()
                    },
                    onMarkWatched = {
                        scope.launch {
                            markWatchedAndClearProgressIfNeeded()
                            onExitToStreams()
                        }
                    },
                    onSeekRelative = { delta ->
                        scheduleRelativeSeek(delta)
                        registerInteraction()
                    },
                    onOpenPanel = {
                        panel = it
                        controlsVisible = true
                    },
                )
            }
        }

        // Pause info card — title logo + synopsis, appears 2.5 s after pause
        if (!loading && error == null && !nextEpisodeDialogVisible && !watchlistPromptVisible) {
            activeSegmentAction()?.let { action ->
                PlayerSkipActionChip(
                    label = action.label,
                    bottomPadding = if (controlsVisible) 112.dp else 24.dp,
                    onClick = {
                        when (action.kind) {
                            SegmentActionKind.Skip -> {
                                val target = maxOf(action.targetTimeSec ?: positionSec, positionSec)
                                scheduleSeek(target)
                                markSegmentHandled(action.segmentType)
                                registerInteraction()
                            }
                            SegmentActionKind.NextEpisode -> {
                                scope.launch { openNextEpisodeDialog() }
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }

        if (nextEpisodeDialogVisible && nextEpisode != null) {
            NextEpisodeDialog(
                detail = detail,
                episode = nextEpisode!!,
                streams = nextEpisodeCandidate?.streams.orEmpty(),
                loading = nextEpisodeLoading,
                countdown = nextEpisodeCountdown,
                onPlayNow = { beginNextEpisode() },
                onSelectStream = { index -> beginNextEpisode(index) },
                onCancel = {
                    nextEpisodeDialogVisible = false
                    nextEpisodeCountdown = null
                    paused = false
                    scheduleControlsHide()
                },
            )
        }

        if (pauseInfoVisible && paused && !loading && error == null && panel == null && !controlsVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(520.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, Color(0x38000000), Color(0xC0000000)),
                            ),
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(360.dp)
                        .padding(end = 52.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                        if (!detail?.titleLogo.isNullOrBlank()) {
                            AsyncImage(
                                model = detail!!.titleLogo,
                                contentDescription = detail?.title ?: request.title,
                                modifier = Modifier.height(52.dp),
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.CenterEnd,
                            )
                        } else {
                            androidx.tv.material3.Text(
                                text = detail?.title ?: request.title ?: "",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                                color = Color.White,
                                textAlign = TextAlign.End,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        currentEpisode?.let { ep ->
                            androidx.tv.material3.Text(
                                text = "S${ep.seasonNumber} · E${ep.episodeNumber}" + (ep.title?.let { " — $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFF0BA66),
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val synopsis = currentEpisode?.overview ?: detail?.description
                        if (!synopsis.isNullOrBlank()) {
                            androidx.tv.material3.Text(
                                text = synopsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.74f),
                                textAlign = TextAlign.End,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        androidx.tv.material3.Text(
                            text = "${com.streamdek.tv.nativeapp.ui.formatPlaybackClock(positionSec)} / ${com.streamdek.tv.nativeapp.ui.formatPlaybackClock(durationSec)}",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White.copy(alpha = 0.55f),
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }

        // Option panel (sources / audio / subtitles)
        panel?.let { activePanel ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x54000000)),
            )
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                PlayerOverlayVisibility(
                    visible = true,
                    modifier = Modifier.padding(end = 36.dp, top = 52.dp, bottom = 52.dp),
                ) {
                    PlayerOptionPanel(
                        panel = activePanel,
                        candidate = candidate,
                        audioTracks = audioTracks,
                        subtitleTracks = subtitleTracks,
                        selectedAudioId = selectedAudioId,
                        selectedSubtitleId = selectedSubtitleId,
                        currentSpeed = speed,
                        closeRequester = panelCloseRequester,
                        firstItemRequester = panelFirstItemRequester,
                        onClose = {
                            panel = null
                            showControls(focusPlay = true)
                        },
                        onInteract = { registerInteraction() },
                        onSelectStream = { index ->
                            scope.launch {
                                val stream = candidate?.streams?.getOrNull(index) ?: return@launch
                                panel = null
                                loading = true
                                controlsVisible = false
                                pendingResumePositionSec = positionSec.takeIf { it > 0.0 }
                                val selected = try {
                                    repository.resolvePlayback(
                                        request.mediaType,
                                        request.mediaId,
                                        request.imdbId,
                                        currentEpisode,
                                        preferredStreamKey = repository.streamSelectionKey(stream),
                                        forceRefresh = true,
                                    )
                                } catch (e: Exception) {
                                    error = "Could not load this source: ${e.message ?: "Unknown error"}"
                                    loading = false
                                    controlsVisible = true
                                    return@launch
                                }
                                if (selected.source == null) {
                                    error = "This source could not be resolved. Please try another."
                                    loading = false
                                    controlsVisible = true
                                    return@launch
                                }
                                candidate = selected
                                currentSourceUrl = selected.source.url
                                currentLabel = selected.source.label ?: repository.describeStreamOption(stream)
                            }
                        },
                        onSelectAudio = {
                            playerView?.setAudioTrack(it)
                            panel = null
                            showControls(focusPlay = true)
                        },
                        onDisableSubtitles = {
                            subtitlePreferenceAppliedForSource = currentSourceUrl
                            playerView?.disableSubtitleTrack()
                            panel = null
                            showControls(focusPlay = true)
                        },
                        onSelectSubtitle = {
                            subtitlePreferenceAppliedForSource = currentSourceUrl
                            playerView?.setSubtitleTrack(it)
                            panel = null
                            showControls(focusPlay = true)
                        },
                        onSelectSpeed = {
                            speed = it
                            panel = null
                            showControls(focusPlay = true)
                        },
                    )
                }
            }
        }
    }

private suspend fun resolveNextEpisode(
    repository: StreamDekRepository,
    request: PlaybackRequest,
    detail: MediaDetail?,
    currentEpisode: EpisodeContext?,
): EpisodeContext? {
    if (request.mediaType != "tv" || currentEpisode == null || detail == null) return null
    val currentSeason = repository.fetchSeason(request.mediaId, currentEpisode.seasonNumber) ?: return null
    val nextInSeason = currentSeason.episodes.firstOrNull { it.episodeNumber == currentEpisode.episodeNumber + 1 }
    if (nextInSeason != null) {
        return EpisodeContext(
            seasonNumber = currentEpisode.seasonNumber,
            episodeNumber = nextInSeason.episodeNumber,
            title = nextInSeason.name,
            overview = nextInSeason.overview,
            still = nextInSeason.still,
            runtime = nextInSeason.runtime,
            airDate = nextInSeason.airDate,
            tmdbEpisodeId = nextInSeason.id,
        )
    }
    val nextSeasonNumber = detail.seasons.firstOrNull { it.seasonNumber > currentEpisode.seasonNumber }?.seasonNumber ?: return null
    val nextSeason = repository.fetchSeason(request.mediaId, nextSeasonNumber) ?: return null
    val first = nextSeason.episodes.firstOrNull() ?: return null
    return EpisodeContext(
        seasonNumber = nextSeasonNumber,
        episodeNumber = first.episodeNumber,
        title = first.name,
        overview = first.overview,
        still = first.still,
        runtime = first.runtime,
        airDate = first.airDate,
        tmdbEpisodeId = first.id,
    )
}

private fun segmentPriority(segmentType: String): Int {
    return when (segmentType) {
        "intro" -> 0
        "recap" -> 1
        "outro" -> 2
        else -> 3
    }
}

private fun preferredSubtitleTrack(
    subtitles: List<MpvTrackInfo>,
    preferredLanguage: String,
): MpvTrackInfo? {
    val normalizedPreference = preferredLanguage.trim().lowercase()
    if (normalizedPreference.isBlank() || normalizedPreference == "off") return null
    return subtitles.firstOrNull { track ->
        subtitleMatchesPreference(track, normalizedPreference)
    }
}

private fun subtitleMatchesPreference(track: MpvTrackInfo, preferredLanguage: String): Boolean {
    val normalizedLanguage = track.language?.trim()?.lowercase().orEmpty()
    val normalizedTitle = track.title?.trim()?.lowercase().orEmpty()
    val aliases = when (preferredLanguage) {
        "en", "eng", "english" -> setOf("en", "eng", "english")
        else -> setOf(preferredLanguage)
    }
    return aliases.any { alias ->
        normalizedLanguage == alias ||
            normalizedLanguage.startsWith("$alias-") ||
            normalizedTitle.contains(alias)
    }
}
