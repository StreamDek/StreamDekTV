package com.streamdek.tv.nativeapp.data

import android.content.Context
import com.streamdek.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Turns an IPTV playlist URL into channels the TV can play.
 *
 * A port of the phone's engine, and it exists for the same reason: a provider playlist is not a
 * small document. A 200k-channel list is 50–80 MB of text, so nothing here ever holds one whole —
 * lines come off the socket and become items as they arrive, and the body is teed into a gzip file
 * on the way past so the next launch costs no download at all.
 *
 * Not carried over from the phone: KODIPROP ClearKey DRM. The TV's [MediaItem] has nowhere to put
 * a licence key, so those entries play only if they are not actually encrypted.
 */
/**
 * What the engine is doing right now, for the screen to show.
 *
 * A provider playlist is not quick: tens of megabytes to download and hundreds of thousands of
 * entries to parse. [fraction] is only known while downloading, and only when the server sent a
 * content length — a cached read reports items instead, which is the honest thing to show rather
 * than inventing a percentage.
 */
data class M3uLoadProgress(
    val message: String,
    val fraction: Float? = null,
    val itemCount: Int = 0,
)

object M3uPlaylistEngine {
    private const val TAG = "M3uPlaylists"
    private const val CACHE_DIRECTORY = "m3u_playlists"

    /** How old a stored copy may get before a refresh is worth doing quietly in the background. */
    private const val CACHE_REFRESH_AFTER_MS = 12L * 60L * 60L * 1000L

    /**
     * Several IPTV panels gate `get.php` on a recognised client and answer anything else with 403,
     * even when the credentials in the URL are good. Only used after such a rejection, so the
     * app's own identifier stays the default for every provider that does not care.
     */
    private const val PLAYER_USER_AGENT = "VLC/3.0.18 LibVLC/3.0.18"

    private var cacheRoot: File? = null

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun initialize(context: Context) {
        if (cacheRoot == null) cacheRoot = File(context.applicationContext.filesDir, CACHE_DIRECTORY)
    }

    /**
     * Accepts any absolute http(s) playlist URL, including the query-string forms IPTV panels use
     * (`…/get.php?username=…&type=m3u_plus&output=ts`).
     *
     * Deliberately OkHttp's parser rather than [java.net.URI]: URI follows RFC 2396 strictly and
     * throws on characters that appear routinely in provider tokens — `|`, `[`, `]`, `{`, `}`,
     * spaces — so a perfectly fetchable link would be rejected before it was ever tried.
     */
    fun parsePlaylistUrl(rawUrl: String): HttpUrl? =
        rawUrl.trim().toHttpUrlOrNull()?.takeIf { it.scheme == "http" || it.scheme == "https" }

    /** True when this playlist has no stored copy, or one old enough to be worth re-fetching. */
    fun needsRefresh(playlist: RemotePlaylist): Boolean {
        val cached = cacheFile(playlist.id)?.takeIf { it.isFile && it.length() > 0L } ?: return true
        return System.currentTimeMillis() - cached.lastModified() > CACHE_REFRESH_AFTER_MS
    }

    /**
     * Channels for one playlist, from the stored copy when there is a usable one.
     *
     * Reading the saved copy first is what makes the Live page appear immediately on a cold start,
     * and it is also what keeps channels available when the provider is unreachable. Pass
     * [forceRefresh] for an explicit refresh, which always goes to the provider.
     */
    suspend fun fetchChannels(
        playlist: RemotePlaylist,
        forceRefresh: Boolean = false,
        onProgress: (M3uLoadProgress) -> Unit = {},
    ): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        runCatching {
            if (!forceRefresh) {
                parseCached(playlist, onProgress)?.let { return@runCatching it }
            }
            streamAndParse(playlist, onProgress)
        }
    }

    fun evictCache(playlistId: String) {
        runCatching { cacheFile(playlistId)?.delete() }
    }

    private fun cacheFile(playlistId: String): File? {
        val root = cacheRoot ?: return null
        if (!root.isDirectory && !root.mkdirs()) return null
        return File(root, "${playlistId.hashCode().toUInt().toString(16)}.m3u.gz")
    }

    /** The stored copy, or null when there isn't a usable one. An unreadable cache counts as absent. */
    private fun parseCached(playlist: RemotePlaylist, onProgress: (M3uLoadProgress) -> Unit): List<MediaItem>? {
        val file = cacheFile(playlist.id)?.takeIf { it.isFile && it.length() > 0L } ?: return null
        return runCatching {
            GZIPInputStream(FileInputStream(file), 64 * 1024).use { gzip ->
                val reader = BufferedReader(InputStreamReader(gzip, StandardCharsets.UTF_8), 128 * 1024)
                var lastReported = 0
                val items = parseM3uLines(reader.lineSequence(), playlist.id, playlist.name) { parsed ->
                    if (parsed - lastReported >= 5_000) {
                        lastReported = parsed
                        onProgress(M3uLoadProgress("Opening ${playlist.name}: ${parsed.formatted()} channels", null, parsed))
                    }
                }
                onProgress(M3uLoadProgress("${playlist.name}: ${items.size.formatted()} saved channels", 1f, items.size))
                items
            }
        }.onFailure {
            TvDebugLogger.w(TAG, "saved copy of ${playlist.name} was unreadable; refetching")
            runCatching { file.delete() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Downloads and parses in a single pass, teeing the body into a gzip file as it goes.
     *
     * Storing the copy costs one pass rather than a second download, and compresses well because
     * playlist text is extremely repetitive — a 60 MB provider list lands at a few megabytes.
     */
    private fun streamAndParse(playlist: RemotePlaylist, onProgress: (M3uLoadProgress) -> Unit): List<MediaItem> {
        val target = cacheFile(playlist.id)
        val temp = target?.let { File(it.parentFile, "${it.name}.tmp") }
        val sink = temp?.let {
            runCatching { GZIPOutputStream(BufferedOutputStream(FileOutputStream(it), 64 * 1024)) }.getOrNull()
        }
        var parsedCleanly = false
        onProgress(M3uLoadProgress("Connecting to ${playlist.name}…", 0f))
        try {
            openPlaylist(playlist.url).use { response ->
                val body = response.body ?: throw IllegalStateException("Empty playlist response.")
                val total = body.contentLength().takeIf { it > 0L }
                val counting = CountingInputStream(body.byteStream(), sink)
                // A large read buffer matters: at ~2 lines per channel this reader is asked for a
                // line 400k times for one big playlist.
                val reader = BufferedReader(InputStreamReader(counting, StandardCharsets.UTF_8), 128 * 1024)
                var lastReported = 0L
                val items = parseM3uLines(reader.lineSequence(), playlist.id, playlist.name) { parsed ->
                    val read = counting.bytesRead
                    if (read - lastReported >= 512 * 1024) {
                        lastReported = read
                        // Held below 1.0 while bytes are still arriving: a bar that sits full for
                        // the rest of a long parse is worse than one that never quite gets there.
                        val fraction = total?.let { (read.toDouble() / it).toFloat().coerceIn(0f, 0.99f) }
                        onProgress(M3uLoadProgress("Reading ${playlist.name}: ${parsed.formatted()} channels found", fraction, parsed))
                    }
                }
                if (counting.bytesRead == 0L) throw IllegalStateException("Empty playlist response.")
                parsedCleanly = true
                onProgress(M3uLoadProgress("${playlist.name}: ${items.size.formatted()} channels", 1f, items.size))
                TvDebugLogger.i(TAG, "parsed ${items.size} entries from ${playlist.name}")
                return items
            }
        } finally {
            // Only a body that parsed all the way through is worth keeping: a truncated response or
            // a provider error page must not become the copy served next launch. The finished file
            // replaces the previous one in one move, so a failure here leaves the older — still
            // valid — copy exactly as it was.
            runCatching { sink?.close() }
            if (temp != null && target != null) {
                if (parsedCleanly && temp.isFile && temp.length() > 0L) {
                    target.delete()
                    if (!temp.renameTo(target)) {
                        temp.delete()
                        TvDebugLogger.w(TAG, "could not store a copy of ${playlist.name}")
                    }
                } else {
                    temp.delete()
                }
            }
        }
    }

    /** Opens the playlist, retrying once with a player user-agent on an auth-style rejection. */
    private fun openPlaylist(url: String): Response {
        fun call(userAgent: String) = http
            .newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())
            .execute()

        var response = call("StreamDekTV/${BuildConfig.VERSION_NAME}")
        if (response.code == 401 || response.code == 403) {
            response.close()
            response = call(PLAYER_USER_AGENT)
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IllegalStateException("Playlist request failed: $code")
        }
        return response
    }
}

// ── Parsing ──────────────────────────────────────────────────────────────────────────────────────

private val m3uTvgLogoRegex = Regex("tvg-logo=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
private val m3uGroupTitleRegex = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
private val m3uMediaTypeRegex = Regex("(?:tvg-type|media-type|content-type|type)=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
private val m3uDurationRegex = Regex("^#EXTINF:([\\d.-]+)", RegexOption.IGNORE_CASE)
private val m3uEpisodePattern = Regex("(?:^|[ ._\\-])S\\d{1,2}E\\d{1,3}(?:$|[ ._\\-])", RegexOption.IGNORE_CASE)
private val m3uVodExtensions = setOf("mp4", "m4v", "mkv", "avi", "mov", "webm", "wmv")
private val m3uVodTypeMarkers = listOf("vod", "movie", "film", "series", "episode", "show")
private val m3uVodCategoryMarkers = listOf("vod", "movies", "movie", "films", "film", "series", "tv shows", "episodes")

/** `url|User-Agent=…&Referer=…`, the convention IPTV providers use to pin playback headers. */
private fun parseInlineM3uHeaders(raw: String): Pair<String, Map<String, String>> {
    val url = raw.substringBefore('|').trim()
    val encodedHeaders = raw.substringAfter('|', "")
    if (encodedHeaders.isBlank()) return url to emptyMap()
    val headers = linkedMapOf<String, String>()
    encodedHeaders.split('&').forEach { pair ->
        val key = pair.substringBefore('=', "").trim()
        val value = pair.substringAfter('=', "").trim()
        if (key.isBlank() || value.isBlank()) return@forEach
        val headerName = when (key.lowercase()) {
            "user-agent", "useragent" -> "User-Agent"
            "referer", "referrer" -> "Referer"
            "origin" -> "Origin"
            else -> key
        }
        headers[headerName] = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)
    }
    return url to headers
}

/**
 * The path component of an absolute URL, lowercased, without allocating a URL object.
 *
 * At 200k entries, constructing a parser per entry dominates parse time on a TV box — and for the
 * malformed links providers ship, throwing from one costs more still.
 */
private fun m3uUrlPath(url: String): String {
    val schemeEnd = url.indexOf("://")
    val afterAuthority = url.indexOf('/', if (schemeEnd >= 0) schemeEnd + 3 else 0)
    if (afterAuthority < 0) return ""
    var end = url.length
    for (index in afterAuthority until url.length) {
        val char = url[index]
        if (char == '?' || char == '#') {
            end = index
            break
        }
    }
    return url.substring(afterAuthority, end).lowercase()
}

/**
 * Whether an entry is on-demand rather than a live channel.
 *
 * Each test runs only if the cheaper ones ahead of it did not already decide — this is evaluated
 * once per entry, so eagerly computing three lowercased copies would be paid for 200k times.
 */
private fun isM3uVodEntry(title: String, group: String?, declaredType: String?, duration: Double?, url: String): Boolean {
    if (duration != null && duration > 0.0) return true
    if (!declaredType.isNullOrEmpty()) {
        val typeText = declaredType.lowercase()
        if (m3uVodTypeMarkers.any { it in typeText }) return true
    }
    val categoryText = if (group.isNullOrEmpty()) title.lowercase() else "$group $title".lowercase()
    if (m3uVodCategoryMarkers.any { it in categoryText }) return true
    if (m3uEpisodePattern.containsMatchIn(title)) return true
    val path = m3uUrlPath(url)
    if (path.substringAfterLast('/').substringAfterLast('.', "") in m3uVodExtensions) return true
    return "/movie/" in path || "/series/" in path
}

/**
 * Parses live and on-demand entries out of an extended M3U.
 *
 * Takes lines rather than a body so a playlist can be parsed straight off the socket without ever
 * existing in memory as one string. Repeated attribute values are interned: a provider groups its
 * channels into a few hundred categories, and the derived description and catalog-name strings are
 * built from those, so without interning a 200k-entry playlist holds close to a million
 * near-duplicate strings.
 */
internal fun Int.formatted(): String = String.format("%,d", this)

internal fun parseM3uLines(
    lines: Sequence<String>,
    playlistId: String,
    playlistName: String,
    onProgress: (itemsParsed: Int) -> Unit = {},
): List<MediaItem> {
    val items = mutableListOf<MediaItem>()
    var pendingTitle: String? = null
    var pendingLogo: String? = null
    var pendingGroup: String? = null
    var pendingMediaType: String? = null
    var pendingDuration: Double? = null
    val pendingHeaders = linkedMapOf<String, String>()
    var index = 0
    var processedLines = 0
    var charactersSeen = 0L
    // Providers that answer a bad token with an HTML error page still return 200, and every line of
    // markup that is not a comment looks exactly like a stream URL. Nothing is emitted until the
    // playlist announces itself, and a body that never does is abandoned rather than read to the
    // end looking for a marker that is not coming.
    var sawPlaylistMarker = false

    val pool = HashMap<String, String>()
    fun intern(value: String): String = pool.getOrPut(value) { value }

    lines.forEach { rawLine ->
        processedLines += 1
        charactersSeen += rawLine.length + 1
        // A UTF-8 BOM would otherwise leave the first line as "﻿#EXTM3U" and defeat the check.
        val line = (if (processedLines == 1) rawLine.removePrefix("﻿") else rawLine).trim()
        if (!sawPlaylistMarker && charactersSeen > 256 * 1024) return emptyList()
        when {
            line.isEmpty() -> Unit
            line.startsWith("#EXTINF", ignoreCase = true) -> {
                sawPlaylistMarker = true
                val comma = line.indexOf(',')
                pendingTitle = if (comma >= 0) line.substring(comma + 1).trim().takeIf { it.isNotEmpty() } else null
                pendingLogo = m3uTvgLogoRegex.find(line)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
                pendingGroup = m3uGroupTitleRegex.find(line)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.let(::intern)
                pendingMediaType = m3uMediaTypeRegex.find(line)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.let(::intern)
                pendingDuration = m3uDurationRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull()
                pendingHeaders.clear()
            }
            line.startsWith("#EXTM3U", ignoreCase = true) -> sawPlaylistMarker = true
            line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) ->
                pendingHeaders["User-Agent"] = line.substringAfter('=').trim()
            line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) ||
                line.startsWith("#EXTVLCOPT:http-referer=", ignoreCase = true) ->
                pendingHeaders["Referer"] = line.substringAfter('=').trim()
            line.startsWith("#EXTVLCOPT:http-origin=", ignoreCase = true) ->
                pendingHeaders["Origin"] = line.substringAfter('=').trim()
            line.startsWith("#") -> Unit
            else -> {
                // Anything before the playlist announces itself is not an entry, whatever it looks
                // like. This is what keeps a login page from parsing into 80 unplayable channels.
                if (!sawPlaylistMarker) return@forEach
                val (streamUrl, inlineHeaders) = parseInlineM3uHeaders(line)
                if (streamUrl.isEmpty()) return@forEach
                val title = pendingTitle ?: "Item ${index + 1}"
                val group = pendingGroup
                val isVod = isM3uVodEntry(title, group, pendingMediaType, pendingDuration, streamUrl)
                items.add(
                    MediaItem(
                        id = "$playlistId:${if (isVod) "vod" else "live"}:${index++}",
                        title = title,
                        // On-demand entries are movies; everything else is a live channel, which is
                        // what puts them in front of the live player rather than a detail page.
                        type = if (isVod) "movie" else "live",
                        poster = pendingLogo,
                        backdrop = pendingLogo,
                        titleLogo = pendingLogo,
                        description = if (group == null) playlistName else intern("$group • $playlistName"),
                        streamType = if (isVod) "movie" else "tv",
                        sourceAddonId = playlistId,
                        sourceAddonName = playlistName,
                        sourceCatalogId = intern("$playlistId:${group ?: "all"}"),
                        sourceCatalogName = group ?: playlistName,
                        directStreamUrl = streamUrl,
                        requestHeaders = if (pendingHeaders.isEmpty() && inlineHeaders.isEmpty()) {
                            emptyMap()
                        } else {
                            LinkedHashMap<String, String>(pendingHeaders).apply { putAll(inlineHeaders) }
                        },
                    ),
                )
                pendingTitle = null
                pendingLogo = null
                pendingGroup = null
                pendingMediaType = null
                pendingDuration = null
                pendingHeaders.clear()
                // Reported off the entry count rather than per line: the caller throttles on bytes
                // or thousands of items, so this stays a comparison in the hot loop.
                onProgress(index)
            }
        }
    }
    return items
}

/**
 * Wraps a stream so bytes read can be counted and copied to [sink] on the way past.
 *
 * A sink that fails is dropped rather than propagated: not being able to store a copy of the
 * playlist is no reason to fail a load that is otherwise succeeding.
 */
private class CountingInputStream(delegate: InputStream, private var sink: OutputStream?) : FilterInputStream(delegate) {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int = super.read().also { value ->
        if (value >= 0) {
            bytesRead += 1
            copy { it.write(value) }
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { count ->
            if (count > 0) {
                bytesRead += count
                copy { it.write(b, off, count) }
            }
        }

    private inline fun copy(block: (OutputStream) -> Unit) {
        val target = sink ?: return
        runCatching { block(target) }.onFailure { sink = null }
    }
}
