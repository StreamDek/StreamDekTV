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

    /**
     * StreamDek's own sync.
     *
     * Deliberately absent from [all]: that list drives connection status and the fan-out to
     * accounts the viewer has linked, and SyncDek is linked to nothing -- it is on whenever they
     * are signed in. It is a value the primary-source setting can hold, not a service to connect.
     */
    const val SYNCDEK = "syncdek"

    val all: List<String> = listOf(TRAKT, SIMKL, MDBLIST, PUNCHPLAY)

    /** Unknown or missing values fall back to Trakt, which is what every older profile used. */
    fun normalize(raw: String?): String = when (raw?.trim()?.lowercase()) {
        SIMKL -> SIMKL
        MDBLIST -> MDBLIST
        PUNCHPLAY -> PUNCHPLAY
        SYNCDEK -> SYNCDEK
        else -> TRAKT
    }

    fun label(service: String): String = when (normalize(service)) {
        SIMKL -> "Simkl"
        MDBLIST -> "MDBList"
        PUNCHPLAY -> "PunchPlay"
        SYNCDEK -> "SyncDek"
        else -> "Trakt"
    }
}

/**
 * The one service whose paused sessions feed Continue Watching, or null for none.
 *
 * SyncDek's positions arrive through `/sync/library` whichever service is selected, so a SyncDek
 * profile needs no provider at all. There is no fallback: an unconnected or failing selection
 * leaves the row to SyncDek, the same as StreamDek Mobile.
 */
internal fun continueWatchingPlaybackService(primary: String?, isConnected: (String) -> Boolean): String? {
    val service = SyncServiceId.normalize(primary)
    if (service == SyncServiceId.SYNCDEK || !SyncServiceCapabilities.of(service).playback) return null
    return service.takeIf(isConnected)
}

/**
 * The one service whose watchlist Library shows, or null for none.
 *
 * SyncDek is always available to a signed-in profile. Any other selection must be connected and able
 * to keep a watchlist; there is no fallback to Trakt, the same as StreamDek Mobile.
 */
internal fun watchlistReadService(primary: String?, isConnected: (String) -> Boolean): String? {
    val service = SyncServiceId.normalize(primary)
    if (service == SyncServiceId.SYNCDEK) return service
    if (!SyncServiceCapabilities.of(service).watchlist) return null
    return service.takeIf(isConnected)
}

/** Trakt's watched history counts toward Next Up and New Episodes only for a profile that chose Trakt. */
internal fun traktHistoryApplies(primary: String?): Boolean = SyncServiceId.normalize(primary) == SyncServiceId.TRAKT

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
                // It does keep paused sessions -- /sync/playback -- which this once assumed it
                // did not. The backend reads them, so it can drive Continue Watching like the rest.
                playback = true,
                traktOnlyFeatures = false,
            )
            SyncServiceId.PUNCHPLAY -> SyncServiceCapabilities(
                watchlist = true,
                watchlistWrite = true,
                playback = true,
                traktOnlyFeatures = false,
            )
            // SyncDek keeps the watchlist. Continue Watching does not come through this path for
            // it -- the resume rows are read straight from /sync/library -- so playback is false
            // here to stop anything asking a provider route that does not exist.
            SyncServiceId.SYNCDEK -> SyncServiceCapabilities(
                watchlist = true,
                watchlistWrite = true,
                playback = false,
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
