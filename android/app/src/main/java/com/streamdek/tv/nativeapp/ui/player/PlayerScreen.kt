package com.streamdek.tv.nativeapp.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import coil.compose.AsyncImage
import com.streamdek.tv.TvRemoteKeyRouter
import com.streamdek.tv.mpv.MPVTextureView
import com.streamdek.tv.mpv.MPVView
import com.streamdek.tv.mpv.MpvPlayerController
import com.streamdek.tv.mpv.MpvTrackInfo
import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.ExternalSubtitleTrack
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.PlaybackPreferences
import com.streamdek.tv.nativeapp.data.PlaybackSegment
import com.streamdek.tv.nativeapp.data.PlaybackRequest
import com.streamdek.tv.nativeapp.data.ResolvedPlaybackCandidate
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.tvCardLongPress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ControlsHideDelayMs = 3000L
private const val LiveControlsHideDelayMs = 2000L
private const val LiveChannelInfoHideDelayMs = 5000L
private const val LiveHintVisibleMs = 3_000L
private const val LiveHintCycleMs = 15_000L
private const val AutoPlayNextEpisodeCountdownSeconds = 5

/**
 * Live feeds drop out routinely (upstream restarts, ad breaks, CDN switches). Mobile retries
 * indefinitely; the TV app uses a bounded retry budget before surfacing a manual retry so a broken
 * outage never dumps the viewer out of the channel.
 */
private const val LiveReconnectMaxAttempts = 6
private const val LiveStallTimeoutMs = 15_000L

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

internal enum class ActivePlaybackEngine { Media3, MPV }

internal enum class LiveRetryAction { Reload, Refetch, GiveUp }

internal fun liveRetryAction(attempt: Int): LiveRetryAction = when (attempt) {
    in 1..2 -> LiveRetryAction.Reload
    in 3..LiveReconnectMaxAttempts -> LiveRetryAction.Refetch
    else -> LiveRetryAction.GiveUp
}

internal fun normalizePlayerEngineSetting(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "media3", "exo", "exoplayer" -> "Media3"
    "mpv" -> "MPV"
    else -> "Auto"
}

internal fun initialPlaybackEngine(preference: String?): ActivePlaybackEngine =
    if (preference.equals("MPV", ignoreCase = true)) ActivePlaybackEngine.MPV else ActivePlaybackEngine.Media3

internal fun shouldAutoFallbackToMpv(
    preference: String?,
    activeEngine: ActivePlaybackEngine,
    fallbackUsed: Boolean,
): Boolean = preference.equals("Auto", ignoreCase = true) &&
    activeEngine == ActivePlaybackEngine.Media3 &&
    !fallbackUsed

@Composable
fun PlayerScreen(
    repository: StreamDekRepository,
    request: PlaybackRequest,
    onBack: () -> Unit,
    onExitToStreams: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val view = LocalView.current
    val bootstrap by repository.bootstrap.collectAsState()
    val playbackPreferences = bootstrap?.preferences?.playback ?: PlaybackPreferences()
    // Live broadcasts: no detail/progress/watched bookkeeping, no seeking, and
    // back exits to the previous screen rather than the streams picker.
    val isLive = request.mediaType == "live"
    var activeLiveRequest by remember(request) { mutableStateOf<PlaybackRequest?>(null) }
    var liveChannelHistory by remember(request) { mutableStateOf<List<PlaybackRequest>>(emptyList()) }
    val playbackRequest = activeLiveRequest ?: request
    val isVod = isLive && playbackRequest.streamType.equals("movie", ignoreCase = true)
    val favouriteChannels by repository.favouriteChannels.collectAsState()
    val liveAddonFavourites = if (isLive) favouriteChannels else emptyList()
    val favouriteChannelKeys = favouriteChannels.mapTo(linkedSetOf()) { "${it.sourceAddonId}:${it.id}" }
    val currentChannelTitle = playbackRequest.title?.takeIf { it.isNotBlank() } ?: "Live TV"
    val completePlaybackExit: () -> Unit = if (isLive) onBack else onExitToStreams
    val backExitPlayback: () -> Unit = if (isLive || request.returnToDetailOnBack) onBack else onExitToStreams

    // Seeded from the cache the detail and streams screens already filled, so the start-up screen
    // shows this title's artwork immediately instead of a black hold while the fetch repeats.
    var detail by remember { mutableStateOf(if (isLive) null else repository.peekCachedDetail(request.mediaId, request.mediaType)) }
    var currentEpisode by remember(request) { mutableStateOf(request.episode) }
    var nextEpisode by remember { mutableStateOf<EpisodeContext?>(null) }
    var candidate by remember { mutableStateOf<ResolvedPlaybackCandidate?>(null) }
    var currentSourceUrl by remember { mutableStateOf<String?>(null) }
    val defaultPlaybackHeaders = remember { mapOf("User-Agent" to "Mozilla/5.0 StreamDekTV") }
    var currentRequestHeaders by remember { mutableStateOf(defaultPlaybackHeaders) }
    var currentLabel by remember { mutableStateOf("Selecting stream…") }
    var paused by remember { mutableStateOf(false) }
    var positionSec by remember { mutableDoubleStateOf(0.0) }
    var durationSec by remember { mutableDoubleStateOf(0.0) }
    var audioTracks by remember { mutableStateOf<List<MpvTrackInfo>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<MpvTrackInfo>>(emptyList()) }
    var externalSubtitles by remember { mutableStateOf<List<ExternalSubtitleTrack>>(emptyList()) }
    var subtitlesLoading by remember { mutableStateOf(false) }
    var selectedExternalSubtitleId by remember { mutableStateOf<String?>(null) }
    var externalSubtitleAppliedKey by remember { mutableStateOf<String?>(null) }
    var selectedAudioId by remember { mutableIntStateOf(-1) }
    var selectedSubtitleId by remember { mutableIntStateOf(-1) }
    var speed by remember { mutableDoubleStateOf(1.0) }
    var panel by remember { mutableStateOf<OverlayPanel?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var playerView: MpvPlayerController? by remember { mutableStateOf(null) }
    var activePlaybackEngine by remember(currentSourceUrl, playbackPreferences.playerEngine) {
        mutableStateOf(initialPlaybackEngine(playbackPreferences.playerEngine))
    }
    var autoEngineFallbackUsed by remember(currentSourceUrl, playbackPreferences.playerEngine) { mutableStateOf(false) }
    var failedStreamKeys by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(emptySet<String>()) }
    var sourceFallbackInProgress by remember(request.mediaId, request.mediaType) { mutableStateOf(false) }
    var pendingEngineResumePositionSec by remember(currentSourceUrl) { mutableStateOf<Double?>(null) }
    var loading by remember { mutableStateOf(true) }
    var controlsVisible by remember { mutableStateOf(false) }
    var showLiveProgress by remember(playbackRequest.mediaId, playbackPreferences.liveProgressBarEnabled) {
        mutableStateOf(playbackPreferences.liveProgressBarEnabled)
    }
    var controlsHideJob by remember { mutableStateOf<Job?>(null) }
    var liveChannelInfoVisible by remember { mutableStateOf(false) }
    var liveChannelInfoHideJob by remember { mutableStateOf<Job?>(null) }
    var liveChannels by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var liveChannelsLoading by remember { mutableStateOf(false) }
    var liveChannelRowVisible by remember { mutableStateOf(false) }
    var liveFavouritesDrawerVisible by remember { mutableStateOf(false) }
    var liveFavouritesCardView by remember { mutableStateOf(false) }
    var liveHintsVisible by remember { mutableStateOf(true) }
    var liveRefetchGeneration by remember { mutableIntStateOf(0) }
    var lastLiveProgressAtMs by remember { mutableStateOf(0L) }
    var lastLiveProgressPositionSec by remember { mutableDoubleStateOf(-1.0) }
    var pendingSeekJob by remember { mutableStateOf<Job?>(null) }
    var pendingResumePositionSec by remember { mutableStateOf<Double?>(null) }
    var lastWorkingSourceUrl by remember { mutableStateOf<String?>(null) }
    var lastWorkingLabel by remember { mutableStateOf<String?>(null) }
    var lastWorkingRequestHeaders by remember { mutableStateOf(defaultPlaybackHeaders) }
    var liveReconnectJob by remember { mutableStateOf<Job?>(null) }
    var liveReconnectAttempt by remember { mutableIntStateOf(0) }
    var pauseInfoVisible by remember { mutableStateOf(false) }
    var lastSeekInputAt by remember { mutableStateOf(0L) }
    var lastSeekDirection by remember { mutableIntStateOf(0) }
    var seekBurstCount by remember { mutableIntStateOf(0) }
    // Scrub state: while a seek is in flight, MPV progress events still report the old
    // position; seekTargetSec pins the UI position until the seek settles so the bar
    // never snaps backwards mid-scrub.
    var seekTargetSec by remember { mutableStateOf<Double?>(null) }
    var seekIssuedAtMs by remember { mutableStateOf(0L) }
    var lastSeekCommandAtMs by remember { mutableStateOf(0L) }
    // Guards against ghost clicks landing on the play/pause button right after an
    // option panel closes (tv-material fires onClick on KeyUp, so a selection's key
    // release can reach the newly focused control).
    var panelClosedAtMs by remember { mutableStateOf(0L) }
    var audioPreferenceAppliedForSource by remember { mutableStateOf<String?>(null) }
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
    var brightnessPercent by remember { mutableIntStateOf(repository.playerBrightnessPercent()) }
    /**
     * A skip prompt owns the remote while it is up.
     *
     * Nothing else may be summoned until it is taken or dismissed: without this the same press that
     * was meant for "Skip Intro" also raised the transport controls behind it, and the viewer ended
     * up with two things on screen competing for the next press.
     */
    var segmentPromptActive by remember { mutableStateOf(false) }

    val resolvedRenderSurface = remember(playbackPreferences.renderSurface) {
        normalizeRenderSurfacePreference(playbackPreferences.renderSurface)
    }

    val errorBackRequester = remember { FocusRequester() }
    val errorSourcesRequester = remember { FocusRequester() }
    val watchlistPromptKeepRequester = remember { FocusRequester() }
    val watchlistPromptRemoveRequester = remember { FocusRequester() }
    val playRequester = remember { FocusRequester() }
    val subtitlesRequester = remember { FocusRequester() }
    val audioRequester = remember { FocusRequester() }
    val sourcesRequester = remember { FocusRequester() }
    val nextRequester = remember { FocusRequester() }
    val watchedRequester = remember { FocusRequester() }
    val speedRequester = remember { FocusRequester() }
    val brightnessRequester = remember { FocusRequester() }
    val segmentChipRequester = remember { FocusRequester() }
    val nextEpisodePlayRequester = remember { FocusRequester() }
    val nextEpisodeCancelRequester = remember { FocusRequester() }
    val progressRequester = remember { FocusRequester() }
    val liveProgressRequester = remember { FocusRequester() }
    val panelCloseRequester = remember { FocusRequester() }
    val panelFirstItemRequester = remember { FocusRequester() }
    val playerRootRequester = remember { FocusRequester() }
    val liveChannelFirstRequester = remember { FocusRequester() }
    val liveChannelListState = rememberLazyListState()
    val liveFavouriteFirstRequester = remember { FocusRequester() }
    val liveFavouriteListState = rememberLazyListState()

    // Keep screen on while the player is active
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            // Closes the news server connections and drops the partially assembled file. A no-op
            // unless what was playing came from a usenet source.
            com.streamdek.tv.nativeapp.usenet.UsenetPlayback.release()
        }
    }

    fun hideControlsNow() {
        controlsHideJob?.cancel()
        controlsHideJob = null
        controlsVisible = false
    }

    fun showLiveChannelInfo() {
        if (!isLive || loading || error != null) return
        liveChannelInfoHideJob?.cancel()
        liveChannelInfoVisible = true
        liveChannelInfoHideJob = scope.launch {
            delay(LiveChannelInfoHideDelayMs)
            liveChannelInfoVisible = false
        }
    }

    fun showLiveChannelRow() {
        if (!isLive || liveChannels.size <= 1 || loading || error != null) {
            showLiveChannelInfo()
            return
        }
        liveChannelInfoHideJob?.cancel()
        liveChannelInfoVisible = false
        liveChannelRowVisible = true
        scope.launch {
            val currentIndex = liveChannels.indexOfFirst { it.id == playbackRequest.mediaId }.coerceAtLeast(0)
            liveChannelListState.scrollToItem(currentIndex)
            delay(100)
            runCatching { liveChannelFirstRequester.requestFocus() }
        }
    }

    fun showLiveFavouritesDrawer() {
        if (!isLive || liveAddonFavourites.isEmpty() || loading || error != null) return
        liveChannelInfoHideJob?.cancel()
        liveChannelInfoVisible = false
        liveChannelRowVisible = false
        liveFavouritesDrawerVisible = true
        scope.launch {
            val currentIndex = liveAddonFavourites.indexOfFirst { it.id == playbackRequest.mediaId }.coerceAtLeast(0)
            liveFavouriteListState.scrollToItem(currentIndex)
            delay(100)
            runCatching { liveFavouriteFirstRequester.requestFocus() }
        }
    }
    fun selectLiveChannel(item: MediaItem) {
        if (!isLive || (item.id == playbackRequest.mediaId && item.sourceAddonId == playbackRequest.sourceAddonId)) {
            // The clicked channel is already playing — this is the common case when the
            // favourites drawer opens with the current channel pre-focused and the user just
            // presses OK on it. Both overlays need to close here, not just the channel row,
            // or selecting the already-playing favourite silently does nothing.
            liveChannelRowVisible = false
            liveFavouritesDrawerVisible = false
            scope.launch { runCatching { playerRootRequester.requestFocus() } }
            return
        }
        val nextRequest = PlaybackRequest(
            mediaId = item.id,
            mediaType = "live",
            title = item.title,
            streamType = item.streamType,
            sourceAddonId = item.sourceAddonId,
            sourceAddonName = item.sourceAddonName,
            sourceCatalogId = item.sourceCatalogId,
            sourceCatalogName = item.sourceCatalogName,
            directStreamUrl = item.directStreamUrl,
            requestHeaders = item.requestHeaders,
        )
        TvDebugLogger.i("Player", "switch live channel from=${playbackRequest.mediaId} to=${item.id} addon=${item.sourceAddonName}")
        liveChannelHistory = (liveChannelHistory + playbackRequest).takeLast(20)
        repository.savePlaybackRequest(nextRequest)
        loading = true
        error = null
        currentLabel = "Loading ${item.title}…"
        liveChannelRowVisible = false
        liveFavouritesDrawerVisible = false
        liveReconnectAttempt = 0
        liveRefetchGeneration = 0
        streamKeyOverride = null
        streamLabelOverride = null
        activeLiveRequest = nextRequest
    }

    fun scheduleLiveReconnect(message: String) {
        if (!isLive || liveReconnectJob?.isActive == true) return
        val attempt = liveReconnectAttempt + 1
        val retryAction = liveRetryAction(attempt)
        if (retryAction == LiveRetryAction.GiveUp) {
            error = "Live feed unavailable after $LiveReconnectMaxAttempts retries: $message"
            loading = false
            controlsVisible = true
            return
        }
        liveReconnectAttempt = attempt
        error = null
        loading = true
        liveChannelRowVisible = false
        controlsVisible = false
        currentLabel = if (retryAction == LiveRetryAction.Reload) {
            "Reloading live feed…"
        } else {
            "Refreshing live source… (attempt $attempt)"
        }
        TvDebugLogger.w("Player", "live retry attempt=$attempt mediaId=${playbackRequest.mediaId} reason=$message")
        liveReconnectJob = scope.launch {
            delay((attempt * 500L).coerceIn(500L, 5_000L))
            if (retryAction == LiveRetryAction.Reload && !currentSourceUrl.isNullOrBlank()) {
                playerView?.reloadSource()
                playerView?.setPaused(false)
            } else {
                liveReconnectJob = null
                liveRefetchGeneration += 1
            }
        }
    }
    fun scheduleSeek(targetSeconds: Double, fast: Boolean = false) {
        val target = targetSeconds
            .coerceAtLeast(0.0)
            .coerceAtMost(durationSec.takeIf { it > 0.0 } ?: targetSeconds)
        seekTargetSec = target
        seekIssuedAtMs = System.currentTimeMillis()
        positionSec = target
        pendingSeekJob?.cancel()
        val now = System.currentTimeMillis()
        if (now - lastSeekCommandAtMs >= 350L) {
            // Throttled immediate seek keeps held-button scrubbing responsive
            // instead of waiting for the key repeats to stop.
            lastSeekCommandAtMs = now
            if (fast) playerView?.seekToFast(target) else playerView?.seekTo(target)
        }
        // Always schedule a trailing settle so the final resting position is exact,
        // including the last step of a held scrub.
        pendingSeekJob = scope.launch {
            delay(if (fast) 220 else 160)
            lastSeekCommandAtMs = System.currentTimeMillis()
            seekIssuedAtMs = lastSeekCommandAtMs
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
        // Base the next step on the in-flight scrub target, not the (stale) playback
        // position, so consecutive presses always accumulate in the scrub direction.
        scheduleSeek((seekTargetSec ?: positionSec) + (baseDeltaSeconds * multiplier), fast = true)
    }

    fun scheduleControlsHide() {
        controlsHideJob?.cancel()
        controlsHideJob = null
        if (paused || panel != null || loading || error != null) return
        controlsHideJob = scope.launch {
            delay(if (isLive) LiveControlsHideDelayMs else ControlsHideDelayMs)
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
        // Every route into the controls funnels through here — key handling, the engine's own
        // remote callbacks, the end of a load — so one check covers all of them.
        if (segmentPromptActive) return
        pauseInfoVisible = false
        controlsVisible = true
        scheduleControlsHide()
        if (focusPlay) requestPlaybackFocus()
    }

    fun registerInteraction() {
        if (segmentPromptActive) return
        pauseInfoVisible = false
        if (!controlsVisible) controlsVisible = true
        scheduleControlsHide()
    }

    fun traktProgressPercent(): Double {
        if (durationSec <= 0.0) return 0.0
        return ((positionSec / durationSec) * 100.0).coerceIn(0.0, 100.0)
    }

    fun activeSegmentAction(): SegmentAction? {
        val outro = segments.firstOrNull { it.segmentType == "outro" }
        val nextEpisodeAvailable = nextEpisode != null &&
            playbackPreferences.isAutoPlayNextEpisodeEnabled() &&
            !handledSegmentTypes.contains("outro") &&
            playbackPreferences.isNextEpisodeThresholdReached(
                positionSec = positionSec,
                durationSec = durationSec,
                segmentStartSec = outro?.startSec,
            )
        if (nextEpisodeAvailable) {
            return SegmentAction(
                kind = SegmentActionKind.NextEpisode,
                segmentType = "outro",
                label = "Next Episode",
            )
        }

        val activeSegment = segments
            .filter { segment ->
                playbackPreferences.isSegmentEnabled(segment.segmentType) &&
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
            "outro" -> SegmentAction(
                kind = SegmentActionKind.Skip,
                segmentType = activeSegment.segmentType,
                label = "Skip Ending",
                targetTimeSec = activeSegment.endSec,
            )
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
        if (isLive || completionThresholdReached) return
        repository.syncProgress(request.mediaType, request.mediaId, positionSec, durationSec, currentEpisode, detail)
    }

    suspend fun markWatchedAndClearProgressIfNeeded() {
        if (watchedMarked) return
        repository.syncProgress(request.mediaType, request.mediaId, positionSec, durationSec, currentEpisode, detail)
        val marked = repository.markWatched(
            mediaType = request.mediaType,
            mediaId = request.mediaId,
            title = detail?.title ?: request.title ?: "",
            year = detail?.year,
            episode = currentEpisode,
            imdbId = request.imdbId ?: detail?.imdbId,
        )
        watchedMarked = marked
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
            forceRefresh = false,
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
        nextEpisodeDialogVisible = false
        nextEpisodeCountdown = null
        paused = true
        scope.launch {
            markWatchedAndClearProgressIfNeeded()
            if (traktScrobbledStart) {
                traktScrobbledStart = false
                repository.traktScrobble(
                    action = "stop",
                    mediaType = request.mediaType,
                    mediaId = request.mediaId,
                    title = detail?.title ?: request.title,
                    year = detail?.year,
                    progress = traktProgressPercent(),
                )
            }
            streamKeyOverride = repository.streamSelectionKey(selectedStream)
            streamLabelOverride = repository.describeStreamOption(selectedStream)
            nextEpisodeCandidate = null
            paused = false
            currentEpisode = targetEpisode
        }
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
            completePlaybackExit()
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

    suspend fun loadPlayback(forceRefresh: Boolean = false, resetReconnectBudget: Boolean = true) {
        liveReconnectJob?.cancel()
        liveReconnectJob = null
        if (resetReconnectBudget) liveReconnectAttempt = 0
        pendingSeekJob?.cancel()
        pendingSeekJob = null
        seekTargetSec = null
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
        val loadStartedAt = android.os.SystemClock.elapsedRealtime()
        val activeRequest = playbackRequest
        val loadResult = runCatching {
        val selectedStream = activeRequest.selectedStream?.takeIf {
            currentEpisode == activeRequest.episode && streamKeyOverride == activeRequest.selectedStreamKey
        }
        var resolvedCandidate = selectedStream?.let {
            repository.resolveSelectedPlayback(
                request = activeRequest.copy(episode = currentEpisode),
                stream = it,
                streams = activeRequest.availableStreams,
                forceRefresh = forceRefresh,
            )
        }
        // Start the player as soon as the chosen source resolves. Detail, library, resume,
        // IntroDB, and watched metadata can continue loading without blocking decoder startup.
        resolvedCandidate?.source?.let { source ->
            candidate = resolvedCandidate
            currentRequestHeaders = defaultPlaybackHeaders + source.requestHeaders
            currentSourceUrl = source.url
            currentLabel = streamLabelOverride ?: source.label
            TvDebugLogger.i("Player", "source ready addon=${resolvedCandidate?.stream?.addonName} elapsedMs=${android.os.SystemClock.elapsedRealtime() - loadStartedAt}")
        }
        detail = if (isLive) null else repository.fetchDetail(activeRequest.mediaId, activeRequest.mediaType)
        val effectiveImdbId = activeRequest.imdbId ?: detail?.imdbId
        inWatchlist = if (isLive) false else runCatching {
            repository.fetchLibrary().watchlist.any { it.id == activeRequest.mediaId && it.type == activeRequest.mediaType }
        }.getOrDefault(false)
        val continueWatchingItem = if (activeRequest.mediaType == "tv") {
            repository.fetchContinueWatchingItem(activeRequest.mediaType, activeRequest.mediaId)
        } else {
            null
        }
        if (activeRequest.mediaType == "tv" && currentEpisode == null) {
            val firstSeason = detail?.seasons?.firstOrNull()?.seasonNumber
            val season = firstSeason?.let { repository.fetchSeason(activeRequest.mediaId, it) }
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
        val progress = if (isLive) null else repository.fetchProgress(activeRequest.mediaType, activeRequest.mediaId, currentEpisode)
        pendingResumePositionSec = if (isLive) {
            null
        } else {
            activeRequest.startPositionSec?.takeIf { it > 0.0 }
                ?: progress?.positionSec
                ?.takeIf { it > 0.0 }
                ?: continueWatchingItem?.positionSec
                ?: continueWatchingItem?.resumeAt
        }
        val resolved = resolvedCandidate ?: repository.resolvePlayback(
            activeRequest.mediaType,
            activeRequest.mediaId,
            effectiveImdbId,
            currentEpisode,
            preferredStreamKey = streamKeyOverride,
            forceRefresh = forceRefresh,
            streamType = activeRequest.streamType,
            directStreamUrl = activeRequest.directStreamUrl,
            requestHeaders = activeRequest.requestHeaders,
            sourceAddonId = activeRequest.sourceAddonId,
            sourceAddonName = activeRequest.sourceAddonName,
        ).also { resolvedCandidate = it }
        candidate = resolved
        TvDebugLogger.i("Player", "playback load complete addon=${resolved.stream?.addonName} elapsedMs=${android.os.SystemClock.elapsedRealtime() - loadStartedAt}")
        currentRequestHeaders = defaultPlaybackHeaders + resolved.source?.requestHeaders.orEmpty()
        currentSourceUrl = resolved.source?.url
        currentLabel = streamLabelOverride ?: resolved.source?.label ?: "No playable stream found"
        positionSec = pendingResumePositionSec ?: 0.0
        durationSec = progress?.durationSec ?: 0.0
        nextEpisode = resolveNextEpisode(repository, activeRequest, detail, currentEpisode)
        if (activeRequest.mediaType == "tv" && currentEpisode != null && !effectiveImdbId.isNullOrBlank()) {
            segments = repository.fetchEpisodeSegments(
                imdbId = effectiveImdbId,
                season = currentEpisode!!.seasonNumber,
                episode = currentEpisode!!.episodeNumber,
            )
            TvDebugLogger.i(
                "Player",
                "segments loaded mediaId=${activeRequest.mediaId} episode=s${currentEpisode!!.seasonNumber}e${currentEpisode!!.episodeNumber} imdbId=$effectiveImdbId count=${segments.size}",
            )
        } else if (activeRequest.mediaType == "tv" && currentEpisode != null) {
            TvDebugLogger.w(
                "Player",
                "segments skipped mediaId=${activeRequest.mediaId} episode=s${currentEpisode!!.seasonNumber}e${currentEpisode!!.episodeNumber} imdbId missing",
            )
        }
        // Live items never participate in watched tracking; marking them watched
        // here keeps markWatchedAndClearProgressIfNeeded() a no-op for live.
        watchedMarked = isLive || repository.isWatched(
            mediaType = activeRequest.mediaType,
            mediaId = activeRequest.mediaId,
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
        loadResult.onFailure { throwable ->
            TvDebugLogger.e("Player", "loadPlayback failed mediaType=${activeRequest.mediaType} mediaId=${activeRequest.mediaId}", throwable)
            candidate = null
            currentSourceUrl = null
            currentLabel = streamLabelOverride ?: "No playable stream found"
            error = when (throwable) {
                is java.net.SocketTimeoutException -> "Stream lookup timed out. Please try again or choose another source."
                else -> throwable.message ?: "Could not prepare playback"
            }
            loading = false
            controlsVisible = true
        }
    }

    LaunchedEffect(playbackRequest.mediaId, playbackRequest.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber, liveRefetchGeneration) {
        val reconnectRefetch = isLive && liveRefetchGeneration > 0
        loadPlayback(forceRefresh = reconnectRefetch, resetReconnectBudget = !reconnectRefetch)
    }

LaunchedEffect(isLive, playbackRequest.sourceAddonId, playbackRequest.sourceCatalogId) {
        if (!isLive || playbackRequest.sourceAddonId.isNullOrBlank()) {
            liveChannels = emptyList()
            liveChannelsLoading = false
            return@LaunchedEffect
        }
        liveChannelsLoading = true
        liveChannels = runCatching {
            repository.fetchRelatedLiveChannels(
                addonId = playbackRequest.sourceAddonId,
                catalogId = playbackRequest.sourceCatalogId,
            )
        }.onFailure {
            TvDebugLogger.w("Player", "live channel row unavailable addon=${playbackRequest.sourceAddonId}: ${it.message}")
        }.getOrDefault(emptyList())
        liveChannelsLoading = false
        TvDebugLogger.i("Player", "live channel row loaded addon=${playbackRequest.sourceAddonId} count=${liveChannels.size}")
    }

    LaunchedEffect(isLive, currentSourceUrl, loading, error) {
        if (!isLive || currentSourceUrl.isNullOrBlank() || loading || error != null) return@LaunchedEffect
        lastLiveProgressAtMs = System.currentTimeMillis()
        while (isLive && !loading && error == null && !currentSourceUrl.isNullOrBlank()) {
            delay(5_000L)
            if (System.currentTimeMillis() - lastLiveProgressAtMs >= LiveStallTimeoutMs) {
                scheduleLiveReconnect("The live feed stalled")
                break
            }
        }
    }
    // Drive MPV state from LaunchedEffect so JNI calls only happen when values change,
    // not on every recomposition triggered by overlay animations.
    LaunchedEffect(currentSourceUrl, currentRequestHeaders, activePlaybackEngine) {
        audioPreferenceAppliedForSource = null
        subtitlePreferenceAppliedForSource = null
        seekTargetSec = null
        playerView?.setHeaders(currentRequestHeaders)
        if (!currentSourceUrl.isNullOrBlank()) playerView?.setSource(currentSourceUrl)
        val resumeAt = pendingResumePositionSec
        if (resumeAt != null && resumeAt > 0.0) {
            delay(1200)
            playerView?.seekTo(resumeAt)
        }
    }


    LaunchedEffect(currentSourceUrl, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber, detail?.imdbId) {
        externalSubtitles = emptyList()
        selectedExternalSubtitleId = null
        externalSubtitleAppliedKey = null
        subtitlesLoading = false
        val source = currentSourceUrl
        if (isLive || source.isNullOrBlank()) return@LaunchedEffect
        subtitlesLoading = true
        val results = repository.fetchExternalSubtitles(
            request.copy(
                imdbId = request.imdbId ?: detail?.imdbId,
                episode = currentEpisode,
            ),
        )
        if (currentSourceUrl != source) return@LaunchedEffect
        externalSubtitles = results
        subtitlesLoading = false
        if (playbackPreferences.autoLoadSubtitles && selectedSubtitleId < 0 && selectedExternalSubtitleId == null) {
            val preferredLanguage = repository.activeStreamProfile(repository.bootstrap.value)?.subtitleLanguage
                ?.takeIf { it.isNotBlank() }
                ?: playbackPreferences.defaultSubtitleLanguage
            val preferred = results.firstOrNull { it.language.equals(preferredLanguage, ignoreCase = true) }
                ?: results.firstOrNull { it.language == "en" }
            if (preferred != null) {
                while (loading && currentSourceUrl == source) delay(100)
                val localPath = repository.downloadSubtitleToCache(preferred.url, context.cacheDir)
                if (localPath != null && currentSourceUrl == source && selectedSubtitleId < 0) {
                    selectedExternalSubtitleId = preferred.id
                    playerView?.addSubtitleFile(localPath)
                    externalSubtitleAppliedKey = "${activePlaybackEngine.name}:$source:${preferred.id}"
                    TvDebugLogger.i("Subtitles", "auto-loaded ${preferred.label}")
                }
            }
        }
    }

    LaunchedEffect(activePlaybackEngine, loading, selectedExternalSubtitleId, currentSourceUrl) {
        val selected = externalSubtitles.firstOrNull { it.id == selectedExternalSubtitleId } ?: return@LaunchedEffect
        val source = currentSourceUrl ?: return@LaunchedEffect
        if (loading) return@LaunchedEffect
        val key = "${activePlaybackEngine.name}:$source:${selected.id}"
        if (externalSubtitleAppliedKey == key) return@LaunchedEffect
        val localPath = repository.downloadSubtitleToCache(selected.url, context.cacheDir) ?: return@LaunchedEffect
        if (currentSourceUrl == source && selectedExternalSubtitleId == selected.id) {
            playerView?.addSubtitleFile(localPath)
            externalSubtitleAppliedKey = key
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
        val activeSource = currentSourceUrl
        if (activeSource.isNullOrBlank() || paused || completionThresholdReached) return@LaunchedEffect
        while (currentSourceUrl == activeSource && !paused && !completionThresholdReached) {
            delay(15000)
            if (currentSourceUrl == activeSource && !paused && !completionThresholdReached) {
                syncProgressIfEligible()
            }
        }
    }

    LaunchedEffect(currentSourceUrl, paused, loading) {
        if (isLive) return@LaunchedEffect
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
        if (isLive || loading || error != null || watchlistPromptVisible) return@LaunchedEffect
        if (completionThresholdReached || durationSec < 60.0 || positionSec < 120.0) return@LaunchedEffect
        val thresholdReached = traktProgressPercent() >= 95.0 ||
            (durationSec - positionSec) <= 60.0
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

    LaunchedEffect(isLive, loading, error) {
        if (!isLive || loading || error != null) {
            liveHintsVisible = false
            return@LaunchedEffect
        }
        while (true) {
            liveHintsVisible = true
            delay(LiveHintVisibleMs)
            liveHintsVisible = false
            delay((LiveHintCycleMs - LiveHintVisibleMs).coerceAtLeast(0L))
        }
    }
    DisposableEffect(
        isLive,
        liveChannelRowVisible,
        liveFavouritesDrawerVisible,
        liveChannels.size,
        liveAddonFavourites.size,
        loading,
        error,
        controlsVisible,
        panel,
        playbackRequest.mediaId,
    ) {
        TvRemoteKeyRouter.onKeyUp = { keyCode ->
            when (keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (isLive && !controlsVisible && panel == null && !liveChannelRowVisible && !liveFavouritesDrawerVisible) {
                        showLiveChannelRow()
                        true
                    } else {
                        false
                    }
                }
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isLive && !controlsVisible && panel == null && !liveChannelRowVisible && !liveFavouritesDrawerVisible && liveAddonFavourites.isNotEmpty()) {
                        showLiveFavouritesDrawer()
                        true
                    } else {
                        false
                    }
                }
                AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                AndroidKeyEvent.KEYCODE_ENTER -> {
                    if (isLive && !controlsVisible && panel == null && !liveChannelRowVisible && !liveFavouritesDrawerVisible) {
                        showControls(focusPlay = true)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
        onDispose { TvRemoteKeyRouter.onKeyUp = null }
    }
    DisposableEffect(request.mediaId, request.mediaType) {
        onDispose {
            controlsHideJob?.cancel()
            liveChannelInfoHideJob?.cancel()
            liveReconnectJob?.cancel()
            pendingSeekJob?.cancel()
            queueTraktStop()
            scope.launch {
                syncProgressIfEligible()
            }
        }
    }

    // The prompt currently owed a decision — skip intro, skip recap, skip ending, or next episode.
    // Computed here, before the handlers that have to respect it, so there is exactly one answer
    // per composition rather than each call site asking again.
    val segmentAction = if (!isLive && !loading && error == null && !nextEpisodeDialogVisible && !watchlistPromptVisible) {
        activeSegmentAction()
    } else {
        null
    }

    LaunchedEffect(segmentAction?.kind, segmentAction?.segmentType) {
        segmentPromptActive = segmentAction != null
        if (segmentAction == null) return@LaunchedEffect
        // Anything already open is taken down: the prompt is the only thing to answer.
        panel = null
        hideControlsNow()
        delay(60)
        runCatching { segmentChipRequester.requestFocus() }
            .onFailure { TvDebugLogger.w("Player", "skip chip focus request skipped: ${it.message}") }
    }

    LaunchedEffect(nextEpisodeDialogVisible, nextEpisodeLoading, nextEpisodeCandidate?.streams?.size) {
        if (!nextEpisodeDialogVisible) return@LaunchedEffect
        delay(80)
        // Play Now is disabled until a stream lands, and a disabled button cannot take focus —
        // Cancel holds it in the meantime so the dialog is never focus-less.
        val requester = if (nextEpisodeCandidate?.streams.isNullOrEmpty()) {
            nextEpisodeCancelRequester
        } else {
            nextEpisodePlayRequester
        }
        runCatching { requester.requestFocus() }
            .onFailure { TvDebugLogger.w("Player", "next episode focus request skipped: ${it.message}") }
    }

    BackHandler {
        if (segmentPromptActive) {
            // Dismissing is a decision too: the segment is marked handled so the prompt does not
            // come straight back on the next progress tick.
            segmentAction?.let { markSegmentHandled(it.segmentType) }
            segmentPromptActive = false
            scope.launch { runCatching { playerRootRequester.requestFocus() } }
        } else if (liveFavouritesDrawerVisible) {
            liveFavouritesDrawerVisible = false
            scope.launch { runCatching { playerRootRequester.requestFocus() } }
        } else if (liveChannelRowVisible) {
            liveChannelRowVisible = false
            scope.launch { runCatching { playerRootRequester.requestFocus() } }
        } else if (nextEpisodeDialogVisible) {
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
            showControls(focusPlay = !isLive)
        } else if (controlsVisible) {
            hideControlsNow()
            scope.launch { runCatching { playerRootRequester.requestFocus() } }
        } else if (isLive && liveChannelHistory.isNotEmpty()) {
            val previousRequest = liveChannelHistory.last()
            liveChannelHistory = liveChannelHistory.dropLast(1)
            TvDebugLogger.i("Player", "back to previous live channel from=${playbackRequest.mediaId} to=${previousRequest.mediaId}")
            repository.savePlaybackRequest(previousRequest)
            loading = true
            error = null
            currentLabel = "Loading ${previousRequest.title ?: "previous channel"}…"
            liveChannelRowVisible = false
            liveFavouritesDrawerVisible = false
            liveReconnectAttempt = 0
            liveRefetchGeneration = 0
            streamKeyOverride = null
            streamLabelOverride = null
            activeLiveRequest = previousRequest
        } else {
            TvDebugLogger.i("Player", "back exit to streams mediaType=${request.mediaType} mediaId=${request.mediaId}")
            queueTraktStop()
            scope.launch {
                syncProgressIfEligible()
            }
            backExitPlayback()
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
    val liveCaretOffset by breathing.animateFloat(
        initialValue = 0f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(animation = tween(850), repeatMode = RepeatMode.Reverse),
        label = "live-channel-caret",
    )

    // The video surface deliberately does not take focus, so the player root holds it
    // whenever no control is focused. Without this, D-pad presses while the controls
    // are hidden would not reach any key handler at all.
    LaunchedEffect(controlsVisible, panel, loading, error, isLive, watchlistPromptVisible, nextEpisodeDialogVisible, segmentPromptActive) {
        // Taking the controls down to show a skip prompt would otherwise land focus back here and
        // pull it straight off the chip that was just raised.
        if (!controlsVisible && panel == null && error == null && !watchlistPromptVisible && !nextEpisodeDialogVisible && !segmentPromptActive) {
            delay(40)
            runCatching { playerRootRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(playerRootRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                // While a skip prompt is up the D-pad is swallowed whole — both edges, since focus
                // moves on key-down — so the only ways out are OK on the chip and Back. OK and Back
                // pass through untouched to the chip and the back handler.
                if (segmentPromptActive) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> true
                        else -> false
                    }
                }
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                if (nextEpisodeDialogVisible) return@onPreviewKeyEvent false
                if (isLive && !controlsVisible && panel == null && !loading && error == null && !watchlistPromptVisible && !liveFavouritesDrawerVisible) {
                    if (liveChannelRowVisible) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            showLiveChannelRow()
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu -> {
                            showControls(focusPlay = true)
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionRight -> {
                            if (liveAddonFavourites.isNotEmpty()) showLiveFavouritesDrawer() else showLiveChannelInfo()
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionLeft, Key.DirectionUp -> {
                            showLiveChannelInfo()
                            return@onPreviewKeyEvent true
                        }
                        Key.Back -> return@onPreviewKeyEvent false
                        else -> return@onPreviewKeyEvent false
                    }
                }
                if (!loading && panel == null && !controlsVisible && error == null && !watchlistPromptVisible &&
                    !liveFavouritesDrawerVisible && !liveChannelRowVisible
                ) {
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
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
        key(resolvedRenderSurface, activePlaybackEngine) {
            AndroidView(
            factory = { context ->
                val player = createPlayerView(context, resolvedRenderSurface, activePlaybackEngine)
                val controller = player as MpvPlayerController
                controller.apply {
                    setDecoderMode(playbackPreferences.decoderMode)
                    setHeaders(currentRequestHeaders)
                    onRemoteCenterCallback = {
                        if (segmentPromptActive) {
                            // Left for the skip chip. Reporting it handled here would consume the
                            // press without doing anything, and the prompt would never be taken.
                            false
                        } else if (isLive) {
                            showControls(focusPlay = true)
                            true
                        } else if (!controlsVisible || panel != null) {
                            showControls(focusPlay = true)
                            true
                        } else {
                            false
                        }
                    }
                    onRemoteDownCallback = {
                        if (isLive && !liveFavouritesDrawerVisible) {
                            showLiveChannelRow()
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
                        liveReconnectJob?.cancel()
                        liveReconnectJob = null
                        liveReconnectAttempt = 0
                        lastWorkingSourceUrl = currentSourceUrl
                        lastWorkingLabel = currentLabel
                        lastWorkingRequestHeaders = currentRequestHeaders
                        if (isLive) {
                            lastLiveProgressAtMs = System.currentTimeMillis()
                            lastLiveProgressPositionSec = positionSec
                            controlsVisible = false
                            liveChannelInfoVisible = false
                        } else {
                            showControls(focusPlay = true)
                        }
                        (pendingEngineResumePositionSec ?: pendingResumePositionSec)?.takeIf { it > 0.0 }?.let { resumeAt ->
                            pendingEngineResumePositionSec = null
                            pendingResumePositionSec = null
                            seekTo(resumeAt)
                        }
                    }
                    onProgressCallback = { position, duration ->
                        durationSec = duration
                        if (isLive && kotlin.math.abs(position - lastLiveProgressPositionSec) >= 0.25) {
                            lastLiveProgressPositionSec = position
                            lastLiveProgressAtMs = System.currentTimeMillis()
                        }
                        val pendingTarget = seekTargetSec
                        if (pendingTarget == null) {
                            positionSec = position
                        } else {
                            val settled = kotlin.math.abs(position - pendingTarget) <= 1.5
                            val timedOut = System.currentTimeMillis() - seekIssuedAtMs > 4000L
                            if (settled || timedOut) {
                                // Seek landed (or MPV never confirmed it) — resume
                                // following real playback progress.
                                seekTargetSec = null
                                positionSec = position
                            }
                            // Otherwise keep the UI pinned to the scrub target so the
                            // progress bar does not jump back to the pre-seek position.
                        }
                    }
                    onEndCallback = {
                        TvDebugLogger.i(
                            "Player",
                            "onEnd mediaType=${request.mediaType} mediaId=${request.mediaId} nextEpisode=${nextEpisode != null} position=$positionSec duration=$durationSec source=${currentSourceUrl ?: "none"} inWatchlist=$inWatchlist",
                        )
                        if (isLive) {
                            scheduleLiveReconnect("The feed ended")
                        } else if (nextEpisode != null && playbackPreferences.isAutoPlayNextEpisodeEnabled()) {
                            scope.launch { openNextEpisodeDialog() }
                        } else {
                            completePlaybackAndExit()
                        }
                    }
                    fun beginSourceFallback(message: String) {
                        if (sourceFallbackInProgress || isLive) return
                        sourceFallbackInProgress = true
                        scope.launch {
                            candidate?.stream?.let { failedStreamKeys = failedStreamKeys + repository.streamSelectionKey(it) }
                            var streams = candidate?.streams.orEmpty()
                            if (streams.none { repository.streamSelectionKey(it) !in failedStreamKeys }) {
                                streams = runCatching {
                                    repository.resolvePlayback(
                                        mediaType = playbackRequest.mediaType,
                                        mediaId = playbackRequest.mediaId,
                                        imdbId = playbackRequest.imdbId ?: detail?.imdbId,
                                        episode = currentEpisode,
                                        preferredStreamKey = null,
                                        forceRefresh = true,
                                        streamType = playbackRequest.streamType,
                                    ).streams
                                }.getOrDefault(streams)
                            }
                            var selected: ResolvedPlaybackCandidate? = null
                            for (stream in streams) {
                                val key = repository.streamSelectionKey(stream)
                                if (key in failedStreamKeys) continue
                                val resolved = runCatching {
                                    repository.resolveSelectedPlayback(
                                        request = playbackRequest.copy(episode = currentEpisode),
                                        stream = stream,
                                        streams = streams,
                                        forceRefresh = true,
                                    )
                                }.getOrNull()
                                if (resolved?.source != null) {
                                    selected = resolved
                                    break
                                }
                                failedStreamKeys = failedStreamKeys + key
                            }
                            if (selected?.source != null) {
                                val resumeAt = positionSec.coerceAtLeast(0.0)
                                candidate = selected
                                currentRequestHeaders = defaultPlaybackHeaders + selected.source.requestHeaders
                                currentSourceUrl = selected.source.url
                                currentLabel = selected.source.label
                                pendingEngineResumePositionSec = resumeAt.takeIf { it > 0.0 }
                                activePlaybackEngine = initialPlaybackEngine(playbackPreferences.playerEngine)
                                autoEngineFallbackUsed = false
                                audioTracks = emptyList()
                                subtitleTracks = emptyList()
                                loading = true
                                error = null
                                TvDebugLogger.w("Player", "Source failed; switching to ${selected.source.label} at ${resumeAt}s")
                            } else {
                                error = "All available sources failed. ${message.take(160)}"
                                loading = false
                                showControls(focusPlay = true)
                            }
                            sourceFallbackInProgress = false
                        }
                    }
                    onErrorCallback = { message ->
                        TvDebugLogger.w(
                            "Player",
                            "onError mediaType=${request.mediaType} mediaId=${request.mediaId} source=${currentSourceUrl ?: "none"} label=$currentLabel position=$positionSec duration=$durationSec message=$message",
                        )
                        if (shouldAutoFallbackToMpv(playbackPreferences.playerEngine, activePlaybackEngine, autoEngineFallbackUsed)) {
                            autoEngineFallbackUsed = true
                            pendingEngineResumePositionSec = positionSec.takeIf { it > 0.0 }
                            loading = true
                            error = null
                            audioTracks = emptyList()
                            subtitleTracks = emptyList()
                            TvDebugLogger.w("Player", "Media3 failed; falling back to libMPV at ${positionSec}s: $message")
                            activePlaybackEngine = ActivePlaybackEngine.MPV
                        } else if (isLive) {
                            scheduleLiveReconnect(message)
                        } else {
                            error = "Source failed. Trying another stream…"
                            loading = true
                            beginSourceFallback(message)
                        }
                    }
                    onTracksChangedCallback = { audio, subtitles, selectedAudioTrackId, selectedSubtitleTrackId ->
                        audioTracks = audio
                        subtitleTracks = subtitles
                        selectedAudioId = selectedAudioTrackId ?: -1
                        selectedSubtitleId = selectedSubtitleTrackId ?: -1
                        val currentSource = currentSourceUrl
                        val currentBootstrap = repository.bootstrap.value
                        val activeProfile = repository.activeStreamProfile(currentBootstrap)
                        if (
                            currentSource != null &&
                            audioPreferenceAppliedForSource != currentSource
                        ) {
                            audioPreferenceAppliedForSource = currentSource
                            preferredAudioTrack(
                                audioTracks = audio,
                                preferredLanguage = activeProfile?.audioLanguage?.takeIf { it.isNotBlank() }
                                    ?: currentBootstrap?.preferences?.playback?.defaultAudioLanguage
                                    ?: "en",
                            )?.let { preferredTrack ->
                                if (selectedAudioTrackId != preferredTrack.id) {
                                    setAudioTrack(preferredTrack.id)
                                }
                            }
                        }
                        if (
                            currentSource != null &&
                            playbackPreferences.autoLoadSubtitles &&
                            selectedExternalSubtitleId == null &&
                            subtitlePreferenceAppliedForSource != currentSource
                        ) {
                            subtitlePreferenceAppliedForSource = currentSource
                            preferredSubtitleTrack(
                                subtitles = subtitles,
                                preferredLanguage = activeProfile?.subtitleLanguage?.takeIf { it.isNotBlank() }
                                    ?: currentBootstrap?.preferences?.playback?.defaultSubtitleLanguage
                                    ?: "en",
                            )?.let { preferredTrack ->
                                if (selectedSubtitleTrackId != preferredTrack.id) {
                                    setSubtitleTrack(preferredTrack.id)
                                }
                            }
                        }
                    }
                    playerView = this
                }
                player
            },
            update = { view: android.view.View ->
                val controller = view as MpvPlayerController
                playerView = controller
                controller.setDecoderMode(playbackPreferences.decoderMode)
                controller.onRemoteCenterCallback = {
                    if (segmentPromptActive) {
                        false
                    } else if (isLive) {
                        showControls(focusPlay = true)
                        true
                    } else if (!controlsVisible || panel != null) {
                        showControls(focusPlay = true)
                        true
                    } else {
                        false
                    }
                }
                controller.onRemoteDownCallback = {
                    if (isLive && !liveFavouritesDrawerVisible) {
                        showLiveChannelRow()
                        true
                    } else {
                        false
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        }

        // Brightness. Sits directly over the video and under everything else, so lowering it dims
        // the picture without also dimming the controls drawn on top of it.
        val brightnessScrim = brightnessScrimAlpha(brightnessPercent)
        if (brightnessScrim > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = brightnessScrim)),
            )
        }

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
                            .width(390.dp)
                            .scale(logoScale)
                            .alpha(logoAlpha),
                        contentScale = ContentScale.Fit,
                    )
                } ?: Text(
                    text = if (isLive) currentChannelTitle else detail?.title ?: request.title ?: "Loading",
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
                                    backExitPlayback()
                                },
                                modifier = Modifier.focusRequester(errorBackRequester),
                            ) {
                                androidx.tv.material3.Text("Go Back")
                            }
                            androidx.tv.material3.OutlinedButton(
                                onClick = {
                                    TvDebugLogger.i("Player", "error overlay retry with fresh streams mediaType=${request.mediaType} mediaId=${request.mediaId}")
                                    error = null
                                    scope.launch { loadPlayback(forceRefresh = true) }
                                },
                            ) {
                                androidx.tv.material3.Text("Retry")
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
                                        currentRequestHeaders = lastWorkingRequestHeaders
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
        if (isLive && !loading && error == null) {
            LiveStatusBadge(
                isVod = isVod,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 26.dp, start = 26.dp),
            )
        }

        if (isLive && liveChannelInfoVisible && !loading && error == null) {
            LiveChannelInfoOverlay(
                channelTitle = currentChannelTitle,
                sourceName = playbackRequest.sourceAddonName ?: currentLabel,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 78.dp, start = 26.dp),
            )
        }

        if (isLive && liveHintsVisible && liveChannels.size > 1 && !liveChannelRowVisible && !loading && error == null) {
            LiveChannelDownHint(
                offsetY = liveCaretOffset,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            )
        }

        if (isLive && liveHintsVisible && liveAddonFavourites.isNotEmpty() && !liveFavouritesDrawerVisible && !loading && error == null) {
            LiveFavouritesRightHint(
                offsetX = liveCaretOffset,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp),
            )
        }

        if (isLive && liveFavouritesDrawerVisible && liveAddonFavourites.isNotEmpty() && !loading && error == null) {
            LiveFavouritesDrawer(
                channels = liveAddonFavourites,
                currentChannelId = playbackRequest.mediaId,
                listState = liveFavouriteListState,
                initialFocusRequester = liveFavouriteFirstRequester,
                onSelect = ::selectLiveChannel,
                onToggleFavourite = repository::toggleFavouriteChannel,
                cardView = liveFavouritesCardView,
                onToggleView = { liveFavouritesCardView = !liveFavouritesCardView },
                onDismiss = {
                    liveFavouritesDrawerVisible = false
                    scope.launch { runCatching { playerRootRequester.requestFocus() } }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        if (isLive && liveChannelRowVisible && liveChannels.size > 1 && !loading && error == null) {
            val currentChannelIndex = liveChannels.indexOfFirst { it.id == playbackRequest.mediaId }.coerceAtLeast(0)
            LiveChannelCarousel(
                channels = liveChannels,
                currentChannelId = playbackRequest.mediaId,
                listState = liveChannelListState,
                initialFocusIndex = currentChannelIndex,
                initialFocusRequester = liveChannelFirstRequester,
                favouriteKeys = favouriteChannelKeys,
                onSelect = ::selectLiveChannel,
                onToggleFavourite = repository::toggleFavouriteChannel,
                onDismiss = {
                    liveChannelRowVisible = false
                    scope.launch { runCatching { playerRootRequester.requestFocus() } }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

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
                    nextRequester = nextRequester,
                    watchedRequester = watchedRequester,
                    speedRequester = speedRequester,
                    brightnessRequester = brightnessRequester,
                    progressRequester = progressRequester,
                    liveProgressRequester = liveProgressRequester,
                    onInteract = ::registerInteraction,
                    onPlayPause = {
                        // tv-material fires onClick on key-up without requiring the
                        // matching key-down, so the release that confirmed a panel
                        // selection can land on the freshly focused play button and
                        // pause playback. Ignore toggles immediately after a panel closes.
                        if (System.currentTimeMillis() - panelClosedAtMs > 450L) {
                            paused = !paused
                            if (!paused) scheduleControlsHide()
                        }
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
                    isLive = isLive,
                    isVod = isVod,
                    showLiveProgress = showLiveProgress,
                    onToggleLiveProgress = {
                        showLiveProgress = !showLiveProgress
                        registerInteraction()
                    },
                )
            }
        }

        // Skip intro / recap / ending, and the hand-off into the next episode.
        segmentAction?.let { action ->
            PlayerSkipActionChip(
                label = action.label,
                bottomPadding = if (controlsVisible) 112.dp else 24.dp,
                focusRequester = segmentChipRequester,
                onClick = {
                    when (action.kind) {
                        SegmentActionKind.Skip -> {
                            val target = maxOf(action.targetTimeSec ?: positionSec, positionSec)
                            scheduleSeek(target)
                            markSegmentHandled(action.segmentType)
                            // Released before showing the controls, or the guard this same press
                            // relies on would swallow the call that puts them back.
                            segmentPromptActive = false
                            registerInteraction()
                        }
                        SegmentActionKind.NextEpisode -> {
                            segmentPromptActive = false
                            scope.launch { openNextEpisodeDialog() }
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }

        if (nextEpisodeDialogVisible && nextEpisode != null) {
            NextEpisodeDialog(
                detail = detail,
                episode = nextEpisode!!,
                streams = nextEpisodeCandidate?.streams.orEmpty(),
                loading = nextEpisodeLoading,
                countdown = nextEpisodeCountdown,
                playRequester = nextEpisodePlayRequester,
                cancelRequester = nextEpisodeCancelRequester,
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
                            text = if (isLive) "LIVE" else "${com.streamdek.tv.nativeapp.ui.formatPlaybackClock(positionSec)} / ${com.streamdek.tv.nativeapp.ui.formatPlaybackClock(durationSec)}",
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
                        externalSubtitles = externalSubtitles,
                        subtitlesLoading = subtitlesLoading,
                        selectedAudioId = selectedAudioId,
                        selectedSubtitleId = selectedSubtitleId,
                        selectedExternalSubtitleId = selectedExternalSubtitleId,
                        currentSpeed = speed,
                        currentBrightness = brightnessPercent,
                        closeRequester = panelCloseRequester,
                        firstItemRequester = panelFirstItemRequester,
                        onClose = {
                            panel = null
                            panelClosedAtMs = System.currentTimeMillis()
                            showControls(focusPlay = !isLive)
                        },
                        onInteract = { if (!isLive) registerInteraction() },
                        onSelectStream = { index ->
                            scope.launch {
                                val stream = candidate?.streams?.getOrNull(index) ?: return@launch
                                panel = null
                                panelClosedAtMs = System.currentTimeMillis()
                                loading = true
                                controlsVisible = false
                                pendingResumePositionSec = positionSec.takeIf { it > 0.0 }
                                val selected = try {
                                    repository.resolveSelectedPlayback(
                                        request = request.copy(
                                            episode = currentEpisode,
                                            selectedStreamKey = repository.streamSelectionKey(stream),
                                            selectedStream = stream,
                                        ),
                                        stream = stream,
                                        streams = candidate?.streams.orEmpty(),
                                    )
                                } catch (e: Exception) {
                                    error = "Could not load this source: ${e.message ?: "Unknown error"}"
                                    loading = false
                                    controlsVisible = true
                                    return@launch
                                }
                                if (selected.source == null) {
                                    error = if (repository.isUsenetStream(stream)) {
                                        "This usenet source could not be opened. The news server may be unreachable, or the post may be incomplete."
                                    } else {
                                        "This source could not be resolved. Please try another."
                                    }
                                    loading = false
                                    controlsVisible = true
                                    return@launch
                                }
                                candidate = selected
                                currentRequestHeaders = defaultPlaybackHeaders + selected.source.requestHeaders
                                currentSourceUrl = selected.source.url
                                currentLabel = selected.source.label ?: repository.describeStreamOption(stream)
                            }
                        },
                        onSelectAudio = {
                            audioPreferenceAppliedForSource = currentSourceUrl
                            playerView?.setAudioTrack(it)
                            panel = null
                            panelClosedAtMs = System.currentTimeMillis()
                            playerView?.setPaused(paused)
                            showControls(focusPlay = !isLive)
                        },
                        onDisableSubtitles = {
                            selectedExternalSubtitleId = null
                            externalSubtitleAppliedKey = null
                            subtitlePreferenceAppliedForSource = currentSourceUrl
                            playerView?.disableSubtitleTrack()
                            panel = null
                            panelClosedAtMs = System.currentTimeMillis()
                            playerView?.setPaused(paused)
                            showControls(focusPlay = !isLive)
                        },
                        onSelectSubtitle = {
                            selectedExternalSubtitleId = null
                            externalSubtitleAppliedKey = null
                            subtitlePreferenceAppliedForSource = currentSourceUrl
                            playerView?.setSubtitleTrack(it)
                            panel = null
                            panelClosedAtMs = System.currentTimeMillis()
                            // Track switches must never interrupt playback: re-assert the
                            // intended pause state after mpv reconfigures its track chain.
                            playerView?.setPaused(paused)
                            showControls(focusPlay = !isLive)
                        },
                        onSelectExternalSubtitle = { subtitle ->
                            scope.launch {
                                val source = currentSourceUrl ?: return@launch
                                selectedExternalSubtitleId = subtitle.id
                                subtitlePreferenceAppliedForSource = source
                                val localPath = repository.downloadSubtitleToCache(subtitle.url, context.cacheDir)
                                if (localPath == null) {
                                    error = "Could not download this subtitle."
                                    selectedExternalSubtitleId = null
                                    return@launch
                                }
                                if (currentSourceUrl != source || selectedExternalSubtitleId != subtitle.id) return@launch
                                selectedSubtitleId = -1
                                playerView?.addSubtitleFile(localPath)
                                externalSubtitleAppliedKey = "${activePlaybackEngine.name}:$source:${subtitle.id}"
                                panel = null
                                panelClosedAtMs = System.currentTimeMillis()
                                playerView?.setPaused(paused)
                                showControls(focusPlay = !isLive)
                            }
                        },
                        onSelectSpeed = {
                            speed = it
                            panel = null
                            panelClosedAtMs = System.currentTimeMillis()
                            showControls(focusPlay = !isLive)
                        },
                        onSelectBrightness = {
                            brightnessPercent = it
                            repository.savePlayerBrightnessPercent(it)
                            panel = null
                            panelClosedAtMs = System.currentTimeMillis()
                            showControls(focusPlay = !isLive)
                        },
                    )
                }
            }
        }
    }

@Composable
private fun LiveStatusBadge(
    isVod: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
            .background(Color(0xD911141B))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isVod) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = Color(0xFF60A5FA),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color(0xFFEF4444)),
            )
        }
        Text(
            text = if (isVod) "VOD" else "LIVE",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
            color = if (isVod) Color(0xFF93C5FD) else Color.White,
        )
    }
}

@Composable
private fun LiveChannelInfoOverlay(
    channelTitle: String,
    sourceName: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(Color(0xD911141B))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = channelTitle,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = sourceName,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LiveChannelDownHint(
    offsetY: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.alpha(0.78f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            text = "Channels",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = "Press down for live channels",
            tint = Color.White,
            modifier = Modifier
                .size(24.dp)
                .offset(y = offsetY.dp),
        )
    }
}

@Composable
private fun LiveFavouritesRightHint(
    offsetX: Float,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.alpha(0.82f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "Favourites",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = "Press right for favourite channels",
            tint = Color.White,
            modifier = Modifier.size(26.dp).offset(x = offsetX.dp),
        )
    }
}

@Composable
private fun LiveFavouritesDrawer(
    channels: List<MediaItem>,
    currentChannelId: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    initialFocusRequester: FocusRequester,
    onSelect: (MediaItem) -> Unit,
    onToggleFavourite: (MediaItem) -> Unit,
    cardView: Boolean,
    onToggleView: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusedIndex = channels.indexOfFirst { it.id == currentChannelId }.coerceAtLeast(0)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth(0.33f)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color(0xD9050505), Color(0xFF050505)),
                ),
            )
            .padding(start = 46.dp, end = 22.dp, top = 88.dp, bottom = 34.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(
                    text = "Favourite channels",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                    color = Color.White,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${channels.size} saved  •  Hold OK to remove",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.66f),
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Card(
                onClick = onToggleView,
                modifier = Modifier.padding(start = 10.dp).size(40.dp),
                shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                colors = CardDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black,
                ),
                border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
                glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
                scale = CardDefaults.scale(focusedScale = 1.08f),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (cardView) Icons.Filled.ViewList else Icons.Filled.GridView,
                        contentDescription = if (cardView) "Switch to text list" else "Switch to card view",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().focusGroup().onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.DirectionLeft) {
                    onDismiss()
                    true
                } else false
            },
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(if (cardView) 8.dp else 5.dp),
            contentPadding = PaddingValues(bottom = 36.dp),
        ) {
            items(channels, key = { "${it.sourceAddonId}:${it.id}" }) { item ->
                val index = channels.indexOf(item)
                val itemModifier = if (index == focusedIndex) Modifier.focusRequester(initialFocusRequester) else Modifier
                if (cardView) {
                    LiveFavouriteDrawerCard(
                        item = item,
                        selected = item.id == currentChannelId,
                        modifier = itemModifier,
                        onLongPress = { onToggleFavourite(item) },
                        onClick = { onSelect(item) },
                    )
                } else {
                    LiveFavouriteTextItem(
                        item = item,
                        selected = item.id == currentChannelId,
                        modifier = itemModifier,
                        onLongPress = { onToggleFavourite(item) },
                        onClick = { onSelect(item) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveFavouriteTextItem(
    item: MediaItem,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(44.dp).tvCardLongPress(onLongPress),
        shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
        colors = CardDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                item.title,
                color = Color.White,
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (selected) FontWeight.Black else FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) Text("  ON NOW", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveFavouriteDrawerCard(
    item: MediaItem,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(60.dp).tvCardLongPress(onLongPress).onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(containerColor = Color(0xFF181A1F), focusedContainerColor = Color(0xFF20232A)),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = 1.03f),
    ) {
        Box(Modifier.fillMaxSize().clip(AppCardShape)) {
            AsyncImage(item.backdrop ?: item.poster, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xE6000000)))))
            if (focused) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)))
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(8.dp), horizontalAlignment = Alignment.End) {
                if (selected) Text("ON NOW", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black))
                Text(item.title, color = Color.White, textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveChannelCarousel(
    channels: List<MediaItem>,
    currentChannelId: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    initialFocusIndex: Int,
    initialFocusRequester: FocusRequester,
    favouriteKeys: Set<String>,
    onSelect: (MediaItem) -> Unit,
    onToggleFavourite: (MediaItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color(0xE6000000), Color(0xFF050505)),
                ),
            )
            .padding(top = 34.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Live channels",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
            color = Color.White,
            modifier = Modifier.padding(horizontal = 36.dp),
        )
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.DirectionUp) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
            contentPadding = PaddingValues(horizontal = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(
                items = channels,
                key = { index, item -> "${item.sourceAddonId}:${item.streamType}:${item.id}:$index" },
            ) { index, item ->
                LivePlayerChannelCard(
                    item = item,
                    selected = item.id == currentChannelId,
                    favourite = "${item.sourceAddonId}:${item.id}" in favouriteKeys,
                    modifier = if (index == initialFocusIndex) {
                        Modifier.focusRequester(initialFocusRequester)
                    } else {
                        Modifier
                    },
                    onLongPress = { onToggleFavourite(item) },
                    onClick = { onSelect(item) },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LivePlayerChannelCard(
    item: MediaItem,
    selected: Boolean,
    favourite: Boolean = false,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier
            .width(240.dp)
            .height(135.dp)
            .tvCardLongPress(onLongPress)
            .onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.24f) else Color(0xFF181A1F),
            focusedContainerColor = Color(0xFF20232A),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                shape = AppCardShape,
            ),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = 1.035f),
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(AppCardShape)) {
            AsyncImage(
                model = item.backdrop ?: item.poster,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x10000000), Color(0x44000000), Color(0xEE000000)),
                        ),
                    ),
            )
            if (favourite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = "Favourite channel",
                    tint = Color(0xFFFACC15),
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(20.dp),
                )
            }
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (selected) {
                    Text(
                        text = "NOW PLAYING",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (focused) FontWeight.Black else FontWeight.Bold),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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

private fun preferredAudioTrack(
    audioTracks: List<MpvTrackInfo>,
    preferredLanguage: String,
): MpvTrackInfo? {
    val normalizedPreference = preferredLanguage.trim().lowercase()
    if (normalizedPreference.isBlank() || normalizedPreference == "off") return null
    return audioTracks.firstOrNull { track ->
        trackMatchesLanguagePreference(track, normalizedPreference)
    }
}

private fun preferredSubtitleTrack(
    subtitles: List<MpvTrackInfo>,
    preferredLanguage: String,
): MpvTrackInfo? {
    val normalizedPreference = preferredLanguage.trim().lowercase()
    if (normalizedPreference.isBlank() || normalizedPreference == "off") return null
    return subtitles.firstOrNull { track ->
        trackMatchesLanguagePreference(track, normalizedPreference)
    }
}

private fun trackMatchesLanguagePreference(track: MpvTrackInfo, preferredLanguage: String): Boolean {
    val normalizedLanguage = track.language?.trim()?.lowercase().orEmpty()
    val normalizedTitle = track.title?.trim()?.lowercase().orEmpty()
    val aliases = languageAliases(preferredLanguage)
    return aliases.any { alias ->
        normalizedLanguage == alias ||
            normalizedLanguage.startsWith("$alias-") ||
            titleMatchesLanguageAlias(normalizedTitle, alias)
    }
}

private fun languageAliases(preferredLanguage: String): Set<String> {
    return when (preferredLanguage) {
        "en", "eng", "english" -> setOf("en", "eng", "english")
        else -> setOf(preferredLanguage)
    }
}

private fun titleMatchesLanguageAlias(title: String, alias: String): Boolean {
    if (title.isBlank()) return false
    val tokenizedTitle = title.replace(Regex("[^a-z0-9]+"), " ")
    return Regex("(^| )${Regex.escape(alias)}( |$)").containsMatchIn(tokenizedTitle)
}













private fun createPlayerView(
    context: android.content.Context,
    renderSurface: String,
    engine: ActivePlaybackEngine,
): android.view.View {
    if (engine == ActivePlaybackEngine.Media3) return ExoPlaybackView(context)
    return when (renderSurface) {
        "texture" -> MPVTextureView(context)
        else -> MPVView(context)
    }
}

private fun normalizeRenderSurfacePreference(value: String?): String {
    return when (value?.trim()?.lowercase()) {
        "texture", "textureview" -> "texture"
        "surface", "surfaceview" -> "surface"
        "auto", "standard", null, "" -> "auto"
        else -> "auto"
    }
}

