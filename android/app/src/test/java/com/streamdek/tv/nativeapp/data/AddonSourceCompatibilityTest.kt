package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two ways a well-behaved add-on can still hand back something this app used to throw
 * away: a usenet result with no playable url, and a `meta` answer that is really an error.
 */
class AddonSourceCompatibilityTest {
    @Test
    fun `a usenet result is recognised by its nzb pointer`() {
        val usenet = AddonStream(
            addonName = "AIOStreams",
            name = "[SN] Usenet Streamer 2160p",
            nzbUrl = "https://api.nzbplanet.net/getnzb/abc.nzb?i=1&r=2",
            servers = listOf("nntps://user:pass@news.example.com:119"),
        )

        assertTrue(isUsenetAddonStream(usenet))
        assertFalse(isUsenetAddonStream(AddonStream(url = "https://cdn.example/movie.mkv")))
        assertFalse(isUsenetAddonStream(AddonStream(infoHash = "abc123")))
        // A blank field is not a usenet source — add-ons serialise empty strings freely.
        assertFalse(isUsenetAddonStream(AddonStream(nzbUrl = "")))
    }

    @Test
    fun `an error placeholder is not accepted as add-on meta`() {
        // Exactly what AIOStreams answers /meta with, for an id it cannot describe.
        val errorMeta = AddonMetaItem(
            id = "aiostreamserror.%7B%22errorTitle%22%3A%22Bingecat%22%7D",
            name = "[X] Bingecat",
            description = "Failed to parse meta for Bingecat",
            type = "movie",
        )

        assertFalse(isUsableAddonMeta(errorMeta, "tt0145487"))
    }

    @Test
    fun `meta is accepted when it can still drive a lookup`() {
        // A metadata add-on echoing back the id it was asked about.
        assertTrue(isUsableAddonMeta(AddonMetaItem(id = "kitsu:1376", name = "Cowboy Bebop"), "kitsu:1376"))
        // Or carrying an IMDb id of its own.
        assertTrue(isUsableAddonMeta(AddonMetaItem(id = "tmdb:30991", imdbId = "tt0213338", name = "Cowboy Bebop"), "tmdb:30991"))
        // Or bringing episodes, which are content in their own right.
        assertTrue(
            isUsableAddonMeta(
                AddonMetaItem(id = "bridge:99", name = "Some Show", videos = listOf(AddonMetaVideo("bridge:99:1:1", 1, 1))),
                "bridge:12",
            ),
        )
    }

    @Test
    fun `meta with no title is never usable`() {
        assertFalse(isUsableAddonMeta(AddonMetaItem(id = "tt0145487", name = " "), "tt0145487"))
    }
}
