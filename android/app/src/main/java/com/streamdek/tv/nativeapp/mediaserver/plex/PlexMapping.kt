package com.streamdek.tv.nativeapp.mediaserver.plex

import com.streamdek.tv.nativeapp.data.CastMember
import com.streamdek.tv.nativeapp.data.ContinueWatchingItem
import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.SeasonDetail
import com.streamdek.tv.nativeapp.data.SeasonEpisode
import com.streamdek.tv.nativeapp.data.SeasonRef
import com.streamdek.tv.nativeapp.mediaserver.MediaServerReference
import com.streamdek.tv.nativeapp.mediaserver.MediaServerReview
import com.streamdek.tv.nativeapp.mediaserver.MediaServerResume
import com.streamdek.tv.nativeapp.mediaserver.PLEX_PROVIDER_ID
import java.net.URLEncoder
import java.time.Instant

/**
 * Plex metadata into StreamDek's own models.
 *
 * Pure, so it is tested without a server. The rules worth knowing:
 *
 *  - A title's `id` is a [MediaServerReference], so it routes back to the server that owns it, and
 *    its TMDB and IMDb ids are carried beside it from Plex's `Guid` list - that is what lets
 *    Continue Watching recognise the same film from Plex and from StreamDek as one title.
 *  - An episode is shown as its series, with the episode as context, the way every other source
 *    in StreamDek shows one.
 *  - Artwork goes through the server's photo transcoder at the size the card draws, so a stick is
 *    never handed a 4K poster to scale down, and the URL carries no token (see MediaServerAuth).
 */
internal data class PlexMappingContext(
    val serverId: String,
    val baseUri: String,
    /** What a card says it came from: "Plex", or "Plex · Living Room" when there is more than one server. */
    val attribution: String,
    val libraryTitles: Map<String, String> = emptyMap(),
    /** The app's words for an unnamed season or episode, in its language. */
    val seasonName: (Int) -> String = { "Season $it" },
    val episodeName: (Int) -> String = { "Episode $it" },
)

internal object PlexImages {
    fun url(context: PlexMappingContext, path: String?, width: Int, height: Int): String? {
        val value = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.startsWith("http://") || value.startsWith("https://")) {
            // An agent's own artwork, already public. Resized by the server anyway only when it is ours.
            return value
        }
        val encoded = URLEncoder.encode(value, "UTF-8")
        return "${context.baseUri}/photo/:/transcode?width=$width&height=$height&minSize=1&upscale=1&url=$encoded"
    }

    fun poster(context: PlexMappingContext, path: String?) = url(context, path, 300, 450)
    fun backdrop(context: PlexMappingContext, path: String?) = url(context, path, 1280, 720)
    fun still(context: PlexMappingContext, path: String?) = url(context, path, 480, 270)
    fun portrait(context: PlexMappingContext, path: String?) = url(context, path, 200, 200)
}

internal object PlexMapping {
    private val tmdbGuid = Regex("^tmdb://(\\d{1,12})$")
    private val imdbGuid = Regex("^imdb://(tt\\d{5,12})$")
    // Old-agent guids carry the id inside the agent URL.
    private val legacyImdb = Regex("imdb://(tt\\d{5,12})")
    private val legacyTmdb = Regex("themoviedb://(\\d{1,12})")

    fun reference(context: PlexMappingContext, ratingKey: String): MediaServerReference =
        MediaServerReference(PLEX_PROVIDER_ID, context.serverId, ratingKey)

    fun tmdbId(meta: PlexMetadata): Int? =
        meta.guids.orEmpty().firstNotNullOfOrNull { tag -> tag.id?.let { tmdbGuid.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() } }
            ?: meta.guid?.let { legacyTmdb.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    fun imdbId(meta: PlexMetadata): String? =
        meta.guids.orEmpty().firstNotNullOfOrNull { tag -> tag.id?.let { imdbGuid.matchEntire(it.trim())?.groupValues?.get(1) } }
            ?: meta.guid?.let { legacyImdb.find(it)?.groupValues?.get(1) }

    /** StreamDek's media type, or null for anything StreamDek does not open as a title. */
    fun mediaType(meta: PlexMetadata): String? = when (meta.type?.lowercase()) {
        "movie" -> "movie"
        "show", "season", "episode" -> "tv"
        "collection" -> COLLECTION_TYPE
        // A "clip" in a personal-videos library plays like a film.
        "clip", "video" -> "movie"
        else -> null
    }

    private fun seriesKey(meta: PlexMetadata): String? = when (meta.type?.lowercase()) {
        "episode" -> meta.grandparentRatingKey
        "season" -> meta.parentRatingKey
        else -> meta.ratingKey
    }

    private fun percent(offsetMs: Long?, durationMs: Long?): Double? {
        if (offsetMs == null || durationMs == null || offsetMs <= 0 || durationMs <= 0) return null
        return (offsetMs.toDouble() / durationMs * 100.0).coerceIn(0.0, 100.0)
    }

    private fun year(meta: PlexMetadata): String? =
        meta.year?.takeIf { it > 0 }?.toString() ?: meta.originallyAvailableAt?.take(4)?.takeIf { it.all(Char::isDigit) }

    /** A card. Episodes and seasons become their series. */
    fun item(meta: PlexMetadata, context: PlexMappingContext, libraryKey: String? = meta.librarySectionID): MediaItem? {
        val type = mediaType(meta) ?: return null
        val isEpisodic = meta.type.equals("episode", true) || meta.type.equals("season", true)
        val key = seriesKey(meta) ?: return null
        val title = (if (isEpisodic) meta.grandparentTitle ?: meta.parentTitle else meta.title)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val posterPath = when {
            meta.type.equals("episode", true) -> meta.grandparentThumb ?: meta.parentThumb ?: meta.thumb
            meta.type.equals("season", true) -> meta.parentThumb ?: meta.thumb
            else -> meta.thumb
        }
        val artPath = when {
            isEpisodic -> meta.grandparentArt ?: meta.art
            else -> meta.art
        }
        return MediaItem(
            id = reference(context, key).encode(),
            tmdbId = if (isEpisodic) 0 else tmdbId(meta) ?: 0,
            imdbId = if (isEpisodic) null else imdbId(meta),
            title = title,
            type = type,
            poster = PlexImages.poster(context, posterPath),
            backdrop = PlexImages.backdrop(context, artPath),
            description = meta.summary?.takeIf { it.isNotBlank() && !isEpisodic },
            rating = (meta.audienceRating ?: meta.rating)?.takeIf { it > 0 },
            year = if (isEpisodic) null else year(meta),
            progress = if (isEpisodic) null else percent(meta.viewOffset, meta.duration),
            positionSec = if (isEpisodic) null else meta.viewOffset?.takeIf { it > 0 }?.div(1000.0),
            durationSec = if (isEpisodic) null else meta.duration?.takeIf { it > 0 }?.div(1000.0),
            sourceAddonId = MediaServerReference.sourceIdOf(PLEX_PROVIDER_ID, context.serverId),
            sourceAddonName = context.attribution,
            sourceMediaType = type,
            sourceCatalogId = libraryKey,
            sourceCatalogName = libraryKey?.let(context.libraryTitles::get) ?: meta.librarySectionTitle,
            genres = meta.genres.orEmpty().mapNotNull { it.tag?.trim()?.takeIf(String::isNotEmpty) }.takeIf { it.isNotEmpty() },
        )
    }

    fun detail(meta: PlexMetadata, seasons: List<PlexMetadata>, context: PlexMappingContext): MediaDetail? {
        val type = mediaType(meta)?.takeIf { it == "movie" || it == "tv" } ?: return null
        val key = meta.ratingKey ?: return null
        val title = meta.title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val seasonRefs = seasons
            .filter { it.type.equals("season", true) && it.index != null && it.index >= 0 }
            .sortedBy { it.index }
            .map { season ->
                SeasonRef(
                    seasonNumber = season.index!!,
                    name = season.title?.takeIf { it.isNotBlank() } ?: context.seasonName(season.index!!),
                    episodeCount = season.leafCount ?: 0,
                    airDate = season.originallyAvailableAt,
                )
            }
        return MediaDetail(
            id = reference(context, key).encode(),
            tmdbId = tmdbId(meta) ?: 0,
            title = title,
            type = type,
            poster = PlexImages.poster(context, meta.thumb),
            backdrop = PlexImages.backdrop(context, meta.art),
            description = meta.summary?.takeIf { it.isNotBlank() },
            rating = (meta.audienceRating ?: meta.rating)?.takeIf { it > 0 },
            year = year(meta),
            imdbId = imdbId(meta),
            certification = meta.contentRating?.takeIf { it.isNotBlank() },
            genreNames = meta.genres.orEmpty().mapNotNull { it.tag?.trim()?.takeIf(String::isNotEmpty) },
            cast = meta.roles.orEmpty().take(24).mapNotNull { role ->
                val name = role.tag?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                // Plex's own person ids mean nothing to TMDB, so no person page is claimed.
                CastMember(id = 0, name = name, character = role.role, photo = PlexImages.portrait(context, role.thumb))
            },
            seasons = seasonRefs,
            runtime = meta.duration?.takeIf { it > 0 && type == "movie" }?.let { (it / 60_000L).toInt() },
            releaseDate = meta.originallyAvailableAt,
            tagline = meta.tagline?.takeIf { it.isNotBlank() },
            numberOfSeasons = seasonRefs.count { it.seasonNumber > 0 }.takeIf { type == "tv" },
            numberOfEpisodes = meta.leafCount?.takeIf { type == "tv" },
        )
    }

    fun season(seasonNumber: Int, seasonMeta: PlexMetadata?, episodes: List<PlexMetadata>, context: PlexMappingContext): SeasonDetail =
        SeasonDetail(
            seasonNumber = seasonNumber,
            name = seasonMeta?.title?.takeIf { it.isNotBlank() } ?: context.seasonName(seasonNumber),
            overview = seasonMeta?.summary?.takeIf { it.isNotBlank() },
            episodes = episodes
                .filter { it.index != null }
                .sortedBy { it.index }
                .map { episode ->
                    SeasonEpisode(
                        id = episode.ratingKey?.toIntOrNull() ?: 0,
                        episodeNumber = episode.index!!,
                        name = episode.title?.takeIf { it.isNotBlank() } ?: context.episodeName(episode.index!!),
                        overview = episode.summary?.takeIf { it.isNotBlank() },
                        still = PlexImages.still(context, episode.thumb),
                        runtime = episode.duration?.takeIf { it > 0 }?.let { (it / 60_000L).toInt() },
                        airDate = episode.originallyAvailableAt,
                    )
                },
        )

    fun episodeContext(episode: PlexMetadata, context: PlexMappingContext): EpisodeContext? {
        val season = episode.parentIndex ?: return null
        val number = episode.index ?: return null
        return EpisodeContext(
            seasonNumber = season,
            episodeNumber = number,
            title = episode.title,
            overview = episode.summary,
            still = PlexImages.still(context, episode.thumb),
            runtime = episode.duration?.takeIf { it > 0 }?.let { (it / 60_000L).toInt() },
            airDate = episode.originallyAvailableAt,
        )
    }

    /**
     * A title part-way through, as Continue Watching holds it.
     *
     * [seriesGuids] carries the series' TMDB/IMDb ids for an episode, which the episode's own
     * metadata does not include; without them an episode could not be matched against StreamDek's
     * own row for the same series.
     */
    fun resume(meta: PlexMetadata, context: PlexMappingContext, seriesGuids: PlexMetadata? = null): MediaServerResume? {
        val offset = meta.viewOffset?.takeIf { it > 0 } ?: return null
        val duration = meta.duration?.takeIf { it > 0 } ?: return null
        val lastViewed = (meta.lastViewedAt ?: meta.updatedAt ?: 0L) * 1000L
        val updatedAt = lastViewed.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() }
        val positionSec = offset / 1000.0
        val durationSec = duration / 1000.0
        return when (meta.type?.lowercase()) {
            "episode" -> {
                val showKey = meta.grandparentRatingKey ?: return null
                val episode = episodeContext(meta, context) ?: return null
                val tmdb = seriesGuids?.let(::tmdbId)
                val imdb = seriesGuids?.let(::imdbId)
                MediaServerResume(
                    item = ContinueWatchingItem(
                        id = reference(context, showKey).encode(),
                        tmdbId = tmdb ?: 0,
                        title = meta.grandparentTitle ?: meta.title ?: return null,
                        type = "tv",
                        poster = PlexImages.poster(context, meta.grandparentThumb ?: meta.parentThumb),
                        backdrop = PlexImages.backdrop(context, meta.grandparentArt ?: meta.art),
                        description = meta.summary,
                        progress = percent(offset, duration),
                        positionSec = positionSec,
                        durationSec = durationSec,
                        resumeAt = positionSec,
                        episode = episode,
                        seasonNumber = episode.seasonNumber,
                        episodeNumber = episode.episodeNumber,
                        updatedAt = updatedAt,
                        lastDevice = context.attribution,
                        lastPlatform = PLEX_PROVIDER_ID,
                    ),
                    lastViewedAtMs = lastViewed,
                    tmdbId = tmdb,
                    imdbId = imdb,
                )
            }
            "movie", "clip", "video" -> {
                val key = meta.ratingKey ?: return null
                val tmdb = tmdbId(meta)
                val imdb = imdbId(meta)
                MediaServerResume(
                    item = ContinueWatchingItem(
                        id = reference(context, key).encode(),
                        tmdbId = tmdb ?: 0,
                        title = meta.title ?: return null,
                        type = "movie",
                        poster = PlexImages.poster(context, meta.thumb),
                        backdrop = PlexImages.backdrop(context, meta.art),
                        description = meta.summary,
                        rating = (meta.audienceRating ?: meta.rating)?.takeIf { it > 0 },
                        year = year(meta),
                        progress = percent(offset, duration),
                        positionSec = positionSec,
                        durationSec = durationSec,
                        resumeAt = positionSec,
                        updatedAt = updatedAt,
                        lastDevice = context.attribution,
                        lastPlatform = PLEX_PROVIDER_ID,
                    ),
                    lastViewedAtMs = lastViewed,
                    tmdbId = tmdb,
                    imdbId = imdb,
                )
            }
            else -> null
        }
    }

    const val COLLECTION_TYPE = "collection"

    /**
     * A title's reviews, those with something to read. A link is kept only when it is an ordinary
     * web address, since it is opened outside the app.
     */
    fun reviews(meta: PlexMetadata): List<MediaServerReview> = meta.reviews.orEmpty().mapNotNull { review ->
        val text = review.text?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val verdict = review.image?.lowercase(java.util.Locale.US).orEmpty()
        MediaServerReview(
            author = review.tag?.trim()?.takeIf { it.isNotEmpty() } ?: review.source?.trim().orEmpty(),
            publication = review.source?.trim()?.takeIf { it.isNotEmpty() && it != review.tag?.trim() },
            text = text,
            link = review.link?.trim()?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) },
            positive = when {
                "fresh" in verdict || "upright" in verdict -> true
                "rotten" in verdict || "spilled" in verdict -> false
                else -> null
            },
        )
    }.filter { it.author.isNotEmpty() }
}
