package com.streamdek.tv.mpv

interface MpvPlayerController {
    var onLoadCallback: ((duration: Double, width: Int, height: Int) -> Unit)?
    var onProgressCallback: ((position: Double, duration: Double) -> Unit)?
    var onEndCallback: (() -> Unit)?
    var onErrorCallback: ((message: String) -> Unit)?
    var onTracksChangedCallback: ((audioTracks: List<MpvTrackInfo>, subtitleTracks: List<MpvTrackInfo>, selectedAudioTrackId: Int?, selectedSubtitleTrackId: Int?) -> Unit)?
    var onRemoteCenterCallback: (() -> Boolean)?

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
}
