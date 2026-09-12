package com.streamdek.tv.nativeapp.ui.detail

import com.streamdek.tv.nativeapp.data.*
import org.junit.Assert.*
import org.junit.Test

/** Contract fixtures matching Mobile Api.kt's response envelopes and optional HLS metadata. */
class AddonResultParityTest {
    @Test
    fun `movie episode and live batches retain every valid result through display grouping`() {
        for (count in listOf(1, 87, 10_000)) {
            for (envelope in listOf("streams", "results", "items", "__array", "array")) {
                val rows = (0 until count).joinToString(",") { index ->
                    val addon = listOf("aio", "torrent", "live")[index % 3]
                    val url = when (index % 3) {
                        0 -> "\"url\":{\"href\":\"https://fixture.test/$index.m3u8\"}"
                        1 -> "\"infoHash\":\"0123456789012345678901234567890123456789\",\"fileIdx\":$index"
                        else -> "\"externalUrl\":\"https://fixture.test/$index.m3u8\""
                    }
                    """{"addonId":"$addon","addonName":"$addon","title":"Result $index",$url}"""
                }
                val raw = if (envelope == "array") "[$rows]" else """{"$envelope":[$rows]}"""
                var returned = -1
                val parsed = parseAddonStreamsPayload(raw) { rawCount, parsedCount ->
                    returned = rawCount
                    assertEquals(count, parsedCount)
                }
                val retained = parsed.distinctBy(::streamAggregationKey)
                // A stale partial publication after the complete one must not remove late rows.
                val state = mergeProgressiveStreamSnapshot(retained, retained.take(count / 2))
                val playable = state.filter(::isPlayableAddonStream)
                val displayed = buildStreamListEntries(playable, "best", true)
                    .filterIsInstance<StreamListEntry.Result>().map { it.stream }
                assertEquals(count, returned)
                assertEquals(count, retained.size)
                assertEquals(count, state.size)
                assertEquals(count, playable.size)
                assertEquals(parsed.map { it.title }.toSet(), displayed.map { it.title }.toSet())
            }
        }
    }

    @Test
    fun `HLS needs neither torrent metadata nor size but archive filtering remains`() {
        assertTrue(isPlayableAddonStream(AddonStream(url = "https://fixture.test/movie.m3u8?token=fixture")))
        assertTrue(isPlayableAddonStream(AddonStream(url = "https://fixture.test/episode.M3U8")))
        assertFalse(isPlayableAddonStream(AddonStream(url = "https://fixture.test/archive.zip")))
        assertFalse(isPlayableAddonStream(AddonStream(title = "Provider error")))
    }

    @Test
    fun `invalid rows are counted and do not suppress adjacent valid results`() {
        var rejected = -1
        val parsed = parseAddonStreamsPayload("""{"streams":[null,17,{"url":"https://fixture.test/valid.m3u8"}]}""") { returned, kept ->
            rejected = returned - kept
        }
        assertEquals(2, rejected)
        assertEquals(1, parsed.size)
        assertTrue(isPlayableAddonStream(parsed.single()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid envelope is a failure rather than a successful empty response`() {
        parseAddonStreamsPayload("""{"error":"upstream failed"}""")
    }
}
