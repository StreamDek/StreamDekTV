package com.streamdek.tv.nativeapp.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.data.AppLanguage
import com.streamdek.tv.nativeapp.data.TvLanguagePreferences
import com.streamdek.tv.nativeapp.data.localizedContext
import com.streamdek.tv.nativeapp.data.normalizeAppLanguageSelection
import com.streamdek.tv.nativeapp.data.resolveAppLanguage

// -------------------------------------------------------------------------------------------------
// The television's side of the interface-language setting. The specification in data/AppLanguage.kt
// is shared verbatim with the phone; this file is not - see the note there on why the selection
// stays on the device. Storage itself lives in data/TvLanguagePreferences.kt, because callers
// outside any composition need it too.
// -------------------------------------------------------------------------------------------------

/**
 * The selection, as snapshot state.
 *
 * Choosing a new language recomposes [ProvideAppLocale] and every string in the tree re-resolves on
 * the spot - no restart, and no screen to back out of first. That matters more here than on the
 * phone: rebuilding the activity would reconstruct every focus requester in the tree and drop the
 * remote's focus, which on a television is the difference between a setting and an ordeal.
 */
internal class TvAppLanguagePreferences(context: Context) {
    private val appContext = context.applicationContext

    /** Either [AppLanguage.SystemSelection] or a supported language tag. */
    var selection: String by mutableStateOf(TvLanguagePreferences.selection(appContext))
        private set

    fun select(value: String) {
        selection = normalizeAppLanguageSelection(value)
        TvLanguagePreferences.save(appContext, selection)
    }
}

/** So Settings can write the selection without it being threaded through every screen. */
internal val LocalTvAppLanguagePreferences = staticCompositionLocalOf<TvAppLanguagePreferences?> { null }

/**
 * The language selector's options: System Default first, then every language in its own name.
 *
 * Built from [AppLanguage.entries] rather than written out, so adding a language is the three steps
 * in `AppLanguage.kt` and this row is not one of them.
 *
 * Only the System Default label is translated. The rest are deliberately *not*: a language is listed
 * in itself in every interface language, which is what lets somebody who has put the television into
 * a language they cannot read find their way back - "English" is recognisable in a Polish list,
 * where "Angielski" would not be.
 */
@Composable
internal fun appLanguageOptions(): List<Pair<String, String>> {
    val systemDefault = stringResource(R.string.settings_language_system_default)
    return remember(systemDefault) {
        listOf(AppLanguage.SystemSelection to systemDefault) +
            AppLanguage.entries.map { it.tag to it.nativeName }
    }
}

/**
 * The second line under each option.
 *
 * System Default says which language it currently resolves to, so choosing it is not a guess; the
 * named languages need no gloss beyond their own name.
 */
@Composable
internal fun appLanguageOptionDescriptions(): Map<String, String> {
    val resolved = resolveAppLanguage(AppLanguage.SystemSelection)
    val description = stringResource(R.string.settings_language_system_default_description)
    return remember(resolved, description) {
        mapOf(AppLanguage.SystemSelection to "$description (${resolved.nativeName})")
    }
}

/**
 * Wraps [context] so resources resolve in the selected interface language.
 *
 * This covers everything that is not the Compose tree: the window the activity opens with, and
 * anything built while no composition exists. The composition itself is handled by
 * [ProvideAppLocale], which can change language without rebuilding the activity; this one is fixed
 * for the life of the process, which is why it must not be the only mechanism.
 *
 * The work itself is `data/TvLanguagePreferences.kt`, because the data layer needs the same thing
 * for code that produces viewer-facing text with no composition to read it from, and one
 * implementation of "a context in the chosen language" is better than two that can drift.
 */
fun localizedAppContext(context: Context): Context = localizedContext(context)
