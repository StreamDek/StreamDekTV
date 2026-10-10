package com.streamdek.tv.nativeapp.data

import com.streamdek.tv.nativeapp.mediaserver.MediaServerProvider
import com.streamdek.tv.nativeapp.mediaserver.MediaServerReference
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Plex and Jellyfin as sources for any title, not only for titles opened from their own pages -
 * the same rule the phone uses.
 *
 * A catalogue title is looked for on every connected server by name, and a server title counts as
 * the same one only when the two share a TMDB or IMDb id: a name finds candidates, it never
 * confirms one, so a remake or a same-named series is never offered as the title on screen. The
 * episode is then picked by season and episode number on the server's own copy of the series.
 */

/** The ids a title is known by. With neither, nothing is matched. */
internal data class TitleIds(val tmdbId: Int?, val imdbId: String?) {
    val isEmpty: Boolean get() = tmdbId == null && imdbId == null

    companion object {
        private val imdbPattern = Regex("tt\\d{5,12}", RegexOption.IGNORE_CASE)

        /** A catalogue title's ids: its TMDB number (bare or `tmdb:123`) and an IMDb id from wherever one is known. */
        fun ofCatalogue(id: String, tmdbId: Int?, imdbId: String?): TitleIds = TitleIds(
            tmdbId = tmdbId?.takeIf { it > 0 } ?: id.removePrefix("tmdb:").toIntOrNull()?.takeIf { it > 0 },
            imdbId = (imdbId ?: id).let { imdbPattern.find(it)?.value?.lowercase() },
        )
    }
}

/** Whether a server title is the catalogue title: same kind, and at least one id in common. */
internal fun isSameServerTitle(wantedType: String, wanted: TitleIds, candidateType: String, candidate: TitleIds): Boolean {
    if (wanted.isEmpty || candidate.isEmpty) return false
    if (MediaClassification.canonical(wantedType) != MediaClassification.canonical(candidateType)) return false
    if (wanted.tmdbId != null && wanted.tmdbId == candidate.tmdbId) return true
    return wanted.imdbId != null && candidate.imdbId != null && wanted.imdbId.equals(candidate.imdbId, ignoreCase = true)
}

/**
 * Finds a catalogue title's copies on the connected servers, remembered for a few minutes per title
 * so another episode, a reopened page or a refresh asks the servers nothing new. A server that is
 * away times out on its own and never holds up the others.
 */
internal object MediaServerSourceFinder {
    private const val TTL_MS = 10L * 60L * 1000L
    private const val SEARCH_LIMIT = 20
    private const val SEARCH_TIMEOUT_MS = 8_000L

    private class Entry(val refs: List<MediaServerReference>, val storedAt: Long)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun clear() = cache.clear()

    suspend fun find(provider: MediaServerProvider, type: String, title: String, ids: TitleIds): List<MediaServerReference> {
        if (ids.isEmpty || title.isBlank()) return emptyList()
        val key = "${provider.id}|${MediaClassification.canonical(type)}|${ids.tmdbId}|${ids.imdbId}"
        cache[key]?.takeIf { System.currentTimeMillis() - it.storedAt < TTL_MS }?.let { return it.refs }
        val found = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            runCatching { provider.search(title, SEARCH_LIMIT) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrDefault(emptyList())
                .filter { item -> isSameServerTitle(type, ids, item.type, TitleIds(item.tmdbId.takeIf { it > 0 }, item.imdbId?.lowercase())) }
                .mapNotNull { MediaServerReference.decode(it.id) }
                .distinct()
        } ?: return emptyList()
        if (cache.size > 400) cache.clear()
        cache[key] = Entry(found, System.currentTimeMillis())
        return found
    }

    /** Every provider at once. */
    suspend fun findAll(providers: List<MediaServerProvider>, type: String, title: String, ids: TitleIds): List<Pair<MediaServerProvider, MediaServerReference>> =
        supervisorScope {
            providers.map { provider -> async { find(provider, type, title, ids).map { provider to it } } }.awaitAll().flatten()
        }
}
