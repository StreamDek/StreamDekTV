package com.streamdek.tv.nativeapp.data

import android.content.Context
import android.content.res.Resources

import com.streamdek.tv.R

internal const val IDLE_TIMEOUT_OFF = 0

// 5 and 10 lead both lists so the behaviour can be seen inside a sitting rather than only
// inferred from a television that eventually went dark on its own.
internal val PAUSED_SLEEP_CHOICES_MINUTES = listOf(0, 5, 10, 15, 30, 60, 90)
internal val APP_IDLE_CHOICES_MINUTES = listOf(0, 5, 10, 30, 60, 120, 240)

internal fun idleTimeoutMillis(minutes: Int): Long? =
    minutes.takeIf { it > 0 }?.times(60_000L)

/**
 * A timeout as a viewer reads it, in their language.
 *
 * Derived rather than enumerated: the two lists above hold seven values each and the old `when`
 * spelled five of them out, so 5, 10, 15 and 30 fell through to a branch that only happened to
 * read correctly in English. Whole hours are said in hours and everything else in minutes, which
 * covers every value either list can hold and any added later.
 */
internal fun idleTimeoutLabel(resources: Resources, minutes: Int): String = when {
    minutes <= 0 -> resources.getString(R.string.idle_timeout_off)
    minutes % 60 == 0 -> resources.getQuantityString(R.plurals.duration_hours, minutes / 60, minutes / 60)
    else -> resources.getQuantityString(R.plurals.duration_minutes, minutes, minutes)
}

/** TV-local because these values describe the room/display, not the viewing profile. */
internal class TvIdlePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "streamdek_tv_idle",
        Context.MODE_PRIVATE,
    )

    var pausedTimeoutMinutes: Int
        get() = preferences.getInt("paused_timeout_minutes", IDLE_TIMEOUT_OFF)
        set(value) {
            preferences.edit().putInt(
                "paused_timeout_minutes",
                value.takeIf { it in PAUSED_SLEEP_CHOICES_MINUTES } ?: IDLE_TIMEOUT_OFF,
            ).apply()
        }

    var appIdleTimeoutMinutes: Int
        get() = preferences.getInt("app_idle_timeout_minutes", IDLE_TIMEOUT_OFF)
        set(value) {
            preferences.edit().putInt(
                "app_idle_timeout_minutes",
                value.takeIf { it in APP_IDLE_CHOICES_MINUTES } ?: IDLE_TIMEOUT_OFF,
            ).apply()
        }
}
