package com.streamdek.tv.nativeapp.data

import com.streamdek.tv.nativeapp.mediaserver.JELLYFIN_PROVIDER_ID
import com.streamdek.tv.nativeapp.mediaserver.MediaServerReference
import com.streamdek.tv.nativeapp.mediaserver.MediaServerResume
import com.streamdek.tv.nativeapp.mediaserver.PLEX_PROVIDER_ID

/**
 * Where a media server's in-progress titles are shown, chosen for Plex and for Jellyfin apart -
 * the same setting, with the same keys and values, as the phone and the web portal.
 *
 * It decides display and nothing else. Playback through a server is reported to that server in
 * both, so its own Continue Watching, watched state and resume points stay right, and switching
 * never deletes a position: the server keeps every one.
 */
enum class MediaServerContinueLocation(val key: String) {
    /** Folded into StreamDek's Continue Watching as well as the server's page. The default: what the app has always done. */
    StreamDek("streamdek"),

    /** Only the server's page has them. */
    ServerLibrary("server"),
    ;

    companion object {
        fun fromKey(key: String?): MediaServerContinueLocation = entries.firstOrNull { it.key == key } ?: StreamDek
    }
}

/** Each provider's choice, read from the profile's synced Home preferences. */
data class MediaServerContinueLocations(
    val plex: MediaServerContinueLocation = MediaServerContinueLocation.StreamDek,
    val jellyfin: MediaServerContinueLocation = MediaServerContinueLocation.StreamDek,
) {
    fun of(provider: String?): MediaServerContinueLocation = when (provider) {
        PLEX_PROVIDER_ID -> plex
        JELLYFIN_PROVIDER_ID -> jellyfin
        else -> MediaServerContinueLocation.StreamDek
    }

    companion object {
        /** Setting keys, under `home`. */
        const val PLEX_KEY = "plexContinueWatchingLocation"
        const val JELLYFIN_KEY = "jellyfinContinueWatchingLocation"

        fun from(home: HomePreferences?): MediaServerContinueLocations = MediaServerContinueLocations(
            plex = MediaServerContinueLocation.fromKey(home?.plexContinueWatchingLocation),
            jellyfin = MediaServerContinueLocation.fromKey(home?.jellyfinContinueWatchingLocation),
        )

        fun keyFor(provider: String): String = if (provider == JELLYFIN_PROVIDER_ID) JELLYFIN_KEY else PLEX_KEY
    }
}

/** The servers' in-progress titles that StreamDek's own Continue Watching should include. */
internal fun List<MediaServerResume>.shownInStreamDek(locations: MediaServerContinueLocations): List<MediaServerResume> =
    filter { locations.of(MediaServerReference.decode(it.item.id)?.provider) == MediaServerContinueLocation.StreamDek }
