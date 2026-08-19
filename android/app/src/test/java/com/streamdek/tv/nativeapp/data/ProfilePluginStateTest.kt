package com.streamdek.tv.nativeapp.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePluginStateTest {
    @Test
    fun `bootstrap parses synced plugin collections and providers`() {
        val bootstrap = Gson().fromJson(
            """
            {
              "profilePlugins": {
                "enabled": true,
                "repos": [{"url":"https://plugins.example/repo.json","name":"Example collection","version":"2","enabled":false}],
                "providers": [{"id":"movies","repo":"https://plugins.example/repo.json","name":"Example Movies","types":["movie","tv"],"enabled":true}],
                "updatedAt": 42
              }
            }
            """.trimIndent(),
            AccountBootstrap::class.java,
        )

        assertTrue(bootstrap.profilePlugins.enabled)
        assertEquals("Example collection", bootstrap.profilePlugins.repos.single().name)
        assertFalse(bootstrap.profilePlugins.repos.single().enabled)
        assertEquals("https://plugins.example/repo.json", bootstrap.profilePlugins.providers.single().repoUrl)
        assertEquals(listOf("movie", "tv"), bootstrap.profilePlugins.providers.single().types)
    }
    @Test
    fun `a provider's synced settings survive being parsed and written back`() {
        // The web portal and the phone write a source's token into this document. Before the
        // settings fields existed on the model, Gson dropped them on read — so the token never
        // reached the television, and worse, any write from the television (toggling a source,
        // say) serialised the model back without them and erased everybody else's copy too.
        val json = """
            {
              "enabled": true,
              "repos": [{"url":"https://plugins.example/repo.json","name":"Example","version":"2","enabled":true}],
              "providers": [{
                "id":"eclipsia","repo":"https://plugins.example/repo.json","name":"Eclipsia",
                "types":["movie"],"enabled":true,"hasSettings":true,
                "settings":{"apiToken":"secret-token","region":"uk"},
                "settingsSchema":[{"type":"text","key":"apiToken","label":"API token"}]
              }]
            }
        """.trimIndent()

        val state = Gson().fromJson(json, ProfilePluginState::class.java)
        val provider = state.providers.single()

        assertEquals("secret-token", provider.settings?.get("apiToken")?.asString)
        assertEquals("uk", provider.settings?.get("region")?.asString)
        assertEquals(1, provider.settingsSchema?.size())

        // Round-tripping is what a TV-side toggle does, and it must not lose the credential.
        val rewritten = Gson().fromJson(Gson().toJson(state), ProfilePluginState::class.java)
        assertEquals("secret-token", rewritten.providers.single().settings?.get("apiToken")?.asString)
        assertEquals(1, rewritten.providers.single().settingsSchema?.size())
    }

    @Test
    fun `a provider that has never been configured parses without settings`() {
        val state = Gson().fromJson(
            """{"providers":[{"id":"pynvix","repo":"r","name":"Pynvix","types":["movie"],"enabled":true}]}""",
            ProfilePluginState::class.java,
        )

        assertNull(state.providers.single().settings)
        assertNull(state.providers.single().settingsSchema)
    }
}
