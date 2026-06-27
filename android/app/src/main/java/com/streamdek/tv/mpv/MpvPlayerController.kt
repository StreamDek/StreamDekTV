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
    fun setPaused(nextPaused: Boolean)
    fun seekTo(positionSeconds: Double)
    fun setSpeed(speed: Double)
    fun setAudioTrack(trackId: Int)
    fun setSubtitleTrack(trackId: Int)
    fun disableSubtitleTrack()
    fun setDecoderMode(mode: String?)
}
