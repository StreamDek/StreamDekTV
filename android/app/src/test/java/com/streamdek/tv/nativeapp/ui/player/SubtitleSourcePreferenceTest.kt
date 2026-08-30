package com.streamdek.tv.nativeapp.ui.player

import com.streamdek.tv.nativeapp.data.externalSubtitleOrigin
import com.streamdek.tv.nativeapp.data.subtitleOriginVisible
import com.streamdek.tv.nativeapp.data.subtitleSourceAllowsOrigin
import com.streamdek.tv.nativeapp.data.preferredSubtitleLanguageAllowed
import com.streamdek.tv.nativeapp.data.ExternalSubtitleOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleSourcePreferenceTest {
    @Test
    fun `subtitle delay shifts only the cue timeline`() {
        assertEquals(85_000_000L, delayedSubtitlePositionUs(100_000L, 15.0))
        assertEquals(115_000_000L, delayedSubtitlePositionUs(100_000L, -15.0))
        assertEquals(100_000_000L, delayedSubtitlePositionUs(100_000L, 0.0))
        assertEquals(85_000_000L, delayedSubtitlePositionUs(100_000L, 30.0))
        assertEquals(-15.0..15.0, SubtitleDelayRange)
    }
    @Test
    fun `all sources is the safe default for missing or unknown values`() {
        assertEquals("All", normalizeSubtitleDefaultSource(null))
        assertEquals("All", normalizeSubtitleDefaultSource("unexpected"))
        assertEquals("All", normalizeSubtitleDefaultSource("All"))
    }

    @Test
    fun `legacy source spellings normalize to player values`() {
        assertEquals("BuiltIn", normalizeSubtitleDefaultSource("built-in"))
        assertEquals("Addons", normalizeSubtitleDefaultSource("add-ons"))
    }

    @Test
    fun `all sources includes embedded and addon subtitles`() {
        assertEquals(true, subtitleSourceIncludesBuiltIn("All"))
        assertEquals(true, subtitleSourceIncludesAddons("All"))
        assertEquals(false, subtitleSourceIncludesAddons("BuiltIn"))
        assertEquals(false, subtitleSourceIncludesBuiltIn("Addons"))
    }

    @Test
    fun `network subtitle results are grouped by their real source`() {
        val openSubtitles = externalSubtitleOrigin("opensubtitles")
        val addon = externalSubtitleOrigin("addon:flix-streams")

        assertEquals(true, subtitleOriginVisible("BuiltIn", openSubtitles))
        assertEquals(false, subtitleOriginVisible("Addons", openSubtitles))
        assertEquals(true, subtitleOriginVisible("Addons", addon))
        assertEquals(false, subtitleOriginVisible("BuiltIn", addon))
        assertEquals(true, subtitleOriginVisible("All", openSubtitles))
        assertEquals(true, subtitleOriginVisible("All", addon))
    }

    @Test
    fun `strict language filter permits only primary and selected secondary`() {
        assertEquals(true, preferredSubtitleLanguageAllowed("English SDH", "en", "none", true))
        assertEquals(false, preferredSubtitleLanguageAllowed("Hindi", "en", "none", true))
        assertEquals(true, preferredSubtitleLanguageAllowed("eng", "English", "Hindi", true))
        assertEquals(true, preferredSubtitleLanguageAllowed("hin", "English", "Hindi", true))
        assertEquals(false, preferredSubtitleLanguageAllowed("French", "English", "Hindi", true))
    }

    @Test
    fun `source preference controls which origins are searched`() {
        assertEquals(true, subtitleSourceAllowsOrigin("BuiltIn", ExternalSubtitleOrigin.BuiltIn))
        assertEquals(false, subtitleSourceAllowsOrigin("BuiltIn", ExternalSubtitleOrigin.Addon))
        assertEquals(true, subtitleSourceAllowsOrigin("Addons", ExternalSubtitleOrigin.Addon))
        assertEquals(false, subtitleSourceAllowsOrigin("Addons", ExternalSubtitleOrigin.BuiltIn))
        assertEquals(true, subtitleSourceAllowsOrigin("All", ExternalSubtitleOrigin.BuiltIn))
        assertEquals(true, subtitleSourceAllowsOrigin("All", ExternalSubtitleOrigin.Addon))
    }
}
