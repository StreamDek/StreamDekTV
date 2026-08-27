package com.streamdek.tv.nativeapp.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two ways a well-behaved add-on can still hand back something this app used to throw
 * away: a usenet result with no playable url, and a `meta` answer that is really an error.
 */
class AddonSourceCompatibilityTest {

    @Test
    fun `provider-labelled direct debrid result is treated as cached`() {
        val streams = parseAddonStreamsPayload(
            """{"streams":[{"name":"Deepbrid 2160p","url":"https://example.test/movie.mkv"}]}""",
        )

        assertEquals(listOf("deepbrid"), streams.single().cachedBy)
    }

    @Test
    fun `provider wording does not mark a torrent-only result cached`() {
        val streams = parseAddonStreamsPayload(
            """{"streams":[{"name":"Deepbrid 2160p","infoHash":"0123456789012345678901234567890123456789"}]}""",
        )

        assertTrue(streams.single().cachedBy.isEmpty())
    }
    @Test
    fun `direct addon parsing retains every stream returned by AIOStreams`() {
        val streams = (1..56).joinToString(",") { index ->
            """{"name":"AIOStreams","title":"Result $index","url":"https://example.test/$index.mkv"}"""
        }

        val parsed = parseAddonStreamsPayload("""{"streams":[$streams]}""")

        assertEquals(56, parsed.size)
        assertEquals("Result 56", parsed.last().title)
    }

    @Test
    fun `one malformed AIOStreams row does not discard valid Deepbrid results`() {
        val parsed = parseAddonStreamsPayload(
            """{"streams":[{"behaviorHints":"invalid"},{"title":"Deepbrid","url":"https://example.test/deepbrid.mkv"}]}""",
        )

        assertEquals(2, parsed.size)
        assertEquals("Deepbrid", parsed.last().title)
    }

    @Test
    fun `Deepbrid AIOStreams result keeps provider label nested url and request headers`() {
        val parsed = parseAddonStreamsPayload(
            """
            {
              "streams": [{
                "name": "AIOStreams",
                "title": "Deepbrid cached 2160p",
                "source": "Deepbrid",
                "url": {"href": "https://deepbrid.example.test/play/123"},
                "cachedBy": ["Deepbrid"],
                "behaviorHints": {
                  "filename": "Movie.2160p.mkv",
                  "proxyHeaders": {"request": {"Referer": "https://deepbrid.example.test/"}}
                }
              }]
            }
            """.trimIndent(),
        ).single()

        assertEquals("https://deepbrid.example.test/play/123", parsed.url)
        assertEquals("Deepbrid", parsed.source)
        assertEquals(listOf("Deepbrid"), parsed.cachedBy)
        assertEquals("https://deepbrid.example.test/", parsed.requestHeaders["Referer"])
        assertTrue(addonStreamDisplayLabel(parsed).contains("Deepbrid", ignoreCase = true))
    }

    @Test
    fun `Eclipsia result retains its full generic source metadata`() {
        val parsed = parseAddonStreamsPayload(
            """
            {"streams":[{
              "addonId":"eclipsia","addonName":"Eclipsia","name":"Release group",
              "title":"Show.S03E01.2160p.WEB-DL.DV.HDR.HEVC.DDP5.1",
              "description":"English • Dolby Vision • DDP 5.1 • 42 seeders",
              "quality":"2160p","size":"10.72 GB","source":"ThePirateBay",
              "infoHash":"0123456789012345678901234567890123456789",
              "filename":"Show.S03E01.2160p.mkv","cachedBy":["Premiumize"]
            }]}
            """.trimIndent(),
        ).single()

        assertEquals("Eclipsia", parsed.addonName)
        assertEquals("Release group", parsed.name)
        assertTrue(parsed.title.orEmpty().contains("DV.HDR.HEVC"))
        assertTrue(parsed.description.orEmpty().contains("42 seeders"))
        assertEquals("2160p", parsed.quality)
        assertEquals("10.72 GB", parsed.size)
        assertEquals("ThePirateBay", parsed.source)
        assertEquals("Show.S03E01.2160p.mkv", parsed.filename)
        assertEquals(listOf("Premiumize"), parsed.cachedBy)
    }

    @Test
    fun `Eclipsia catalogue metadata accepts overview and tmdb id aliases`() {
        val parsed = Gson().fromJson(
            """{"id":"tmdb:12345","name":"Example","overview":"A real provider synopsis.","tmdb_id":12345}""",
            AddonCatalogMetaItem::class.java,
        )

        assertEquals("A real provider synopsis.", parsed.overview)
        assertEquals(12345, parsed.movieDbId)
    }

    @Test
    fun `Eclipsia meta metadata accepts synopsis fallback`() {
        val parsed = Gson().fromJson(
            """{"id":"tmdb:12345","name":"Example","synopsis":"Another real provider synopsis."}""",
            AddonMetaItem::class.java,
        )

        assertEquals("Another real provider synopsis.", parsed.synopsis)
    }

    @Test
    fun `AIOStreams usenet aliases survive direct addon parsing`() {
        val response = Gson().fromJson(
            """{"streams":[{"name":"Usenet","nzb_url":"https://example/nzb/1","nntp_servers":["nntps://reader@example:563"]}]}""",
            AddonStreamsResponse::class.java,
        )

        val stream = response.streams.single()
        assertEquals("https://example/nzb/1", stream.nzbUrl)
        assertEquals(listOf("nntps://reader@example:563"), stream.servers)
        assertTrue(isUsenetAddonStream(stream))
    }

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
