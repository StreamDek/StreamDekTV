package com.streamdek.tv.nativeapp.data

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Searching the titles and channels that plugins list, for Search and for StreamDek Fuse alike.
 *
 * Plugins reach StreamDek as CloudStream providers - `.cs3` plugins directly, SkyStream sources
 * through [SkyStreamMainApi] - so one path covers both. Regular StreamDek plugins resolve streams
 * for titles found elsewhere and list nothing of their own, so they have nothing here to search.
 *
 * Every provider is asked through its own search first. Whether it has one is learnt rather than
 * assumed: `MainAPI.search` is abstract in all but name, and a provider that never implemented it
 * throws [NotImplementedError], which is remembered for the rest of the session. Such a provider -
 * typically a live-channel plugin whose "catalogue" is a handful of rows - is still searchable
 * against the rows StreamDek has already loaded from it for Home and the Fuse ([index]), which costs
 * no request at all. Nothing is crawled to build that index; it is only what was already fetched.
 *
 * A provider that is slow or fails is skipped on its own: [searchAll] reports each one as it
 * answers, so results appear while the slowest provider is still working, and one that times out
 * drops out of this query without taking the others with it.
 */
internal object PluginCatalogSearch {
    /** How long one provider may take to answer a query before it is left out of it. */
    const val PROVIDER_TIMEOUT_MS = 15_000L

    /** Providers asked at once. A TV box does not want a socket per plugin. */
    private const val CONCURRENCY = 4
    private const val CACHE_TTL_MS = 5 * 60_000L
    private const val CACHE_LIMIT = 160
    private const val INDEX_LIMIT_PER_PROVIDER = 2_000

    /** How a provider can be searched. */
    enum class Capability {
        /** Answers a query itself. */
        Native,

        /** Has no search of its own; matched against the rows already loaded from it. */
        CatalogueOnly,
    }

    /** One provider's answer to one query. */
    data class ProviderOutcome(
        val providerName: String,
        val items: List<MediaItem>,
        val capability: Capability,
        /** The provider errored or timed out. Its indexed rows, if any, are still in [items]. */
        val failed: Boolean = false,
    )

    private data class CachedOutcome(val at: Long, val outcome: ProviderOutcome)

    private val capabilities = ConcurrentHashMap<String, Capability>()
    private val index = ConcurrentHashMap<String, List<MediaItem>>()
    private val cache = ConcurrentHashMap<String, CachedOutcome>()

    /** What is known about [providerName]'s search, or null before it has been asked. */
    fun capability(providerName: String): Capability? = capabilities[providerName]

    /**
     * Keeps the titles a provider's row just returned, so a provider without search of its own can
     * still be searched. Called wherever rows are loaded anyway; never fetches anything itself.
     */
    fun index(providerName: String, items: List<MediaItem>) {
        if (items.isEmpty()) return
        val merged = (items + index[providerName].orEmpty()).distinctBy { it.id }.take(INDEX_LIMIT_PER_PROVIDER)
        index[providerName] = merged
    }

    /** Whether anything has been indexed for [providerName] yet. */
    fun hasIndex(providerName: String): Boolean = !index[providerName].isNullOrEmpty()

    /** Forgets cached answers, for a pull-to-refresh style retry. Capabilities and the index stay. */
    fun clearCache() = cache.clear()

    /**
     * One provider's matches for [query]: its own search when it has one, the rows indexed from it
     * either way. Cached for a few minutes, so typing back to an earlier query is instant.
     */
    suspend fun searchProvider(provider: MainAPI, query: String, forceRefresh: Boolean = false): ProviderOutcome {
        val needle = query.trim()
        val key = provider.name + "" + needle.lowercase(Locale.US)
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cache[key]?.takeIf { now - it.at < CACHE_TTL_MS }?.let { return it.outcome }
        }
        val indexed = matchIndexed(provider.name, needle)
        var failed = false
        val native = if (capabilities[provider.name] == Capability.CatalogueOnly) {
            emptyList()
        } else {
            try {
                withTimeout(PROVIDER_TIMEOUT_MS) { nativeSearch(provider, needle) }
                    .also { capabilities[provider.name] = Capability.Native }
            } catch (cancelled: CancellationException) {
                // A timeout is a cancellation too, but only the query's own cancellation propagates.
                if (cancelled is kotlinx.coroutines.TimeoutCancellationException) {
                    TvDebugLogger.w("PluginSearch", "${provider.name} timed out for '$needle'")
                    failed = true
                    emptyList()
                } else {
                    throw cancelled
                }
            } catch (_: NotImplementedError) {
                capabilities[provider.name] = Capability.CatalogueOnly
                TvDebugLogger.i("PluginSearch", "${provider.name} has no search; using its loaded rows")
                emptyList()
            } catch (failure: Throwable) {
                // Plugins throw linkage errors as well as exceptions.
                TvDebugLogger.w("PluginSearch", "${provider.name} search failed: ${failure.javaClass.simpleName}: ${failure.message}")
                failed = true
                emptyList()
            }
        }
        val outcome = ProviderOutcome(
            providerName = provider.name,
            items = (relevant(native, needle) + indexed).distinctBy { it.id },
            capability = capabilities[provider.name] ?: Capability.Native,
            failed = failed,
        )
        if (!failed) {
            if (cache.size >= CACHE_LIMIT) {
                cache.entries.sortedBy { it.value.at }.take(CACHE_LIMIT / 4).forEach { cache.remove(it.key) }
            }
            cache[key] = CachedOutcome(now, outcome)
        }
        return outcome
    }

    /**
     * Every provider's matches, reported provider by provider as they answer. Each emission is the
     * whole set of outcomes so far, so a collector can simply show the latest one.
     */
    fun searchAll(providers: List<MainAPI>, query: String, forceRefresh: Boolean = false): Flow<List<ProviderOutcome>> = channelFlow {
        val needle = query.trim()
        if (needle.length < 2 || providers.isEmpty()) {
            send(emptyList())
            return@channelFlow
        }
        val gate = Semaphore(CONCURRENCY)
        val arrived = Channel<ProviderOutcome>(Channel.UNLIMITED)
        val collected = mutableListOf<ProviderOutcome>()
        launch {
            supervisorScope {
                providers.distinctBy { it.name }.map { provider ->
                    async {
                        gate.withPermit { arrived.send(searchProvider(provider, needle, forceRefresh)) }
                    }
                }.awaitAll()
            }
            arrived.close()
        }
        for (outcome in arrived) {
            collected += outcome
            send(collected.toList())
        }
    }

    /**
     * The provider's own search. The paged overload first: it defaults to the single-argument one,
     * so it works for a provider that implemented either - whereas calling `search(query)` directly
     * throws for every provider that only implemented the paged form.
     */
    private suspend fun nativeSearch(provider: MainAPI, query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val paged: List<SearchResponse>? = try {
            provider.search(query, 1)?.items
        } catch (notImplemented: NotImplementedError) {
            null
        }
        val results = paged?.takeIf { it.isNotEmpty() } ?: provider.search(query).orEmpty()
        results.distinctBy { it.url }.map { result ->
            CloudStreamCatalog.toMediaItem(provider, result).withProviderLine(provider.name)
        }
    }

    private fun matchIndexed(providerName: String, needle: String): List<MediaItem> =
        index[providerName].orEmpty()
            .mapNotNull { item -> matchRank(item.title, needle)?.let { it to item } }
            .sortedBy { it.first }
            .map { (_, item) -> item.withProviderLine(providerName) }

    /**
     * Names the provider on the card - unless the card already does. A channel carries its provider
     * as [MediaItem.sourceAddonName], which the card prints, and a second line saying the same thing
     * read as "News / News".
     */
    private fun MediaItem.withProviderLine(providerName: String): MediaItem =
        if (cardSubtitle != null || sourceAddonName == providerName) this else copy(cardSubtitle = providerName)

    /**
     * What a provider returned, less anything plainly unrelated.
     *
     * A provider may ignore the query and answer with its default listing, which would bury the real
     * matches. When at least one result matches the query by title, only matches are kept; when none
     * does, the provider's answer is kept as it is, since it may be matching on titles it does not
     * show (a romanised name, an alternative title).
     */
    private fun relevant(items: List<MediaItem>, needle: String): List<MediaItem> {
        val ranked = items.mapNotNull { item -> matchRank(item.title, needle)?.let { it to item } }
        return if (ranked.isEmpty()) items else ranked.sortedBy { it.first }.map { it.second }
    }

    /** How well [title] answers [needle], lowest first, or null when it does not. */
    fun matchRank(title: String, needle: String): Int? {
        val haystack = fold(title)
        val query = fold(needle)
        if (query.isEmpty() || haystack.isEmpty()) return null
        return when {
            haystack == query -> 0
            haystack.startsWith(query) -> 1
            haystack.split(' ').any { it.startsWith(query) } -> 2
            haystack.contains(query) -> 3
            query.split(' ').filter { it.isNotEmpty() }.all { token -> haystack.contains(token) } -> 4
            // "spiderman" against "Spider-Man": compare with the separators gone too.
            haystack.replace(" ", "").contains(query.replace(" ", "")) -> 5
            else -> null
        }
    }

    /** Lower case, accents off, punctuation to spaces: "Amélie!" and "amelie" are the same query. */
    private fun fold(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.US)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
