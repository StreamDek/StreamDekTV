package com.streamdek.tv.nativeapp.data

import java.util.Locale

/**
 * StreamDek Fuse: every live and on-demand source this profile has — add-ons, CloudStream rows,
 * playlists — in one place, instead of a Home row each.
 *
 * The phone has the same feature and the same rules: off by default and device-local, it replaces
 * the source rows on Home with a single card, and a row switched off in Home Rows is left out of it.
 */
internal const val FUSE_HOME_RAIL_ID = "streamdek-fuse"

/** The type of the one card on the Fuse Home row, which opens the page rather than a title. */
internal const val FUSE_PORTAL_ITEM_TYPE = "streamdek-fuse"

internal fun isFuseCatalogType(type: String): Boolean = type.lowercase(Locale.US) in setOf(
    "movie", "movies", "series", "tv", "live", "channel", "iptv", "sport", "sports", "events", "anime", "livestream",
)

/** Whether a Home row is one of the source rows the Fuse card stands in for. */
internal fun isFuseSourceHomeRail(id: String): Boolean =
    id.startsWith("addon:") && isFuseCatalogType(id.split(":").getOrNull(2).orEmpty())

/**
 * Home's rows with the Fuse applied: the source rows it stands in for gone, and its own row directly
 * under the personal rows. Switched off, Home is exactly as it was, and no Fuse row is shown.
 */
internal fun applyFuseToHomeRails(rails: List<HomeRail>, enabled: Boolean): List<HomeRail> {
    if (!enabled) return rails.filterNot { it.id == FUSE_HOME_RAIL_ID }
    val fuse = rails.firstOrNull { it.id == FUSE_HOME_RAIL_ID }
    val rest = rails.filterNot { it.id == FUSE_HOME_RAIL_ID || isFuseSourceHomeRail(it.id) }
    if (fuse == null) return rest
    // A few of the titles it stands in for, channels first, as the phone's card shows them.
    val preview = rails.filter { isFuseSourceHomeRail(it.id) }
        .sortedByDescending { it.isLive }
        .flatMap { it.items.take(3) }
        .filter { !(it.poster ?: it.backdrop).isNullOrBlank() }
        .distinctBy { it.poster ?: it.backdrop }
        .take(12)
    val position = rest.indexOfLast { it.id == "continue-watching" || it.id == "new-episodes" } + 1
    return rest.toMutableList().apply { add(position, fuse.copy(previewItems = preview)) }
}

/**
 * The StreamDek catalogue's New Movies and New Series rows, which lead Home ahead of the live rows.
 * Their artwork is what the hero shows on arrival; with the Fuse card or a live row first, the hero
 * opened on a channel logo or nothing at all, and Home looked empty until the viewer moved.
 */
internal val LEADING_CATALOG_ROW_IDS = listOf("new_movies", "new_series")

/**
 * Home's rows in the phone's order: Continue Watching, New Episodes, New Movies, New Series, then
 * what is on now - the Fuse card when it is on, live rows otherwise - then Streaming Networks, then
 * everything else in the order the layout gave it.
 *
 * Live rows used to sit wherever Home Rows put them, and Streaming Networks with them, so the two
 * apps on one account laid out the same rows differently. The personal, new and live rows are
 * placed here whatever the saved layout says, as the phone places them. A row switched off in the
 * layout is not here at all, so switching one off still works.
 */
internal fun arrangeHomeRails(rails: List<HomeRail>, networkRowIds: Set<String>): List<HomeRail> {
    val leadingIds = listOf("continue-watching", "new-episodes") + LEADING_CATALOG_ROW_IDS
    val leading = leadingIds.mapNotNull { id -> rails.firstOrNull { it.id == id } }
    val remaining = rails.filterNot { it.id in leadingIds }
    val fuse = remaining.filter { it.id == FUSE_HOME_RAIL_ID }
    val live = remaining.filter { it.isLive && it.id != FUSE_HOME_RAIL_ID }
    val networks = remaining.filter { it.id in networkRowIds && !it.isLive }
    val later = remaining.filterNot { it.id == FUSE_HOME_RAIL_ID || it.isLive || it.id in networkRowIds }
    return leading + fuse + live + networks + later
}

/**
 * Where the viewer was in the Fuse: the view and filters, the sections they opened and closed, how far
 * down the list they were and the card they were on. The Fuse is a destination in the navigation rail
 * when it is on, and a channel played from it is a round trip - so coming back lands on that channel
 * rather than on the search box at the top of a list the viewer had scrolled.
 */
internal data class FuseViewMemory(
    val mode: String = "all",
    val sourceKey: String? = null,
    val catalogKey: String? = null,
    val category: String? = null,
    val query: String = "",
    val groupOrder: List<String> = emptyList(),
    val openedGroups: Set<String> = emptySet(),
    val closedGroups: Set<String> = emptySet(),
    val focusedItemKey: String? = null,
    val firstVisibleIndex: Int = 0,
    val firstVisibleOffset: Int = 0,
) {
    /** The filters the saved sections belong to; sections saved under other filters are not restored. */
    val groupIdentity: String get() = listOf(mode, catalogKey, category, query.trim()).joinToString("\u001f")
}

/**
 * [content] with the Fuse card's artwork held steady while its source rows are still arriving.
 *
 * The card draws a few of the channels from the source rows it stands in for, and those rows land at
 * different moments - CloudStream's often before the add-ons'. Home refreshes every few seconds, and a
 * refresh that published before every source row was back drew the card from whichever had landed:
 * no artwork, or another source's channels, for a second, and then the usual ones again. Until the
 * sources are all in, the card keeps what it last showed ([stable]); once they are, what it shows is
 * returned as the next [stable].
 */
internal fun steadyFusePreview(content: HomeContent, sourcesResolved: Boolean, stable: List<MediaItem>?): Pair<HomeContent, List<MediaItem>?> {
    val fuse = content.rails.firstOrNull { it.id == FUSE_HOME_RAIL_ID } ?: return content to stable
    if (sourcesResolved) return content to fuse.previewItems
    val held = stable.orEmpty()
    if (held == fuse.previewItems) return content to stable
    fun steady(rail: HomeRail) = if (rail.id == FUSE_HOME_RAIL_ID) rail.copy(previewItems = held) else rail
    return content.copy(
        rails = content.rails.map(::steady),
        shelves = content.shelves.map { slot -> if (slot is HomeShelfSlot.Loaded) HomeShelfSlot.Loaded(steady(slot.rail)) else slot },
    ) to stable
}

/** Where a Fuse source's titles come from, which decides how it is asked for more. */
internal enum class FuseOrigin { Addon, CloudStream, Playlist }

/** One catalogue in the Fuse: an add-on catalogue, a CloudStream row, or one half of a playlist. */
internal data class FuseCatalog(
    val key: String,
    /** The add-on, provider or playlist it belongs to; the Fuse groups by this. */
    val sourceKey: String,
    val sourceName: String,
    val title: String,
    val live: Boolean,
    val origin: FuseOrigin,
    val addonId: String? = null,
    val rawType: String? = null,
    val catalogId: String? = null,
    val genre: String? = null,
    val searchable: Boolean = false,
    val cloudRowId: String? = null,
    /** A playlist's channels, already on the device: nothing to page through. */
    val localItems: List<MediaItem>? = null,
)

/** What has been loaded from one catalogue for one query. */
internal data class FusePage(
    val items: List<MediaItem> = emptyList(),
    val nextSkip: Int = 0,
    val end: Boolean = false,
    val failed: Boolean = false,
)

/** Source-qualified identity: equal ids from different providers are not the same title. */
internal fun fuseItemKey(item: MediaItem): String =
    listOf(item.sourceAddonId.orEmpty(), item.type, item.id).joinToString("")

/**
 * The key a catalogue's pages are held under for [query]. A catalogue that cannot search is filtered
 * on the device instead, so every query shares its one listing.
 */
internal fun fusePageKey(catalog: FuseCatalog, query: String): String = when {
    // A plugin is searched as a whole, so every row of one provider shares that one answer.
    catalog.origin == FuseOrigin.CloudStream && query.isNotBlank() ->
        "cloudsearch:" + catalog.sourceKey + "" + query.trim()
    else -> catalog.key + "" + if (catalog.searchable) query.trim() else ""
}
