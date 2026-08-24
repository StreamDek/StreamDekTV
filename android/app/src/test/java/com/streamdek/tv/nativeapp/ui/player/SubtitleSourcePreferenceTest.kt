package com.streamdek.tv.nativeapp.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleSourcePreferenceTest {
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
}
