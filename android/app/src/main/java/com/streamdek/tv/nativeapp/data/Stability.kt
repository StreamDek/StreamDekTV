package com.streamdek.tv.nativeapp.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Crash and freeze reporting for the TV app.
 *
 * Nothing useful can be sent from inside a crash. By the time an uncaught exception reaches the
 * default handler the process is being torn down: a network call started there will not finish,
 * and attempting one only delays the death the viewer is already watching. So nothing is sent at
 * the time. What happens instead:
 *
 *  - [install] puts a handler in front of the existing one that writes a small record to
 *    preferences -- exception class, bounded cause frames, timestamp and build -- and then hands
 *    straight on to whatever handler was already there. It changes no behaviour: the app still
 *    dies exactly as it would have.
 *  - [reportPending] runs on the next launch, sends that record, and clears it.
 *
 * Freezes come from Android's own record of why previous processes ended, which costs nothing to
 * read and cannot itself cause the stall it reports. That record only exists from Android 11, so
 * freezes under-report on older boxes -- which matters more here than on phones, because the
 * cheapest sticks are also the oldest. The admin console states this rather than letting a quiet
 * chart imply health.
 *
 * This matters more on a TV than on a phone. A stick has less memory and a slower CPU, the viewer
 * has no task switcher to notice a restart with, and an app that dies mid-episode simply looks
 * like the stream stopped.
 */
object Stability {

    private const val PREFS = "streamdek_tv_stability"
    private const val KEY_EXCEPTION = "pending_exception"
    private const val KEY_FRAME = "pending_frame"
    private const val KEY_DIAGNOSTICS = "pending_diagnostics"
    private const val KEY_AT = "pending_at"
    private const val KEY_VERSION = "pending_version"
    private const val KEY_LAST_EXIT_AT = "last_exit_reported_at"

    /** How many system exit records to look at. More than a handful is history, not an incident. */
    private const val MAX_EXIT_RECORDS = 10

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var installed = false

    /**
     * Starts recording crashes. Call as early as possible -- a crash before this runs is lost.
     *
     * Idempotent, and deliberately chains rather than replaces: swallowing the handler that was
     * already there would change what the viewer sees when the app dies, to collect a statistic.
     */
    fun install(context: Context, appVersion: String?) {
        if (installed) return
        installed = true

        runCatching {
            val applicationContext = context.applicationContext
            val previous = Thread.getDefaultUncaughtExceptionHandler()

            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching { record(applicationContext, error, appVersion) }
                previous?.uncaughtException(thread, error)
            }
        }
    }

    /**
     * Writes the crash to preferences, synchronously.
     *
     * `commit()` rather than `apply()`, which is the one place in the app where that is right:
     * `apply()` writes on a background thread, and this process has moments to live.
     */
    private fun record(context: Context, error: Throwable, appVersion: String?) {
        val root = rootCause(error)
        // Extra diagnostics must never prevent the original minimal crash record being saved.
        val diagnostics = runCatching {
            com.google.gson.Gson().toJson(crashDiagnostics(error) + mapOf(
                "crashedVersionCode" to com.streamdek.tv.BuildConfig.VERSION_CODE,
                "androidSdk" to Build.VERSION.SDK_INT,
            ))
        }.getOrNull()

        prefs(context)
            .edit()
            .putString(KEY_EXCEPTION, root.javaClass.name.take(180))
            .putString(KEY_FRAME, ownFrame(root))
            .putString(KEY_DIAGNOSTICS, diagnostics)
            .putString(KEY_AT, Instant.now().toString())
            .putString(KEY_VERSION, appVersion)
            .commit()
    }

    /**
     * Sends anything recorded on a previous run.
     *
     * Call after [Telemetry.configure]: events queued before the emitter has a client are dropped,
     * so reporting earlier would lose exactly the crash this exists to report.
     */
    fun reportPending(context: Context) {
        val applicationContext = context.applicationContext

        scope.launch {
            runCatching { reportRecordedCrash(applicationContext) }
            runCatching { reportSystemExits(applicationContext) }
        }
    }

    private fun reportRecordedCrash(context: Context) {
        val prefs = prefs(context)
        val exception = prefs.getString(KEY_EXCEPTION, null) ?: return

        Telemetry.appCrash(
            exceptionClass = exception,
            topFrame = prefs.getString(KEY_FRAME, null),
            occurredAtIso = prefs.getString(KEY_AT, null),
            crashedAppVersion = prefs.getString(KEY_VERSION, null),
            diagnostics = runCatching {
                com.google.gson.Gson().fromJson<Map<String, Any>>(
                    prefs.getString(KEY_DIAGNOSTICS, null),
                    object : com.google.gson.reflect.TypeToken<Map<String, Any>>() {}.type,
                )
            }.getOrNull(),
        )

        // Cleared whether or not the send succeeds. The emitter owns it by now, and keeping it
        // would re-report the same crash on every launch until the box next reached the backend --
        // turning one crash into a week of them.
        prefs.edit()
            .remove(KEY_EXCEPTION)
            .remove(KEY_FRAME)
            .remove(KEY_DIAGNOSTICS)
            .remove(KEY_AT)
            .remove(KEY_VERSION)
            .apply()
    }

    /**
     * Freezes, and any crash the handler above missed, from Android's own record.
     *
     * A watermark is kept so a record is reported once. Without it every launch would re-report
     * the same handful of exits, and the crash count would rise with how often the app is opened.
     */
    private fun reportSystemExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val prefs = prefs(context)
        val watermark = prefs.getLong(KEY_LAST_EXIT_AT, 0L)

        val records = runCatching {
            manager.getHistoricalProcessExitReasons(null, 0, MAX_EXIT_RECORDS)
        }.getOrNull() ?: return

        var newest = watermark

        for (record in records) {
            if (record.timestamp <= watermark) continue
            if (record.timestamp > newest) newest = record.timestamp

            val at = Instant.ofEpochMilli(record.timestamp).toString()

            when (record.reason) {
                ApplicationExitInfo.REASON_ANR ->
                    Telemetry.appNotResponding(
                        occurredAtIso = at,
                        crashedAppVersion = null,
                        description = record.description,
                    )

                // Native crashes only. A JVM crash is already reported by the handler above with a
                // real exception name, and reporting it here too would double every count.
                ApplicationExitInfo.REASON_CRASH_NATIVE ->
                    Telemetry.appCrash(
                        exceptionClass = "native_crash",
                        topFrame = record.description?.take(180),
                        occurredAtIso = at,
                        crashedAppVersion = null,
                        diagnostics = mapOf(
                            "exitStatus" to record.status,
                            "exitImportance" to record.importance,
                            "pssKb" to record.pss,
                            "rssKb" to record.rss,
                        ),
                    )

                // Everything else -- the viewer leaving, the system reclaiming memory, an ordinary
                // exit -- is not a fault and is deliberately not reported. Counting them would make
                // the crash rate a measure of how often the app is closed, which on a TV box that
                // kills background apps aggressively would be enormous and meaningless.
                else -> Unit
            }
        }

        if (newest > watermark) {
            prefs.edit().putLong(KEY_LAST_EXIT_AT, newest).apply()
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The exception that actually failed, rather than whatever wrapped it on the way up. */
    private fun rootCause(error: Throwable): Throwable {
        var current = error
        var depth = 0
        while (current.cause != null && current.cause !== current && depth < 10) {
            current = current.cause!!
            depth += 1
        }
        return current
    }

    /**
     * The actual throwing frame, including framework and obfuscated classes. Filtering by our
     * package loses release frames and can incorrectly group everything at Activity dispatch.
     */
    private fun ownFrame(error: Throwable): String? = runCatching {
        error.stackTrace
            .firstOrNull()
            ?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
            ?.take(180)
    }.getOrNull()
}
