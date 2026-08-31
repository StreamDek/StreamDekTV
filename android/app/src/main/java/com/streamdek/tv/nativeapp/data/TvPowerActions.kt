package com.streamdek.tv.nativeapp.data

import android.content.Context
import android.os.IBinder
import android.os.SystemClock

/**
 * The two things an idle timer would like to do to a television, and an honest answer about
 * whether this app is allowed to do them.
 *
 * Neither the screensaver nor sleep is an app-level capability on Android TV: `IDreamManager.dream`
 * is behind `WRITE_DREAM_STATE` and `PowerManager.goToSleep` behind `DEVICE_POWER`, and both of
 * those are signature permissions held by the platform. A set built by a manufacturer who signs
 * this app, or a rooted/dev box where the permission has been granted, will answer them; a retail
 * Fire TV will not.
 *
 * So each call is attempted and reports whether it actually happened, and the callers have a real
 * fallback for the false case rather than pretending the television went dark. That is the whole
 * point of returning Boolean here: an idle timer that silently did nothing is the bug this
 * replaces.
 */
internal object TvPowerActions {

    /**
     * Asks the system to start its screensaver now.
     *
     * The polite version of what happens anyway: leaving a lit page up with nothing playing lets
     * the set's own idle timer start the daydream in its own time. This only brings that forward.
     */
    fun startScreensaver(): Boolean = runCatching {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "dreams") as? IBinder ?: return false
        val manager = Class.forName("android.service.dreams.IDreamManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder) ?: return false
        manager.javaClass.getMethod("dream").invoke(manager)
        true
    }.getOrElse { error ->
        TvDebugLogger.w("TvPower", "screensaver unavailable: ${error.message}")
        false
    }

    /**
     * Asks the system to put the display to sleep now.
     *
     * Distinct from [startScreensaver]: sleep is the set going dark, the screensaver is the set
     * showing something else. The app-idle timer wants the former and settles for standing down.
     */
    fun sleepDevice(context: Context): Boolean = runCatching {
        val power = context.getSystemService(Context.POWER_SERVICE) ?: return false
        power.javaClass
            .getMethod("goToSleep", Long::class.javaPrimitiveType)
            .invoke(power, SystemClock.uptimeMillis())
        true
    }.getOrElse { error ->
        TvDebugLogger.w("TvPower", "device sleep unavailable: ${error.message}")
        false
    }
}
