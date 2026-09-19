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

    @Test
    fun `this television's trailer settings win over the shared ones`() {
        val preferences = json(
            """{"detail":{"heroTrailerDelaySeconds":3,"heroTrailerAutoplay":true,"ratingsEnabled":true},
               "platforms":{"tv":{"heroTrailerDelaySeconds":0},"mobile":{"heroTrailerAutoplay":false}}}""",
        )

        val detail = PlatformPreferences.applyToPreferences(preferences).getAsJsonObject("detail")

        assertEquals(0, detail.get("heroTrailerDelaySeconds").asInt)
        // Unset here, so the shared value stands; the phone's own choice never leaks across.
        assertTrue(detail.get("heroTrailerAutoplay").asBoolean)
        assertTrue(detail.get("ratingsEnabled").asBoolean)
    }

    @Test
    fun `without a platform section the shared values are used unchanged`() {
        val preferences = json("""{"detail":{"heroTrailerResolution":1080}}""")

        val detail = PlatformPreferences.applyToPreferences(preferences).getAsJsonObject("detail")

        assertEquals(1080, detail.get("heroTrailerResolution").asInt)
    }

    @Test
    fun `trailer settings are written to this television's section only`() {
        val payload = PlatformPreferences.splitSectionUpdate(
            "detail",
            mapOf("heroTrailerAutoplay" to false, "heroTrailerResolution" to 720, "ratingsEnabled" to true),
        )

        assertEquals(mapOf("ratingsEnabled" to true), payload["detail"])
        assertEquals(
            mapOf("tv" to mapOf("heroTrailerAutoplay" to false, "heroTrailerResolution" to 720)),
            payload["platforms"],
        )
    }

    @Test
    fun `device settings the account holds are applied and the rest are uploaded`() {
        val platforms = json("""{"tv":{"animationSpeed":"cinematic","appIdleTimeoutMinutes":"30"},"mobile":{"appLanguage":"fr"}}""")
        val local = mapOf<String, Any>("animationSpeed" to "standard", "appLanguage" to "system", "appIdleTimeoutMinutes" to 0)

        val plan = PlatformPreferences.reconcileDevice(platforms, local)

        assertEquals("cinematic", plan.apply.getValue("animationSpeed").asString)
        // The portal sends select values as strings; the device reads them as numbers.
        assertEquals(30, plan.apply.getValue("appIdleTimeoutMinutes").asInt)
        // The phone's language is the phone's: this television uploads its own instead.
        assertEquals(mapOf<String, Any>("appLanguage" to "system"), plan.upload)
    }

    @Test
    fun `an account with no television section learns everything from the device`() {
        val local = mapOf<String, Any>("animationSpeed" to "fast", "sleepWhenPausedMinutes" to 15)

        val plan = PlatformPreferences.reconcileDevice(null, local)

        assertTrue(plan.apply.isEmpty())
        assertEquals(local, plan.upload)
    }

    @Test
    fun `the platform section never reaches a profile blob`() {
        val changed = json("""{"platforms":{"tv":{"heroTrailerAutoplay":false}}}""")

        assertNull(PreferenceScopes.applyToProfileBlob(json("{}"), changed))
    }
}
