package com.streamdek.tv.nativeapp.ui.player

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.streamdek.tv.mpv.MpvPlayerController
import com.streamdek.tv.mpv.MpvTrackInfo
import com.streamdek.tv.nativeapp.data.Dv7AwareRenderersFactory
import com.streamdek.tv.nativeapp.data.Languages
import com.streamdek.tv.nativeapp.data.PlaybackCodecOptions
import com.streamdek.tv.nativeapp.data.PlaybackStats
import com.streamdek.tv.nativeapp.data.ExternalSubtitleTrack

/** Media3 playback path used for CNCVerse Bridge VODs, matching Nuvio's primary engine. */
@OptIn(UnstableApi::class)
class ExoPlaybackView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0,
) : PlayerView(context, attrs, defStyleAttr), MpvPlayerController {
  companion object {
    private const val TAG = "StreamDekExoPlayer"
    private const val DEFAULT_USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
  }

  override var onLoadCallback: ((duration: Double, width: Int, height: Int) -> Unit)? = null
  override var onProgressCallback: ((position: Double, duration: Double) -> Unit)? = null
  override var onEndCallback: (() -> Unit)? = null
  override var onErrorCallback: ((message: String) -> Unit)? = null
  override var onTracksChangedCallback: ((List<MpvTrackInfo>, List<MpvTrackInfo>, Int?, Int?) -> Unit)? = null
  var onStallChangedCallback: ((Boolean) -> Unit)? = null
  override var onRemoteCenterCallback: (() -> Boolean)? = null
  override var onRemoteDownCallback: (() -> Boolean)? = null

  // Shared by every player this view builds, so a source switch or an engine retry keeps the
  // estimate it has already gathered instead of starting from the built-in default again.
  private val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()

  private var exoPlayer: ExoPlayer? = null
  private var source: String? = null
  private var requestHeaders: Map<String, String> = emptyMap()
  private var pendingPaused = false
  private var pendingSpeed = 1.0
  private var preferredAudioLanguage = "en"
  private var subtitlePositionPercent = 92
  private var pendingSubtitles: List<MediaItem.SubtitleConfiguration> = emptyList()
  private val audioSelections = mutableMapOf<Int, Pair<Tracks.Group, Int>>()
  private val subtitleSelections = mutableMapOf<Int, Pair<Tracks.Group, Int>>()
  private val externalSubtitleSelections = mutableMapOf<String, Pair<Tracks.Group, Int>>()
  private val progressTicker = object : Runnable {
    override fun run() {
      exoPlayer?.let { active ->
        val durationMs = active.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        onProgressCallback?.invoke(active.currentPosition / 1000.0, durationMs / 1000.0)
      }
      postDelayed(this, 500L)
    }
  }

  init {
    useController = false
    setShutterBackgroundColor(Color.BLACK)
    keepScreenOn = true
    subtitleView?.setApplyEmbeddedStyles(false)
    subtitleView?.setApplyEmbeddedFontSizes(false)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    post(progressTicker)
    source?.let(::prepareSource)
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_UP) {
      when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_DOWN -> if (onRemoteDownCallback?.invoke() == true) return true
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER -> if (onRemoteCenterCallback?.invoke() == true) return true
      }
    }
    return super.dispatchKeyEvent(event)
  }
  override fun onDetachedFromWindow() {
    removeCallbacks(progressTicker)
    releasePlayer()
    clearCallbacks()
    super.onDetachedFromWindow()
  }

  override fun setHeaders(headers: Map<String, String>?) {
    requestHeaders = headers.orEmpty().mapNotNull { (key, value) ->
      key.trim().takeIf { it.isNotBlank() && !it.equals("Range", true) }
        ?.let { cleanKey -> value.trim().takeIf(String::isNotBlank)?.let { cleanKey to it } }
    }.toMap()
  }

  override fun setSource(url: String?) {
    val next = url?.trim().orEmpty()
    if (next.isBlank() || next == source) return
    source = next
    if (isAttachedToWindow) prepareSource(next)
  }

  override fun reloadSource() {
    val current = source ?: return
    prepareSource(current, exoPlayer?.currentPosition ?: 0L)
  }

  override fun setPaused(paused: Boolean) {
    pendingPaused = paused
    keepScreenOn = !paused
    exoPlayer?.playWhenReady = !paused
  }

  override fun seekTo(positionSeconds: Double) {
    exoPlayer?.seekTo((positionSeconds * 1000.0).toLong().coerceAtLeast(0L))
  }

  override fun setSpeed(speed: Double) {
    pendingSpeed = speed
    exoPlayer?.setPlaybackSpeed(speed.toFloat())
  }

  fun setPreferredAudioLanguage(language: String?) {
    preferredAudioLanguage = normalizePreferredAudioLanguage(language)
    val tags = preferredAudioLanguageTags(preferredAudioLanguage)
    exoPlayer?.let { active ->
      if (tags.isNotEmpty()) {
        active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
          .setPreferredAudioLanguages(*tags.toTypedArray())
          .build()
      }
    }
  }

  fun setResizeMode(mode: String?) {
    resizeMode = when (mode) {
      "cover" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
      "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
      else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
  }

  override fun setDecoderMode(mode: String?) = Unit
  fun setRenderSurface(mode: String?) = Unit

  override fun setAudioTrack(trackId: Int) = applyTrackSelection(audioSelections[trackId])

  override fun setSubtitleTrack(trackId: Int) {
    val active = exoPlayer ?: return
    active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
    applyTrackSelection(subtitleSelections[trackId])
  }

  override fun disableSubtitleTrack() {
    val active = exoPlayer ?: return
    active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
      .build()
  }

  override fun addSubtitleFile(path: String) {
    val current = source ?: return
    pendingSubtitles = listOf(MediaItem.SubtitleConfiguration.Builder(Uri.parse(path))
      .setId("streamdek-external:file")
      .setMimeType(subtitleMimeType(path))
      .setLanguage("en")
      .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
      .build())
    prepareSource(current, exoPlayer?.currentPosition ?: 0L)
  }

  override fun setExternalSubtitleTracks(tracks: List<ExternalSubtitleTrack>) {
    // Called before setSource. Merely replacing these configurations never touches the active
    // player, which is what makes every subsequent selection a track override rather than a media
    // reload.
    pendingSubtitles = tracks.distinctBy { it.id to it.url }.map { track ->
      MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
        .setId("streamdek-external:${track.id}")
        .setMimeType(subtitleMimeType(track.url))
        .setLanguage(track.language)
        .setLabel(track.label)
        .build()
    }
  }

  override fun selectExternalSubtitleTrack(trackId: String): Boolean {
    val selection = externalSubtitleSelections[trackId] ?: return false
    val active = exoPlayer ?: return false
    active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
      .setOverrideForType(TrackSelectionOverride(selection.first.mediaTrackGroup, selection.second))
      .build()
    return true
  }

  // Media3 has no subtitle-delay control: the renderer honours the timestamps in the track and
  // there is nowhere to shift them. Declared so the panel can offer the adjustment on mpv without
  // having to ask which engine is playing; on this one it does nothing.
  override fun setSubtitleDelay(seconds: Double) = Unit

  override fun setSubtitleFontSize(size: Int) {
    subtitleView?.setApplyEmbeddedStyles(false)
    subtitleView?.setApplyEmbeddedFontSizes(false)
    subtitleView?.setFractionalTextSize((size.coerceIn(28, 84) / 55f) * 0.0533f)
  }

  fun setSubtitleColor(color: String) {
    val parsed = runCatching { Color.parseColor(color.take(7)) }.getOrDefault(Color.WHITE)
    subtitleView?.setStyle(CaptionStyleCompat(parsed, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
  }

  override fun setSubtitlePosition(position: Int) {
    subtitlePositionPercent = position.coerceIn(0, 100)
    subtitleView?.setBottomPaddingFraction(((100 - subtitlePositionPercent) / 100f).coerceIn(0.02f, 0.50f))
  }

  /**
   * A snapshot of what Media3 is pulling, for the player's info panel.
   *
   * The transfer rate is the shared bandwidth meter's estimate rather than a byte count of our own:
   * it already smooths across the chunked requests an adaptive source makes, and a raw count would
   * read as zero for the whole gap between one chunk and the next.
   */
  override fun playbackStats(): PlaybackStats {
    val active = exoPlayer ?: return PlaybackStats()
    val videoFormat = active.videoFormat
    val audioFormat = active.audioFormat
    val estimateBps = bandwidthMeter.bitrateEstimate.takeIf { it > 0L }?.toDouble()
    val bufferedAhead = (active.bufferedPosition - active.currentPosition)
      .takeIf { it > 0L && active.bufferedPosition != C.TIME_UNSET }
      ?.div(1000.0)
    return PlaybackStats(
      bytesPerSecond = estimateBps?.div(8.0),
      videoBitrateBps = videoFormat?.bitrate?.takeIf { it != Format.NO_VALUE }?.toDouble(),
      width = active.videoSize.width,
      height = active.videoSize.height,
      videoCodec = videoFormat?.codecs ?: videoFormat?.sampleMimeType?.substringAfter('/'),
      audioCodec = audioFormat?.codecs ?: audioFormat?.sampleMimeType?.substringAfter('/'),
      audioChannels = audioFormat?.channelCount?.takeIf { it != Format.NO_VALUE },
      frameRate = videoFormat?.frameRate?.takeIf { it > 0f && it != Format.NO_VALUE.toFloat() }?.toDouble(),
      bufferedSeconds = bufferedAhead,
    )
  }

  private fun prepareSource(url: String, startPositionMs: Long = 0L) {
    releasePlayer()
    val httpFactory = DefaultHttpDataSource.Factory()
      .setUserAgent(DEFAULT_USER_AGENT)
      .setAllowCrossProtocolRedirects(true)
      .setDefaultRequestProperties(requestHeaders)
    val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
    // Dolby Vision profile 7 is mapped down to its HEVC base layer here when the viewer has
    // asked for it -- see PlaybackCodecOptions. The factory is the same one otherwise.
    val renderers = Dv7AwareRenderersFactory(context)
      .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
      .setEnableDecoderFallback(true)
      // Queue codec work off the playback thread on API 24+ TVs.
      .forceEnableMediaCodecAsynchronousQueueing()
    // Tunneled output hands decoding and display to the hardware as one pipeline, which is what
    // holds audio and video in step on a television box. Off by default because the devices that
    // do not implement it properly fail loudly -- a black picture with the sound still running.
    val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
      if (PlaybackCodecOptions.tunneledPlayback) {
        setParameters(buildUponParameters().setTunnelingEnabled(true))
      }
    }
    val loadControl = DefaultLoadControl.Builder()
      // Start quickly, retain enough forward/back buffer for stable playback and seeks.
      .setBufferDurationsMs(10_000, 50_000, 750, 2_500)
      .setBackBuffer(15_000, true)
      .build()
    val active = ExoPlayer.Builder(context)
      .setRenderersFactory(renderers)
      .setTrackSelector(trackSelector)
      .setLoadControl(loadControl)
      .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
      .setBandwidthMeter(bandwidthMeter)
      .build()
    exoPlayer = active
    player = active
    active.addListener(listener)
    preferredAudioLanguageTags(preferredAudioLanguage).takeIf(List<String>::isNotEmpty)?.let { tags ->
      active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
        .setPreferredAudioLanguages(*tags.toTypedArray())
        .build()
    }
    val item = MediaItem.Builder()
      .setUri(url)
      .apply { inferMimeType(url)?.let(::setMimeType) }
      .apply { if (pendingSubtitles.isNotEmpty()) setSubtitleConfigurations(pendingSubtitles) }
      .build()
    runCatching {
      active.setMediaItem(item, startPositionMs.coerceAtLeast(0L))
      active.setPlaybackSpeed(pendingSpeed.toFloat())
      active.playWhenReady = !pendingPaused
      active.prepare()
    }.onSuccess {
      Log.i(TAG, "Preparing source with Media3: ${url.substringBefore('?')}")
    }.onFailure { failure ->
      Log.e(TAG, "Media3 could not prepare this protocol", failure)
      post { onErrorCallback?.invoke(failure.localizedMessage ?: "This source protocol is not supported.") }
    }
  }

  private val listener = object : Player.Listener {
    override fun onPlaybackStateChanged(state: Int) {
      onStallChangedCallback?.invoke(state == Player.STATE_BUFFERING)
      when (state) {
        Player.STATE_READY -> {
          val active = exoPlayer ?: return
          val duration = active.duration.takeIf { it > 0 && it != C.TIME_UNSET }?.div(1000.0) ?: 0.0
          val videoSize = active.videoSize
          Log.i(TAG, "Ready duration=${duration}s video=${videoSize.width}x${videoSize.height}")
          onLoadCallback?.invoke(duration, videoSize.width, videoSize.height)
        }
        Player.STATE_ENDED -> onEndCallback?.invoke()
      }
    }

    override fun onPlayerError(error: PlaybackException) {
      Log.e(TAG, "Media3 playback failed", error)
      onErrorCallback?.invoke(error.localizedMessage ?: "This source could not be played.")
    }

    override fun onTracksChanged(tracks: Tracks) = dispatchTracks(tracks)

    override fun onCues(cueGroup: CueGroup) {
      val userPositionedCues = cueGroup.cues.map { cue ->
        cue.buildUpon()
          .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
          .setPosition(Cue.DIMEN_UNSET)
          .build()
      }
      subtitleView?.setCues(userPositionedCues)
      subtitleView?.setBottomPaddingFraction(((100 - subtitlePositionPercent) / 100f).coerceIn(0.02f, 0.50f))
    }
  }

  private fun dispatchTracks(tracks: Tracks) {
    audioSelections.clear()
    subtitleSelections.clear()
    externalSubtitleSelections.clear()
    val audio = mutableListOf<MpvTrackInfo>()
    val subtitles = mutableListOf<MpvTrackInfo>()
    var nextId = 1
    tracks.groups.forEach { group ->
      for (index in 0 until group.length) {
        if (!group.isTrackSupported(index)) continue
        val format = group.getTrackFormat(index)
        val id = nextId++
        val info = MpvTrackInfo(id, if (group.type == C.TRACK_TYPE_AUDIO) "audio" else "sub", format.label, format.language, format.codecs, group.isTrackSelected(index))
        when (group.type) {
          C.TRACK_TYPE_AUDIO -> { audio += info; audioSelections[id] = group to index }
          C.TRACK_TYPE_TEXT -> {
            subtitles += info
            subtitleSelections[id] = group to index
            val formatId = format.id.orEmpty()
            if (formatId.contains("streamdek-external:")) {
              externalSubtitleSelections[formatId.substringAfter("streamdek-external:")] = group to index
            }
          }
        }
      }
    }
    onTracksChangedCallback?.invoke(audio, subtitles, audio.firstOrNull { it.selected }?.id, subtitles.firstOrNull { it.selected }?.id)
  }

  private fun applyTrackSelection(selection: Pair<Tracks.Group, Int>?) {
    val (group, index) = selection ?: return
    val active = exoPlayer ?: return
    active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
      .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
      .build()
  }

  private fun releasePlayer() {
    player = null
    exoPlayer?.removeListener(listener)
    exoPlayer?.release()
    exoPlayer = null
  }

  private fun clearCallbacks() {
    onLoadCallback = null
    onProgressCallback = null
    onEndCallback = null
    onErrorCallback = null
    onTracksChangedCallback = null
    onRemoteCenterCallback = null
    onRemoteDownCallback = null
    onStallChangedCallback = null
  }

  private fun inferMimeType(url: String): String? = when (url.substringBefore('?').substringAfterLast('.').lowercase()) {
    "m3u8" -> MimeTypes.APPLICATION_M3U8
    "mpd" -> MimeTypes.APPLICATION_MPD
    "mkv" -> MimeTypes.VIDEO_MATROSKA
    "mp4", "m4v" -> MimeTypes.VIDEO_MP4
    "webm" -> MimeTypes.VIDEO_WEBM
    else -> null
  }

  private fun subtitleMimeType(path: String): String = when (path.substringBefore('?').substringAfterLast('.').lowercase()) {
    "vtt" -> MimeTypes.TEXT_VTT
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "ttml", "xml" -> MimeTypes.APPLICATION_TTML
    else -> MimeTypes.APPLICATION_SUBRIP
  }
}

/**
 * The stored form of an audio-language choice.
 *
 * Delegates to [Languages], which knows every ISO language rather than the nine that used to be
 * listed here. That mattered beyond tidiness: anything outside the nine fell through to English, so
 * a viewer who chose Vietnamese got English audio and no indication why.
 */
internal fun normalizePreferredAudioLanguage(value: String?): String =
  when (
    val normalized = Languages.normalize(
      value?.trim()?.lowercase().let { if (it == "default" || it == "auto") Languages.ORIGINAL else it },
    )
  ) {
    Languages.ORIGINAL -> Languages.ORIGINAL
    Languages.NONE, "" -> "en"
    else -> normalized
  }

/** Track tags for one audio-language choice; empty means "leave the release alone". */
internal fun preferredAudioLanguageTags(value: String?): List<String> =
  when (val normalized = normalizePreferredAudioLanguage(value)) {
    Languages.ORIGINAL -> emptyList()
    else -> Languages.tags(normalized)
  }
