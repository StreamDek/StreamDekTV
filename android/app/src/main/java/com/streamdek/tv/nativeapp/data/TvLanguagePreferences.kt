package com.streamdek.tv.nativeapp.data

import android.content.Context
import android.content.res.Configuration

/**
 * Where this television keeps its interface-language selection.
 *
 * Deliberately *not* an `AppPreferences` field, which is part of the account bootstrap and would
 * therefore travel to every device in the household: changing the language on a phone must not
 * change the language on the television across the room. It follows [TvIdlePreferences], the app's
 * existing pattern for settings that describe the box in the room rather than the person using it.
 * See `AppLanguage.kt` for the whole argument.
 *
 * Plain functions rather than the Compose-aware holder in the UI layer, because two callers need
 * this before or outside any composition: `localizedAppContext`, which runs in `attachBaseContext`,
 * and [StreamDekApi], which builds an `Accept-Language` header on a background thread.
 */
internal object TvLanguagePreferences {
    private const val FILE = "streamdek_tv_language"
    private const val KEY = "app_language"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Either [AppLanguage.SystemSelection] or a supported language tag. */
    fun selection(context: Context): String =
        normalizeAppLanguageSelection(preferences(context).getString(KEY, null))

    fun save(context: Context, value: String) {
        preferences(context).edit().putString(KEY, normalizeAppLanguageSelection(value)).apply()
    }
}

/** The interface language this television is actually drawn in, resolved from what is stored. */
internal fun savedAppLanguage(context: Context): AppLanguage =
    resolveAppLanguage(TvLanguagePreferences.selection(context))

/**
 * A context whose resources resolve in the selected interface language.
 *
 * For the handful of places that have to produce viewer-facing text with no composition to read it
 * from - a repository labelling a stream it synthesised, an update manager setting a status line.
 * Those must not use a plain application context: it is never locale-wrapped, so it would answer in
 * the *device* language and quietly ignore the viewer's choice.
 *
 * Resolved per call rather than cached, so it is right after the language changes. Anything drawn by
 * Compose should be reading `stringResource` instead, which re-resolves on recomposition; this is
 * the fallback for code that cannot.
 *
 * Only locale and layout direction are overridden. Copying the full current configuration would pin
 * every other field to whatever it was when this was first called.
 */
internal fun localizedContext(context: Context): Context {
    val locale = savedAppLanguage(context).locale
    val configuration = Configuration()
    configuration.setLocale(locale)
    configuration.setLayoutDirection(locale)
    return context.createConfigurationContext(configuration)
}
