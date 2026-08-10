package com.streamdek.tv.nativeapp.data

import com.google.gson.JsonObject
import com.streamdek.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.LinkedHashMap
import java.util.Locale
import java.net.URLEncoder
import java.time.Instant

// Stremio-native catalog types that represent live content. Native 'tv' means
// live television channels — series catalogs use 'series'.
private val LIVE_ADDON_CATALOG_TYPES = setOf(
    "tv", "channel", "channels", "event", "events", "live", "sport", "sports", "other",
)

private const val MAX_ADDON_RAIL_TITLE_LENGTH = 30

/** Add-on rows are identified by prefix so they can be ordered as a group. */
private const val ADDON_RAIL_PREFIX = "addon:"

/** Display order of the Home slots, independent of the order they finish loading in. */
private val HOME_SLOT_ORDER = listOf(
    "continue-watching",
    "popular-movies",
    "popular-series",
    "trending",
    "recently-added",
    "networks",
    "recommended",
    "addon-catalogs",
)

/**
 * Resolved playback URLs (debrid links, addon direct links) expire quickly, so cached
 * candidates are only reused for a short window before being re-resolved.
 */
private const val RESOLVED_PLAYBACK_CACHE_TTL_MS = 3 * 60_000L
internal fun effectiveRememberedStreamKey(
    explicitKey: String?,
    storedKey: String?,
    rememberLastSource: Boolean,
): String? = explicitKey ?: storedKey.takeIf { rememberLastSource }


internal fun playbackRequestFromHandoff(payload: PlaybackHandoffPayload): PlaybackRequest {
    require(payload.mediaId.isNotBlank()) { "The handoff did not include a media id." }
    val episode = if (payload.seasonNumber != null && payload.episodeNumber != null) {
        EpisodeContext(payload.seasonNumber, payload.episodeNumber, title = payload.episodeTitle)
    } else {
        null
    }
    val stream = payload.stream
    return PlaybackRequest(
        mediaId = payload.mediaId,
        mediaType = payload.mediaType,
        imdbId = payload.imdbId,
        episode = episode,
        title = payload.title,
        selectedStreamKey = null,
        selectedStreamLabel = payload.sourceLabel ?: payload.quality,
        selectedStream = stream,
        availableStreams = listOf(stream),
        directStreamUrl = stream.url,
        requestHeaders = stream.requestHeaders,
        startPositionSec = payload.positionSeconds.coerceAtLeast(0.0),
        returnToDetailOnBack = false,
    )
}
/**
 * Raised when a screen has nothing to show *and* the backend could not be reached, so the UI can
 * offer a retry instead of presenting an outage as an empty catalog.
 */
class ContentUnavailableException(
    message: String = "StreamDek could not be reached. Check the connection and try again.",
) : Exception(message)

private data class AddonCatalogCollection(
    val addonId: String,
    val addonName: String,
    val rawType: String,
    val catalogId: String,
    val catalogName: String?,
    val items: List<MediaItem>,
)

/** Maps a Stremio-native catalog type to the app-internal type, or null when unsupported. */
fun mapAddonCatalogType(rawType: String): String? = when {
    rawType == "movie" -> "movie"
    rawType == "series" -> "tv"
    rawType in LIVE_ADDON_CATALOG_TYPES -> "live"
    else -> null
}

private fun truncateAtWordBoundary(text: String, maxLength: Int): String {
    if (text.length <= maxLength) return text
    val cut = text.take(maxLength - 1)
    val lastSpace = cut.lastIndexOf(' ')
    val trimmed = if (lastSpace > maxLength / 2) cut.take(lastSpace) else cut
    return trimmed.trimEnd() + "…"
}

fun buildAddonRailTitle(addonName: String, catalogName: String?): String {
    val addon = addonName.trim()
    val catalog = catalogName?.trim().orEmpty()
    if (catalog.isBlank()) return truncateAtWordBoundary(addon, MAX_ADDON_RAIL_TITLE_LENGTH)
    // Skip the addon prefix when the catalog name already identifies it.
    if (addon.isBlank() || catalog.contains(addon, ignoreCase = true)) {
        return truncateAtWordBoundary(catalog, MAX_ADDON_RAIL_TITLE_LENGTH)
    }
    val combined = "$addon - $catalog"
    if (combined.length <= MAX_ADDON_RAIL_TITLE_LENGTH) return combined
    // Prefer the more descriptive catalog name over a truncated combination.
    return truncateAtWordBoundary(catalog, MAX_ADDON_RAIL_TITLE_LENGTH)
}

private fun buildLiveRailTitle(rawType: String, catalogName: String?): String {
    val catalog = catalogName?.trim().orEmpty()
    if (catalog.isNotBlank()) {
        return truncateAtWordBoundary(catalog, MAX_ADDON_RAIL_TITLE_LENGTH)
    }
    return when (rawType) {
        "sport", "sports" -> "Sports"
        "event", "events" -> "Live Events"
        else -> "Live TV"
    }
}

class StreamDekRepository(
    private val sessionStore: AuthSessionStore,
    private val api: StreamDekApi = StreamDekApi(sessionStore),
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val detailsCache = lruCache<String, MediaDetail>(48)
    private val seasonCache = lruCache<String, SeasonDetail>(32)
    private val homeCache = lruCache<String, HomeContent>(4)
    private val libraryCache = lruCache<String, LibraryResponse>(4)
    private val searchCache = lruCache<String, List<MediaItem>>(16)
    private val networkCache = lruCache<String, PagedRailResponse>(12)
    private val genreCache = lruCache<String, List<GenreItem>>(8)
    private val resolvedPlaybackCache = lruCache<String, ResolvedPlaybackCandidate>(16)
    private val resolvedPlaybackCacheTimes = lruCache<String, Long>(32)

    /**
     * Client used to talk to Stremio addons directly (bypassing the backend), mirroring the
     * mobile app's fresh-stream fetch used when a cached addon link has expired.
     */
    private val directStreamClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val episodeSegmentCache = lruCache<String, List<PlaybackSegment>>(32)
    private val watchedHistoryCache = lruCache<String, Set<String>>(4)
    private val bootstrapState = MutableStateFlow<AccountBootstrap?>(null)
    /** Prevent an older bootstrap response from publishing after a newer settings mutation. */
    private val bootstrapRefreshMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * The active profile's stored blob exactly as the backend holds it. Kept raw because it also
     * carries keys this client does not model — live favourites, mobile-only layout choices — and
     * a write has to hand the whole thing back without dropping them.
     */
    private val profilePreferencesState = MutableStateFlow(JsonObject())
    private val fusionBadgeSourcesState = MutableStateFlow<Map<String, FusionBadgeSource>>(emptyMap())
    private val favouriteChannelsState = MutableStateFlow(sessionStore.loadFavouriteChannels())
    private var lastPlaybackRequest: PlaybackRequest? = null

    val session: StateFlow<AuthSession?> = sessionStore.session
    val bootstrap: StateFlow<AccountBootstrap?> = bootstrapState

    /** Whether the backend is answering, and whether what is on screen came out of the cache. */
    val reachability: StateFlow<ApiReachability> = api.reachability

    /** Raised once the backend rejects the stored credentials, so the shell can ask for sign-in. */
    val sessionExpired: StateFlow<Boolean> = api.sessionExpired

    val fusionBadgeSources: StateFlow<Map<String, FusionBadgeSource>> = fusionBadgeSourcesState
    val favouriteChannels: StateFlow<List<MediaItem>> = favouriteChannelsState

    suspend fun fetchPendingHandoff(): PlaybackHandoff? =
        api.get<PlaybackHandoffEnvelope>("/handoffs/pending")?.handoff

    suspend fun acknowledgeHandoff(id: String, status: String): Boolean =
        api.post<PlaybackHandoffAck>("/handoffs/$id/ack", mapOf("status" to status))?.success == true

    fun acceptHandoff(handoff: PlaybackHandoff): PlaybackRequest {
        handoff.profileId?.takeIf { profileId ->
            bootstrapState.value?.streamProfiles.orEmpty().any { it.id == profileId }
        }?.let(::setActiveStreamProfile)
        val decryptedJson = HandoffCrypto.decryptPayload(handoff.encryptedPayload)
        val payload = api.gson.fromJson(decryptedJson, PlaybackHandoffPayload::class.java)
            ?: throw IllegalArgumentException("The handoff payload could not be read.")
        return playbackRequestFromHandoff(payload)
    }
    fun isFavouriteChannel(item: MediaItem): Boolean = favouriteChannelsState.value.any {
        it.id == item.id && it.sourceAddonId == item.sourceAddonId
    }

    fun toggleFavouriteChannel(item: MediaItem) {
        if (item.type != "live") return
        val current = favouriteChannelsState.value.toMutableList()
        val index = current.indexOfFirst { it.id == item.id && it.sourceAddonId == item.sourceAddonId }
        if (index >= 0) current.removeAt(index) else current.add(0, item)
        sessionStore.saveFavouriteChannels(current)
        favouriteChannelsState.value = current
        syncFavouriteChannels(current)
    }

    private fun reloadFavouriteChannels() {
        favouriteChannelsState.value = sessionStore.loadFavouriteChannels()
    }

    private fun syncFavouriteChannels(items: List<MediaItem>) {
        val profileId = sessionStore.activeProfileId() ?: return
        if (currentSession() == null) return
        repositoryScope.launch {
            val result = api.put<LiveFavouriteChannelsEnvelope>(
                "/profiles/${URLEncoder.encode(profileId, "UTF-8")}/live-favourites",
                mapOf("items" to items),
            )
            if (result == null) {
                TvDebugLogger.w("LiveFavourites", "Cloud sync failed; local favourites retained")
            } else {
                rememberFavouritesInProfileBlob(result)
            }
        }
    }

    /**
     * Favourites live inside the same profile blob as the settings, and a settings write has to
     * resend that blob whole. Keeping the cached copy current stops a settings change made after
     * a favourite was toggled from putting the old list back.
     */
    private fun rememberFavouritesInProfileBlob(envelope: LiveFavouriteChannelsEnvelope) {
        val blob = profilePreferencesState.value.deepCopy()
        blob.add(
            "liveFavouriteChannels",
            api.gson.toJsonTree(mapOf("items" to envelope.items, "updatedAt" to envelope.updatedAt)),
        )
        profilePreferencesState.value = blob
    }

    private suspend fun refreshFavouriteChannelsFromCloud() {
        val profileId = sessionStore.activeProfileId() ?: return
        if (currentSession() == null) return
        val local = sessionStore.loadFavouriteChannels()
        val cloud = api.get<LiveFavouriteChannelsEnvelope>(
            "/profiles/${URLEncoder.encode(profileId, "UTF-8")}/live-favourites",
        ) ?: return
        if (cloud.updatedAt > 0L) {
            sessionStore.saveFavouriteChannels(cloud.items)
            favouriteChannelsState.value = cloud.items
        } else if (local.isNotEmpty()) {
            api.put<LiveFavouriteChannelsEnvelope>(
                "/profiles/${URLEncoder.encode(profileId, "UTF-8")}/live-favourites",
                mapOf("items" to local),
            )
        }
    }
    fun currentSession(): AuthSession? = sessionStore.currentSession()

    fun savePlaybackRequest(request: PlaybackRequest) {
        lastPlaybackRequest = request
    }

    fun currentPlaybackRequest(): PlaybackRequest? = lastPlaybackRequest

    fun playerBrightnessPercent(): Int = sessionStore.playerBrightnessPercent()

    fun savePlayerBrightnessPercent(percent: Int) = sessionStore.savePlayerBrightnessPercent(percent)

    fun consumePlaybackRequest(): PlaybackRequest? = lastPlaybackRequest

    fun peekCachedDetail(id: String, type: String): MediaDetail? {
        val cacheKey = "$type:$id"
        return detailsCache[cacheKey]
    }

    fun peekCachedResolvedPlayback(request: PlaybackRequest): ResolvedPlaybackCandidate? {
        val cacheKey = playbackCacheKey(
            mediaType = request.mediaType,
            mediaId = request.mediaId,
            imdbId = request.imdbId,
            episode = request.episode,
            preferredStreamKey = request.selectedStreamKey,
            streamType = request.streamType,
        )
        return readResolvedPlaybackCache(cacheKey)
    }

    private fun readResolvedPlaybackCache(cacheKey: String): ResolvedPlaybackCandidate? {
        val cached = resolvedPlaybackCache[cacheKey] ?: return null
        val storedAt = resolvedPlaybackCacheTimes[cacheKey] ?: 0L
        if (System.currentTimeMillis() - storedAt > RESOLVED_PLAYBACK_CACHE_TTL_MS) {
            resolvedPlaybackCache.remove(cacheKey)
            resolvedPlaybackCacheTimes.remove(cacheKey)
            return null
        }
        return cached
    }

    private fun writeResolvedPlaybackCache(cacheKey: String, candidate: ResolvedPlaybackCandidate) {
        resolvedPlaybackCache[cacheKey] = candidate
        resolvedPlaybackCacheTimes[cacheKey] = System.currentTimeMillis()
    }

    suspend fun signIn(email: String, password: String): AuthSession {
        val response = api.post<AuthResponse>("/auth/login", mapOf("email" to email, "password" to password), session = null)
            ?: error("Sign in failed")
        val session = persistSession(response)
        refreshBootstrap()
        return session
    }

    suspend fun register(email: String, password: String, displayName: String): AuthSession {
        val response = api.post<AuthResponse>(
            "/auth/register",
            mapOf("email" to email, "password" to password, "displayName" to displayName),
            session = null,
        ) ?: error("Sign up failed")
        val session = persistSession(response)
        refreshBootstrap()
        return session
    }

    suspend fun createTvSession(): TvSessionInfo {
        TvDebugLogger.i("Auth", "createTvSession")
        return api.post<TvSessionInfo>("/auth/tv/session", emptyMap<String, String>(), session = null)
            ?: error("Could not create TV sign-in session")
    }

    suspend fun pollTvSession(deviceCode: String): TvPollResult {
        val result = api.post<TvPollResult>("/auth/tv/token", mapOf("device_code" to deviceCode), session = null)
            ?: TvPollResult(status = "invalid_grant")
        TvDebugLogger.i("Auth", "pollTvSession status=${result.status}")
        return result
    }

    suspend fun completeTvSession(result: TvPollResult): AuthSession {
        val token = result.token ?: error("Missing TV auth token")
        val session = AuthSession(
            token = token,
            user = normalizeUser(result.user, token),
        )
        sessionStore.saveSession(session)
        TvDebugLogger.i("Auth", "completeTvSession user=${session.user.uid}")
        runCatching { refreshBootstrap() }
        return session
    }

    fun signOut() {
        sessionStore.clearSession()
        bootstrapState.value = null
        fusionBadgeSourcesState.value = emptyMap()
        reloadFavouriteChannels()
        detailsCache.clear()
        seasonCache.clear()
        homeCache.clear()
        libraryCache.clear()
        searchCache.clear()
        networkCache.clear()
        genreCache.clear()
        resolvedPlaybackCache.clear()
        resolvedPlaybackCacheTimes.clear()
        watchedHistoryCache.clear()
        profilePreferencesState.value = JsonObject()
        StreamDekHttp.evictCache()
        api.clearSessionExpired()
    }

    suspend fun refreshBootstrap(): AccountBootstrap? = bootstrapRefreshMutex.withLock {
        val session = currentSession() ?: run {
            bootstrapState.value = null
            TvDebugLogger.w("Bootstrap", "refreshBootstrap skipped: no session")
            return@withLock null
        }
        TvDebugLogger.i("Bootstrap", "refreshBootstrap start user=${session.user.uid}")
        var bootstrap = fetchBootstrap(session)
        if (bootstrap != null) {
            val activeProfileId = sessionStore.activeProfileId()
            if (activeProfileId.isNullOrBlank()) {
                val preferredProfileId = bootstrap.streamProfiles
                    .firstOrNull { it.isDefault }
                    ?.id
                    ?: bootstrap.streamProfiles.firstOrNull()?.id
                if (!preferredProfileId.isNullOrBlank()) {
                    sessionStore.setActiveProfileId(preferredProfileId)
                    TvDebugLogger.i("Bootstrap", "selected initial profile=$preferredProfileId")
                    // Re-read so the profile-scoped overrides for the profile just picked are applied.
                    bootstrap = fetchBootstrap(session) ?: bootstrap
                }
            }
            TvDebugLogger.i(
                "Bootstrap",
                "refreshBootstrap ok profiles=${bootstrap.streamProfiles.size} devices=${bootstrap.devices.size} sessions=${bootstrap.sessions.size}",
            )
        } else {
            TvDebugLogger.w("Bootstrap", "refreshBootstrap returned null")
        }
        bootstrapState.value = bootstrap
        reloadFavouriteChannels()
        refreshFavouriteChannelsFromCloud()
        return@withLock bootstrap
    }

    /**
     * Reads the bootstrap and folds the active profile's overrides into `preferences` before the
     * payload is typed, so every screen keeps reading `bootstrap.preferences` and gets the answer
     * for the profile actually in use.
     */
    private suspend fun fetchBootstrap(session: AuthSession): AccountBootstrap? {
        val raw = api.get<JsonObject>("/account/bootstrap", session) ?: return null
        val profilePreferences = raw.asObjectOrNull("profilePreferences") ?: JsonObject()
        profilePreferencesState.value = profilePreferences
        val accountPreferences = raw.asObjectOrNull("preferences") ?: JsonObject()
        raw.add("preferences", PreferenceScopes.mergeIntoAccountPreferences(accountPreferences, profilePreferences))
        return runCatching { api.gson.fromJson(raw, AccountBootstrap::class.java) }
            .onFailure { TvDebugLogger.e("Bootstrap", "could not read bootstrap payload", it) }
            .getOrNull()
    }

    suspend fun updatePlaybackPreferences(partial: Map<String, Any?>): AccountBootstrap? {
        val existing = bootstrapState.value?.preferences?.playback ?: PlaybackPreferences()
        if (!patchPreferences(
            mapOf(
                "playback" to mapOf(
                    "autoplayNextEpisode" to (partial["autoplayNextEpisode"] ?: existing.autoplayNextEpisode),
                    "preferredQuality" to (partial["preferredQuality"] ?: existing.preferredQuality),
                    "maxFileSizeGB" to (partial["maxFileSizeGB"] ?: existing.maxFileSizeGB),
                    "streamingServer" to (partial["streamingServer"] ?: existing.streamingServer),
                    "defaultSubtitleLanguage" to (partial["defaultSubtitleLanguage"] ?: existing.defaultSubtitleLanguage),
                    "defaultAudioLanguage" to (partial["defaultAudioLanguage"] ?: existing.defaultAudioLanguage),
                    "externalPlayerEnabled" to (partial["externalPlayerEnabled"] ?: existing.externalPlayerEnabled),
                    "preferEmbeddedMpvByDefault" to (partial["preferEmbeddedMpvByDefault"] ?: existing.preferEmbeddedMpvByDefault),
                    "skipSegmentsEnabled" to (partial["skipSegmentsEnabled"]
                        ?: listOf(
                            partial["skipIntroEnabled"] as? Boolean ?: existing.isSegmentEnabled("intro"),
                            partial["skipRecapEnabled"] as? Boolean ?: existing.isSegmentEnabled("recap"),
                            partial["skipEndingEnabled"] as? Boolean ?: existing.isSegmentEnabled("outro"),
                        ).any { it }),
                    "skipIntroEnabled" to (partial["skipIntroEnabled"] ?: existing.isSegmentEnabled("intro")),
                    "skipRecapEnabled" to (partial["skipRecapEnabled"] ?: existing.isSegmentEnabled("recap")),
                    "skipEndingEnabled" to (partial["skipEndingEnabled"] ?: existing.isSegmentEnabled("outro")),
                    "autoPlayNextEpisodeEnabled" to (partial["autoPlayNextEpisodeEnabled"]
                        ?: partial["autoplayNextEpisode"]
                        ?: existing.isAutoPlayNextEpisodeEnabled()),
                    "preferBingeGroupNextEpisode" to (partial["preferBingeGroupNextEpisode"] ?: existing.preferBingeGroupNextEpisode),
                    "autoLoadSubtitles" to (partial["autoLoadSubtitles"] ?: existing.autoLoadSubtitles),
                    "nextEpisodeThresholdMode" to (partial["nextEpisodeThresholdMode"] ?: existing.nextEpisodeThresholdMode),
                    "nextEpisodeThresholdPercent" to (partial["nextEpisodeThresholdPercent"] ?: existing.nextEpisodeThresholdPercent),
                    "nextEpisodeThresholdMinutes" to (partial["nextEpisodeThresholdMinutes"] ?: existing.nextEpisodeThresholdMinutes),
                    "decoderMode" to (partial["decoderMode"] ?: existing.decoderMode),
                    "renderSurface" to (partial["renderSurface"] ?: existing.renderSurface),
                    "playerEngine" to (partial["playerEngine"] ?: existing.playerEngine),
                    "rememberLastSource" to (partial["rememberLastSource"] ?: existing.rememberLastSource),
                    "manualStreamSelectionEnabled" to (partial["manualStreamSelectionEnabled"] ?: existing.manualStreamSelectionEnabled),
                    "liveProgressBarEnabled" to (partial["liveProgressBarEnabled"] ?: existing.liveProgressBarEnabled),
                ),
            ),
        )) return null
        return refreshBootstrap()
    }

    suspend fun updateAppPreferences(partial: Map<String, Any?>): AccountBootstrap? {
        val existing = bootstrapState.value?.preferences?.app ?: AppPreferences()
        if (!patchPreferences(
            mapOf(
                "app" to mapOf(
                    "theme" to (partial["theme"] ?: existing.theme),
                    "colorMode" to (partial["colorMode"] ?: existing.colorMode),
                    "startScreen" to (partial["startScreen"] ?: existing.startScreen),
                    "homeRowCardStyle" to (partial["homeRowCardStyle"] ?: existing.homeRowCardStyle),
                    "compactMode" to (partial["compactMode"] ?: existing.compactMode),
                    "syncOverCellular" to (partial["syncOverCellular"] ?: existing.syncOverCellular),
                    "cardDensity" to (partial["cardDensity"] ?: existing.cardDensity),
                    "animationSpeed" to (partial["animationSpeed"] ?: existing.animationSpeed),
                    "navigationStyle" to (partial["navigationStyle"] ?: existing.navigationStyle),
                    "gridSize" to (partial["gridSize"] ?: existing.gridSize),
                    "backgroundBlur" to (partial["backgroundBlur"] ?: existing.backgroundBlur),
                    "highContrast" to (partial["highContrast"] ?: existing.highContrast),
                    "largeText" to (partial["largeText"] ?: existing.largeText),
                    "reducedMotion" to (partial["reducedMotion"] ?: existing.reducedMotion),
                    "hideHomeSynopsis" to (partial["hideHomeSynopsis"] ?: existing.hideHomeSynopsis),
                    "transparentNavigation" to (partial["transparentNavigation"] ?: existing.transparentNavigation),
                ),
            ),
        )) return null
        return refreshBootstrap()
    }

    suspend fun updateHomePreferences(partial: Map<String, Any?>): AccountBootstrap? {
        val existing = bootstrapState.value?.preferences?.home ?: HomePreferences()
        if (!patchPreferences(
            mapOf(
                "home" to mapOf(
                    "primarySyncService" to (partial["primarySyncService"] ?: existing.primarySyncService),
                    "defaultAppCatalogsEnabled" to (partial["defaultAppCatalogsEnabled"] ?: existing.defaultAppCatalogsEnabled),
                    "continueWatchingStyle" to (partial["continueWatchingStyle"] ?: existing.continueWatchingStyle),
                    "liveCategoriesEnabled" to (partial["liveCategoriesEnabled"] ?: existing.liveCategoriesEnabled),
                    "liveLandscapeCards" to (partial["liveLandscapeCards"] ?: existing.liveLandscapeCards),
                    "liveFavouriteDrawerCards" to (partial["liveFavouriteDrawerCards"] ?: existing.liveFavouriteDrawerCards),
                    "showHeroSynopsis" to (partial["showHeroSynopsis"] ?: existing.showHeroSynopsis),
                    "detailPageStyle" to (partial["detailPageStyle"] ?: existing.detailPageStyle),
                    "vividAmbient" to (partial["vividAmbient"] ?: existing.vividAmbient),
                    "ambientTintPercent" to (partial["ambientTintPercent"] ?: existing.ambientTintPercent),
                    "homeCatalogRows" to (partial["homeCatalogRows"] ?: existing.homeCatalogRows),
                ),
            ),
        )) return null
        homeCache.clear()
        return refreshBootstrap()
    }

    suspend fun updateStreamsPreferences(partial: Map<String, Any?>): AccountBootstrap? {
        val existing = bootstrapState.value?.preferences?.streams ?: StreamsPreferences()
        if (!patchPreferences(
            mapOf(
                "streams" to mapOf(
                    "fusionBadgesEnabled" to (partial["fusionBadgesEnabled"] ?: existing.fusionBadgesEnabled),
                    "showSizeBadges" to (partial["showSizeBadges"] ?: existing.showSizeBadges),
                    "badgePosition" to (partial["badgePosition"] ?: existing.badgePosition),
                    "fusionBadgeUrls" to (partial["fusionBadgeUrls"] ?: existing.fusionBadgeUrls),
                    "activeFusionBadgeUrl" to (if (partial.containsKey("activeFusionBadgeUrl")) partial["activeFusionBadgeUrl"] else existing.activeFusionBadgeUrl),
                    // Carried through untouched so writing a badge setting from the TV does not
                    // blank out the stream-picker keys the other clients own.
                    "showStreamsList" to (partial["showStreamsList"] ?: existing.showStreamsList),
                    "rememberLastSource" to (partial["rememberLastSource"] ?: existing.rememberLastSource),
                    "blurUnwatchedEpisodes" to (partial["blurUnwatchedEpisodes"] ?: existing.blurUnwatchedEpisodes),
                    "streamDekFormattingEnabled" to (partial["streamDekFormattingEnabled"] ?: existing.streamDekFormattingEnabled),
                    "showAddonTmdbRatings" to (partial["showAddonTmdbRatings"] ?: existing.showAddonTmdbRatings),
                ),
            ),
        )) return null
        return refreshBootstrap()
    }

    suspend fun fetchFusionBadgeSource(url: String, forceRefresh: Boolean = false): FusionBadgeSource? {
        val encodedUrl = URLEncoder.encode(url, "UTF-8")
        val path = "/badges/fusion-source?url=$encodedUrl" + if (forceRefresh) "&refresh=true" else ""
        val source = api.get<FusionBadgeSource>(path) ?: return null
        fusionBadgeSourcesState.value = fusionBadgeSourcesState.value + (url to source)
        return source
    }

    suspend fun ensureFusionBadgeSourcesLoaded(forceRefresh: Boolean = false) {
        val streamsPrefs = bootstrapState.value?.preferences?.streams ?: StreamsPreferences()
        if (!streamsPrefs.fusionBadgesEnabled) return
        val urls = streamsPrefs.fusionBadgeUrls.take(MAX_FUSION_BADGE_URLS).filter { it.isNotBlank() }
        supervisorScope {
            urls.map { url ->
                async {
                    if (forceRefresh || fusionBadgeSourcesState.value[url] == null) {
                        runCatching { fetchFusionBadgeSource(url, forceRefresh) }
                    }
                }
            }.forEach { it.await() }
        }
    }

    fun removeFusionBadgeSource(url: String) {
        fusionBadgeSourcesState.value = fusionBadgeSourcesState.value - url
    }

    suspend fun fetchAddonManifests(forceRefresh: Boolean = false): List<AddonManifest> {
        val fetched = api.get<List<AddonManifest>>(
            "/addons/manifests" + if (forceRefresh) "?refresh=true" else "",
        )
        if (fetched != null) applyAddonSnapshot(fetched)
        return fetched ?: bootstrapState.value?.integrations?.addons?.items.orEmpty()
    }

    suspend fun toggleAddon(id: String, enabled: Boolean): Boolean {
        val response = api.post<JsonObject>("/addons/toggle", mapOf("id" to id, "enabled" to enabled))
            ?: return false
        val saved = response.get("success")?.asBoolean == true ||
            response.has("id") || response.has("enabled")
        if (!saved) return false
        homeCache.clear()
        refreshBootstrap()
        return true
    }

    suspend fun updateProfilePlugins(state: ProfilePluginState): AccountBootstrap? {
        val profileId = sessionStore.activeProfileId()?.takeIf { it.isNotBlank() } ?: return null
        val next = state.copy(updatedAt = System.currentTimeMillis())
        val response = api.put<JsonObject>(
            "/profiles/${URLEncoder.encode(profileId, "UTF-8")}/plugins",
            mapOf("plugins" to next),
        ) ?: return null
        if (response.get("success")?.asBoolean != true) return null
        return refreshBootstrap()
    }

    private fun applyAddonSnapshot(addons: List<AddonManifest>) {
        val current = bootstrapState.value ?: return
        bootstrapState.value = current.copy(
            integrations = current.integrations.copy(
                addons = current.integrations.addons.copy(items = addons),
            ),
        )
    }

    suspend fun setDebridAccountEnabled(provider: String, enabled: Boolean): Boolean {
        val encoded = URLEncoder.encode(provider, "UTF-8")
        val response = api.patch<JsonObject>(
            "/debrid/accounts/$encoded",
            mapOf("enabled" to enabled),
        ) ?: return false
        refreshBootstrap()
        return response.get("success")?.asBoolean == true
    }

    suspend fun uninstallAddon(id: String) {
        api.delete<Map<String, String>>("/addons/uninstall", mapOf("id" to id))
        refreshBootstrap()
    }

    private suspend fun fetchAddonCatalogCollections(
        addonId: String? = null,
        includeCatalog: (rawType: String, mappedType: String) -> Boolean = { _, _ -> true },
    ): List<AddonCatalogCollection> {
        val addons = runCatching { fetchAddonManifests() }.getOrDefault(emptyList())
            .filter { it.enabled && (addonId.isNullOrBlank() || it.id == addonId) }
            .sortedBy { it.position }
        if (addons.isEmpty()) return emptyList()

        return supervisorScope {
            addons.flatMap { addon ->
                addon.manifest.catalogs.mapIndexedNotNull { _, catalog ->
                    val rawType = catalog.type.trim().lowercase(Locale.US)
                    val mappedType = mapAddonCatalogType(rawType) ?: return@mapIndexedNotNull null
                    if (!includeCatalog(rawType, mappedType)) return@mapIndexedNotNull null
                    val catalogId = catalog.id.trim()
                    if (catalogId.isBlank()) return@mapIndexedNotNull null
                    async {
                        val metas = runCatching {
                            api.get<AddonCatalogResponse>(
                                "/addons/${URLEncoder.encode(addon.id, "UTF-8")}/catalog/$rawType/${URLEncoder.encode(catalogId, "UTF-8")}",
                            )?.metas.orEmpty()
                        }.onFailure {
                            TvDebugLogger.w("Home", "addon catalog fetch failed addon=${addon.id} type=$rawType id=$catalogId")
                        }.getOrDefault(emptyList())
                        val items = metas.mapNotNull {
                            normalizeAddonCatalogMeta(
                                meta = it,
                                fallbackType = mappedType,
                                nativeFallbackType = rawType,
                                addonId = addon.id,
                                addonName = addon.manifest.name,
                                catalogId = catalogId,
                                catalogName = catalog.name,
                            )
                        }
                        if (items.isEmpty()) {
                            null
                        } else {
                            AddonCatalogCollection(
                                addonId = addon.id,
                                addonName = addon.manifest.name,
                                rawType = rawType,
                                catalogId = catalogId,
                                catalogName = catalog.name,
                                items = items,
                            )
                        }
                    }
                }
            }.mapNotNull { it.await() }
        }
    }

    /**
     * Builds one home rail per addon catalog. Catalogs are fetched through the
     * backend proxy with the addon's Stremio-native type ('series' for shows,
     * 'tv' for live channels, 'events'/'sport' for live events).
     */
    suspend fun fetchAddonCatalogRails(): List<HomeRail> {
        return fetchAddonCatalogCollections().mapIndexed { index, collection ->
            HomeRail(
                id = "addon:${collection.addonId}:${collection.rawType}:${collection.catalogId}:$index",
                title = buildAddonRailTitle(collection.addonName, collection.catalogName),
                items = collection.items.take(80),
                isLive = mapAddonCatalogType(collection.rawType.lowercase(Locale.US)) == "live",
            )
        }
    }

    suspend fun fetchLiveCatalogSections(): List<LiveCatalogSection> {
        return fetchAddonCatalogCollections { _, mappedType -> mappedType == "live" }
            .groupBy { it.addonId }
            .map { (addonId, collections) ->
                LiveCatalogSection(
                    id = "live:$addonId",
                    title = collections.firstOrNull()?.addonName.orEmpty(),
                    rails = collections.mapIndexed { index, collection ->
                        LiveCatalogRail(
                            id = "live:${collection.addonId}:${collection.rawType}:${collection.catalogId}:$index",
                            title = buildLiveRailTitle(collection.rawType, collection.catalogName),
                            items = collection.items,
                        )
                    },
                )
            }
            .filter { section -> section.rails.any { it.items.isNotEmpty() } }
    }

    suspend fun fetchRelatedLiveChannels(
        addonId: String?,
        catalogId: String?,
    ): List<MediaItem> {
        if (addonId.isNullOrBlank()) return emptyList()
        val collections = fetchAddonCatalogCollections(addonId = addonId) { _, mappedType -> mappedType == "live" }
            .filter { collection ->
                collection.addonId == addonId &&
                    (catalogId.isNullOrBlank() || collection.catalogId == catalogId)
            }
        return collections
            .flatMap { it.items }
            .distinctBy { item -> "${item.sourceAddonId}:${item.streamType}:${item.id}" }
    }

    private fun normalizeAddonCatalogMeta(
        meta: AddonCatalogMetaItem,
        fallbackType: String,
        nativeFallbackType: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String?,
    ): MediaItem? {
        val rawId = meta.id?.trim().orEmpty()
        val tmdbId = meta.movieDbId ?: 0
        val resolvedId = if (tmdbId > 0) tmdbId.toString() else rawId
        if (resolvedId.isBlank()) return null
        val rawNativeType = meta.type?.trim()?.lowercase(Locale.US).orEmpty()
        val mapped = rawNativeType.takeIf { it.isNotBlank() }?.let { mapAddonCatalogType(it) }
        val type = mapped ?: fallbackType
        val nativeType = if (mapped != null) rawNativeType else nativeFallbackType
        return MediaItem(
            id = resolvedId,
            tmdbId = tmdbId,
            title = meta.name.orEmpty(),
            type = type,
            poster = meta.poster,
            backdrop = meta.background ?: meta.poster,
            description = meta.description,
            rating = meta.imdbRating?.toDoubleOrNull(),
            year = meta.releaseInfo?.take(4)?.takeIf { it.toIntOrNull() != null },
            titleLogo = meta.logo,
            streamType = if (type == "live") (nativeType.ifBlank { "tv" }) else null,
            sourceAddonId = addonId,
            sourceAddonName = addonName,
            sourceCatalogId = catalogId,
            sourceCatalogName = catalogName,
            directStreamUrl = directMediaUrl(meta),
            requestHeaders = catalogRequestHeaders(meta),
        )
    }

    private fun directMediaUrl(meta: AddonCatalogMetaItem): String? {
        val behaviorHints = meta.behaviorHints.orEmpty()
        return sequenceOf(meta.url, meta.externalUrl, behaviorHints["url"], behaviorHints["externalUrl"])
            .mapNotNull(::stringUrlValue)
            .firstOrNull()
    }

    private fun stringUrlValue(value: Any?): String? {
        return when (value) {
            is String -> value.trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            is Map<*, *> -> sequenceOf(value["url"], value["href"])
                .mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
                .firstOrNull()
            else -> null
        }
    }

    private fun catalogRequestHeaders(meta: AddonCatalogMetaItem): Map<String, String> {
        val directHeaders = stringMap(meta.headers)
        val proxyHeaders = (meta.behaviorHints?.get("proxyHeaders") as? Map<*, *>)
            ?.get("request") as? Map<*, *>
        return directHeaders + stringMap(proxyHeaders)
    }

    private fun stringMap(source: Map<*, *>?): Map<String, String> = source.orEmpty()
        .mapNotNull { (key, value) ->
            val name = key?.toString()?.trim().orEmpty()
            val content = value?.toString()?.trim().orEmpty()
            if (name.isBlank() || content.isBlank()) null else name to content
        }
        .toMap()

    suspend fun fetchLatestAppRelease(): AppReleaseManifest? {
        val configuredPath = BuildConfig.STREAMDEK_OTA_MANIFEST_PATH.takeIf { it.isNotBlank() }
        val candidatePaths = buildList {
            configuredPath?.let(::add)
            add("/public/updates/android-tv/latest")
            add("/updates/android-tv/latest")
            add("/app/updates/android-tv/latest")
        }.map { raw ->
            if (raw.startsWith("/")) raw else "/$raw"
        }.distinct()

        for (path in candidatePaths) {
            val manifest = api.get<AppReleaseManifest>(path, session = null)
                ?.takeIf { it.versionCode > 0 && it.versionName.isNotBlank() && it.apkUrl.isNotBlank() }
            if (manifest != null) {
                TvDebugLogger.i("Updates", "fetchLatestAppRelease ok path=$path version=${manifest.versionName} code=${manifest.versionCode}")
                return manifest
            }
            TvDebugLogger.w("Updates", "fetchLatestAppRelease unavailable path=$path")
        }

        return null
    }

    /**
     * Home, delivered a row at a time.
     *
     * Building the whole screen before showing any of it meant the slowest source set the speed of
     * the entire app: fanning out to every installed add-on, or one Trakt round trip, held back the
     * TMDB rows that were already in hand. On a Firestick over a slow connection that is several
     * seconds of nothing. Rows now arrive as they resolve, each into a slot reserved from the
     * start, so the layout never reflows underneath the viewer.
     *
     * The last value emitted is the complete screen, which is what gets cached.
     */
    fun homeContentStream(forceRefresh: Boolean = false): Flow<HomeContent> = channelFlow {
        val homePreferences = bootstrapState.value?.preferences?.home
        val addonConfiguration = bootstrapState.value?.integrations?.addons?.items.orEmpty()
            .joinToString("|") { "${it.id}:${it.enabled}:${it.position}" }
        val cacheKey = buildSessionProfileCacheKey() +
            ":${homePreferences?.defaultAppCatalogsEnabled != false}:$addonConfiguration"
        if (!forceRefresh) {
            homeCache[cacheKey]?.let {
                send(it)
                return@channelFlow
            }
        }

        val recommendationsAvailable = isSyncServiceConnected(SyncServiceId.TRAKT)
        val builtInCatalogsEnabled = bootstrapState.value?.preferences?.home?.defaultAppCatalogsEnabled != false
        val failuresBefore = api.failureEpoch

        // Slots are declared up front, in final display order, so a row that resolves late lands
        // where its skeleton already was.
        val pending = linkedMapOf<String, PendingRail>()
        fun reserve(id: String, title: String, portrait: Boolean = false) {
            pending[id] = PendingRail(id, title, portrait)
        }
        reserve("continue-watching", "Continue Watching")
        if (builtInCatalogsEnabled) {
            reserve("popular-movies", "Popular Movies")
            reserve("popular-series", "Popular Series")
            reserve("trending", "Trending")
            reserve("recently-added", "Recently Added")
            reserve("networks", "Streaming Services")
            reserve("recommended", "Recommended For You")
        }
        reserve("addon-catalogs", "Add-on Catalogues")

        val resolved = linkedMapOf<String, List<HomeRail>>()
        val mutex = kotlinx.coroutines.sync.Mutex()

        suspend fun publish(slot: String, rails: List<HomeRail>) {
            val snapshot = mutex.withLock {
                resolved[slot] = rails
                pending.remove(slot)
                // Emit in declared order regardless of which slot finished first.
                val ordered = pending.keys.toList()
                val ready = resolved.keys
                    .sortedBy { key -> HOME_SLOT_ORDER.indexOf(key) }
                    .flatMap { resolved.getValue(it) }
                    .filter { it.items.isNotEmpty() }
                HomeContent(
                    featured = ready.firstOrNull { it.id != "continue-watching" }?.items?.firstOrNull()
                        ?: ready.firstOrNull()?.items?.firstOrNull(),
                    rails = orderHomeRails(ready),
                    pendingRails = ordered.mapNotNull { pending[it] },
                )
            }
            send(snapshot)
        }

        supervisorScope {
            launch {
                val library = runCatching { fetchLibrary() }.getOrDefault(LibraryResponse())
                val items = library.continueWatching.map(::continueWatchingCard)
                publish("continue-watching", listOf(HomeRail("continue-watching", "Continue Watching", items)))
            }

            if (builtInCatalogsEnabled) {
                launch { publishTmdbRails(::publish, recommendationsAvailable) }
            }

            launch {
                val addonRails = runCatching { fetchAddonCatalogRails() }.getOrDefault(emptyList())
                publish("addon-catalogs", addonRails)
            }
        }

        val complete = mutex.withLock {
            val ready = resolved.keys
                .sortedBy { key -> HOME_SLOT_ORDER.indexOf(key) }
                .flatMap { resolved.getValue(it) }
                .filter { it.items.isNotEmpty() }
            HomeContent(
                featured = ready.firstOrNull { it.id != "continue-watching" }?.items?.firstOrNull()
                    ?: ready.firstOrNull()?.items?.firstOrNull(),
                rails = orderHomeRails(ready),
            )
        }

        // Every row is fetched defensively, so a total outage produces an empty screen rather than
        // an error. Without this check the viewer is shown a blank Home with nothing to act on and
        // no hint that anything went wrong.
        if (complete.rails.isEmpty() && api.failureEpoch > failuresBefore) {
            TvDebugLogger.w("Home", "home produced nothing after backend failures")
            throw ContentUnavailableException()
        }

        homeCache[cacheKey] = complete
        send(complete)
    }

    private fun continueWatchingCard(item: ContinueWatchingItem): MediaItem = MediaItem(
        id = item.id,
        tmdbId = item.tmdbId,
        title = item.title,
        type = item.type,
        poster = item.poster,
        backdrop = item.backdrop,
        description = item.description,
        rating = item.rating,
        year = item.year,
        titleLogo = null,
        progress = item.progress,
        positionSec = item.positionSec ?: item.resumeAt,
        durationSec = item.durationSec,
        episode = item.episode,
    )

    /**
     * The TMDB rows. Popular falls back to Trending and Browse falls back to Popular, so these
     * share one coroutine and publish in two waves: the three rows the viewer sees first, then the
     * rest. Splitting them further would not help, since the fallbacks make them interdependent.
     */
    private suspend fun publishTmdbRails(
        publish: suspend (String, List<HomeRail>) -> Unit,
        recommendationsAvailable: Boolean,
    ) = supervisorScope {
        val trendingMovie = async { safeResults<RailResponse>("/tmdb/trending/movie") }
        val trendingTv = async { safeResults<RailResponse>("/tmdb/trending/tv") }
        val popularMovie = async { safeResults<RailResponse>("/tmdb/popular/movie") }
        val popularTv = async { safeResults<RailResponse>("/tmdb/popular/tv") }
        val browseMovie = async { safeResults<RailResponse>("/tmdb/browse/movie") }
        val browseTv = async { safeResults<RailResponse>("/tmdb/browse/tv") }
        val networks = async { safeResults<NetworkResponse>("/tmdb/networks") }
        val recMovie = async {
            if (recommendationsAvailable) safeResults<RailResponse>("/trakt/recommendations/movies") else emptyList()
        }
        val recTv = async {
            if (recommendationsAvailable) safeResults<RailResponse>("/trakt/recommendations/shows") else emptyList()
        }

        val trendingMovies = trendingMovie.await()
        val trendingShows = trendingTv.await()
        val popularMovies = popularMovie.await().ifEmpty { trendingMovies }
        val popularShows = popularTv.await().ifEmpty { trendingShows }

        publish("popular-movies", listOf(HomeRail("popular-movies", "Popular Movies", popularMovies)))
        publish("popular-series", listOf(HomeRail("popular-series", "Popular Series", popularShows)))
        publish("trending", listOf(HomeRail("trending", "Trending", (trendingMovies + trendingShows).take(20))))

        val browseMovies = browseMovie.await().ifEmpty { popularMovies }
        val browseShows = browseTv.await().ifEmpty { popularShows }
        val recentlyAdded = (browseMovies + browseShows)
            .distinctBy { "${it.type}:${it.id}" }
            .sortedByDescending { it.year?.toIntOrNull() ?: 0 }
            .take(20)
        publish("recently-added", listOf(HomeRail("recently-added", "Recently Added", recentlyAdded)))
        publish("networks", listOf(HomeRail("networks", "Streaming Services", networks.await())))

        val recommendedMovies = recMovie.await().ifEmpty { popularMovies }
        val recommendedShows = recTv.await().ifEmpty { popularShows }
        publish(
            "recommended",
            listOf(HomeRail("recommended", "Recommended For You", (recommendedMovies + recommendedShows).take(20))),
        )
    }

    /**
     * Matches the mobile app's ordering: live add-on rows sit directly below Streaming Services,
     * everything else from add-ons goes to the end.
     */
    private fun orderHomeRails(rails: List<HomeRail>): List<HomeRail> {
        val (addonRails, baseRails) = rails.partition { it.id.startsWith(ADDON_RAIL_PREFIX) }
        val (liveAddonRails, otherAddonRails) = addonRails.partition { it.isLive }
        val ordered = baseRails.toMutableList()
        val networksIndex = ordered.indexOfFirst { it.id == "networks" }
        if (liveAddonRails.isNotEmpty()) {
            if (networksIndex >= 0) ordered.addAll(networksIndex + 1, liveAddonRails) else ordered.addAll(liveAddonRails)
        }
        ordered.addAll(otherAddonRails)
        return ordered
    }

    /** The finished screen. Callers that cannot render progressively still get one value. */
    suspend fun fetchHomeContent(forceRefresh: Boolean = false): HomeContent =
        homeContentStream(forceRefresh).last()


    suspend fun fetchDetail(id: String, type: String, forceRefresh: Boolean = false): MediaDetail? {
        val canonicalType = if (type == "series") "tv" else type
        val imdbId = Regex("tt\\d+", RegexOption.IGNORE_CASE).find(id)?.value
        val resolved = if (imdbId != null) {
            runCatching {
                api.get<TmdbFindResponse>("/tmdb/find/imdb/$imdbId?type=$canonicalType")
            }.getOrNull()?.takeIf { it.id > 0 }
        } else {
            null
        }
        val resolvedType = resolved?.type?.let { if (it == "series") "tv" else it } ?: canonicalType
        val resolvedId = resolved?.id?.toString() ?: id
        val cacheKey = "$resolvedType:$resolvedId"
        if (!forceRefresh) {
            detailsCache[cacheKey]?.let { return it }
        }
        val detail = api.get<MediaDetail>("/tmdb/details/$resolvedType/$resolvedId")
        if (detail != null) {
            detailsCache[cacheKey] = detail
        }
        return detail
    }

    suspend fun fetchTraktComments(id: String, type: String): List<TraktCommentItem> {
        return api.get<TraktCommentsResponse>("/trakt/comments/$type/$id")?.results.orEmpty()
    }

    suspend fun fetchSeason(id: String, seasonNumber: Int, forceRefresh: Boolean = false): SeasonDetail? {
        val cacheKey = "$id:$seasonNumber"
        if (!forceRefresh) {
            seasonCache[cacheKey]?.let { return it }
        }
        val detail = api.get<SeasonDetail>("/tmdb/season/$id/$seasonNumber")
        if (detail != null) {
            seasonCache[cacheKey] = detail
        }
        return detail
    }

    suspend fun fetchLibrary(forceRefresh: Boolean = false): LibraryResponse {
        val cacheKey = buildSessionProfileCacheKey()
        if (!forceRefresh) {
            libraryCache[cacheKey]?.let { return it }
        }
        TvDebugLogger.i(
            "Library",
            "fetchLibrary forceRefresh=$forceRefresh user=$cacheKey profile=${sessionStore.activeProfileId() ?: "none"}",
        )
        val failuresBefore = api.failureEpoch
        val library = runCatching {
            api.get<LibraryResponse>("/sync/library")
        }.onFailure {
            TvDebugLogger.e("Library", "fetchLibrary failed", it)
        }.getOrNull() ?: LibraryResponse()
        val servicePlayback = fetchServicePlayback()
        val mergedContinueWatching = mergeContinueWatching(
            primary = library.continueWatching,
            secondary = servicePlayback,
        )
        // /sync/library follows the profile's tracking service, so its watchlist is normally the
        // right one already. The direct read stays as a safety net for a TV running ahead of a
        // backend that still answers with Trakt's list only.
        val watchlist = if (library.watchlist.isNotEmpty() || primarySyncService() == SyncServiceId.TRAKT) {
            library.watchlist
        } else {
            fetchServiceWatchlist() ?: library.watchlist
        }
        val merged = library.copy(
            continueWatching = mergedContinueWatching,
            watchlist = watchlist,
        )
        // An empty library is perfectly normal for a new account, so only an empty result that
        // also had failed requests behind it is reported as a problem worth showing.
        if (merged.continueWatching.isEmpty() && merged.watchlist.isEmpty() && api.failureEpoch > failuresBefore) {
            TvDebugLogger.w("Library", "library came back empty after backend failures")
            throw ContentUnavailableException("Your library could not be loaded. Check the connection and try again.")
        }
        TvDebugLogger.i(
            "Library",
            "fetchLibrary ok continue=${merged.continueWatching.size} watchlist=${merged.watchlist.size} " +
                "service=${primarySyncService()} serviceContinue=${servicePlayback.size}",
        )
        libraryCache[cacheKey] = merged
        return merged
    }

    suspend fun searchMedia(query: String, forceRefresh: Boolean = false): List<MediaItem> {
        val normalized = query.trim()
        if (normalized.isBlank()) return emptyList()
        val cacheKey = buildSessionProfileCacheKey() + ":" + normalized.lowercase(Locale.US)
        if (!forceRefresh) {
            searchCache[cacheKey]?.let { return it }
        }
        val encoded = URLEncoder.encode(normalized, "UTF-8")
        val (tmdbResults, liveResults) = supervisorScope {
            val tmdb = async {
                runCatching { api.get<PagedRailResponse>("/tmdb/search?q=$encoded")?.results.orEmpty() }
                    .getOrDefault(emptyList())
                    .filter { it.type == "movie" || it.type == "tv" }
            }
            val live = async {
                fetchAddonCatalogCollections { _, mappedType -> mappedType == "live" }
                    .flatMap { it.items }
                    .filter { item ->
                        sequenceOf(
                            item.title,
                            item.description.orEmpty(),
                            item.sourceAddonName.orEmpty(),
                            item.sourceCatalogName.orEmpty(),
                        ).any { value -> value.contains(normalized, ignoreCase = true) }
                    }
            }
            tmdb.await() to live.await()
        }
        val results = (liveResults + tmdbResults)
            .distinctBy { item -> listOf(item.type, item.sourceAddonId.orEmpty(), item.sourceCatalogId.orEmpty(), item.id).joinToString(":") }
        searchCache[cacheKey] = results
        return results
    }
    suspend fun fetchGenres(type: String, forceRefresh: Boolean = false): List<GenreItem> {
        val normalized = if (type == "tv") "tv" else "movie"
        if (!forceRefresh) {
            genreCache[normalized]?.let { return it }
        }
        val genres = api.get<GenreResponse>("/tmdb/genres/$normalized")?.genres.orEmpty()
        genreCache[normalized] = genres
        return genres
    }

    /**
     * Discover browse used by the Search screen, mirroring the mobile app's Discover
     * section. "documentary" is a movie query pinned to TMDB's documentary genre, and a
     * "before:YYYY" year filter becomes a release-date cutoff rather than an exact year.
     */
    suspend fun fetchDiscover(
        type: String,
        page: Int = 1,
        genreId: Int? = null,
        year: String? = null,
        forceRefresh: Boolean = false,
    ): PagedRailResponse {
        val requestedType = type.trim().lowercase(Locale.US)
        val isDocumentary = requestedType == "documentary"
        val effectiveType = if (isDocumentary) "movie" else if (requestedType == "tv") "tv" else "movie"
        val params = mutableListOf("type=$effectiveType", "page=$page")
        when {
            isDocumentary -> params += "genre_id=99"
            genreId != null -> params += "genre_id=$genreId"
        }
        if (!year.isNullOrBlank()) {
            if (year.startsWith("before:")) {
                val cutoff = year.removePrefix("before:").trim()
                if (cutoff.isNotBlank()) {
                    val encodedCutoff = URLEncoder.encode("$cutoff-12-31", "UTF-8")
                    params += if (effectiveType == "tv") {
                        "first_air_date.lte=$encodedCutoff"
                    } else {
                        "primary_release_date.lte=$encodedCutoff"
                    }
                }
            } else {
                params += "year=${URLEncoder.encode(year, "UTF-8")}"
            }
        }
        val query = "/tmdb/discover?${params.joinToString("&")}"
        val cacheKey = "discover:$query"
        if (!forceRefresh) {
            networkCache[cacheKey]?.let { return it }
        }
        val response = api.get<PagedRailResponse>(query) ?: PagedRailResponse()
        networkCache[cacheKey] = response
        return response
    }

    suspend fun fetchNetworkCatalog(
        networkId: String,
        type: String = "all",
        year: String? = null,
        genreId: Int? = null,
        sort: String = "year",
        page: Int = 1,
        forceRefresh: Boolean = false,
    ): PagedRailResponse {
        val cacheKey = listOf(networkId, type, year.orEmpty(), genreId?.toString().orEmpty(), sort, page.toString()).joinToString(":")
        if (!forceRefresh) {
            networkCache[cacheKey]?.let { return it }
        }
        val query = buildString {
            append("/tmdb/network/$networkId?page=$page&type=$type&sort=$sort")
            if (!year.isNullOrBlank()) append("&year=${URLEncoder.encode(year, "UTF-8")}")
            if (genreId != null) append("&genre_id=$genreId")
        }
        val response = api.get<PagedRailResponse>(query) ?: PagedRailResponse()
        networkCache[cacheKey] = response
        return response
    }

    /**
     * The tracking service this profile has chosen. Picked up from the profile-scoped home
     * preferences that mobile and the web portal write, so all three clients agree on where a
     * watchlist toggle lands.
     */
    fun primarySyncService(): String =
        SyncServiceId.normalize(bootstrapState.value?.preferences?.home?.primarySyncService)

    fun isSyncServiceConnected(service: String): Boolean {
        val integrations = bootstrapState.value?.integrations ?: return false
        return when (SyncServiceId.normalize(service)) {
            SyncServiceId.SIMKL -> integrations.simkl.connected
            SyncServiceId.MDBLIST -> integrations.mdblist.connected
            else -> integrations.trakt.connected || bootstrapState.value?.syncStatus?.traktConnected == true
        }
    }

    /**
     * The services to try, in order, for a watchlist or resume-point call.
     *
     * The profile's own choice comes first. Trakt is kept as a backstop whenever it is also
     * connected: a profile that switched to Simkl last week still has years of Trakt history, and
     * a service that is unreachable or half-configured should cost the viewer an empty screen for
     * as short a time as possible. A service that cannot do the job at all is skipped outright.
     */
    private fun syncServiceChain(requires: (SyncServiceCapabilities) -> Boolean): List<String> {
        val primary = primarySyncService()
        return listOf(primary, SyncServiceId.TRAKT)
            .distinct()
            .filter { requires(SyncServiceCapabilities.of(it)) && isSyncServiceConnected(it) }
    }

    suspend fun addToWatchlist(item: MediaItem) {
        updateWatchlist(item, remove = false)
    }

    suspend fun removeFromWatchlist(item: MediaItem) {
        updateWatchlist(item, remove = true)
    }

    private suspend fun updateWatchlist(item: MediaItem, remove: Boolean) {
        val tmdbId = item.tmdbId.takeIf { it > 0 } ?: item.id.toIntOrNull()
        val entry: Map<String, Any?> = mapOf(
            "title" to item.title,
            "year" to item.year?.toIntOrNull(),
            "ids" to mapOf<String, Any?>("tmdb" to tmdbId),
        )
        val payload = if (item.type == "tv") {
            mapOf("movies" to emptyList<Any>(), "shows" to listOf(entry))
        } else {
            mapOf("movies" to listOf(entry), "shows" to emptyList<Any>())
        }
        val action = if (remove) "remove" else "add"
        // Only the profile's own service is written to. Mirroring the change into Trakt as well
        // would quietly edit a list the viewer did not ask to touch.
        val services = syncServiceChain { it.watchlistWrite }.take(1)
            .ifEmpty { listOf(primarySyncService()) }
        for (service in services) {
            val response = api.post<Map<String, Any>>("/$service/sync/watchlist/$action", payload)
            if (response != null) break
            TvDebugLogger.w("Watchlist", "$action failed on $service for ${item.type}:${item.id}")
        }
        // Refreshing is a courtesy; a failure here must not surface as a failed watchlist edit.
        runCatching { fetchLibrary(forceRefresh = true) }
    }

    /**
     * Watchlist for the profile's tracking service. `/sync/library` enriches Trakt only, so any
     * other primary service has to be read from its own route.
     */
    private suspend fun fetchServiceWatchlist(): List<MediaItem>? {
        val services = syncServiceChain { it.watchlist }
        for (service in services) {
            val results = api.get<WatchlistEnvelope>("/$service/sync/watchlist/enriched")?.results
            if (results != null) return results
            TvDebugLogger.w("Watchlist", "could not read the $service watchlist")
        }
        return null
    }

    suspend fun markWatched(
        mediaType: String,
        mediaId: String,
        title: String,
        year: String? = null,
        episode: EpisodeContext? = null,
        imdbId: String? = null,
    ): Boolean {
        val watchedAt = Instant.now().toString()
        val parsedTmdbId = mediaId.toIntOrNull()
        val parsedYear = year?.take(4)?.toIntOrNull()
        val payload = if (mediaType == "tv" && episode != null) {
            mapOf(
                "movies" to emptyList<Any>(),
                "shows" to listOf(
                    mapOf(
                        "title" to title,
                        "ids" to mapOf(
                            "tmdb" to parsedTmdbId,
                            "imdb" to imdbId,
                        ),
                        "seasons" to listOf(
                            mapOf(
                                "number" to episode.seasonNumber,
                                "episodes" to listOf(
                                    mapOf(
                                        "number" to episode.episodeNumber,
                                        "watched_at" to watchedAt,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        } else {
            mapOf(
                "movies" to listOf(
                    mapOf(
                        "title" to title,
                        "year" to parsedYear,
                        "ids" to mapOf(
                            "tmdb" to parsedTmdbId,
                            "imdb" to imdbId,
                        ),
                        "watched_at" to watchedAt,
                    ),
                ),
                "shows" to emptyList<Any>(),
            )
        }

        return runCatching {
            val ok = api.post<Any>("/trakt/sync/watched", payload) != null
            if (ok) {
                invalidatePlaybackDerivedCaches()
            }
            ok
        }.onFailure {
            TvDebugLogger.w("Trakt", "markWatched failed mediaType=$mediaType mediaId=$mediaId")
        }.getOrDefault(false)
    }

    suspend fun markBrowseItemWatched(item: MediaItem): Boolean {
        return if (item.type == "tv" && item.episode == null) {
            markSeriesWatched(
                mediaId = item.id,
                title = item.title,
                year = item.year,
            )
        } else {
            markWatched(
                mediaType = item.type,
                mediaId = item.id,
                title = item.title,
                year = item.year,
                episode = item.episode,
            )
        }.also { ok ->
            if (ok) {
                clearBrowseItemProgress(item)
            }
        }
    }

    suspend fun markSeasonWatched(
        mediaId: String,
        title: String,
        year: String?,
        seasonNumber: Int,
    ): Boolean {
        val detail = fetchDetail(mediaId, "tv") ?: return false
        val season = fetchSeason(mediaId, seasonNumber) ?: return false
        if (season.episodes.isEmpty()) return false
        val watchedAt = Instant.now().toString()
        val payload = mapOf(
            "movies" to emptyList<Any>(),
            "shows" to listOf(
                mapOf(
                    "title" to title,
                    "year" to year?.take(4)?.toIntOrNull(),
                    "ids" to mapOf(
                        "tmdb" to mediaId.toIntOrNull(),
                        "imdb" to detail.imdbId,
                    ),
                    "seasons" to listOf(
                        mapOf(
                            "number" to seasonNumber,
                            "episodes" to season.episodes.map {
                                mapOf(
                                    "number" to it.episodeNumber,
                                    "watched_at" to watchedAt,
                                )
                            },
                        ),
                    ),
                ),
            ),
        )
        return runCatching {
            val ok = api.post<Any>("/trakt/sync/watched", payload) != null
            if (ok) {
                clearSeasonProgress(mediaId, seasonNumber, season)
                invalidatePlaybackDerivedCaches()
            }
            ok
        }.onFailure {
            TvDebugLogger.w("Trakt", "markSeasonWatched failed mediaId=$mediaId season=$seasonNumber")
        }.getOrDefault(false)
    }

    suspend fun clearProgress(
        mediaType: String,
        mediaId: String,
        episode: EpisodeContext? = null,
    ) {
        val path = buildString {
            append("/sync/progress/$mediaType/$mediaId")
            buildEpisodeKey(episode)?.let { append("?episodeKey=$it") }
        }
        runCatching {
            api.delete<Any>(path)
            invalidatePlaybackDerivedCaches()
        }.onFailure {
            TvDebugLogger.w("Playback", "clearProgress failed mediaType=$mediaType mediaId=$mediaId")
        }
    }

    suspend fun clearBrowseItemProgress(item: MediaItem) {
        if (item.type == "tv" && item.episode == null) {
            runCatching {
                val library = fetchLibrary(forceRefresh = true)
                library.continueWatching
                    .filter { it.type == "tv" && it.id == item.id && it.episode != null }
                    .forEach { clearProgress("tv", item.id, it.episode) }
                clearProgress("tv", item.id, null)
            }
        } else {
            clearProgress(item.type, item.id, item.episode)
        }
    }

    suspend fun clearSeasonProgress(
        mediaId: String,
        seasonNumber: Int,
        seasonDetail: SeasonDetail? = null,
    ) {
        val season = seasonDetail ?: fetchSeason(mediaId, seasonNumber) ?: return
        season.episodes.forEach { episode ->
            clearProgress(
                mediaType = "tv",
                mediaId = mediaId,
                episode = EpisodeContext(
                    seasonNumber = seasonNumber,
                    episodeNumber = episode.episodeNumber,
                    title = episode.name,
                    overview = episode.overview,
                    still = episode.still,
                    runtime = episode.runtime,
                    airDate = episode.airDate,
                    tmdbEpisodeId = episode.id,
                ),
            )
        }
    }

    fun activeStreamProfile(): StreamProfile? {
        val profiles = bootstrapState.value?.streamProfiles.orEmpty()
        val activeId = sessionStore.activeProfileId()
        return profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull { it.isDefault } ?: profiles.firstOrNull()
    }

    fun activeStreamProfile(bootstrap: AccountBootstrap?): StreamProfile? {
        val profiles = bootstrap?.streamProfiles.orEmpty()
        val activeId = sessionStore.activeProfileId()
        return profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull { it.isDefault } ?: profiles.firstOrNull()
    }

    fun setActiveStreamProfile(profileId: String?) {
        if (profileId == sessionStore.activeProfileId()) return
        sessionStore.setActiveProfileId(profileId)
        reloadFavouriteChannels()
        libraryCache.clear()
        homeCache.clear()
        watchedHistoryCache.clear()
        // Responses are cached per URL, and the profile only travels in a header, so the previous
        // profile's rows would otherwise be replayed for this one whenever the network drops.
        StreamDekHttp.evictCache()
    }

    suspend fun fetchProgress(mediaType: String, mediaId: String, episode: EpisodeContext? = null): PlaybackProgressRecord? {
        val episodeKey = buildEpisodeKey(episode)
        val query = buildString {
            append("/sync/progress?entityType=$mediaType&entityId=$mediaId")
            if (episodeKey != null) append("&episodeKey=$episodeKey")
        }
        return api.get<PlaybackProgressResponse>(query)?.progress
    }

    suspend fun fetchContinueWatchingItem(mediaType: String, mediaId: String): ContinueWatchingItem? {
        return fetchLibrary().continueWatching.firstOrNull { it.type == mediaType && it.id == mediaId }
    }

    suspend fun fetchWatchedKeys(forceRefresh: Boolean = false): Set<String> {
        val session = currentSession() ?: return emptySet()
        val profileId = sessionStore.activeProfileId()?.takeIf { it.isNotBlank() } ?: return emptySet()
        val cacheKey = "${session.user.uid}:$profileId"
        if (!forceRefresh) {
            watchedHistoryCache[cacheKey]?.let { return it }
        }
        // Watched history is a Trakt-only feature; a profile tracking elsewhere would otherwise
        // spend a rejected request on every detail screen it opens.
        if (!isSyncServiceConnected(SyncServiceId.TRAKT)) return emptySet()
        val results = runCatching {
            api.get<TraktHistoryResponse>("/trakt/sync/history", session)?.results.orEmpty()
        }.onFailure {
            TvDebugLogger.e("Trakt", "fetchWatchedKeys failed", it)
        }.getOrDefault(emptyList())
        val watchedKeys = results.mapNotNull(::historyItemKey).toSet()
        watchedHistoryCache[cacheKey] = watchedKeys
        return watchedKeys
    }

    suspend fun isWatched(
        mediaType: String,
        mediaId: String,
        episode: EpisodeContext? = null,
        forceRefresh: Boolean = false,
    ): Boolean {
        return fetchWatchedKeys(forceRefresh).contains(watchedHistoryKey(mediaType, mediaId, episode))
    }

    suspend fun syncProgress(
        mediaType: String,
        mediaId: String,
        positionSec: Double,
        durationSec: Double,
        episode: EpisodeContext? = null,
        detail: MediaDetail? = null,
    ) {
        if (positionSec <= 0.0 || durationSec <= 0.0) return
        runCatching {
            api.request<Any>(
                method = "PUT",
                path = "/sync/progress",
                body = com.google.gson.Gson().toJson(
                    mapOf(
                        "entityType" to mediaType,
                        "entityId" to mediaId,
                        "positionSec" to positionSec,
                        "durationSec" to durationSec,
                        "episodeKey" to buildEpisodeKey(episode),
                        "updatedAt" to Instant.now().toString(),
                        "metadata" to buildSyncMetadata(detail, episode),
                    ),
                ),
            )
            invalidatePlaybackDerivedCaches()
        }
    }

    suspend fun traktScrobble(
        action: String,
        mediaType: String,
        mediaId: String,
        title: String? = null,
        year: String? = null,
        progress: Double = 0.0,
    ): Boolean {
        val session = currentSession() ?: return false
        val profileId = sessionStore.activeProfileId()?.takeIf { it.isNotBlank() } ?: return false
        val traktConnected = bootstrapState.value?.syncStatus?.traktConnected == true ||
            bootstrapState.value?.integrations?.trakt?.connected == true
        if (!traktConnected) return false

        val clampedProgress = progress.coerceIn(0.0, 100.0)
        val parsedYear = year
            ?.take(4)
            ?.toIntOrNull()

        val payload = if (mediaType == "tv") {
            mapOf(
                "show" to mapOf(
                    "title" to (title ?: ""),
                    "year" to parsedYear,
                    "ids" to mapOf("tmdb" to (mediaId.toIntOrNull())),
                ),
                "progress" to clampedProgress,
            )
        } else {
            mapOf(
                "movie" to mapOf(
                    "title" to (title ?: ""),
                    "year" to parsedYear,
                    "ids" to mapOf("tmdb" to (mediaId.toIntOrNull())),
                ),
                "progress" to clampedProgress,
            )
        }

        return runCatching {
            api.post<Any>("/trakt/scrobble/$action", payload, session) != null
        }.onFailure {
            TvDebugLogger.w("Trakt", "scrobble failed action=$action profile=$profileId mediaType=$mediaType mediaId=$mediaId")
        }.getOrDefault(false)
    }

    suspend fun resolvePlayback(
        mediaType: String,
        mediaId: String,
        imdbId: String?,
        episode: EpisodeContext? = null,
        preferredStreamKey: String? = null,
        preferredAddonName: String? = null,
        preferredQualityGroup: String? = null,
        forceRefresh: Boolean = false,
        streamType: String? = null,
        directStreamUrl: String? = null,
        requestHeaders: Map<String, String> = emptyMap(),
        sourceAddonId: String? = null,
        sourceAddonName: String? = null,
    ): ResolvedPlaybackCandidate {
        if (mediaType == "live" && !directStreamUrl.isNullOrBlank()) {
            val directStream = AddonStream(
                addonId = sourceAddonId.orEmpty(),
                addonName = sourceAddonName ?: "Live source",
                name = sourceAddonName ?: "Live source",
                title = "Direct live stream",
                url = directStreamUrl,
                requestHeaders = requestHeaders,
            )
            return ResolvedPlaybackCandidate(
                source = ResolvedPlaybackSource(
                    url = directStreamUrl,
                    contentType = guessContentType(directStreamUrl),
                    label = sourceAddonName ?: "Live stream",
                    requestHeaders = requestHeaders,
                ),
                stream = directStream,
                streams = listOf(directStream),
            )
        }
        val episodeKey = buildEpisodeKey(episode)
        val effectivePreferredStreamKey = effectiveRememberedStreamKey(
            explicitKey = preferredStreamKey,
            storedKey = sessionStore.preferredStreamKey(mediaType, mediaId, episodeKey),
            rememberLastSource = rememberLastSourceEnabled(),
        )
        val cacheKey = playbackCacheKey(
            mediaType = mediaType,
            mediaId = mediaId,
            imdbId = imdbId,
            episode = episode,
            preferredStreamKey = effectivePreferredStreamKey,
            preferredAddonName = preferredAddonName,
            preferredQualityGroup = preferredQualityGroup,
            streamType = streamType,
        )
        if (!forceRefresh) {
            readResolvedPlaybackCache(cacheKey)?.let { return it }
        }
        // Mirrors the mobile app's live type fallbacks: addons publish live channels
        // under a wide range of Stremio-native type names.
        val lookupTypes = streamLookupTypes(mediaType, streamType)
        val videoId = buildStreamVideoId(imdbId ?: mediaId, episode)
        val rememberedAddonId = effectivePreferredStreamKey
            ?.substringBefore('|')
            ?.takeIf { it.isNotBlank() }
        val targetedAddonId = sourceAddonId?.takeIf { it.isNotBlank() } ?: rememberedAddonId
        val targetedStreams = targetedAddonId?.let { addonId ->
            fetchStreamsFromOwningAddon(
                addonId = addonId,
                lookupTypes = lookupTypes,
                videoId = videoId,
                isLive = mediaType == "live",
                forceRefresh = mediaType == "live",
            )
        }
        val (streamLookupType, streams) = targetedStreams
            ?: fetchStreamsForPlayback(lookupTypes, videoId, isLive = mediaType == "live")
        for (stream in rankStreams(streams, effectivePreferredStreamKey, preferredAddonName, preferredQualityGroup)) {
            val resolvedUrl = resolveStreamToUrl(stream, streamLookupType, videoId)
            if (!resolvedUrl.isNullOrBlank()) {
                val resolvedStreamKey = streamSelectionKey(stream)
                val candidate = ResolvedPlaybackCandidate(
                    source = ResolvedPlaybackSource(
                        url = resolvedUrl,
                        contentType = guessContentType(resolvedUrl),
                        label = describeStream(stream),
                        filename = effectiveFilename(stream),
                        requestHeaders = stream.requestHeaders,
                    ),
                    stream = stream,
                    streams = streams,
                )
                if (rememberLastSourceEnabled()) {
                    sessionStore.savePreferredStreamKey(mediaType, mediaId, episodeKey, resolvedStreamKey)
                }
                writeResolvedPlaybackCache(cacheKey, candidate)
                return candidate
            }
        }
        return ResolvedPlaybackCandidate(null, null, streams).also {
            writeResolvedPlaybackCache(cacheKey, it)
        }
    }

    /**
     * Fetches candidate streams the same way the mobile app does: ask the backend for each
     * enabled addon individually (ordered by addon position), fall back to querying the addon
     * directly for a fresh response, and only then fall back to the aggregated backend route.
     * Returns the lookup type that produced results together with the de-duplicated streams.
     */
    private suspend fun fetchStreamsFromOwningAddon(
        addonId: String,
        lookupTypes: List<String>,
        videoId: String,
        isLive: Boolean,
        forceRefresh: Boolean,
    ): Pair<String?, List<AddonStream>>? {
        val addon = runCatching { fetchAddonManifests() }.getOrDefault(emptyList())
            .firstOrNull { it.enabled && it.id == addonId }
            ?: return null
        val baseId = videoId.substringBefore(":")
        for (lookupType in lookupTypes) {
            if (!addonSupportsStreamType(addon, lookupType)) continue
            val streams = fetchStreamsFromSingleAddon(
                addon = addon,
                lookupType = lookupType,
                videoId = videoId,
                baseId = baseId,
                isLive = isLive,
                forceRefresh = forceRefresh,
            )
            if (streams.isNotEmpty()) return lookupType to streams
        }
        return null
    }
    private suspend fun fetchStreamsForPlayback(
        lookupTypes: List<String>,
        videoId: String,
        isLive: Boolean = false,
    ): Pair<String?, List<AddonStream>> {
        val addons = runCatching { fetchAddonManifests() }.getOrDefault(emptyList())
            .filter { it.enabled }
            .sortedBy { it.position }
        val baseId = videoId.substringBefore(":")
        for (lookupType in lookupTypes) {
            val supportingAddons = addons.filter { addonSupportsStreamType(it, lookupType) }
            if (supportingAddons.isEmpty()) continue
            val merged = supervisorScope {
                supportingAddons.map { addon ->
                    async { fetchStreamsFromSingleAddon(addon, lookupType, videoId, baseId, isLive) }
                }.map { deferred -> runCatching { deferred.await() }.getOrDefault(emptyList()) }
            }.flatten()
            if (merged.isNotEmpty()) {
                return lookupType to dedupeStreams(merged)
            }
        }
        // Aggregated backend route as the final fallback, matching mobile behavior.
        for (lookupType in lookupTypes) {
            val aggregated = runCatching {
                api.get<AddonStreamsResponse>("/addons/streams/$lookupType/${encodePathSegment(videoId)}")?.streams
            }.getOrNull().orEmpty()
            if (aggregated.isNotEmpty()) {
                return lookupType to dedupeStreams(aggregated)
            }
        }
        return lookupTypes.firstOrNull() to emptyList()
    }

    private suspend fun fetchStreamsFromSingleAddon(
        addon: AddonManifest,
        lookupType: String,
        videoId: String,
        baseId: String,
        isLive: Boolean,
        forceRefresh: Boolean = false,
    ): List<AddonStream> {
        if (forceRefresh) {
            val direct = fetchFreshStreamsFromAddon(addon, lookupType, videoId)
            if (direct.isNotEmpty()) return direct
        }
        val viaBackend = runCatching {
            api.get<AddonStreamsResponse>(
                "/addons/streams/single/${encodePathSegment(addon.id)}/$lookupType/${encodePathSegment(videoId)}",
            )?.streams
        }.getOrNull().orEmpty()
        if (viaBackend.isNotEmpty()) {
            return viaBackend.map { it.withAddonIdentity(addon) }
        }
        // Direct addon fallback mirrors mobile: only for ids the addon can actually serve.
        val requiresImdbId = !isLive && (lookupType == "movie" || lookupType == "series" || lookupType == "tv")
        if (requiresImdbId && !baseId.matches(Regex("^tt\\d+$", RegexOption.IGNORE_CASE))) return emptyList()
        return fetchFreshStreamsFromAddon(addon, lookupType, videoId)
    }

    private fun AddonStream.withAddonIdentity(addon: AddonManifest): AddonStream = copy(
        addonId = addonId.ifBlank { addon.id },
        addonName = addonName.ifBlank { addon.manifest.name },
    )

    private fun dedupeStreams(streams: List<AddonStream>): List<AddonStream> = streams.distinctBy {
        listOf(it.addonId, it.name, it.title, effectiveInfoHash(it), it.url, effectiveFilename(it)).joinToString("|")
    }

    private fun addonSupportsStreamType(addon: AddonManifest, type: String): Boolean {
        val resources = addon.manifest.resources.mapNotNull { resource ->
            when (resource) {
                is String -> resource.trim().lowercase(Locale.US)
                is Map<*, *> -> (resource["name"] as? String)?.trim()?.lowercase(Locale.US)
                else -> null
            }
        }
        if (resources.isNotEmpty() && resources.none { it == "stream" || it == "streams" }) return false
        val nativeType = type.trim().lowercase(Locale.US)
        val types = addon.manifest.types.map { it.trim().lowercase(Locale.US) }
        if (types.isEmpty()) return true
        return nativeType in types ||
            (nativeType == "series" && "tv" in types) ||
            (nativeType == "tv" && "series" in types)
    }

    /**
     * Queries a Stremio addon directly for streams, bypassing the backend cache. Used both as
     * a fetch fallback and to refresh expired direct playback links right before playback.
     */
    private suspend fun fetchFreshStreamsFromAddon(
        addon: AddonManifest,
        type: String,
        videoId: String,
    ): List<AddonStream> = withContext(Dispatchers.IO) {
        val manifestUrl = addon.transportUrl ?: addon.manifestUrl ?: return@withContext emptyList()
        val addonBaseUrl = manifestUrl.substringBeforeLast("/manifest.json", missingDelimiterValue = manifestUrl.trimEnd('/'))
        val streamType = type.trim().lowercase(Locale.US)
        val request = okhttp3.Request.Builder()
            .url("$addonBaseUrl/stream/${encodePathSegment(streamType)}/${encodePathSegment(videoId)}.json?_sd=${System.currentTimeMillis()}")
            .header("User-Agent", "Stremio/4.4.168")
            .header("Cache-Control", "no-cache")
            .build()
        runCatching {
            directStreamClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val raw = response.body?.string()?.takeIf { it.isNotBlank() } ?: return@use emptyList()
                val parsed = com.google.gson.Gson().fromJson(raw, AddonStreamsResponse::class.java)
                parsed?.streams.orEmpty().map { it.withAddonIdentity(addon) }
            }
        }.onFailure {
            TvDebugLogger.w("Playback", "fetchFreshStreamsFromAddon failed addon=${addon.id} type=$streamType id=$videoId")
        }.getOrDefault(emptyList())
    }

    private fun encodePathSegment(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * Streams the addon results for a title as they arrive instead of waiting for every
     * addon to answer. Each emission carries the ranked streams gathered so far plus how
     * many sources are still outstanding, so the UI can render the first results
     * immediately and fill in the rest.
     */
    fun streamCandidates(
        mediaType: String,
        mediaId: String,
        imdbId: String?,
        episode: EpisodeContext? = null,
        preferredStreamKey: String? = null,
        streamType: String? = null,
        directStreamUrl: String? = null,
        requestHeaders: Map<String, String> = emptyMap(),
        sourceAddonId: String? = null,
        sourceAddonName: String? = null,
        forceRefresh: Boolean = false,
    ): kotlinx.coroutines.flow.Flow<StreamCandidatesProgress> = kotlinx.coroutines.flow.channelFlow {
        val isLive = mediaType == "live"
        if (isLive && !directStreamUrl.isNullOrBlank()) {
            val directStream = AddonStream(
                addonId = sourceAddonId.orEmpty(),
                addonName = sourceAddonName ?: "Live source",
                name = sourceAddonName ?: "Live source",
                title = "Direct live stream",
                url = directStreamUrl,
                requestHeaders = requestHeaders,
            )
            send(StreamCandidatesProgress(listOf(directStream), pendingSources = 0, done = true))
            return@channelFlow
        }

        val episodeKey = buildEpisodeKey(episode)
        val effectivePreferredStreamKey = effectiveRememberedStreamKey(
            explicitKey = preferredStreamKey,
            storedKey = sessionStore.preferredStreamKey(mediaType, mediaId, episodeKey),
            rememberLastSource = rememberLastSourceEnabled(),
        )
        val lookupTypes = streamLookupTypes(mediaType, streamType)
        val videoId = buildStreamVideoId(imdbId ?: mediaId, episode)
        val baseId = videoId.substringBefore(":")

        val addons = runCatching { fetchAddonManifests() }.getOrDefault(emptyList())
            .filter { it.enabled }
            .sortedBy { it.position }

        // Only the first lookup type that any addon claims to support is fanned out;
        // the remaining types stay available as a sequential fallback below.
        val primaryType = lookupTypes.firstOrNull { type -> addons.any { addonSupportsStreamType(it, type) } }
        val supportingAddons = primaryType?.let { type ->
            addons.filter { addon ->
                addonSupportsStreamType(addon, type) &&
                    (sourceAddonId.isNullOrBlank() || addon.id == sourceAddonId)
            }
        }.orEmpty()

        if (primaryType == null || supportingAddons.isEmpty()) {
            val (_, fallback) = fetchStreamsForPlayback(lookupTypes, videoId, isLive)
            send(
                StreamCandidatesProgress(
                    rankStreams(fallback, effectivePreferredStreamKey),
                    pendingSources = 0,
                    done = true,
                ),
            )
            return@channelFlow
        }

        val merged = java.util.concurrent.ConcurrentHashMap<String, AddonStream>()
        val order = java.util.concurrent.CopyOnWriteArrayList<String>()
        val remaining = java.util.concurrent.atomic.AtomicInteger(supportingAddons.size)
        val mutex = kotlinx.coroutines.sync.Mutex()
        // Cap concurrent addon requests so a large addon list cannot saturate the
        // TV's limited network stack and slow down the first results.
        val gate = kotlinx.coroutines.sync.Semaphore(4)

        suspend fun publish(done: Boolean) {
            val snapshot = mutex.withLock { order.mapNotNull { merged[it] } }
            send(
                StreamCandidatesProgress(
                    streams = rankStreams(snapshot, effectivePreferredStreamKey),
                    pendingSources = remaining.get().coerceAtLeast(0),
                    done = done,
                ),
            )
        }

        send(StreamCandidatesProgress(emptyList(), pendingSources = supportingAddons.size, done = false))

        supervisorScope {
            supportingAddons.forEach { addon ->
                launch {
                    val streams = runCatching {
                        gate.withPermit { fetchStreamsFromSingleAddon(addon, primaryType, videoId, baseId, isLive, forceRefresh) }
                    }.getOrDefault(emptyList())
                    mutex.withLock {
                        streams.forEach { stream ->
                            val key = streamMergeKey(stream)
                            if (merged.putIfAbsent(key, stream) == null) order.add(key)
                        }
                    }
                    remaining.decrementAndGet()
                    publish(done = false)
                }
            }
        }

        remaining.set(0)
        if (merged.isEmpty()) {
            // Nothing from the per-addon fan-out — fall back to the aggregated route
            // and any remaining lookup types before declaring the list empty.
            val (_, fallback) = fetchStreamsForPlayback(lookupTypes, videoId, isLive)
            fallback.forEach { stream ->
                val key = streamMergeKey(stream)
                if (merged.putIfAbsent(key, stream) == null) order.add(key)
            }
        }
        publish(done = true)
    }

    private fun streamMergeKey(stream: AddonStream): String = listOf(
        stream.addonId,
        stream.addonName,
        stream.name,
        stream.title,
        effectiveInfoHash(stream),
        stream.url,
        effectiveFilename(stream),
        stream.quality,
        stream.size,
    ).joinToString("|")

    private fun streamLookupTypes(mediaType: String, streamType: String?): List<String> = when (mediaType) {
        "live" -> {
            val native = streamType?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: "tv"
            buildList {
                add(native)
                if (native == "tv") add("live-tv")
                if (native == "live-tv") add("tv")
                if (native == "sport") add("sports")
                if (native == "sports") add("sport")
                addAll(listOf("live", "channel", "channels", "tv", "sport", "sports", "event", "events", "other"))
            }.distinct()
        }
        "tv" -> listOf("series")
        else -> listOf("movie")
    }


    /**
     * Mobile and the web portal keep this alongside the other stream-picker settings, while older
     * TV builds wrote it with playback. Both are read so the viewer's choice is honoured whichever
     * client last saved it.
     */
    private fun rememberLastSourceEnabled(): Boolean {
        val preferences = bootstrapState.value?.preferences ?: return true
        return preferences.streams.rememberLastSource
    }

    /** Searches OpenSubtitles, installed subtitle addons, and mobile-managed cloud sources. */
    suspend fun fetchExternalSubtitles(request: PlaybackRequest): List<ExternalSubtitleTrack> = withContext(Dispatchers.IO) {
        val imdbId = request.imdbId?.takeIf { it.startsWith("tt") } ?: return@withContext emptyList()
        val isSeries = request.mediaType == "tv" || request.mediaType == "series"
        val videoId = if (isSeries) {
            val episode = request.episode ?: return@withContext emptyList()
            "$imdbId:${episode.seasonNumber}:${episode.episodeNumber}"
        } else {
            imdbId
        }
        val type = if (isSeries) "series" else "movie"
        val preferences = bootstrapState.value?.preferences?.playback ?: PlaybackPreferences()
        val cloudSources = (preferences.subtitleSources + preferences.customSubtitleSources)
            .filter { it.enabled }
            .mapNotNull { source ->
                val baseUrl = source.baseUrl.ifBlank { source.url.orEmpty() }.trimEnd('/')
                baseUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?.let { SubtitleSourcePreference(source.id, source.name, it, enabled = true) }
            }
        val addonSources = bootstrapState.value?.integrations?.addons?.items.orEmpty()
            .filter { addon ->
                addon.enabled && addon.manifest.resources.any { resource ->
                    when (resource) {
                        is String -> resource.trim().equals("subtitles", ignoreCase = true)
                        is Map<*, *> -> resource["name"]?.toString()?.trim()?.equals("subtitles", ignoreCase = true) == true
                        else -> false
                    }
                }
            }
            .mapNotNull { addon ->
                val manifestUrl = addon.transportUrl ?: addon.manifestUrl ?: return@mapNotNull null
                val baseUrl = manifestUrl.substringBeforeLast("/manifest.json", manifestUrl).trimEnd('/')
                baseUrl.takeIf { it.isNotBlank() }?.let {
                    SubtitleSourcePreference("addon:${addon.id}", addon.manifest.name.ifBlank { addon.id }, it)
                }
            }
        val sources = (listOf(SubtitleSourcePreference("opensubtitles", "OpenSubtitles", "https://opensubtitles-v3.strem.io")) +
            cloudSources + addonSources).distinctBy { it.baseUrl.lowercase(Locale.US) }

        supervisorScope {
            sources.map { source ->
                async {
                    runCatching {
                        val endpoint = "${source.baseUrl.trimEnd('/')}/subtitles/$type/${encodePathSegment(videoId)}.json"
                        val httpRequest = okhttp3.Request.Builder()
                            .url(endpoint)
                            .header("Accept", "application/json")
                            .header("User-Agent", "Stremio/4.4.168")
                            .build()
                        api.client.newCall(httpRequest).execute().use { response ->
                            if (!response.isSuccessful) return@use emptyList()
                            val payload = api.gson.fromJson(response.body?.charStream(), StremioSubtitlesResponse::class.java)
                                ?: return@use emptyList()
                            payload.subtitles.mapNotNull { subtitle ->
                                val language = normalizeSubtitleLanguage(subtitle.language)
                                if (subtitle.id.isBlank() || subtitle.url.isBlank() || language.isBlank()) return@mapNotNull null
                                ExternalSubtitleTrack(
                                    id = "${source.id}:${subtitle.id}",
                                    language = language,
                                    label = listOf(language.uppercase(Locale.US), subtitle.release, source.name.ifBlank { "Subtitle addon" })
                                        .filter { it.isNotBlank() }.joinToString(" - "),
                                    url = subtitle.url,
                                )
                            }
                        }
                    }.onFailure { TvDebugLogger.w("Subtitles", "lookup failed source=${source.name}: ${it.message}") }
                        .getOrDefault(emptyList())
                }
            }.map { it.await() }
                .flatten()
                .distinctBy { it.url }
                .sortedWith(compareBy<ExternalSubtitleTrack> {
                    when (it.language) {
                        normalizeSubtitleLanguage(activeStreamProfile(bootstrapState.value)?.subtitleLanguage
                            ?: preferences.defaultSubtitleLanguage) -> 0
                        "en" -> 1
                        else -> 2
                    }
                }.thenBy { it.label })
                .take(80)
        }
    }

    suspend fun downloadSubtitleToCache(url: String, cacheDir: File): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 StreamDekTV").build()
            api.client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Subtitle download failed: ${response.code}" }
                val extensionFromUrl = response.request.url.encodedPath.substringAfterLast('.', "").lowercase(Locale.US)
                    .takeIf { it in setOf("srt", "vtt", "ass", "ssa", "ttml", "xml") }
                val extension = extensionFromUrl ?: when {
                    response.body?.contentType()?.subtype?.contains("vtt", ignoreCase = true) == true -> "vtt"
                    response.body?.contentType()?.subtype?.contains("ttml", ignoreCase = true) == true -> "ttml"
                    else -> "srt"
                }
                val directory = File(cacheDir, "subtitles").apply { mkdirs() }
                val target = File(directory, "${url.hashCode().toUInt()}.$extension")
                if (target.exists() && target.length() > 0L) return@use target.absolutePath
                response.body?.byteStream()?.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                    ?: error("Empty subtitle response")
                check(target.length() > 0L) { "Empty subtitle response" }
                target.absolutePath
            }
        }.onFailure { TvDebugLogger.w("Subtitles", "download failed: ${it.message}") }.getOrNull()
    }

    private fun normalizeSubtitleLanguage(raw: String?): String {
        val value = raw?.trim()?.lowercase(Locale.US).orEmpty()
        return when (value) {
            "eng", "en-us", "en-gb" -> "en"
            "spa" -> "es"
            "fra", "fre" -> "fr"
            "deu", "ger" -> "de"
            "ita" -> "it"
            "por" -> "pt"
            "jpn" -> "ja"
            else -> value.substringBefore('-')
        }
    }
    suspend fun prefetchPlayback(

        mediaType: String,
        mediaId: String,
        imdbId: String?,
        episode: EpisodeContext? = null,
    ) {
        resolvePlayback(mediaType, mediaId, imdbId, episode, preferredStreamKey = null, forceRefresh = false)
    }

    /**
     * Resolves a stream that the source picker already discovered. This is the latency-critical
     * path: do not query every enabled addon again after the viewer has selected one.
     */
    suspend fun resolveSelectedPlayback(
        request: PlaybackRequest,
        stream: AddonStream,
        streams: List<AddonStream> = emptyList(),
        forceRefresh: Boolean = false,
    ): ResolvedPlaybackCandidate {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val selectedKey = streamSelectionKey(stream)
        val cacheKey = playbackCacheKey(
            mediaType = request.mediaType,
            mediaId = request.mediaId,
            imdbId = request.imdbId,
            episode = request.episode,
            preferredStreamKey = selectedKey,
            streamType = request.streamType,
        )
        if (!forceRefresh) {
            readResolvedPlaybackCache(cacheKey)?.let { cached ->
                if (cached.source != null && rememberLastSourceEnabled()) {
                    sessionStore.savePreferredStreamKey(request.mediaType, request.mediaId, buildEpisodeKey(request.episode), selectedKey)
                }
                TvDebugLogger.i("Playback", "selected-source cache hit addon=${stream.addonName} elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}")
                return cached.copy(streams = streams.ifEmpty { cached.streams })
            }
        }

        val lookupType = streamLookupTypes(request.mediaType, request.streamType).firstOrNull()
        val videoId = buildStreamVideoId(request.imdbId ?: request.mediaId, request.episode)
        val playbackStream = if (!lookupType.isNullOrBlank()) {
            refreshStreamForPlayback(stream, lookupType, videoId)
        } else {
            stream
        }
        val resolvedUrl = resolveStreamToUrl(playbackStream)
        val allStreams = streams.ifEmpty { listOf(stream) }
        val candidate = if (resolvedUrl.isNullOrBlank()) {
            ResolvedPlaybackCandidate(null, null, allStreams)
        } else {
            ResolvedPlaybackCandidate(
                source = ResolvedPlaybackSource(
                    url = resolvedUrl,
                    contentType = guessContentType(resolvedUrl),
                    label = describeStream(playbackStream),
                    filename = effectiveFilename(playbackStream),
                    requestHeaders = playbackStream.requestHeaders,
                ),
                stream = playbackStream,
                streams = allStreams,
            )
        }
        if (candidate.source != null) {
            if (rememberLastSourceEnabled()) {
                sessionStore.savePreferredStreamKey(request.mediaType, request.mediaId, buildEpisodeKey(request.episode), selectedKey)
            }
        }
        writeResolvedPlaybackCache(cacheKey, candidate)
        TvDebugLogger.i(
            "Playback",
            "selected-source resolved addon=${stream.addonName} direct=${normalizedDirectUrl(playbackStream) != null} elapsedMs=${android.os.SystemClock.elapsedRealtime() - startedAt}",
        )
        return candidate
    }

    suspend fun resolvePlaybackSource(
        stream: AddonStream,
        lookupType: String? = null,
        videoId: String? = null,
    ): ResolvedPlaybackSource? {
        val resolvedUrl = resolveStreamToUrl(stream, lookupType, videoId) ?: return null
        return ResolvedPlaybackSource(
            url = resolvedUrl,
            contentType = guessContentType(resolvedUrl),
            label = describeStream(stream),
            filename = effectiveFilename(stream),
            requestHeaders = stream.requestHeaders,
        )
    }

    suspend fun fetchEpisodeSegments(
        imdbId: String,
        season: Int,
        episode: Int,
    ): List<PlaybackSegment> {
        if (!imdbId.startsWith("tt") || season < 0 || episode <= 0) return emptyList()
        val cacheKey = "$imdbId:$season:$episode"
        episodeSegmentCache[cacheKey]?.let { return it }
        val params = "imdb_id=${URLEncoder.encode(imdbId, "UTF-8")}&season=$season&episode=$episode"
        val result = withContext(Dispatchers.IO) { runCatching {
            val request = okhttp3.Request.Builder()
                .url("https://api.introdb.app/segments?$params")
                .header("Accept", "application/json")
                .build()
            api.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("IntroDB fetch failed with status ${response.code}")
                }
                val raw = response.body?.string().orEmpty()
                val segments = parseIntroDbSegments(api.gson.fromJson(raw, Any::class.java)).toMutableList()
                if (segments.none { it.segmentType == "intro" }) {
                    parseLegacyIntroSegment(fetchLegacyIntroPayload(imdbId, season, episode))?.let { legacyIntro ->
                        segments.removeAll { it.segmentType == "intro" }
                        segments.add(0, legacyIntro)
                    }
                }
                segments.sortedWith(compareBy<PlaybackSegment> { it.startSec }.thenBy { it.endSec }.thenBy { it.segmentType })
            }
        }.recoverCatching {
            parseLegacyIntroSegment(fetchLegacyIntroPayload(imdbId, season, episode))?.let(::listOf) ?: emptyList()
        }.onFailure {
            TvDebugLogger.w("Playback", "fetchEpisodeSegments failed imdbId=$imdbId season=$season episode=$episode")
        }.getOrDefault(emptyList()) }
        episodeSegmentCache[cacheKey] = result
        return result
    }

    private suspend fun markSeriesWatched(
        mediaId: String,
        title: String,
        year: String?,
    ): Boolean {
        val detail = fetchDetail(mediaId, "tv") ?: return false
        val watchedAt = Instant.now().toString()
        val seasonsPayload = detail.seasons.mapNotNull { seasonRef ->
            val season = fetchSeason(mediaId, seasonRef.seasonNumber) ?: return@mapNotNull null
            val episodes = season.episodes.map {
                mapOf(
                    "number" to it.episodeNumber,
                    "watched_at" to watchedAt,
                )
            }
            if (episodes.isEmpty()) null else mapOf(
                "number" to seasonRef.seasonNumber,
                "episodes" to episodes,
            )
        }
        if (seasonsPayload.isEmpty()) return false
        val payload = mapOf(
            "movies" to emptyList<Any>(),
            "shows" to listOf(
                mapOf(
                    "title" to title,
                    "year" to year?.take(4)?.toIntOrNull(),
                    "ids" to mapOf(
                        "tmdb" to mediaId.toIntOrNull(),
                        "imdb" to detail.imdbId,
                    ),
                    "seasons" to seasonsPayload,
                ),
            ),
        )
        return runCatching {
            val ok = api.post<Any>("/trakt/sync/watched", payload) != null
            if (ok) {
                invalidatePlaybackDerivedCaches()
            }
            ok
        }.onFailure {
            TvDebugLogger.w("Trakt", "markSeriesWatched failed mediaId=$mediaId")
        }.getOrDefault(false)
    }

    fun streamSelectionKey(stream: AddonStream): String {
        return listOfNotNull(
            stream.addonId.takeIf { it.isNotBlank() },
            stream.infoHash?.lowercase()?.takeIf { it.isNotBlank() },
            stream.url?.trim()?.takeIf { it.isNotBlank() },
            stream.behaviorHints?.filename?.trim()?.takeIf { it.isNotBlank() },
            stream.title?.trim()?.takeIf { it.isNotBlank() },
            stream.name?.trim()?.takeIf { it.isNotBlank() },
        ).joinToString("|")
    }

    fun describeStreamOption(stream: AddonStream): String = describeStream(stream)

    private suspend fun resolveStreamToUrl(
        stream: AddonStream,
        lookupType: String? = null,
        videoId: String? = null,
    ): String? {
        // Expired direct links (short-lived addon URLs) are refreshed straight from the
        // source addon before playback, mirroring the mobile app's refresh behavior.
        val playbackStream = if (!lookupType.isNullOrBlank() && !videoId.isNullOrBlank()) {
            refreshStreamForPlayback(stream, lookupType, videoId)
        } else {
            stream
        }
        if (!isPlayableStreamOption(playbackStream)) return null
        normalizedDirectUrl(playbackStream)?.let { return it }
        val infoHash = effectiveInfoHash(playbackStream) ?: return null
        val filename = effectiveFilename(playbackStream)
        val magnetLink = buildMagnetLink(infoHash, filename)
        val payload = buildMap<String, Any> {
            put("infoHash", infoHash)
            put("magnetLink", magnetLink)
            filename?.let { put("filename", it) }
            playbackStream.cachedBy.firstOrNull()?.let { put("providerHint", it) }
            maxFileSizeBytes()?.let { put("maxSize", it) }
        }
        return runCatching {
            val debrid = api.post<DebridResolveResponse>("/debrid/resolve", payload)
            if (!debrid?.url.isNullOrBlank()) return@runCatching debrid.url
            val torrent = api.post<TorrentResolveResponse>("/stream/torrent/add", payload)
            torrent?.streamUrl
        }.onFailure {
            TvDebugLogger.e("Playback", "resolveStreamToUrl failed infoHash=$infoHash", it)
        }.getOrNull()
    }

    /** Reject archive/download payloads that addons occasionally mislabel as playable videos. */
    fun isPlayableStreamOption(stream: AddonStream): Boolean {
        if (!effectiveInfoHash(stream).isNullOrBlank()) return true
        val url = normalizedDirectUrl(stream) ?: return false
        val decodedUrl = runCatching { java.net.URLDecoder.decode(url, "UTF-8") }.getOrDefault(url)
        val evidence = listOfNotNull(
            decodedUrl,
            stream.filename,
            stream.behaviorHints?.filename,
            stream.title,
            stream.name,
        ).joinToString(" ").lowercase(Locale.US)
        return !Regex("\\.(zip|rar|7z|tar|gz)(?:$|[?&#\\\" ]|\\.)").containsMatchIn(evidence)
    }

    /** Direct playback URL, excluding magnet links which must be resolved via debrid/torrent. */
    private fun normalizedDirectUrl(stream: AddonStream): String? {
        val url = stream.url?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) } ?: return null
        return url.takeUnless { it.startsWith("magnet:", ignoreCase = true) }
    }

    /** Info hash from the stream, or parsed out of a magnet url when absent. */
    private fun effectiveInfoHash(stream: AddonStream): String? {
        stream.infoHash?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val url = stream.url?.trim().orEmpty()
        if (!url.startsWith("magnet:?", ignoreCase = true)) return null
        return Regex("btih:([A-Fa-f0-9]{32,40})").find(url)?.groupValues?.getOrNull(1)
    }

    private fun effectiveFilename(stream: AddonStream): String? =
        stream.behaviorHints?.filename?.takeIf { it.isNotBlank() }
            ?: stream.filename?.takeIf { it.isNotBlank() }
            ?: stream.title?.takeIf { it.isNotBlank() }
            ?: stream.name?.takeIf { it.isNotBlank() }

    private fun effectiveBingeGroup(stream: AddonStream): String? =
        stream.bingeGroup?.takeIf { it.isNotBlank() }
            ?: stream.behaviorHints?.bingeGroup?.takeIf { it.isNotBlank() }

    /** Addon links served from short-lived direct routes must be re-fetched before playback. */
    private fun needsFreshPlaybackUrl(stream: AddonStream): Boolean {
        val url = stream.url ?: return false
        return runCatching {
            val uri = java.net.URI(url)
            uri.host.equals("pengu.uk", ignoreCase = true) && uri.path.orEmpty().startsWith("/direct/")
        }.getOrDefault(false)
    }

    private suspend fun refreshStreamForPlayback(
        stream: AddonStream,
        lookupType: String,
        videoId: String,
    ): AddonStream {
        if (!needsFreshPlaybackUrl(stream)) return stream
        val addon = runCatching { fetchAddonManifests() }.getOrDefault(emptyList())
            .firstOrNull { it.id == stream.addonId && it.enabled }
            ?: return stream
        val fresh = fetchFreshStreamsFromAddon(addon, lookupType, videoId)
        if (fresh.isEmpty()) return stream
        val bingeGroup = effectiveBingeGroup(stream)
        val filename = effectiveFilename(stream)
        return fresh.firstOrNull { candidate ->
            !bingeGroup.isNullOrBlank() && effectiveBingeGroup(candidate) == bingeGroup
        } ?: fresh.firstOrNull { candidate ->
            !filename.isNullOrBlank() && effectiveFilename(candidate) == filename && candidate.name == stream.name
        } ?: stream
    }

    private fun maxFileSizeBytes(): Long? {
        val gb = bootstrapState.value?.preferences?.playback?.maxFileSizeGB?.trim()?.toDoubleOrNull() ?: return null
        if (gb <= 0.0) return null
        return (gb * 1024.0 * 1024.0 * 1024.0).toLong()
    }

    private fun rankStreams(
        streams: List<AddonStream>,
        preferredStreamKey: String?,
        preferredAddonName: String? = null,
        preferredQualityGroup: String? = null,
    ): List<AddonStream> {
        val preferredQuality = bootstrapState.value?.preferences?.playback?.preferredQuality ?: "best"
        val normalizedPreferredAddon = preferredAddonName?.trim()?.lowercase(Locale.US)
        val normalizedPreferredQuality = preferredQualityGroup?.trim()?.lowercase(Locale.US)
        val autoSelectionLanguage = preferredAudioLanguageForAutoSelection()
        val candidateStreams = if (preferredStreamKey.isNullOrBlank()) {
            filterStreamsByPreferredAudioLanguage(streams, autoSelectionLanguage)
        } else {
            streams
        }
        return candidateStreams.sortedWith(
            compareByDescending<AddonStream> { if (preferredStreamKey != null && streamSelectionKey(it) == preferredStreamKey) 10 else 0 }
                .thenByDescending {
                    if (!normalizedPreferredAddon.isNullOrBlank() && it.addonName.trim().lowercase(Locale.US) == normalizedPreferredAddon) 6 else 0
                }
                .thenByDescending {
                    if (!normalizedPreferredQuality.isNullOrBlank() && it.quality?.trim()?.lowercase(Locale.US) == normalizedPreferredQuality) 4 else 0
                }
                .thenByDescending { if (!normalizedDirectUrl(it).isNullOrBlank()) 3 else 0 }
                .thenByDescending { if (it.cachedBy.isNotEmpty()) 2 else 0 }
                .thenByDescending { if (!effectiveInfoHash(it).isNullOrBlank()) 1 else 0 }
                .thenByDescending { preferredQualityScore(it.quality, preferredQuality) }
                .thenByDescending { parseQualityScore(it.quality) }
        )
    }

    private fun preferredAudioLanguageForAutoSelection(): String? {
        val activeProfile = activeStreamProfile(bootstrapState.value)
        val profileLanguage = activeProfile?.audioLanguage?.trim()?.takeIf { it.isNotBlank() }
        val playbackLanguage = bootstrapState.value?.preferences?.playback?.defaultAudioLanguage?.trim()?.takeIf { it.isNotBlank() }
        val preferredLanguage = profileLanguage ?: playbackLanguage
        return preferredLanguage?.takeUnless { it.equals("auto", ignoreCase = true) }
    }

    private fun filterStreamsByPreferredAudioLanguage(streams: List<AddonStream>, preferredLanguage: String?): List<AddonStream> {
        val normalizedPreference = preferredLanguage?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: return streams
        val filtered = streams.filter { streamMatchesPreferredAudioLanguage(it, normalizedPreference) }
        return if (filtered.isNotEmpty()) filtered else streams
    }

    private fun streamMatchesPreferredAudioLanguage(stream: AddonStream, preferredLanguage: String): Boolean {
        val aliases = audioLanguageAliases(preferredLanguage)
        val descriptors = listOfNotNull(
            stream.behaviorHints?.filename,
            stream.title,
            stream.name,
            stream.quality,
        )
            .joinToString(" ")
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), " ")
        if (descriptors.isBlank()) return false
        return aliases.any { alias ->
            Regex("(^| )${Regex.escape(alias)}( |$)").containsMatchIn(descriptors)
        }
    }

    private fun audioLanguageAliases(preferredLanguage: String): Set<String> {
        return when (preferredLanguage.trim().lowercase(Locale.US)) {
            "auto" -> emptySet()
            "en", "eng", "english" -> setOf("en", "eng", "english")
            "es", "spa", "spanish", "espanol" -> setOf("es", "spa", "spanish", "espanol")
            "fr", "fre", "fra", "french" -> setOf("fr", "fre", "fra", "french")
            "de", "ger", "deu", "german" -> setOf("de", "ger", "deu", "german")
            "it", "ita", "italian" -> setOf("it", "ita", "italian")
            "pt", "por", "portuguese" -> setOf("pt", "por", "portuguese")
            "ar", "ara", "arabic" -> setOf("ar", "ara", "arabic")
            "hi", "hin", "hindi" -> setOf("hi", "hin", "hindi")
            "ja", "jpn", "japanese" -> setOf("ja", "jpn", "japanese")
            "ko", "kor", "korean" -> setOf("ko", "kor", "korean")
            "zh", "chi", "zho", "chinese", "mandarin", "cantonese" -> setOf("zh", "chi", "zho", "chinese", "mandarin", "cantonese")
            "ru", "rus", "russian" -> setOf("ru", "rus", "russian")
            "tr", "tur", "turkish" -> setOf("tr", "tur", "turkish")
            else -> setOf(preferredLanguage.trim().lowercase(Locale.US))
        }
    }

    private fun preferredQualityScore(quality: String?, preferredQuality: String): Int {
        val preference = preferredQuality.trim().lowercase(Locale.US)
        if (preference == "best" || preference == "auto") return 0
        val normalized = quality.orEmpty().lowercase()
        val is4k = "2160" in normalized || "4k" in normalized || "uhd" in normalized
        val is1080 = "1080" in normalized
        val is720 = "720" in normalized
        return when (preference) {
            "4k", "2160p" -> if (is4k) 4 else -1
            "1080p" -> when {
                is1080 -> 4
                is4k -> -3
                else -> -1
            }
            "720p" -> when {
                is720 -> 4
                is4k -> -4
                is1080 -> -2
                else -> -1
            }
            else -> 0
        }
    }

    private fun parseQualityScore(quality: String?): Int {
        val normalized = quality.orEmpty().lowercase()
        return when {
            "2160" in normalized || "4k" in normalized -> 4
            "1080" in normalized -> 3
            "720" in normalized -> 2
            normalized.isNotBlank() -> 1
            else -> 0
        }
    }

    private fun guessContentType(url: String): String {
        val clean = url.substringBefore('?').lowercase()
        return when {
            clean.endsWith(".m3u8") -> "hls"
            clean.endsWith(".mpd") -> "dash"
            else -> "progressive"
        }
    }

    private fun describeStream(stream: AddonStream): String {
        return listOfNotNull(
            stream.addonName.takeIf { it.isNotBlank() },
            stream.quality,
            stream.size,
            stream.behaviorHints?.filename,
        ).joinToString(" | ").ifBlank { "Selected stream" }
    }

    private fun buildMagnetLink(infoHash: String, filename: String?): String {
        return "magnet:?xt=urn:btih:$infoHash" +
            if (filename.isNullOrBlank()) "" else "&dn=${URLEncoder.encode(filename, "UTF-8")}"
    }

    private fun buildStreamVideoId(baseId: String, episode: EpisodeContext?): String {
        return if (episode == null) baseId else "$baseId:${episode.seasonNumber}:${episode.episodeNumber}"
    }

    private fun buildEpisodeKey(episode: EpisodeContext?): String? {
        return episode?.let { "s${it.seasonNumber.toString().padStart(2, '0')}e${it.episodeNumber.toString().padStart(2, '0')}" }
    }

    private fun playbackCacheKey(
        mediaType: String,
        mediaId: String,
        imdbId: String?,
        episode: EpisodeContext?,
        preferredStreamKey: String?,
        preferredAddonName: String? = null,
        preferredQualityGroup: String? = null,
        streamType: String? = null,
    ): String {
        return listOf(
            mediaType,
            mediaId,
            imdbId.orEmpty(),
            buildEpisodeKey(episode).orEmpty(),
            preferredStreamKey.orEmpty(),
            preferredAddonName.orEmpty(),
            preferredQualityGroup.orEmpty(),
            streamType.orEmpty(),
        ).joinToString(":")
    }

    private fun invalidatePlaybackDerivedCaches() {
        libraryCache.clear()
        homeCache.clear()
        watchedHistoryCache.clear()
    }

    private fun buildSessionProfileCacheKey(): String {
        val userId = currentSession()?.user?.uid ?: "guest"
        val profileId = sessionStore.activeProfileId()?.takeIf { it.isNotBlank() } ?: "default"
        return "$userId:$profileId"
    }

    private fun watchedHistoryKey(mediaType: String, mediaId: String, episode: EpisodeContext?): String {
        return if (mediaType == "tv" && episode != null) {
            "tv:$mediaId:s${episode.seasonNumber}:e${episode.episodeNumber}"
        } else {
            "movie:$mediaId"
        }
    }

    private fun historyItemKey(item: TraktHistoryItem): String? {
        return when (item.type?.trim()?.lowercase(Locale.US)) {
            "movie" -> item.movie?.ids?.tmdb?.let { "movie:$it" }
            "episode" -> {
                val showId = item.show?.ids?.tmdb ?: return null
                val season = item.episode?.season ?: return null
                val episode = item.episode?.number ?: return null
                "tv:$showId:s$season:e$episode"
            }
            else -> null
        }
    }

    private fun parseIntroDbSegments(payload: Any?): List<PlaybackSegment> {
        val rawSegments = extractRawSegments(payload)
        val normalized = rawSegments.mapNotNull(::normalizeSegment)
            .sortedWith(compareBy<PlaybackSegment> { it.startSec }.thenBy { it.endSec }.thenBy { it.segmentType })
        val hasIntro = normalized.any { it.segmentType == "intro" }
        return if (hasIntro) normalized else normalized
    }

    private fun extractRawSegments(payload: Any?): List<Any?> {
        return when (payload) {
            is List<*> -> payload
            is Map<*, *> -> {
                when {
                    payload["segments"] is List<*> -> payload["segments"] as List<*>
                    payload["data"] is List<*> -> payload["data"] as List<*>
                    else -> listOfNotNull("intro", "recap", "outro", "credits")
                        .mapNotNull { key ->
                            val value = payload[key]
                            if (value is Map<*, *>) {
                                linkedMapOf<String, Any?>("segment_type" to key).apply {
                                    putAll(value.mapKeys { it.key.toString() })
                                }
                            } else {
                                null
                            }
                        }
                }
            }
            else -> emptyList()
        }
    }

    private fun normalizeSegment(raw: Any?): PlaybackSegment? {
        val map = raw as? Map<*, *> ?: return null
        val segmentType = normalizeSegmentType(
            map["segment_type"] ?: map["type"] ?: map["kind"]
        ) ?: return null
        val startSec = parseClockOrSeconds(
            map["start_sec"] ?: map["start"] ?: map["startSeconds"] ?: map["start_seconds"]
        ) ?: return null
        val endSec = parseClockOrSeconds(
            map["end_sec"] ?: map["end"] ?: map["endSeconds"] ?: map["end_seconds"]
        ) ?: return null
        if (endSec <= startSec) return null
        return PlaybackSegment(segmentType = segmentType, startSec = startSec, endSec = endSec)
    }

    private fun parseLegacyIntroSegment(payload: Any?): PlaybackSegment? {
        val map = payload as? Map<*, *> ?: return null
        val startSec = parseClockOrSeconds(map["start_sec"] ?: map["start"] ?: map["intro_start"]) ?: return null
        val endSec = parseClockOrSeconds(map["end_sec"] ?: map["end"] ?: map["intro_end"]) ?: return null
        if (endSec <= startSec) return null
        return PlaybackSegment(segmentType = "intro", startSec = startSec, endSec = endSec)
    }

    private fun fetchLegacyIntroPayload(imdbId: String, season: Int, episode: Int): Any? {
        val request = okhttp3.Request.Builder()
            .url("https://api.introdb.app/intro?imdb=${URLEncoder.encode(imdbId, "UTF-8")}&imdb_id=${URLEncoder.encode(imdbId, "UTF-8")}&season=$season&episode=$episode")
            .header("Accept", "application/json")
            .build()
        return api.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                null
            } else {
                api.gson.fromJson(response.body?.string().orEmpty(), Any::class.java)
            }
        }
    }

    private fun normalizeSegmentType(value: Any?): String? {
        return when (value?.toString()?.trim()?.lowercase(Locale.US)) {
            "intro" -> "intro"
            "recap" -> "recap"
            "outro", "credits", "credit" -> "outro"
            else -> null
        }
    }

    private fun parseClockOrSeconds(value: Any?): Double? {
        return when (value) {
            is Number -> value.toDouble().takeIf { it.isFinite() && it >= 0.0 }
            is String -> {
                val trimmed = value.trim()
                trimmed.toDoubleOrNull()?.takeIf { it >= 0.0 } ?: run {
                    val parts = trimmed.split(":").mapNotNull { it.trim().toDoubleOrNull() }
                    if (parts.size !in 2..3) {
                        null
                    } else if (parts.any { !it.isFinite() || it < 0.0 }) {
                        null
                    } else if (parts.size == 2) {
                        (parts[0] * 60.0) + parts[1]
                    } else {
                        (parts[0] * 3600.0) + (parts[1] * 60.0) + parts[2]
                    }
                }
            }
            else -> null
        }
    }

    private fun buildSyncMetadata(detail: MediaDetail?, episode: EpisodeContext?): Map<String, Any?> {
        return if (episode != null) {
            mapOf(
                "title" to detail?.title,
                "showTitle" to detail?.title,
                "posterUrl" to detail?.poster,
                "backdropUrl" to detail?.backdrop,
                "description" to detail?.description,
                "year" to detail?.year,
                "tmdbId" to detail?.tmdbId,
                "seasonNumber" to episode.seasonNumber,
                "episodeNumber" to episode.episodeNumber,
                "episodeTitle" to episode.title,
            )
        } else {
            mapOf(
                "title" to detail?.title,
                "posterUrl" to detail?.poster,
                "backdropUrl" to detail?.backdrop,
                "description" to detail?.description,
                "year" to detail?.year,
                "tmdbId" to detail?.tmdbId,
            )
        }
    }

    /**
     * Writes a settings change to both scopes: the account copy keeps devices that have no profile
     * selected in step, and the profile copy is what mobile and web read back for this viewer.
     * Sending only the account copy would leave the change invisible to them, and a later profile
     * write from another client would silently undo it.
     */
    private suspend fun patchPreferences(payload: Map<String, Any?>): Boolean {
        val response = api.patch<JsonObject>(
            "/account/preferences",
            mapOf("preferences" to payload),
        ) ?: return false
        if (!response.has("preferences")) return false
        return writeProfilePreferences(payload)
    }

    private suspend fun writeProfilePreferences(payload: Map<String, Any?>): Boolean {
        val profileId = sessionStore.activeProfileId()?.takeIf { it.isNotBlank() } ?: return true
        if (currentSession() == null) return true
        val changed = runCatching { api.gson.toJsonTree(payload).asJsonObject }.getOrNull() ?: return false
        // The whole blob is resent, so it has to be current: another client may have changed a
        // favourite channel since this one last read it, and settings writes are rare enough that
        // one extra read costs nothing.
        val current = api.get<ProfilePreferencesEnvelope>(
            "/profiles/${URLEncoder.encode(profileId, "UTF-8")}/preferences",
        )?.preferences ?: profilePreferencesState.value
        val next = PreferenceScopes.applyToProfileBlob(current, changed) ?: return true
        val response = api.put<ProfilePreferencesEnvelope>(
            "/profiles/${URLEncoder.encode(profileId, "UTF-8")}/preferences",
            mapOf("preferences" to next),
        )
        if (response == null) {
            TvDebugLogger.w("Preferences", "profile preference sync failed; account copy was still saved")
            return false
        }
        profilePreferencesState.value = response.preferences ?: next
        return true
    }

    /**
     * Resume points held by the tracking service, which complement the ones this account recorded
     * itself. MDBList has no playback API, so a profile using it simply contributes nothing here
     * and falls back to Trakt if that is still connected.
     */
    private suspend fun fetchServicePlayback(): List<ContinueWatchingItem> {
        val session = currentSession() ?: return emptyList()
        for (service in syncServiceChain { it.playback }) {
            val results = runCatching {
                api.get<TraktPlaybackResponse>("/$service/sync/playback", session)?.results
            }.onFailure {
                TvDebugLogger.e("Library", "continue-watching read failed on $service", it)
            }.getOrNull()
            if (results != null) return results
        }
        return emptyList()
    }

    private fun mergeContinueWatching(
        primary: List<ContinueWatchingItem>,
        secondary: List<ContinueWatchingItem>,
    ): List<ContinueWatchingItem> {
        if (secondary.isEmpty()) return primary
        val merged = linkedMapOf<String, ContinueWatchingItem>()
        (primary + secondary).forEach { item ->
            val key = listOf(
                item.type,
                item.id,
                item.episodeKey.orEmpty(),
            ).joinToString(":")
            val existing = merged[key]
            merged[key] = when {
                existing == null -> item
                (existing.progress ?: 0.0) <= 0.0 && (item.progress ?: 0.0) > 0.0 -> item
                (existing.positionSec ?: existing.resumeAt ?: 0.0) <= 0.0 && (item.positionSec ?: item.resumeAt ?: 0.0) > 0.0 -> item
                existing.poster.isNullOrBlank() && !item.poster.isNullOrBlank() -> item
                else -> existing
            }
        }
        return merged.values.toList()
    }

    private suspend inline fun <reified T> safeResults(path: String): List<MediaItem> {
        return runCatching { api.get<T>(path) }
            .getOrNull()
            .extractResults()
    }

    private fun Any?.extractResults(): List<MediaItem> = when (this) {
        is RailResponse -> results
        is NetworkResponse -> results.map { network ->
            MediaItem(
                id = network.id.toString(),
                tmdbId = network.id,
                title = network.name,
                type = "network",
                titleLogo = network.logo,
                poster = network.logo,
            )
        }
        else -> emptyList()
    }

    private fun persistSession(response: AuthResponse): AuthSession {
        val token = response.token ?: error("Missing auth token")
        val session = AuthSession(
            token = token,
            user = normalizeUser(response.user, token),
        )
        sessionStore.saveSession(session)
        return session
    }

    private fun normalizeUser(payload: AuthUserPayload?, token: String): SessionUser {
        return SessionUser(
            uid = payload?.uid ?: payload?.id ?: error("Missing user id"),
            email = payload?.email,
            displayName = payload?.displayName,
            subscriptionStatus = payload?.subscriptionStatus ?: "free",
            accessToken = token,
        )
    }

    private fun <K, V> lruCache(maxEntries: Int): MutableMap<K, V> {
        return object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
                return size > maxEntries
            }
        }
    }
}


