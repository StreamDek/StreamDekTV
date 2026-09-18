package com.streamdek.tv.nativeapp.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyStreamTest {
    private val section = """
        {
          "updatedAt": 100,
          "repos": [{"url":"https://repo.test/mega.json","name":"Sky Universe","enabled":true,"favourite":false}],
          "providers": [{
            "repoUrl":"https://repo.test/mega.json","packageName":"com.arranoust.torrentio","name":"Torrentio",
            "version":7,"downloadUrl":"https://repo.test/dist/com.arranoust.torrentio.sky",
            "categories":["Movie","TvSeries"],"enabled":true,
            "settings":{"sort_by":"size","_base_url":"https://mirror.test"},
            "settingsSchema":[{"type":"select","key":"sort_by","label":"Sort By"}],
            "subProviders":[{"id":"a","name":"A"}],
            "someFieldTheTvDoesNotKnow":{"kept":true}
          }]
        }
    """.trimIndent()

    @Test
    fun `the skystream section survives the television saving the document`() {
        // The television sends ProfilePluginState back whole whenever a plugin setting changes.
        // Before this field existed, Gson dropped the section on that write and every SkyStream
        // collection on the account went with it.
        val gson = Gson()
        val state = gson.fromJson("""{"enabled":true,"repos":[],"providers":[],"skystream":$section}""", ProfilePluginState::class.java)
        val written = JsonParser.parseString(gson.toJson(state.copy(enabled = false))).asJsonObject
        val sky = written.getAsJsonObject("skystream")
        assertNotNull(sky)
        val provider = sky.getAsJsonArray("providers")[0].asJsonObject
        assertEquals("size", provider.getAsJsonObject("settings").get("sort_by").asString)
        // Fields the television does not model — the portal's schemas, anything added later — too.
        assertTrue(provider.has("settingsSchema"))
        assertTrue(provider.getAsJsonObject("someFieldTheTvDoesNotKnow").get("kept").asBoolean)
    }

    @Test
    fun `reads collections and sources out of the section`() {
        val json = JsonParser.parseString(section).asJsonObject
        val repo = SkyStreamProfile.repos(json).single()
        assertEquals("Sky Universe", repo.name)
        val source = SkyStreamProfile.sources(json).single()
        assertEquals("com.arranoust.torrentio", source.packageName)
        assertEquals(7, source.version)
        assertEquals(listOf("Movie", "TvSeries"), source.categories)
        assertEquals("https://mirror.test", source.settings[SkyStreamPluginManager.ADDRESS_KEY])
        assertTrue(SkyStreamProfile.repos(null).isEmpty())
    }

    @Test
    fun `edits change only what they name, keep everything else and stamp the section`() {
        val json = JsonParser.parseString(section).asJsonObject
        val switched = SkyStreamProfile.withSource(json, "https://repo.test/mega.json", "com.arranoust.torrentio") { it.addProperty("enabled", false) }
        assertEquals(false, SkyStreamProfile.sources(switched).single().enabled)
        assertTrue(switched.get("updatedAt").asLong > 100)
        assertTrue(switched.getAsJsonArray("providers")[0].asJsonObject.has("someFieldTheTvDoesNotKnow"))
        // The original is left alone: the screen still holds it until the account answers.
        assertEquals(true, SkyStreamProfile.sources(json).single().enabled)

        val settings = SkyStreamProfile.withSettings(json, "https://repo.test/mega.json", "com.arranoust.torrentio", mapOf("sort_by" to "seeders"))
        assertEquals(mapOf("sort_by" to "seeders"), SkyStreamProfile.sources(settings).single().settings)

        val favourite = SkyStreamProfile.withRepo(json, "https://repo.test/mega.json") { it.addProperty("favourite", true) }
        assertTrue(SkyStreamProfile.repos(favourite).single().favourite)
    }

    @Test
    fun `reads toggles whether sources declare them as booleans or as text`() {
        assertTrue(settingIsOn(true))
        assertTrue(settingIsOn("true"))
        assertTrue(!settingIsOn("false", fallback = true))
        assertTrue(settingIsOn(null, fallback = true))
    }

    @Test
    fun `parses the settings shapes skystream accepts, with defaults as text`() {
        val fields = parseSkySettingsSchema(
            """{"settings":[
              {"key":"external_subs","title":"Subs","type":"toggle","defaultValue":true},
              {"key":"base_url","title":"Site","type":"text"},
              {"key":"langs","title":"Languages","type":"toggle_group","options":[{"value":"en","label":"English","defaultValue":true}]}
            ]}""",
        )
        assertEquals(listOf("toggle", SKY_ADDRESS_FIELD_TYPE, "toggleGroup"), fields.map { it.type })
        assertEquals("true", fields[0].defaultValue)
        assertTrue(fields[2].options.single().defaultOn)
    }

    @Test
    fun `maps categories, titles and qualities the way the phone does`() {
        assertEquals(setOf(TvType.Live), skyProviderTypes(listOf("LiveTv")))
        assertNull(skyItemType("something"))
        assertEquals("Avengers: Endgame" to 2019, skyCleanTitle("Avengers: Endgame (2019) BluRay [Hindi] 1080p"))
        assertEquals(1080, skyQualityOf("HDHub 1080p [MKV]"))
        assertEquals(listOf("https://anisuge.tv"), skyDomains(org.json.JSONArray("""["https://anisuge.tv/"]""")).map { it.url })
    }
}
