package com.streamdek.tv.nativeapp.data

import android.content.Context

internal const val IDLE_TIMEOUT_OFF = 0

// 5 and 10 lead both lists so the behaviour can be seen inside a sitting rather than only
// inferred from a television that eventually went dark on its own.
internal val PAUSED_SLEEP_CHOICES_MINUTES = listOf(0, 5, 10, 15, 30, 60, 90)
internal val APP_IDLE_CHOICES_MINUTES = listOf(0, 5, 10, 30, 60, 120, 240)

internal fun idleTimeoutMillis(minutes: Int): Long? =
    minutes.takeIf { it > 0 }?.times(60_000L)

internal fun idleTimeoutLabel(minutes: Int): String = when (minutes) {
    0 -> "Off"
    60 -> "1 hour"
    90 -> "90 minutes"
    120 -> "2 hours"
    240 -> "4 hours"
    else -> "$minutes minutes"
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
