package com.streamdek.tv.nativeapp.ui.player

import com.streamdek.tv.mpv.MpvTrackInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineTrackRestoreTest {
    private fun track(id: Int, language: String, title: String) =
        MpvTrackInfo(id, "audio", title, language, "aac", false)

    @Test fun restoresMetadataAcrossEngineIdsAndLanguageAliases() {
        val wanted = track(0, "eng", "Commentary")
        val tracks = listOf(track(0, "fr", "Main"), track(3, "en", "Main"), track(8, "en", "Commentary"))
        assertEquals(8, matchingEngineTrack(wanted, tracks)?.id)
    }

    @Test fun acceptsUniqueLanguageWhenEngineOmitsTitle() {
        assertEquals(4, matchingEngineTrack(track(0, "en", "Main"), listOf(track(4, "eng", "")))?.id)
    }

    @Test fun doesNotGuessBetweenAmbiguousTracksOrReuseNumericId() {
        assertNull(matchingEngineTrack(track(0, "en", "Commentary"), listOf(track(0, "en", "A"), track(1, "en", "B"))))
        assertNull(matchingEngineTrack(track(0, "en", "Main"), listOf(track(0, "fr", "Main"))))
    }
}
