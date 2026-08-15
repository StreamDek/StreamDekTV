package com.streamdek.tv.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveStreamAggregationTest {
    @Test
    fun `late addon snapshot cannot remove previously displayed streams`() {
        val eclipsia = (1..8).map { index ->
            AddonStream(addonId = "eclipsia", addonName = "Eclipsia", title = "Eclipsia $index")
        }
        val aioStreams = (1..12).map { index ->
            AddonStream(addonId = "aio", addonName = "AIOStreams", title = "AIO $index")
        }

        val merged = mergeProgressiveStreamSnapshot(eclipsia, aioStreams)

        assertEquals(20, merged.size)
        assertEquals(8, merged.count { it.addonId == "eclipsia" })
        assertEquals(12, merged.count { it.addonId == "aio" })
    }

    @Test
    fun `repeated progressive snapshots do not duplicate streams`() {
        val stream = AddonStream(addonId = "aio", addonName = "AIOStreams", title = "Source")

        assertEquals(listOf(stream), mergeProgressiveStreamSnapshot(listOf(stream), listOf(stream)))
    }

    @Test
    fun `same-labelled files from one provider are all retained`() {
        val providerRows = (0 until 30).map { fileIndex ->
            AddonStream(
                addonId = "aio",
                addonName = "AIOStreams",
                name = "Cached 1080p",
                title = "Release pack",
                infoHash = "same-torrent-hash",
                fileIdx = fileIndex,
                filename = "episode-$fileIndex.mkv",
            )
        }

        assertEquals(30, mergeProgressiveStreamSnapshot(emptyList(), providerRows).size)
    }

    @Test
    fun `cache decoration replaces the progressive row instead of duplicating it`() {
        val original = AddonStream(
            addonId = "aio",
            addonName = "AIOStreams",
            title = "Release",
            infoHash = "ABC123",
        )
        val decorated = applyCachedProviders(listOf(original), mapOf("abc123" to listOf("Deepbrid")))

        val merged = mergeProgressiveStreamSnapshot(listOf(original), decorated)

        assertEquals(1, merged.size)
        assertEquals(listOf("Deepbrid"), merged.single().cachedBy)
    }

    @Test
    fun `addon supplied cache provider is not overwritten by account check`() {
        val supplied = AddonStream(infoHash = "abc123", cachedBy = listOf("AIOStreams cache"))

        val decorated = applyCachedProviders(listOf(supplied), mapOf("abc123" to listOf("Deepbrid")))

        assertEquals(listOf("AIOStreams cache"), decorated.single().cachedBy)
    }

    @Test
    fun `AIOStreams diagnostic metas are excluded from catalogs`() {
        assertTrue(
            isAddonCatalogDiagnosticMeta(
                AddonCatalogMetaItem(
                    id = "aiostreamserror:catalog",
                    type = "movie",
                    name = "[❌] AIOStreams Stable - Error",
                ),
            ),
        )
        assertFalse(
            isAddonCatalogDiagnosticMeta(
                AddonCatalogMetaItem(
                    id = "tt32916440",
                    type = "movie",
                    name = "Marty Supreme",
                    poster = "https://example.com/poster.jpg",
                ),
            ),
        )
    }
}
