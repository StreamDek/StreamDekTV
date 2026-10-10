package com.streamdek.tv.nativeapp.mediaserver

/*
 * Tidying the server list in Settings: taking one library or one server off the list without
 * disconnecting everything, and folding a server's libraries away.
 *
 * "Removed" is two things together. The library or server is switched off - that part is the
 * profile's, saved the way every other switch is, so it stops appearing anywhere in StreamDek on
 * every device. And this device stops listing it in Settings, which is what makes it removed
 * rather than merely off. It is kept under "Removed" so it can be brought back; nothing is deleted
 * from the server itself, which StreamDek has no business doing.
 *
 * A Jellyfin server is the exception: it has its own sign-in, so removing one really does sign out
 * of that server alone.
 */

/**
 * The name a removed or folded entry is remembered under. A null [libraryKey] means the server
 * itself. Each part is encoded, so an id containing the separator cannot be mistaken for two parts
 * and nothing unprintable reaches the preferences file.
 */
internal fun mediaServerEntryKey(provider: String, serverId: String, libraryKey: String? = null): String =
    listOfNotNull(provider, serverId, libraryKey).joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8") }

/**
 * Whether an entry belongs in the list. Something removed here but switched back on from another
 * device is on, and so is shown: the list never hides a thing that is in use.
 */
internal fun mediaServerEntryListed(removed: Set<String>, key: String, enabled: Boolean): Boolean =
    enabled || key !in removed

/**
 * [this] servers in the viewer's chosen order (set on the phone, kept with the profile). [order]
 * holds entry keys, most preferred first; a server it does not name keeps its place after those it
 * does, in the order the servers arrived.
 */
internal fun List<MediaServerView>.inServerOrder(provider: String, order: List<String>): List<MediaServerView> {
    if (order.isEmpty() || size < 2) return this
    val rank = order.withIndex().associate { it.value to it.index }
    return withIndex()
        .sortedWith(compareBy({ rank[mediaServerEntryKey(provider, it.value.id)] ?: Int.MAX_VALUE }, { it.index }))
        .map { it.value }
}

/** What a library's place is remembered by. It names the server, so each server keeps its own order. */
internal fun mediaServerLibraryOrderKey(serverId: String, libraryKey: String): String =
    mediaServerEntryKey("library", serverId, libraryKey)

/** This server's libraries in the chosen order; libraries [order] does not name follow as the server gave them. */
internal fun MediaServerView.withLibrariesInOrder(order: List<String>): MediaServerView {
    if (order.isEmpty() || libraries.size < 2) return this
    val rank = order.withIndex().associate { it.value to it.index }
    val sorted = libraries.withIndex()
        .sortedWith(compareBy({ rank[mediaServerLibraryOrderKey(id, it.value.key)] ?: Int.MAX_VALUE }, { it.index }))
        .map { it.value }
    return if (sorted == libraries) this else copy(libraries = sorted)
}

/** Servers, and the libraries in each, in the chosen order. */
internal fun List<MediaServerView>.inChosenOrder(provider: String, order: List<String>, libraryOrder: List<String>): List<MediaServerView> =
    if (order.isEmpty() && libraryOrder.isEmpty()) this
    else inServerOrder(provider, order).map { it.withLibrariesInOrder(libraryOrder) }

/**
 * Page rows in the order Settings shows the servers: by server, then by library within a server,
 * keeping each library's own rows in the order they came. A row that belongs to no one library
 * (Next Up, Favourites) stays at the top of its server's rows; rows of a server not in [servers]
 * follow the rest.
 */
internal fun List<MediaServerRow>.inPageOrder(servers: List<MediaServerView>): List<MediaServerRow> {
    if (size < 2 || servers.isEmpty()) return this
    val serverRank = servers.withIndex().associate { it.value.id to it.index }
    val libraryRank = servers.associate { server -> server.id to server.libraries.withIndex().associate { it.value.key to it.index } }
    return withIndex()
        .sortedWith(
            compareBy(
                { serverRank[it.value.serverId] ?: Int.MAX_VALUE },
                { row -> row.value.libraryKey?.let { libraryRank[row.value.serverId]?.get(it) ?: Int.MAX_VALUE } ?: -1 },
                { it.index },
            ),
        )
        .map { it.value }
}

/** One thing under "Removed", ready to be named and brought back. */
internal data class RemovedMediaServerEntry(
    val key: String,
    val serverId: String,
    val serverName: String,
    /** Null when the whole server was removed. */
    val library: MediaServerLibrary?,
)

/** The servers still on the list, each with only the libraries still on it. */
internal fun listedMediaServers(provider: String, servers: List<MediaServerView>, removed: Set<String>): List<MediaServerView> =
    servers
        .filter { mediaServerEntryListed(removed, mediaServerEntryKey(provider, it.id), it.enabled) }
        .map { server ->
            server.copy(
                libraries = server.libraries.filter {
                    mediaServerEntryListed(removed, mediaServerEntryKey(provider, server.id, it.key), it.enabled)
                },
            )
        }

/** What has been taken off the list and can be brought back, servers first. */
internal fun removedMediaServerEntries(provider: String, servers: List<MediaServerView>, removed: Set<String>): List<RemovedMediaServerEntry> = buildList {
    servers.forEach { server ->
        val serverKey = mediaServerEntryKey(provider, server.id)
        if (!mediaServerEntryListed(removed, serverKey, server.enabled)) {
            add(RemovedMediaServerEntry(serverKey, server.id, server.name, null))
            // Its libraries went with it and come back with it; they are not listed twice.
            return@forEach
        }
        server.libraries.forEach { library ->
            val key = mediaServerEntryKey(provider, server.id, library.key)
            if (!mediaServerEntryListed(removed, key, library.enabled)) add(RemovedMediaServerEntry(key, server.id, server.name, library))
        }
    }
}
