package com.streamdek.tv.nativeapp.mediaserver

import com.streamdek.tv.nativeapp.data.AddonStream
import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.ExternalSubtitleTrack
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.SeasonDetail

/**
 * One kind of personal media server, as StreamDek uses it.
 *
 * The contract every screen relies on, and the only thing a second provider (Jellyfin, Emby) has
 * to implement: StreamDek's own models in and out, so Home, Search, the title page, the stream list,
 * the player and StreamDek Fuse never learn which product a title came from.
 *
 * Authentication and discovery are *not* here. Linking an account and asking which servers it can
 * use is StreamDek-backend work ([MediaServerManager]); a provider is handed the servers and
 * talks to them. Everything here is expected to:
 *
 *  - answer from the device, directly to the viewer's server;
 *  - time out quickly and return empty rather than throw when a server is away, so one sleeping
 *    NAS never holds up a screen that has other sources to show;
 *  - never put a token in a URL it returns - see [MediaServerAuth].
 */
interface MediaServerProvider {
    val id: String
    val label: String

    /** Replaces the servers this provider may use. Called after every discovery. */
    fun setServers(servers: List<DiscoveredMediaServer>)

    /** Works out how to reach each enabled server, fastest route first. */
    suspend fun connect(force: Boolean = false)

    /** Where each server stands right now. */
    fun reachability(serverId: String): MediaServerReachability

    /** The libraries on [serverId], with the viewer's choices applied. Cached; [force] asks again. */
    suspend fun libraries(serverId: String, force: Boolean = false): List<MediaServerLibrary>

    /** Rows for Home and the provider's own page, from every enabled library. */
    suspend fun rows(includeCollections: Boolean): List<MediaServerRow>

    /** Titles part-way through, across enabled servers, for the unified Continue Watching. */
    suspend fun continueWatching(): List<MediaServerResume>

    /** One page of a library, newest additions or A-Z. */
    suspend fun browse(serverId: String, libraryKey: String, start: Int, size: Int, sort: MediaServerSort): MediaServerPage

    /** The titles inside a collection card. */
    suspend fun collection(ref: MediaServerReference, start: Int, size: Int): MediaServerPage

    suspend fun detail(ref: MediaServerReference): MediaDetail?

    suspend fun season(ref: MediaServerReference, seasonNumber: Int): SeasonDetail?

    suspend fun search(query: String, limit: Int): List<MediaItem>

    /**
     * Playable sources for a title, best first: Direct Play, then Direct Stream, then Transcode.
     *
     * Returned as ordinary [AddonStream]s so the stream list and the player's own fallback - try
     * the next source when one will not start - work unchanged. The order is the decision; see
     * `PlexPlaybackPlanner`.
     */
    suspend fun streams(ref: MediaServerReference, episode: EpisodeContext?, context: MediaServerPlaybackContext): List<AddonStream>

    /** The server's own resume point and watched state, read fresh. */
    suspend fun progress(ref: MediaServerReference, episode: EpisodeContext?): MediaServerProgress?

    /** Every episode of a series with its standing, in one read. */
    suspend fun seriesProgress(ref: MediaServerReference): List<MediaServerEpisodeProgress>

    /** Sidecar subtitle files the server holds for a title, if any. */
    suspend fun subtitles(ref: MediaServerReference, episode: EpisodeContext?): List<ExternalSubtitleTrack>

    /** Tells the server where playback is. Called only from live playback, never from stored progress. */
    suspend fun reportProgress(
        ref: MediaServerReference,
        episode: EpisodeContext?,
        positionMs: Long,
        durationMs: Long,
        state: MediaServerPlaybackState,
    )

    suspend fun setWatched(ref: MediaServerReference, episode: EpisodeContext?, watched: Boolean): Boolean

    suspend fun setSeasonWatched(ref: MediaServerReference, seasonNumber: Int, watched: Boolean): Boolean

    /** Takes a title out of the server's own Continue Watching, as removing its card asks. */
    suspend fun removeFromContinueWatching(ref: MediaServerReference, episode: EpisodeContext?): Boolean

    /** Drops everything cached, for disconnect, sign-out and profile switch. */
    fun reset()
}

enum class MediaServerSort { RecentlyAdded, Title, ReleaseDate }

/**
 * The words a provider puts on rows and sources, supplied by the app so they follow the chosen
 * language. A provider never holds English of its own that a viewer would read.
 */
interface MediaServerLabels {
    fun recentlyAdded(library: String): String
    fun recentlyWatched(library: String): String
    fun collections(library: String): String
    fun directPlay(): String
    fun directStream(): String
    fun transcode(quality: String): String
    /** "Plex", or "Plex · Living Room" when the profile uses more than one server. */
    fun attribution(provider: String, serverName: String, multipleServers: Boolean): String
}

/** What the device and the viewer's settings allow, for [MediaServerProvider.streams]. */
data class MediaServerPlaybackContext(
    /** "Auto", "ExoPlayer" or "MPV" - the player engine setting. */
    val engine: String,
    /** Upper bound for anything that is not on the local network, in kbps. Null means original quality. */
    val remoteMaxBitrateKbps: Int?,
    val deviceName: String,
    val clientIdentifier: String,
    val appVersion: String,
)
