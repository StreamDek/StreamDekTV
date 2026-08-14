package com.streamdek.tv.mpv

import com.streamdek.tv.nativeapp.data.PlaybackStats

interface MpvPlayerController {
    var onLoadCallback: ((duration: Double, width: Int, height: Int) -> Unit)?
    var onProgressCallback: ((position: Double, duration: Double) -> Unit)?
    var onEndCallback: (() -> Unit)?
    var onErrorCallback: ((message: String) -> Unit)?
    var onTracksChangedCallback: ((audioTracks: List<MpvTrackInfo>, subtitleTracks: List<MpvTrackInfo>, selectedAudioTrackId: Int?, selectedSubtitleTrackId: Int?) -> Unit)?
    var onRemoteCenterCallback: (() -> Boolean)?
    var onRemoteDownCallback: (() -> Boolean)?

    fun setHeaders(nextHeaders: Map<String, String>?)
    fun setSource(url: String?)
    fun reloadSource()
    fun setPaused(nextPaused: Boolean)
    fun seekTo(positionSeconds: Double)

    /**
     * Seek intended for interactive scrubbing. Snapping to the nearest keyframe makes
     * held-button scrubbing responsive; [seekTo] stays exact for resume/skip jumps.
     */
    fun seekToFast(positionSeconds: Double) = seekTo(positionSeconds)
    fun setSpeed(speed: Double)
    fun setAudioTrack(trackId: Int)
    fun setSubtitleTrack(trackId: Int)
    fun disableSubtitleTrack()
    fun addSubtitleFile(path: String)
    fun setDecoderMode(mode: String?)

    /**
     * Subtitle appearance, adjustable from the player itself.
     *
     * Both engines already implemented these; they were simply not reachable from the screen, so
     * the TV's subtitle panel could only switch tracks while the phone could also size and place
     * them. Defaulted to no-ops so a controller that genuinely cannot honour one — Media3 has no
     * concept of a subtitle delay — is not forced to declare an empty override.
     */
    fun setSubtitleFontSize(size: Int) = Unit
    fun setSubtitlePosition(position: Int) = Unit
    fun setSubtitleDelay(seconds: Double) = Unit

    /** What this engine can say about the stream it is pulling, for the info panel. */
    fun playbackStats(): PlaybackStats = PlaybackStats()
}
