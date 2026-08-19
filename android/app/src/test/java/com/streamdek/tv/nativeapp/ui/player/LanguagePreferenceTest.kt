package com.streamdek.tv.nativeapp.ui.player

import com.streamdek.tv.nativeapp.data.Languages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning the viewer's language choice into the tags a player is asked to satisfy.
 *
 * The failure these guard against is silent: a preference that produces the wrong tag list does
 * not error, it just plays the wrong audio, and looks like the television ignored a setting made
 * on the phone.
 */
class LanguagePreferenceTest {

    @Test
    fun `a language outside the old hardcoded list is no longer turned into English`() {
        // The regression this replaced: anything not among nine listed languages fell through to
        // English, so choosing Vietnamese or Tamil silently played English audio.
        assertEquals("vi", normalizePreferredAudioLanguage("vi"))
        assertEquals("ta", normalizePreferredAudioLanguage("Tamil"))
        assertEquals("pl", normalizePreferredAudioLanguage("Polish"))
        assertFalse(preferredAudioLanguageTags("vi").contains("en"))
    }

    @Test
    fun `both three-letter spellings travel with a language`() {
        // Containers written by different tools disagree: a French track is "fra" to one and
        // "fre" to another, and matching only one misses half of them.
        assertTrue(preferredAudioLanguageTags("fr").containsAll(listOf("fr", "fra", "fre")))
        assertTrue(preferredAudioLanguageTags("de").containsAll(listOf("de", "deu", "ger")))
    }

    @Test
    fun `original language asks the player for nothing`() {
        assertTrue(preferredAudioLanguageTags(Languages.ORIGINAL).isEmpty())
        assertTrue(preferredAudioLanguageTags("auto").isEmpty())
        assertTrue(preferredAudioLanguageTags("default").isEmpty())
    }

    @Test
    fun `an unset or unrecognised choice still asks for something playable`() {
        assertEquals("en", normalizePreferredAudioLanguage(null))
        assertEquals("en", normalizePreferredAudioLanguage(""))
        assertEquals("en", normalizePreferredAudioLanguage("not a language"))
    }

    @Test
    fun `a language written any of the ways it arrives resolves to the same choice`() {
        // From a container, an add-on, a file name and a person, respectively.
        listOf("pt", "por", "pt-BR", "Portuguese").forEach { spelling ->
            assertEquals(spelling, "pt", normalizePreferredAudioLanguage(spelling))
        }
    }
}
