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
    fun `entries without a title still parse`() {
        val items = parse("#EXTM3U\n#EXTINF:-1,\nhttps://provider.example/nameless.ts")

        assertEquals(1, items.size)
        assertEquals("Item 1", items.single().title)
    }
}
