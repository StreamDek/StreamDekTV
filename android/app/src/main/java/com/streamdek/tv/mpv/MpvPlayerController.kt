package com.streamdek.tv.mpv

import com.streamdek.tv.nativeapp.data.PlaybackStats
import com.streamdek.tv.nativeapp.data.ExternalSubtitleTrack

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
    /** Attach remote sidecar tracks before Media3 prepares so later selection does not reload video. */
    fun setExternalSubtitleTracks(tracks: List<ExternalSubtitleTrack>) = Unit
    /** Select a pre-attached sidecar. Returns false when this engine needs its normal file path. */
    fun selectExternalSubtitleTrack(trackId: String): Boolean = false
    fun setDecoderMode(mode: String?)

    /**
     * Whether "loaded" waits until decoded media is on screen rather than until the stream has been
     * opened. On for live channels, so a feed that opens but never plays is noticed; see MPVView.
     * Media3 already reports loaded at READY, which is that point, so it keeps the default.
     */
    fun setLoadWaitsForPlayback(waits: Boolean) = Unit

    /**
     * Whether the engine should confirm speculative caption tracks by listening for their data.
     *
     * Only live channels ask. A confirmed track stops being [MpvTrackInfo.speculative] and the
     * engine reports its tracks again, which is how the player learns, mid-broadcast, that a
     * channel has captions. Listening never shows anything the viewer has not asked to see.
     */
    fun setCaptionProbe(enabled: Boolean) = Unit

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

    /**
     * Moves the sound against the picture: positive plays it later. Both engines implement it;
     * [audioDelaySupported] says whether it can take effect on what is playing now - Media3 cannot
     * while tunneled, where the hardware keeps sound and picture together itself.
     */
    fun setAudioDelay(seconds: Double) = Unit
    fun audioDelaySupported(): Boolean = false

    /** What this engine can say about the stream it is pulling, for the info panel. */
    fun playbackStats(): PlaybackStats = PlaybackStats()

    /**
     * Lets go of everything held for the current source, keeping only where to pick it up.
     *
     * Called when the screen stops - the viewer opened another app, the television went to
     * standby. Nothing is being watched at that point, and what the engine is holding is a great
     * deal: a released Media3 player hands its buffer pool back, which on a television stick is
     * tens of megabytes of the little there is. Leaving it held is what left this app resident at
     * half a gigabyte with nothing on screen, and is why the system kept killing it for memory.
     *
     * Only Media3 implements it. mpv's pipeline lives on a surface the window takes with it, so it
     * already lets go on its own and has nothing to do here.
     */
    fun releaseWhileStopped() = Unit

    /** Reopens what [releaseWhileStopped] let go of, at the position it was let go at. */
    fun restoreAfterStop() = Unit
}
