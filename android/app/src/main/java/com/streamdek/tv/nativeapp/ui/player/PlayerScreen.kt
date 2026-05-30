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
    val view = LocalView.current

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

    val errorBackRequester = remember { FocusRequester() }
    val errorSourcesRequester = remember { FocusRequester() }
    val playRequester = remember { FocusRequester() }
    val subtitlesRequester = remember { FocusRequester() }
    val audioRequester = remember { FocusRequester() }
    val sourcesRequester = remember { FocusRequester() }
    val rewindRequester = remember { FocusRequester() }
    val nextRequester = remember { FocusRequester() }
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
            playRequester.requestFocus()
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

    suspend fun loadPlayback() {
        pendingSeekJob?.cancel()
        pendingSeekJob = null
        loading = true
        controlsVisible = false
        pauseInfoVisible = false
        detail = repository.fetchDetail(request.mediaId, request.mediaType)
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
            request.imdbId,
            currentEpisode,
            preferredStreamKey = request.selectedStreamKey,
        )
        candidate = resolved
        currentSourceUrl = resolved.source?.url
        currentLabel = request.selectedStreamLabel ?: resolved.source?.label ?: "No playable stream found"
        positionSec = pendingResumePositionSec ?: 0.0
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

    LaunchedEffect(paused, panel, loading, error) {
        playerView?.setPaused(paused)
        if (paused && panel == null && !loading && error == null) {
            pauseInfoVisible = false
            delay(2500)
            if (paused && panel == null && !loading && error == null) {
                controlsVisible = false
                pauseInfoVisible = true
            }
        } else {
            pauseInfoVisible = false
        }
    }

    LaunchedEffect(currentSourceUrl, paused) {
        while (currentSourceUrl != null) {
            delay(15000)
            repository.syncProgress(request.mediaType, request.mediaId, positionSec, durationSec, currentEpisode, detail)
        }
    }

    LaunchedEffect(panel) {
        if (panel != null) {
            delay(80)
            panelFirstItemRequester.requestFocus()
        }
    }

    LaunchedEffect(error) {
        if (error != null) {
            delay(80)
            try { errorBackRequester.requestFocus() } catch (_: Exception) { }
        }
    }

    DisposableEffect(request.mediaId, currentEpisode, currentSourceUrl) {
        onDispose {
            controlsHideJob?.cancel()
            pendingSeekJob?.cancel()
            scope.launch {
                repository.syncProgress(request.mediaType, request.mediaId, positionSec, durationSec, currentEpisode, detail)
            }
        }
    }

    BackHandler {
        if (panel != null) {
            panel = null
            showControls(focusPlay = true)
        } else {
            scope.launch {
                repository.syncProgress(request.mediaType, request.mediaId, positionSec, durationSec, currentEpisode, detail)
            }
            onBack()
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
                if (!loading && panel == null && !controlsVisible && error == null) {
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
                                    scope.launch {
                                        repository.syncProgress(request.mediaType, request.mediaId, positionSec, durationSec, currentEpisode, detail)
                                    }
                                    onBack()
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
