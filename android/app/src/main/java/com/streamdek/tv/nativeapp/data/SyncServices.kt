package com.streamdek.tv.nativeapp.data

/**
 * A profile can track what it watches with Trakt, Simkl, MDBList or PunchPlay. The backend mounts
 * the same route shape for each (`/{service}/sync/...`), so the only thing that varies here is
 * which service a profile has chosen and what that service is able to do.
 */
object SyncServiceId {
    const val TRAKT = "trakt"
    const val SIMKL = "simkl"
    const val MDBLIST = "mdblist"
    const val PUNCHPLAY = "punchplay"

    val all: List<String> = listOf(TRAKT, SIMKL, MDBLIST, PUNCHPLAY)

    /** Unknown or missing values fall back to Trakt, which is what every older profile used. */
    fun normalize(raw: String?): String = when (raw?.trim()?.lowercase()) {
        SIMKL -> SIMKL
        MDBLIST -> MDBLIST
        PUNCHPLAY -> PUNCHPLAY
        else -> TRAKT
    }

    fun label(service: String): String = when (normalize(service)) {
        SIMKL -> "Simkl"
        MDBLIST -> "MDBList"
        PUNCHPLAY -> "PunchPlay"
        else -> "Trakt"
    }
}

/**
 * What a service can actually serve, mirroring the backend's capability table. MDBList is a list
 * manager with no concept of a playback position, so asking it for resume points is pointless
 * rather than merely unsuccessful.
 */
data class SyncServiceCapabilities(
    val watchlist: Boolean,
    val watchlistWrite: Boolean,
    val playback: Boolean,
    /** Trakt alone accepts scrobbles, watched history and comments. */
    val traktOnlyFeatures: Boolean,
) {
    companion object {
        fun of(service: String): SyncServiceCapabilities = when (SyncServiceId.normalize(service)) {
            SyncServiceId.SIMKL -> SyncServiceCapabilities(
                watchlist = true,
                watchlistWrite = true,
                playback = true,
                traktOnlyFeatures = false,
            )
            SyncServiceId.MDBLIST -> SyncServiceCapabilities(
                watchlist = true,
                watchlistWrite = true,
                playback = false,
                traktOnlyFeatures = false,
            )
            SyncServiceId.PUNCHPLAY -> SyncServiceCapabilities(
                watchlist = true,
                watchlistWrite = true,
                playback = true,
                traktOnlyFeatures = false,
            )
            else -> SyncServiceCapabilities(
                watchlist = true,
                watchlistWrite = true,
                playback = true,
                traktOnlyFeatures = true,
            )
        }
    }
}
