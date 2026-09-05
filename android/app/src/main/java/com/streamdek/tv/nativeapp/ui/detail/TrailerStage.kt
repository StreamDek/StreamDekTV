package com.streamdek.tv.nativeapp.ui.detail

import android.content.Context
import android.graphics.Matrix
import android.view.TextureView
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.data.TrailerPlaybackSource
import com.streamdek.tv.nativeapp.data.TrailerResetSignal
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import com.streamdek.tv.nativeapp.data.trailerDataSourceFactory
import com.streamdek.tv.nativeapp.ui.TvMotion
import kotlinx.coroutines.delay

/**
 * Where the picture is coming from.
 *
 * Two routes to the same trailer, and the stage treats them as one: it owns the remote, the pause
 * and the exit whichever is underneath, so the difference between them stops at the surface.
 */
internal sealed interface TrailerPlayback {
    /** A file the resolver got hold of. The good case: a real video surface, no chrome. */
    data class Native(val source: TrailerPlaybackSource) : TrailerPlayback

    /** YouTube's own embed, for when the player API refused the file. */
    data class Embed(val youtubeKey: String) : TrailerPlayback
}

/**
 * A trailer playing over the whole title page.
 *
 * This is not the phone's treatment. There the trailer runs muted behind the hero while the page
 * carries on around it — a phone is held close and the page is the thing being read. A television
 * is watched from across a room, so the trailer takes the screen and the sound, and the page it
 * came from is not competing with it.
 *
 * It also owns the remote for as long as it is up. Every key but Back is swallowed here rather than
 * left to reach the page underneath: the page is still composed, and a stray press that scrolled a
 * row nobody can see would leave the viewer somewhere else entirely when the trailer ends.
 */
@Composable
internal fun TrailerStage(
    playback: TrailerPlayback,
    maxHeight: Int,
    /** False while the trailer is leaving, which stops the sound before the picture has gone. */
    active: Boolean,
    focusRequester: FocusRequester,
    onEnded: () -> Unit,
    onFailed: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var paused by remember(playback) { mutableStateOf(false) }
    // Mobile gets another native attempt before abandoning a failed trailer. TV used to make the
    // KinoCheck lead-in seek a single point of failure: a rendition that prepared from byte zero
    // but rejected the seek-driven range request dismissed the whole trailer. Keep the preferred
    // 3.5-second start, then retry this same source once from zero if it fails during startup.
    var retryNativeFromStart by remember(playback) { mutableStateOf(false) }
    // Tried at once and then retried, rather than waiting out a fixed delay. A requester whose node
    // has not been placed yet throws, and the window where that is true is the same window in which
    // the page underneath still answers the remote — so the first attempt goes in immediately and
    // the retries only exist to cover having been too early.
    LaunchedEffect(focusRequester) {
        repeat(4) { attempt ->
            if (runCatching { focusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40L * (attempt + 1))
        }
        TvDebugLogger.w("Trailer", "stage never took focus; the page below is still live")
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Under the picture rather than behind the page: a trailer that is letterboxed on a
            // 21:9 cut should show black bars, not half a title page either side of it.
            .background(Color.Black)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                when (event.key) {
                    Key.Back, Key.Escape -> {
                        onBack()
                        true
                    }
                    // OK pauses; it does not leave. It used to dismiss, and the key-up that
                    // followed then landed on the Play button the page had just taken focus back
                    // from — so a viewer who pressed OK to pause a trailer found themselves in the
                    // stream picker. Staying here is both what they meant and what stops that.
                    Key.DirectionCenter, Key.Enter, Key.Spacebar, Key.MediaPlayPause -> {
                        paused = !paused
                        true
                    }
                    Key.MediaPlay -> {
                        paused = false
                        true
                    }
                    Key.MediaPause -> {
                        paused = true
                        true
                    }
                    else -> true
                }
            }
            .focusable(),
    ) {
        when (playback) {
            is TrailerPlayback.Native -> key(retryNativeFromStart) {
                TrailerSurface(
                    url = playback.source.url,
                    audioUrl = playback.source.audioUrl,
                    requestHeaders = playback.source.requestHeaders,
                    maxHeight = playback.source.height ?: maxHeight,
                    playing = active && !paused,
                    startPositionMs = if (retryNativeFromStart) 0L else playback.source.startPositionMs,
                    onEnded = onEnded,
                    onFailed = { positionMs ->
                        if (!retryNativeFromStart && playback.source.startPositionMs > 0L && positionMs < 10_000L) {
                            retryNativeFromStart = true
                            TvDebugLogger.w(
                                "Trailer",
                                "lead-in seek failed at ${positionMs}ms; retrying native source from start",
                            )
                        } else {
                            onFailed()
                        }
                    },
                )
            }
            // Keyed on the reset token as well as the video, so clearing trailer state throws the
            // WebView away rather than leaving the embed on the very session that was refused.
            is TrailerPlayback.Embed -> key(TrailerResetSignal.current()) {
                TrailerEmbedSurface(
                    youtubeKey = playback.youtubeKey,
                    playing = active && !paused,
                    onEnded = onEnded,
                    onFailed = onFailed,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Only while paused. A permanent "press back to exit" caption over a trailer is the kind
        // of thing that stops being read by the second title and never stops being on screen; the
        // moment it earns its place is when the viewer has just stopped the picture and is asking
        // what to do next.
        androidx.compose.animation.AnimatedVisibility(
            visible = paused && active,
            enter = TvMotion.fadeInSpec(TvMotion.Quick),
            exit = TvMotion.fadeOutSpec(TvMotion.Quick),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xC7000000))
                    .padding(horizontal = 34.dp, vertical = 22.dp),
            ) {
                Text(
                    text = stringResource(R.string.trailer_paused),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                    color = Color.White,
                )
                Text(
                    text = stringResource(R.string.trailer_resume_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                )
            }
        }
    }
}

/**
 * Media3 against a TextureView, carried over from the phone's trailer player.
 *
 * A TextureView rather than a SurfaceView because this is composited with the page fading out
 * around it: a SurfaceView punches a hole through the window and cannot be cross-faded, which is
 * the whole transition this exists to serve. It costs a copy per frame, which for two minutes of
 * trailer is a fair trade.
 */
@OptIn(UnstableApi::class)
@Composable
private fun TrailerSurface(
    url: String,
    audioUrl: String?,
    requestHeaders: Map<String, String>,
    maxHeight: Int,
    playing: Boolean,
    /** Where to begin, for sources that carry a lead-in worth skipping. */
    startPositionMs: Long = 0L,
    onEnded: () -> Unit,
    onFailed: (positionMs: Long) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnEnded = rememberUpdatedState(onEnded)
    val latestOnFailed = rememberUpdatedState(onFailed)
    var attachedContainer by remember(url) { mutableStateOf<TrailerTextureContainer?>(null) }

    val player = remember(url, audioUrl, requestHeaders, maxHeight, startPositionMs) {
        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setMaxVideoSize(Int.MAX_VALUE, maxHeight.coerceAtLeast(360))
                .build()
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(12_000, 45_000, 1_500, 4_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        // googlevideo refuses an open-ended byte request on these URLs, which is what a progressive
        // source issues by default — see ChunkedGoogleVideoDataSource.
        val dataSourceFactory = trailerDataSourceFactory(context, requestHeaders)
        ExoPlayer.Builder(context).setTrackSelector(trackSelector).setLoadControl(loadControl).build().apply {
            val factory = ProgressiveMediaSource.Factory(dataSourceFactory)
            // YouTube HLS manifests come from manifest.googlevideo.com without a .m3u8 extension,
            // so detect HLS explicitly — a progressive source cannot parse them.
            val looksLikeHls = url.contains(".m3u8", ignoreCase = true) ||
                url.contains("/hls_", ignoreCase = true) ||
                url.contains("api/manifest/hls", ignoreCase = true)
            when {
                !audioUrl.isNullOrBlank() -> setMediaSource(
                    MergingMediaSource(
                        factory.createMediaSource(ExoMediaItem.fromUri(url)),
                        factory.createMediaSource(ExoMediaItem.fromUri(audioUrl)),
                    ),
                )
                looksLikeHls -> setMediaSource(
                    HlsMediaSource.Factory(dataSourceFactory).createMediaSource(ExoMediaItem.fromUri(url)),
                )
                else -> setMediaSource(factory.createMediaSource(ExoMediaItem.fromUri(url)))
            }
            repeatMode = Player.REPEAT_MODE_OFF
            // Sound on, unlike the phone's muted hero loop. This has the screen; a silent trailer
            // across a room is a video with something wrong with it.
            volume = 1f
            // Deliberately *not* seeking before prepare.
            //
            // These URLs are not read with HTTP Range headers: ChunkedGoogleVideoDataSource asks
            // googlevideo for a span through its own `&range=` query parameter, and a first request
            // that does not start at byte zero is answered 403. Seeking here therefore did not skip
            // the opening — it broke playback outright, on every trailer. The seek is applied once
            // the source is prepared instead; see the listener below.
            prepare()
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        var playbackEnded = false
        // Applied once, after the source is prepared, because a seek before that makes the first
        // chunk request start mid-file and googlevideo answers it 403. By this point the opening
        // bytes have been read, so the seek is an ordinary one and its span is accepted.
        var startApplied = startPositionMs <= 0L
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !startApplied) {
                    startApplied = true
                    player.seekTo(startPositionMs)
                }
                if (playbackState == Player.STATE_ENDED && !playbackEnded) {
                    playbackEnded = true
                    latestOnEnded.value()
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                attachedContainer?.setVideoSize(videoSize)
            }
            override fun onPlayerError(error: PlaybackException) {
                // A source that faults as it runs out has still shown the whole trailer; reporting
                // it would put the page back up and then take it away again.
                if (playbackEnded) return
                TvDebugLogger.w("Trailer", "playback failed: ${error.errorCodeName}")
                latestOnFailed.value(player.currentPosition.coerceAtLeast(0L))
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                else -> Unit
            }
        }
        player.addListener(listener)
        attachedContainer?.setVideoSize(player.videoSize)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, playing) { player.playWhenReady = playing }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            TrailerTextureContainer(viewContext).also { container ->
                attachedContainer = container
                container.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                container.attachPlayer(player)
                container.setVideoSize(player.videoSize)
            }
        },
        update = { view ->
            attachedContainer = view
            view.attachPlayer(player)
            view.setVideoSize(player.videoSize)
        },
        onRelease = { view ->
            if (attachedContainer === view) attachedContainer = null
            view.detachPlayer(player)
        },
    )
}

/**
 * Holds the texture and keeps the picture in proportion.
 *
 * Fits the whole picture inside the television without changing its aspect ratio.
 *
 * Trailers are not all 16:9. Wider cinema masters used to be centre-cropped here, which enlarged
 * faces and cut off both sides on a TV. The texture still occupies the full TV surface, but the
 * video is scaled inside it and any spare area is left black.
 */
private class TrailerTextureContainer(context: Context) : FrameLayout(context) {
    private val textureView = TextureView(context)
    private val textureTransform = Matrix()
    private var videoAspectRatio = 16f / 9f
    private var attachedPlayer: ExoPlayer? = null

    init {
        clipChildren = true
        clipToPadding = true
        isFocusable = false
        isClickable = false
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        textureView.isFocusable = false
        textureView.isClickable = false
    }

    fun attachPlayer(player: ExoPlayer) {
        if (attachedPlayer === player) return
        attachedPlayer?.clearVideoTextureView(textureView)
        attachedPlayer = player
        player.setVideoTextureView(textureView)
    }

    fun detachPlayer(player: ExoPlayer) {
        if (attachedPlayer === player) {
            player.clearVideoTextureView(textureView)
            attachedPlayer = null
        }
    }

    private fun updateScaleTransform() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || videoAspectRatio <= 0f) return
        val viewAspectRatio = w / h
        val (scaleX, scaleY) = trailerFitScale(viewAspectRatio, videoAspectRatio)
        textureTransform.reset()
        textureTransform.setScale(scaleX, scaleY, w / 2f, h / 2f)
        textureView.setTransform(textureTransform)
    }

    fun setVideoSize(videoSize: VideoSize) {
        if (videoSize.width <= 0 || videoSize.height <= 0) return
        videoAspectRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
        updateScaleTransform()
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        updateScaleTransform()
    }
}

/** Scale applied to a full-surface TextureView to aspect-fit its video without stretching. */
internal fun trailerFitScale(viewAspectRatio: Float, videoAspectRatio: Float): Pair<Float, Float> =
    if (viewAspectRatio > videoAspectRatio) {
        // The TV is wider than the video: retain the full height and pillarbox the sides.
        (videoAspectRatio / viewAspectRatio) to 1f
    } else {
        // The video is wider than the TV: retain the full width and letterbox top and bottom.
        1f to (viewAspectRatio / videoAspectRatio)
    }
