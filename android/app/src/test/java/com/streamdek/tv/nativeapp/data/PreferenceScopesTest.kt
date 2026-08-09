package com.streamdek.tv.nativeapp.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferenceScopesTest {

    private fun json(raw: String) = JsonParser.parseString(raw).asJsonObject

    @Test
    fun `profile overrides win over the account value key by key`() {
        val account = json("""{"home":{"primarySyncService":"trakt","showHeroSynopsis":true}}""")
        val profile = json("""{"home":{"primarySyncService":"simkl"}}""")

        val merged = PreferenceScopes.mergeIntoAccountPreferences(account, profile)

        val home = merged.getAsJsonObject("home")
        assertEquals("simkl", home.get("primarySyncService").asString)
        // A key the profile never set keeps the account answer instead of reverting to a default.
        assertTrue(home.get("showHeroSynopsis").asBoolean)
    }

    @Test
    fun `hardware playback settings stay account scoped`() {
        val account = json("""{"playback":{"decoderMode":"hardware_plus","preferredQuality":"1080p"}}""")
        val profile = json("""{"playback":{"decoderMode":"software","preferredQuality":"2160p"}}""")

        val playback = PreferenceScopes.mergeIntoAccountPreferences(account, profile).getAsJsonObject("playback")

        assertEquals("2160p", playback.get("preferredQuality").asString)
        // The decoder describes the box, not the viewer, so a profile cannot override it.
        assertEquals("hardware_plus", playback.get("decoderMode").asString)
    }

    @Test
    fun `the mdblist key is never copied into a profile blob`() {
        val existing = json("{}")
        val changed = json("""{"detail":{"ratingsEnabled":false,"mdblistApiKey":"secret"}}""")

        val next = PreferenceScopes.applyToProfileBlob(existing, changed)!!

        val detail = next.getAsJsonObject("detail")
        assertEquals(false, detail.get("ratingsEnabled").asBoolean)
        assertNull(detail.get("mdblistApiKey"))
    }

    @Test
    fun `writing a setting preserves everything else already in the profile blob`() {
        val existing = json(
            """{"liveFavouriteChannels":{"items":[{"id":"bbc-one"}],"updatedAt":42},"addonsInitialized":true}""",
        )
        val changed = json("""{"streams":{"showSizeBadges":false}}""")

        val next = PreferenceScopes.applyToProfileBlob(existing, changed)!!

        // The backend replaces the blob outright, so anything dropped here is lost for good.
        assertEquals(42, next.getAsJsonObject("liveFavouriteChannels").get("updatedAt").asInt)
        assertTrue(next.get("addonsInitialized").asBoolean)
        assertEquals(false, next.getAsJsonObject("streams").get("showSizeBadges").asBoolean)
    }

    @Test
    fun `an account-only change produces no profile write`() {
        val changed = json("""{"app":{"theme":"cinema-blue"}}""")

        assertNull(PreferenceScopes.applyToProfileBlob(json("{}"), changed))
    }
}
