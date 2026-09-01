package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSourceEngineTest {
    private val repoUrl = "https://plugins.example/manifest.json"

    private fun provider(
        id: String,
        types: List<String>,
        enabled: Boolean = true,
        repo: String = repoUrl,
    ) = ProfilePluginProvider(id = id, repoUrl = repo, name = id, types = types, enabled = enabled)

    private fun state(
        enabled: Boolean = true,
        repoEnabled: Boolean = true,
        providers: List<ProfilePluginProvider>,
    ) = ProfilePluginState(
        enabled = enabled,
        repos = listOf(ProfilePluginRepo(url = repoUrl, name = "Example", version = "1", enabled = repoEnabled)),
        providers = providers,
    )

    @Test
    fun `providers answer the type they advertise, series and tv being the same thing`() {
        val current = state(
            providers = listOf(
                provider("movies", listOf("movie")),
                provider("shows", listOf("series")),
            ),
        )

        assertEquals(listOf("movies"), eligiblePluginProviders(current, "movie").map { it.id })
        assertEquals(listOf("shows"), eligiblePluginProviders(current, "tv").map { it.id })
        assertEquals(listOf("shows"), eligiblePluginProviders(current, "series").map { it.id })
    }

    @Test
    fun `disabling the feature, the collection or the provider takes it out of a lookup`() {
        val providers = listOf(provider("movies", listOf("movie")))

        assertTrue(eligiblePluginProviders(state(enabled = false, providers = providers), "movie").isEmpty())
        assertTrue(eligiblePluginProviders(state(repoEnabled = false, providers = providers), "movie").isEmpty())
        assertTrue(
            eligiblePluginProviders(
                state(providers = listOf(provider("movies", listOf("movie"), enabled = false))),
                "movie",
            ).isEmpty(),
        )
    }

    @Test
    fun `a provider whose collection is not in the snapshot never runs`() {
        val current = state(
            providers = listOf(provider("orphan", listOf("movie"), repo = "https://gone.example/manifest.json")),
        )

        assertTrue(eligiblePluginProviders(current, "movie").isEmpty())
    }

    @Test
    fun `no plugin state at all means no plugin sources`() {
        assertTrue(eligiblePluginProviders(null, "movie").isEmpty())
    }

    @Test
    fun `favourite providers are queried first without changing eligibility`() {
        val current = state(
            providers = listOf(
                provider("alpha", listOf("movie")),
                provider("zulu", listOf("movie")).copy(favourite = true),
                provider("disabled", listOf("movie"), enabled = false).copy(favourite = true),
            ),
        )

        assertEquals(listOf("zulu", "alpha"), eligiblePluginProviders(current, "movie").map { it.id })
    }

    @Test
    fun `scraper filenames resolve against the collection manifest url`() {
        assertEquals(
            "https://plugins.example/sources/movies.js",
            resolvePluginProviderUrl(repoUrl, "sources/movies.js"),
        )
        assertEquals(
            "https://cdn.example/movies.js",
            resolvePluginProviderUrl(repoUrl, "https://cdn.example/movies.js"),
        )
        // A manifest served with a query string (a signed link, say) passes it on to its scrapers.
        assertEquals(
            "https://plugins.example/movies.js?token=abc",
            resolvePluginProviderUrl("https://plugins.example/manifest.json?token=abc", "movies.js"),
        )
    }

    @Test
    fun `module syntax is rewritten into the sandbox's commonjs shape`() {
        val normalized = normalizePluginJavaScript(
            """
            import cheerio from 'cheerio';
            import { load as parse } from 'cheerio';
            export { getStreams };
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                "const cheerio = require('cheerio');",
                "const {load: parse} = require('cheerio');",
                "module.exports = {getStreams};",
            ),
            normalized.lines(),
        )
    }
    @Test
    fun `a source exporting onSettings is offered settings whatever its manifest said`() {
        assertTrue(pluginDeclaresSettings("async function onSettings() { return [] }"))
        assertTrue(pluginDeclaresSettings("module.exports = { onSettings: async () => [] }"))
        assertTrue(pluginDeclaresSettings("globalThis.onSettings = async function () { return [] }"))
    }

    @Test
    fun `a source without an onSettings export is not offered settings`() {
        assertFalse(pluginDeclaresSettings("async function getStreams() { return [] }"))
        // Substring matches are the trap here: neither of these exports onSettings itself.
        assertFalse(pluginDeclaresSettings("function buildOnSettingsPayload() {}"))
        assertFalse(pluginDeclaresSettings("const label = 'onSettingsLabel'"))
    }
}
