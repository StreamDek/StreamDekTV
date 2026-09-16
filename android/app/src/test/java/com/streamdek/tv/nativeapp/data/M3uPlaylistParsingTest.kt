package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The M3U parser, against the shapes provider playlists actually ship.
 *
 * Worth pinning closely: this reads untrusted text from a third party at 200k entries a go, and
 * every field it gets wrong becomes a channel that either does not appear or does not play.
 */
class M3uPlaylistParsingTest {
    private fun parse(body: String) = parseM3uLines(body.lineSequence(), "pl1", "My Provider")

    @Test
    fun `reads title artwork and category off an extinf line`() {
        val items = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="bbc1" tvg-logo="https://cdn.example/bbc1.png" group-title="UK Entertainment",BBC One HD
            https://provider.example/live/bbc1.ts
            """.trimIndent(),
        )

        assertEquals(1, items.size)
        val channel = items.first()
        assertEquals("BBC One HD", channel.title)
        assertEquals("live", channel.type)
        assertEquals("https://cdn.example/bbc1.png", channel.poster)
        assertEquals("UK Entertainment", channel.sourceCatalogName)
        assertEquals("https://provider.example/live/bbc1.ts", channel.directStreamUrl)
    }

    @Test
    fun `a byte order mark does not hide the playlist marker`() {
        // A BOM leaves the first line as "﻿#EXTM3U", which used to read as an unknown line.
        val items = parse("﻿#EXTM3U\n#EXTINF:-1,Channel One\nhttps://provider.example/one.ts")

        assertEquals(1, items.size)
        assertEquals("Channel One", items.first().title)
    }

    @Test
    fun `inline and vlc headers both reach the item`() {
        val items = parse(
            """
            #EXTM3U
            #EXTINF:-1,Guarded Channel
            #EXTVLCOPT:http-referrer=https://portal.example/
            https://provider.example/live/guarded.ts|User-Agent=SmartTV%2F1.0&Referer=https%3A%2F%2Foverride.example%2F
            """.trimIndent(),
        )

        val headers = items.single().requestHeaders
        // Percent-encoding is decoded, and the inline value wins over the VLC directive.
        assertEquals("SmartTV/1.0", headers["User-Agent"])
        assertEquals("https://override.example/", headers["Referer"])
        assertEquals("https://provider.example/live/guarded.ts", items.single().directStreamUrl)
    }

    @Test
    fun `on-demand entries are separated from live channels`() {
        val items = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Sports",Sky Sports Main Event
            https://provider.example/live/sky.ts
            #EXTINF:7200 group-title="Movies",Blade Runner 2049
            https://provider.example/movie/blade-runner.mp4
            #EXTINF:-1 group-title="Series",The Wire S01E03
            https://provider.example/series/wire-s01e03.mkv
            """.trimIndent(),
        )

        assertEquals(3, items.size)
        assertEquals("live", items[0].type)
        // A real duration, a movie path and an SxxExx title each mark on-demand content.
        assertEquals("movie", items[1].type)
        assertEquals("movie", items[2].type)
    }

    @Test
    fun `attributes do not leak from one entry to the next`() {
        val items = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://cdn.example/one.png" group-title="News",Channel One
            https://provider.example/one.ts
            #EXTINF:-1,Channel Two
            https://provider.example/two.ts
            """.trimIndent(),
        )

        assertEquals(2, items.size)
        assertNull("logo carried over", items[1].poster)
        assertEquals("My Provider", items[1].sourceCatalogName)
    }

    @Test
    fun `an html error page is not mistaken for a playlist`() {
        // Providers answer a dead token with a 200 and a login page. Without the marker check this
        // parsed every line of markup into a channel.
        val html = buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html><body>")
            repeat(80) { appendLine("<p>Your subscription has expired</p>") }
            appendLine("</body></html>")
        }

        assertTrue(parse(html).isEmpty())
    }

    @Test
    fun `progress is reported as entries are parsed`() {
        // What the Live page shows instead of a silent skeleton. Reported per entry; the caller
        // decides how often to surface it.
        val seen = mutableListOf<Int>()
        val items = parseM3uLines(
            """
            #EXTM3U
            #EXTINF:-1,One
            https://provider.example/1.ts
            #EXTINF:-1,Two
            https://provider.example/2.ts
            #EXTINF:-1,Three
            https://provider.example/3.ts
            """.trimIndent().lineSequence(),
            "pl1",
            "My Provider",
        ) { parsed -> seen += parsed }

        assertEquals(3, items.size)
        assertEquals(listOf(1, 2, 3), seen)
    }

    @Test
    fun `a vlc cookie directive reaches the item`() {
        val items = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News",Cookie Channel
            #EXTVLCOPT:http-user-agent=Provider Player
            #EXTVLCOPT:http-cookie=__hdnea__=st=1789548446~exp=1789570046~acl=/*~hmac=2e3de672
            https://provider.example/live/index.mpd
            """.trimIndent(),
        )

        val headers = items.single().requestHeaders
        // Only the first '=' belongs to the directive; the cookie value keeps its own.
        assertEquals("__hdnea__=st=1789548446~exp=1789570046~acl=/*~hmac=2e3de672", headers["Cookie"])
        assertEquals("Provider Player", headers["User-Agent"])
    }

    @Test
    fun `exthttp headers are read literally`() {
        val items = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Sports",Json Channel
            #EXTHTTP:{"origin":"https://www.provider.example","Referer":"https://www.provider.example/","Cookie":"hdntl=exp=1789629904~acl=%2f*~hmac=68cd","X-Custom":"a, b"}
            https://provider.example/live/master.m3u8
            """.trimIndent(),
        )

        val headers = items.single().requestHeaders
        // JSON values are not URL-encoded; a literal %2f in a signed token must survive untouched.
        assertEquals("https://www.provider.example", headers["Origin"])
        assertEquals("https://www.provider.example/", headers["Referer"])
        assertEquals("hdntl=exp=1789629904~acl=%2f*~hmac=68cd", headers["Cookie"])
        assertEquals("a, b", headers["X-Custom"])
        assertEquals(4, headers.size)
    }

    @Test
    fun `a premium plugx style entry carries one of each header`() {
        // KODIPROP, EXTVLCOPT and EXTHTTP all describing one channel, as PremiumPlugX serves it.
        val items = parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="255" group-title="Jio TV+ | News",NDTV 24x7
            #KODIPROP:inputstream.adaptive.manifest_type=mpd
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #KODIPROP:inputstream.adaptive.license_key=9b5f31aacf4f57758fb654a54b5aafec:e7ff670f95103a87bdb0ede3689f257b
            #EXTVLCOPT:http-user-agent=Premium Plugx
            #EXTVLCOPT:http-referrer=https://www.jiotv.com/
            #EXTVLCOPT:http-cookie=__hdnea__=st=1~exp=2~acl=/*~hmac=abc
            #EXTHTTP:{"User-Agent":"Premium Plugx","Referer":"https://www.jiotv.com/","Origin":"https://www.jiotv.com/","Cookie":"__hdnea__=st=1~exp=2~acl=/*~hmac=abc"}
            https://jiotvmblive.cdn.jio.com/bpk-tv/NDTV_24x7_MOB/WDVLive/index.mpd
            #EXTINF:-1 group-title="Jio TV+ | News",No Headers Channel
            https://provider.example/plain.m3u8
            """.trimIndent(),
        )

        assertEquals(2, items.size)
        assertEquals(
            mapOf(
                "User-Agent" to "Premium Plugx",
                "Referer" to "https://www.jiotv.com/",
                "Cookie" to "__hdnea__=st=1~exp=2~acl=/*~hmac=abc",
                "Origin" to "https://www.jiotv.com/",
            ),
            items[0].requestHeaders,
        )
        assertTrue("headers carried over", items[1].requestHeaders.isEmpty())
    }

    @Test
    fun `the inline suffix still wins without duplicating header names`() {
        val items = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Sports",Both Forms
            #EXTHTTP:{"Cookie":"from=json","Referer":"https://json.example/","X-Token":"json"}
            https://provider.example/live.m3u8?|cookie=from%3Dsuffix&referer=https://suffix.example/&x-token=suffix
            """.trimIndent(),
        )

        assertEquals("https://provider.example/live.m3u8?", items.single().directStreamUrl)
        assertEquals(
            mapOf("Cookie" to "from=suffix", "Referer" to "https://suffix.example/", "x-token" to "suffix"),
            items.single().requestHeaders,
        )
    }

    @Test
    fun `malformed or unsendable exthttp values are ignored`() {
        val items = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News",Broken Json
            #EXTVLCOPT:http-user-agent=Provider Player
            #EXTHTTP:{"Cookie":"unterminated
            https://provider.example/broken.m3u8
            #EXTINF:-1 group-title="News",Odd Values
            #EXTHTTP:{"Cookie":"a\r\nX-Injected: 1","Nested":{"a":1},"List":[1],"Empty":"","Missing":null,"Port":8080}
            https://provider.example/odd.m3u8
            #EXTINF:-1 group-title="News",Not An Object
            #EXTHTTP:["Cookie","x"]
            https://provider.example/array.m3u8
            """.trimIndent(),
        )

        assertEquals(3, items.size)
        assertEquals(mapOf("User-Agent" to "Provider Player"), items[0].requestHeaders)
        assertEquals(mapOf("Port" to "8080"), items[1].requestHeaders)
        assertTrue(items[2].requestHeaders.isEmpty())
    }

    @Test
    fun `clearkey licence is read when kodiprop precedes extinf`() {
        // Real playlists (e.g. Tamil IPTV lists) put #KODIPROP directives before the #EXTINF line
        // they belong to, not after.
        val item = parse(
            """
            #EXTM3U
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #KODIPROP:inputstream.adaptive.license_key=3891557F1CB14DEDB7545BF52499D748:FB662F742E5F5E0C61A7C1C66D2B019A
            #EXTINF:-1 group-title="Entertainment",Sun TV HD
            https://livestream.example/SunTVHDB_IN_index.mpd
            """.trimIndent(),
        ).single()

        assertEquals("clearkey", item.drmLicenseType)
        // Hex is lowercased so the player sees one spelling.
        assertEquals(mapOf("3891557f1cb14dedb7545bf52499d748" to "fb662f742e5f5e0c61a7c1c66d2b019a"), item.drmClearKeys)
    }

    @Test
    fun `clearkey licence is read when kodiprop follows extinf`() {
        val item = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Jio TV+ | News",NDTV 24x7
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #KODIPROP:inputstream.adaptive.license_key=9b5f31aacf4f57758fb654a54b5aafec:e7ff670f95103a87bdb0ede3689f257b
            #EXTVLCOPT:http-user-agent=Premium Plugx
            https://jiotvmblive.cdn.jio.com/bpk-tv/NDTV_24x7_MOB/WDVLive/index.mpd
            """.trimIndent(),
        ).single()

        assertEquals("clearkey", item.drmLicenseType)
        assertEquals("e7ff670f95103a87bdb0ede3689f257b", item.drmClearKeys?.get("9b5f31aacf4f57758fb654a54b5aafec"))
        assertEquals("Premium Plugx", item.requestHeaders["User-Agent"])
    }

    @Test
    fun `multiple clearkey pairs are read and do not leak to the next entry`() {
        val items = parse(
            """
            #EXTM3U
            #KODIPROP:inputstream.adaptive.license_type=clearkey
            #KODIPROP:inputstream.adaptive.license_key=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:11111111111111111111111111111111&bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb:22222222222222222222222222222222
            #EXTINF:-1 group-title="Entertainment",Multi Key Channel
            https://provider.example/multikey.mpd
            #EXTINF:-1 group-title="Entertainment",Plain Channel
            https://provider.example/plain.mpd
            #EXTINF:-1 group-title="Entertainment",Malformed Key
            #KODIPROP:inputstream.adaptive.license_key=notapair
            https://provider.example/malformed.mpd
            """.trimIndent(),
        )

        assertEquals(3, items.size)
        assertEquals(
            mapOf(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" to "11111111111111111111111111111111",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" to "22222222222222222222222222222222",
            ),
            items[0].drmClearKeys,
        )
        // The second entry has no KODIPROP of its own - it must not inherit the first entry's keys.
        assertNull(items[1].drmLicenseType)
        assertNull(items[1].drmClearKeys)
        assertNull(items[2].drmClearKeys)
    }

    @Test
    fun `entries without a title still parse`() {
        val items = parse("#EXTM3U\n#EXTINF:-1,\nhttps://provider.example/nameless.ts")

        assertEquals(1, items.size)
        assertEquals("Item 1", items.single().title)
    }
}
