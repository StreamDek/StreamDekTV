package com.streamdek.tv.nativeapp.data

import com.streamdek.tv.nativeapp.mediaserver.MediaServerReference
import java.util.Locale

/**
 * How a source plays, as the "Prefer media server source" setting ranks it: a server's Direct Play
 * first, its Direct Stream next, then every add-on and plugin, and a server transcode last - kept
 * for choosing by hand, never preferred over an add-on just because it is the viewer's own server.
 * The same order as the phone's.
 *
 * Read from the play method StreamDek's own planner chose for this device and engine (the
 * `source` tag every Plex, Jellyfin and Emby source carries), not from the server's own label, so
 * a file this television cannot decode is never a Direct Play here.
 */
internal enum class SourceTier { ServerDirectPlay, ServerDirectStream, AddonOrPlugin, ServerTranscode }

internal fun sourceTierOf(stream: AddonStream): SourceTier {
    if (!stream.addonId.startsWith(MediaServerReference.SOURCE_PREFIX)) return SourceTier.AddonOrPlugin
    return when (stream.source?.substringAfterLast(':')?.lowercase(Locale.US)) {
        "directplay" -> SourceTier.ServerDirectPlay
        "directstream" -> SourceTier.ServerDirectStream
        else -> SourceTier.ServerTranscode
    }
}

private val SIZE_PATTERN = Regex("""([\d.]+)\s*(GB|GiB|MB|MiB|TB|TiB)\b""", RegexOption.IGNORE_CASE)

private fun sizeGiB(size: String?): Double? {
    val match = SIZE_PATTERN.find(size.orEmpty()) ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    return when (match.groupValues[2].lowercase(Locale.US)) {
        "tb", "tib" -> value * 1024.0
        "mb", "mib" -> value / 1024.0
        else -> value
    }
}

/**
 * How good a media server copy is, for ordering copies that play the same way: the viewer's
 * preferred quality first, then resolution, then dynamic range, then size. Only ever compared
 * between copies already known to play here, so a higher number is never a copy that will fail.
 */
internal fun mediaServerQualityScore(stream: AddonStream, preferredQuality: String): Int {
    val text = listOfNotNull(stream.quality, stream.title, stream.name).joinToString(" ").lowercase(Locale.US)
    val resolution = when {
        "2160" in text || "4k" in text -> 5
        "1440" in text -> 4
        "1080" in text -> 3
        "720" in text -> 2
        "576" in text || "480" in text -> 1
        else -> 0
    }
    val range = when {
        "dovi" in text || "dolby vision" in text -> 4
        "hdr10plus" in text || "hdr10+" in text -> 3
        "hdr" in text -> 2
        "hlg" in text -> 1
        else -> 0
    }
    val size = sizeGiB(stream.size)?.let { (it / 8.0).toInt().coerceIn(0, 9) } ?: 0
    return preferredQualityScore(inferredStreamQuality(stream), preferredQuality) * 1000 + resolution * 100 + range * 10 + size
}

/**
 * The tier and in-tier quality of each stream, worked out once for a ranking, or nothing when the
 * preference is off - so the ranking's own order is then exactly what it always was.
 */
internal class MediaServerPriority private constructor(private val keys: java.util.IdentityHashMap<AddonStream, Pair<Int, Int>>) {
    fun tier(stream: AddonStream): Int = keys[stream]?.first ?: 0
    fun quality(stream: AddonStream): Int = keys[stream]?.second ?: 0

    companion object {
        val Off = MediaServerPriority(java.util.IdentityHashMap())

        fun of(streams: List<AddonStream>, enabled: Boolean, preferredQuality: String): MediaServerPriority {
            if (!enabled) return Off
            val keys = java.util.IdentityHashMap<AddonStream, Pair<Int, Int>>()
            for (stream in streams) {
                val tier = sourceTierOf(stream)
                keys[stream] = tier.ordinal to if (tier == SourceTier.AddonOrPlugin) 0 else mediaServerQualityScore(stream, preferredQuality)
            }
            return MediaServerPriority(keys)
        }
    }
}
