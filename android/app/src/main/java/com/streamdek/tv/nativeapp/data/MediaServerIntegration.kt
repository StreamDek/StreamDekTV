package com.streamdek.tv.nativeapp.data

import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.mediaserver.MediaServerReference
import com.streamdek.tv.nativeapp.mediaserver.MediaServerResume
import com.streamdek.tv.nativeapp.mediaserver.MediaServerRow
import com.streamdek.tv.nativeapp.mediaserver.MediaServerRowKind
import java.time.Instant

/**
 * Where a personal media server's content meets StreamDek's own, kept apart from the repository
 * so the rules can be read, and tested, on their own.
 */

/** Home's slot for media server rows. Rows are published into it by the Home assembly. */
internal const val MEDIA_SERVER_HOME_SLOT = "media-server-rows"

/**
 * Media server rows as Home rails.
 *
 * Collections are left off Home: a collection card opens a list, not a title, and Home's cards all
 * open titles. They are on the provider's own page, which knows what to do with one.
 */
internal fun mediaServerHomeRails(rows: List<MediaServerRow>): List<HomeRail> = rows
    .filter { it.kind != MediaServerRowKind.Collections && it.items.isNotEmpty() }
    .map { row -> HomeRail(id = row.id, title = row.title, items = row.items) }

/** The rows Home Rows settings lists for media servers, from the rows last built. */
internal fun mediaServerHomeRowOptions(rows: List<MediaServerRow>): List<HomeRowOption> = rows
    .filter { it.kind != MediaServerRowKind.Collections }
    .map { row ->
        HomeRowOption(
            id = row.id,
            title = row.title,
            subtitleRes = R.string.home_row_from_addon,
            subtitleArg = row.items.firstOrNull()?.sourceAddonName ?: row.serverName,
            builtin = false,
        )
    }

private fun epochMillis(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

/**
 * Whether a StreamDek Continue Watching row and a media server's in-progress title are the same
 * title, and so must appear once.
 *
 * Same id is the same title. Otherwise the server's TMDB or IMDb id, taken from Plex's Guid list,
 * against the row's: a film watched halfway through an add-on source and then finished on Plex is
 * one film. A series is one card whichever episode each side is on - StreamDek shows a series in
 * Continue Watching once, at the episode most recently watched.
 */
internal fun sameContinueTitle(row: ContinueWatchingItem, server: MediaServerResume): Boolean {
    if (row.id == server.item.id) return true
    if (MediaClassification.canonical(row.type) != MediaClassification.canonical(server.item.type)) return false
    val tmdb = server.tmdbId?.takeIf { it > 0 }
    if (tmdb != null && (row.tmdbId == tmdb || row.id == tmdb.toString() || row.id == "tmdb:$tmdb")) return true
    val imdb = server.imdbId?.takeIf { it.isNotBlank() }
    return imdb != null && row.id == imdb
}

/**
 * StreamDek's Continue Watching with the media servers' in-progress titles folded in.
 *
 * One entry per title: where both sides know a title, the more recently watched one stays, so the
 * card resumes at the newest position and plays from where that position was made. A server title
 * nobody else knows is placed by when it was last watched, without reordering the rows StreamDek
 * already had - those carry their own ordering rules (Next Up among them) that this must not undo.
 */
internal fun reconcileContinueWatching(
    streamDek: List<ContinueWatchingItem>,
    servers: List<MediaServerResume>,
): List<ContinueWatchingItem> {
    if (servers.isEmpty()) return streamDek
    val result = streamDek.toMutableList()
    // Newest first, so when two servers hold the same title the newer one is the one placed.
    for (resume in servers.sortedByDescending { it.lastViewedAtMs }) {
        val existing = result.indexOfFirst { sameContinueTitle(it, resume) }
        if (existing >= 0) {
            val rowTime = epochMillis(result[existing].updatedAt) ?: 0L
            if (resume.lastViewedAtMs > rowTime) result[existing] = resume.item
            continue
        }
        // A server title also held by an earlier server entry is the same title twice.
        if (result.any { it.id == resume.item.id }) continue
        val insertAt = result.indexOfFirst { row ->
            val time = epochMillis(row.updatedAt) ?: return@indexOfFirst false
            time < resume.lastViewedAtMs
        }
        if (insertAt < 0) result.add(resume.item) else result.add(insertAt, resume.item)
    }
    return result
}

/** Whether a title, by its id, belongs to a personal media server rather than to StreamDek or an add-on. */
internal fun isMediaServerId(id: String?): Boolean = MediaServerReference.isReference(id)


/**
 * A media server's title page with the catalogue's description of the same title filled in.
 *
 * The server's own identity, title, seasons and poster stay: they are what plays, and what the
 * viewer chose on their server. What the server does not know - logo, trailers, similar titles,
 * certification - comes from the catalogue, and the catalogue's cast (with photos and pages) is
 * preferred to the server's list of names.
 */
internal fun MediaDetail.enrichedFromCatalog(catalog: MediaDetail): MediaDetail = copy(
    tmdbId = tmdbId.takeIf { it > 0 } ?: catalog.tmdbId.takeIf { it > 0 } ?: catalog.id.toIntOrNull() ?: 0,
    poster = poster ?: catalog.poster,
    backdrop = backdrop ?: catalog.backdrop,
    description = description?.takeIf { it.isNotBlank() } ?: catalog.description,
    rating = rating ?: catalog.rating,
    year = year ?: catalog.year,
    imdbId = imdbId ?: catalog.imdbId,
    titleLogo = titleLogo ?: catalog.titleLogo,
    trailerKey = trailerKey ?: catalog.trailerKey,
    trailerSite = trailerSite ?: catalog.trailerSite,
    trailerKeys = trailerKeys.ifEmpty { catalog.trailerKeys },
    certification = certification ?: catalog.certification,
    certificationCountry = certificationCountry ?: catalog.certificationCountry,
    genreNames = genreNames.ifEmpty { catalog.genreNames },
    cast = catalog.cast.ifEmpty { cast },
    similarTitles = similarTitles.ifEmpty { catalog.similarTitles.withoutAdult() },
    runtime = runtime ?: catalog.runtime,
    releaseDate = releaseDate ?: catalog.releaseDate,
    tagline = tagline ?: catalog.tagline,
    status = status ?: catalog.status,
)
