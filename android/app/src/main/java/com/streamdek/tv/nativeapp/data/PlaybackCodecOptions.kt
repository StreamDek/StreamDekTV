package com.streamdek.tv.nativeapp.data

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Two decoder choices that belong to the device rather than to the account.
 *
 * Deliberately not synced with the phone or the portal. Whether a Dolby Vision stream needs mapping
 * down, and whether tunneled output helps or breaks, is a property of the silicon in front of the
 * viewer — a stick and a phone will not agree, and copying one's answer onto the other is how a
 * working player gets broken from another room. Read at the moment a player is built, so a change
 * takes effect on the next thing played rather than needing a restart.
 */
object PlaybackCodecOptions {
    private const val PREFS_NAME = "streamdek_tv_playback_codec"
    private const val DV7_HEVC_KEY = "dv7_hevc_fallback"
    private const val TUNNELED_KEY = "tunneled_playback"

    @Volatile
    var dv7HevcFallback: Boolean = false
        private set

    @Volatile
    var tunneledPlayback: Boolean = false
        private set

    /** Seeds the in-memory copy the player reads. Safe to call more than once. */
    fun initialize(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        dv7HevcFallback = prefs.getBoolean(DV7_HEVC_KEY, false)
        tunneledPlayback = prefs.getBoolean(TUNNELED_KEY, false)
    }

    fun setDv7HevcFallback(context: Context, enabled: Boolean) {
        dv7HevcFallback = enabled
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(DV7_HEVC_KEY, enabled).apply()
    }

    fun setTunneledPlayback(context: Context, enabled: Boolean) {
        tunneledPlayback = enabled
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(TUNNELED_KEY, enabled).apply()
    }
}

/**
 * Dolby Vision profile 7, and why it needs help when nothing else does.
 *
 * Media3 already copes with most Dolby Vision on a device that cannot decode it: when the display
 * does not report Dolby Vision support, `MediaCodecVideoRenderer` asks for an *alternative* codec
 * and `MediaCodecUtil.getAlternativeCodecMimeType` answers HEVC for profiles 4 and 8, AVC for 9 and
 * AV1 for 10. Profile 7 is the one profile it has no answer for.
 *
 * The failure is worse than a refusal. A television with a Dolby Vision decoder -- a Fire TV stick,
 * say -- accepts a profile 7 stream, decodes it, reports a first frame and a healthy frame rate,
 * and shows black: profile 7 is the dual-layer disc format, and the hardware is built for the
 * single-layer streaming ones. Nothing errors, so nothing falls back.
 *
 * A profile 7 stream is an HEVC Main 10 base layer with an enhancement layer and metadata carried
 * alongside it, on NAL units an ordinary HEVC decoder is required to ignore. So the base layer is
 * playable as plain HEVC -- without the Dolby Vision grade, in HDR10 or SDR as the base was
 * mastered -- and that is what this offers, when asked.
 */
@OptIn(UnstableApi::class)
internal object Dv7Hevc {
  private const val TAG = "StreamDekDv7"

  /** `dvhe.07`, as MediaCodec numbers it. */
  private const val PROFILE_DVHE_DTB = android.media.MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb

  /**
   * The HEVC codec string of the stream's base layer, or null when there is not one.
   *
   * Read out of the initialisation data rather than the `codecs` string, which is the point: a
   * remux in a Matroska container often reaches the renderer with no usable codec string at all,
   * and this inspects the actual parameter sets instead. Its being non-null is also the honest
   * test of whether mapping down is even possible.
   */
  fun hevcBaseLayerCodecs(format: Format): String? =
    runCatching { androidx.media3.container.NalUnitUtil.getH265BaseLayerCodecsString(format.initializationData) }
      .getOrNull()
      ?.takeIf { it.isNotBlank() }

  /**
   * Whether this stream should be handed to an HEVC decoder instead of a Dolby Vision one.
   *
   * Profile 7 when the codec string says so. When it says nothing -- the common case for a remux
   * in Matroska -- the presence of an HEVC base layer is taken as sufficient: a single-layer
   * profile 5 stream is the only Dolby Vision that would be wrong to map down, and one of those on
   * a device whose decoder works is not a stream anybody turns this setting on for.
   */
  fun shouldMapDown(format: Format): Boolean {
    if (!PlaybackCodecOptions.dv7HevcFallback) return false
    if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return false
    if (hevcBaseLayerCodecs(format) == null) return false
    val profile = MediaCodecUtil.getCodecProfileAndLevel(format)?.first
    return profile == null || profile == PROFILE_DVHE_DTB
  }

  /**
   * The same stream, described as the HEVC it will be decoded as.
   *
   * Rewriting the format rather than only the decoder list is what makes this work at all.
   * `MediaCodecVideoRenderer.getMediaFormat` keys off the *stream's* mime type, not the decoder's,
   * and puts the Dolby Vision profile into the `MediaFormat` whenever it sees one -- so an HEVC
   * decoder handed a Dolby Vision format is configured with a profile constant that means nothing
   * to it. Changing the format at the source leaves every later decision consistent.
   */
  fun asHevc(format: Format): Format = format.buildUpon()
    .setSampleMimeType(MimeTypes.VIDEO_H265)
    .setCodecs(hevcBaseLayerCodecs(format))
    .build()

  fun log(message: String) = Log.i(TAG, message)
}

/**
 * The stock renderers, with the video one swapped for a Dolby Vision profile 7 aware version.
 *
 * Built by asking the base class for its renderers and replacing the one entry rather than
 * reimplementing the method: everything else it sets up — the extension decoders, the codec adapter
 * factory, the secondary renderer — stays exactly as Media3 arranged it.
 */
@OptIn(UnstableApi::class)
internal class Dv7AwareRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
  override fun buildVideoRenderers(
    context: Context,
    extensionRendererMode: Int,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    allowedVideoJoiningTimeMs: Long,
    out: ArrayList<Renderer>,
  ) {
    super.buildVideoRenderers(
      context,
      extensionRendererMode,
      mediaCodecSelector,
      enableDecoderFallback,
      eventHandler,
      eventListener,
      allowedVideoJoiningTimeMs,
      out,
    )
    val index = out.indexOfFirst { it is MediaCodecVideoRenderer }
    if (index < 0) return
    out[index] = Dv7AwareVideoRenderer(
      MediaCodecVideoRenderer.Builder(context)
        .setMediaCodecSelector(mediaCodecSelector)
        .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
        .setEnableDecoderFallback(enableDecoderFallback)
        .setEventHandler(eventHandler)
        .setEventListener(eventListener)
        .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
        .setCodecAdapterFactory(codecAdapterFactory),
    )
  }
}

@OptIn(UnstableApi::class)
private class Dv7AwareVideoRenderer(builder: MediaCodecVideoRenderer.Builder) : MediaCodecVideoRenderer(builder) {
  /**
   * Where the stream stops being Dolby Vision.
   *
   * Everything downstream of here -- which decoder is chosen, what the `MediaFormat` says, how the
   * output is signalled to the display -- follows the format, so this one substitution is the whole
   * mapping. Doing it in the decoder list alone left the renderer still configuring an HEVC decoder
   * with a Dolby Vision profile, and a Dolby Vision decoder still winning the list on any device
   * that has one.
   */
  override fun onInputFormatChanged(formatHolder: androidx.media3.exoplayer.FormatHolder): androidx.media3.exoplayer.DecoderReuseEvaluation? {
    val incoming = formatHolder.format
    if (incoming != null && Dv7Hevc.shouldMapDown(incoming)) {
      val mapped = Dv7Hevc.asHevc(incoming)
      Dv7Hevc.log("Mapping Dolby Vision down to its HEVC base layer: codecs=${incoming.codecs} -> ${mapped.codecs}")
      formatHolder.format = mapped
    }
    return super.onInputFormatChanged(formatHolder)
  }

  /**
   * Reported as supported before the substitution above has happened.
   *
   * Track selection asks about the original Dolby Vision format, so the HEVC decoders have to be
   * offered for it too — first, because a device with a Dolby Vision decoder would otherwise keep
   * choosing the one that shows a black picture.
   */
  override fun getDecoderInfos(
    mediaCodecSelector: MediaCodecSelector,
    format: Format,
    requiresSecureDecoder: Boolean,
  ): List<MediaCodecInfo> {
    val decoders = super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
    if (!Dv7Hevc.shouldMapDown(format)) return decoders
    val hevcDecoders = MediaCodecUtil.getDecoderInfosSortedByFormatSupport(
      mediaCodecSelector.getDecoderInfos(MimeTypes.VIDEO_H265, requiresSecureDecoder, false),
      format,
    )
    if (hevcDecoders.isEmpty()) return decoders
    Dv7Hevc.log("Preferring ${hevcDecoders.size} HEVC decoder(s) over ${decoders.size} Dolby Vision one(s): ${hevcDecoders.joinToString { it.name }}")
    // The Dolby Vision decoders stay behind them rather than being dropped: if no HEVC decoder can
    // take the stream, trying the original is better than refusing to play at all.
    return hevcDecoders + decoders
  }
}
