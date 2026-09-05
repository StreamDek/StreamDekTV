package com.streamdek.tv.nativeapp.data

import androidx.annotation.StringRes
import com.google.gson.JsonObject
import com.streamdek.tv.BuildConfig
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.debrid.DebridKeyStore
import com.streamdek.tv.nativeapp.debrid.DebridManager
import com.streamdek.tv.nativeapp.debrid.PremiumizeClient
import com.streamdek.tv.nativeapp.debrid.PremiumizeDeviceAuth
import com.streamdek.tv.nativeapp.debrid.RealDebridClient
import com.streamdek.tv.nativeapp.debrid.RealDebridDeviceAuth
import com.streamdek.tv.nativeapp.debrid.SUPPORTED_DEBRID_PROVIDERS
import com.streamdek.tv.nativeapp.peer.LocalTorrentPlayback
import com.streamdek.tv.nativeapp.usenet.UsenetPlayback
import java.io.File
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.LinkedHashMap
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

// Stremio-native catalog types that represent live content. Native 'tv' means
// live television channels — series catalogs use 'series'.
private val LIVE_ADDON_CATALOG_TYPES = setOf(
    "tv", "channel", "channels", "event", "events", "live", "sport", "sports", "other",
)

/** How often a running television asks whether the plugin document moved somewhere else. */
/**
 * What a subtitle download presents itself as.
 *
 * The same reasoning as the plugin sandbox's: a request that does not look like a browser is
 * refused outright by some of the hosts these add-ons point at, and a refusal arrives as a
 * subtitle that simply never appears.
 */
private const val SUBTITLE_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Safari/537.36"

private const val PLUGIN_WATCH_INTERVAL_MS = 15_000L

private const val MAX_ADDON_RAIL_TITLE_LENGTH = 30
private const val ADDON_CACHE_SECONDS = 90
private const val ADDON_CACHE_BYTES = 12L * 1024L * 1024L
private const val WATCHLIST_MUTATION_GRACE_MS = 60_000L
private const val CONTINUE_DISMISSAL_GRACE_MS = 60_000L

internal fun sameWatchlistTitle(left: MediaItem, right: MediaItem): Boolean {
    fun normalizedType(item: MediaItem): String = when (item.type.trim().lowercase(Locale.US)) {
        "series", "show" -> "tv"
        else -> item.type.trim().lowercase(Locale.US)
    }
    fun canonicalId(item: MediaItem): String {
        if (item.tmdbId > 0) return "tmdb:${item.tmdbId}"
        val raw = item.id.trim().lowercase(Locale.US)
        val tmdb = Regex("^(?:tmdb:)?(\\d+)$").matchEntire(raw)?.groupValues?.getOrNull(1)
        return tmdb?.let { "tmdb:$it" } ?: raw
    }
    return normalizedType(left) == normalizedType(right) && canonicalId(left) == canonicalId(right)
}

internal fun mutateWatchlistSnapshot(
    current: List<MediaItem>,
    item: MediaItem,
    remove: Boolean,
): List<MediaItem> = if (remove) {
    current.filterNot { sameWatchlistTitle(it, item) }
} else if (current.any { sameWatchlistTitle(it, item) }) {
    current
} else {
    listOf(item) + current
}

internal fun sameContinueWatchingItem(entry: ContinueWatchingItem, item: MediaItem): Boolean {
    fun normalizedType(value: String): String = when (value.trim().lowercase(Locale.US)) {
        "series", "show" -> "tv"
        else -> value.trim().lowercase(Locale.US)
    }
    fun canonicalId(rawId: String, tmdbId: Int): String {
        if (tmdbId > 0) return "tmdb:$tmdbId"
        val normalized = rawId.trim().lowercase(Locale.US)
        val parsedTmdb = Regex("^(?:tmdb:)?(\\d+)$").matchEntire(normalized)?.groupValues?.getOrNull(1)
        return parsedTmdb?.let { "tmdb:$it" } ?: normalized
    }
    val entryEpisode = entry.exactEpisode()
    val itemEpisode = item.episode
    return normalizedType(entry.type) == normalizedType(item.type) &&
        canonicalId(entry.id, entry.tmdbId) == canonicalId(item.id, item.tmdbId) &&
        entryEpisode?.seasonNumber == itemEpisode?.seasonNumber &&
        entryEpisode?.episodeNumber == itemEpisode?.episodeNumber
}

internal fun removeContinueWatchingSnapshot(
    current: List<ContinueWatchingItem>,
    item: MediaItem,
): List<ContinueWatchingItem> = current.filterNot { sameContinueWatchingItem(it, item) }

internal fun orderedConnectedSyncServices(primary: String, connected: Set<String>): List<String> =
    (listOf(SyncServiceId.normalize(primary)) + SyncServiceId.all)
        .distinct()
        .filter(connected::contains)

/** Give add-on answers the same short cache lifetime used by mobile unless the add-on sets one. */
private object AddonResponseCacheInterceptor : okhttp3.Interceptor {
    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return response
        val declared = response.header("Cache-Control").orEmpty()
        val declaredPolicy = listOf("no-store", "max-age", "s-maxage").any { declared.contains(it, ignoreCase = true) }
        if (declaredPolicy) return response
        return response.newBuilder()
            .header("Cache-Control", "private, max-age=$ADDON_CACHE_SECONDS")
            .removeHeader("Pragma")
            .build()
    }
}

private fun JsonObject.streamString(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
    get(name)?.takeUnless { it.isJsonNull }?.let { element ->
        runCatching { element.asString.trim().takeIf { it.isNotBlank() && it != "null" } }.getOrNull()
    }
}

private fun JsonObject.streamStringMap(): Map<String, String> = entrySet().mapNotNull { (key, value) ->
    runCatching { value.asString.trim().takeIf(String::isNotBlank)?.let { key to it } }.getOrNull()
}.toMap()

/** Parses loose Stremio stream JSON without dropping nested direct URLs or proxy headers. */
internal fun parseAddonStreamsPayload(raw: String): List<AddonStream> {
    val root = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return emptyList()
    val streams = root.getAsJsonArray("streams") ?: return emptyList()
    return streams.mapNotNull { element ->
        val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        // One malformed provider row must not discard every other result in a large AIO response.
        runCatching {
            val behavior = item.get("behaviorHints")?.takeIf { it.isJsonObject }?.asJsonObject
            fun objectValue(parent: JsonObject?, name: String): JsonObject? =
                parent?.get(name)?.takeIf { it.isJsonObject }?.asJsonObject
            fun urlValue(value: com.google.gson.JsonElement?): String? = when {
                value == null || value.isJsonNull -> null
                value.isJsonPrimitive -> runCatching { value.asString.trim().takeIf(String::isNotBlank) }.getOrNull()
                value.isJsonObject -> value.asJsonObject.streamString("url", "href")
                else -> null
            }
            val url = urlValue(item.get("url"))
                ?: urlValue(item.get("externalUrl"))
                ?: urlValue(behavior?.get("url"))
            val infoHash = item.streamString("infoHash") ?: url?.takeIf { it.startsWith("magnet:?", true) }
                ?.let { Regex("btih:([A-Fa-f0-9]{32,40})").find(it)?.groupValues?.getOrNull(1) }
            val requestHeaders = buildMap {
                objectValue(objectValue(behavior, "proxyHeaders"), "request")?.streamStringMap()?.let(::putAll)
                objectValue(behavior, "requestHeaders")?.streamStringMap()?.let(::putAll)
                objectValue(item, "headers")?.streamStringMap()?.let(::putAll)
                objectValue(item, "requestHeaders")?.streamStringMap()?.let(::putAll)
            }
            fun stringList(vararg names: String): List<String> {
                val array = names.firstNotNullOfOrNull { name ->
                    item.get(name)?.takeIf { it.isJsonArray }?.asJsonArray
                } ?: return emptyList()
                return array.mapNotNull { runCatching { it.asString.trim().takeIf(String::isNotBlank) }.getOrNull() }
            }
            val declaredCachedBy = stringList("cachedBy")
            val providerText = listOf(
                item.streamString("name"),
                item.streamString("title"),
                item.streamString("description"),
                item.streamString("source", "provider"),
            ).filterNotNull().joinToString(" ").lowercase(Locale.US)
            // AIOStreams and similar aggregators frequently turn a debrid hit into a direct URL
            // and name the service in the row, but omit Stremio's non-standard `cachedBy` field.
            // A provider-labelled direct link is already available from that service, so retain
            // that fact for cached-first ranking and for the picker attribution.
            val inferredCachedBy = if (declaredCachedBy.isEmpty() &&
                url?.let { it.startsWith("http://", true) || it.startsWith("https://", true) } == true
            ) {
                buildList {
                    if (Regex("\\bdeep[ -]?brid\\b").containsMatchIn(providerText)) add("deepbrid")
                    if (Regex("\\breal[ -]?debrid\\b|\\[rd\\+?]", RegexOption.IGNORE_CASE).containsMatchIn(providerText)) add("real-debrid")
                    if (Regex("\\ball[ -]?debrid\\b").containsMatchIn(providerText)) add("alldebrid")
                    if (Regex("\\bpremiumize(?:\\.me)?\\b").containsMatchIn(providerText)) add("premiumize")
                    if (Regex("\\btorbox\\b").containsMatchIn(providerText)) add("torbox")
                    if (Regex("\\bdebrid[ -]?link\\b").containsMatchIn(providerText)) add("debrid-link")
                    if (Regex("\\boffcloud\\b").containsMatchIn(providerText)) add("offcloud")
                }
            } else {
                emptyList()
            }
            AddonStream(
                addonId = item.streamString("addonId").orEmpty(),
                addonName = item.streamString("addonName").orEmpty(),
                name = item.streamString("name"),
                title = item.streamString("title"),
                description = item.streamString("description"),
                url = url,
                infoHash = infoHash,
                nzbUrl = item.streamString("nzbUrl", "nzb_url", "nzb"),
                servers = stringList("servers", "nntpServers", "nntp_servers"),
                fileIdx = item.get("fileIdx")?.let { runCatching { it.asInt }.getOrNull() },
                filename = behavior?.streamString("filename") ?: item.streamString("filename"),
                behaviorHints = behavior?.let {
                    BehaviorHints(filename = it.streamString("filename"), bingeGroup = it.streamString("bingeGroup"))
                },
                quality = item.streamString("quality"),
                size = item.streamString("size"),
                cachedBy = declaredCachedBy.ifEmpty { inferredCachedBy },
                bingeGroup = behavior?.streamString("bingeGroup") ?: item.streamString("bingeGroup"),
                source = item.streamString("source", "provider"),
                requestHeaders = requestHeaders,
            )
        }.getOrNull()
    }
}

private fun streamSingleLine(value: String?): String? = value?.trim()
    ?.replace(Regex("\\s+"), " ")
    ?.takeIf(String::isNotBlank)

/** Preserve the provider/debrid wording supplied by the add-on instead of hiding it. */
/**
 * Words that describe a file rather than name it.
 *
 * Several plugin sources answer with nothing but a resolution and a size, and a row reading
 * "1080p | 2.3 GB" is not a result anyone can identify — five of them from one source are
 * indistinguishable from each other on a ten-foot screen.
 */
private val StreamDescriptorTokens = setOf(
    "4k", "uhd", "fhd", "qhd", "hd", "sd", "hq", "lq",
    "hdr", "hdr10", "hdr10+", "dv", "dolby", "vision", "atmos", "dts", "dtshd", "truehd", "ddp", "dd",
    "ac3", "eac3", "aac", "aac2", "opus", "flac", "mp3", "mp4", "mkv", "m3u8", "avi", "mpd",
    "x264", "x265", "h264", "h265", "hevc", "avc", "av1", "10bit", "8bit",
    "web", "webdl", "webrip", "bluray", "brrip", "bdrip", "dvdrip", "hdrip", "hdtv", "remux", "proper", "repack",
    "cam", "camrip", "ts", "tc", "telesync", "telecine", "multi", "dual", "audio", "sub", "subs", "subbed", "dubbed",
    "gb", "mb", "tb", "gib", "mib", "tib", "kb", "kbps", "mbps", "fps", "ch", "bit",
    "auto", "unknown", "stream", "link", "video", "file", "size", "quality", "source",
)

private val StreamWordSplitPattern = Regex("[^\\p{L}\\p{N}+]+")
private val StreamResolutionTokenPattern = Regex("\\d{2,4}p")
private val StreamNumberTokenPattern = Regex("\\d+")

/**
 * Whether [value] contains anything that names the thing being watched.
 *
 * A token counts if it is not a descriptor, not a bare number and not a resolution. Two characters
 * is the floor, so "4k" and separators do not rescue an otherwise empty label.
 */
internal fun streamTextNamesTitle(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    return value.lowercase(Locale.US)
        .split(StreamWordSplitPattern)
        .any { token ->
            token.length > 1 &&
                token !in StreamDescriptorTokens &&
                !StreamResolutionTokenPattern.matches(token) &&
                !StreamNumberTokenPattern.matches(token)
        }
}

/**
 * The same label, with the title being watched put in front when the source named nothing.
 *
 * Only the screens that know which title is on offer can supply [fallbackName], which is why this
 * is a separate entry point rather than a change to the label everything else uses. Nothing is
 * hidden either way: a result that cannot describe itself still appears, under the name of the
 * thing it is a copy of.
 */
internal fun addonStreamDisplayLabel(stream: AddonStream, fallbackName: String): String {
    val label = addonStreamDisplayLabel(stream)
    if (streamTextNamesTitle(label)) return label
    return listOf(fallbackName, label).filter { it.isNotBlank() }.joinToString(" | ")
}

internal fun addonStreamDisplayLabel(stream: AddonStream): String = listOfNotNull(
    streamSingleLine(stream.name),
    streamSingleLine(stream.source)?.takeUnless { it.equals(stream.addonName, ignoreCase = true) },
    streamSingleLine(stream.title),
    streamSingleLine(stream.description),
    streamSingleLine(stream.behaviorHints?.filename ?: stream.filename),
    streamSingleLine(stream.quality),
    streamSingleLine(stream.size),
).distinct().take(3).joinToString(" | ").ifBlank {
    stream.addonName.takeIf(String::isNotBlank) ?: "Selected stream"
}

private val StreamQualityPattern = Regex("""(2160p|4k|uhd|1080p|720p|480p)""", RegexOption.IGNORE_CASE)

/** Most add-ons embed quality in display text instead of the optional Stremio quality field. */
internal fun inferredStreamQuality(stream: AddonStream): String? {
    stream.quality?.trim()?.takeIf(String::isNotBlank)?.let { return it }
    val evidence = listOfNotNull(
        stream.name,
        stream.title,
        stream.description,
        stream.behaviorHints?.filename,
        stream.filename,
    ).joinToString(" ")
    return StreamQualityPattern.find(evidence)?.value
}

internal fun preferredQualityScore(quality: String?, preferredQuality: String): Int {
    val preference = preferredQuality.trim().lowercase(Locale.US)
    if (preference == "best" || preference == "auto") return 0
    val normalized = quality.orEmpty().lowercase(Locale.US)
    val is4k = "2160" in normalized || "4k" in normalized || "uhd" in normalized
    val is1080 = "1080" in normalized
    val is720 = "720" in normalized
    return when (preference) {
        "4k", "2160p" -> when {
            is4k -> 4
            is1080 -> 3
            is720 -> 2
            else -> 1
        }
        "1080p" -> when {
            is1080 -> 4
            is720 -> 3
            is4k -> 2
            else -> 1
        }
        "720p" -> when {
            is720 -> 4
            is1080 -> 3
            is4k -> 2
            else -> 1
        }
        else -> 0
    }
}

internal fun parseQualityScore(quality: String?): Int {
    val normalized = quality.orEmpty().lowercase(Locale.US)
    return when {
        "2160" in normalized || "4k" in normalized || "uhd" in normalized -> 4
        "1080" in normalized -> 3
        "720" in normalized -> 2
        normalized.isNotBlank() -> 1
        else -> 0
    }
}

/** The picker contract: cached first, then the user's preferred quality. */
internal fun cacheThenQualityComparator(preferredQuality: String): Comparator<AddonStream> =
    compareByDescending<AddonStream> { it.cachedBy.isNotEmpty() }
        .thenByDescending { preferredQualityScore(inferredStreamQuality(it), preferredQuality) }
        .thenByDescending { parseQualityScore(inferredStreamQuality(it)) }

/**
 * A usenet result: an NZB pointer plus the news servers to fetch it from, with no direct url and
 * no info hash. AIOStreams returns these alongside ordinary results, and for some titles they are
 * nearly all of them.
 */
internal fun isUsenetAddonStream(stream: AddonStream): Boolean = !stream.nzbUrl.isNullOrBlank()

/**
 * Whether a catalog entry is an add-on reporting a failure rather than describing a real title.
 *
 * AIOStreams drops a card into the row whenever one of the catalog providers behind it errors —
 * "[❌] Bingecat / Failed to parse meta for Bingecat" — under its own `aiostreamserror` id prefix,
 * which its manifest openly declares. That is a diagnostic for the add-on's operator, not
 * something to put on a viewer's home screen: it has no artwork and opening it leads nowhere.
 */
internal fun isPlaceholderCatalogMeta(id: String, name: String?): Boolean {
    if (id.isBlank() || id.equals("null", ignoreCase = true)) return true
    if (id.startsWith("aiostreamserror", ignoreCase = true)) return true
    return name?.trim()?.startsWith("[❌]") == true
}

/**
 * Whether an add-on's `meta` answer actually describes the item that was asked for.
 *
 * Advertising a `meta` resource is not the same as being able to serve one: AIOStreams answers
 * every call with a placeholder whose id encodes the failure ("aiostreamserror.…") and whose name
 * is the error text. A usable answer keeps an id that could still drive a lookup — the id that was
 * requested, or an IMDb id — or brings episodes with it.
 */
internal fun isUsableAddonMeta(meta: AddonMetaItem, requestedId: String): Boolean {
    if (meta.name.isNullOrBlank()) return false
    if (!meta.imdbId.isNullOrBlank()) return true
    if (meta.videos.isNotEmpty()) return true
    return meta.id?.equals(requestedId, ignoreCase = true) == true
}

/** Add-on rows are identified by prefix so they can be ordered as a group. */
private const val ADDON_RAIL_PREFIX = "addon:"

/**
 * Display order of the Home slots, independent of the order they finish loading in.
 *
 * "catalog-rows" is the backend catalog registry's whole set, which arrives in one response and so
 * occupies one slot however many rows it turns out to hold. The named rows below it are the
 * pre-registry defaults, reached only when a backend has no registry to offer.
 */
private val HOME_SLOT_ORDER = listOf(
    "continue-watching",
    "catalog-rows",
    "popular-movies",
    "popular-series",
    "trending",
    "recently-added",
    "networks",
    "recommended",
    "addon-catalogs",
)

/** How long the catalog registry is held for. It changes on backend deploys, not minute to minute. */
private const val CATALOG_MANIFEST_TTL_MS = 6L * 60L * 60L * 1000L

/** The registry's id for the service-tile row, which is laid out differently from a title row. */
private const val NETWORKS_CATALOG_ID = "streaming_networks"

/**
 * Resolved playback URLs (debrid links, addon direct links) expire quickly, so cached
 * candidates are only reused for a short window before being re-resolved.
 */
private const val RESOLVED_PLAYBACK_CACHE_TTL_MS = 3 * 60_000L

/**
 * How long a remembered source URL is worth trying before the player resolves from scratch instead.
 *
 * Deliberately long. Debrid links and cookie-signed CDN URLs outlive this often enough to be worth
 * it, and a URL that has gone stale costs one failed request that the player already knows how to
 * recover from — against which the alternative is making every single remembered resume pay for a
 * full stream lookup.
 */
internal const val RememberedSourceTtlMs = 12L * 60 * 60 * 1000
internal fun effectiveRememberedStreamKey(
    explicitKey: String?,
    storedKey: String?,
    rememberLastSource: Boolean,
): String? = explicitKey ?: storedKey.takeIf { rememberLastSource }

/** Identity used while progressively merging results from independently completing add-ons. */
internal fun streamAggregationKey(stream: AddonStream): String = listOf(
    stream.addonId,
    stream.addonName,
    stream.name,
    stream.title,
    stream.description,
    stream.infoHash?.lowercase(Locale.US),
    stream.url?.trim(),
    stream.nzbUrl?.trim(),
    stream.servers.joinToString("\u001e"),
    stream.fileIdx,
    stream.filename?.trim(),
    stream.behaviorHints?.filename?.trim(),
    stream.behaviorHints?.bingeGroup?.trim(),
    stream.quality,
    stream.size,
    stream.bingeGroup,
    stream.source,
    stream.requestHeaders.toSortedMap().entries.joinToString("\u001e") { "${it.key}=${it.value}" },
).joinToString("|")

/**
 * A progressive stream page is append-only for the lifetime of one lookup. If concurrent
 * publishers arrive out of order, retain anything already shown and let the newest snapshot
 * determine the ordering of entries it contains.
 */
internal fun mergeProgressiveStreamSnapshot(
    previous: List<AddonStream>,
    incoming: List<AddonStream>,
): List<AddonStream> {
    val incomingKeys = incoming.mapTo(linkedSetOf(), ::streamAggregationKey)
    return incoming + previous.filter { streamAggregationKey(it) !in incomingKeys }
}

/** Applies a cache-check answer without changing stream identity or dropping provider results. */
internal fun applyCachedProviders(
    streams: List<AddonStream>,
    cachedByHash: Map<String, List<String>>,
): List<AddonStream> = streams.map { stream ->
    if (stream.cachedBy.isNotEmpty()) return@map stream
    val hash = stream.infoHash?.trim()?.lowercase(Locale.US)
        ?: stream.url?.takeIf { it.startsWith("magnet:?", ignoreCase = true) }
            ?.let { Regex("btih:([A-Fa-f0-9]{32,40})").find(it)?.groupValues?.getOrNull(1)?.lowercase(Locale.US) }
    val providers = hash?.let(cachedByHash::get).orEmpty()
    if (providers.isEmpty()) stream else stream.copy(cachedBy = providers)
}


internal fun playbackRequestFromHandoff(payload: PlaybackHandoffPayload): PlaybackRequest {
    require(payload.mediaId.isNotBlank()) { "The handoff did not include a media id." }
    val episode = if (payload.seasonNumber != null && payload.episodeNumber != null) {
        EpisodeContext(payload.seasonNumber, payload.episodeNumber, title = payload.episodeTitle)
    } else {
        null
    }
    val stream = payload.stream
    return PlaybackRequest(
        mediaId = payload.mediaId,
        mediaType = payload.mediaType,
        imdbId = payload.imdbId,
        episode = episode,
        title = payload.title,
        selectedStreamKey = null,
        selectedStreamLabel = payload.sourceLabel ?: payload.quality,
        selectedStream = stream,
        availableStreams = listOf(stream),
        directStreamUrl = stream.url,
        requestHeaders = stream.requestHeaders,
        startPositionSec = payload.positionSeconds.coerceAtLeast(0.0),
        returnToDetailOnBack = false,
    )
}
/**
 * Raised when a screen has nothing to show *and* the backend could not be reached, so the UI can
 * offer a retry instead of presenting an outage as an empty catalog.
 */
class ContentUnavailableException(
    message: String = "StreamDek could not be reached. Check the connection and try again.",
) : Exception(message)

private data class AddonCatalogCollection(
    val addonId: String,
    val addonName: String,
    val rawType: String,
    val catalogId: String,
    val catalogName: String?,
    val items: List<MediaItem>,
)

/** Maps a Stremio-native catalog type to the app-internal type, or null when unsupported. */
fun mapAddonCatalogType(rawType: String): String? = when {
    rawType == "movie" -> "movie"
    // Anime is published as its own catalog type by a good number of add-ons and is series-shaped
    // in every other respect — episodes, seasons, a `series` meta resource. Dropping it meant those
    // rows simply never appeared, with nothing on screen to say why.
    rawType == "series" || rawType == "anime" -> "tv"
    rawType in LIVE_ADDON_CATALOG_TYPES -> "live"
    else -> null
}

/**
 * How a row says which half of a two-type catalog it is.
 *
 * Add-ons routinely publish one catalog under both `movie` and `series` with the same name and the
 * same id — AIOStreams alone ships ten such pairs, Xperience another handful — so the two arrive as
 * identical rows stacked one above the other. Only added when a title is genuinely ambiguous; a
 * catalog that exists under one type only keeps the name its author gave it.
 */
private fun addonRailTypeSuffix(rawType: String): String = when {
    rawType == "movie" -> "Movies"
    rawType == "series" -> "Series"
    rawType == "anime" -> "Anime"
    rawType in LIVE_ADDON_CATALOG_TYPES -> "Live"
    else -> rawType.replaceFirstChar { it.uppercase(Locale.US) }
}

/** AIOStreams exposes provider failures as synthetic meta items; they are not playable titles. */
internal fun isAddonCatalogDiagnosticMeta(meta: AddonCatalogMetaItem): Boolean {
    val id = meta.id.orEmpty().trim()
    val name = meta.name.orEmpty().trim()
    return id.startsWith("aiostreamserror", ignoreCase = true) ||
        (meta.poster.isNullOrBlank() && name.contains("AIOStreams", ignoreCase = true) &&
            name.contains("Error", ignoreCase = true))
}

private fun truncateAtWordBoundary(text: String, maxLength: Int): String {
    if (text.length <= maxLength) return text
    val cut = text.take(maxLength - 1)
    val lastSpace = cut.lastIndexOf(' ')
    val trimmed = if (lastSpace > maxLength / 2) cut.take(lastSpace) else cut
    return trimmed.trimEnd() + "…"
}

fun buildAddonRailTitle(addonName: String, catalogName: String?, typeSuffix: String? = null): String {
    val addon = addonName.trim()
    val catalog = catalogName?.trim().orEmpty()
    val suffix = typeSuffix?.trim()?.takeIf { it.isNotEmpty() }
    // The suffix is never the part that gets cut: it is the only thing telling two otherwise
    // identical rows apart.
    val budget = MAX_ADDON_RAIL_TITLE_LENGTH - (suffix?.let { it.length + 1 } ?: 0)
    fun finish(base: String) = listOfNotNull(base.takeIf { it.isNotBlank() }, suffix).joinToString(" ")
    if (catalog.isBlank()) return finish(truncateAtWordBoundary(addon, budget))
    // Skip the addon prefix when the catalog name already identifies it.
    if (addon.isBlank() || catalog.contains(addon, ignoreCase = true)) {
        return finish(truncateAtWordBoundary(catalog, budget))
    }
    val combined = "$addon - $catalog"
    if (combined.length <= budget) return finish(combined)
    // Prefer the more descriptive catalog name over a truncated combination.
    return finish(truncateAtWordBoundary(catalog, budget))
}

private fun buildLiveRailTitle(rawType: String, catalogName: String?): String {
    val catalog = catalogName?.trim().orEmpty()
    if (catalog.isNotBlank()) {
        return truncateAtWordBoundary(catalog, MAX_ADDON_RAIL_TITLE_LENGTH)
    }
    return when (rawType) {
        "sport", "sports" -> "Sports"
        "event", "events" -> "Live Events"
        else -> "Live TV"
    }
}

class StreamDekRepository(
    private val sessionStore: AuthSessionStore,
    private val api: StreamDekApi = StreamDekApi(sessionStore),
    /**
     * Runs the profile's synced plugin sources. Absent in unit tests and on any build without a
     * Context to hand, in which case plugin results simply do not participate in a lookup.
     */
    private val pluginEngine: PluginSourceEngine? = null,
    /** Application context, for the on-device usenet assembler's cache. Absent in unit tests. */
    private val appContext: android.content.Context? = null,
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Viewer-facing text this repository has to synthesise itself, in the interface language.
     *
     * Only for labels StreamDek generates - a stream it built from a direct URL, say. Anything a
     * provider or add-on supplied is passed through untouched; see AppLanguage.kt.
     *
     * [appContext] is absent in unit tests, and a label is not worth failing a test over, so those
     * take the English source text. It is the same string `values/strings.xml` holds, which is
     * also the platform fallback for every locale.
     */
    private fun label(@StringRes id: Int, fallback: String): String =
        appContext?.let { runCatching { localizedContext(it).getString(id) }.getOrNull() } ?: fallback

    /** Loading a profile's `.cs3` extensions; cancelled when the profile changes under it. */
    private var cloudStreamLoadJob: kotlinx.coroutines.Job? = null
    private val detailsCache = lruCache<String, MediaDetail>(48)
    private val seasonCache = lruCache<String, SeasonDetail>(32)
    private val homeCache = lruCache<String, HomeContent>(4)
    private val libraryCache = lruCache<String, LibraryResponse>(4)
    private data class PendingWatchlistMutation(val item: MediaItem, val remove: Boolean, val recordedAt: Long)
    private val pendingWatchlistMutations = mutableMapOf<String, MutableList<PendingWatchlistMutation>>()
    private val pendingWatchlistLock = Any()
    private data class PendingContinueDismissal(val item: MediaItem, val recordedAt: Long)
    private val pendingContinueDismissals = mutableMapOf<String, MutableList<PendingContinueDismissal>>()
    private val pendingContinueLock = Any()
    private val searchCache = lruCache<String, List<MediaItem>>(16)
    private val addonSearchCache = lruCache<String, List<MediaItem>>(16)
    private val playlistCache = lruCache<String, List<RemotePlaylist>>(4)
    private val networkCache = lruCache<String, PagedRailResponse>(12)
    private val genreCache = lruCache<String, List<GenreItem>>(8)
    private val resolvedPlaybackCache = lruCache<String, ResolvedPlaybackCandidate>(16)
    private val resolvedPlaybackCacheTimes = lruCache<String, Long>(32)

    /** The catalog registry and when it was read. See [fetchCatalogManifest]. */
    @Volatile
    private var catalogManifest: Pair<List<CatalogDefinition>, Long>? = null

    /**
     * Region used for theatrical listings and watch-provider rows. Taken from the television,
     * which is as close to "where the viewer is" as this app knows; the backend falls back to US
     * for a service that does not operate here rather than handing back an empty row.
     */
    private val catalogRegion: String =
        runCatching { Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.US) }
            .getOrNull() ?: "US"

    /**
     * Client used to talk to Stremio addons directly (bypassing the backend), mirroring the
     * mobile app's fresh-stream fetch used when a cached addon link has expired.
     */
    private val directStreamClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(150, java.util.concurrent.TimeUnit.SECONDS)
        .apply {
            appContext?.let { context ->
                runCatching { cache(okhttp3.Cache(File(context.cacheDir, "addon-http"), ADDON_CACHE_BYTES)) }
            }
        }
        .addNetworkInterceptor(AddonResponseCacheInterceptor)
        .build()
    private val episodeSegmentCache = lruCache<String, List<PlaybackSegment>>(32)
    private val movieSegmentCache = lruCache<String, List<PlaybackSegment>>(24)
    private val watchedHistoryCache = lruCache<String, Set<String>>(4)
    private val libraryRevisionState = MutableStateFlow(0L)
    private val bootstrapState = MutableStateFlow<AccountBootstrap?>(null)
    /** Prevent an older bootstrap response from publishing after a newer settings mutation. */
    private val bootstrapRefreshMutex = kotlinx.coroutines.sync.Mutex()
    private val addonEntitlementsMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var addonEntitlementsUserId: String? = null
    @Volatile private var serverSideStreamsEnabled: Boolean = false

    init {
        // Client funnel capture. The backend can see which add-ons were queried and which debrid
        // providers were tried, but only this device knows whether anything actually played.
        Telemetry.configure(api)
        Telemetry.sessionStarted()
    }

    /**
     * The active profile's stored blob exactly as the backend holds it. Kept raw because it also
     * carries keys this client does not model — live favourites, mobile-only layout choices — and
     * a write has to hand the whole thing back without dropping them.
     */
    private val profilePreferencesState = MutableStateFlow(JsonObject())
    private val fusionBadgeSourcesState = MutableStateFlow<Map<String, FusionBadgeSource>>(emptyMap())
    private val favouriteChannelsState = MutableStateFlow(sessionStore.loadFavouriteChannels())
    private var lastPlaybackRequest: PlaybackRequest? = null

    val session: StateFlow<AuthSession?> = sessionStore.session
    val bootstrap: StateFlow<AccountBootstrap?> = bootstrapState
    /** Changes after a local progress/watched mutation so visible library surfaces refresh now. */
    val libraryRevision: StateFlow<Long> = libraryRevisionState

    /** Whether the backend is answering, and whether what is on screen came out of the cache. */
    val reachability: StateFlow<ApiReachability> = api.reachability

    /** Raised once the backend rejects the stored credentials, so the shell can ask for sign-in. */
    val sessionExpired: StateFlow<Boolean> = api.sessionExpired

    /**
     * Why the session ended, when the backend said why -- a suspension, in practice.
     *
     * Shown on the sign-in screen. Without it a banned account is dropped at a sign-in form with
     * no explanation, types the correct password, is refused, and has no way to tell that the
     * refusal is about the account rather than what they typed.
     */
    val sessionEndedMessage: StateFlow<String?> = api.sessionEndedMessage

    /** Clears both, so a fresh pairing attempt does not start under the last one's message. */
    fun clearSessionExpired() = api.clearSessionExpired()

    val fusionBadgeSources: StateFlow<Map<String, FusionBadgeSource>> = fusionBadgeSourcesState
    val favouriteChannels: StateFlow<List<MediaItem>> = favouriteChannelsState

    suspend fun fetchPendingHandoff(): PlaybackHandoff? =
        api.get<PlaybackHandoffEnvelope>("/handoffs/pending")?.handoff

    suspend fun acknowledgeHandoff(id: String, status: String): Boolean =
        api.post<PlaybackHandoffAck>("/handoffs/$id/ack", mapOf("status" to status))?.success == true

    fun acceptHandoff(handoff: PlaybackHandoff): PlaybackRequest {
        handoff.profileId?.takeIf { profileId ->
            bootstrapState.value?.streamProfiles.orEmpty().any { it.id == profileId }
        }?.let(::setActiveStreamProfile)
        val decryptedJson = HandoffCrypto.decryptPayload(handoff.encryptedPayload)
        val payload = api.gson.fromJson(decryptedJson, PlaybackHandoffPayload::class.java)
            ?: throw IllegalArgumentException("The handoff payload could not be read.")
        return playbackRequestFromHandoff(payload)
    }
    fun isFavouriteChannel(item: MediaItem): Boolean = favouriteChannelsState.value.any {
        it.id == item.id && it.sourceAddonId == item.sourceAddonId
    }

    fun toggleFavouriteChannel(item: MediaItem) {
        if (item.type != "live") return
        val current = favouriteChannelsState.value.toMutableList()
        val index = current.indexOfFirst { it.id == item.id && it.sourceAddonId == item.sourceAddonId }
        if (index >= 0) current.removeAt(index) else current.add(0, item)
        sessionStore.saveFavouriteChannels(current)
        favouriteChannelsState.value = current
        syncFavouriteChannels(current)
    }

    private fun reloadFavouriteChannels() {
        favouriteChannelsState.value = sessionStore.loadFavouriteChannels()
    }

    private fun syncFavouriteChannels(items: List<MediaItem>) {
        val profileId = sessionStore.activeProfileId() ?: return
        if (currentSession() == null) return
        repositoryScope.launch {
            val result = api.put<LiveFavouriteChannelsEnvelope>(
                "/profiles/${URLEncoder.encode(profileId, "UTF-8")}/live-favourites",
                mapOf("items" to items),
            )
            if (result == null) {
                TvDebugLogger.w("LiveFavourites", "Cloud sync failed; local favourites retained")
            } else {
                rememberFavouritesInProfileBlob(result)
            }
        }
    }

    /**
     * Favourites live inside the same profile blob as the settings, and a settings write has to
     * resend that blob whole. Keeping the cached copy current stops a settings change made after
     * a favourite was toggled from putting the old list back.
     */
    private fun rememberFavouritesInProfileBlob(envelope: LiveFavouriteChannelsEnvelope) {
        val blob = profilePreferencesState.value.deepCopy()
        blob.add(
            "liveFavouriteChannels",
            api.gson.toJsonTree(mapOf("items" to envelope.items, "updatedAt" to envelope.updatedAt)),
        )
        profilePreferencesState.value = blob
    }

    private suspend fun refreshFavouriteChannelsFromCloud() {
        val profileId = sessionStore.activeProfileId() ?: return
        if (currentSession() == null) return
        val local = sessionStore.loadFavouriteChannels()
        val cloud = api.get<LiveFavouriteChannelsEnvelope>(
            "/profiles/${URLEncoder.encode(profileId, "UTF-8")}/live-favourites",
        ) ?: return
        if (cloud.updatedAt > 0L) {
            sessionStore.saveFavouriteChannels(cloud.items)
            favouriteChannelsState.value = cloud.items
        } else if (local.isNotEmpty()) {
            api.put<LiveFavouriteChannelsEnvelope>(
                "/profiles/${URLEncoder.encode(profileId, "UTF-8")}/live-favourites",
                mapOf("items" to local),
            )
        }
    }
    fun currentSession(): AuthSession? = sessionStore.currentSession()

    /**
     * The source search currently on screen, so it can be retired when it stops being the one the
     * viewer is waiting for.
     *
     * A discovery fans out to every enabled add-on and plugin provider, and the plugin half is by
     * far the longest: one measured lookup ran 49 providers for 113 s. Leaving the picker or
     * choosing a source used to leave all of that running -- on a stick it was still scraping a
     * minute and a half after the film had started, competing for the same CPU and network the
     * decoder needed. Cancelling is therefore not only about wasted work; it is part of how
     * quickly the picture appears.
     *
     * Held as the Job of the in-flight fan-out rather than a generation counter, because the point
     * is to stop the work, not merely to ignore its result.
     */
    @Volatile
    private var activeDiscovery: Job? = null

    /**
     * Retires the in-flight source search.
     *
     * Safe to call when there is none. Called when the viewer picks a source, leaves the picker,
     * or starts a different search -- anything that makes the current results no longer the ones
     * being waited on.
     */
    fun cancelStreamDiscovery(reason: String) {
        activeDiscovery?.let { job ->
            if (job.isActive) {
                TvDebugLogger.i("Streams", "discovery cancelled reason=$reason")
                Perf.playback?.mark("discoveryCancelled", reason)
                job.cancel(kotlinx.coroutines.CancellationException("discovery superseded: $reason"))
            }
        }
        activeDiscovery = null
    }

    fun savePlaybackRequest(request: PlaybackRequest) {
        lastPlaybackRequest = request
    }

    fun currentPlaybackRequest(): PlaybackRequest? = lastPlaybackRequest

    fun subtitleFontSize(): Int = sessionStore.subtitleFontSize()

    fun saveSubtitleFontSize(size: Int) = sessionStore.saveSubtitleFontSize(size)

    fun subtitlePosition(): Int = sessionStore.subtitlePosition()

    fun saveSubtitlePosition(position: Int) = sessionStore.saveSubtitlePosition(position)

    fun consumePlaybackRequest(): PlaybackRequest? = lastPlaybackRequest

    fun peekCachedDetail(id: String, type: String): MediaDetail? {
        val cacheKey = "$type:$id"
        return detailsCache[cacheKey]
    }

    fun peekCachedResolvedPlayback(request: PlaybackRequest): ResolvedPlaybackCandidate? {
        val cacheKey = playbackCacheKey(
            mediaType = request.mediaType,
            mediaId = request.mediaId,
            imdbId = request.imdbId,
            episode = request.episode,
            preferredStreamKey = request.selectedStreamKey,
            streamType = request.streamType,
        )
        return readResolvedPlaybackCache(cacheKey)
    }

    private fun readResolvedPlaybackCache(cacheKey: String): ResolvedPlaybackCandidate? {
        val cached = resolvedPlaybackCache[cacheKey] ?: return null
        val storedAt = resolvedPlaybackCacheTimes[cacheKey] ?: 0L
        if (System.currentTimeMillis() - storedAt > RESOLVED_PLAYBACK_CACHE_TTL_MS) {
            resolvedPlaybackCache.remove(cacheKey)
            resolvedPlaybackCacheTimes.remove(cacheKey)
            return null
        }
        return cached
    }

    private fun writeResolvedPlaybackCache(cacheKey: String, candidate: ResolvedPlaybackCandidate) {
        resolvedPlaybackCache[cacheKey] = candidate
        resolvedPlaybackCacheTimes[cacheKey] = System.currentTimeMillis()
    }

    suspend fun signIn(email: String, password: String): AuthSession {
        val response = api.post<AuthResponse>("/auth/login", mapOf("email" to email, "password" to password), session = null)
            ?: error("Sign in failed")
        val session = persistSession(response)
        refreshBootstrap()
        return session
    }

    suspend fun register(email: String, password: String, displayName: String): AuthSession {
        val response = api.post<AuthResponse>(
            "/auth/register",
            mapOf("email" to email, "password" to password, "displayName" to displayName),
            session = null,
        ) ?: error("Sign up failed")
        val session = persistSession(response)
        refreshBootstrap()
        return session
    }

    suspend fun createTvSession(): TvSessionInfo {
        TvDebugLogger.i("Auth", "createTvSession")
        return api.post<TvSessionInfo>("/auth/tv/session", emptyMap<String, String>(), session = null)
            ?: error("Could not create TV sign-in session")
    }

    suspend fun pollTvSession(deviceCode: String): TvPollResult {
        val result = api.post<TvPollResult>("/auth/tv/token", mapOf("device_code" to deviceCode), session = null)
            ?: TvPollResult(status = "invalid_grant")
        TvDebugLogger.i("Auth", "pollTvSession status=${result.status}")
        return result
    }

    suspend fun completeTvSession(result: TvPollResult): AuthSession {
        val token = result.token ?: error("Missing TV auth token")
        val session = AuthSession(
            token = token,
            user = normalizeUser(result.user, token),
        )
        sessionStore.saveSession(session)
        TvDebugLogger.i("Auth", "completeTvSession user=${session.user.uid}")
        runCatching { refreshBootstrap() }
        return session
    }

    fun signOut() {
        sessionStore.clearSession()
        clearDebridKeys()
        // The content-service keys belonged to the account that just left -- including one kept on
        // this television, which was still that viewer's key and not the box's.
        serviceCredentials.clearAll()
        contentServicesState.value = ContentServicesState()
        bootstrapState.value = null
        addonEntitlementsUserId = null
        serverSideStreamsEnabled = false
        fusionBadgeSourcesState.value = emptyMap()
        reloadFavouriteChannels()
        detailsCache.clear()
        seasonCache.clear()
        homeCache.clear()
        libraryCache.clear()
        searchCache.clear()
        addonSearchCache.clear()
        playlistCache.clear()
        networkCache.clear()
        genreCache.clear()
        resolvedPlaybackCache.clear()
        resolvedPlaybackCacheTimes.clear()
        watchedHistoryCache.clear()
        profilePreferencesState.value = JsonObject()
        StreamDekHttp.evictCache()
        api.clearSessionExpired()
    }

    suspend fun refreshBootstrap(): AccountBootstrap? = bootstrapRefreshMutex.withLock {
        val session = currentSession() ?: run {
            bootstrapState.value = null
            TvDebugLogger.w("Bootstrap", "refreshBootstrap skipped: no session")
            return@withLock null
        }
        TvDebugLogger.i("Bootstrap", "refreshBootstrap start user=${session.user.uid}")
        var bootstrap = fetchBootstrap(session)
        if (bootstrap != null) {
            val activeProfileId = sessionStore.activeProfileId()
            if (activeProfileId.isNullOrBlank()) {
                val preferredProfileId = bootstrap.streamProfiles
                    .firstOrNull { it.isDefault }
                    ?.id
                    ?: bootstrap.streamProfiles.firstOrNull()?.id
                if (!preferredProfileId.isNullOrBlank()) {
                    sessionStore.setActiveProfileId(preferredProfileId)
                    TvDebugLogger.i("Bootstrap", "selected initial profile=$preferredProfileId")
                    // Re-read so the profile-scoped overrides for the profile just picked are applied.
                    bootstrap = fetchBootstrap(session) ?: bootstrap
                }
            }
            TvDebugLogger.i(
                "Bootstrap",
                "refreshBootstrap ok profiles=${bootstrap.streamProfiles.size} devices=${bootstrap.devices.size} sessions=${bootstrap.sessions.size}",
            )
        } else {
            TvDebugLogger.w("Bootstrap", "refreshBootstrap returned null")
        }
        bootstrapState.value = bootstrap
        // Account-saved TMDB and MDBList keys ride along on the bootstrap, which is what lets a
        // television that has only just been signed into find them already there -- and what makes
        // a key changed or removed elsewhere take effect here on the next refresh rather than
        // living on in a cached copy.
        applyContentServices(bootstrap)
        // The synced snapshot carries plugin configuration but not the scrapers themselves, so
        // fetch those in the background rather than on the first stream lookup.
        pluginEngine?.selectProfile(sessionStore.activeProfileId())
        applyCloudStreamCollections(bootstrap?.profilePlugins)
        // Before the warm-up, so a scraper that reads its token at module scope has it on the
        // very first lookup rather than on the one after the next bootstrap.
        pluginEngine?.applySyncedSettings(bootstrap?.profilePlugins)
        pluginEngine?.warmUp(bootstrap?.profilePlugins)
        // Resolve direct/server mode while the profile picker or Home is being shown. Stream
        // selection can then fan out immediately instead of waiting on this account check first.
        repositoryScope.launch { runCatching { usesServerSideStreams() } }
        reloadFavouriteChannels()
        refreshFavouriteChannelsFromCloud()
        // In the background rather than on the first playback: a television that has just signed
        // in should already hold its keys by the time someone presses play, and nothing on screen
        // is waiting on the answer.
        if (bootstrap != null) repositoryScope.launch { syncDebridKeys() }
        return@withLock bootstrap
    }

    /**
     * Reads the bootstrap and folds the active profile's overrides into `preferences` before the
     * payload is typed, so every screen keeps reading `bootstrap.preferences` and gets the answer
     * for the profile actually in use.
     */
    private suspend fun fetchBootstrap(session: AuthSession): AccountBootstrap? {
        val raw = api.get<JsonObject>("/account/bootstrap", session) ?: return null
        val profilePreferences = raw.asObjectOrNull("profilePreferences") ?: JsonObject()
        profilePreferencesState.value = profilePreferences
        val accountPreferences = raw.asObjectOrNull("preferences") ?: JsonObject()
        raw.add("preferences", PreferenceScopes.mergeIntoAccountPreferences(accountPreferences, profilePreferences))
        return runCatching { api.gson.fromJson(raw, AccountBootstrap::class.java) }
            .onFailure { TvDebugLogger.e("Bootstrap", "could not read bootstrap payload", it) }
            .getOrNull()
    }

    suspend fun updatePlaybackPreferences(partial: Map<String, Any?>): AccountBootstrap? {
        val existing = bootstrapState.value?.preferences?.playback ?: PlaybackPreferences()
        if (!patchPreferences(
            mapOf(
                "playback" to mapOf(
                    "autoplayNextEpisode" to (partial["autoplayNextEpisode"] ?: existing.autoplayNextEpisode),
                    "preferredQuality" to (partial["preferredQuality"] ?: existing.preferredQuality),
                    "maxFileSizeGB" to (partial["maxFileSizeGB"] ?: existing.maxFileSizeGB),
                    "streamingServer" to (partial["streamingServer"] ?: existing.streamingServer),
                    "defaultSubtitleLanguage" to (partial["defaultSubtitleLanguage"] ?: existing.defaultSubtitleLanguage),
                    "defaultAudioLanguage" to (partial["defaultAudioLanguage"] ?: existing.defaultAudioLanguage),
                    "externalPlayerEnabled" to (partial["externalPlayerEnabled"] ?: existing.externalPlayerEnabled),
                    "preferEmbeddedMpvByDefault" to (partial["preferEmbeddedMpvByDefault"] ?: existing.preferEmbeddedMpvByDefault),
                    "skipSegmentsEnabled" to (partial["skipSegmentsEnabled"]
                        ?: listOf(
                            partial["skipIntroEnabled"] as? Boolean ?: existing.isSegmentEnabled("intro"),
                            partial["skipRecapEnabled"] as? Boolean ?: existing.isSegmentEnabled("recap"),
                            partial["skipEndingEnabled"] as? Boolean ?: existing.isSegmentEnabled("outro"),
                        ).any { it }),
                    "skipIntroEnabled" to (partial["skipIntroEnabled"] ?: existing.isSegmentEnabled("intro")),
                    "skipRecapEnabled" to (partial["skipRecapEnabled"] ?: existing.isSegmentEnabled("recap")),
                    "skipEndingEnabled" to (partial["skipEndingEnabled"] ?: existing.isSegmentEnabled("outro")),
                    "autoSkipIntroEnabled" to (partial["autoSkipIntroEnabled"] ?: existing.autoSkipIntroEnabled),
                    "autoSkipRecapEnabled" to (partial["autoSkipRecapEnabled"] ?: existing.autoSkipRecapEnabled),
                    "autoSkipEndingEnabled" to (partial["autoSkipEndingEnabled"] ?: existing.autoSkipEndingEnabled),
                    "autoPlayNextEpisodeEnabled" to (partial["autoPlayNextEpisodeEnabled"]
                        ?: partial["autoplayNextEpisode"]
                        ?: existing.isAutoPlayNextEpisodeEnabled()),
                    "preferBingeGroupNextEpisode" to (partial["preferBingeGroupNextEpisode"] ?: existing.preferBingeGroupNextEpisode),
                    "autoLoadSubtitles" to (partial["autoLoadSubtitles"] ?: existing.autoLoadSubtitles),
                    "showOnlyPreferredSubtitleLanguages" to (partial["showOnlyPreferredSubtitleLanguages"] ?: existing.showOnlyPreferredSubtitleLanguages),
                    "secondarySubtitleLanguage" to (partial["secondarySubtitleLanguage"] ?: existing.secondarySubtitleLanguage),
                    "addonSubtitleLoading" to (partial["addonSubtitleLoading"] ?: existing.addonSubtitleLoading),
                    "subtitleDefaultSource" to (partial["subtitleDefaultSource"] ?: existing.subtitleDefaultSource),
                    "nextEpisodeThresholdMode" to (partial["nextEpisodeThresholdMode"] ?: existing.nextEpisodeThresholdMode),
                    "nextEpisodeThresholdPercent" to (partial["nextEpisodeThresholdPercent"] ?: existing.nextEpisodeThresholdPercent),
                    "nextEpisodeThresholdMinutes" to (partial["nextEpisodeThresholdMinutes"] ?: existing.nextEpisodeThresholdMinutes),
                    "endOfPlaybackRecommendationsEnabled" to (partial["endOfPlaybackRecommendationsEnabled"] ?: existing.endOfPlaybackRecommendationsEnabled),
                    "recommendationTiming" to (partial["recommendationTiming"] ?: existing.recommendationTiming),
                    "recommendationItemCount" to (partial["recommendationItemCount"] ?: existing.recommendationItemCount),
                    "timingProvider" to (partial["timingProvider"] ?: existing.timingProvider),
                    "timingProviderFallbackEnabled" to (partial["timingProviderFallbackEnabled"] ?: existing.timingProviderFallbackEnabled),
                    "decoderMode" to (partial["decoderMode"] ?: existing.decoderMode),
                    "renderSurface" to (partial["renderSurface"] ?: existing.renderSurface),
                    "playerEngine" to (partial["playerEngine"] ?: existing.playerEngine),
                    "rememberLastSource" to (partial["rememberLastSource"] ?: existing.rememberLastSource),
                    "manualStreamSelectionEnabled" to (partial["manualStreamSelectionEnabled"] ?: existing.manualStreamSelectionEnabled),
                    "liveProgressBarEnabled" to (partial["liveProgressBarEnabled"] ?: existing.liveProgressBarEnabled),
                ),
            ),
        )) return null
        return refreshBootstrap()
    }

    suspend fun updateAppPreferences(partial: Map<String, Any?>): AccountBootstrap? {
        val existing = bootstrapState.value?.preferences?.app ?: AppPreferences()
        if (!patchPreferences(
            mapOf(
                "app" to mapOf(
                    "theme" to (partial["theme"] ?: existing.theme),
                    "colorMode" to (partial["colorMode"] ?: existing.colorMode),
                    "startScreen" to (partial["startScreen"] ?: existing.startScreen),
                    "homeRowCardStyle" to (partial["homeRowCardStyle"] ?: existing.homeRowCardStyle),
                    "compactMode" to (partial["compactMode"] ?: existing.compactMode),
                    "syncOverCellular" to (partial["syncOverCellular"] ?: existing.syncOverCellular),
                    "cardDensity" to (partial["cardDensity"] ?: existing.cardDensity),
                    // Carried through untouched rather than dropped: this television no longer
                    // reads it, but an older client on the same account still might, and a PATCH
                    // that omitted the key would clear their setting.
                    "animationSpeed" to (partial["animationSpeed"] ?: @Suppress("DEPRECATION") existing.animationSpeed),
                    "navigationStyle" to (partial["navigationStyle"] ?: existing.navigationStyle),
                    "gridSize" to (partial["gridSize"] ?: existing.gridSize),
                    "backgroundBlur" to (partial["backgroundBlur"] ?: existing.backgroundBlur),
                    "highContrast" to (partial["highContrast"] ?: existing.highContrast),
                    "largeText" to (partial["largeText"] ?: existing.largeText),
                    "reducedMotion" to (partial["reducedMotion"] ?: existing.reducedMotion),
                    "hideHomeSynopsis" to (partial["hideHomeSynopsis"] ?: existing.hideHomeSynopsis),
                    "hideHomeCardTitles" to (partial["hideHomeCardTitles"] ?: existing.hideHomeCardTitles),
                    "transparentNavigation" to (partial["transparentNavigation"] ?: existing.transparentNavigation),
                ),
            ),
        )) return null
        return refreshBootstrap()
    }

    suspend fun updateHomePreferences(partial: Map<String, Any?>): AccountBootstrap? {
        val existing = bootstrapState.value?.preferences?.home ?: HomePreferences()
        if (!patchPreferences(
            mapOf(
                "home" to mapOf(
                    "primarySyncService" to (partial["primarySyncService"] ?: existing.primarySyncService),
                    "defaultAppCatalogsEnabled" to (partial["defaultAppCatalogsEnabled"] ?: existing.defaultAppCatalogsEnabled),
                    "continueWatchingStyle" to (partial["continueWatchingStyle"] ?: existing.continueWatchingStyle),
                    "networkCardStyle" to (partial["networkCardStyle"] ?: existing.networkCardStyle),
                    "liveCategoriesEnabled" to (partial["liveCategoriesEnabled"] ?: existing.liveCategoriesEnabled),
                    "liveLandscapeCards" to (partial["liveLandscapeCards"] ?: existing.liveLandscapeCards),
                    "liveFavouriteDrawerCards" to (partial["liveFavouriteDrawerCards"] ?: existing.liveFavouriteDrawerCards),
                    "showHeroSynopsis" to (partial["showHeroSynopsis"] ?: existing.showHeroSynopsis),
                    "detailPageStyle" to (partial["detailPageStyle"] ?: existing.detailPageStyle),
                    "vividAmbient" to (partial["vividAmbient"] ?: existing.vividAmbient),
                    "ambientTintPercent" to (partial["ambientTintPercent"] ?: existing.ambientTintPercent),
                    "homeCatalogRows" to (partial["homeCatalogRows"] ?: existing.homeCatalogRows),
                ),
            ),
        )) return null
        homeCache.clear()
        return refreshBootstrap()
    }

    suspend fun updateDetailPreferences(partial: Map<String, Any?>): AccountBootstrap? {
        val existing = bootstrapState.value?.preferences?.detail ?: DetailPreferences()
        if (!patchPreferences(
            mapOf(
                "detail" to mapOf(
                    "seasonTabStyle" to (partial["seasonTabStyle"] ?: existing.seasonTabStyle),
                    "heroTrailerAutoplay" to (partial["heroTrailerAutoplay"] ?: existing.heroTrailerAutoplay),
                    "heroTrailerDelaySeconds" to (partial["heroTrailerDelaySeconds"] ?: existing.heroTrailerDelaySeconds),
                    "heroTrailerResolution" to (partial["heroTrailerResolution"] ?: existing.heroTrailerResolution),
                    "trailerCacheClearHours" to (partial["trailerCacheClearHours"] ?: existing.trailerCacheClearHours),
                    "ratingsEnabled" to (partial["ratingsEnabled"] ?: existing.ratingsEnabled),
                    "externalRatingsEnabled" to (partial["externalRatingsEnabled"] ?: existing.externalRatingsEnabled),
                    "enabledRatingProviders" to (partial["enabledRatingProviders"] ?: existing.enabledRatingProviders),
                    // Deliberately not carried through any more. The MDBList key was a secret
                    // travelling on an ordinary settings document; it lives in the encrypted
                    // credential store now and is managed under Content Services. The field is left
                    // out entirely rather than echoed back, so a trailer setting saved from the
                    // television cannot resurrect a plaintext copy the migration has just cleared.
                ),
            ),
        )) return null
        return refreshBootstrap()
    }

    suspend fun updateStreamsPreferences(partial: Map<String, Any?>): AccountBootstrap? {
        val existing = bootstrapState.value?.preferences?.streams ?: StreamsPreferences()
        if (!patchPreferences(
            mapOf(
                "streams" to mapOf(
                    "fusionBadgesEnabled" to (partial["fusionBadgesEnabled"] ?: existing.fusionBadgesEnabled),
                    "showSizeBadges" to (partial["showSizeBadges"] ?: existing.showSizeBadges),
                    "badgePosition" to (partial["badgePosition"] ?: existing.badgePosition),
                    "fusionBadgeUrls" to (partial["fusionBadgeUrls"] ?: existing.fusionBadgeUrls),
                    "activeFusionBadgeUrl" to (if (partial.containsKey("activeFusionBadgeUrl")) partial["activeFusionBadgeUrl"] else existing.activeFusionBadgeUrl),
                    // Carried through untouched so writing a badge setting from the TV does not
                    // blank out the stream-picker keys the other clients own.
                    "showStreamsList" to (partial["showStreamsList"] ?: existing.showStreamsList),
                    "rememberLastSource" to (partial["rememberLastSource"] ?: existing.rememberLastSource),
                    "blurUnwatchedEpisodes" to (partial["blurUnwatchedEpisodes"] ?: existing.blurUnwatchedEpisodes),
                    "streamDekFormattingEnabled" to (partial["streamDekFormattingEnabled"] ?: existing.streamDekFormattingEnabled),
                    "showAddonTmdbRatings" to (partial["showAddonTmdbRatings"] ?: existing.showAddonTmdbRatings),
                    "favoriteSourceKeys" to (partial["favoriteSourceKeys"] ?: existing.favoriteSourceKeys),
                ),
            ),
        )) return null
        return refreshBootstrap()
    }

    suspend fun fetchFusionBadgeSource(url: String, forceRefresh: Boolean = false): FusionBadgeSource? {
        val encodedUrl = URLEncoder.encode(url, "UTF-8")
        val path = "/badges/fusion-source?url=$encodedUrl" + if (forceRefresh) "&refresh=true" else ""
        val source = api.get<FusionBadgeSource>(path) ?: return null
        fusionBadgeSourcesState.value = fusionBadgeSourcesState.value + (url to source)
        return source
    }

    suspend fun ensureFusionBadgeSourcesLoaded(forceRefresh: Boolean = false) {
        val streamsPrefs = bootstrapState.value?.preferences?.streams ?: StreamsPreferences()
        if (!streamsPrefs.fusionBadgesEnabled) return
        val urls = streamsPrefs.fusionBadgeUrls.take(MAX_FUSION_BADGE_URLS).filter { it.isNotBlank() }
        supervisorScope {
            urls.map { url ->
                async {
                    if (forceRefresh || fusionBadgeSourcesState.value[url] == null) {
                        runCatching { fetchFusionBadgeSource(url, forceRefresh) }
                    }
                }
            }.forEach { it.await() }
        }
    }

    fun removeFusionBadgeSource(url: String) {
        fusionBadgeSourcesState.value = fusionBadgeSourcesState.value - url
    }

    suspend fun fetchAddonManifests(forceRefresh: Boolean = false): List<AddonManifest> {
        val fetched = api.get<List<AddonManifest>>(
            "/addons/manifests" + if (forceRefresh) "?refresh=true" else "",
        )
        if (fetched != null) applyAddonSnapshot(fetched)
        return fetched ?: bootstrapState.value?.integrations?.addons?.items.orEmpty()
    }

    suspend fun toggleAddon(id: String, enabled: Boolean): Boolean {
        val previousEnabled = bootstrapState.value?.integrations?.addons?.items
            ?.firstOrNull { it.id == id }
            ?.enabled
        if (previousEnabled != null) {
            applyAddonSnapshot(
                bootstrapState.value?.integrations?.addons?.items.orEmpty().map { addon ->
                    if (addon.id == id) addon.copy(enabled = enabled) else addon
                },
            )
        }
        val response = api.post<JsonObject>("/addons/toggle", mapOf("id" to id, "enabled" to enabled))
        if (response == null) {
            if (previousEnabled != null) rollbackAddonToggle(id, enabled, previousEnabled)
            return false
        }
        val saved = response.get("success")?.asBoolean == true ||
            response.has("id") || response.has("enabled")
        if (!saved) {
            if (previousEnabled != null) rollbackAddonToggle(id, enabled, previousEnabled)
            return false
        }
        homeCache.clear()
        return true
    }

    private fun rollbackAddonToggle(id: String, attempted: Boolean, previous: Boolean) {
        val addons = bootstrapState.value?.integrations?.addons?.items.orEmpty()
        if (addons.firstOrNull { it.id == id }?.enabled != attempted) return
        applyAddonSnapshot(addons.map { addon ->
            if (addon.id == id) addon.copy(enabled = previous) else addon
        })
    }

    suspend fun setAddonFavourite(id: String, favourite: Boolean): Boolean {
        val response = api.post<JsonObject>("/addons/favourite", mapOf("id" to id, "favourite" to favourite)) ?: return false
        val saved = response.get("success")?.asBoolean == true
        if (!saved) return false
        homeCache.clear()
        refreshBootstrap()
        return true
    }

    /** The fields a plugin source asks for, read by running its own `onSettings` export. */
    suspend fun pluginSettingsSchema(provider: ProfilePluginProvider): Result<List<PluginSettingField>> =
        pluginEngine?.settingsSchema(bootstrapState.value?.profilePlugins, provider)
            ?: Result.failure(IllegalStateException("Plugin sources are unavailable on this device."))

    /**
     * Whether to offer the settings entry for a plugin source. Answers from the scraper itself
     * where it has been cached, because a collection's `hasSettings` flag is advisory and often
     * absent on sources that do need a key.
     */
    fun pluginProviderHasSettings(provider: ProfilePluginProvider): Boolean =
        pluginEngine?.declaresSettings(provider) ?: provider.hasSettings

    /**
     * Values entered for one plugin source. Kept on this device rather than synced: they are API
     * keys and passwords, and nothing in the account carries them today.
     */
    fun pluginProviderSettings(providerId: String): Map<String, String> =
        pluginEngine?.providerSettings(providerId).orEmpty()

    /**
     * Stores settings for one plugin source and syncs them to the profile.
     *
     * Kept on the device *and* in the account. The device copy is what the sandbox reads; the
     * account copy is what carries a token typed here to the phone, and what brings a token typed
     * on the portal back — the same document the other clients write.
     */
    suspend fun savePluginProviderSettings(providerId: String, values: Map<String, String>): Boolean {
        val engine = pluginEngine ?: return false
        engine.saveProviderSettings(providerId, values)
        val state = bootstrapState.value?.profilePlugins ?: return true
        val provider = state.providers.firstOrNull { it.id == providerId } ?: return true
        val payload = JsonObject().apply {
            values.forEach { (key, value) -> if (key.isNotBlank()) addProperty(key, value) }
        }
        val next = state.copy(
            providers = state.providers.map { if (it.id == providerId) it.copy(settings = payload) else it },
        )
        // A failed sync is not a failed save: the token is already on this device and working, so
        // the source plays here either way. Only the reach to other devices is lost.
        val updated = updateProfilePlugins(next)
        if (updated == null) {
            TvDebugLogger.w("Plugins", "settings for ${provider.name} saved on this TV but not synced")
        }
        return updated != null
    }

    /**
     * Watches the account for a plugin change made on the phone or the web portal.
     *
     * Polls a stamp rather than the document: the document carries every source and every settings
     * schema, and asking for that on a timer would be wasteful on a stick. Only when the stamp
     * moves past what this television last saw is the bootstrap actually refreshed -- so the steady
     * state is a few bytes, and a collection added elsewhere appears here within the interval
     * rather than on the next cold start.
     */
    fun watchProfilePlugins(scope: kotlinx.coroutines.CoroutineScope): kotlinx.coroutines.Job = scope.launch {
        var lastSeen = bootstrapState.value?.profilePlugins?.updatedAt ?: 0L
        while (isActive) {
            kotlinx.coroutines.delay(PLUGIN_WATCH_INTERVAL_MS)
            val profileId = sessionStore.activeProfileId()?.takeIf { it.isNotBlank() } ?: continue
            val version = runCatching {
                api.get<com.google.gson.JsonObject>(
                    "/profiles/${URLEncoder.encode(profileId, "UTF-8")}/plugins/version",
                )?.get("updatedAt")?.asLong ?: 0L
            }.getOrDefault(0L)
            if (version > lastSeen) {
                lastSeen = version
                TvDebugLogger.i("Plugins", "plugin document changed elsewhere; refreshing")
                runCatching { refreshBootstrap() }
            }
        }
    }

    suspend fun updateProfilePlugins(state: ProfilePluginState): AccountBootstrap? {
        val profileId = sessionStore.activeProfileId()?.takeIf { it.isNotBlank() } ?: return null
        val next = state.copy(updatedAt = System.currentTimeMillis())
        val response = api.put<JsonObject>(
            "/profiles/${URLEncoder.encode(profileId, "UTF-8")}/plugins",
            mapOf("plugins" to next),
        ) ?: return null
        if (response.get("success")?.asBoolean != true) return null
        return refreshBootstrap()
    }

    private fun applyAddonSnapshot(addons: List<AddonManifest>) {
        val current = bootstrapState.value ?: return
        bootstrapState.value = current.copy(
            integrations = current.integrations.copy(
                addons = current.integrations.addons.copy(items = addons),
            ),
        )
    }

    suspend fun setDebridAccountEnabled(provider: String, enabled: Boolean): Boolean {
        val encoded = URLEncoder.encode(provider, "UTF-8")
        val response = api.patch<JsonObject>(
            "/debrid/accounts/$encoded",
            mapOf("enabled" to enabled),
        ) ?: return false
        // Apply the account switch immediately to this device too. Waiting for the asynchronous
        // cloud-key refresh leaves a disabled provider available for one more picker/playback.
        appContext?.let { context ->
            val stored = DebridKeyStore.load(context)
            if (stored.any { it.provider == provider }) {
                DebridKeyStore.save(
                    context,
                    stored.map { key -> if (key.provider == provider) key.copy(enabled = enabled) else key },
                )
                deviceDebridManager = null
            }
        }
        refreshBootstrap()
        return response.get("success")?.asBoolean == true
    }

    suspend fun uninstallAddon(id: String) {
        api.delete<Map<String, String>>("/addons/uninstall", mapOf("id" to id))
        refreshBootstrap()
    }

    private suspend fun fetchAddonCatalogCollections(
        addonId: String? = null,
        includeCatalog: (rawType: String, mappedType: String) -> Boolean = { _, _ -> true },
    ): List<AddonCatalogCollection> {
        val addons = runCatching { fetchAddonManifests() }.getOrDefault(emptyList())
            .filter { it.enabled && (addonId.isNullOrBlank() || it.id == addonId) }
            .sortedWith(compareByDescending<AddonManifest> { it.favourite }.thenBy { it.position })
        if (addons.isEmpty()) return emptyList()

        return supervisorScope {
            addons.flatMap { addon ->
                addon.manifest.catalogs.mapIndexedNotNull { _, catalog ->
                    val rawType = catalog.type.trim().lowercase(Locale.US)
                    val mappedType = mapAddonCatalogType(rawType) ?: return@mapIndexedNotNull null
                    if (!includeCatalog(rawType, mappedType)) return@mapIndexedNotNull null
                    val catalogId = catalog.id.trim()
                    if (catalogId.isBlank()) return@mapIndexedNotNull null
                    // A catalog that cannot answer without a search term is a search endpoint, not
                    // a row. Asking it anyway costs a round trip per add-on per load to be told
                    // nothing, and an add-on less forgiving than the ones tested here could answer
                    // with an error card instead of an empty list.
                    if (catalog.requiresSearch) return@mapIndexedNotNull null
                    // A required genre has to be supplied or the catalog is within its rights to
                    // refuse. Only the backend proxy cannot carry one, so those go direct.
                    val requiredGenre = catalog.defaultGenre
                    async {
                        val proxiedMetas = if (requiredGenre != null) {
                            emptyList()
                        } else {
                            runCatching {
                                api.get<AddonCatalogResponse>(
                                    "/addons/${URLEncoder.encode(addon.id, "UTF-8")}/catalog/$rawType/${URLEncoder.encode(catalogId, "UTF-8")}",
                                )?.metas.orEmpty()
                            }.onFailure {
                                TvDebugLogger.w("Home", "addon catalog fetch failed addon=${addon.id} type=$rawType id=$catalogId")
                            }.getOrDefault(emptyList())
                        }
                        val usableProxiedMetas = proxiedMetas.filterNot(::isAddonCatalogDiagnosticMeta)
                        val shouldTryDirect = proxiedMetas.isEmpty() || usableProxiedMetas.size != proxiedMetas.size
                        val directMetas = if (shouldTryDirect) {
                            fetchAddonCatalogDirect(addon, rawType, catalogId, genre = requiredGenre)
                                .filterNot(::isAddonCatalogDiagnosticMeta)
                        } else {
                            emptyList()
                        }
                        val metas = directMetas.takeIf { it.isNotEmpty() } ?: usableProxiedMetas
                        val items = metas.mapNotNull {
                            normalizeAddonCatalogMeta(
                                meta = it,
                                fallbackType = mappedType,
                                nativeFallbackType = rawType,
                                addonId = addon.id,
                                addonName = addon.manifest.name,
                                catalogId = catalogId,
                                catalogName = catalog.name,
                            )
                        }
                        if (items.isEmpty()) {
                            null
                        } else {
                            AddonCatalogCollection(
                                addonId = addon.id,
                                addonName = addon.manifest.name,
                                rawType = rawType,
                                catalogId = catalogId,
                                catalogName = catalog.name,
                                items = items,
                            )
                        }
                    }
                }
            }.mapNotNull { it.await() }
        }
    }

    private suspend fun fetchAddonCatalogDirect(
        addon: AddonManifest,
        rawType: String,
        catalogId: String,
        genre: String? = null,
        search: String? = null,
    ): List<AddonCatalogMetaItem> = withContext(Dispatchers.IO) {
        val manifestUrl = addon.transportUrl ?: addon.manifestUrl ?: return@withContext emptyList()
        val addonBaseUrl = manifestUrl
            .substringBeforeLast("/manifest.json", missingDelimiterValue = manifestUrl.trimEnd('/'))
            .trimEnd('/')
        // Stremio takes extras as one more path segment before .json, not a query string:
        // /catalog/movie/{id}/search=blade%20runner.json
        val extras = buildList {
            genre?.takeIf { it.isNotBlank() }?.let { add("genre=" + addonPathSegment(it)) }
            search?.takeIf { it.isNotBlank() }?.let { add("search=" + addonPathSegment(it)) }
        }
        val extraSegment = extras.takeIf { it.isNotEmpty() }?.joinToString("&", prefix = "/").orEmpty()
        val endpoint = "$addonBaseUrl/catalog/${addonPathSegment(rawType)}/${addonPathSegment(catalogId)}$extraSegment.json"
        runCatching {
            val request = okhttp3.Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .header("User-Agent", "Stremio/4.4.168")
                .build()
            directStreamClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                api.gson.fromJson(response.body?.charStream(), AddonCatalogResponse::class.java)
                    ?.metas.orEmpty()
            }
        }.onFailure {
            TvDebugLogger.w("Home", "direct addon catalog retry failed addon=${addon.id} type=$rawType id=$catalogId")
        }.getOrDefault(emptyList())
    }

    /**
     * Builds one home rail per addon catalog. Catalogs are fetched through the
     * backend proxy with the addon's Stremio-native type ('series' for shows,
     * 'tv' for live channels, 'events'/'sport' for live events).
     */
    suspend fun fetchAddonCatalogRails(): List<HomeRail> {
        val collections = fetchAddonCatalogCollections()
        // Which titles land on more than one catalog type, and so need saying which they are.
        // Worked out across every add-on at once rather than per add-on: two providers publishing
        // a row called "Trending" are just as indistinguishable as one publishing it twice.
        val ambiguousTitles = collections
            .groupBy { buildAddonRailTitle(it.addonName, it.catalogName) }
            .filterValues { group -> group.map { it.rawType }.distinct().size > 1 }
            .keys
        return collections.mapIndexed { index, collection ->
            val plainTitle = buildAddonRailTitle(collection.addonName, collection.catalogName)
            HomeRail(
                id = "addon:${collection.addonId}:${collection.rawType}:${collection.catalogId}:$index",
                title = if (plainTitle in ambiguousTitles) {
                    buildAddonRailTitle(
                        addonName = collection.addonName,
                        catalogName = collection.catalogName,
                        typeSuffix = addonRailTypeSuffix(collection.rawType),
                    )
                } else {
                    plainTitle
                },
                items = collection.items.take(80),
                isLive = mapAddonCatalogType(collection.rawType.lowercase(Locale.US)) == "live",
            )
        }
    }

    /**
     * @param onProgress what is happening, for the Live page to show. A provider playlist takes
     *   long enough that a silent skeleton reads as a hang, so every stage reports: the add-on
     *   catalogs, each playlist as it downloads and parses, and the grouping afterwards.
     */
    suspend fun fetchLiveCatalogSections(
        onProgress: (M3uLoadProgress) -> Unit = {},
    ): List<LiveCatalogSection> {
        onProgress(M3uLoadProgress("Loading channels from your add-ons…"))
        val addonSections = fetchAddonCatalogCollections { _, mappedType -> mappedType == "live" }
            .groupBy { it.addonId }
            .map { (addonId, collections) ->
                LiveCatalogSection(
                    id = "live:$addonId",
                    title = collections.firstOrNull()?.addonName.orEmpty(),
                    rails = collections.mapIndexed { index, collection ->
                        LiveCatalogRail(
                            id = "live:${collection.addonId}:${collection.rawType}:${collection.catalogId}:$index",
                            title = buildLiveRailTitle(collection.rawType, collection.catalogName),
                            items = collection.items,
                        )
                    },
                )
            }
            .filter { section -> section.rails.any { it.items.isNotEmpty() } }
        // Playlists are one more source of live channels, so they arrive as sections alongside the
        // add-ons rather than anywhere separate — the sidebar, favourites and search all treat them
        // the same way once they are here.
        return addonSections + fetchPlaylistLiveSections(onProgress)
    }

    // ── IPTV playlists ───────────────────────────────────────────────────────────────────────────

    /** The playlists saved against this profile, newest state from the account. */
    suspend fun fetchPlaylists(forceRefresh: Boolean = false): List<RemotePlaylist> {
        if (!forceRefresh) {
            playlistCache[buildSessionProfileCacheKey()]?.let { return it }
        }
        // The profile header is attached by the API client from the active profile, the same way
        // every other profile-scoped call gets it.
        if (activeStreamProfile(bootstrapState.value) == null) return emptyList()
        val response = runCatching { api.get<RemotePlaylistResponse>("/playlists") }.getOrNull()
        val playlists = response?.playlists.orEmpty().filter { it.url.isNotBlank() }.sortedBy { it.position }
        playlistCache[buildSessionProfileCacheKey()] = playlists
        return playlists
    }

    /**
     * Turns one playlist on or off for this profile.
     *
     * Adding and removing stay on the phone and the web portal — typing a provider URL carrying
     * credentials on a remote is miserable — but turning one off is a single press and is the
     * thing someone actually reaches for on the TV.
     */
    suspend fun setPlaylistEnabled(id: String, enabled: Boolean): Boolean {
        val response = runCatching {
            api.patch<RemotePlaylistResponse>("/playlists/${encodePathSegment(id)}", mapOf("enabled" to enabled))
        }.getOrNull() ?: return false
        playlistCache[buildSessionProfileCacheKey()] = response.playlists.sortedBy { it.position }
        return true
    }

    /**
     * One section per enabled playlist, its channels grouped into rails by `group-title`.
     *
     * Each playlist is loaded from its stored copy when there is one, so a cold start shows
     * channels without waiting on the provider; a copy older than half a day is refetched. A
     * playlist that fails is dropped rather than failing the others — an expired IPTV subscription
     * must not empty the Live page for the add-ons that are still working.
     */
    private suspend fun fetchPlaylistLiveSections(
        onProgress: (M3uLoadProgress) -> Unit,
    ): List<LiveCatalogSection> {
        val playlists = fetchPlaylists().filter { it.enabled }
        if (playlists.isEmpty()) return emptyList()
        appContext?.let { M3uPlaylistEngine.initialize(it) }

        // Playlists are loaded one after another rather than at once. Two 200k-channel lists
        // parsing in parallel is the worst case this box handles, and it makes progress
        // unreportable: two sets of counts interleaving says less than one that moves.
        val sections = mutableListOf<LiveCatalogSection>()
        playlists.forEachIndexed { playlistIndex, playlist ->
            val prefix = if (playlists.size > 1) "Playlist ${playlistIndex + 1} of ${playlists.size} · " else ""
            val channels = M3uPlaylistEngine
                .fetchChannels(playlist, forceRefresh = M3uPlaylistEngine.needsRefresh(playlist)) { progress ->
                    onProgress(progress.copy(message = prefix + progress.message))
                }
                .onFailure {
                    TvDebugLogger.w("Live", "playlist ${playlist.name} failed to load")
                    onProgress(M3uLoadProgress("${playlist.name} could not be loaded"))
                }
                .getOrDefault(emptyList())
                .filter { it.type == "live" }
            if (channels.isEmpty()) return@forEachIndexed

            // Grouping a 200k-channel playlist is real work, and it happens after the counts have
            // stopped moving — without a word here the screen looks stuck at the last number.
            onProgress(M3uLoadProgress("${prefix}Sorting ${channels.size.formatted()} channels into categories", null, channels.size))
            val rails = withContext(Dispatchers.Default) {
                channels
                    .groupBy { it.sourceCatalogName ?: playlist.name }
                    .entries
                    .sortedBy { it.key.lowercase(Locale.US) }
                    .mapIndexed { index, (category, items) ->
                        LiveCatalogRail(
                            id = "playlist:${playlist.id}:$index",
                            title = category,
                            items = items,
                        )
                    }
            }
            onProgress(M3uLoadProgress("${playlist.name}: ${rails.size} categories, ${channels.size.formatted()} channels", 1f, channels.size))
            sections += LiveCatalogSection(
                id = "playlist:${playlist.id}",
                title = playlist.name,
                rails = rails,
            )
        }
        return sections
    }

    suspend fun fetchRelatedLiveChannels(
        addonId: String?,
        catalogId: String?,
    ): List<MediaItem> {
        if (addonId.isNullOrBlank()) return emptyList()
        val collections = fetchAddonCatalogCollections(addonId = addonId) { _, mappedType -> mappedType == "live" }
            .filter { collection ->
                collection.addonId == addonId &&
                    (catalogId.isNullOrBlank() || collection.catalogId == catalogId)
            }
        return collections
            .flatMap { it.items }
            .distinctBy { item -> "${item.sourceAddonId}:${item.streamType}:${item.id}" }
    }

    private fun normalizeAddonCatalogMeta(
        meta: AddonCatalogMetaItem,
        fallbackType: String,
        nativeFallbackType: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String?,
    ): MediaItem? {
        val rawId = meta.id?.trim().orEmpty()
        val tmdbId = meta.movieDbId
            ?: rawId.takeIf { it.startsWith("tmdb:", ignoreCase = true) }
                ?.substringAfter(':')?.toIntOrNull()
            ?: 0
        val resolvedId = if (tmdbId > 0) tmdbId.toString() else rawId
        if (resolvedId.isBlank()) return null
        if (isPlaceholderCatalogMeta(rawId, meta.name)) return null
        val rawNativeType = meta.type?.trim()?.lowercase(Locale.US).orEmpty()
        val mapped = rawNativeType.takeIf { it.isNotBlank() }?.let { mapAddonCatalogType(it) }
        val type = mapped ?: fallbackType
        val nativeType = if (mapped != null) rawNativeType else nativeFallbackType
        return MediaItem(
            id = resolvedId,
            tmdbId = tmdbId,
            title = meta.name.orEmpty(),
            type = type,
            poster = meta.poster,
            backdrop = meta.background ?: meta.poster,
            description = sequenceOf(meta.description, meta.overview, meta.synopsis)
                .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
                .firstOrNull(),
            rating = meta.imdbRating?.toDoubleOrNull(),
            year = meta.releaseInfo?.take(4)?.takeIf { it.toIntOrNull() != null },
            titleLogo = meta.logo,
            streamType = if (type == "live") (nativeType.ifBlank { "tv" }) else null,
            sourceAddonId = addonId,
            sourceAddonName = addonName,
            sourceCatalogId = catalogId,
            sourceCatalogName = catalogName,
            directStreamUrl = directMediaUrl(meta),
            requestHeaders = catalogRequestHeaders(meta),
        )
    }

    private fun directMediaUrl(meta: AddonCatalogMetaItem): String? {
        val behaviorHints = meta.behaviorHints.orEmpty()
        return sequenceOf(meta.url, meta.externalUrl, behaviorHints["url"], behaviorHints["externalUrl"])
            .mapNotNull(::stringUrlValue)
            .firstOrNull()
    }

    private fun stringUrlValue(value: Any?): String? {
        return when (value) {
            is String -> value.trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            is Map<*, *> -> sequenceOf(value["url"], value["href"])
                .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
                .firstOrNull()
            else -> null
        }
    }

    private fun catalogRequestHeaders(meta: AddonCatalogMetaItem): Map<String, String> {
        val directHeaders = stringMap(meta.headers)
        val proxyHeaders = (meta.behaviorHints?.get("proxyHeaders") as? Map<*, *>)
            ?.get("request") as? Map<*, *>
        return directHeaders + stringMap(proxyHeaders)
    }

    private fun stringMap(source: Map<*, *>?): Map<String, String> = source.orEmpty()
        .mapNotNull { (key, value) ->
            val name = key?.toString()?.trim().orEmpty()
            val content = value?.toString()?.trim().orEmpty()
            if (name.isBlank() || content.isBlank()) null else name to content
        }
        .toMap()

    suspend fun fetchLatestAppRelease(): AppReleaseManifest? {
        val configuredPath = BuildConfig.STREAMDEK_OTA_MANIFEST_PATH.takeIf { it.isNotBlank() }
        val candidatePaths = buildList {
            configuredPath?.let(::add)
            add("/public/updates/android-tv/latest")
            add("/updates/android-tv/latest")
            add("/app/updates/android-tv/latest")
        }.map { raw ->
            if (raw.startsWith("/")) raw else "/$raw"
        }.distinct()

        for (path in candidatePaths) {
            val manifest = api.get<AppReleaseManifest>(path, session = null)
                ?.takeIf { it.versionCode > 0 && it.versionName.isNotBlank() && it.apkUrl.isNotBlank() }
            if (manifest != null) {
                TvDebugLogger.i("Updates", "fetchLatestAppRelease ok path=$path version=${manifest.versionName} code=${manifest.versionCode}")
                return manifest
            }
            TvDebugLogger.w("Updates", "fetchLatestAppRelease unavailable path=$path")
        }

        return null
    }

    /**
     * Home, delivered a row at a time.
     *
     * Building the whole screen before showing any of it meant the slowest source set the speed of
     * the entire app: fanning out to every installed add-on, or one Trakt round trip, held back the
     * TMDB rows that were already in hand. On a Firestick over a slow connection that is several
     * seconds of nothing. Rows now arrive as they resolve, each into a slot reserved from the
     * start, so the layout never reflows underneath the viewer.
     *
     * The last value emitted is the complete screen, which is what gets cached.
     */
    fun homeContentStream(forceRefresh: Boolean = false): Flow<HomeContent> = channelFlow {
        val perf = Perf.span("home", if (forceRefresh) "forced" else "normal")
        val homePreferences = bootstrapState.value?.preferences?.home
        val addonConfiguration = bootstrapState.value?.integrations?.addons?.items.orEmpty()
            .joinToString("|") { "${it.id}:${it.enabled}:${it.position}" }
        // The row layout is part of the key: switching a row off on the phone has to change what
        // this screen shows the next time it is opened, not the next time the cache happens to miss.
        val rowLayout = homePreferences?.homeCatalogRows.orEmpty()
            .sortedBy { it.position }
            .joinToString("|") { "${it.id}:${it.enabled}" }
        val cacheKey = buildSessionProfileCacheKey() +
            ":${homePreferences?.defaultAppCatalogsEnabled != false}:$addonConfiguration:$rowLayout"
        if (!forceRefresh) {
            homeCache[cacheKey]?.let {
                send(it)
                perf.end("cacheHit", "rails=${it.rails.size}")
                Perf.startupMark("home.firstContent", "cached")
                return@channelFlow
            }
        }

        val recommendationsAvailable = isSyncServiceConnected(SyncServiceId.TRAKT)
        val builtInCatalogsEnabled = bootstrapState.value?.preferences?.home?.defaultAppCatalogsEnabled != false
        val failuresBefore = api.failureEpoch

        // Which rows exist is the registry's decision, and the skeleton has to name them before
        // anything is fetched, so the manifest is read first. It is held for hours after the first
        // read, so this costs one small request per session rather than one per home load.
        val catalogRows = if (builtInCatalogsEnabled) {
            Perf.timed(perf, "catalogManifest") { catalogRowOrder(fetchCatalogManifest()) }
        } else emptyList()
        perf.mark("skeletonReady", "rows=${catalogRows.size}")

        // Slots are declared up front, in final display order, so a row that resolves late lands
        // where its skeleton already was.
        val pending = linkedMapOf<String, PendingRail>()
        fun reserve(id: String, title: String, portrait: Boolean = false) {
            pending[id] = PendingRail(id, title, portrait)
        }
        reserve("continue-watching", "Continue Watching")
        reserve("new-episodes", "New Episodes", portrait = true)
        if (catalogRows.isNotEmpty()) {
            catalogRows.forEach { reserve(it.id, it.title, portrait = it.mediaType != "network") }
        } else if (builtInCatalogsEnabled) {
            reserve("popular-movies", "Popular Movies")
            reserve("popular-series", "Popular Series")
            reserve("trending", "Trending")
            reserve("recently-added", "Recently Added")
            reserve("networks", "Streaming Services")
            reserve("recommended", "Recommended For You")
        }
        reserve("addon-catalogs", "Add-on Catalogues")

        // Display order, which with the registry is only known at runtime. The pre-registry slots
        // still come from [HOME_SLOT_ORDER], so the fallback path is unchanged.
        val slotOrder = if (catalogRows.isEmpty()) buildList {
            add("continue-watching")
            add("new-episodes")
            addAll(HOME_SLOT_ORDER.filterNot { it == "continue-watching" })
        } else buildList {
            add("continue-watching")
            add("new-episodes")
            catalogRows.forEach { add(it.id) }
            add("addon-catalogs")
        }

        val resolved = linkedMapOf<String, List<HomeRail>>()
        val mutex = kotlinx.coroutines.sync.Mutex()

        suspend fun publish(slot: String, rails: List<HomeRail>) {
            val snapshot = mutex.withLock {
                resolved[slot] = rails
                pending.remove(slot)
                // Emit in declared order regardless of which slot finished first.
                val ordered = pending.keys.toList()
                val ready = resolved.keys
                    .sortedBy { key -> slotOrder.indexOf(key) }
                    .flatMap { resolved.getValue(it) }
                    .filter { it.items.isNotEmpty() }
                HomeContent(
                    featured = ready.heroCandidate(),
                    rails = orderHomeRails(ready),
                    pendingRails = ordered.mapNotNull { pending[it] },
                )
            }
            perf.mark("publish:$slot", "rails=${rails.size} items=${rails.sumOf { it.items.size }} pending=${snapshot.pendingRails.size}")
            if (snapshot.rails.isNotEmpty()) Perf.startupMark("home.firstContent", "slot=$slot")
            if (snapshot.featured != null) Perf.startupMark("home.hero")
            send(snapshot)
        }

        supervisorScope {
            launch {
                // A forced Home refresh must reach the account, not merely bypass Home's outer
                // cache and then rebuild the row from the still-stale library cache beneath it.
                val library = runCatching { fetchLibrary(forceRefresh = forceRefresh) }.getOrDefault(LibraryResponse())
                val items = library.continueWatching.map(::continueWatchingCard)
                publish("continue-watching", listOf(HomeRail("continue-watching", "Continue Watching", items)))
                // Built from the same library read rather than a second one. A television cannot
                // show a notification -- there is no drawer on Android TV or Fire OS for one to
                // land in -- so "a new episode is out" has to be somewhere the viewer already
                // looks, which is Home.
                publish("new-episodes", newEpisodeRails(library))
            }

            if (catalogRows.isNotEmpty()) {
                launch {
                    val rails = runCatching { fetchCatalogHomeRails(catalogRows) }
                        .onFailure { TvDebugLogger.w("Home", "catalog rows failed", it) }
                        .getOrDefault(emptyList())
                        .associateBy { it.id }
                    // Published per row, including the ones that came back with nothing: a slot
                    // left reserved is a skeleton that never resolves, and Home would sit
                    // permanently incomplete waiting for a row the backend deliberately dropped.
                    catalogRows.forEach { definition ->
                        publish(definition.id, listOfNotNull(rails[definition.id]))
                    }
                }
            } else if (builtInCatalogsEnabled) {
                launch { publishTmdbRails(::publish, recommendationsAvailable) }
            }

            launch {
                val addonRails = runCatching { fetchAddonCatalogRails() }.getOrDefault(emptyList())
                publish("addon-catalogs", addonRails)
            }
        }

        val complete = mutex.withLock {
            val ready = resolved.keys
                .sortedBy { key -> slotOrder.indexOf(key) }
                .flatMap { resolved.getValue(it) }
                .filter { it.items.isNotEmpty() }
            HomeContent(
                featured = ready.heroCandidate(),
                rails = orderHomeRails(ready),
            )
        }

        // Every row is fetched defensively, so a total outage produces an empty screen rather than
        // an error. Without this check the viewer is shown a blank Home with nothing to act on and
        // no hint that anything went wrong.
        if (complete.rails.isEmpty() && api.failureEpoch > failuresBefore) {
            TvDebugLogger.w("Home", "home produced nothing after backend failures")
            throw ContentUnavailableException()
        }

        homeCache[cacheKey] = complete
        perf.end("complete", "rails=${complete.rails.size} items=${complete.rails.sumOf { it.items.size }}")
        Perf.startupMark("home.allContent")
        send(complete)
    }

    /**
     * Series a viewer follows whose most recent episode landed in the last few days.
     *
     * "Follows" is both senses of the word: on the watchlist, or part-way through. Ordered newest
     * first, so the row reads as a feed of what has just dropped rather than as another catalogue.
     *
     * Empty is a perfectly ordinary answer -- most days nothing a given viewer follows has aired --
     * and an empty rail is dropped by the publisher, so the row simply is not there.
     */
    private suspend fun newEpisodeRails(library: LibraryResponse): List<HomeRail> {
        val followed = (library.watchlist + library.continueWatching.map(::continueWatchingCard))
            .filter { it.type.equals("tv", ignoreCase = true) || it.type.equals("series", ignoreCase = true) }
            .mapNotNull { item -> item.tmdbId?.takeIf { it > 0 } ?: item.id.toIntOrNull()?.takeIf { it > 0 } }
            .distinct()
        if (followed.isEmpty()) return emptyList()

        val statuses = runCatching { fetchSeriesEpisodeStatus(followed) }
            .onFailure { TvDebugLogger.w("Home", "new episodes lookup failed", it) }
            .getOrDefault(emptyList())
        if (statuses.isEmpty()) return emptyList()

        val today = java.time.LocalDate.now()
        val earliest = today.minusDays(NEW_EPISODE_WINDOW_DAYS)
        val recent = statuses.mapNotNull { entry ->
            val episode = entry.lastEpisode ?: return@mapNotNull null
            val airDate = runCatching { java.time.LocalDate.parse(episode.airDate?.trim().orEmpty()) }.getOrNull()
                ?: return@mapNotNull null
            // Dated in the future is a schedule, not a release: TMDB carries those on the last
            // episode of a series that is between seasons.
            if (airDate.isAfter(today) || airDate.isBefore(earliest)) return@mapNotNull null
            airDate to MediaItem(
                id = entry.tmdbId.toString(),
                tmdbId = entry.tmdbId,
                title = entry.title.orEmpty(),
                type = "tv",
                poster = entry.poster,
                backdrop = entry.backdrop,
                description = newEpisodeSubtitle(episode),
                rating = null,
                year = null,
                titleLogo = null,
                episode = if (episode.season != null && episode.episode != null) {
                    EpisodeContext(
                        seasonNumber = episode.season,
                        episodeNumber = episode.episode,
                        title = episode.name,
                        still = episode.still,
                        airDate = episode.airDate,
                        tmdbEpisodeId = episode.id,
                    )
                } else {
                    null
                },
                cardSubtitle = newEpisodeCardSubtitle(episode, airDate),
                cardHighlight = true,
            )
        }.sortedByDescending { (airDate, _) -> airDate }.map { (_, item) -> item }

        if (recent.isEmpty()) return emptyList()
        return listOf(HomeRail("new-episodes", "New Episodes", recent))
    }

    /** How far back the New Episodes row reaches. A week covers a weekly show plus a late look. */
    private val NEW_EPISODE_WINDOW_DAYS = 8L

    /** Batched on purpose: one request for the whole followed list, capped and cached server-side. */
    private suspend fun fetchSeriesEpisodeStatus(tmdbIds: List<Int>): List<SeriesEpisodeStatus> {
        if (tmdbIds.isEmpty()) return emptyList()
        val response = api.post<SeriesEpisodeStatusResponse>(
            "/tmdb/series/episode-status",
            mapOf("ids" to tmdbIds),
        )
        return response?.series.orEmpty()
    }

    /**
     * The line on a New Episodes card.
     *
     * Three things in the width of a poster: that this is new, which episode it is, and when it
     * landed. "NEW" leads because that is the reason the row exists -- without it the card reads
     * as just another entry in a catalogue. Word for word what the phone shows.
     */
    private fun newEpisodeCardSubtitle(episode: AiringEpisode, airDate: java.time.LocalDate): String {
        val code = when {
            episode.season != null && episode.episode != null -> "S${episode.season} E${episode.episode}"
            episode.episode != null -> "Ep ${episode.episode}"
            else -> null
        }
        val today = java.time.LocalDate.now()
        val whenLabel = when (airDate) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> airDate.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
        }
        return listOfNotNull("NEW", code, whenLabel).joinToString(" · ")
    }

    private fun newEpisodeSubtitle(episode: AiringEpisode): String {
        val code = when {
            episode.season != null && episode.episode != null -> "S%02dE%02d".format(episode.season, episode.episode)
            episode.episode != null -> "Episode ${episode.episode}"
            else -> null
        }
        val name = episode.name?.trim()?.takeIf { it.isNotEmpty() }
        return listOfNotNull(code, name).joinToString(" · ").ifBlank { "A new episode is out." }
    }

    private fun continueWatchingCard(item: ContinueWatchingItem): MediaItem = MediaItem(
        id = item.id,
        tmdbId = item.tmdbId,
        title = item.title,
        type = item.type,
        poster = item.poster,
        backdrop = item.backdrop,
        description = item.description,
        rating = item.rating,
        year = item.year,
        titleLogo = null,
        progress = item.progress,
        positionSec = item.positionSec ?: item.resumeAt,
        durationSec = item.durationSec,
        episode = item.exactEpisode(),
        // Which episode you are part-way through, said the same way the phone says it. The card
        // otherwise leant on the episode title, which many series do not carry.
        cardSubtitle = item.exactEpisode()?.let { ep ->
            val season = ep.seasonNumber
            val number = ep.episodeNumber
            if (season != null && number != null) "S$season E$number" else null
        },
    )

    /**
     * The default catalogs the backend offers, in the order it wants them shown.
     *
     * Held for [CATALOG_MANIFEST_TTL_MS]: the registry changes on backend deploys, and every home
     * load would otherwise re-ask for it. An empty list means this backend predates the registry,
     * which is what sends Home down the pre-registry path rather than leaving it blank.
     */
    /** The registry's row definitions. Read by the settings screen as well as by Home. */
    internal suspend fun fetchCatalogManifest(): List<CatalogDefinition> {
        catalogManifest?.takeIf { System.currentTimeMillis() - it.second < CATALOG_MANIFEST_TTL_MS }
            ?.let { return it.first }
        val response = runCatching { api.get<CatalogManifestResponse>("/tmdb/catalogs?region=$catalogRegion") }.getOrNull()
        val definitions = parseCatalogDefinitions(response)
        if (definitions.isNotEmpty()) catalogManifest = definitions to System.currentTimeMillis()
        return definitions
    }

    /** Which registry rows this profile wants, in the order it wants them. */
    private fun catalogRowOrder(definitions: List<CatalogDefinition>): List<CatalogDefinition> =
        orderCatalogRows(definitions, bootstrapState.value?.preferences?.home?.homeCatalogRows.orEmpty())

    /** Home previews for [definitions], as rails, in one request. */
    private suspend fun fetchCatalogHomeRails(definitions: List<CatalogDefinition>): List<HomeRail> {
        if (definitions.isEmpty()) return emptyList()
        val ids = URLEncoder.encode(definitions.joinToString(",") { it.id }, "UTF-8")
        val response = runCatching {
            api.get<CatalogHomeResponse>("/tmdb/home?region=$catalogRegion&ids=$ids")
        }.getOrNull() ?: return emptyList()
        val titles = definitions.associate { it.id to it.title }
        val order = definitions.withIndex().associate { (index, definition) -> definition.id to index }
        return response.sections
            .mapNotNull { section ->
                val id = section.id?.trim().orEmpty().ifEmpty { return@mapNotNull null }
                val items = section.results.map { it.toMediaItem(section.media_type) }.filter { it.title.isNotBlank() }.withoutAdult()
                // An empty carousel reads as a broken row, so it is dropped rather than shown.
                if (items.isEmpty()) return@mapNotNull null
                HomeRail(
                    id = id,
                    title = titles[id] ?: section.title?.takeIf { it.isNotBlank() } ?: id,
                    items = items,
                )
            }
            .sortedBy { order[it.id] ?: Int.MAX_VALUE }
    }

    /**
     * The TMDB rows. Popular falls back to Trending and Browse falls back to Popular, so these
     * share one coroutine and publish in two waves: the three rows the viewer sees first, then the
     * rest. Splitting them further would not help, since the fallbacks make them interdependent.
     *
     * Only reached when the backend has no catalog registry — see [fetchCatalogManifest].
     */
    private suspend fun publishTmdbRails(
        publish: suspend (String, List<HomeRail>) -> Unit,
        recommendationsAvailable: Boolean,
    ) = supervisorScope {
        val trendingMovie = async { safeResults<RailResponse>("/tmdb/trending/movie") }
        val trendingTv = async { safeResults<RailResponse>("/tmdb/trending/tv") }
        val popularMovie = async { safeResults<RailResponse>("/tmdb/popular/movie") }
        val popularTv = async { safeResults<RailResponse>("/tmdb/popular/tv") }
        val browseMovie = async { safeResults<RailResponse>("/tmdb/browse/movie") }
        val browseTv = async { safeResults<RailResponse>("/tmdb/browse/tv") }
        val networks = async { safeResults<NetworkResponse>("/tmdb/networks") }
        val recMovie = async {
            if (recommendationsAvailable) safeResults<RailResponse>("/trakt/recommendations/movies") else emptyList()
        }
        val recTv = async {
            if (recommendationsAvailable) safeResults<RailResponse>("/trakt/recommendations/shows") else emptyList()
        }

        val trendingMovies = trendingMovie.await()
        val trendingShows = trendingTv.await()
        val popularMovies = popularMovie.await().ifEmpty { trendingMovies }
        val popularShows = popularTv.await().ifEmpty { trendingShows }

        publish("popular-movies", listOf(HomeRail("popular-movies", "Popular Movies", popularMovies)))
        publish("popular-series", listOf(HomeRail("popular-series", "Popular Series", popularShows)))
        publish("trending", listOf(HomeRail("trending", "Trending", (trendingMovies + trendingShows).take(20))))

        val browseMovies = browseMovie.await().ifEmpty { popularMovies }
        val browseShows = browseTv.await().ifEmpty { popularShows }
        val recentlyAdded = (browseMovies + browseShows)
            .distinctBy { "${it.type}:${it.id}" }
            .sortedByDescending { it.year?.toIntOrNull() ?: 0 }
            .take(20)
        publish("recently-added", listOf(HomeRail("recently-added", "Recently Added", recentlyAdded)))
        publish("networks", listOf(HomeRail("networks", "Streaming Services", networks.await())))

        val recommendedMovies = recMovie.await().ifEmpty { popularMovies }
        val recommendedShows = recTv.await().ifEmpty { popularShows }
        publish(
            "recommended",
            listOf(HomeRail("recommended", "Recommended For You", (recommendedMovies + recommendedShows).take(20))),
        )
    }

    /**
     * The title the hero shows.
     *
     * Continue Watching is skipped because the hero is for discovery, and service tiles and live
     * channels are skipped because neither has the artwork or the synopsis the hero is built
     * around. That matters more now the first row is the registry's choice rather than a fixed
     * one: Streaming Services can legitimately be laid out above everything else.
     */
    private fun List<HomeRail>.heroCandidate(): MediaItem? {
        val eligible = firstNotNullOfOrNull { rail ->
            if (rail.id == "continue-watching") return@firstNotNullOfOrNull null
            rail.items.firstOrNull { it.type != "network" && it.type != "live" }
        }
        return eligible ?: firstOrNull()?.items?.firstOrNull()
    }

    /**
     * Matches the mobile app's ordering: live add-on rows sit directly below Streaming Services,
     * everything else from add-ons goes to the end.
     */
    /**
     * The finished row order, with the viewer's layout applied to every row rather than only the
     * built-in ones.
     *
     * Add-on rows used to bypass the layout entirely: [catalogRowOrder] applies it to the registry
     * catalogues, and nothing applied it here, so a row switched off on the phone still arrived on
     * the television. Since add-on rows are most of them for anyone running a catalogue add-on, the
     * setting looked broken rather than partial.
     *
     * The grouping below still decides where add-on rows sit relative to the built-in ones when the
     * viewer has expressed no preference; [applyHomeRowLayout] then has the final say, and does
     * nothing at all when there is no layout to apply.
     */
    private fun orderHomeRails(rails: List<HomeRail>): List<HomeRail> {
        val (addonRails, baseRails) = rails.partition { it.id.startsWith(ADDON_RAIL_PREFIX) }
        val (liveAddonRails, otherAddonRails) = addonRails.partition { it.isLive }
        val ordered = baseRails.toMutableList()
        val networksIndex = ordered.indexOfFirst { it.id == "networks" || it.id == NETWORKS_CATALOG_ID }
        if (liveAddonRails.isNotEmpty()) {
            if (networksIndex >= 0) ordered.addAll(networksIndex + 1, liveAddonRails) else ordered.addAll(liveAddonRails)
        }
        ordered.addAll(otherAddonRails)
        return applyHomeRowLayoutKeepingPersonalRows(
            ordered,
            bootstrapState.value?.preferences?.home?.homeCatalogRows.orEmpty(),
        )
    }

    /** The finished screen. Callers that cannot render progressively still get one value. */
    suspend fun fetchHomeContent(forceRefresh: Boolean = false): HomeContent =
        homeContentStream(forceRefresh).last()


    suspend fun fetchDetail(id: String, type: String, forceRefresh: Boolean = false): MediaDetail? {
        val perf = Perf.span("detail", "$type:$id")
        try {
        val canonicalType = if (type == "series") "tv" else type
        val imdbId = Regex("tt\\d+", RegexOption.IGNORE_CASE).find(id)?.value
        val resolved = if (imdbId != null) {
            runCatching {
                api.get<TmdbFindResponse>("/tmdb/find/imdb/$imdbId?type=$canonicalType")
            }.getOrNull()?.takeIf { it.id > 0 }
        } else {
            null
        }
        val resolvedType = resolved?.type?.let { if (it == "series") "tv" else it } ?: canonicalType
        val resolvedId = resolved?.id?.toString() ?: id
        val cacheKey = "$resolvedType:$resolvedId"
        if (!forceRefresh) {
            // A cache hit is still the viewer opening the title, so it counts. Missing these
            // would understate the top of the funnel for exactly the titles people revisit most.
            detailsCache[cacheKey]?.let {
                Telemetry.contentOpened(mediaId = it.id, mediaType = it.type, title = it.title)
                return it
            }
        }
        val detail = (api.get<MediaDetail>("/tmdb/details/$resolvedType/$resolvedId")
            ?: fetchAddonMetaDetail(resolvedId, canonicalType))?.let { resolvedDetail ->
            if (resolvedDetail.type == "tv") {
                val releasedSeasons = availableSeasons(resolvedDetail.seasons)
                resolvedDetail.copy(
                    seasons = releasedSeasons,
                    numberOfSeasons = releasedSeasons.size,
                    numberOfEpisodes = releasedSeasons.sumOf(SeasonRef::episodeCount),
                )
            } else resolvedDetail
        }
        if (detail != null) {
            detailsCache[cacheKey] = detail
            Telemetry.contentOpened(mediaId = detail.id, mediaType = detail.type, title = detail.title)
        }
        return detail
        } finally { perf.end() }
    }

    /**
     * Falls back to whichever installed add-on can describe this item, for cards TMDB cannot
     * resolve — a metadata add-on's `tmdb:`/`kitsu:` id, a bridge's own id, a title TMDB has never
     * heard of. Without this the detail screen simply reported that it could not load the title.
     */
    private suspend fun fetchAddonMetaDetail(id: String, canonicalType: String): MediaDetail? {
        // Add-on routes speak Stremio's spelling: 'series', never TMDB's 'tv'.
        val addonType = if (canonicalType == "tv") "series" else canonicalType
        val response = runCatching {
            api.get<AddonMetaResponse>("/addons/meta/$addonType/${encodePathSegment(id)}")
        }.onFailure { TvDebugLogger.w("Detail", "addon meta lookup failed type=$addonType id=$id") }
            .getOrNull()
        val meta = response?.meta ?: return null
        if (!isUsableAddonMeta(meta, id)) {
            TvDebugLogger.w("Detail", "addon meta unusable addon=${response.addonName.orEmpty()} id=${meta.id.orEmpty()}")
            return null
        }
        val seasonNumbers = meta.videos.mapNotNull { it.season }.filter { it > 0 }.distinct().sorted()
        return MediaDetail(
            id = id,
            title = meta.name?.trim().orEmpty().ifBlank { return null },
            type = if (meta.videos.isNotEmpty() || addonType == "series") "tv" else "movie",
            poster = meta.poster,
            backdrop = meta.background ?: meta.poster,
            description = sequenceOf(meta.description, meta.overview, meta.synopsis)
                .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
                .firstOrNull(),
            rating = meta.imdbRating?.toDoubleOrNull(),
            year = meta.releaseInfo?.let { Regex("(19|20)\\d{2}").find(it)?.value },
            imdbId = meta.imdbId?.takeIf { it.startsWith("tt") } ?: id.takeIf { it.startsWith("tt") },
            titleLogo = meta.logo,
            genreNames = meta.genres,
            seasons = seasonNumbers.map { season -> SeasonRef(seasonNumber = season, name = "Season $season") },
        )
    }


    suspend fun fetchTraktComments(id: String, type: String): List<TraktCommentItem> {
        return api.get<TraktCommentsResponse>("/trakt/comments/$type/$id")?.results.orEmpty()
    }

    suspend fun fetchPerson(id: String): PersonDetail? {
        val raw = api.get<com.google.gson.JsonObject>("/tmdb/person/${encodePathSegment(id)}") ?: return null
        val personJson = raw.getAsJsonObject("person") ?: raw
        val person = runCatching { api.gson.fromJson(personJson, PersonDetail::class.java) }.getOrNull() ?: return null
        val worksJson = sequenceOf("popularWorks", "knownFor", "credits")
            .mapNotNull { key -> raw.getAsJsonArray(key) }
            .firstOrNull()
        val works = worksJson?.let { array ->
            val type = object : com.google.gson.reflect.TypeToken<List<MediaItem>>() {}.type
            runCatching { api.gson.fromJson<List<MediaItem>>(array, type) }.getOrDefault(emptyList())
        }.orEmpty()
        return person.copy(popularWorks = person.popularWorks.ifEmpty { works })
            .takeIf { it.name.isNotBlank() }
    }

    suspend fun fetchSeason(id: String, seasonNumber: Int, forceRefresh: Boolean = false): SeasonDetail? {
        val cacheKey = "$id:$seasonNumber"
        if (!forceRefresh) {
            seasonCache[cacheKey]?.let { return it }
        }
        val detail = api.get<SeasonDetail>("/tmdb/season/$id/$seasonNumber")
        if (detail != null) {
            seasonCache[cacheKey] = detail
        }
        return detail
    }

    suspend fun fetchLibrary(forceRefresh: Boolean = false): LibraryResponse {
        val cacheKey = buildSessionProfileCacheKey()
        if (!forceRefresh) {
            libraryCache[cacheKey]?.let { return it }
        }
        TvDebugLogger.i(
            "Library",
            "fetchLibrary forceRefresh=$forceRefresh user=$cacheKey profile=${sessionStore.activeProfileId() ?: "none"}",
        )
        val failuresBefore = api.failureEpoch
        val library = runCatching {
            api.get<LibraryResponse>("/sync/library")
        }.onFailure {
            TvDebugLogger.e("Library", "fetchLibrary failed", it)
        }.getOrNull() ?: LibraryResponse()
        val servicePlayback = fetchServicePlayback()
        val mergedContinueWatching = mergeContinueWatching(
            primary = library.continueWatching,
            secondary = servicePlayback,
            progressRecords = library.progress,
        )
        // /sync/library follows the profile's tracking service, so its watchlist is normally the
        // right one already. The direct read stays as a safety net for a TV running ahead of a
        // backend that still answers with Trakt's list only.
        // SyncDek is served by /sync/library itself, so an empty answer there is a genuinely
        // empty watchlist. Falling through would show a connected provider list instead, which
        // reads as the setting having been ignored.
        val watchlist = if (
            library.watchlist.isNotEmpty() ||
            primarySyncService() == SyncServiceId.TRAKT ||
            primarySyncService() == SyncServiceId.SYNCDEK
        ) {
            library.watchlist
        } else {
            fetchServiceWatchlist() ?: library.watchlist
        }
        val merged = library.copy(
            // Removing one progress row is an optimistic, targeted edit. A tracking provider may
            // still return its pre-dismissal snapshot for a short time, so keep the local removal
            // over that response instead of replacing the whole Library grid with stale state.
            continueWatching = applyPendingContinueDismissals(cacheKey, mergedContinueWatching),
            // A provider can briefly return its pre-write snapshot. Keep confirmed edits over
            // that answer long enough for Trakt/SIMKL/MDBList to converge, including when the
            // viewer leaves Library and comes straight back.
            watchlist = applyPendingWatchlistMutations(cacheKey, watchlist),
        )
        // An empty library is perfectly normal for a new account, so only an empty result that
        // also had failed requests behind it is reported as a problem worth showing.
        if (merged.continueWatching.isEmpty() && merged.watchlist.isEmpty() && api.failureEpoch > failuresBefore) {
            TvDebugLogger.w("Library", "library came back empty after backend failures")
            throw ContentUnavailableException("Your library could not be loaded. Check the connection and try again.")
        }
        TvDebugLogger.i(
            "Library",
            "fetchLibrary ok continue=${merged.continueWatching.size} watchlist=${merged.watchlist.size} " +
                "service=${primarySyncService()} serviceContinue=${servicePlayback.size}",
        )
        libraryCache[cacheKey] = merged
        return merged
    }

    suspend fun searchMedia(query: String, forceRefresh: Boolean = false): List<MediaItem> {
        val perf = Perf.span("search")
        try {
        val normalized = query.trim()
        if (normalized.isBlank()) return emptyList()
        val cacheKey = buildSessionProfileCacheKey() + ":" + normalized.lowercase(Locale.US)
        if (!forceRefresh) {
            searchCache[cacheKey]?.let { return it }
        }
        val encoded = URLEncoder.encode(normalized, "UTF-8")
        val (tmdbResults, liveResults) = supervisorScope {
            val tmdb = async {
                runCatching { api.get<PagedRailResponse>("/tmdb/search?q=$encoded")?.results.orEmpty() }
                    .getOrDefault(emptyList())
                    .filter { it.type == "movie" || it.type == "tv" }
            }
            val live = async {
                fetchAddonCatalogCollections { _, mappedType -> mappedType == "live" }
                    .flatMap { it.items }
                    .filter { item ->
                        sequenceOf(
                            item.title,
                            item.description.orEmpty(),
                            item.sourceAddonName.orEmpty(),
                            item.sourceCatalogName.orEmpty(),
                        ).any { value -> value.contains(normalized, ignoreCase = true) }
                    }
            }
            tmdb.await() to live.await()
        }
        val results = (liveResults + tmdbResults)
            .distinctBy { item -> listOf(item.type, item.sourceAddonId.orEmpty(), item.sourceCatalogId.orEmpty(), item.id).joinToString(":") }
        searchCache[cacheKey] = results
        // Only the fresh lookups: a cache hit is the same search the viewer already made, and
        // counting it again would report a search that never reached anything.
        Telemetry.searchPerformed(results.size)
        return results
        } finally { perf.end() }
    }
    /**
     * Asks every enabled add-on catalog that advertises search to answer the same query.
     *
     * Kept apart from [searchMedia] rather than folded into it: TMDB answers in one round trip
     * while add-ons answer at their own pace, and waiting for the slowest add-on before showing
     * anything would make every search feel as slow as the worst provider. The Search screen runs
     * the two side by side and appends these when they land.
     *
     * Goes straight to each add-on. The backend's catalog route takes no extras, so there is no
     * way to pass a query through it, and this works for locally-reachable add-ons unchanged.
     */
    suspend fun searchAddonCatalogs(query: String, forceRefresh: Boolean = false): List<MediaItem> {
        val normalized = query.trim()
        if (normalized.length < 2) return emptyList()
        val cacheKey = buildSessionProfileCacheKey() + ":addon:" + normalized.lowercase(Locale.US)
        if (!forceRefresh) {
            addonSearchCache[cacheKey]?.let { return it }
        }
        val addons = runCatching { fetchAddonManifests() }.getOrDefault(emptyList())
            .filter { it.enabled }
            .sortedWith(compareByDescending<AddonManifest> { it.favourite }.thenBy { it.position })
        val searchable = addons.flatMap { addon ->
            addon.manifest.catalogs.filter { it.supportsSearch }.map { addon to it }
        }
        if (searchable.isEmpty()) return emptyList()

        // Enough to keep a wall of add-ons from queueing behind each other, few enough not to
        // open a socket per catalog on a TV box.
        val gate = Semaphore(5)
        val found = supervisorScope {
            searchable.map { (addon, catalog) ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val rawType = catalog.type.trim().lowercase(Locale.US)
                        val mappedType = mapAddonCatalogType(rawType) ?: return@withPermit emptyList()
                        val catalogId = catalog.id.trim()
                        if (catalogId.isBlank()) return@withPermit emptyList()
                        fetchAddonCatalogDirect(
                            addon = addon,
                            rawType = rawType,
                            catalogId = catalogId,
                            // A catalog that insists on a genre still needs one alongside the query.
                            genre = catalog.defaultGenre,
                            search = normalized,
                        )
                            .filterNot(::isAddonCatalogDiagnosticMeta)
                            .mapNotNull { meta ->
                                normalizeAddonCatalogMeta(
                                    meta = meta,
                                    fallbackType = mappedType,
                                    nativeFallbackType = rawType,
                                    addonId = addon.id,
                                    addonName = addon.manifest.name,
                                    catalogId = catalogId,
                                    catalogName = catalog.name,
                                )
                            }
                    }
                }
            }.awaitAll().flatten()
        }
        // Add-ons answer their own way and some ignore the query entirely, returning their default
        // listing, so matches are checked here rather than trusted wholesale.
        val results = found
            .mapNotNull { item -> addonSearchRank(item, normalized)?.let { it to item } }
            .sortedWith(compareBy({ it.first }, { it.second.title.lowercase(Locale.US) }))
            .map { it.second }
            .distinctBy { item ->
                listOf(item.type, item.sourceAddonId.orEmpty(), item.sourceCatalogId.orEmpty(), item.id).joinToString(":")
            }
        TvDebugLogger.i("Search", "addon catalog search '$normalized' catalogs=${searchable.size} results=${results.size}")
        addonSearchCache[cacheKey] = results
        return results
    }

    /**
     * How well an add-on result answers the query, lowest first, or null when it does not.
     *
     * Title and catalog name only. An add-on's descriptions are often built from its own name and
     * group, so matching those too would let one matching word pull in the whole catalog.
     */
    private fun addonSearchRank(item: MediaItem, needle: String): Int? = when {
        item.title.equals(needle, ignoreCase = true) -> 0
        item.title.startsWith(needle, ignoreCase = true) -> 1
        item.title.contains(needle, ignoreCase = true) -> 2
        item.sourceCatalogName?.contains(needle, ignoreCase = true) == true -> 3
        else -> null
    }

    suspend fun fetchGenres(type: String, forceRefresh: Boolean = false): List<GenreItem> {
        val normalized = if (type == "tv") "tv" else "movie"
        if (!forceRefresh) {
            genreCache[normalized]?.let { return it }
        }
        val genres = api.get<GenreResponse>("/tmdb/genres/$normalized")?.genres.orEmpty()
        genreCache[normalized] = genres
        return genres
    }

    /**
     * Discover browse used by the Search screen, mirroring the mobile app's Discover
     * section. "documentary" is a movie query pinned to TMDB's documentary genre, and a
     * "before:YYYY" year filter becomes a release-date cutoff rather than an exact year.
     */
    suspend fun fetchDiscover(
        type: String,
        page: Int = 1,
        genreId: Int? = null,
        year: String? = null,
        forceRefresh: Boolean = false,
    ): PagedRailResponse {
        val requestedType = type.trim().lowercase(Locale.US)
        val isDocumentary = requestedType == "documentary"
        val effectiveType = if (isDocumentary) "movie" else if (requestedType == "tv") "tv" else "movie"
        val params = mutableListOf("type=$effectiveType", "page=$page")
        when {
            isDocumentary -> params += "genre_id=99"
            genreId != null -> params += "genre_id=$genreId"
        }
        if (!year.isNullOrBlank()) {
            if (year.startsWith("before:")) {
                val cutoff = year.removePrefix("before:").trim()
                if (cutoff.isNotBlank()) {
                    val encodedCutoff = URLEncoder.encode("$cutoff-12-31", "UTF-8")
                    params += if (effectiveType == "tv") {
                        "first_air_date.lte=$encodedCutoff"
                    } else {
                        "primary_release_date.lte=$encodedCutoff"
                    }
                }
            } else {
                params += "year=${URLEncoder.encode(year, "UTF-8")}"
            }
        }
        val query = "/tmdb/discover?${params.joinToString("&")}"
        val cacheKey = "discover:$query"
        if (!forceRefresh) {
            networkCache[cacheKey]?.let { return it }
        }
        val response = api.get<PagedRailResponse>(query) ?: PagedRailResponse()
        networkCache[cacheKey] = response
        return response
    }

    suspend fun fetchNetworkCatalog(
        networkId: String,
        type: String = "all",
        year: String? = null,
        genreId: Int? = null,
        sort: String = "year",
        page: Int = 1,
        forceRefresh: Boolean = false,
    ): PagedRailResponse {
        val cacheKey = listOf(networkId, type, year.orEmpty(), genreId?.toString().orEmpty(), sort, page.toString()).joinToString(":")
        if (!forceRefresh) {
            networkCache[cacheKey]?.let { return it }
        }
        val query = buildString {
            append("/tmdb/network/$networkId?page=$page&type=$type&sort=$sort")
            if (!year.isNullOrBlank()) append("&year=${URLEncoder.encode(year, "UTF-8")}")
            if (genreId != null) append("&genre_id=$genreId")
        }
        val response = api.get<PagedRailResponse>(query) ?: PagedRailResponse()
        networkCache[cacheKey] = response
        return response
    }

    /**
     * The tracking service this profile has chosen. Picked up from the profile-scoped home
     * preferences that mobile and the web portal write, so all three clients agree on where a
     * watchlist toggle lands.
     */
    fun primarySyncService(): String =
        SyncServiceId.normalize(bootstrapState.value?.preferences?.home?.primarySyncService)

    fun isSyncServiceConnected(service: String): Boolean {
        val integrations = bootstrapState.value?.integrations ?: return false
        return when (SyncServiceId.normalize(service)) {
            SyncServiceId.SIMKL -> integrations.simkl.connected
            SyncServiceId.MDBLIST -> integrations.mdblist.connected
            SyncServiceId.PUNCHPLAY -> integrations.punchplay.connected
            else -> integrations.trakt.connected || bootstrapState.value?.syncStatus?.traktConnected == true
        }
    }

    /**
     * The services to try, in order, for a watchlist or resume-point call.
     *
     * The profile's own choice comes first. Trakt is kept as a backstop whenever it is also
     * connected: a profile that switched to Simkl last week still has years of Trakt history, and
     * a service that is unreachable or half-configured should cost the viewer an empty screen for
     * as short a time as possible. A service that cannot do the job at all is skipped outright.
     */
    private fun syncServiceChain(requires: (SyncServiceCapabilities) -> Boolean): List<String> {
        val primary = primarySyncService()
        val connected = SyncServiceId.all
            .filterTo(linkedSetOf(), ::isSyncServiceConnected)
        return orderedConnectedSyncServices(primary, connected)
            .filter { requires(SyncServiceCapabilities.of(it)) }
    }

    suspend fun isInWatchlist(item: MediaItem, forceRefresh: Boolean = false): Boolean =
        fetchLibrary(forceRefresh).watchlist.any { sameWatchlistTitle(it, item) }

    suspend fun addToWatchlist(item: MediaItem) {
        updateWatchlist(item, remove = false)
    }

    suspend fun removeFromWatchlist(item: MediaItem) {
        updateWatchlist(item, remove = true)
    }

    private suspend fun updateWatchlist(item: MediaItem, remove: Boolean) {
        val tmdbId = item.tmdbId.takeIf { it > 0 }
            ?: Regex("(?:tmdb:)?(\\d+)$", RegexOption.IGNORE_CASE).find(item.id)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val entry: Map<String, Any?> = mapOf(
            "title" to item.title,
            "year" to item.year?.toIntOrNull(),
            "ids" to mapOf<String, Any?>("tmdb" to tmdbId),
        )
        val isSeries = item.type.trim().lowercase(Locale.US) in setOf("tv", "series", "show")
        val payload = if (isSeries) {
            mapOf("movies" to emptyList<Any>(), "shows" to listOf(entry))
        } else {
            mapOf("movies" to listOf(entry), "shows" to emptyList<Any>())
        }
        val action = if (remove) "remove" else "add"
        // Match mobile: a watchlist edit fans out to every connected tracking provider. The
        // selected primary service still owns what Library reads; fan-out keeps Trakt, SIMKL and
        // MDBList in agreement when the viewer changes that selection on another device.
        // StreamDek's own list is written every time, whatever the profile tracks with, so that
        // choosing SyncDek later reveals a watchlist that is already there. It leads the chain
        // because it is the one write that cannot fail for want of a linked account.
        val services = listOf(SyncServiceId.SYNCDEK) + syncServiceChain { it.watchlistWrite }
        val sourceService = services.first()
        var sourceUpdated = false
        for (service in services) {
            val path = if (service == SyncServiceId.SYNCDEK) "/sync/watchlist/$action" else "/$service/sync/watchlist/$action"
            val response = api.post<Map<String, Any>>(path, payload)
            if (response != null) {
                if (service == sourceService) sourceUpdated = true
            } else {
                TvDebugLogger.w("Watchlist", "$action failed on $service for ${item.type}:${item.id}")
            }
        }
        if (!sourceUpdated) throw ContentUnavailableException("Could not ${if (remove) "remove this title from" else "add this title to"} your watchlist.")
        // Publish the successful mutation immediately. Tracking providers can be eventually
        // consistent; force-reading in the same frame used to replace the whole grid with a
        // transient empty response and later resurrect the item that had just been removed.
        val cacheKey = buildSessionProfileCacheKey()
        rememberPendingWatchlistMutation(cacheKey, item, remove)
        libraryCache[cacheKey]?.let { current ->
            libraryCache[cacheKey] = current.copy(
                watchlist = mutateWatchlistSnapshot(current.watchlist, item, remove),
            )
        }
    }

    /**
     * Watchlist for the profile's tracking service. `/sync/library` enriches Trakt only, so any
     * other primary service has to be read from its own route.
     */
    private suspend fun fetchServiceWatchlist(): List<MediaItem>? {
        val services = syncServiceChain { it.watchlist }
        for (service in services) {
            val results = api.get<WatchlistEnvelope>("/$service/sync/watchlist/enriched")?.results
            if (results != null) return results
            TvDebugLogger.w("Watchlist", "could not read the $service watchlist")
        }
        return null
    }

    suspend fun markWatched(
        mediaType: String,
        mediaId: String,
        title: String,
        year: String? = null,
        episode: EpisodeContext? = null,
        imdbId: String? = null,
    ): Boolean {
        val watchedAt = Instant.now().toString()
        val parsedTmdbId = mediaId.toIntOrNull()
        val parsedYear = year?.take(4)?.toIntOrNull()
        val payload = if (mediaType == "tv" && episode != null) {
            mapOf(
                "movies" to emptyList<Any>(),
                "shows" to listOf(
                    mapOf(
                        "title" to title,
                        "ids" to mapOf(
                            "tmdb" to parsedTmdbId,
                            "imdb" to imdbId,
                        ),
                        "seasons" to listOf(
                            mapOf(
                                "number" to episode.seasonNumber,
                                "episodes" to listOf(
                                    mapOf(
                                        "number" to episode.episodeNumber,
                                        "watched_at" to watchedAt,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        } else {
            mapOf(
                "movies" to listOf(
                    mapOf(
                        "title" to title,
                        "year" to parsedYear,
                        "ids" to mapOf(
                            "tmdb" to parsedTmdbId,
                            "imdb" to imdbId,
                        ),
                        "watched_at" to watchedAt,
                    ),
                ),
                "shows" to emptyList<Any>(),
            )
        }

        return runCatching {
            val ok = api.post<Any>("/trakt/sync/watched", payload) != null
            if (ok) {
                invalidatePlaybackDerivedCaches()
            }
            ok
        }.onFailure {
            TvDebugLogger.w("Trakt", "markWatched failed mediaType=$mediaType mediaId=$mediaId")
        }.getOrDefault(false)
    }

    /**
     * Records a title as finished on the account.
     *
     * Written rather than deleted. "Mark as watched" used to clear the progress rows, and a
     * deletion cannot travel through a list of records, so the phone learnt nothing about it -- a
     * finished row goes down the same path that watching something to the end already uses.
     *
     * A whole series carries no episode key, so its in-progress episodes are dropped first;
     * otherwise the marker and the half-watched episode it is meant to retire would both come
     * back in the same sync.
     */
    private suspend fun markProgressWatched(item: MediaItem): Boolean {
        val episode = item.episode
        return runCatching {
            if (item.type == "tv" && episode == null) {
                api.delete<Any>("/sync/progress/tv/${item.id}")
            }
            api.request<Any>(
                method = "PUT",
                path = "/sync/progress",
                body = com.google.gson.Gson().toJson(
                    mapOf(
                        "entityType" to item.type,
                        "entityId" to item.id,
                        "episodeKey" to buildEpisodeKey(episode),
                        "positionSec" to 0,
                        "durationSec" to 0,
                        // Said outright: there is no position to work it out from.
                        "completed" to true,
                        "updatedAt" to Instant.now().toString(),
                        "lastDevice" to "StreamDek TV",
                        "lastPlatform" to "tv",
                        "metadata" to mapOf(
                            "title" to item.title,
                            "posterUrl" to item.poster,
                            "backdropUrl" to item.backdrop,
                            "year" to item.year,
                            "seasonNumber" to episode?.seasonNumber,
                            "episodeNumber" to episode?.episodeNumber,
                            "episodeTitle" to episode?.title,
                        ),
                    ),
                ),
            )
            invalidatePlaybackDerivedCaches()
            true
        }.onFailure {
            TvDebugLogger.w("Playback", "markProgressWatched failed mediaType=${item.type} mediaId=${item.id}")
        }.getOrDefault(false)
    }

    /**
     * Marks a browse item watched.
     *
     * The account write is what decides the outcome. Trakt is attempted alongside it and its
     * result is not the answer: this used to return whatever Trakt said, so on a profile with no
     * tracking service connected the press reported failure and genuinely did nothing -- it did
     * not even clear the progress, because that was gated on the same result.
     */
    suspend fun markBrowseItemWatched(item: MediaItem): Boolean {
        val recorded = markProgressWatched(item)
        if (item.type == "tv" && item.episode == null) {
            markSeriesWatched(
                mediaId = item.id,
                title = item.title,
                year = item.year,
            )
        } else {
            markWatched(
                mediaType = item.type,
                mediaId = item.id,
                title = item.title,
                year = item.year,
                episode = item.episode,
            )
        }
        return recorded
    }

    suspend fun markSeasonWatched(
        mediaId: String,
        title: String,
        year: String?,
        seasonNumber: Int,
    ): Boolean = setSeasonWatched(mediaId, title, year, seasonNumber, watched = true)

    /** Marks every regular episode before [selected] in one SyncDek/provider operation. */
    suspend fun markPreviousEpisodesWatched(detail: MediaDetail, selected: EpisodeContext): Boolean {
        val seasons = detail.seasons
            .map(SeasonRef::seasonNumber)
            .filter { it > 0 && it <= selected.seasonNumber }
            .distinct()
            .sorted()
        val previous = supervisorScope {
            seasons.map { seasonNumber -> async { fetchSeason(detail.id, seasonNumber) } }
                .mapNotNull { it.await() }
        }.flatMap { season ->
            season.episodes.filter { episode ->
                val beforeSelected = season.seasonNumber < selected.seasonNumber || episode.episodeNumber < selected.episodeNumber
                val released = episode.airDate?.take(10)?.let { date ->
                    runCatching { !java.time.LocalDate.parse(date).isAfter(java.time.LocalDate.now()) }.getOrDefault(true)
                } ?: true
                beforeSelected && released
            }.map { episode ->
                EpisodeContext(
                    seasonNumber = season.seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    title = episode.name,
                    overview = episode.overview,
                    still = episode.still,
                    runtime = episode.runtime,
                    airDate = episode.airDate,
                    tmdbEpisodeId = episode.id,
                )
            }
        }
        if (previous.isEmpty()) return true
        val updatedAt = Instant.now().toString()
        val items = previous.map { episode ->
            mapOf(
                "entityType" to "tv",
                "entityId" to detail.id,
                "episodeKey" to buildEpisodeKey(episode),
                "positionSec" to 0,
                "durationSec" to 0,
                "completed" to true,
                "updatedAt" to updatedAt,
                "lastDevice" to "StreamDek TV",
                "lastPlatform" to "tv",
                "metadata" to buildSyncMetadata(detail, episode),
            )
        }
        val syncDek = runCatching {
            api.request<Any>(
                method = "POST",
                path = "/sync/progress/batch",
                body = com.google.gson.Gson().toJson(mapOf("items" to items)),
            )
            true
        }.onFailure {
            TvDebugLogger.w("Playback", "previous episodes batch failed mediaId=${detail.id}")
        }.getOrDefault(false)
        if (!syncDek) return false

        if (isSyncServiceConnected(SyncServiceId.TRAKT)) {
            val traktSeasons = previous.groupBy(EpisodeContext::seasonNumber).map { (season, episodes) ->
                mapOf(
                    "number" to season,
                    "episodes" to episodes.map { mapOf("number" to it.episodeNumber, "watched_at" to updatedAt) },
                )
            }
            val payload = mapOf(
                "movies" to emptyList<Any>(),
                "shows" to listOf(mapOf(
                    "title" to detail.title,
                    "ids" to mapOf("tmdb" to detail.id.toIntOrNull(), "imdb" to detail.imdbId),
                    "seasons" to traktSeasons,
                )),
            )
            runCatching { api.post<Any>("/trakt/sync/watched", payload) }
                .onFailure { TvDebugLogger.w("Trakt", "previous episodes sync failed mediaId=${detail.id}") }
        }
        invalidatePlaybackDerivedCaches()
        return true
    }

    suspend fun setSeasonWatched(
        mediaId: String,
        title: String,
        year: String?,
        seasonNumber: Int,
        watched: Boolean,
        seasonDetail: SeasonDetail? = null,
    ): Boolean {
        val detail = fetchDetail(mediaId, "tv") ?: return false
        val season = seasonDetail ?: fetchSeason(mediaId, seasonNumber) ?: return false
        if (season.episodes.isEmpty()) return false
        val resolvedDetail = detail.copy(title = title.ifBlank { detail.title }, year = year ?: detail.year)
        val updatedAt = Instant.now().toString()
        val items = season.episodes.map { episode ->
            val context = EpisodeContext(
                seasonNumber = seasonNumber,
                episodeNumber = episode.episodeNumber,
                title = episode.name,
                overview = episode.overview,
                still = episode.still,
                runtime = episode.runtime,
                airDate = episode.airDate,
                tmdbEpisodeId = episode.id,
            )
            mapOf(
                "entityType" to "tv",
                "entityId" to mediaId,
                "episodeKey" to buildEpisodeKey(context),
                "positionSec" to 0,
                "durationSec" to 0,
                "completed" to watched,
                "unwatched" to !watched,
                "updatedAt" to updatedAt,
                "lastDevice" to "StreamDek TV",
                "lastPlatform" to "tv",
                "metadata" to buildSyncMetadata(resolvedDetail, context),
            )
        }
        val syncDek = runCatching {
            api.request<Any>(
                method = "POST",
                path = "/sync/progress/batch",
                body = com.google.gson.Gson().toJson(mapOf("items" to items)),
            )
            true
        }.onFailure {
            TvDebugLogger.w("Playback", "season watched batch failed mediaId=$mediaId season=$seasonNumber")
        }.getOrDefault(false)

        if (syncDek && isSyncServiceConnected(SyncServiceId.TRAKT)) {
            val endpoint = if (watched) "/trakt/sync/watched" else "/trakt/sync/history/remove"
            val payload = mapOf(
                "movies" to emptyList<Any>(),
                "shows" to listOf(mapOf(
                    "title" to resolvedDetail.title,
                    "ids" to mapOf("tmdb" to mediaId.toIntOrNull(), "imdb" to resolvedDetail.imdbId),
                    "seasons" to listOf(mapOf(
                        "number" to seasonNumber,
                        "episodes" to season.episodes.map { episode ->
                            mapOf("number" to episode.episodeNumber, "watched_at" to updatedAt)
                        },
                    )),
                )),
            )
            runCatching { api.post<Any>(endpoint, payload) }
                .onFailure { TvDebugLogger.w("Trakt", "season watched sync failed mediaId=$mediaId season=$seasonNumber") }
        }
        if (syncDek) invalidatePlaybackDerivedCaches()
        return syncDek
    }

    /** One lightweight SyncDek read used while a series detail page is visible on another device. */
    suspend fun fetchSyncedEpisodeWatchState(mediaId: String): SyncedEpisodeWatchState {
        val records = fetchSeriesProgressRecords(mediaId)
        val completed = linkedSetOf<String>()
        val unwatched = linkedSetOf<String>()
        records.forEach { record ->
            val key = record.compactEpisodeKey() ?: return@forEach
            when (record.status.lowercase()) {
                "completed" -> completed += key
                "unwatched" -> unwatched += key
            }
        }
        return SyncedEpisodeWatchState(completed, unwatched)
    }

    suspend fun clearProgress(
        mediaType: String,
        mediaId: String,
        episode: EpisodeContext? = null,
    ): Boolean {
        val path = buildString {
            append("/sync/progress/$mediaType/$mediaId")
            buildEpisodeKey(episode)?.let { append("?episodeKey=$it") }
        }
        return runCatching {
            api.delete<Any>(path)
            invalidatePlaybackDerivedCaches()
            true
        }.onFailure {
            TvDebugLogger.w("Playback", "clearProgress failed mediaType=$mediaType mediaId=$mediaId")
        }.getOrDefault(false)
    }

    /**
     * Hides one Continue Watching row without claiming the viewer completed it.
     *
     * A deletion alone is not durable when Trakt or another provider still reports the playback
     * row. A dismissed progress tombstone travels through SyncDek and suppresses that stale provider
     * result on every device until genuinely new playback replaces it.
     *
     * This is the television's only removal path -- the card menu on Home and in Library both reach
     * it -- and it now calls SyncDek's canonical removal rather than assembling the tombstone here.
     * The server owns the whole lifecycle: recording the intent under an identity every source can
     * be matched against, dropping the provider caches that would otherwise replay the title for
     * the next few minutes, and suppressing the provider row on every device from then on. The
     * phone calls the same endpoint, so a removal means the same thing on both.
     */
    suspend fun dismissContinueWatching(item: MediaItem): Boolean {
        val episode = item.episode
        val entityType = if (item.type.equals("movie", true)) "movie" else "tv"
        val cacheKey = buildSessionProfileCacheKey()
        val previous = libraryCache[cacheKey]
        rememberPendingContinueDismissal(cacheKey, item)
        previous?.let { current ->
            libraryCache[cacheKey] = current.copy(
                continueWatching = removeContinueWatchingSnapshot(current.continueWatching, item),
            )
        }
        // Home and Library both rebuild from the same profile-scoped library snapshot. Publish the
        // targeted change immediately while the network mutation completes; do not clear Library.
        homeCache.clear()
        libraryRevisionState.value = libraryRevisionState.value + 1L
        val identity = mediaIdentityOf(item.type, item.id, item.tmdbId, item.imdbId)
        val removedAt = Instant.now().toString()
        return runCatching {
            val accepted = api.request<Any>(
                method = "POST",
                path = "/sync/continue-watching/remove",
                body = com.google.gson.Gson().toJson(
                    mapOf(
                        "entityType" to entityType,
                        "entityId" to item.id,
                        "episodeKey" to buildEpisodeKey(episode),
                        "seasonNumber" to episode?.seasonNumber,
                        "episodeNumber" to episode?.episodeNumber,
                        // Sent so the removal can be recognised later against a provider row that
                        // spells this title a different way. Without them the tombstone is only
                        // findable by the one id this card happened to carry.
                        "tmdbId" to identity.tmdbId,
                        "imdbId" to identity.imdbId,
                        "title" to item.title,
                        "posterUrl" to item.poster,
                        "backdropUrl" to item.backdrop,
                        "year" to item.year,
                        "removedAt" to removedAt,
                        "lastDevice" to "StreamDek TV",
                        "lastPlatform" to "tv",
                    ),
                ),
            ) != null
            // An account still on a SyncDek without the canonical route gets the tombstone written
            // the long way, which is what this app did before the route existed. The removal is
            // weaker that way -- the server cannot suppress the provider row on the other devices --
            // but it is far better than a television that cannot remove anything at all until the
            // backend is deployed.
            check(accepted || writeLegacyContinueDismissal(item, entityType, episode, removedAt)) {
                "Continue Watching removal was not accepted."
            }
            true
        }.onFailure {
            forgetPendingContinueDismissal(cacheKey, item)
            if (previous != null) libraryCache[cacheKey] = previous else libraryCache.remove(cacheKey)
            homeCache.clear()
            libraryRevisionState.value = libraryRevisionState.value + 1L
            TvDebugLogger.w("Playback", "dismissContinueWatching failed mediaType=${item.type} mediaId=${item.id}")
        }.getOrDefault(false)
    }

    /**
     * The pre-canonical removal write, used only when SyncDek does not offer the canonical route.
     *
     * Deliberately identical to what this app sent before, so an older backend behaves exactly as
     * it did rather than in some third way. Nothing else should call this: the canonical operation
     * above is the one every surface goes through.
     */
    private suspend fun writeLegacyContinueDismissal(
        item: MediaItem,
        entityType: String,
        episode: EpisodeContext?,
        removedAt: String,
    ): Boolean = api.request<Any>(
        method = "PUT",
        path = "/sync/progress",
        body = com.google.gson.Gson().toJson(
            mapOf(
                "entityType" to entityType,
                "entityId" to item.id,
                "episodeKey" to buildEpisodeKey(episode),
                "positionSec" to 0,
                "durationSec" to 0,
                "completed" to false,
                "dismissed" to true,
                "updatedAt" to removedAt,
                "lastDevice" to "StreamDek TV",
                "lastPlatform" to "tv",
                "metadata" to mapOf(
                    "title" to item.title,
                    "posterUrl" to item.poster,
                    "backdropUrl" to item.backdrop,
                    "year" to item.year,
                    "seasonNumber" to episode?.seasonNumber,
                    "episodeNumber" to episode?.episodeNumber,
                    "episodeTitle" to episode?.title,
                ),
            ),
        ),
    ) != null

    suspend fun clearSeasonProgress(
        mediaId: String,
        seasonNumber: Int,
        seasonDetail: SeasonDetail? = null,
    ) {
        val season = seasonDetail ?: fetchSeason(mediaId, seasonNumber) ?: return
        season.episodes.forEach { episode ->
            clearProgress(
                mediaType = "tv",
                mediaId = mediaId,
                episode = EpisodeContext(
                    seasonNumber = seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    title = episode.name,
                    overview = episode.overview,
                    still = episode.still,
                    runtime = episode.runtime,
                    airDate = episode.airDate,
                    tmdbEpisodeId = episode.id,
                ),
            )
        }
    }

    fun activeStreamProfile(): StreamProfile? {
        val profiles = bootstrapState.value?.streamProfiles.orEmpty()
        val activeId = sessionStore.activeProfileId()
        return profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull { it.isDefault } ?: profiles.firstOrNull()
    }

    fun activeStreamProfile(bootstrap: AccountBootstrap?): StreamProfile? {
        val profiles = bootstrap?.streamProfiles.orEmpty()
        val activeId = sessionStore.activeProfileId()
        return profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull { it.isDefault } ?: profiles.firstOrNull()
    }

    fun rememberLastProfileAtStartup(): Boolean = sessionStore.rememberLastProfileAtStartup()

    fun setRememberLastProfileAtStartup(remember: Boolean) {
        sessionStore.setRememberLastProfileAtStartup(remember)
    }

    suspend fun verifyProfilePin(profileId: String, pin: String): Boolean {
        if (pin.length != 4 || pin.any { !it.isDigit() }) return false
        return api.post<JsonObject>(
            "/profiles/${URLEncoder.encode(profileId, "UTF-8")}/verify-pin",
            mapOf("pin" to pin),
        )?.get("valid")?.asBoolean == true
    }

    fun setActiveStreamProfile(profileId: String?) {
        if (profileId == sessionStore.activeProfileId()) return
        sessionStore.setActiveProfileId(profileId)
        reloadFavouriteChannels()
        libraryCache.clear()
        homeCache.clear()
        watchedHistoryCache.clear()
        // Responses are cached per URL, and the profile only travels in a header, so the previous
        // profile's rows would otherwise be replayed for this one whenever the network drops.
        StreamDekHttp.evictCache()
    }

    suspend fun fetchProgress(mediaType: String, mediaId: String, episode: EpisodeContext? = null): PlaybackProgressRecord? {
        val episodeKey = buildEpisodeKey(episode)
        val query = buildString {
            append("/sync/progress?entityType=$mediaType&entityId=$mediaId")
            if (episodeKey != null) append("&episodeKey=$episodeKey")
        }
        return api.get<PlaybackProgressResponse>(query)?.progress
    }

    suspend fun fetchSeriesResumeState(detail: MediaDetail): SeriesResumeState = supervisorScope {
        val progressDeferred = async {
            fetchSeriesProgressRecords(detail.id)
        }
        val watchedDeferred = async { fetchWatchedKeys(forceRefresh = true) }
        val providerPlaybackDeferred = async { runCatching { fetchServicePlayback() }.getOrDefault(emptyList()) }
        val syncDekEvents = progressDeferred.await().mapNotNull { record ->
            val (season, episode) = record.episodeNumbers() ?: return@mapNotNull null
            SeriesProgressEvent(
                seasonNumber = season,
                episodeNumber = episode,
                positionSec = record.positionSec,
                progress = record.progress,
                status = record.status,
                updatedAtMillis = record.updatedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L,
            )
        }
        val providerEvents = providerPlaybackDeferred.await().mapNotNull { item ->
            if (item.type != "tv" || (item.id != detail.id && item.tmdbId.toString() != detail.id)) return@mapNotNull null
            val season = item.episode?.seasonNumber ?: item.seasonNumber ?: return@mapNotNull null
            val episode = item.episode?.episodeNumber ?: item.episodeNumber ?: return@mapNotNull null
            SeriesProgressEvent(
                seasonNumber = season,
                episodeNumber = episode,
                positionSec = item.positionSec
                    ?: item.durationSec?.times((item.progress ?: 0.0) / 100.0)
                    ?: 0.0,
                progress = item.progress ?: 0.0,
                status = "in-progress",
                updatedAtMillis = item.updatedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L,
            )
        }
        val prefix = "tv:${detail.id}:"
        val providerWatched = watchedDeferred.await().mapNotNull { key ->
            key.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
        }.toSet()
        getSeriesResumeState(seriesEpisodeSlots(detail.seasons), syncDekEvents + providerEvents, providerWatched)
    }

    /**
     * Returns every episode row for one series.
     *
     * Older backends interpret entityType + entityId as an exact lookup even without an
     * episodeKey. Try the explicit list contract first, then omit entityType as a compatibility
     * fallback and filter the resulting DTOs locally.
     */
    private suspend fun fetchSeriesProgressRecords(mediaId: String): List<PlaybackProgressRecord> {
        val encodedId = URLEncoder.encode(mediaId, "UTF-8")
        val scoped = api.get<PlaybackProgressListResponse>(
            "/sync/progress?list=true&entityType=tv&entityId=$encodedId&limit=500",
        )?.results.orEmpty()
        val records = if (scoped.isNotEmpty()) scoped else {
            api.get<PlaybackProgressListResponse>(
                "/sync/progress?entityId=$encodedId&limit=500",
            )?.results.orEmpty()
        }
        return records.filter { record ->
            (record.entityType == null || record.entityType.equals("tv", ignoreCase = true)) &&
                (record.entityId == null || record.entityId == mediaId)
        }
    }

    suspend fun fetchContinueWatchingItem(
        mediaType: String,
        mediaId: String,
        episode: EpisodeContext? = null,
    ): ContinueWatchingItem? {
        val matches = fetchLibrary().continueWatching.filter { it.type == mediaType && it.id == mediaId }
        if (episode == null) return matches.firstOrNull()
        return matches.firstOrNull { item ->
            item.exactEpisode()?.let {
                it.seasonNumber == episode.seasonNumber && it.episodeNumber == episode.episodeNumber
            } == true
        } ?: matches.firstOrNull()
    }

    fun rememberedStreamKey(mediaType: String, mediaId: String, episode: EpisodeContext?): String? {
        if (!rememberLastSourceEnabled()) return null
        return sessionStore.preferredStreamKey(mediaType, mediaId, buildEpisodeKey(episode))
    }

    fun forgetRememberedStream(mediaType: String, mediaId: String, episode: EpisodeContext?) {
        val episodeKey = buildEpisodeKey(episode)
        sessionStore.savePreferredStreamKey(mediaType, mediaId, episodeKey, null)
        sessionStore.saveRememberedPlaybackSource(mediaType, mediaId, episodeKey, null)
    }

    /**
     * The playable URL this title last actually played from, if it is still worth trying.
     *
     * This is what makes a remembered resume instant. The alternative — which is what "remember
     * last source" used to do on its own — is to ask the owning add-on for its stream list again
     * and then resolve the remembered row back into a URL, and those two round trips are most of
     * the wait before a resume starts. Worse, they are the same two round trips whether or not
     * anything is remembered, so remembering bought the viewer nothing.
     *
     * Null once [RememberedSourceTtlMs] has passed. That window is generous on purpose: a stale URL
     * is not a failure state here, because the player falls back to a full resolve the moment the
     * engine cannot open it, so the cost of trying is one failed request and the cost of not trying
     * is the entire wait.
     */
    fun rememberedPlaybackSource(
        mediaType: String,
        mediaId: String,
        episode: EpisodeContext?,
    ): RememberedPlaybackSource? {
        if (!rememberLastSourceEnabled()) return null
        val stored = sessionStore.rememberedPlaybackSource(mediaType, mediaId, buildEpisodeKey(episode))
            ?: return null
        if (System.currentTimeMillis() - stored.savedAtMs > RememberedSourceTtlMs) {
            sessionStore.saveRememberedPlaybackSource(mediaType, mediaId, buildEpisodeKey(episode), null)
            return null
        }
        return stored
    }

    /**
     * Records the source a title is playing from, once it has proved it plays.
     *
     * Called when the engine reports a first frame rather than when the URL resolves: a URL that
     * resolved and then would not open is exactly what should *not* be remembered, since it would
     * be tried first every time and fail every time.
     */
    fun rememberPlaybackSource(
        mediaType: String,
        mediaId: String,
        episode: EpisodeContext?,
        candidate: ResolvedPlaybackCandidate,
    ) {
        if (!rememberLastSourceEnabled()) return
        val source = candidate.source ?: return
        val stream = candidate.stream ?: return
        val episodeKey = buildEpisodeKey(episode)
        val streamKey = streamSelectionKey(stream)
        sessionStore.savePreferredStreamKey(mediaType, mediaId, episodeKey, streamKey)
        sessionStore.saveRememberedPlaybackSource(
            mediaType,
            mediaId,
            episodeKey,
            RememberedPlaybackSource(
                streamKey = streamKey,
                url = source.url,
                contentType = source.contentType,
                label = source.label,
                filename = source.filename,
                requestHeaders = source.requestHeaders,
                addonId = stream.addonId,
                addonName = stream.addonName,
                savedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /** Rebuilds a candidate the player can start from without resolving anything. */
    fun candidateFromRememberedSource(remembered: RememberedPlaybackSource): ResolvedPlaybackCandidate {
        val stream = AddonStream(
            addonId = remembered.addonId,
            addonName = remembered.addonName,
            name = remembered.addonName,
            title = remembered.label,
            url = remembered.url,
            requestHeaders = remembered.requestHeaders,
        )
        return ResolvedPlaybackCandidate(
            source = ResolvedPlaybackSource(
                url = remembered.url,
                contentType = remembered.contentType.ifBlank { guessContentType(remembered.url) },
                label = remembered.label,
                filename = remembered.filename,
                requestHeaders = remembered.requestHeaders,
            ),
            stream = stream,
            streams = listOf(stream),
        )
    }

    suspend fun fetchWatchedKeys(forceRefresh: Boolean = false): Set<String> {
        val session = currentSession() ?: return emptySet()
        val profileId = sessionStore.activeProfileId()?.takeIf { it.isNotBlank() } ?: return emptySet()
        val cacheKey = "${session.user.uid}:$profileId"
        if (!forceRefresh) {
            watchedHistoryCache[cacheKey]?.let { return it }
        }
        // Watched history is a Trakt-only feature; a profile tracking elsewhere would otherwise
        // spend a rejected request on every detail screen it opens.
        if (!isSyncServiceConnected(SyncServiceId.TRAKT)) return emptySet()
        val results = runCatching {
            api.get<TraktHistoryResponse>("/trakt/sync/history", session)?.results.orEmpty()
        }.onFailure {
            TvDebugLogger.e("Trakt", "fetchWatchedKeys failed", it)
        }.getOrDefault(emptyList())
        val watchedKeys = results.mapNotNull(::historyItemKey).toSet()
        watchedHistoryCache[cacheKey] = watchedKeys
        return watchedKeys
    }

    suspend fun isWatched(
        mediaType: String,
        mediaId: String,
        episode: EpisodeContext? = null,
        forceRefresh: Boolean = false,
    ): Boolean {
        val syncDek = fetchProgress(mediaType, mediaId, episode)
        if (syncDek?.status == "completed") return true
        if (syncDek?.status == "unwatched") return false
        return fetchWatchedKeys(forceRefresh).contains(watchedHistoryKey(mediaType, mediaId, episode))
    }

    suspend fun setEpisodeWatched(detail: MediaDetail, episode: EpisodeContext, watched: Boolean): Boolean {
        val syncDek = runCatching {
            api.request<Any>(
                method = "PUT",
                path = "/sync/progress",
                body = com.google.gson.Gson().toJson(
                    mapOf(
                        "entityType" to "tv",
                        "entityId" to detail.id,
                        "episodeKey" to buildEpisodeKey(episode),
                        "positionSec" to 0,
                        "durationSec" to 0,
                        "completed" to watched,
                        "unwatched" to !watched,
                        "updatedAt" to Instant.now().toString(),
                        "lastDevice" to "StreamDek TV",
                        "lastPlatform" to "tv",
                        "metadata" to buildSyncMetadata(detail, episode),
                    ),
                ),
            )
            true
        }.getOrDefault(false)

        if (isSyncServiceConnected(SyncServiceId.TRAKT)) {
            val payload = mapOf(
                "movies" to emptyList<Any>(),
                "shows" to listOf(
                    mapOf(
                        "title" to detail.title,
                        "ids" to mapOf("tmdb" to detail.id.toIntOrNull(), "imdb" to detail.imdbId),
                        "seasons" to listOf(
                            mapOf(
                                "number" to episode.seasonNumber,
                                "episodes" to listOf(
                                    mapOf("number" to episode.episodeNumber, "watched_at" to Instant.now().toString()),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val endpoint = if (watched) "/trakt/sync/watched" else "/trakt/sync/history/remove"
            runCatching { api.post<Any>(endpoint, payload) }
                .onFailure { TvDebugLogger.w("Trakt", "episode watched toggle failed ${detail.id}:${episode.seasonNumber}:${episode.episodeNumber}") }
        }
        if (syncDek) invalidatePlaybackDerivedCaches()
        return syncDek
    }

    suspend fun syncProgress(
        mediaType: String,
        mediaId: String,
        positionSec: Double,
        durationSec: Double,
        episode: EpisodeContext? = null,
        detail: MediaDetail? = null,
    ) {
        if (positionSec <= 0.0 || durationSec <= 0.0) return
        runCatching {
            api.request<Any>(
                method = "PUT",
                path = "/sync/progress",
                body = com.google.gson.Gson().toJson(
                    mapOf(
                        "entityType" to mediaType,
                        "entityId" to mediaId,
                        "positionSec" to positionSec,
                        "durationSec" to durationSec,
                        "episodeKey" to buildEpisodeKey(episode),
                        "updatedAt" to Instant.now().toString(),
                        "lastDevice" to "StreamDek TV",
                        "lastPlatform" to "tv",
                        "metadata" to buildSyncMetadata(detail, episode),
                    ),
                ),
            )
            invalidatePlaybackDerivedCaches()
        }
    }

    suspend fun traktScrobble(
        action: String,
        mediaType: String,
        mediaId: String,
        title: String? = null,
        year: String? = null,
        progress: Double = 0.0,
    ): Boolean {
        val session = currentSession() ?: return false
        val profileId = sessionStore.activeProfileId()?.takeIf { it.isNotBlank() } ?: return false
        val traktConnected = bootstrapState.value?.syncStatus?.traktConnected == true ||
            bootstrapState.value?.integrations?.trakt?.connected == true
        if (!traktConnected) return false

        val clampedProgress = progress.coerceIn(0.0, 100.0)
        val parsedYear = year
            ?.take(4)
            ?.toIntOrNull()

        val payload = if (mediaType == "tv") {
            mapOf(
                "show" to mapOf(
                    "title" to (title ?: ""),
                    "year" to parsedYear,
                    "ids" to mapOf("tmdb" to (mediaId.toIntOrNull())),
                ),
                "progress" to clampedProgress,
            )
        } else {
            mapOf(
                "movie" to mapOf(
                    "title" to (title ?: ""),
                    "year" to parsedYear,
                    "ids" to mapOf("tmdb" to (mediaId.toIntOrNull())),
                ),
                "progress" to clampedProgress,
            )
        }

        return runCatching {
            api.post<Any>("/trakt/scrobble/$action", payload, session) != null
        }.onFailure {
            TvDebugLogger.w("Trakt", "scrobble failed action=$action profile=$profileId mediaType=$mediaType mediaId=$mediaId")
        }.getOrDefault(false)
    }

    suspend fun resolvePlayback(
        mediaType: String,
        mediaId: String,
        imdbId: String?,
        episode: EpisodeContext? = null,
        preferredStreamKey: String? = null,
        preferredAddonName: String? = null,
        preferredQualityGroup: String? = null,
        forceRefresh: Boolean = false,
        streamType: String? = null,
        directStreamUrl: String? = null,
        requestHeaders: Map<String, String> = emptyMap(),
        sourceAddonId: String? = null,
        sourceAddonName: String? = null,
    ): ResolvedPlaybackCandidate {
        if (mediaType == "live" && !directStreamUrl.isNullOrBlank()) {
            val directStream = AddonStream(
                addonId = sourceAddonId.orEmpty(),
                addonName = sourceAddonName ?: label(R.string.stream_live_source, "Live source"),
                name = sourceAddonName ?: label(R.string.stream_live_source, "Live source"),
                title = label(R.string.stream_direct_live, "Direct live stream"),
                url = directStreamUrl,
                requestHeaders = requestHeaders,
            )
            return ResolvedPlaybackCandidate(
                source = ResolvedPlaybackSource(
                    url = directStreamUrl,
                    contentType = guessContentType(directStreamUrl),
                    label = sourceAddonName ?: "Live stream",
                    requestHeaders = requestHeaders,
                ),
                stream = directStream,
                streams = listOf(directStream),
            )
        }
        val perf = Perf.span("resolve", "$mediaType:$mediaId")
        val episodeKey = buildEpisodeKey(episode)
        val effectivePreferredStreamKey = effectiveRememberedStreamKey(
            explicitKey = preferredStreamKey,
            storedKey = sessionStore.preferredStreamKey(mediaType, mediaId, episodeKey),
            rememberLastSource = rememberLastSourceEnabled(),
        )
        val cacheKey = playbackCacheKey(
            mediaType = mediaType,
            mediaId = mediaId,
            imdbId = imdbId,
            episode = episode,
            preferredStreamKey = effectivePreferredStreamKey,
            preferredAddonName = preferredAddonName,
            preferredQualityGroup = preferredQualityGroup,
            streamType = streamType,
        )
        if (!forceRefresh) {
            readResolvedPlaybackCache(cacheKey)?.let { perf.end("cacheHit"); return it }
        }
        // Mirrors the mobile app's live type fallbacks: addons publish live channels
        // under a wide range of Stremio-native type names.
        val lookupTypes = streamLookupTypes(mediaType, streamType)
        val videoId = buildStreamVideoId(imdbId ?: mediaId, episode)
        val rememberedAddonId = effectivePreferredStreamKey
            ?.substringBefore('|')
            ?.takeIf { it.isNotBlank() }
        val targetedAddonId = sourceAddonId?.takeIf { it.isNotBlank() } ?: rememberedAddonId
        val targetedStreams = targetedAddonId?.let { addonId ->
            perf.mark("rememberedPath", "addon=$addonId")
            fetchStreamsFromOwningAddon(
                addonId = addonId,
                lookupTypes = lookupTypes,
                videoId = videoId,
                isLive = mediaType == "live",
                forceRefresh = mediaType == "live",
            )
        }
        val (streamLookupType, addonStreams) = targetedStreams
            ?: Perf.timed(perf, "addonDiscovery") { fetchStreamsForPlayback(lookupTypes, videoId, isLive = mediaType == "live") }
        perf.mark("addonStreams", "count=${addonStreams.size}")
        // Plugin sources join the pool the same way they do on mobile — except when a specific
        // add-on already answered, which only happens for a live channel or a remembered source
        // the viewer explicitly picked, and where waiting on scrapers would just delay playback.
        val streams = Perf.timed(perf, "streamPool") {
            markCachedStreams(
                if (targetedStreams != null) {
                    addonStreams
                } else {
                    dedupeStreams(addonStreams + Perf.timed(perf, "pluginDiscovery") { pluginStreams(mediaType, mediaId, imdbId, episode) })
                },
            )
        }
        perf.mark("poolReady", "count=${streams.size}")
        // Decorated before ranking rather than after: a source the service already holds starts
        // instantly, and that is worth more than anything else the ranking weighs.
        var resolveAttempts = 0
        for (stream in rankStreams(streams, effectivePreferredStreamKey, preferredAddonName, preferredQualityGroup)) {
            resolveAttempts++
            val attemptBegan = android.os.SystemClock.uptimeMillis()
            val resolvedUrl = resolveStreamToUrl(
                stream, streamLookupType, videoId,
                seasonNumber = episode?.seasonNumber,
                episodeNumber = episode?.episodeNumber,
            )
            perf.mark("resolveAttempt$resolveAttempts", "took=${android.os.SystemClock.uptimeMillis() - attemptBegan} ok=${!resolvedUrl.isNullOrBlank()}")
            if (!resolvedUrl.isNullOrBlank()) {
                val resolvedStreamKey = streamSelectionKey(stream)
                val candidate = ResolvedPlaybackCandidate(
                    source = ResolvedPlaybackSource(
                        url = resolvedUrl,
                        contentType = guessContentType(resolvedUrl),
                        label = describeStream(stream),
                        filename = effectiveFilename(stream),
                        requestHeaders = stream.requestHeaders,
                    ),
                    stream = stream,
                    streams = streams,
                )
                if (rememberLastSourceEnabled()) {
                    sessionStore.savePreferredStreamKey(mediaType, mediaId, episodeKey, resolvedStreamKey)
                }
                writeResolvedPlaybackCache(cacheKey, candidate)
                perf.end("resolved", "attempts=$resolveAttempts")
                return candidate
            }
        }
        perf.end("noPlayableSource", "attempts=$resolveAttempts pool=${streams.size}")
        return ResolvedPlaybackCandidate(null, null, streams).also {
            writeResolvedPlaybackCache(cacheKey, it)
        }
    }

    /**
     * Fetches candidate streams the same way the mobile app does: ask the backend for each
     * enabled addon individually (ordered by addon position), fall back to querying the addon
     * directly for a fresh response, and only then fall back to the aggregated backend route.
     * Returns the lookup type that produced results together with the de-duplicated streams.
     */
    private suspend fun fetchStreamsFromOwningAddon(
        addonId: String,
        lookupTypes: List<String>,
        videoId: String,
        isLive: Boolean,
        forceRefresh: Boolean,
    ): Pair<String?, List<AddonStream>>? {
        val addon = runCatching { fetchAddonManifests() }.getOrDefault(emptyList())
            .firstOrNull { it.enabled && it.id == addonId }
            ?: return null
        val baseId = videoId.substringBefore(":")
        for (lookupType in lookupTypes) {
            if (!addonSupportsStreamType(addon, lookupType)) continue
            val streams = fetchStreamsFromSingleAddon(
                addon = addon,
                lookupType = lookupType,
                videoId = videoId,
                baseId = baseId,
                isLive = isLive,
                forceRefresh = forceRefresh,
            )
            if (streams.isNotEmpty()) return lookupType to streams
        }
        return null
    }
    private suspend fun fetchStreamsForPlayback(
        lookupTypes: List<String>,
        videoId: String,
        isLive: Boolean = false,
    ): Pair<String?, List<AddonStream>> {
        val addons = runCatching { fetchAddonManifests() }.getOrDefault(emptyList())
            .filter { it.enabled }
            .sortedWith(compareByDescending<AddonManifest> { it.favourite }.thenBy { it.position })
        val baseId = videoId.substringBefore(":")
        for (lookupType in lookupTypes) {
            val supportingAddons = addons.filter { addonSupportsStreamType(it, lookupType) }
            if (supportingAddons.isEmpty()) continue
            val merged = supervisorScope {
                supportingAddons.map { addon ->
                    async { fetchStreamsFromSingleAddon(addon, lookupType, videoId, baseId, isLive) }
                }.map { deferred -> runCatching { deferred.await() }.getOrDefault(emptyList()) }
            }.flatten()
            if (merged.isNotEmpty()) {
                return lookupType to dedupeStreams(merged)
            }
        }
        // The aggregate route belongs exclusively to server-side mode. A direct account must
        // never leak an otherwise-empty lookup across the mode boundary as a fallback.
        if (!usesServerSideStreams()) return lookupTypes.firstOrNull() to emptyList()
        for (lookupType in lookupTypes) {
            val aggregated = runCatching {
                api.get<AddonStreamsResponse>("/addons/streams/$lookupType/${encodePathSegment(videoId)}")?.streams
            }.getOrNull().orEmpty()
            if (aggregated.isNotEmpty()) {
                return lookupType to dedupeStreams(aggregated)
            }
        }
        return lookupTypes.firstOrNull() to emptyList()
    }

    /**
     * Whether the active profile has a synced plugin source that could answer this lookup.
     *
     * Plugin sources are movie/series scrapers, so live channels never consult them — the same
     * rule the mobile app applies.
     */
    private fun hasPluginSourcesFor(mediaType: String): Boolean = pluginProviderCount(mediaType) > 0

    /**
     * Brings this profile's CloudStream collections up from the synced document.
     *
     * The record that arrives is a pointer -- a name, a version and a download URL -- so a source
     * switched on elsewhere is fetched and loaded here the first time it is asked for. Downloading
     * a multi-megabyte extension is why this runs off the caller's thread and why a failure is
     * logged rather than surfaced: the rest of the sources should still answer.
     */
    private fun applyCloudStreamCollections(plugins: ProfilePluginState?) {
        if (!CloudStreamPlugins.isInitialized) return
        val ownerKey = sessionStore.activeProfileId()?.takeIf { it.isNotBlank() } ?: "guest"
        CloudStreamPlugins.manager.selectProfileStorage(ownerKey)
        val section = plugins?.cloudstream?.let { com.google.gson.Gson().toJson(it) }
        val changed = runCatching { CloudStreamPlugins.manager.restoreCloudState(section) }.getOrDefault(false)
        cloudStreamLoadJob?.cancel()
        cloudStreamLoadJob = repositoryScope.launch(Dispatchers.IO) {
            runCatching { CloudStreamPlugins.manager.loadEnabledProviders() }
                .onFailure { TvDebugLogger.w("CloudStream", "could not bring up enabled sources", it) }
            if (changed) TvDebugLogger.i("CloudStream", "collections updated from the account")
        }
    }

    /**
     * The CloudStream sources ready to answer, or none.
     *
     * These search by title rather than by id -- a `.cs3` scrapes a website -- so unlike the JS
     * plugins they are worth asking only once the title is known.
     */
    private fun cloudStreamProviders(): List<com.lagradost.cloudstream3.MainAPI> {
        if (!CloudStreamPlugins.isInitialized) return emptyList()
        return runCatching { CloudStreamPlugins.manager.activeProviders() }.getOrDefault(emptyList())
    }

    /** How many plugin scrapers a lookup of [mediaType] would fan out to. */
    private fun pluginProviderCount(mediaType: String): Int {
        if (mediaType == "live") return 0
        val engine = pluginEngine ?: return 0
        return engine.eligibleProviderCount(bootstrapState.value?.profilePlugins, mediaType)
    }

    /**
     * Streams from the profile's synced plugin sources, alongside the add-on results.
     *
     * [onProviderResults] fires as each provider finishes so the picker can show partial results
     * rather than waiting for the slowest scraper.
     */
    private suspend fun pluginStreams(
        mediaType: String,
        mediaId: String,
        imdbId: String?,
        episode: EpisodeContext?,
        onProviderResults: suspend (List<AddonStream>) -> Unit = {},
    ): List<AddonStream> {
        val engine = pluginEngine ?: return emptyList()
        if (!hasPluginSourcesFor(mediaType)) return emptyList()
        val lookupId = pluginLookupId(mediaType, mediaId, imdbId)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            engine.streams(
                state = bootstrapState.value?.profilePlugins,
                id = lookupId,
                type = mediaType,
                season = episode?.seasonNumber,
                episode = episode?.episodeNumber,
                onProviderResults = onProviderResults,
            )
        }.onFailure { TvDebugLogger.w("Plugins", "lookup failed media=$mediaType:$mediaId", it) }
            .getOrDefault(emptyList())
    }

    /**
     * Plugin scrapers are keyed by TMDB id, so an IMDb-only id is resolved first — mirroring the
     * mobile app's resolvePluginMediaId.
     */
    private suspend fun pluginLookupId(mediaType: String, mediaId: String, imdbId: String?): String? {
        val candidate = mediaId.substringBefore(':').trim()
        if (candidate.isNotBlank() && !candidate.startsWith("tt", ignoreCase = true)) return candidate
        val imdb = Regex("tt\\d+", RegexOption.IGNORE_CASE)
            .find(candidate.ifBlank { imdbId.orEmpty() })?.value
            ?: return candidate.ifBlank { null }
        val canonicalType = if (mediaType == "series") "tv" else mediaType
        val resolved = runCatching {
            api.get<TmdbFindResponse>("/tmdb/find/imdb/$imdb?type=$canonicalType")
        }.getOrNull()?.takeIf { it.id > 0 }
        return resolved?.id?.toString() ?: candidate.ifBlank { null }
    }

    private suspend fun fetchStreamsFromSingleAddon(
        addon: AddonManifest,
        lookupType: String,
        videoId: String,
        baseId: String,
        isLive: Boolean,
        forceRefresh: Boolean = false,
    ): List<AddonStream> {
        // Keep the two modes isolated. In direct mode every response is fetched and parsed on this
        // device; in server-side mode this client never contacts the add-on itself.
        if (usesServerSideStreams()) {
            return runCatching {
                api.get<AddonStreamsResponse>(
                    "/addons/streams/single/${encodePathSegment(addon.id)}/$lookupType/${encodePathSegment(videoId)}",
                )?.streams.orEmpty().map { it.withAddonIdentity(addon) }
            }.getOrDefault(emptyList())
        }

        // Direct add-on calls require the identifier shape the add-on understands.
        val requiresImdbId = !isLive && (lookupType == "movie" || lookupType == "series" || lookupType == "tv")
        if (requiresImdbId && !baseId.matches(Regex("^tt\\d+$", RegexOption.IGNORE_CASE))) return emptyList()
        return fetchFreshStreamsFromAddon(addon, lookupType, videoId, forceNetwork = forceRefresh)
    }

    /** Fail closed to direct mode, matching mobile, and cache the entitlement once per account. */
    private suspend fun usesServerSideStreams(): Boolean {
        val userId = currentSession()?.user?.uid ?: return false
        if (addonEntitlementsUserId == userId) return serverSideStreamsEnabled
        return addonEntitlementsMutex.withLock {
            if (addonEntitlementsUserId != userId) {
                serverSideStreamsEnabled = runCatching {
                    api.get<AddonEntitlements>("/addons/entitlements")?.serverSideStreams == true
                }.getOrDefault(false)
                addonEntitlementsUserId = userId
            }
            serverSideStreamsEnabled
        }
    }

    private fun rememberPendingWatchlistMutation(cacheKey: String, item: MediaItem, remove: Boolean) {
        synchronized(pendingWatchlistLock) {
            val mutations = pendingWatchlistMutations.getOrPut(cacheKey) { mutableListOf() }
            mutations.removeAll { sameWatchlistTitle(it.item, item) }
            mutations += PendingWatchlistMutation(item, remove, System.currentTimeMillis())
        }
    }

    private fun applyPendingWatchlistMutations(cacheKey: String, remote: List<MediaItem>): List<MediaItem> {
        val active = synchronized(pendingWatchlistLock) {
            val now = System.currentTimeMillis()
            val mutations = pendingWatchlistMutations[cacheKey].orEmpty()
                .filter { now - it.recordedAt < WATCHLIST_MUTATION_GRACE_MS }
            if (mutations.isEmpty()) pendingWatchlistMutations.remove(cacheKey)
            else pendingWatchlistMutations[cacheKey] = mutations.toMutableList()
            mutations
        }
        return active.fold(remote) { current, mutation ->
            mutateWatchlistSnapshot(current, mutation.item, mutation.remove)
        }
    }

    private fun rememberPendingContinueDismissal(cacheKey: String, item: MediaItem) {
        synchronized(pendingContinueLock) {
            val dismissals = pendingContinueDismissals.getOrPut(cacheKey) { mutableListOf() }
            dismissals.removeAll { pending ->
                pending.item.type.equals(item.type, ignoreCase = true) &&
                    pending.item.id == item.id &&
                    pending.item.episode?.seasonNumber == item.episode?.seasonNumber &&
                    pending.item.episode?.episodeNumber == item.episode?.episodeNumber
            }
            dismissals += PendingContinueDismissal(item, System.currentTimeMillis())
        }
    }

    private fun forgetPendingContinueDismissal(cacheKey: String, item: MediaItem) {
        synchronized(pendingContinueLock) {
            pendingContinueDismissals[cacheKey]?.removeAll { pending ->
                pending.item.type.equals(item.type, ignoreCase = true) &&
                    pending.item.id == item.id &&
                    pending.item.episode?.seasonNumber == item.episode?.seasonNumber &&
                    pending.item.episode?.episodeNumber == item.episode?.episodeNumber
            }
            if (pendingContinueDismissals[cacheKey].isNullOrEmpty()) pendingContinueDismissals.remove(cacheKey)
        }
    }

    private fun applyPendingContinueDismissals(
        cacheKey: String,
        remote: List<ContinueWatchingItem>,
    ): List<ContinueWatchingItem> {
        val active = synchronized(pendingContinueLock) {
            val now = System.currentTimeMillis()
            val dismissals = pendingContinueDismissals[cacheKey].orEmpty()
                .filter { now - it.recordedAt < CONTINUE_DISMISSAL_GRACE_MS }
            if (dismissals.isEmpty()) pendingContinueDismissals.remove(cacheKey)
            else pendingContinueDismissals[cacheKey] = dismissals.toMutableList()
            dismissals
        }
        return active.fold(remote) { current, pending ->
            removeContinueWatchingSnapshot(current, pending.item)
        }
    }

    private fun AddonStream.withAddonIdentity(addon: AddonManifest): AddonStream = copy(
        addonId = addonId.ifBlank { addon.id },
        addonName = addonName.ifBlank { addon.manifest.name },
    )

    // Only remove byte-for-byte-equivalent provider rows. File index, NZB server, quality, size,
    // headers and every other playback-relevant field participate in the identity so distinct
    // results from one provider are never collapsed merely because their labels or hash match.
    private fun dedupeStreams(streams: List<AddonStream>): List<AddonStream> = streams.distinctBy(::streamAggregationKey)

    private fun addonSupportsStreamType(addon: AddonManifest, type: String): Boolean {
        val resources = addon.manifest.resources.mapNotNull { resource ->
            when (resource) {
                is String -> resource.trim().lowercase(Locale.US)
                is Map<*, *> -> (resource["name"] as? String)?.trim()?.lowercase(Locale.US)
                else -> null
            }
        }
        if (resources.isNotEmpty() && resources.none { it == "stream" || it == "streams" }) return false
        val nativeType = type.trim().lowercase(Locale.US)
        val types = addon.manifest.types.map { it.trim().lowercase(Locale.US) }
        if (types.isEmpty()) return true
        return nativeType in types ||
            (nativeType == "series" && "tv" in types) ||
            (nativeType == "tv" && "series" in types)
    }

    /**
     * Queries a Stremio addon directly for streams, bypassing the backend cache. Used both as
     * a fetch fallback and to refresh expired direct playback links right before playback.
     */
    private suspend fun fetchFreshStreamsFromAddon(
        addon: AddonManifest,
        type: String,
        videoId: String,
        forceNetwork: Boolean = false,
    ): List<AddonStream> = withContext(Dispatchers.IO) {
        val manifestUrl = addon.transportUrl ?: addon.manifestUrl ?: return@withContext emptyList()
        val addonBaseUrl = manifestUrl.substringBeforeLast("/manifest.json", missingDelimiterValue = manifestUrl.trimEnd('/'))
        val streamType = type.trim().lowercase(Locale.US)
        val request = okhttp3.Request.Builder()
            .url("$addonBaseUrl/stream/${addonPathSegment(streamType)}/${addonPathSegment(videoId)}.json")
            .header("User-Agent", "Stremio/4.4.168")
            .apply { if (forceNetwork) cacheControl(okhttp3.CacheControl.FORCE_NETWORK) }
            .build()
        runCatching {
            directStreamClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val raw = response.body?.string()?.takeIf { it.isNotBlank() } ?: return@use emptyList()
                parseAddonStreamsPayload(raw).map { it.withAddonIdentity(addon) }
            }
        }.onFailure {
            TvDebugLogger.w("Playback", "fetchFreshStreamsFromAddon failed addon=${addon.id} type=$streamType id=$videoId")
        }.getOrDefault(emptyList())
    }

    private fun encodePathSegment(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * Percent-encoding for a path segment sent straight to a third-party add-on.
     *
     * [encodePathSegment] form-encodes, turning a space into "+", which a server decoding a path
     * segment does not turn back into a space. That is harmless for StreamDek's own routes but
     * breaks add-on catalog ids containing spaces, and would break every multi-word search.
     */
    private fun addonPathSegment(value: String): String = android.net.Uri.encode(value)

    /**
     * Streams the addon results for a title as they arrive instead of waiting for every
     * addon to answer. Each emission carries the ranked streams gathered so far plus how
     * many sources are still outstanding, so the UI can render the first results
     * immediately and fill in the rest.
     */
    fun streamCandidates(
        mediaType: String,
        mediaId: String,
        imdbId: String?,
        episode: EpisodeContext? = null,
        preferredStreamKey: String? = null,
        preferredAddonName: String? = null,
        preferredQualityGroup: String? = null,
        streamType: String? = null,
        directStreamUrl: String? = null,
        requestHeaders: Map<String, String> = emptyMap(),
        sourceAddonId: String? = null,
        sourceAddonName: String? = null,
        forceRefresh: Boolean = false,
    ): kotlinx.coroutines.flow.Flow<StreamCandidatesProgress> = kotlinx.coroutines.flow.channelFlow {
        val isLive = mediaType == "live"
        if (isLive && !directStreamUrl.isNullOrBlank()) {
            val directStream = AddonStream(
                addonId = sourceAddonId.orEmpty(),
                addonName = sourceAddonName ?: label(R.string.stream_live_source, "Live source"),
                name = sourceAddonName ?: label(R.string.stream_live_source, "Live source"),
                title = label(R.string.stream_direct_live, "Direct live stream"),
                url = directStreamUrl,
                requestHeaders = requestHeaders,
            )
            send(StreamCandidatesProgress(listOf(directStream), pendingSources = 0, done = true))
            return@channelFlow
        }

        val perf = Perf.span("streams", "$mediaType:$mediaId")
        val firstResultLogged = java.util.concurrent.atomic.AtomicBoolean(false)
        val episodeKey = buildEpisodeKey(episode)
        val effectivePreferredStreamKey = effectiveRememberedStreamKey(
            explicitKey = preferredStreamKey,
            storedKey = sessionStore.preferredStreamKey(mediaType, mediaId, episodeKey),
            rememberLastSource = rememberLastSourceEnabled(),
        )
        val lookupTypes = streamLookupTypes(mediaType, streamType)
        val videoId = buildStreamVideoId(imdbId ?: mediaId, episode)
        val baseId = videoId.substringBefore(":")

        // Bootstrap already carries the same enabled add-on snapshot mobile starts from. A fresh
        // manifest request here serialised every provider behind one avoidable backend round trip.
        val addons = (bootstrapState.value?.integrations?.addons?.items.orEmpty()
            .takeIf { it.isNotEmpty() }
            ?: runCatching { fetchAddonManifests() }.getOrDefault(emptyList()))
            .filter { it.enabled }
            .sortedWith(compareByDescending<AddonManifest> { it.favourite }.thenBy { it.position })

        // Only the first lookup type that any addon claims to support is fanned out;
        // the remaining types stay available as a sequential fallback below.
        val primaryType = lookupTypes.firstOrNull { type -> addons.any { addonSupportsStreamType(it, type) } }
        val supportingAddons = primaryType?.let { type ->
            addons.filter { addon ->
                addonSupportsStreamType(addon, type) &&
                    (sourceAddonId.isNullOrBlank() || addon.id == sourceAddonId)
            }
        }.orEmpty()

        // One pending source per provider, not one for the whole set. Counting them together made
        // the picker claim a single outstanding source that only cleared when the slowest scraper
        // did, so the ones that had already answered looked like they were still running.
        val pluginProviderCount = pluginProviderCount(mediaType)

        // CloudStream extensions search by title, so they need one and are skipped for live.
        val cloudStreamTitle = if (isLive) null else {
            peekCachedDetail(mediaId, mediaType)?.title?.takeIf { it.isNotBlank() }
                ?: Perf.timed(perf, "cloudStreamTitleLookup") {
                    runCatching { fetchDetail(mediaId, mediaType)?.title }.getOrNull()?.takeIf { it.isNotBlank() }
                }
        }
        val cloudStreamSources = if (cloudStreamTitle == null) emptyList() else cloudStreamProviders()
        // Counted as one source rather than one per extension: the bridge fans out internally and
        // reports once, so a per-extension count would never come back down.
        val cloudStreamPending = if (cloudStreamSources.isEmpty()) 0 else 1

        val merged = java.util.concurrent.ConcurrentHashMap<String, AddonStream>()
        val order = java.util.concurrent.CopyOnWriteArrayList<String>()
        val remaining = java.util.concurrent.atomic.AtomicInteger(supportingAddons.size + pluginProviderCount + cloudStreamPending)
        val mutex = kotlinx.coroutines.sync.Mutex()
        // Snapshot creation and channel publication must be one serialized operation. Otherwise
        // an earlier, smaller snapshot can suspend in send() and arrive after a later, larger one.
        val publishMutex = kotlinx.coroutines.sync.Mutex()
        // Cap concurrent addon requests so a large addon list cannot saturate the
        // TV's limited network stack and slow down the first results.
        val gate = kotlinx.coroutines.sync.Semaphore(4)

        suspend fun mergeStreams(streams: List<AddonStream>) {
            mutex.withLock {
                streams.forEach { stream ->
                    val key = streamMergeKey(stream)
                    if (merged.putIfAbsent(key, stream) == null) order.add(key)
                }
            }
        }

        suspend fun replaceStreams(streams: List<AddonStream>) {
            mutex.withLock {
                merged.clear()
                order.clear()
                streams.forEach { stream ->
                    val key = streamMergeKey(stream)
                    if (merged.putIfAbsent(key, stream) == null) order.add(key)
                }
            }
        }

        suspend fun publish(done: Boolean) {
            publishMutex.withLock {
                val snapshot = mutex.withLock { order.mapNotNull { merged[it] } }
                if (snapshot.isNotEmpty() && firstResultLogged.compareAndSet(false, true)) {
                    perf.mark("firstResult", "count=${snapshot.size}")
                }
                send(
                    StreamCandidatesProgress(
                        streams = rankStreams(snapshot, effectivePreferredStreamKey, preferredAddonName, preferredQualityGroup),
                        pendingSources = remaining.get().coerceAtLeast(0),
                        done = done,
                    ),
                )
            }
        }

        perf.mark("discoveryStart", "addons=${supportingAddons.size} plugins=$pluginProviderCount cloudstream=$cloudStreamPending")
        send(StreamCandidatesProgress(emptyList(), pendingSources = remaining.get(), done = false))

        // A new search supersedes the one before it. Without this, opening a second title while
        // the first was still scraping left both fan-outs running against each other.
        cancelStreamDiscovery("new discovery")

        supervisorScope {
            activeDiscovery = coroutineContext[Job]
            if (cloudStreamPending > 0 && cloudStreamTitle != null) {
                launch {
                    runCatching {
                        // Published as each extension finishes rather than at the end, so the
                        // picker fills in the same way the add-on and plugin sources do.
                        val streams = CloudStreamProviderBridge.streams(
                            providers = cloudStreamSources,
                            request = CloudStreamProviderBridge.StreamRequest(
                                title = cloudStreamTitle,
                                year = peekCachedDetail(mediaId, mediaType)?.year?.toIntOrNull(),
                                type = if (mediaType == "series") "tv" else mediaType,
                                season = episode?.seasonNumber,
                                episode = episode?.episodeNumber,
                            ),
                            onProviderResults = { providerStreams ->
                                mergeStreams(providerStreams)
                                perf.mark("cloudstream:batch", "results=${providerStreams.size}")
                                publish(done = false)
                            },
                        )
                        mergeStreams(streams)
                    }.onFailure { TvDebugLogger.w("CloudStream", "lookup failed for $cloudStreamTitle", it) }
                    remaining.decrementAndGet()
                    publish(done = false)
                }
            }
            if (pluginProviderCount > 0) {
                launch {
                    val reported = java.util.concurrent.atomic.AtomicInteger(0)
                    val pluginBegan = android.os.SystemClock.uptimeMillis()
                    val streams = pluginStreams(mediaType, mediaId, imdbId, episode) { providerStreams ->
                        mergeStreams(providerStreams)
                        val n = reported.incrementAndGet()
                        perf.mark("plugin:batch$n", "took=${android.os.SystemClock.uptimeMillis() - pluginBegan} results=${providerStreams.size}")
                        remaining.decrementAndGet()
                        publish(done = false)
                    }
                    perf.mark("plugin:all", "took=${android.os.SystemClock.uptimeMillis() - pluginBegan} batches=${reported.get()}")
                    mergeStreams(streams)
                    // A lookup that never reached the fan-out — an id that would not resolve, or the
                    // whole call throwing — reports nothing, so its providers are retired here
                    // instead of sitting in the pending count until the flow completes.
                    repeat((pluginProviderCount - reported.get()).coerceAtLeast(0)) { remaining.decrementAndGet() }
                    publish(done = false)
                }
            }
            if (primaryType != null) {
                supportingAddons.forEach { addon ->
                    launch {
                        val began = android.os.SystemClock.uptimeMillis()
                        val streams = runCatching {
                            gate.withPermit { fetchStreamsFromSingleAddon(addon, primaryType, videoId, baseId, isLive, forceRefresh) }
                        }.getOrDefault(emptyList())
                        perf.mark("addon:${addon.id}", "took=${android.os.SystemClock.uptimeMillis() - began} results=${streams.size}")
                        mergeStreams(streams)
                        remaining.decrementAndGet()
                        publish(done = false)
                    }
                }
            }
        }

        remaining.set(0)
        if (merged.isEmpty()) {
            // Nothing from the per-addon or plugin fan-out — fall back to the aggregated route
            // and any remaining lookup types before declaring the list empty.
            val (_, fallback) = fetchStreamsForPlayback(lookupTypes, videoId, isLive)
            mergeStreams(fallback)
        }
        // Mobile asks the user's enabled premium services once, after every add-on/plugin has
        // answered. Doing the same here avoids hammering providers after each progressive batch
        // and ensures the final picker order and Cached column reflect this account, not merely
        // whatever cache marker an add-on happened to include.
        val completeSnapshot = mutex.withLock { order.mapNotNull { merged[it] } }
        if (completeSnapshot.isNotEmpty()) {
            Perf.timed(perf, "markCachedStreams") { replaceStreams(markCachedStreams(completeSnapshot)) }
        }
        publish(done = true)
        perf.end("done", "total=${completeSnapshot.size}")
    }

    private fun streamMergeKey(stream: AddonStream): String = streamAggregationKey(stream)

    private fun streamLookupTypes(mediaType: String, streamType: String?): List<String> = when (mediaType) {
        "live" -> {
            val native = streamType?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: "tv"
            buildList {
                add(native)
                if (native == "tv") add("live-tv")
                if (native == "live-tv") add("tv")
                if (native == "sport") add("sports")
                if (native == "sports") add("sport")
                addAll(listOf("live", "channel", "channels", "tv", "sport", "sports", "event", "events", "other"))
            }.distinct()
        }
        "tv" -> listOf("series")
        else -> listOf("movie")
    }


    /**
     * The stream-picker copy is the one every client now writes -- mobile, the web portal, and this
     * app's own settings screen -- so it is the one read here. PlaybackPreferences carries a field
     * of the same name that older TV builds wrote; it is left alone rather than folded in, because
     * with no timestamp on either there is no way to tell a stale "off" from a current one.
     */
    private fun rememberLastSourceEnabled(): Boolean {
        val preferences = bootstrapState.value?.preferences ?: return true
        return preferences.streams.rememberLastSource
    }

    /** Searches OpenSubtitles, installed subtitle addons, and mobile-managed cloud sources. */
    /**
     * Shares one subtitle fan-out between the player's repeated asks for the same content.
     *
     * The effect that drives it is keyed on the source URL and on the detail record's IMDb id, so
     * it legitimately re-runs when the detail lands — which meant every provider was queried twice
     * during the few hundred milliseconds the decoder was starting.
     */
    private val subtitleRequests = RequestCoalescer<String, List<ExternalSubtitleTrack>>()

    suspend fun fetchExternalSubtitles(request: PlaybackRequest): List<ExternalSubtitleTrack> {
        val key = listOf(
            request.mediaType,
            request.imdbId ?: request.mediaId,
            request.episode?.seasonNumber ?: -1,
            request.episode?.episodeNumber ?: -1,
        ).joinToString(":")
        return subtitleRequests.run(key) { fetchExternalSubtitlesUncoalesced(request) }
    }

    private suspend fun fetchExternalSubtitlesUncoalesced(request: PlaybackRequest): List<ExternalSubtitleTrack> = withContext(Dispatchers.IO) {
        val imdbId = request.imdbId?.takeIf { it.startsWith("tt") } ?: return@withContext emptyList()
        val isSeries = request.mediaType == "tv" || request.mediaType == "series"
        val videoId = if (isSeries) {
            val episode = request.episode ?: return@withContext emptyList()
            "$imdbId:${episode.seasonNumber}:${episode.episodeNumber}"
        } else {
            imdbId
        }
        val type = if (isSeries) "series" else "movie"
        val preferences = bootstrapState.value?.preferences?.playback ?: PlaybackPreferences()
        val preferredLanguages = listOf(
            preferences.defaultSubtitleLanguage,
            preferences.secondarySubtitleLanguage,
        ).map(Languages::normalize).filter { it.isNotBlank() && it != Languages.NONE }.toSet()
        val cloudSources = (preferences.subtitleSources + preferences.customSubtitleSources)
            .filter { it.enabled }
            .mapNotNull { source ->
                val baseUrl = source.baseUrl.ifBlank { source.url.orEmpty() }.trimEnd('/')
                baseUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?.let { SubtitleSourcePreference(source.id, source.name, it, enabled = true) }
            }
        val addonSources = bootstrapState.value?.integrations?.addons?.items.orEmpty()
            .filter { addon ->
                addon.enabled && addon.manifest.resources.any { resource ->
                    when (resource) {
                        is String -> resource.trim().equals("subtitles", ignoreCase = true)
                        is Map<*, *> -> resource["name"]?.toString()?.trim()?.equals("subtitles", ignoreCase = true) == true
                        else -> false
                    }
                }
            }
            .mapNotNull { addon ->
                val manifestUrl = addon.transportUrl ?: addon.manifestUrl ?: return@mapNotNull null
                val baseUrl = manifestUrl.substringBeforeLast("/manifest.json", manifestUrl).trimEnd('/')
                baseUrl.takeIf { it.isNotBlank() }?.let {
                    SubtitleSourcePreference("addon:${addon.id}", addon.manifest.name.ifBlank { addon.id }, it)
                }
            }
        val enabledBuiltInSources = if (!subtitleSourceAllowsOrigin(preferences.subtitleDefaultSource, ExternalSubtitleOrigin.BuiltIn)) emptyList() else
            listOf(SubtitleSourcePreference("opensubtitles", "OpenSubtitles", "https://opensubtitles-v3.strem.io")) + cloudSources
        val enabledAddonSources = if (
            !subtitleSourceAllowsOrigin(preferences.subtitleDefaultSource, ExternalSubtitleOrigin.Addon) ||
            preferences.addonSubtitleLoading.equals("off", ignoreCase = true)
        ) emptyList() else addonSources
        val sources = (enabledBuiltInSources + enabledAddonSources).distinctBy { it.baseUrl.lowercase(Locale.US) }

        supervisorScope {
            sources.map { source ->
                async {
                    runCatching {
                        val endpoint = "${source.baseUrl.trimEnd('/')}/subtitles/$type/${encodePathSegment(videoId)}.json"
                        val httpRequest = okhttp3.Request.Builder()
                            .url(endpoint)
                            .header("Accept", "application/json")
                            .header("User-Agent", "Stremio/4.4.168")
                            .build()
                        api.client.newCall(httpRequest).execute().use { response ->
                            if (!response.isSuccessful) {
                                TvDebugLogger.w("Subtitles", "${source.name} answered HTTP ${response.code}")
                                return@use emptyList()
                            }
                            val payload = api.gson.fromJson(response.body?.charStream(), StremioSubtitlesResponse::class.java)
                                ?: return@use emptyList()
                            payload.subtitles.mapNotNull { subtitle ->
                                val language = normalizeSubtitleLanguage(subtitle.language)
                                if (subtitle.id.isBlank() || subtitle.url.isBlank() || language.isBlank()) return@mapNotNull null
                                ExternalSubtitleTrack(
                                    id = "${source.id}:${subtitle.id}",
                                    language = language,
                                    // Named, not coded. "FR - <release> - OpenSubtitles" asks a
                                    // viewer to know that FR is French before they can pick their
                                    // own language out of eighty rows; the add-on's two-letter tag
                                    // is what this list is sorted by, not what it is read by.
                                    label = listOf(Languages.label(language), subtitle.release, source.name.ifBlank { "Subtitle addon" })
                                        .filter { it.isNotBlank() }.joinToString(" - "),
                                    url = subtitle.url,
                                    origin = externalSubtitleOrigin(source.id),
                                    sourceName = source.name.ifBlank { if (source.id.startsWith("addon:")) "Subtitle add-on" else "StreamDek" },
                                    release = subtitle.release.takeIf { it.isNotBlank() },
                                )
                            }.also { parsed ->
                                TvDebugLogger.i(
                                    "Subtitles",
                                    "${source.name} returned ${payload.subtitles.size} entries, ${parsed.size} usable",
                                )
                            }
                        }
                    }.onFailure { TvDebugLogger.w("Subtitles", "lookup failed source=${source.name}: ${it.message}") }
                        .getOrDefault(emptyList())
                }
            }.map { it.await() }
                .flatten()
                .distinctBy { it.url }
                .let { results ->
                    val matching = results.filter { Languages.normalize(it.language) in preferredLanguages }
                    when {
                        preferences.showOnlyPreferredSubtitleLanguages -> matching
                        preferences.addonSubtitleLoading.equals("preferred", ignoreCase = true) -> {
                            val builtIn = results.filter { it.origin == ExternalSubtitleOrigin.BuiltIn }
                            val addons = results.filter { it.origin == ExternalSubtitleOrigin.Addon }
                            val matchingAddons = addons.filter { Languages.normalize(it.language) in preferredLanguages }
                            builtIn + matchingAddons.ifEmpty { addons }
                        }
                        else -> results
                    }
                }
                .sortedWith(compareBy<ExternalSubtitleTrack> {
                    when (it.language) {
                        normalizeSubtitleLanguage(preferences.defaultSubtitleLanguage) -> 0
                        "en" -> 1
                        else -> 2
                    }
                }.thenBy { it.label })
                .take(80)
        }
    }

    /**
     * What this subtitle actually is, read from the file rather than from its address or headers.
     *
     * These URLs carry no extension -- ".../file/1962235234" -- and the content type is whatever
     * the host felt like sending, so a WebVTT or ASS file served as text/plain was saved as .srt
     * and parsed to nothing. Nothing is exactly what the viewer then sees: no error, no subtitles.
     */
    private fun subtitleExtensionFor(text: String): String {
        val head = text.take(4_096)
        return when {
            head.trimStart().startsWith("WEBVTT") -> "vtt"
            head.contains("[Events]", ignoreCase = true) && head.contains("Dialogue:", ignoreCase = true) -> "ass"
            head.contains("[Script Info]", ignoreCase = true) -> "ass"
            Regex("""<tt[\s>]""", RegexOption.IGNORE_CASE).containsMatchIn(head) -> "ttml"
            else -> "srt"
        }
    }

    /**
     * Text out of whatever the source encoded it in.
     *
     * OpenSubtitles alone serves both UTF-8 and CP1252 for the same title, and a CP1252 file read
     * as UTF-8 loses every accented character. Strict decoding tells them apart -- real UTF-8
     * either decodes or throws -- and everything is rewritten as UTF-8 so the players see one
     * encoding.
     */
    private fun decodeSubtitleBytes(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(body))
                .toString()
        }.getOrElse { String(body, charset("windows-1252")) }
    }

    /** Writes an explicit completion marker so SyncDek and every mirrored service see the finish. */
    suspend fun completeProgress(
        mediaType: String,
        mediaId: String,
        episode: EpisodeContext?,
        detail: MediaDetail?,
        positionSec: Double,
        durationSec: Double,
    ): Boolean = runCatching {
        api.request<Any>(
            method = "PUT",
            path = "/sync/progress",
            body = com.google.gson.Gson().toJson(
                mapOf(
                    "entityType" to mediaType,
                    "entityId" to mediaId,
                    "positionSec" to positionSec.coerceAtLeast(0.0),
                    "durationSec" to durationSec.coerceAtLeast(0.0),
                    "episodeKey" to buildEpisodeKey(episode),
                    "completed" to true,
                    "updatedAt" to Instant.now().toString(),
                    "lastDevice" to "StreamDek TV",
                    "lastPlatform" to "tv",
                    "metadata" to buildSyncMetadata(detail, episode),
                ),
            ),
        )
        invalidatePlaybackDerivedCaches()
        true
    }.onFailure {
        TvDebugLogger.w("Playback", "completeProgress failed mediaType=$mediaType mediaId=$mediaId")
    }.getOrDefault(false)

    suspend fun downloadSubtitleToCache(url: String, cacheDir: File): String? = withContext(Dispatchers.IO) {
        runCatching {
            // Media3's pre-prepare path has already downloaded and validated this sidecar. If an
            // automatic codec fallback switches to MPV, reuse that file instead of treating its
            // absolute path as an HTTP URL (which also avoids a second main-thread stall).
            val subtitleDirectory = File(cacheDir, "subtitles")
            val local = File(url)
            val localPath = runCatching { local.canonicalFile }.getOrNull()
            val subtitleRoot = runCatching { subtitleDirectory.canonicalFile }.getOrNull()
            if (
                localPath?.isFile == true &&
                subtitleRoot != null &&
                localPath.path.startsWith(subtitleRoot.path + File.separator) &&
                subtitleTextHasTimedCues(localPath.readText(), localPath.extension.lowercase())
            ) return@runCatching localPath.absolutePath
            // Presented as a browser: several of these hosts answer anything else with a refusal
            // rather than a file, and a refusal arrives as a subtitle that never appears.
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", SUBTITLE_USER_AGENT)
                .header("Accept", "*/*")
                .apply {
                    runCatching { java.net.URI(url) }.getOrNull()
                        ?.let { uri -> uri.scheme?.let { scheme -> uri.host?.let { host -> "$scheme://$host/" } } }
                        ?.let { header("Referer", it) }
                }
                .build()
            val directory = subtitleDirectory.apply { mkdirs() }
            val stem = "${url.hashCode().toUInt()}"
            // Whatever extension was written for this URL before; the content decided it then.
            directory.listFiles { file -> file.name.startsWith("$stem.") }
                ?.firstOrNull { it.length() > 0L }
                ?.let { cached ->
                    if (runCatching { subtitleTextHasTimedCues(cached.readText(), cached.extension.lowercase()) }.getOrDefault(false)) return@runCatching cached.absolutePath
                    cached.delete()
                }
            val bytes = api.client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Subtitle download failed: ${response.code}" }
                response.body?.bytes() ?: error("Empty subtitle response")
            }
            val text = decodeSubtitleBytes(bytes)
            check(text.isNotBlank()) { "Empty subtitle response" }
            val extension = subtitleExtensionFor(text)
            check(subtitleTextHasTimedCues(text, extension)) { "Subtitle response contained no timed cues" }
            val target = File(directory, "$stem.$extension")
            target.writeText(text, Charsets.UTF_8)
            TvDebugLogger.i("Subtitles", "cached ${target.name} (${bytes.size} bytes)")
            target.absolutePath
        }.onFailure { TvDebugLogger.w("Subtitles", "download failed: ${it.message}") }.getOrNull()
    }

    private fun normalizeSubtitleLanguage(raw: String?): String = Languages.normalize(raw)
    suspend fun prefetchPlayback(

        mediaType: String,
        mediaId: String,
        imdbId: String?,
        episode: EpisodeContext? = null,
    ) {
        resolvePlayback(mediaType, mediaId, imdbId, episode, preferredStreamKey = null, forceRefresh = false)
    }

    /**
     * Resolves a stream that the source picker already discovered. This is the latency-critical
     * path: do not query every enabled addon again after the viewer has selected one.
     */
    suspend fun resolveSelectedPlayback(
        request: PlaybackRequest,
        stream: AddonStream,
        streams: List<AddonStream> = emptyList(),
        forceRefresh: Boolean = false,
    ): ResolvedPlaybackCandidate {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val selectedKey = streamSelectionKey(stream)
        val cacheKey = playbackCacheKey(
            mediaType = request.mediaType,
            mediaId = request.mediaId,
            imdbId = request.imdbId,
            episode = request.episode,
            preferredStreamKey = selectedKey,
            streamType = request.streamType,
        )
        if (!forceRefresh) {
            readResolvedPlaybackCache(cacheKey)?.let { cached ->
                if (cached.source != null && rememberLastSourceEnabled()) {
                    sessionStore.savePreferredStreamKey(request.mediaType, request.mediaId, buildEpisodeKey(request.episode), selectedKey)
                }
                TvDebugLogger.i("Playback", "selected-source cache hit addon=${stream.addonName} elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}")
                return cached.copy(streams = streams.ifEmpty { cached.streams })
            }
        }

        val lookupType = streamLookupTypes(request.mediaType, request.streamType).firstOrNull()
        val videoId = buildStreamVideoId(request.imdbId ?: request.mediaId, request.episode)
        val playbackStream = if (!lookupType.isNullOrBlank()) {
            refreshStreamForPlayback(stream, lookupType, videoId)
        } else {
            stream
        }
        val resolvedUrl = resolveStreamToUrl(
            playbackStream,
            mediaTitle = request.title,
            seasonNumber = request.episode?.seasonNumber,
            episodeNumber = request.episode?.episodeNumber,
        )
        val allStreams = streams.ifEmpty { listOf(stream) }
        val candidate = if (resolvedUrl.isNullOrBlank()) {
            ResolvedPlaybackCandidate(null, null, allStreams)
        } else {
            ResolvedPlaybackCandidate(
                source = ResolvedPlaybackSource(
                    url = resolvedUrl,
                    contentType = guessContentType(resolvedUrl),
                    label = describeStream(playbackStream),
                    filename = effectiveFilename(playbackStream),
                    requestHeaders = playbackStream.requestHeaders,
                ),
                stream = playbackStream,
                streams = allStreams,
            )
        }
        if (candidate.source != null) {
            if (rememberLastSourceEnabled()) {
                sessionStore.savePreferredStreamKey(request.mediaType, request.mediaId, buildEpisodeKey(request.episode), selectedKey)
            }
        }
        writeResolvedPlaybackCache(cacheKey, candidate)
        TvDebugLogger.i(
            "Playback",
            "selected-source resolved addon=${stream.addonName} direct=${normalizedDirectUrl(playbackStream) != null} elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}",
        )
        return candidate
    }

    /**
     * Works down the ranked sources until one opens.
     *
     * A source can fail before a single frame is decoded — a usenet post packed into archives, a
     * debrid link that has expired, an add-on whose host is down — and stopping at the first of
     * those put the viewer back on the picker to choose again from a list that gives no clue which
     * entries are dead. The list is already ranked, so the next one down is exactly what they would
     * have picked anyway.
     *
     * [onAttempt] and [onAttemptFailed] are the caller's chance to say what is happening. This can
     * take several seconds per source, and a screen that sits on a spinner through three of them
     * looks like a hang rather than like progress.
     */
    suspend fun resolveFirstPlayableSource(
        request: PlaybackRequest,
        streams: List<AddonStream>,
        skipKeys: Set<String> = emptySet(),
        forceRefresh: Boolean = false,
        onAttempt: suspend (stream: AddonStream) -> Unit = {},
        onAttemptFailed: suspend (stream: AddonStream, failureKey: String) -> Unit = { _, _ -> },
    ): ResolvedPlaybackCandidate? {
        for (stream in streams) {
            val key = streamSelectionKey(stream)
            if (key in skipKeys) continue
            onAttempt(stream)
            val resolved = runCatching {
                resolveSelectedPlayback(
                    request = request,
                    stream = stream,
                    streams = streams,
                    forceRefresh = forceRefresh,
                )
            }.onFailure {
                TvDebugLogger.w("Playback", "source ${stream.addonName} threw while resolving", it)
            }.getOrNull()
            if (resolved?.source != null) return resolved
            onAttemptFailed(stream, key)
        }
        return null
    }

    /**
     * How a source delivers, in one word, for a status line that has to name what just failed.
     *
     * "Usenet" and "Torrent" fail for entirely different reasons and take different lengths of
     * time to give up, so a viewer watching the player work through a list is owed the distinction.
     */
    fun streamDeliveryLabel(stream: AddonStream?): String = when {
        stream == null -> "Source"
        isUsenetStream(stream) -> "Usenet"
        !normalizedDirectUrl(stream).isNullOrBlank() -> "Direct"
        !effectiveInfoHash(stream).isNullOrBlank() -> "Torrent"
        else -> "Source"
    }

    suspend fun resolvePlaybackSource(
        stream: AddonStream,
        lookupType: String? = null,
        videoId: String? = null,
    ): ResolvedPlaybackSource? {
        val resolvedUrl = resolveStreamToUrl(stream, lookupType, videoId) ?: return null
        return ResolvedPlaybackSource(
            url = resolvedUrl,
            contentType = guessContentType(resolvedUrl),
            label = describeStream(stream),
            filename = effectiveFilename(stream),
            requestHeaders = stream.requestHeaders,
        )
    }

    suspend fun fetchEpisodeSegments(
        imdbId: String,
        season: Int,
        episode: Int,
    ): List<PlaybackSegment> {
        if (!imdbId.startsWith("tt") || season < 0 || episode <= 0) return emptyList()
        val cacheKey = "$imdbId:$season:$episode"
        episodeSegmentCache[cacheKey]?.let { return it }
        val params = "imdb_id=${URLEncoder.encode(imdbId, "UTF-8")}&season=$season&episode=$episode"
        val result = withContext(Dispatchers.IO) { runCatching {
            val payload = api.get<Any>("/services/timings/introdb?$params")
                ?: throw IllegalStateException("IntroDB timing lookup failed")
            val segments = parseIntroDbSegments(payload).toMutableList()
            if (segments.none { it.segmentType == "intro" }) {
                parseLegacyIntroSegment(fetchLegacyIntroPayload(imdbId, season, episode))?.let { legacyIntro ->
                    segments.removeAll { it.segmentType == "intro" }
                    segments.add(0, legacyIntro)
                }
            }
            segments.sortedWith(compareBy<PlaybackSegment> { it.startSec }.thenBy { it.endSec }.thenBy { it.segmentType })
        }.recoverCatching {
            parseLegacyIntroSegment(fetchLegacyIntroPayload(imdbId, season, episode))?.let(::listOf) ?: emptyList()
        }.onFailure {
            TvDebugLogger.w("Playback", "fetchEpisodeSegments failed imdbId=$imdbId season=$season episode=$episode")
        }.getOrDefault(emptyList()) }
        episodeSegmentCache[cacheKey] = result
        return result
    }

    private suspend fun fetchTheIntroDbSegments(
        tmdbId: Int,
        mediaType: String,
        season: Int? = null,
        episode: Int? = null,
        durationSec: Double? = null,
    ): List<PlaybackSegment> {
        if (tmdbId <= 0) return emptyList()
        val durationMs = durationSec?.takeIf { it.isFinite() && it > 0.0 }?.times(1000.0)?.toLong()
        val cacheKey = "theintrodb:$mediaType:$tmdbId:${season ?: 0}:${episode ?: 0}:${durationMs ?: 0L}"
        movieSegmentCache[cacheKey]?.let { return it }
        val result = withContext(Dispatchers.IO) {
            val path = buildString {
                append("/services/timings/theintrodb?tmdb_id=").append(tmdbId)
                season?.let { append("&season=").append(it) }
                episode?.let { append("&episode=").append(it) }
                durationMs?.let { append("&duration_ms=").append(it) }
            }
            api.get<JsonObject>(path)
                ?.let { TheIntroDbClient.parseMedia(api.gson.toJson(it), api.gson) }
                ?.takeIf { (it.tmdbId == null || it.tmdbId == tmdbId) && (it.type == null || it.type == mediaType) }
                ?.let { media ->
                    fun mapped(type: String, values: List<TheIntroDbTimestamp>) = values.mapNotNull { value ->
                        val start = value.startMs / 1000.0
                        val end = value.endMs?.div(1000.0) ?: durationSec
                        end?.let { PlaybackSegment(type, start, it) }
                    }
                    mapped("intro", media.intro) + mapped("recap", media.recap) + mapped("outro", media.credits)
                }
                .orEmpty()
                .filter { it.startSec >= 0.0 && it.endSec > it.startSec && (durationSec == null || (it.startSec < durationSec && it.endSec <= durationSec + 2.0)) }
                .distinctBy { Triple(it.segmentType, it.startSec, it.endSec) }
                .sortedBy { it.startSec }
        }
        movieSegmentCache[cacheKey] = result
        return result
    }

    suspend fun resolvePlaybackTimingSegments(
        mediaType: String,
        tmdbId: Int,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        durationSec: Double?,
        preferences: PlaybackPreferences,
    ): List<PlaybackSegment> {
        val preferred = preferences.timingProvider.takeIf { it in setOf("introdb", "theintrodb") } ?: "introdb"
        suspend fun load(provider: String): List<PlaybackSegment> = when {
            provider == "theintrodb" -> fetchTheIntroDbSegments(tmdbId, mediaType, season.takeIf { mediaType == "tv" }, episode.takeIf { mediaType == "tv" }, durationSec)
            mediaType == "tv" && !imdbId.isNullOrBlank() && season != null && episode != null -> fetchEpisodeSegments(imdbId, season, episode)
            else -> emptyList()
        }
        val primary = load(preferred)
        if (primary.isNotEmpty()) {
            TvDebugLogger.i("Playback", "timing provider=$preferred fallback=none segments=${primary.size}")
            return primary
        }
        if (!preferences.timingProviderFallbackEnabled) {
            TvDebugLogger.i("Playback", "timing provider=none preferred=$preferred fallback=disabled")
            return emptyList()
        }
        val alternateProvider = if (preferred == "theintrodb") "introdb" else "theintrodb"
        val alternate = load(alternateProvider)
        TvDebugLogger.i("Playback", "timing provider=${if (alternate.isEmpty()) "none" else alternateProvider} preferred=$preferred fallback=no_usable_data segments=${alternate.size}")
        return alternate
    }

    private suspend fun markSeriesWatched(
        mediaId: String,
        title: String,
        year: String?,
    ): Boolean {
        val detail = fetchDetail(mediaId, "tv") ?: return false
        val watchedAt = Instant.now().toString()
        val seasonsPayload = detail.seasons.mapNotNull { seasonRef ->
            val season = fetchSeason(mediaId, seasonRef.seasonNumber) ?: return@mapNotNull null
            val episodes = season.episodes.map {
                mapOf(
                    "number" to it.episodeNumber,
                    "watched_at" to watchedAt,
                )
            }
            if (episodes.isEmpty()) null else mapOf(
                "number" to seasonRef.seasonNumber,
                "episodes" to episodes,
            )
        }
        if (seasonsPayload.isEmpty()) return false
        val payload = mapOf(
            "movies" to emptyList<Any>(),
            "shows" to listOf(
                mapOf(
                    "title" to title,
                    "year" to year?.take(4)?.toIntOrNull(),
                    "ids" to mapOf(
                        "tmdb" to mediaId.toIntOrNull(),
                        "imdb" to detail.imdbId,
                    ),
                    "seasons" to seasonsPayload,
                ),
            ),
        )
        return runCatching {
            val ok = api.post<Any>("/trakt/sync/watched", payload) != null
            if (ok) {
                invalidatePlaybackDerivedCaches()
            }
            ok
        }.onFailure {
            TvDebugLogger.w("Trakt", "markSeriesWatched failed mediaId=$mediaId")
        }.getOrDefault(false)
    }

    fun streamSelectionKey(stream: AddonStream): String {
        return listOfNotNull(
            stream.addonId.takeIf { it.isNotBlank() },
            stream.infoHash?.lowercase()?.takeIf { it.isNotBlank() },
            stream.url?.trim()?.takeIf { it.isNotBlank() },
            stream.behaviorHints?.filename?.trim()?.takeIf { it.isNotBlank() },
            stream.title?.trim()?.takeIf { it.isNotBlank() },
            stream.name?.trim()?.takeIf { it.isNotBlank() },
        ).joinToString("|")
    }

    fun describeStreamOption(stream: AddonStream): String = describeStream(stream)

    private suspend fun resolveStreamToUrl(
        stream: AddonStream,
        lookupType: String? = null,
        videoId: String? = null,
        mediaTitle: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): String? {
        // Expired direct links (short-lived addon URLs) are refreshed straight from the
        // source addon before playback, mirroring the mobile app's refresh behavior.
        val playbackStream = if (!lookupType.isNullOrBlank() && !videoId.isNullOrBlank()) {
            refreshStreamForPlayback(stream, lookupType, videoId)
        } else {
            stream
        }
        // Cleared for every attempt, not only usenet ones, so what it holds always belongs to the
        // source that was tried last. Left standing it would outlive its own source and explain a
        // torrent's failure with the reason a usenet post gave three sources ago.
        lastUsenetFailureMessage = null
        if (!isPlayableStreamOption(playbackStream)) return null
        normalizedDirectUrl(playbackStream)?.let { return it }
        if (isUsenetStream(playbackStream)) {
            // Assembled here on the TV: the NZB names the articles, the stream's own `servers`
            // list says where to pull them from, and the player is handed a loopback URL into the
            // local assembler. Nothing about it touches a StreamDek server.
            val context = appContext ?: return null
            return runCatching {
                withContext(Dispatchers.IO) {
                    UsenetPlayback.open(context, playbackStream.nzbUrl.orEmpty(), playbackStream.servers)
                }
            }.onFailure {
                TvDebugLogger.w("Playback", "usenet source ${playbackStream.addonName} could not be opened", it)
                lastUsenetFailureMessage = it.message?.takeIf { message -> message.isNotBlank() }
            }.getOrNull()
        }
        val infoHash = effectiveInfoHash(playbackStream) ?: return null
        val filename = effectiveFilename(playbackStream)
        val magnetLink = buildMagnetLink(infoHash, filename)
        val payload = buildMap<String, Any> {
            put("infoHash", infoHash)
            put("magnetLink", magnetLink)
            filename?.let { put("filename", it) }
            playbackStream.cachedBy.firstOrNull()?.let { put("providerHint", it) }
            maxFileSizeBytes()?.let { put("maxSize", it) }
        }
        return runCatching {
            // Resolved on this device with its own keys wherever they are held here, so the
            // provider sees this household rather than one StreamDek server address standing in
            // for every account on the platform. Backend debrid is used only for an account that
            // explicitly enabled Server-Side Streaming; it is never a P2P fallback.
            deviceDebrid()?.let { manager ->
                val resolution = manager.resolve(infoHash, magnetLink, filename)
                resolution.stream?.link?.url?.takeIf { it.isNotBlank() }?.let { return@runCatching it }
                resolution.failures.firstOrNull()?.let {
                    TvDebugLogger.w("Playback", "device debrid could not resolve $infoHash: ${it.message}")
                }
            }
            if (usesServerSideStreams()) {
                val debrid = api.post<DebridResolveResponse>("/debrid/resolve", payload)
                if (!debrid?.url.isNullOrBlank()) return@runCatching debrid.url
            }
            val context = appContext ?: return@runCatching null
            val episodeParts = videoId?.split(':').orEmpty()
            val parsedSeason = episodeParts.takeIf { it.size >= 3 }?.getOrNull(episodeParts.size - 2)?.toIntOrNull()
            val parsedEpisode = episodeParts.takeIf { it.size >= 3 }?.lastOrNull()?.toIntOrNull()
            withContext(Dispatchers.IO) {
                LocalTorrentPlayback.open(
                    context = context,
                    infoHash = infoHash,
                    magnetLink = magnetLink,
                    preferredFilename = filename,
                    title = mediaTitle ?: playbackStream.title ?: playbackStream.name,
                    season = seasonNumber ?: parsedSeason,
                    episode = episodeNumber ?: parsedEpisode,
                )
            }
        }.onFailure {
            TvDebugLogger.e("Playback", "resolveStreamToUrl failed infoHash=$infoHash", it)
        }.getOrNull()
    }

    /**
     * This device's own premium services, or null when it holds no keys.
     *
     * Held rather than rebuilt per call: each provider memoises what its account already contains —
     * Real-Debrid pages through its whole library to answer one cache check — and a manager built
     * fresh for every playback would discard that and pay for it again. Dropped whenever the keys
     * change so a disconnected service stops being asked straight away.
     */
    @Volatile private var deviceDebridManager: DebridManager? = null

    private fun deviceDebrid(): DebridManager? {
        val context = appContext ?: return null
        val existing = deviceDebridManager ?: DebridManager.fromStoredKeys(context).also { deviceDebridManager = it }
        return existing.takeIf { it.hasProviders }
    }

    /**
     * Brings this device's copy of the premium-service keys up to date.
     *
     * Quiet on failure by design: the keys are how playback avoids the round trip through
     * StreamDek's servers, not a prerequisite for it, and [resolveStreamToUrl] still has the
     * backend debrid route only when Server-Side Streaming is enabled. Native P2P never uses it.
     */
    suspend fun syncDebridKeys() {
        val context = appContext ?: return
        // Nothing to pull when the keys are not in the cloud — and nothing to clear either. This
        // device holds the only copy, so writing an empty server list over the store would destroy
        // the credentials outright.
        if (!debridCloudSyncEnabled()) return
        val keys = runCatching { api.get<DebridKeysResponse>("/debrid/accounts/keys")?.accounts }
            .onFailure { TvDebugLogger.w("Debrid", "could not sync premium service keys", it) }
            .getOrNull()
            ?: return
        // A credential this television signed in for itself outranks the account's copy of it.
        // Real-Debrid's device sign-in stores a token plus the material that renews it, and only
        // the token is ever posted to the account — so taking the server's version wholesale would
        // drop the renewal material and leave a credential dead within the hour.
        val locallyRenewing = DebridKeyStore.load(context).filter { it.refreshToken != null }
        val selfRenewing = locallyRenewing.map { local ->
            val account = keys.firstOrNull { it.provider == local.provider }
            if (account == null) local else local.copy(
                priority = account.priority ?: local.priority,
                enabled = account.enabled ?: local.enabled,
                username = account.username ?: local.username,
            )
        }
        val fromAccount = keys.mapNotNull { key ->
            val provider = key.provider?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (selfRenewing.any { it.provider == provider }) return@mapNotNull null
            val apiKey = key.apiKey?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DebridKeyStore.StoredKey(provider, apiKey, key.priority ?: 0, key.enabled ?: true, key.username)
        }
        DebridKeyStore.save(context, selfRenewing + fromAccount)
        deviceDebridManager = null
    }

    /** Whether this build can offer Premiumize sign-in at all; blank id hides the option. */
    fun premiumizeSignInAvailable(): Boolean =
        PremiumizeDeviceAuth.isConfigured(BuildConfig.PREMIUMIZE_CLIENT_ID)

    /** Every premium service this build can talk to, as provider id to the name viewers know. */
    fun supportedDebridProviders(): List<Pair<String, String>> = SUPPORTED_DEBRID_PROVIDERS

    /**
     * Whether a service can be connected by approving a code on a phone.
     *
     * The rest want an API key, which on a television means one typed on an on-screen keyboard —
     * so the settings page has to offer a different action for each kind, and this is what tells
     * them apart. Premiumize is only in this group when the build carries a client id.
     */
    fun debridProviderUsesDeviceSignIn(provider: String): Boolean = when (provider) {
        "real-debrid" -> true
        "premiumize" -> premiumizeSignInAvailable()
        else -> false
    }

    /**
     * Connects a service from a typed API key.
     *
     * The key is checked against the provider before anything is stored: a mistyped character on a
     * remote is likely enough that saving it unverified would leave an account that looks connected
     * and silently serves nothing. Returns the name the provider reports, or null if the key was
     * refused.
     */
    // ── Content services (TMDB, MDBList) ─────────────────────────────────────────────────────
    //
    // Everything a television needs to do with the viewer's own enrichment keys. The precedence
    // between a key kept here and one saved to the account lives in ServiceCredentialManager,
    // shared verbatim with the phone; this only moves keys around and reports what happened.

    val serviceCredentials: ServiceCredentialManager get() = api.serviceCredentials

    private val contentServicesState = MutableStateFlow(ContentServicesState())

    /** What the settings screen renders. Updated from the bootstrap and after any change here. */
    val contentServices: StateFlow<ContentServicesState> = contentServicesState

    /**
     * Folds the account's answer into this television's own.
     *
     * Reads the state off the ordinary bootstrap rather than a call of its own, which is what
     * makes a key added on the phone or the web portal simply be there the next time the
     * television refreshes -- no restart, and no long-lived cached copy of anyone's secret.
     */
    fun applyContentServices(bootstrap: AccountBootstrap?) {
        val reported = bootstrap?.integrations?.contentServices
        val account = reported?.let { envelope ->
            fun stateFor(service: ContentService): AccountCredentialState? =
                envelope.services.firstOrNull { it.service.equals(service.id, ignoreCase = true) }
                    ?.let { entry ->
                        AccountCredentialState(
                            service = service,
                            configured = entry.configured,
                            maskedKey = entry.maskedKey,
                            label = entry.label,
                            needsAttention = entry.status == "needs_attention",
                        )
                    }
            AccountCredentials(
                tmdb = stateFor(ContentService.Tmdb),
                mdblist = stateFor(ContentService.Mdblist),
                introDb = stateFor(ContentService.IntroDb),
                theIntroDb = stateFor(ContentService.TheIntroDb),
                sharedFallbackAvailable = envelope.sharedFallbackAvailable,
            )
        }
        contentServicesState.value = serviceCredentials.mergeAll(account, contentServicesState.value)
    }

    /** Re-reads the account state on its own, for the settings screen's Refresh action. */
    suspend fun refreshContentServices() {
        val session = currentSession()
        if (session == null) {
            contentServicesState.value = serviceCredentials.mergeAll(null, contentServicesState.value)
            return
        }
        applyContentServices(refreshBootstrap())
    }

    /**
     * Checks a key against the service without saving it anywhere.
     *
     * Used before a key is kept on this television, so "This TV only" earns the same confirmation
     * as saving to the account -- and StreamDek still stores nothing.
     */
    private suspend fun validateContentServiceKey(
        service: ContentService,
        apiKey: String,
    ): Result<String?> {
        val response = api.post<JsonObject>(
            "/services/credentials/${service.id}/validate",
            mapOf("apiKey" to apiKey),
        ) ?: return Result.failure(IllegalStateException(CredentialFailure.ServiceUnavailable.message))

        if (response.get("valid")?.asBoolean == true) {
            return Result.success(response.get("label")?.takeIf { !it.isJsonNull }?.asString)
        }
        val failure = CredentialFailure.fromId(
            response.get("failure")?.takeIf { !it.isJsonNull }?.asString,
        )
        return Result.failure(IllegalStateException(failure.message))
    }

    /**
     * Puts a key where the viewer asked for it.
     *
     * The account route validates server-side before storing, so a key that has never worked is
     * never saved for every device to inherit. The device route checks first and then writes to
     * the keystore-backed vault, and nothing leaves this television.
     */
    suspend fun submitContentServiceKey(
        service: ContentService,
        apiKey: String,
        choice: StorageChoice,
    ): Result<String> {
        val trimmed = apiKey.trim()
        if (trimmed.length < 8) return Result.failure(IllegalStateException(CredentialFailure.Malformed.message))

        if (choice == StorageChoice.SaveToStreamDek) {
            if (currentSession() == null) {
                return Result.failure(IllegalStateException(CredentialFailure.NotSignedIn.message))
            }
            val response = api.put<JsonObject>(
                "/services/credentials/${service.id}",
                mapOf("apiKey" to trimmed),
            ) ?: return Result.failure(IllegalStateException(CredentialFailure.ServiceUnavailable.message))
            response.get("error")?.takeIf { !it.isJsonNull }?.let {
                return Result.failure(IllegalStateException(it.asString))
            }
            // Saved to the account, so this television keeps no copy of its own: one key in one
            // place, and removing it from the account removes it everywhere at once.
            serviceCredentials.clearDeviceKey(service)
            refreshContentServices()
            return Result.success(
                "${service.label} connected and saved to your StreamDek account. Your other devices will use it too.",
            )
        }

        // Device-only. Checked first where there is an account to check through; stored unverified
        // otherwise, because it is the viewer's own key and it gets checked the moment it is used.
        if (currentSession() != null) {
            validateContentServiceKey(service, trimmed).getOrElse { return Result.failure(it) }
        }
        if (!serviceCredentials.saveDeviceKey(service, trimmed)) {
            return Result.failure(
                IllegalStateException(
                    "This television could not store the key securely, so it has not been saved. " +
                        "Saving it to your StreamDek account instead will work.",
                ),
            )
        }
        refreshContentServices()
        return Result.success("${service.label} connected, and kept on this TV only.")
    }

    /**
     * Copies a key held here up to the account, at the viewer's request.
     *
     * The only path by which a device-only key ever reaches StreamDek. The local copy is dropped
     * once the account has it, so there is one key in one place afterwards.
     */
    suspend fun copyContentServiceKeyToAccount(service: ContentService): Result<String> {
        if (currentSession() == null) {
            return Result.failure(IllegalStateException(CredentialFailure.NotSignedIn.message))
        }
        val key = serviceCredentials.deviceKey(service)
            ?: return Result.failure(IllegalStateException("There is no ${service.label} key on this TV to save."))
        val response = api.put<JsonObject>(
            "/services/credentials/${service.id}",
            mapOf("apiKey" to key),
        ) ?: return Result.failure(IllegalStateException(CredentialFailure.ServiceUnavailable.message))
        response.get("error")?.takeIf { !it.isJsonNull }?.let {
            return Result.failure(IllegalStateException(it.asString))
        }
        serviceCredentials.clearDeviceKey(service)
        refreshContentServices()
        return Result.success("${service.label} is now saved to your StreamDek account.")
    }

    /**
     * Removes a key, from wherever the viewer said.
     *
     * The two scopes are never collapsed into one destructive action: removing the account copy
     * takes it from every signed-in device, and the screen says so before this is reached.
     */
    suspend fun removeContentServiceKey(
        service: ContentService,
        scope: CredentialRemoval,
    ): Result<String> {
        if (scope == CredentialRemoval.Device) {
            serviceCredentials.clearDeviceKey(service)
            refreshContentServices()
            return Result.success("${service.label} key removed from this TV.")
        }
        if (currentSession() == null) {
            return Result.failure(IllegalStateException(CredentialFailure.NotSignedIn.message))
        }
        api.delete<JsonObject>("/services/credentials/${service.id}")
            ?: return Result.failure(IllegalStateException(CredentialFailure.ServiceUnavailable.message))
        refreshContentServices()
        return Result.success(
            "${service.label} key removed from your StreamDek account. Your other devices will stop using it.",
        )
    }

    suspend fun connectDebridApiKey(provider: String, apiKey: String): String? {
        val context = appContext ?: return null
        val key = apiKey.trim().takeIf { it.isNotEmpty() } ?: return null
        val client = DebridManager.build(provider, key) ?: return null
        val validation = runCatching { client.validate() }
            .onFailure { TvDebugLogger.w("Debrid", "$provider key could not be checked", it) }
            .getOrNull()
        if (validation?.valid != true) return null
        val stored = DebridKeyStore.load(context).filterNot { it.provider == provider }
        DebridKeyStore.save(
            context,
            stored + DebridKeyStore.StoredKey(
                provider = provider,
                apiKey = key,
                priority = stored.size,
                enabled = true,
                username = validation.username,
            ),
        )
        deviceDebridManager = null
        // Unlike the device sign-ins, a key typed here goes to the account only when the account is
        // where this viewer said their keys should live.
        if (debridCloudSyncEnabled()) {
            runCatching {
                api.post<JsonObject>("/debrid/accounts", mapOf("provider" to provider, "apiKey" to key))
            }.onFailure { TvDebugLogger.w("Debrid", "$provider key not synced to the account", it) }
        }
        refreshBootstrap()
        return validation.username?.takeIf { it.isNotBlank() }
            ?: SUPPORTED_DEBRID_PROVIDERS.firstOrNull { it.first == provider }?.second
            ?: provider
    }

    /**
     * Begins Premiumize's device sign-in and returns the code to put on screen.
     *
     * The flow Premiumize recommends for televisions: nobody types a forty-character API key on a
     * remote control one letter at a time. The viewer reads a short code here and enters it on a
     * phone, and this device is handed a token.
     */
    suspend fun startPremiumizeSignIn(): PremiumizeDeviceAuth.Started? = runCatching {
        PremiumizeDeviceAuth.start(BuildConfig.PREMIUMIZE_CLIENT_ID)
    }.onFailure { TvDebugLogger.w("Debrid", "premiumize sign-in could not start", it) }.getOrNull()

    /**
     * Waits for the viewer to approve the code, then connects the account.
     *
     * The token is stored on this device so playback can use it straight away. It is posted to the
     * account only when this television is configured to sync premium-service keys; device-only
     * mode must never upload a credential and rely on another device to remove it afterwards.
     *
     * Returns the display name of the connected account, or null if the viewer never approved it.
     */
    suspend fun completePremiumizeSignIn(
        started: PremiumizeDeviceAuth.Started,
        onWaiting: suspend (secondsLeft: Int) -> Unit = {},
    ): String? {
        val context = appContext ?: return null
        val clientId = BuildConfig.PREMIUMIZE_CLIENT_ID
        val deadline = System.currentTimeMillis() + started.expiresInSeconds * 1000L
        var intervalMs = started.intervalSeconds * 1000L

        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            when (val poll = PremiumizeDeviceAuth.poll(clientId, started.deviceCode)) {
                is PremiumizeDeviceAuth.Poll.Authorized -> {
                    val token = poll.accessToken
                    val validation = runCatching { PremiumizeClient(token).validate() }.getOrNull()
                    val stored = DebridKeyStore.load(context).filterNot { it.provider == "premiumize" }
                    DebridKeyStore.save(
                        context,
                        stored + DebridKeyStore.StoredKey(
                            provider = "premiumize",
                            apiKey = token,
                            priority = stored.size,
                            enabled = true,
                            username = validation?.username,
                        ),
                    )
                    deviceDebridManager = null
                    if (debridCloudSyncEnabled()) {
                        runCatching {
                            api.post<JsonObject>("/debrid/accounts", mapOf("provider" to "premiumize", "apiKey" to token))
                        }.onFailure { TvDebugLogger.w("Debrid", "premiumize token not synced to the account", it) }
                    }
                    refreshBootstrap()
                    return validation?.username ?: "Premiumize"
                }
                // Asked to back off: honour it, or the next answer is another slow_down.
                PremiumizeDeviceAuth.Poll.SlowDown -> intervalMs = (intervalMs + 5_000L).coerceAtMost(60_000L)
                PremiumizeDeviceAuth.Poll.Pending -> onWaiting(((deadline - System.currentTimeMillis()) / 1000L).toInt())
                is PremiumizeDeviceAuth.Poll.Failed -> {
                    TvDebugLogger.w("Debrid", "premiumize sign-in failed: ${poll.message}")
                    return null
                }
            }
        }
        return null
    }

    private fun debridPreferences() =
        appContext?.getSharedPreferences("streamdek_tv_debrid", android.content.Context.MODE_PRIVATE)

    /**
     * Whether this account's premium keys are kept in StreamDek's database as well as on this
     * television. On by default, because that is where an existing account's keys already are.
     */
    fun debridCloudSyncEnabled(): Boolean = debridPreferences()?.getBoolean("cloud_sync", true) ?: true

    /**
     * Moves the keys between the account and this television alone.
     *
     * Turning it off never deletes the stored copy before this device has one of its own: until
     * then the account holds the only copy, and removing it first would lose the credential with
     * nothing to restore it from. Returns false when that check fails, so the caller can leave the
     * switch where it was and say so.
     */
    suspend fun setDebridCloudSync(enabled: Boolean): Boolean {
        val context = appContext ?: return false
        if (enabled) {
            DebridKeyStore.load(context).forEach { key ->
                runCatching {
                    api.post<JsonObject>(
                        "/debrid/accounts",
                        mapOf("provider" to key.provider, "apiKey" to key.apiKey),
                    )
                }
            }
            debridPreferences()?.edit()?.putBoolean("cloud_sync", true)?.apply()
            refreshBootstrap()
            return true
        }

        if (DebridKeyStore.load(context).isEmpty()) syncDebridKeys()
        val local = DebridKeyStore.load(context)
        val onAccount = bootstrapState.value?.integrations?.debrid?.accounts.orEmpty()
        if (local.isEmpty() && onAccount.isNotEmpty()) return false

        local.forEach { key ->
            runCatching { api.delete<JsonObject>("/debrid/accounts/${URLEncoder.encode(key.provider, "UTF-8")}") }
                .onFailure { TvDebugLogger.w("Debrid", "could not remove ${key.provider} from the account", it) }
        }
        debridPreferences()?.edit()?.putBoolean("cloud_sync", false)?.apply()
        refreshBootstrap()
        return true
    }

    /** Begins Real-Debrid's device sign-in and returns the code to put on screen. */
    suspend fun startRealDebridSignIn(): RealDebridDeviceAuth.Started? = runCatching {
        RealDebridDeviceAuth.start()
    }.onFailure { TvDebugLogger.w("Debrid", "real-debrid sign-in could not start", it) }.getOrNull()

    /**
     * Waits for the code to be approved, then connects the account.
     *
     * The renewal material is kept on the device alongside the token and never sent anywhere: the
     * account record holds one key, and a Real-Debrid token without the credentials that renew it
     * stops working inside the hour.
     */
    suspend fun completeRealDebridSignIn(
        started: RealDebridDeviceAuth.Started,
        onWaiting: suspend (secondsLeft: Int) -> Unit = {},
    ): String? {
        val context = appContext ?: return null
        val deadline = System.currentTimeMillis() + started.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(started.intervalSeconds * 1000L)
            when (val poll = RealDebridDeviceAuth.poll(started.deviceCode)) {
                is RealDebridDeviceAuth.Poll.Authorized -> {
                    val credentials = poll.credentials
                    val username = runCatching { RealDebridClient(credentials.accessToken).validate() }
                        .getOrNull()?.username
                    val stored = DebridKeyStore.load(context).filterNot { it.provider == "real-debrid" }
                    DebridKeyStore.save(
                        context,
                        stored + DebridKeyStore.StoredKey(
                            provider = "real-debrid",
                            apiKey = credentials.accessToken,
                            priority = stored.size,
                            enabled = true,
                            username = username,
                            refreshToken = credentials.refreshToken,
                            oauthClientId = credentials.clientId,
                            oauthClientSecret = credentials.clientSecret,
                        ),
                    )
                    deviceDebridManager = null
                    runCatching {
                        api.post<JsonObject>("/debrid/accounts", mapOf("provider" to "real-debrid", "apiKey" to credentials.accessToken))
                    }.onFailure { TvDebugLogger.w("Debrid", "real-debrid token not synced to the account", it) }
                    refreshBootstrap()
                    return username ?: "Real-Debrid"
                }
                RealDebridDeviceAuth.Poll.Pending -> onWaiting(((deadline - System.currentTimeMillis()) / 1000L).toInt())
                is RealDebridDeviceAuth.Poll.Failed -> {
                    TvDebugLogger.w("Debrid", "real-debrid sign-in failed: ${poll.message}")
                    return null
                }
            }
        }
        return null
    }

    /** Drops the stored keys — they belong to the account that signed out, not to the television. */
    fun clearDebridKeys() {
        appContext?.let(DebridKeyStore::clear)
        deviceDebridManager = null
    }

    /**
     * Marks which sources a premium service already holds, so the list can say so.
     *
     * Previously this decoration only ever arrived pre-applied on streams the backend had fetched.
     * An account that queries add-ons from the device — now the default — got streams with nothing
     * on them, so nothing was ever marked as instantly playable. Asked from here, the answer is
     * the same one playback will act on.
     */
    suspend fun markCachedStreams(streams: List<AddonStream>): List<AddonStream> {
        // The bootstrap is the authoritative enabled-state list. Device keys can briefly outlive
        // a settings change, so never query one when the account says every service is disabled.
        bootstrapState.value?.integrations?.debrid?.accounts?.let { accounts ->
            if (accounts.none { it.enabled }) return streams
        }
        val undecorated = streams.filter { it.cachedBy.isEmpty() }
        val hashes = undecorated.mapNotNull { effectiveInfoHash(it)?.lowercase(Locale.US) }.distinct()
        if (hashes.isEmpty()) return streams
        // Sent for Deepbrid's benefit: it publishes no info-hash anywhere in its API, so a hash on
        // its own is a question it cannot answer and it has to report everything as uncached.
        val names = undecorated.mapNotNull { stream ->
            val hash = effectiveInfoHash(stream)?.lowercase(Locale.US) ?: return@mapNotNull null
            val name = effectiveFilename(stream) ?: stream.title ?: return@mapNotNull null
            hash to name
        }.toMap()
        val cached = runCatching {
            deviceDebrid()?.checkCacheAll(hashes, names)
                ?: currentSession()?.takeIf { usesServerSideStreams() }?.let {
                    api.post<DebridCacheCheckResponse>(
                        "/debrid/cache-check",
                        buildMap<String, Any> {
                            put("infoHashes", hashes)
                            if (names.isNotEmpty()) put("names", names)
                        },
                    )?.cachedBy
                }.orEmpty()
        }
            .onFailure { TvDebugLogger.w("Debrid", "cache check failed", it) }
            .getOrDefault(emptyMap())
        if (cached.isEmpty()) return streams
        return applyCachedProviders(streams, cached.mapKeys { it.key.lowercase(Locale.US) })
    }

    /**
     * A usenet result: an NZB pointer plus the news servers to fetch it from, with no direct url
     * and no info hash. AIOStreams returns these alongside ordinary results, and for some titles
     * they are nearly all of them.
     */
    fun isUsenetStream(stream: AddonStream): Boolean = isUsenetAddonStream(stream)

    /**
     * Why the last usenet source failed to open, in the assembler's own words.
     *
     * A post packed into archives, a stream that named no news server, and a server that cannot be
     * reached are three different problems with three different answers — and the player was
     * flattening all of them into one sentence about the server possibly being unreachable, which
     * is actively wrong for the first two. Held here because [resolveStreamToUrl] answers null for
     * every kind of failure alike and the player has nothing else to read.
     */
    @Volatile
    var lastUsenetFailureMessage: String? = null
        private set

    /** Reject archive/download payloads that addons occasionally mislabel as playable videos. */
    fun isPlayableStreamOption(stream: AddonStream): Boolean {
        // Usenet results are playable: resolveStreamToUrl assembles them on the device and hands
        // the player a loopback URL. They carry neither a direct url nor an info hash, so they
        // have to be admitted here explicitly.
        if (isUsenetStream(stream)) return true
        if (!effectiveInfoHash(stream).isNullOrBlank()) return true
        val url = normalizedDirectUrl(stream) ?: return false
        val decodedUrl = runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val evidence = listOfNotNull(
            decodedUrl,
            stream.filename,
            stream.behaviorHints?.filename,
            stream.title,
            stream.name,
        ).joinToString(" ").lowercase(Locale.US)
        return !Regex("\\.(zip|rar|7z|tar|gz)(?:$|[?&#\\\" ]|\\.)").containsMatchIn(evidence)
    }

    /** Direct playback URL, excluding magnet links which must be resolved via debrid/torrent. */
    private fun normalizedDirectUrl(stream: AddonStream): String? {
        val url = stream.url?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) } ?: return null
        return url.takeUnless { it.startsWith("magnet:", ignoreCase = true) }
    }

    /** Info hash from the stream, or parsed out of a magnet url when absent. */
    private fun effectiveInfoHash(stream: AddonStream): String? {
        stream.infoHash?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val url = stream.url?.trim().orEmpty()
        if (!url.startsWith("magnet:?", ignoreCase = true)) return null
        return Regex("btih:([A-Fa-f0-9]{32,40})").find(url)?.groupValues?.getOrNull(1)
    }

    private fun effectiveFilename(stream: AddonStream): String? =
        stream.behaviorHints?.filename?.takeIf { it.isNotBlank() }
            ?: stream.filename?.takeIf { it.isNotBlank() }
            ?: stream.title?.takeIf { it.isNotBlank() }
            ?: stream.name?.takeIf { it.isNotBlank() }

    private fun effectiveBingeGroup(stream: AddonStream): String? =
        stream.bingeGroup?.takeIf { it.isNotBlank() }
            ?: stream.behaviorHints?.bingeGroup?.takeIf { it.isNotBlank() }

    /** Addon links served from short-lived direct routes must be re-fetched before playback. */
    private fun needsFreshPlaybackUrl(stream: AddonStream): Boolean {
        val url = stream.url ?: return false
        return runCatching {
            val uri = java.net.URI(url)
            uri.host.equals("pengu.uk", ignoreCase = true) && uri.path.orEmpty().startsWith("/direct/")
        }.getOrDefault(false)
    }

    private suspend fun refreshStreamForPlayback(
        stream: AddonStream,
        lookupType: String,
        videoId: String,
    ): AddonStream {
        if (!needsFreshPlaybackUrl(stream)) return stream
        val addon = runCatching { fetchAddonManifests() }.getOrDefault(emptyList())
            .firstOrNull { it.id == stream.addonId && it.enabled }
            ?: return stream
        val fresh = fetchStreamsFromSingleAddon(
            addon = addon,
            lookupType = lookupType,
            videoId = videoId,
            baseId = videoId.substringBefore(":"),
            isLive = lookupType in LIVE_ADDON_CATALOG_TYPES,
            forceRefresh = true,
        )
        if (fresh.isEmpty()) return stream
        val bingeGroup = effectiveBingeGroup(stream)
        val filename = effectiveFilename(stream)
        return fresh.firstOrNull { candidate ->
            !bingeGroup.isNullOrBlank() && effectiveBingeGroup(candidate) == bingeGroup
        } ?: fresh.firstOrNull { candidate ->
            !filename.isNullOrBlank() && effectiveFilename(candidate) == filename && candidate.name == stream.name
        } ?: stream
    }

    private fun maxFileSizeBytes(): Long? {
        val gb = bootstrapState.value?.preferences?.playback?.maxFileSizeGB?.trim()?.toDoubleOrNull() ?: return null
        if (gb <= 0.0) return null
        return (gb * 1024.0 * 1024.0 * 1024.0).toLong()
    }

    private fun rankStreams(
        streams: List<AddonStream>,
        preferredStreamKey: String?,
        preferredAddonName: String? = null,
        preferredQualityGroup: String? = null,
    ): List<AddonStream> {
        val preferredQuality = bootstrapState.value?.preferences?.playback?.preferredQuality ?: "best"
        val normalizedPreferredAddon = preferredAddonName?.trim()?.lowercase(Locale.US)
        val normalizedPreferredQuality = preferredQualityGroup?.trim()?.lowercase(Locale.US)
        val preferredAudioLanguage = preferredAudioLanguageForAutoSelection()
        val favouriteAddonIds = bootstrapState.value?.integrations?.addons?.items.orEmpty()
            .filter { it.favourite }.mapTo(hashSetOf()) { it.id }
        val pluginState = bootstrapState.value?.profilePlugins
        val favouritePluginRepos = pluginState?.repos.orEmpty().filter { it.favourite }.mapTo(hashSetOf()) { it.url }
        val favouritePluginProviderIds = pluginState?.providers.orEmpty()
            .filter { it.repoUrl in favouritePluginRepos }.mapTo(hashSetOf()) { it.id }
        // Every stream list shown is ordered here first, whatever add-on or plugin produced it,
        // so this is the one place the block cannot be routed around by a new caller.
        return streams.filterNot { stream ->
            AdultContentFilter.isBlocked(
                stream.name,
                stream.title,
                stream.addonName,
                // A stream description is usually the release name rather than a synopsis, and
                // that is exactly where the marker tends to sit.
                stream.description,
            )
        }.sortedWith(
            compareByDescending<AddonStream> {
                it.addonId in favouriteAddonIds || it.addonId.removePrefix("plugin:") in favouritePluginProviderIds
            }
                .thenByDescending { it.cachedBy.isNotEmpty() }
                .thenByDescending { preferredQualityScore(inferredStreamQuality(it), preferredQuality) }
                .thenByDescending { if (preferredStreamKey != null && streamSelectionKey(it) == preferredStreamKey) 10 else 0 }
                .thenByDescending {
                    if (!normalizedPreferredAddon.isNullOrBlank() && it.addonName.trim().lowercase(Locale.US) == normalizedPreferredAddon) 6 else 0
                }
                .thenByDescending {
                    if (!normalizedPreferredQuality.isNullOrBlank() && inferredStreamQuality(it)?.trim()?.lowercase(Locale.US) == normalizedPreferredQuality) 4 else 0
                }
                .thenByDescending { if (!normalizedDirectUrl(it).isNullOrBlank()) 3 else 0 }
                .thenByDescending { if (!effectiveInfoHash(it).isNullOrBlank()) 1 else 0 }
                // Language is a preference, never a visibility filter. A provider's full response
                // remains in the picker while matching rows sort nearer the top.
                .thenByDescending {
                    if (preferredAudioLanguage != null && streamMatchesPreferredAudioLanguage(it, preferredAudioLanguage)) 1 else 0
                }
                .thenByDescending { parseQualityScore(inferredStreamQuality(it)) }
        )
    }

    private fun preferredAudioLanguageForAutoSelection(): String? {
        val activeProfile = activeStreamProfile(bootstrapState.value)
        val profileLanguage = activeProfile?.audioLanguage?.trim()?.takeIf { it.isNotBlank() }
        val playbackLanguage = bootstrapState.value?.preferences?.playback?.defaultAudioLanguage?.trim()?.takeIf { it.isNotBlank() }
        val preferredLanguage = profileLanguage ?: playbackLanguage
        return preferredLanguage?.takeUnless { it.equals("auto", ignoreCase = true) }
    }

    private fun streamMatchesPreferredAudioLanguage(stream: AddonStream, preferredLanguage: String): Boolean {
        val aliases = audioLanguageAliases(preferredLanguage)
        val descriptors = listOfNotNull(
            stream.behaviorHints?.filename,
            stream.title,
            stream.name,
            stream.quality,
        )
            .joinToString(" ")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), " ")
        if (descriptors.isBlank()) return false
        return aliases.any { alias ->
            Regex("(^| )${Regex.escape(alias)}( |$)").containsMatchIn(descriptors)
        }
    }

    /**
     * Every spelling of [preferredLanguage] worth looking for in a release name.
     *
     * Was a hand-written table of fourteen languages, which is how a viewer who chose Polish or
     * Thai got no language matching at all while a viewer who chose French got three spellings.
     * [Languages] derives the same thing from the JVM's ISO tables for every language there is.
     */
    private fun audioLanguageAliases(preferredLanguage: String): Set<String> {
        if (preferredLanguage.trim().equals("auto", ignoreCase = true)) return emptySet()
        val tags = Languages.tags(preferredLanguage)
        if (tags.isEmpty()) return setOf(preferredLanguage.trim().lowercase(Locale.US))
        // The written-out name too: release names say "French" far more often than "fra".
        return (tags + Languages.label(preferredLanguage).lowercase(Locale.US))
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun guessContentType(url: String): String {
        val clean = url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".m3u8") -> "hls"
            clean.endsWith(".mpd") -> "dash"
            else -> "progressive"
        }
    }

    private fun describeStream(stream: AddonStream): String {
        return addonStreamDisplayLabel(stream)
    }

    private fun buildMagnetLink(infoHash: String, filename: String?): String {
        return "magnet:?xt=urn:btih:$infoHash" +
            if (filename.isNullOrBlank()) "" else "&dn=${URLEncoder.encode(filename, "UTF-8")}"
    }

    private fun buildStreamVideoId(baseId: String, episode: EpisodeContext?): String {
        return if (episode == null) baseId else "$baseId:${episode.seasonNumber}:${episode.episodeNumber}"
    }

    private fun buildEpisodeKey(episode: EpisodeContext?): String? {
        return episode?.let { "s${it.seasonNumber.toString().padStart(2, '0')}e${it.episodeNumber.toString().padStart(2, '0')}" }
    }

    private fun playbackCacheKey(
        mediaType: String,
        mediaId: String,
        imdbId: String?,
        episode: EpisodeContext?,
        preferredStreamKey: String?,
        preferredAddonName: String? = null,
        preferredQualityGroup: String? = null,
        streamType: String? = null,
    ): String {
        return listOf(
            mediaType,
            mediaId,
            imdbId.orEmpty(),
            buildEpisodeKey(episode).orEmpty(),
            preferredStreamKey.orEmpty(),
            preferredAddonName.orEmpty(),
            preferredQualityGroup.orEmpty(),
            streamType.orEmpty(),
        ).joinToString(":")
    }

    private fun invalidatePlaybackDerivedCaches() {
        libraryCache.clear()
        homeCache.clear()
        watchedHistoryCache.clear()
        libraryRevisionState.value = libraryRevisionState.value + 1L
    }

    private fun buildSessionProfileCacheKey(): String {
        val userId = currentSession()?.user?.uid ?: "guest"
        val profileId = sessionStore.activeProfileId()?.takeIf { it.isNotBlank() } ?: "default"
        return "$userId:$profileId"
    }

    private fun watchedHistoryKey(mediaType: String, mediaId: String, episode: EpisodeContext?): String {
        return if (mediaType == "tv" && episode != null) {
            "tv:$mediaId:s${episode.seasonNumber}:e${episode.episodeNumber}"
        } else {
            "movie:$mediaId"
        }
    }

    private fun historyItemKey(item: TraktHistoryItem): String? {
        return when (item.type?.trim()?.lowercase(Locale.US)) {
            "movie" -> item.movie?.ids?.tmdb?.let { "movie:$it" }
            "episode" -> {
                val showId = item.show?.ids?.tmdb ?: return null
                val season = item.episode?.season ?: return null
                val episode = item.episode?.number ?: return null
                "tv:$showId:s$season:e$episode"
            }
            else -> null
        }
    }

    private fun parseIntroDbSegments(payload: Any?): List<PlaybackSegment> {
        val rawSegments = extractRawSegments(payload)
        val normalized = rawSegments.mapNotNull(::normalizeSegment)
            .sortedWith(compareBy<PlaybackSegment> { it.startSec }.thenBy { it.endSec }.thenBy { it.segmentType })
        val hasIntro = normalized.any { it.segmentType == "intro" }
        return if (hasIntro) normalized else normalized
    }

    private fun extractRawSegments(payload: Any?): List<Any?> {
        return when (payload) {
            is List<*> -> payload
            is Map<*, *> -> {
                when {
                    payload["segments"] is List<*> -> payload["segments"] as List<*>
                    payload["data"] is List<*> -> payload["data"] as List<*>
                    else -> listOfNotNull("intro", "recap", "outro", "credits")
                        .mapNotNull { key ->
                            val value = payload[key]
                            if (value is Map<*, *>) {
                                linkedMapOf<String, Any?>("segment_type" to key).apply {
                                    putAll(value.mapKeys { it.key.toString() })
                                }
                            } else {
                                null
                            }
                        }
                }
            }
            else -> emptyList()
        }
    }

    private fun normalizeSegment(raw: Any?): PlaybackSegment? {
        val map = raw as? Map<*, *> ?: return null
        val segmentType = normalizeSegmentType(
            map["segment_type"] ?: map["type"] ?: map["kind"]
        ) ?: return null
        val startSec = parseClockOrSeconds(
            map["start_sec"] ?: map["start"] ?: map["startSeconds"] ?: map["start_seconds"]
        ) ?: return null
        val endSec = parseClockOrSeconds(
            map["end_sec"] ?: map["end"] ?: map["endSeconds"] ?: map["end_seconds"]
        ) ?: return null
        if (endSec <= startSec) return null
        return PlaybackSegment(segmentType = segmentType, startSec = startSec, endSec = endSec)
    }

    private fun parseLegacyIntroSegment(payload: Any?): PlaybackSegment? {
        val map = payload as? Map<*, *> ?: return null
        val startSec = parseClockOrSeconds(map["start_sec"] ?: map["start"] ?: map["intro_start"]) ?: return null
        val endSec = parseClockOrSeconds(map["end_sec"] ?: map["end"] ?: map["intro_end"]) ?: return null
        if (endSec <= startSec) return null
        return PlaybackSegment(segmentType = "intro", startSec = startSec, endSec = endSec)
    }

    private suspend fun fetchLegacyIntroPayload(imdbId: String, season: Int, episode: Int): Any? {
        val params = "imdb_id=${URLEncoder.encode(imdbId, "UTF-8")}&season=$season&episode=$episode&legacy=1"
        return api.get<Any>("/services/timings/introdb?$params")
    }

    private fun normalizeSegmentType(value: Any?): String? {
        return when (value?.toString()?.trim()?.lowercase(Locale.US)) {
            "intro" -> "intro"
            "recap" -> "recap"
            "outro", "credits", "credit" -> "outro"
            else -> null
        }
    }

    private fun parseClockOrSeconds(value: Any?): Double? {
        return when (value) {
            is Number -> value.toDouble().takeIf { it.isFinite() && it >= 0.0 }
            is String -> {
                val trimmed = value.trim()
                trimmed.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: run {
                    val parts = trimmed.split(":").mapNotNull { it.trim().toDoubleOrNull() }
                    if (parts.size !in 2..3) {
                        null
                    } else if (parts.any { !it.isFinite() || it < 0.0 }) {
                        null
                    } else if (parts.size == 2) {
                        (parts[0] * 60.0) + parts[1]
                    } else {
                        (parts[0] * 3600.0) + (parts[1] * 60.0) + parts[2]
                    }
                }
            }
            else -> null
        }
    }

    private fun buildSyncMetadata(detail: MediaDetail?, episode: EpisodeContext?): Map<String, Any?> {
        return if (episode != null) {
            mapOf(
                "title" to detail?.title,
                "showTitle" to detail?.title,
                "posterUrl" to detail?.poster,
                "backdropUrl" to detail?.backdrop,
                "description" to detail?.description,
                "year" to detail?.year,
                "tmdbId" to detail?.tmdbId,
                "seasonNumber" to episode.seasonNumber,
                "episodeNumber" to episode.episodeNumber,
                "episodeTitle" to episode.title,
            )
        } else {
            mapOf(
                "title" to detail?.title,
                "posterUrl" to detail?.poster,
                "backdropUrl" to detail?.backdrop,
                "description" to detail?.description,
                "year" to detail?.year,
                "tmdbId" to detail?.tmdbId,
            )
        }
    }

    /**
     * Writes a settings change to both scopes: the account copy keeps devices that have no profile
     * selected in step, and the profile copy is what mobile and web read back for this viewer.
     * Sending only the account copy would leave the change invisible to them, and a later profile
     * write from another client would silently undo it.
     */
    private suspend fun patchPreferences(payload: Map<String, Any?>): Boolean {
        val response = api.patch<JsonObject>(
            "/account/preferences",
            mapOf("preferences" to payload),
        ) ?: return false
        if (!response.has("preferences")) return false
        return writeProfilePreferences(payload)
    }

    private suspend fun writeProfilePreferences(payload: Map<String, Any?>): Boolean {
        val profileId = sessionStore.activeProfileId()?.takeIf { it.isNotBlank() } ?: return true
        if (currentSession() == null) return true
        val changed = runCatching { api.gson.toJsonTree(payload).asJsonObject }.getOrNull() ?: return false
        // The whole blob is resent, so it has to be current: another client may have changed a
        // favourite channel since this one last read it, and settings writes are rare enough that
        // one extra read costs nothing.
        val current = api.get<ProfilePreferencesEnvelope>(
            "/profiles/${URLEncoder.encode(profileId, "UTF-8")}/preferences",
        )?.preferences ?: profilePreferencesState.value
        val next = PreferenceScopes.applyToProfileBlob(current, changed) ?: return true
        val response = api.put<ProfilePreferencesEnvelope>(
            "/profiles/${URLEncoder.encode(profileId, "UTF-8")}/preferences",
            mapOf("preferences" to next),
        )
        if (response == null) {
            TvDebugLogger.w("Preferences", "profile preference sync failed; account copy was still saved")
            return false
        }
        profilePreferencesState.value = response.preferences ?: next
        return true
    }

    /**
     * Resume points held by the tracking service, which complement the ones this account recorded
     * itself. MDBList has no playback API, so a profile using it simply contributes nothing here
     * and falls back to Trakt if that is still connected.
     */
    private suspend fun fetchServicePlayback(): List<ContinueWatchingItem> {
        val session = currentSession() ?: return emptyList()
        for (service in syncServiceChain { it.playback }) {
            val results = runCatching {
                api.get<TraktPlaybackResponse>("/$service/sync/playback", session)?.results
            }.onFailure {
                TvDebugLogger.e("Library", "continue-watching read failed on $service", it)
            }.getOrNull()
            if (results != null) return results
        }
        return emptyList()
    }

    private fun mergeContinueWatching(
        primary: List<ContinueWatchingItem>,
        secondary: List<ContinueWatchingItem>,
        progressRecords: List<PlaybackProgressRecord> = emptyList(),
    ): List<ContinueWatchingItem> {
        val dismissals = progressRecords.filter { it.status.equals("dismissed", ignoreCase = true) }
        // Identity, not string equality.
        //
        // This compared `record.entityId` with `item.id` directly. Those are the same title spelled
        // by two different sources -- SyncDek stores whatever the removing device held, a provider
        // returns its own -- so a removal recorded as `tt1160419` did not suppress the row that came
        // back as `438631`, and the title reappeared on the next refresh. The server now applies the
        // same rule before it answers, and this keeps the local view agreeing with it in the moment
        // between an optimistic removal and the next fetch.
        fun isDismissed(item: ContinueWatchingItem): Boolean {
            val itemIdentity = mediaIdentityOf(item.type, item.id, item.tmdbId)
            val itemEpisode = item.exactEpisode()
            return dismissals.any { record ->
                sameMediaIdentity(
                    mediaIdentityOf(record.entityType, record.entityId, record.tmdbId, record.imdbId),
                    itemIdentity,
                ) && removalCoversEpisode(
                    record.seasonNumber,
                    record.episodeNumber,
                    itemEpisode?.seasonNumber,
                    itemEpisode?.episodeNumber,
                )
            }
        }
        val eligibleSecondary = secondary.filterNot(::isDismissed)
        if (eligibleSecondary.isEmpty()) return primary.filterNot(::isDismissed)
        val merged = linkedMapOf<String, ContinueWatchingItem>()
        (primary + eligibleSecondary).filterNot(::isDismissed).forEach { item ->
            val key = listOf(
                item.type,
                item.id,
                item.episodeKey.orEmpty(),
            ).joinToString(":")
            val existing = merged[key]
            merged[key] = when {
                existing == null -> item
                (existing.progress ?: 0.0) <= 0.0 && (item.progress ?: 0.0) > 0.0 -> item
                (existing.positionSec ?: existing.resumeAt ?: 0.0) <= 0.0 && (item.positionSec ?: item.resumeAt ?: 0.0) > 0.0 -> item
                existing.poster.isNullOrBlank() && !item.poster.isNullOrBlank() -> item
                else -> existing
            }
        }
        return merged.values.toList()
    }

    private suspend inline fun <reified T> safeResults(path: String): List<MediaItem> {
        return runCatching { api.get<T>(path) }
            .getOrNull()
            .extractResults()
    }

    private fun Any?.extractResults(): List<MediaItem> = when (this) {
        is RailResponse -> results
        is NetworkResponse -> results.map { network ->
            MediaItem(
                id = network.id.toString(),
                tmdbId = network.id,
                title = network.name,
                type = "network",
                titleLogo = network.logo,
                poster = network.logo,
            )
        }
        else -> emptyList()
    }

    private fun persistSession(response: AuthResponse): AuthSession {
        val token = response.token ?: error("Missing auth token")
        val session = AuthSession(
            token = token,
            user = normalizeUser(response.user, token),
            // The refresh token has been in every sign-in and pairing response since the backend
            // shipped it; this is the television learning to keep it.
            refreshToken = response.refreshToken ?: response.refresh_token,
        )
        sessionStore.saveSession(session)
        return session
    }

    private fun normalizeUser(payload: AuthUserPayload?, token: String): SessionUser {
        return SessionUser(
            uid = payload?.uid ?: payload?.id ?: error("Missing user id"),
            email = payload?.email,
            displayName = payload?.displayName,
            subscriptionStatus = payload?.subscriptionStatus ?: "free",
            accessToken = token,
        )
    }

    private fun <K, V> lruCache(maxEntries: Int): MutableMap<K, V> {
        return object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
                return size > maxEntries
            }
        }
    }
}

/**
 * Reads the catalog registry out of a `/tmdb/catalogs` payload, skipping anything unusable.
 *
 * A row with no id or no title cannot be laid out, ordered or persisted against, so it is dropped
 * rather than rendered as a nameless carousel.
 */
internal fun parseCatalogDefinitions(response: CatalogManifestResponse?): List<CatalogDefinition> =
    response?.catalogs.orEmpty().mapNotNull { entry ->
        val id = entry.id?.trim().orEmpty()
        val title = entry.title?.trim().orEmpty()
        if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
        CatalogDefinition(
            id = id,
            title = title,
            mediaType = entry.media_type?.takeIf { it.isNotBlank() } ?: "movie",
            group = entry.group?.takeIf { it.isNotBlank() } ?: "other",
            previewLimit = entry.preview_limit.takeIf { it > 0 } ?: 20,
            maxItems = entry.max_items?.takeIf { it > 0 },
            paginated = entry.paginated,
        )
    }

/**
 * The registry rows this profile wants, in the order it wants them.
 *
 * [layout] is written by mobile and the web portal and synced through the profile, so a row
 * switched off or moved there is switched off or moved here. Rows the layout has never heard of —
 * a default added by a later backend deploy — keep their registry position rather than being
 * appended, because appending would bury a whole deploy's worth of rows beneath rows the viewer
 * never deliberately ranked. A row the registry has dropped disappears.
 */
internal fun orderCatalogRows(
    definitions: List<CatalogDefinition>,
    layout: List<HomeCatalogRowPreference>,
): List<CatalogDefinition> {
    val named = layout.filter { it.id.isNotBlank() }
    if (named.isEmpty()) return definitions
    val known = definitions.associateBy { it.id }
    val merged = named.sortedBy { it.position }
        .mapNotNull { row -> known[row.id]?.takeIf { row.enabled } }
        .toMutableList()
    val seen = named.mapTo(mutableSetOf()) { it.id }
    definitions.forEachIndexed { index, definition ->
        if (definition.id in seen) return@forEachIndexed
        val after = definitions.take(index).lastOrNull { earlier -> merged.any { it.id == earlier.id } }
        val at = if (after == null) 0 else merged.indexOfFirst { it.id == after.id } + 1
        merged.add(at, definition)
    }
    return merged
}

/**
 * A catalog section item as a card.
 *
 * The networks row names its fields differently from a title row, and it is the section's own
 * `media_type` that says which to read — an item's own `type` is absent on some rows.
 */
/**
 * Whether a catalogue card is pornography.
 *
 * The description is deliberately not searched: a synopsis mentioning pornography is usually a
 * documentary about it, and hiding those is how a filter earns a reputation for being wrong.
 */
internal fun MediaItem.isAdultCard(): Boolean =
    AdultContentFilter.isBlockedItem(title = title) || AdultContentFilter.isBlocked(sourceCatalogName)

/** Drops adult cards from a row. Applied where rows are built rather than where they render. */
internal fun List<MediaItem>.withoutAdult(): List<MediaItem> = filterNot { it.isAdultCard() }

internal fun CatalogSectionItem.toMediaItem(sectionMediaType: String?): MediaItem {
    val kind = type?.takeIf { it.isNotBlank() } ?: sectionMediaType?.takeIf { it.isNotBlank() } ?: "movie"
    if (kind == "network") {
        return MediaItem(
            id = id.orEmpty(),
            tmdbId = id?.toIntOrNull() ?: tmdbId,
            title = name ?: title.orEmpty(),
            type = "network",
            titleLogo = logo,
            poster = logo,
        )
    }
    return MediaItem(
        id = id.orEmpty(),
        tmdbId = tmdbId,
        title = title ?: name.orEmpty(),
        type = kind,
        poster = poster,
        backdrop = backdrop,
        description = description,
        rating = rating,
        year = year,
    )
}
