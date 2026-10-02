package com.streamdek.tv.nativeapp.data

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TvSettingsJournalTest {
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
    private val owner = TvSettingsOwner("account-a", "profile-a")
    private var disk: String? = null
    private fun store() = TvSettingsJournal({ disk }, { disk = it; true })
    private fun value(store: TvSettingsJournal, path: String, selected: TvSettingsOwner = owner) =
        path.split('.').fold(store.snapshot(selected)!!.getAsJsonObject("preferences") as com.google.gson.JsonElement) { node, key -> node.asJsonObject.get(key) }

    @Test fun `rapid changes survive process recreation and stale cloud`() {
        val store = store()
        repeat(50) { store.enqueue(owner, json("""{"playback":{"liveProgressBarEnabled":${it % 2 == 0}}}""")) }
        val restarted = store()
        restarted.acceptRemote(owner, json("{}"), json("""{"playback":{"liveProgressBarEnabled":true}}"""), null)
        assertFalse(value(restarted, "playback.liveProgressBarEnabled").asBoolean)
        assertFalse(restarted.pending(owner).empty)
    }

    @Test fun `older upload acknowledgement preserves later edit`() {
        val store = store()
        store.enqueue(owner, json("""{"home":{"showHeroSynopsis":false}}"""))
        val sent = store.pending(owner)
        store.enqueue(owner, json("""{"home":{"showHeroSynopsis":true}}"""))
        store.acknowledge(owner, sent)
        assertFalse(store.pending(owner).empty)
        assertTrue(value(store(), "home.showHeroSynopsis").asBoolean)
        store.acknowledge(owner, store.pending(owner))
        assertTrue(store.pending(owner).empty)
        assertTrue(value(store(), "home.showHeroSynopsis").asBoolean)
    }

    @Test fun `accounts profiles and guest remain isolated across login logout`() {
        val store = store()
        val otherProfile = owner.copy(profile = "profile-b")
        val otherAccount = TvSettingsOwner("account-b", "profile-a")
        val guest = TvSettingsOwner("guest", null)
        listOf(owner, otherProfile, otherAccount, guest).forEachIndexed { index, selected ->
            store.enqueue(selected, json("""{"home":{"heroTrailerDelaySeconds":$index}}"""))
        }
        listOf(owner, otherProfile, otherAccount, guest).forEachIndexed { index, selected ->
            assertEquals(index, value(store(), "home.heroTrailerDelaySeconds", selected).asInt)
        }
    }

    @Test fun `sparse changes preserve false zero empty values and unrelated fields`() {
        val store = store()
        store.acceptRemote(owner, json("""{"playback":{"decoderMode":"software"},"home":{"showHeroSynopsis":true}}"""), json("{}"), null)
        store.enqueue(owner, json("""{"home":{"showHeroSynopsis":false,"heroTrailerDelaySeconds":0,"homeCatalogRows":[]}}"""))
        val sent = store.pending(owner)
        assertEquals(0, sent.account.size())
        assertEquals(3, sent.profile.size())
        assertFalse(value(store, "home.showHeroSynopsis").asBoolean)
        assertEquals(0, value(store, "home.heroTrailerDelaySeconds").asInt)
        assertEquals(0, value(store, "home.homeCatalogRows").asJsonArray.size())
        assertEquals("software", value(store, "playback.decoderMode").asString)
    }

    @Test fun `fresh install restores cloud profile while hardware stays account scoped`() {
        val store = store()
        store.acceptRemote(owner, json("""{"playback":{"decoderMode":"hardware_plus"}}"""), json("""{"playback":{"decoderMode":"software","liveProgressBarEnabled":false}}"""), null)
        assertEquals("hardware_plus", value(store(), "playback.decoderMode").asString)
        assertFalse(value(store(), "playback.liveProgressBarEnabled").asBoolean)
        assertTrue(store.pending(owner).empty)
    }

    @Test fun `disk failure is returned and diagnosed`() {
        val events = mutableListOf<String>()
        val store = TvSettingsJournal({ null }, { false }, events::add)
        assertFalse(store.enqueue(owner, json("""{"home":{"showHeroSynopsis":false}}""")))
        assertEquals(listOf("disk_write_failed"), events)
    }

    @Test fun `platform settings and foreign profile data survive partial upgrade`() {
        val store = store()
        store.acceptRemote(owner, json("""{"platforms":{"tv":{"app":{"appLanguage":"fr"}}}}"""), json("""{"home":{"showHeroSynopsis":false},"liveFavouriteChannels":{"items":["bbc"]}}"""), null)
        store.enqueue(owner, json("""{"streams":{"showSizeBadges":false}}"""))
        assertFalse(value(store(), "home.showHeroSynopsis").asBoolean)
        assertEquals("bbc", store().snapshot(owner)!!.getAsJsonObject("profilePreferences").getAsJsonObject("liveFavouriteChannels").getAsJsonArray("items")[0].asString)
    }
    @Test fun `every modeled preference survives journal serialization and process recreation`() {
        val gson = com.google.gson.Gson()
        var count = 0
        listOf("app" to AppPreferences(), "playback" to PlaybackPreferences(), "home" to HomePreferences(),
            "detail" to DetailPreferences(), "streams" to StreamsPreferences()).forEach { (section, model) ->
            model.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) || it.name.contains("apiKey", true) }.forEach { field ->
                val sample: com.google.gson.JsonElement = when (field.type) {
                    java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> com.google.gson.JsonPrimitive(false)
                    java.lang.Integer.TYPE, java.lang.Integer::class.java -> com.google.gson.JsonPrimitive(0)
                    String::class.java -> com.google.gson.JsonPrimitive("")
                    else -> { field.isAccessible = true; gson.toJsonTree(field.get(model)) }
                }
                disk = null
                val patch = com.google.gson.JsonObject().apply { add(section, com.google.gson.JsonObject().apply { add(field.name, sample) }) }
                assertTrue(store().enqueue(owner, patch))
                assertEquals("$section.${field.name}", sample, value(store(), "$section.${field.name}"))
                count++
            }
        }
        assertTrue("Expected broad model coverage, got $count", count > 80)
    }

}
