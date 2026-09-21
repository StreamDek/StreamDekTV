package com.streamdek.tv.nativeapp.ui.player

import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.AttributeSet
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.annotation.StringRes
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
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.streamdek.tv.R
import com.streamdek.tv.mpv.MpvPlayerController
import com.streamdek.tv.mpv.MpvTrackInfo
import com.streamdek.tv.nativeapp.data.shouldUseDv7Fallback
import com.streamdek.tv.nativeapp.data.Dv7Hevc
import com.streamdek.tv.nativeapp.data.Languages
import com.streamdek.tv.nativeapp.data.PlaybackCodecOptions
import com.streamdek.tv.nativeapp.data.PlaybackStats
import com.streamdek.tv.nativeapp.data.ExternalSubtitleTrack
import com.streamdek.tv.nativeapp.data.localizedContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Media3 playback path used for CNCVerse Bridge VODs, matching Nuvio's primary engine. */
@OptIn(UnstableApi::class)
class ExoPlaybackView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0,
) : PlayerView(context, attrs, defStyleAttr), MpvPlayerController {

  /**
   * Wording for the viewer, in the interface language.
   *
   * A View is not a composition, and its own context carries the system locale rather than the one
   * chosen in Settings. Everything else this class logs stays English on purpose -- those lines are
   * for whoever reads the log, not for whoever is watching.
   */
  private fun viewerText(@StringRes id: Int): String =
    runCatching { localizedContext(context).getString(id) }.getOrElse { context.getString(id) }
  companion object {
    private const val TAG = "StreamDekExoPlayer"

    /**
     * The most media this view will hold in memory at once, by how much memory the box has.
     *
     * Media3's own ceiling is `DEFAULT_MUXED_BUFFER_SIZE`, 137.5 MiB, which is a desktop number:
     * it was never reached on ordinary streams, so it went unnoticed, and on a 4K remux it is
     * reached exactly. A Fire TV Stick has about 1.7 GB for the whole television, and this app was
     * found sitting at 544 MB when the process aborted in native code - a heap dump taken
     * afterwards had 59.5 MB of 64 KiB buffer segments in it from a single player, with the
     * allocator's target still set to the full 137.5 MiB.
     *
     * These numbers are budgets, not buffer lengths: the durations below still decide how much is
     * held, and only a stream fat enough to reach the budget first is shortened by it. At the
     * bitrates televisions actually receive - 8 to 12 Mbit/s - a 64 MiB budget holds the whole 50
     * seconds asked for and nothing changes. A 4K remux is capped at roughly ten seconds of
     * forward buffer, which is enough to ride out a hiccup and is what the box can afford.
     *
     * The trailer player on the title page was given the same treatment for the same reason, and
     * from the same signals -- see `trailerMemoryConstrained`. It is the feature player that was
     * missed, which is the one that holds the most for the longest.
     */
    private const val SmallDeviceBufferBytes = 48 * 1024 * 1024
    private const val MediumDeviceBufferBytes = 64 * 1024 * 1024
    private const val LargeDeviceBufferBytes = 96 * 1024 * 1024

    /** Which of those budgets this television gets, from its total RAM rather than its heap. */
    internal fun targetBufferBytes(context: Context): Int {
      val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return SmallDeviceBufferBytes
      if (manager.isLowRamDevice) return SmallDeviceBufferBytes
      val total = runCatching {
        ActivityManager.MemoryInfo().also(manager::getMemoryInfo).totalMem
      }.getOrNull()?.takeIf { it > 0L } ?: return SmallDeviceBufferBytes
      val gibibytes = total.toDouble() / (1024.0 * 1024.0 * 1024.0)
      return when {
        gibibytes < 2.0 -> SmallDeviceBufferBytes
        gibibytes < 3.0 -> MediumDeviceBufferBytes
        else -> LargeDeviceBufferBytes
      }
    }

    private const val DEFAULT_USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
  }

  override var onLoadCallback: ((duration: Double, width: Int, height: Int) -> Unit)? = null
  override var onProgressCallback: ((position: Double, duration: Double) -> Unit)? = null
  override var onEndCallback: (() -> Unit)? = null
  override var onErrorCallback: ((message: String) -> Unit)? = null
  var onExternalSubtitleErrorCallback: ((message: String) -> Unit)? = null
  override var onTracksChangedCallback: ((List<MpvTrackInfo>, List<MpvTrackInfo>, Int?, Int?) -> Unit)? = null

  /** Requests a DV7 compatibility handoff. True means the owner accepted the switch. */
  var onDolbyVisionProfile7Callback: (() -> Boolean)? = null
  private var dolbyVisionProfile7Reported = false
  private var selectedDv7Format: Format? = null
  var onStallChangedCallback: ((Boolean) -> Unit)? = null
  override var onRemoteCenterCallback: (() -> Boolean)? = null
  override var onRemoteDownCallback: (() -> Boolean)? = null

  // Shared by every player this view builds, so a source switch or an engine retry keeps the
  // estimate it has already gathered instead of starting from the built-in default again.
  private val bandwidthMeter = DefaultBandwidthMeter.Builder(context).build()

  private var exoPlayer: ExoPlayer? = null
  private var source: String? = null
  private var requestHeaders: Map<String, String> = emptyMap()
  private var drmLicenseType: String? = null
  private var drmClearKeys: Map<String, String> = emptyMap()
  private var pendingPaused = false
  private var pendingSpeed = 1.0
  private var preferredAudioLanguage = "en"
  private var subtitlePositionPercent = 92
  private var subtitleDelaySeconds = 0.0
  /** Read by the renderers each player here is built with; see [SyncAdjustableRenderersFactory]. */
  private val playbackOffsets = PlaybackOffsets()
  private var pendingSubtitles: List<MediaItem.SubtitleConfiguration> = emptyList()
  private val subtitleExecutor = Executors.newCachedThreadPool()
  private val subtitleRequestGeneration = AtomicLong()
  private var externalSubtitleCues: List<androidx.media3.extractor.text.CuesWithTiming>? = null
  private val audioSelections = mutableMapOf<Int, Pair<Tracks.Group, Int>>()
  private val subtitleSelections = mutableMapOf<Int, Pair<Tracks.Group, Int>>()
  private val externalSubtitleSelections = mutableMapOf<String, Pair<Tracks.Group, Int>>()

  /** See [setCaptionProbe]. */
  private var captionProbeEnabled = false

  /**
   * The viewer has asked for no subtitles, but a speculative caption track may still be decoding so
   * its data can be noticed. Cues are withheld from the screen while this is set.
   */
  private var subtitlesHidden = false

  /** Speculative caption tracks, by [captionTrackKey], that have delivered at least one cue. */
  private val confirmedCaptionKeys = HashSet<String>()
  private var lastTracks: Tracks? = null
  private val progressTicker = object : Runnable {
    override fun run() {
      exoPlayer?.let { active ->
        val durationMs = active.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        onProgressCallback?.invoke(active.currentPosition / 1000.0, durationMs / 1000.0)
      }
      postDelayed(this, 500L)
    }
  }
  private val externalSubtitleTicker = object : Runnable {
    override fun run() {
      val timeline = externalSubtitleCues ?: return
      val positionUs = delayedSubtitlePositionUs(exoPlayer?.currentPosition ?: 0L, subtitleDelaySeconds)
      val cues = timeline.asSequence()
        .filter { positionUs >= it.startTimeUs && positionUs < it.endTimeUs }
        .flatMap { it.cues.asSequence() }
        .map { it.buildUpon().setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET).setPosition(Cue.DIMEN_UNSET).build() }
        .toList()
      subtitleView?.setCues(cues)
      subtitleView?.setBottomPaddingFraction(((100 - subtitlePositionPercent) / 100f).coerceIn(0.02f, 0.50f))
      postDelayed(this, if (exoPlayer?.isPlaying == true) 100L else 250L)
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
    stoppedAtPositionMs = null
    removeCallbacks(progressTicker)
    clearExternalSubtitleOverlay()
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

  /** Only "clearkey" (hex key-id -> hex key, as published by IPTV playlists via
   * #KODIPROP:inputstream.adaptive.license_* lines) is supported. Anything else is ignored -
   * the stream will fail to decrypt exactly as it did before this existed. Takes effect on the
   * next source prepared, so it is set before [setSource]. */
  fun setDrmClearKeys(licenseType: String?, keys: Map<String, String>?) {
    drmLicenseType = licenseType
    drmClearKeys = keys.orEmpty()
  }

  /** Builds a local (offline, no license server) ClearKey session from key-id/key pairs
   * published in plaintext by the playlist itself. ExoPlayer's ClearKey implementation expects a
   * JSON Web Key Set with base64url (no padding) values, so the playlist's hex pairs are
   * re-encoded here. */
  private fun clearKeyDrmSessionManager(keys: Map<String, String>): DefaultDrmSessionManager {
    fun hexToBase64Url(hex: String): String {
      val clean = hex.trim().removePrefix("0x")
      val bytes = ByteArray(clean.length / 2) { i -> ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte() }
      return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
    val keyArray = JSONArray()
    keys.forEach { (keyId, key) ->
      keyArray.put(JSONObject().put("kty", "oct").put("kid", hexToBase64Url(keyId)).put("k", hexToBase64Url(key)))
    }
    val jwkSet = JSONObject().put("keys", keyArray).put("type", "temporary").toString()
    val drmCallback = LocalMediaDrmCallback(jwkSet.toByteArray(Charsets.UTF_8))
    return DefaultDrmSessionManager.Builder()
      .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
      .build(drmCallback)
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

  /** Where playback stood when the screen stopped; null when nothing is waiting to be reopened. */
  private var stoppedAtPositionMs: Long? = null

  override fun releaseWhileStopped() {
    val active = exoPlayer ?: return
    if (source == null) return
    stoppedAtPositionMs = active.currentPosition.coerceAtLeast(0L)
    Log.i(TAG, "screen stopped at ${stoppedAtPositionMs}ms; releasing the player and its buffers")
    // Both tickers repost themselves for as long as the view is attached, and a stopped activity
    // keeps its views attached. One of these was found still running on a backgrounded box half an
    // hour after anyone had looked at it, reading a position nobody was watching twice a second.
    // The subtitle timeline itself is left alone, so what the viewer chose is still there on the
    // way back; only the loop reading it stops.
    removeCallbacks(progressTicker)
    removeCallbacks(externalSubtitleTicker)
    // Deliberately not clearCallbacks(): this is a pause in the screen's life, not its end, and
    // the callbacks are how the restored player reports that it is back.
    releasePlayer()
  }

  override fun restoreAfterStop() {
    val resumeAt = stoppedAtPositionMs ?: return
    stoppedAtPositionMs = null
    val current = source ?: return
    // A source that was replaced while the screen was away has already built its own player, and
    // reopening the old URL over it would take the viewer back to what they had left behind.
    if (exoPlayer != null) return
    Log.i(TAG, "screen started; reopening at ${resumeAt}ms")
    post(progressTicker)
    if (externalSubtitleCues != null) post(externalSubtitleTicker)
    prepareSource(current, resumeAt)
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
    clearExternalSubtitleOverlay()
    subtitlesHidden = false
    val active = exoPlayer ?: return
    active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
    applyTrackSelection(subtitleSelections[trackId])
  }

  override fun disableSubtitleTrack() {
    clearExternalSubtitleOverlay()
    subtitlesHidden = true
    val active = exoPlayer ?: return
    // Still listening for a channel's captions: keep decoding the unconfirmed track, unseen, so the
    // CC control can appear the moment the broadcast carries some.
    if (probeUnconfirmedCaptions(active)) {
      lastTracks?.let(::dispatchTracks)
      return
    }
    active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
      .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
      .build()
    lastTracks?.let(::dispatchTracks)
  }

  override fun setCaptionProbe(enabled: Boolean) {
    if (captionProbeEnabled == enabled) return
    captionProbeEnabled = enabled
    val active = exoPlayer ?: return
    if (enabled) {
      // Nothing selected yet counts as "hidden": the probe must never switch captions on.
      if (currentTextSelection(lastTracks) == null) subtitlesHidden = true
      probeUnconfirmedCaptions(active)
    }
    lastTracks?.let(::dispatchTracks)
  }

  /**
   * Starts decoding an unconfirmed caption track with its cues withheld, when there is one and no
   * other text track is in use. True when such a probe is (now) running.
   */
  private fun probeUnconfirmedCaptions(active: ExoPlayer): Boolean {
    if (!captionProbeEnabled || !subtitlesHidden) return false
    val tracks = lastTracks ?: return false
    val candidate = tracks.groups.asSequence()
      .filter { it.type == C.TRACK_TYPE_TEXT }
      .flatMap { group -> (0 until group.length).asSequence().map { group to it } }
      .firstOrNull { (group, index) ->
        group.isTrackSupported(index) && isSpeculativeCaption(group, index)
      } ?: return false
    val (group, index) = candidate
    if (!group.isTrackSelected(index)) {
      active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
        .build()
    }
    subtitleView?.setCues(emptyList())
    return true
  }

  private fun captionTrackKey(group: Tracks.Group, index: Int): String =
    "${group.mediaTrackGroup.id}#$index"

  /**
   * An in-band CEA-608/708 track that no metadata vouches for.
   *
   * HLS names the caption renditions it really carries (CLOSED-CAPTIONS / INSTREAM-ID), and those
   * arrive with a label or a language. The placeholder Media3 adds to every transport stream has
   * neither, and nothing but decoded caption data can tell it apart from a real one.
   */
  private fun isSpeculativeCaption(group: Tracks.Group, index: Int): Boolean {
    val format = group.getTrackFormat(index)
    val inBand = format.sampleMimeType == MimeTypes.APPLICATION_CEA608 ||
      format.sampleMimeType == MimeTypes.APPLICATION_CEA708
    return inBand && format.label.isNullOrBlank() && format.language.isNullOrBlank() &&
      captionTrackKey(group, index) !in confirmedCaptionKeys
  }

  private fun currentTextSelection(tracks: Tracks?): Pair<Tracks.Group, Int>? = tracks?.groups
    ?.asSequence()
    ?.filter { it.type == C.TRACK_TYPE_TEXT }
    ?.flatMap { group -> (0 until group.length).asSequence().map { group to it } }
    ?.firstOrNull { (group, index) -> group.isTrackSelected(index) }

  override fun addSubtitleFile(path: String) {
    val generation = subtitleRequestGeneration.incrementAndGet()
    subtitleExecutor.execute {
      val parsed = runCatching { parseExternalSubtitleCues(path) }
      post {
        if (subtitleRequestGeneration.get() != generation) return@post
        parsed.onSuccess { timeline ->
          externalSubtitleCues = timeline
          exoPlayer?.let { active ->
            active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
              .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
              .clearOverridesOfType(C.TRACK_TYPE_TEXT)
              .build()
          }
          removeCallbacks(externalSubtitleTicker)
          externalSubtitleTicker.run()
          Log.i(TAG, "External subtitle ready: ${timeline.size} timed cue groups")
        }.onFailure {
          Log.w(TAG, "External subtitle parse failed", it)
          onExternalSubtitleErrorCallback?.invoke(viewerText(R.string.player_subtitle_load_failed))
        }
      }
    }
  }

  private fun clearExternalSubtitleOverlay() {
    subtitleRequestGeneration.incrementAndGet()
    externalSubtitleCues = null
    removeCallbacks(externalSubtitleTicker)
    subtitleView?.setCues(emptyList())
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

  override fun selectExternalSubtitleTrack(trackId: String): Boolean = false

  override fun setSubtitleDelay(seconds: Double) {
    subtitleDelaySeconds = seconds.coerceIn(-SUBTITLE_DELAY_LIMIT_SECONDS, SUBTITLE_DELAY_LIMIT_SECONDS)
    // Embedded tracks and captions, through the text renderer; a loaded subtitle file, through the
    // overlay ticker below. Only one of the two is ever showing.
    playbackOffsets.subtitleDelayUs = (subtitleDelaySeconds * 1_000_000.0).toLong()
    if (externalSubtitleCues != null) {
      removeCallbacks(externalSubtitleTicker)
      externalSubtitleTicker.run()
    }
  }

  override fun setAudioDelay(seconds: Double) {
    playbackOffsets.audioDelayUs = (seconds.coerceIn(-AUDIO_DELAY_LIMIT_SECONDS, AUDIO_DELAY_LIMIT_SECONDS) * 1_000_000.0).toLong()
  }

  /**
   * Not with tunneled output, where the hardware keeps picture and sound together without reading
   * the clock the delay moves. Asked of the tracks actually selected rather than of the setting:
   * tunneling that was switched on but could not be used for this stream leaves the delay working.
   */
  @Suppress("DEPRECATION")
  override fun audioDelaySupported(): Boolean = runCatching { exoPlayer?.isTunnelingEnabled != true }.getOrDefault(true)

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
    dolbyVisionProfile7Reported = false
    selectedDv7Format = null
    // A new source is a new broadcast: what the last one proved about its captions says nothing here.
    confirmedCaptionKeys.clear()
    lastTracks = null
    releasePlayer()
    val httpFactory = DefaultHttpDataSource.Factory()
      .setUserAgent(DEFAULT_USER_AGENT)
      .setAllowCrossProtocolRedirects(true)
      .setDefaultRequestProperties(requestHeaders)
    // A fresh jar per player: cookies one stream's CDN hands out never reach another channel.
    val dataSourceFactory = DefaultDataSource.Factory(context, CookieJarDataSourceFactory(httpFactory, requestHeaders))
    val renderers = SyncAdjustableRenderersFactory(context, playbackOffsets)
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
    val budget = targetBufferBytes(context)
    val loadControl = DefaultLoadControl.Builder()
      // Start quickly, retain enough forward/back buffer for stable playback and seeks.
      .setBufferDurationsMs(10_000, 50_000, 750, 2_500)
      .setBackBuffer(15_000, true)
      // Without this the allocator's ceiling is Media3's desktop default; see targetBufferBytes.
      .setTargetBufferBytes(budget)
      .build()
    Log.i(TAG, "buffer budget ${budget / (1024 * 1024)} MiB for 50s forward + 15s back")
    val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
    if (drmLicenseType.equals("clearkey", ignoreCase = true) && drmClearKeys.isNotEmpty()) {
      runCatching { clearKeyDrmSessionManager(drmClearKeys) }
        .onSuccess { manager ->
          mediaSourceFactory.setDrmSessionManagerProvider { manager }
          Log.i(TAG, "ClearKey DRM set up with ${drmClearKeys.size} key(s) for ${url.substringBefore('?')}")
        }
        .onFailure { Log.w(TAG, "Unable to set up ClearKey DRM for ${url.substringBefore('?')}, playback will likely fail to decrypt", it) }
    }
    val active = ExoPlayer.Builder(context)
      .setRenderersFactory(renderers)
      .setTrackSelector(trackSelector)
      .setLoadControl(loadControl)
      .setMediaSourceFactory(mediaSourceFactory)
      .setBandwidthMeter(bandwidthMeter)
      // Takes the television's audio away from whatever else was using it.
      //
      // Nothing here ever asked the system for audio focus, so starting a film left the music app
      // or the radio app playing straight through it -- two sounds at once, and no way to stop the
      // other one without leaving StreamDek. Declaring the attributes and handing focus to Media3
      // makes it request focus on play and give it back on stop, which is what pauses everyone
      // else; it also brings the other half of the bargain, pausing this playback for a call or a
      // system alert rather than talking over it, and resuming afterwards.
      .setAudioAttributes(
        androidx.media3.common.AudioAttributes.Builder()
          .setUsage(androidx.media3.common.C.USAGE_MEDIA)
          .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
          .build(),
        /* handleAudioFocus = */ true,
      )
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
      com.streamdek.tv.nativeapp.data.Perf.playback?.mark("player.prepare")
      Log.i(TAG, "Preparing source with Media3: ${url.substringBefore('?')}")
    }.onFailure { failure ->
      Log.e(TAG, "Media3 could not prepare this protocol", failure)
      post { onErrorCallback?.invoke(failure.localizedMessage ?: viewerText(R.string.player_protocol_unsupported)) }
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
          com.streamdek.tv.nativeapp.data.Perf.playback?.mark(
            "player.ready",
            "video=${videoSize.width}x${videoSize.height} duration=${duration}",
          )
          Log.i(TAG, "Ready duration=${duration}s video=${videoSize.width}x${videoSize.height}")
          onLoadCallback?.invoke(duration, videoSize.width, videoSize.height)
        }
        Player.STATE_ENDED -> onEndCallback?.invoke()
      }
    }

    /** The moment a picture actually exists on screen — the number "time to first frame" means. */
    override fun onRenderedFirstFrame() {
      com.streamdek.tv.nativeapp.data.Perf.playback?.mark("player.firstFrame")
    }

    override fun onPlayerError(error: PlaybackException) {
      Log.e(TAG, "Media3 playback failed", error)
      if (error.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED &&
        requestDv7Fallback(decoderFailed = true)) return
      onErrorCallback?.invoke(error.localizedMessage ?: viewerText(R.string.player_source_unplayable))
    }

    override fun onTracksChanged(tracks: Tracks) = dispatchTracks(tracks)

    override fun onCues(cueGroup: CueGroup) {
      if (externalSubtitleCues != null) return
      if (cueGroup.cues.isNotEmpty()) confirmSelectedCaptionTrack()
      if (subtitlesHidden) {
        subtitleView?.setCues(emptyList())
        return
      }
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

  private fun requestDv7Fallback(decoderFailed: Boolean): Boolean {
    if (dolbyVisionProfile7Reported) return false
    val format = selectedDv7Format ?: exoPlayer?.videoFormat?.takeIf(Dv7Hevc::isDolbyVisionProfile7) ?: return false
    if (!shouldUseDv7Fallback(
        enabled = PlaybackCodecOptions.dv7HevcFallback,
        profile7 = Dv7Hevc.isDolbyVisionProfile7(format),
        nativeSupported = if (decoderFailed) false else Dv7Hevc.supportsNativePlayback(format, display),
        decoderFailed = decoderFailed,
        protectedContent = format.drmInitData != null,
      )) return false
    // The owner guards engine retries too. Do not swallow an error if it declines the handoff.
    val switched = onDolbyVisionProfile7Callback?.invoke() == true
    dolbyVisionProfile7Reported = switched
    return switched
  }

  /** Inspect only the selected video; unknown profiles do not activate this setting. */
  private fun reportDolbyVisionProfile7(tracks: Tracks) {
    if (dolbyVisionProfile7Reported) return
    selectedDv7Format = null
    tracks.groups.forEach { group ->
      if (group.type != C.TRACK_TYPE_VIDEO) return@forEach
      for (index in 0 until group.length) {
        if (!group.isTrackSelected(index)) continue
        val format = group.getTrackFormat(index)
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) continue
        Dv7Hevc.log("Dolby Vision video track selected: " + Dv7Hevc.describe(format))
        selectedDv7Format = format.takeIf(Dv7Hevc::isDolbyVisionProfile7)
        requestDv7Fallback(decoderFailed = false)
        return
      }
    }
  }

  /**
   * The selected caption track just produced text, so it is real. Reported straight away, and - when
   * it was only being listened to - decoding stops again, since the question has been answered.
   */
  private fun confirmSelectedCaptionTrack() {
    val tracks = lastTracks ?: return
    val (group, index) = currentTextSelection(tracks) ?: return
    if (!isSpeculativeCaption(group, index)) return
    confirmedCaptionKeys += captionTrackKey(group, index)
    Log.i(TAG, "Captions confirmed in stream data: ${group.getTrackFormat(index).sampleMimeType}")
    if (subtitlesHidden) {
      exoPlayer?.let { active ->
        active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
          .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
          .build()
      }
    }
    post { lastTracks?.let(::dispatchTracks) }
  }

  private fun dispatchTracks(tracks: Tracks) {
    lastTracks = tracks
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
        val speculative = group.type == C.TRACK_TYPE_TEXT && isSpeculativeCaption(group, index)
        val info = MpvTrackInfo(
          id = id,
          type = if (group.type == C.TRACK_TYPE_AUDIO) "audio" else "sub",
          title = format.label,
          language = format.language,
          codec = format.codecs,
          // A track decoding only so its captions can be noticed is not one the viewer turned on.
          selected = group.isTrackSelected(index) && !(group.type == C.TRACK_TYPE_TEXT && subtitlesHidden),
          speculative = speculative,
        )
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
    reportDolbyVisionProfile7(tracks)
    // Tracks can arrive after playback has started - a live stream's often do - so the probe is
    // reconsidered every time they change rather than only when it was switched on.
    exoPlayer?.let(::probeUnconfirmedCaptions)
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
