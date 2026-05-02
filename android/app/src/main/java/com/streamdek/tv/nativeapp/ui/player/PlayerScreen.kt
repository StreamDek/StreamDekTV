package com.streamdek.tv.nativeapp.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamdek.tv.mpv.MPVView
import com.streamdek.tv.mpv.MpvTrackInfo
import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.PlaybackRequest
import com.streamdek.tv.nativeapp.data.ResolvedPlaybackCandidate
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ControlsHideDelayMs = 3000L

@Composable
fun PlayerScreen(
    repository: StreamDekRepository,
    request: PlaybackRequest,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<MediaDetail?>(null) }
    var currentEpisode by remember(request) { mutableStateOf(request.episode) }
    var nextEpisode by remember { mutableStateOf<EpisodeContext?>(null) }
    var candidate by remember { mutableStateOf<ResolvedPlaybackCandidate?>(null) }
    var currentSourceUrl by remember { mutableStateOf<String?>(null) }
    var currentLabel by remember { mutableStateOf("Selecting stream") }
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
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsHideJob by remember { mutableStateOf<Job?>(null) }

    val playPauseRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val rewindRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val forwardRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val nextRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val progressRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val settingsRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    fun hideControlsNow() {
        controlsHideJob?.cancel()
        controlsHideJob = null
        controlsVisible = false
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

    fun showControls(focusPlay: Boolean = false) {
        controlsVisible = true
        scheduleControlsHide()
        if (focusPlay) {
            scope.launch {
                delay(60)
                playPauseRequester.requestFocus()
            }
        }
    }

    fun registerInteraction() {
        if (!controlsVisible) controlsVisible = true
        scheduleControlsHide()
    }

    suspend fun loadPlayback() {
        loading = true
        detail = repository.fetchDetail(request.mediaId, request.mediaType)
        if (request.mediaType == "tv" && currentEpisode == null) {
            val firstSeason = detail?.seasons?.firstOrNull()?.seasonNumber
            val season = firstSeason?.let { repository.fetchSeason(request.mediaId, it) }
            currentEpisode = season?.episodes?.firstOrNull()?.let {
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
        val resolved = repository.resolvePlayback(request.mediaType, request.mediaId, request.imdbId, currentEpisode)
        candidate = resolved
        currentSourceUrl = resolved.source?.url
        currentLabel = resolved.source?.label ?: "No playable stream found"
        val progress = repository.fetchProgress(request.mediaType, request.mediaId, currentEpisode)
        positionSec = progress?.positionSec ?: 0.0
        durationSec = progress?.durationSec ?: 0.0
        nextEpisode = resolveNextEpisode(repository, request, detail, currentEpisode)
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

    LaunchedEffect(currentSourceUrl) {
        if (positionSec > 0.0) {
            delay(1200)
            playerView?.seekTo(positionSec)
        }
    }

    LaunchedEffect(currentSourceUrl, paused) {
        while (currentSourceUrl != null) {
            delay(15000)
            repository.syncProgress(request.mediaType, request.mediaId, positionSec, durationSec, currentEpisode, detail)
        }
    }

    DisposableEffect(request.mediaId, currentEpisode, currentSourceUrl) {
        onDispose {
            controlsHideJob?.cancel()
            scope.launch {
                repository.syncProgress(request.mediaType, request.mediaId, positionSec, durationSec, currentEpisode, detail)
            }
        }
    }

    BackHandler(enabled = panel != null) {
        panel = null
        showControls()
    }

    BackHandler {
        scope.launch {
            repository.syncProgress(request.mediaType, request.mediaId, positionSec, durationSec, currentEpisode, detail)
        }
        onBack()
    }

    val breathing = rememberInfiniteTransition(label = "player-loading")
    val logoScale by breathing.animateFloat(
        initialValue = 0.98f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "logo-breathe",
    )
    val logoAlpha by breathing.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "logo-alpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                if (panel == null && !controlsVisible && event.key in setOf(
                        Key.DirectionCenter,
                        Key.Enter,
                        Key.NumPadEnter,
                        Key.DirectionLeft,
                        Key.DirectionRight,
                        Key.DirectionUp,
                        Key.DirectionDown,
                    )
                ) {
                    showControls(focusPlay = true)
                    return@onPreviewKeyEvent true
                }
                false
            },
    ) {
        AndroidView(
            factory = { context ->
                MPVView(context).apply {
                    setHeaders(mapOf("User-Agent" to "Mozilla/5.0 StreamDekTV"))
                    onLoadCallback = { _, _, _ ->
                        loading = false
                        showControls()
                        if (positionSec > 0.0) {
                            seekTo(positionSec)
                        }
                    }
                    onProgressCallback = { position, duration ->
                        positionSec = position
                        durationSec = duration
                    }
                    onEndCallback = {
                        scope.launch {
                            repository.syncProgress(request.mediaType, request.mediaId, positionSec, durationSec, currentEpisode, detail)
                        }
                        val autoplay = repository.bootstrap.value?.preferences?.playback?.autoplayNextEpisode ?: true
                        if (autoplay && nextEpisode != null) {
                            currentEpisode = nextEpisode
                        } else {
                            onBack()
                        }
                    }
                    onErrorCallback = { message ->
                        error = message
                        loading = false
                        controlsVisible = true
                    }
                    onTracksChangedCallback = { audio, subtitles, selectedAudioTrackId, selectedSubtitleTrackId ->
                        audioTracks = audio
                        subtitleTracks = subtitles
                        selectedAudioId = selectedAudioTrackId ?: -1
                        selectedSubtitleId = selectedSubtitleTrackId ?: -1
                    }
                    playerView = this
                }
            },
            update = { view ->
                playerView = view
                if (!currentSourceUrl.isNullOrBlank()) {
                    view.setSource(currentSourceUrl)
                }
                view.setPaused(paused)
                view.setSpeed(speed)
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (!loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0x68000000), Color.Transparent, Color(0xB0000000)),
                        ),
                    ),
            )
        }

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
                            colors = listOf(Color(0xE6000000), Color(0xB3000000), Color(0xF4000000)),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                detail?.titleLogo?.takeIf { it.isNotBlank() }?.let { logo ->
                    AsyncImage(
                        model = logo,
                        contentDescription = detail?.title ?: request.title,
                        modifier = Modifier
                            .width(360.dp)
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
                Text(
                    text = currentLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                    modifier = Modifier.padding(top = 18.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        PlayerOverlayVisibility(
            visible = controlsVisible || paused || panel != null || loading || error != null,
            modifier = Modifier.align(Alignment.TopStart).padding(28.dp),
        ) {
            PlayerTopInfo(
                detail = detail,
                requestTitle = request.title,
                currentEpisode = currentEpisode,
                currentLabel = currentLabel,
                error = error,
            )
        }

        PlayerOverlayVisibility(
            visible = controlsVisible || paused || panel != null || loading || error != null,
            modifier = Modifier.align(Alignment.Center),
        ) {
            PlayerCenterControls(
                paused = paused,
                hasNext = nextEpisode != null,
                playPauseRequester = playPauseRequester,
                rewindRequester = rewindRequester,
                forwardRequester = forwardRequester,
                nextRequester = nextRequester,
                progressRequester = progressRequester,
                onInteract = { registerInteraction() },
                onRewind = {
                    playerView?.seekTo((positionSec - 10.0).coerceAtLeast(0.0))
                    registerInteraction()
                },
                onPlayPause = {
                    paused = !paused
                    if (!paused) scheduleControlsHide()
                },
                onForward = {
                    playerView?.seekTo((positionSec + 10.0).coerceAtMost(durationSec.takeIf { it > 0.0 } ?: (positionSec + 10.0)))
                    registerInteraction()
                },
                onNext = {
                    nextEpisode?.let { currentEpisode = it }
                    registerInteraction()
                },
            )
        }

        PlayerOverlayVisibility(
            visible = controlsVisible || paused || panel != null || loading || error != null,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PlayerTimeline(
                    positionSec = positionSec,
                    durationSec = durationSec,
                    requester = progressRequester,
                    controlsRequester = playPauseRequester,
                    settingsRequester = settingsRequester,
                    onInteract = { registerInteraction() },
                    onSeekRelative = { delta ->
                        playerView?.seekTo(
                            (positionSec + delta).coerceAtLeast(0.0).coerceAtMost(durationSec.takeIf { it > 0.0 } ?: (positionSec + delta)),
                        )
                        registerInteraction()
                    },
                    modifier = Modifier.width(920.dp),
                )
                SettingsPanel(
                    requester = settingsRequester,
                    progressRequester = progressRequester,
                    selectedPanel = panel,
                    onInteract = { registerInteraction() },
                    onOpenPanel = {
                        panel = it
                        showControls()
                    },
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .width(920.dp),
                )
            }
        }

        panel?.let { activePanel ->
            PlayerOverlayVisibility(
                visible = true,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp),
            ) {
                PlayerOptionPanel(
                    panel = activePanel,
                    candidate = candidate,
                    audioTracks = audioTracks,
                    subtitleTracks = subtitleTracks,
                    selectedAudioId = selectedAudioId,
                    selectedSubtitleId = selectedSubtitleId,
                    currentSpeed = speed,
                    onClose = {
                        panel = null
                        showControls()
                    },
                    onInteract = { registerInteraction() },
                    onSelectStream = { index ->
                        scope.launch {
                            val stream = candidate?.streams?.getOrNull(index) ?: return@launch
                            val selected = repository.resolvePlayback(request.mediaType, request.mediaId, request.imdbId, currentEpisode, forceRefresh = true)
                            val matched = selected.streams.firstOrNull {
                                (it.infoHash != null && it.infoHash == stream.infoHash) ||
                                    (it.url != null && it.url == stream.url)
                            }
                            currentSourceUrl = if (matched != null) {
                                repository.resolvePlayback(request.mediaType, request.mediaId, request.imdbId, currentEpisode, forceRefresh = true).source?.url
                            } else {
                                selected.source?.url
                            }
                            currentLabel = stream.addonName
                            panel = null
                            loading = true
                            showControls()
                        }
                    },
                    onSelectAudio = {
                        playerView?.setAudioTrack(it)
                        panel = null
                        showControls()
                    },
                    onDisableSubtitles = {
                        playerView?.disableSubtitleTrack()
                        panel = null
                        showControls()
                    },
                    onSelectSubtitle = {
                        playerView?.setSubtitleTrack(it)
                        panel = null
                        showControls()
                    },
                    onSelectSpeed = {
                        speed = it
                        panel = null
                        showControls()
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
