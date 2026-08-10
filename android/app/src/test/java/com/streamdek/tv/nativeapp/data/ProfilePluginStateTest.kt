package com.streamdek.tv.nativeapp.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
