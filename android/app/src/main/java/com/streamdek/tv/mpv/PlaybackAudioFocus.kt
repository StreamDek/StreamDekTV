package com.streamdek.tv.mpv

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Takes the television's audio away from whatever else was using it.
 *
 * Starting a video did nothing to the music app, the radio app or the screensaver already playing:
 * the two sounds simply ran together, because this client never asked the system for audio focus.
 * Requesting it is what tells Android to stop everyone else — well-behaved apps pause, and the
 * platform ducks or mutes the ones that are not.
 *
 * It also carries the obligation that comes with holding focus. A phone call, a system alert or
 * another app taking over means this playback pauses rather than talking over it, and a transient
 * interruption hands playback back when it ends. Losing focus permanently — someone starting music
 * elsewhere — is a pause the viewer has to undo themselves, because they chose the other thing.
 *
 * Media3 does all of this natively, so the ExoPlayer path asks for it there instead of here; this
 * covers mpv, which has no such notion of its own. Both engines request focus exactly the same way,
 * so which one is playing makes no difference to the rest of the television.
 */
internal class PlaybackAudioFocus(
    context: Context,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
) {
    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** Whether the pause currently in effect was ours, and so ours to undo. */
    private var pausedByFocusLoss = false
    private var holdsFocus = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            // Ducking is refused via setWillPauseWhenDucked, so this arrives as a pause too: a film
            // quietly continuing under a notification is worse than one that waits.
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                pausedByFocusLoss = true
                onPause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (pausedByFocusLoss) {
                pausedByFocusLoss = false
                onResume()
            }
        }
    }

    private val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setOnAudioFocusChangeListener(listener, Handler(Looper.getMainLooper()))
            .setWillPauseWhenDucked(true)
            .build()
    } else {
        null
    }

    /**
     * Asks for the television's audio. Safe to call for every source change.
     *
     * A refusal is not treated as a reason to stop: the viewer pressed play, and a television that
     * shows a picture with no sound is easier to understand than one that refuses to start.
     */
    fun acquire() {
        val manager = audioManager ?: return
        if (holdsFocus) return
        val result = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
                manager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        }.getOrElse {
            Log.w(TAG, "audio focus request failed", it)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        holdsFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** Gives the audio back, so whatever was playing before can resume. */
    fun release() {
        val manager = audioManager ?: return
        pausedByFocusLoss = false
        if (!holdsFocus) return
        holdsFocus = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request != null) {
                manager.abandonAudioFocusRequest(request)
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(listener)
            }
        }.onFailure { Log.w(TAG, "abandoning audio focus failed", it) }
    }

    /**
     * The viewer pausing or playing by hand.
     *
     * A manual play after we paused for a phone call means they want it back now, so the focus is
     * asked for again rather than waited for; a manual pause retires our claim to resume it later,
     * since a later focus gain must not restart something the viewer deliberately stopped.
     */
    fun onUserPlaybackChanged(paused: Boolean) {
        if (paused) {
            pausedByFocusLoss = false
        } else {
            acquire()
        }
    }

    private companion object {
        const val TAG = "StreamDekAudioFocus"
    }
}
