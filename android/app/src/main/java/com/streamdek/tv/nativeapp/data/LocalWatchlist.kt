package com.streamdek.tv.nativeapp.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * One title this television put on the watchlist, and when.
 *
 * The time is kept beside the item rather than on it: [MediaItem] travels to and from the backend,
 * and when a title was saved on this device is not something a catalogue or service supplies.
 */
internal data class LocalWatchlistEntry(val item: MediaItem, val addedAt: Long)

/** Applies one watchlist edit to the local copy. Adding a title already there keeps its original time. */
internal fun mutateLocalWatchlist(
    current: List<LocalWatchlistEntry>,
    item: MediaItem,
    remove: Boolean,
    now: Long,
): List<LocalWatchlistEntry> = when {
    remove -> current.filterNot { sameWatchlistTitle(it.item, item) }
    current.any { sameWatchlistTitle(it.item, item) } -> current
    else -> listOf(LocalWatchlistEntry(item, now)) + current
}

/**
 * The watchlist Library shows: the selected service's list with this television's own copy merged in.
 *
 * The same shape as StreamDek Mobile's merge. A title saved here stays visible even while the service
 * has not caught up with it, or when the service could not be read at all. The service's copy of a
 * title wins where both have it, because it carries the enriched artwork; titles only saved here lead,
 * newest first, as the most recent additions.
 */
internal fun mergeWatchlistWithLocal(service: List<MediaItem>, local: List<LocalWatchlistEntry>): List<MediaItem> {
    val localOnly = local
        .filterNot { entry -> service.any { sameWatchlistTitle(it, entry.item) } }
        .sortedByDescending { it.addedAt }
        .map { it.item }
    return (localOnly + service).fold(mutableListOf()) { merged, item ->
        if (merged.none { sameWatchlistTitle(it, item) }) merged += item
        merged
    }
}

/** This television's watchlist copy, one list per signed-in user and profile. */
internal class LocalWatchlistStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("streamdek_tv_local_watchlist", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<LocalWatchlistEntry>>() {}.type

    fun load(owner: String): List<LocalWatchlistEntry> {
        val raw = prefs.getString(key(owner), null) ?: return emptyList()
        return runCatching { gson.fromJson<List<LocalWatchlistEntry>>(raw, listType) }
            .getOrNull().orEmpty()
            // Gson fills a field a stored entry lacks with null whatever Kotlin says, so an entry from a
            // damaged or older payload is dropped rather than allowed to crash the Library.
            .filter { entry -> runCatching { entry.item.id.isNotBlank() && entry.item.type.isNotBlank() }.getOrDefault(false) }
    }

    fun save(owner: String, entries: List<LocalWatchlistEntry>) {
        prefs.edit().putString(key(owner), gson.toJson(entries.take(MAX_ENTRIES))).apply()
    }

    private fun key(owner: String) = "watchlist:$owner"

    private companion object {
        const val MAX_ENTRIES = 500
    }
}
