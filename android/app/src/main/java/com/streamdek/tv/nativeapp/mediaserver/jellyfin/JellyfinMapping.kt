package com.streamdek.tv.nativeapp.mediaserver.jellyfin

import com.streamdek.tv.nativeapp.data.CastMember
import com.streamdek.tv.nativeapp.data.ContinueWatchingItem
import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.SeasonDetail
import com.streamdek.tv.nativeapp.data.SeasonEpisode
import com.streamdek.tv.nativeapp.data.SeasonRef
import com.streamdek.tv.nativeapp.mediaserver.JELLYFIN_PROVIDER_ID
import com.streamdek.tv.nativeapp.mediaserver.MediaServerReference
import com.streamdek.tv.nativeapp.mediaserver.MediaServerResume
import com.streamdek.tv.nativeapp.mediaserver.plex.PlexMapping
import java.time.Instant
import java.util.Locale

internal data class JellyfinMappingContext(
    /** "jellyfin" or "emby": which provider a title's id routes back to. */
    val provider: String = JELLYFIN_PROVIDER_ID,
    val serverId: String,
    val baseUrl: String,
    /** "Jellyfin", or "Jellyfin · Home Server" when the profile uses more than one server. */
    val attribution: String,
    val libraryTitles: Map<String, String> = emptyMap(),
    val seasonName: (Int) -> String = { "Season $it" },
    val episodeName: (Int) -> String = { "Episode $it" },
)

/**
 * Jellyfin artwork at the size each surface draws. The image endpoint needs no token, and the URL
 * carries none: requests to a signed-in server get their header from MediaServerAuth.
 */
internal object JellyfinImages {
    private fun url(context: JellyfinMappingContext, itemId: String?, type: String, tag: String?, width: Int, height: Int, index: Int? = null): String? {
        if (itemId.isNullOrBlank() || tag.isNullOrBlank()) return null
        val path = if (index != null) "/Items/$itemId/Images/$type/$index" else "/Items/$itemId/Images/$type"
        return "${context.baseUrl.trimEnd('/')}$path?fillWidth=$width&fillHeight=$height&quality=90&tag=$tag"
    }

    fun poster(context: JellyfinMappingContext, itemId: String?, tag: String?) = url(context, itemId, "Primary", tag, 300, 450)
    fun backdrop(context: JellyfinMappingContext, itemId: String?, tag: String?) = url(context, itemId, "Backdrop", tag, 1280, 720, 0)
    fun still(context: JellyfinMappingContext, itemId: String?, tag: String?) = url(context, itemId, "Primary", tag, 480, 270)
    fun portrait(context: JellyfinMappingContext, itemId: String?, tag: String?) = url(context, itemId, "Primary", tag, 200, 200)
    fun logo(context: JellyfinMappingContext, itemId: String?, tag: String?) = url(context, itemId, "Logo", tag, 640, 180)
}

internal object JellyfinMapping {
    private const val TICKS_PER_MS = 10_000L

    fun reference(context: JellyfinMappingContext, itemId: String): MediaServerReference =
        MediaServerReference(context.provider, context.serverId, itemId)

    fun ms(ticks: Long?): Long? = ticks?.takeIf { it > 0 }?.div(TICKS_PER_MS)

    fun ticks(ms: Long): Long = ms * TICKS_PER_MS

    fun tmdbId(item: JellyfinItem): Int? =
        item.providerIds.orEmpty().entries.firstOrNull { it.key.equals("Tmdb", true) }?.value?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    fun imdbId(item: JellyfinItem): String? =
        item.providerIds.orEmpty().entries.firstOrNull { it.key.equals("Imdb", true) }?.value?.trim()?.takeIf { it.matches(Regex("tt\\d{5,12}")) }

    /** StreamDek's media type, or null for anything StreamDek does not open as a title. */
    fun mediaType(item: JellyfinItem): String? = when (item.type?.lowercase(Locale.US)) {
        "movie", "video", "musicvideo" -> "movie"
        "series", "season", "episode" -> "tv"
        "boxset" -> PlexMapping.COLLECTION_TYPE
        else -> null
    }

    private fun isEpisodic(item: JellyfinItem) = item.type.equals("Episode", true) || item.type.equals("Season", true)

    private fun seriesKey(item: JellyfinItem): String? = if (isEpisodic(item)) item.seriesId else item.id

    fun year(item: JellyfinItem): String? =
        item.productionYear?.takeIf { it > 0 }?.toString() ?: item.premiereDate?.take(4)?.takeIf { it.all(Char::isDigit) }

    private fun percent(positionMs: Long?, durationMs: Long?): Double? {
        if (positionMs == null || durationMs == null || positionMs <= 0 || durationMs <= 0) return null
        return (positionMs.toDouble() / durationMs * 100.0).coerceIn(0.0, 100.0)
    }

    private fun posterOf(item: JellyfinItem, context: JellyfinMappingContext): String? = when {
        isEpisodic(item) -> JellyfinImages.poster(context, item.seriesId, item.seriesPrimaryImageTag)
        else -> JellyfinImages.poster(context, item.id, item.imageTags?.get("Primary"))
    }

    private fun backdropOf(item: JellyfinItem, context: JellyfinMappingContext): String? =
        item.backdropImageTags?.firstOrNull()?.let { JellyfinImages.backdrop(context, item.id, it) }
            ?: item.parentBackdropImageTags?.firstOrNull()?.let { JellyfinImages.backdrop(context, item.parentBackdropItemId, it) }

    private fun logoOf(item: JellyfinItem, context: JellyfinMappingContext): String? =
        item.imageTags?.get("Logo")?.let { JellyfinImages.logo(context, item.id, it) }
            ?: JellyfinImages.logo(context, item.parentLogoItemId, item.parentLogoImageTag)

    fun rating(item: JellyfinItem): Double? = item.communityRating?.takeIf { it > 0 }

    /** A card. Episodes and seasons become their series. */
    fun item(item: JellyfinItem, context: JellyfinMappingContext, libraryKey: String? = null): MediaItem? {
        val type = mediaType(item) ?: return null
        val episodic = isEpisodic(item)
        val key = seriesKey(item) ?: return null
        val title = (if (episodic) item.seriesName else item.name)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val durationMs = ms(item.runTimeTicks)
        val positionMs = ms(item.userData?.playbackPositionTicks)
        return MediaItem(
            id = reference(context, key).encode(),
            tmdbId = if (episodic) 0 else tmdbId(item) ?: 0,
            imdbId = if (episodic) null else imdbId(item),
            title = title,
            type = type,
            poster = posterOf(item, context),
            backdrop = backdropOf(item, context),
            description = item.overview?.takeIf { it.isNotBlank() && !episodic },
            rating = rating(item),
            year = if (episodic) null else year(item),
            progress = if (episodic) null else percent(positionMs, durationMs),
            positionSec = if (episodic) null else positionMs?.div(1000.0),
            durationSec = if (episodic) null else durationMs?.div(1000.0),
            sourceAddonId = MediaServerReference.sourceIdOf(context.provider, context.serverId),
            sourceAddonName = context.attribution,
            sourceMediaType = type,
            sourceCatalogId = libraryKey,
            sourceCatalogName = libraryKey?.let(context.libraryTitles::get),
            genres = item.genres?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }?.takeIf { it.isNotEmpty() },
        )
    }

    fun detail(item: JellyfinItem, seasons: List<JellyfinItem>, context: JellyfinMappingContext): MediaDetail? {
        val type = mediaType(item)?.takeIf { it == "movie" || it == "tv" } ?: return null
        val id = item.id ?: return null
        val title = item.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val seasonRefs = seasons
            .filter { it.type.equals("Season", true) && (it.indexNumber ?: -1) >= 0 }
            .sortedBy { it.indexNumber }
            .map { season ->
                SeasonRef(
                    seasonNumber = season.indexNumber!!,
                    name = season.name?.takeIf { it.isNotBlank() } ?: context.seasonName(season.indexNumber),
                    episodeCount = season.childCount ?: season.recursiveItemCount ?: 0,
                    airDate = season.premiereDate?.take(10),
                )
            }
        return MediaDetail(
            id = reference(context, id).encode(),
            tmdbId = tmdbId(item) ?: 0,
            title = title,
            type = type,
            poster = posterOf(item, context),
            backdrop = backdropOf(item, context),
            description = item.overview?.takeIf { it.isNotBlank() },
            rating = rating(item),
            year = year(item),
            imdbId = imdbId(item),
            titleLogo = logoOf(item, context),
            certification = item.officialRating?.takeIf { it.isNotBlank() },
            genreNames = item.genres.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) },
            cast = item.people.orEmpty()
                .filter { it.type.equals("Actor", true) || it.type.equals("GuestStar", true) }
                .take(24)
                .mapNotNull { person ->
                    val name = person.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    // Person pages open from TMDB ids; a Jellyfin person has none here, so 0 keeps
                    // the card informational rather than opening the wrong person.
                    CastMember(id = 0, name = name, character = person.role?.takeIf { it.isNotBlank() }, photo = JellyfinImages.portrait(context, person.id, person.primaryImageTag))
                },
            seasons = seasonRefs,
            runtime = ms(item.runTimeTicks)?.takeIf { type == "movie" }?.let { (it / 60_000L).toInt() },
            releaseDate = item.premiereDate?.take(10),
            tagline = item.taglines?.firstOrNull()?.takeIf { it.isNotBlank() },
            status = item.status?.takeIf { it.isNotBlank() },
            numberOfSeasons = seasonRefs.count { it.seasonNumber > 0 }.takeIf { type == "tv" },
            numberOfEpisodes = item.recursiveItemCount?.takeIf { type == "tv" },
        )
    }

    fun season(seasonNumber: Int, seasonItem: JellyfinItem?, episodes: List<JellyfinItem>, context: JellyfinMappingContext): SeasonDetail =
        SeasonDetail(
            seasonNumber = seasonNumber,
            name = seasonItem?.name?.takeIf { it.isNotBlank() } ?: context.seasonName(seasonNumber),
            overview = seasonItem?.overview?.takeIf { it.isNotBlank() },
            episodes = episodes
                .filter { it.indexNumber != null }
                .sortedBy { it.indexNumber }
                .map { episode ->
                    SeasonEpisode(
                        // Jellyfin ids are GUIDs; the card only needs a stable number.
                        id = episode.id?.hashCode() ?: 0,
                        episodeNumber = episode.indexNumber!!,
                        name = episode.name?.takeIf { it.isNotBlank() } ?: context.episodeName(episode.indexNumber),
                        overview = episode.overview?.takeIf { it.isNotBlank() },
                        still = JellyfinImages.still(context, episode.id, episode.imageTags?.get("Primary")),
                        runtime = ms(episode.runTimeTicks)?.let { (it / 60_000L).toInt() },
                        airDate = episode.premiereDate?.take(10),
                    )
                },
        )

    fun episodeContext(episode: JellyfinItem, context: JellyfinMappingContext): EpisodeContext? {
        val season = episode.parentIndexNumber ?: return null
        val number = episode.indexNumber ?: return null
        return EpisodeContext(
            seasonNumber = season,
            episodeNumber = number,
            title = episode.name,
            overview = episode.overview,
            still = JellyfinImages.still(context, episode.id, episode.imageTags?.get("Primary")),
            runtime = ms(episode.runTimeTicks)?.let { (it / 60_000L).toInt() },
            airDate = episode.premiereDate?.take(10),
        )
    }

    fun instantMs(value: String?): Long = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    /**
     * A title part-way through, as Continue Watching holds it. [series] carries the series' TMDB and
     * IMDb ids for an episode, which is what matches it against StreamDek's own row for that series.
     */
    fun resume(item: JellyfinItem, context: JellyfinMappingContext, series: JellyfinItem? = null): MediaServerResume? {
        val positionMs = ms(item.userData?.playbackPositionTicks) ?: return null
        val durationMs = ms(item.runTimeTicks) ?: return null
        val lastViewed = instantMs(item.userData?.lastPlayedDate)
        val updatedAt = lastViewed.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() }
        val positionSec = positionMs / 1000.0
        val durationSec = durationMs / 1000.0
        return when (item.type?.lowercase(Locale.US)) {
            "episode" -> {
                val seriesId = item.seriesId ?: return null
                val episode = episodeContext(item, context) ?: return null
                val tmdb = series?.let(::tmdbId)
                val imdb = series?.let(::imdbId)
                MediaServerResume(
                    item = ContinueWatchingItem(
                        id = reference(context, seriesId).encode(),
                        tmdbId = tmdb ?: 0,
                        title = item.seriesName ?: item.name ?: return null,
                        type = "tv",
                        poster = posterOf(item, context),
                        backdrop = backdropOf(item, context),
                        description = item.overview,
                        progress = percent(positionMs, durationMs),
                        positionSec = positionSec,
                        durationSec = durationSec,
                        resumeAt = positionSec,
                        episode = episode,
                        seasonNumber = episode.seasonNumber,
                        episodeNumber = episode.episodeNumber,
                        updatedAt = updatedAt,
                        lastDevice = context.attribution,
                        lastPlatform = context.provider,
                    ),
                    lastViewedAtMs = lastViewed,
                    tmdbId = tmdb,
                    imdbId = imdb,
                )
            }
            "movie", "video", "musicvideo" -> {
                val id = item.id ?: return null
                val tmdb = tmdbId(item)
                val imdb = imdbId(item)
                MediaServerResume(
                    item = ContinueWatchingItem(
                        id = reference(context, id).encode(),
                        tmdbId = tmdb ?: 0,
                        title = item.name ?: return null,
                        type = "movie",
                        poster = posterOf(item, context),
                        backdrop = backdropOf(item, context),
                        description = item.overview,
                        rating = rating(item),
                        year = year(item),
                        progress = percent(positionMs, durationMs),
                        positionSec = positionSec,
                        durationSec = durationSec,
                        resumeAt = positionSec,
                        updatedAt = updatedAt,
                        lastDevice = context.attribution,
                        lastPlatform = context.provider,
                    ),
                    lastViewedAtMs = lastViewed,
                    tmdbId = tmdb,
                    imdbId = imdb,
                )
            }
            else -> null
        }
    }

    /** Which libraries StreamDek shows. Music, books, photos, live TV and playlists are not played here. */
    fun libraryKind(view: JellyfinItem): com.streamdek.tv.nativeapp.mediaserver.MediaServerLibraryKind? =
        when (view.collectionType?.lowercase(Locale.US)) {
            "movies" -> com.streamdek.tv.nativeapp.mediaserver.MediaServerLibraryKind.Movies
            "tvshows" -> com.streamdek.tv.nativeapp.mediaserver.MediaServerLibraryKind.Shows
            // Home videos, mixed libraries (no collection type) and folders play like films.
            "homevideos", "mixed", "folders", null, "" -> com.streamdek.tv.nativeapp.mediaserver.MediaServerLibraryKind.Other
            else -> null
        }

    /** The item types a library lists as titles, for Jellyfin's IncludeItemTypes. */
    fun includeTypes(kind: com.streamdek.tv.nativeapp.mediaserver.MediaServerLibraryKind): String = when (kind) {
        com.streamdek.tv.nativeapp.mediaserver.MediaServerLibraryKind.Movies -> "Movie"
        com.streamdek.tv.nativeapp.mediaserver.MediaServerLibraryKind.Shows -> "Series"
        com.streamdek.tv.nativeapp.mediaserver.MediaServerLibraryKind.Other -> "Movie,Video,Series"
    }
}
