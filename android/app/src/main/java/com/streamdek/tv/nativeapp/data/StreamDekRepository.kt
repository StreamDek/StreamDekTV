package com.streamdek.tv.nativeapp.data

import com.streamdek.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val fusionBadgeSourcesState = MutableStateFlow<Map<String, FusionBadgeSource>>(emptyMap())
    private val favouriteChannelsState = MutableStateFlow(sessionStore.loadFavouriteChannels())
    private var lastPlaybackRequest: PlaybackRequest? = null

    val session: StateFlow<AuthSession?> = sessionStore.session
    val bootstrap: StateFlow<AccountBootstrap?> = bootstrapState
    val fusionBadgeSources: StateFlow<Map<String, FusionBadgeSource>> = fusionBadgeSourcesState
    val favouriteChannels: StateFlow<List<MediaItem>> = favouriteChannelsState

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
    }

    private fun reloadFavouriteChannels() {
        favouriteChannelsState.value = sessionStore.loadFavouriteChannels()
    }
    fun currentSession(): AuthSession? = sessionStore.currentSession()

    fun savePlaybackRequest(request: PlaybackRequest) {
        lastPlaybackRequest = request
    }

    fun currentPlaybackRequest(): PlaybackRequest? = lastPlaybackRequest

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
    }

    suspend fun refreshBootstrap(): AccountBootstrap? {
        val session = currentSession() ?: run {
            bootstrapState.value = null
            TvDebugLogger.w("Bootstrap", "refreshBootstrap skipped: no session")
            return null
        }
        TvDebugLogger.i("Bootstrap", "refreshBootstrap start user=${session.user.uid}")
        var bootstrap = api.get<AccountBootstrap>("/account/bootstrap", session)
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
                    bootstrap = api.get<AccountBootstrap>("/account/bootstrap", session) ?: bootstrap
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
        return bootstrap
    }

    suspend fun updatePlaybackPreferences(partial: Map<String, Any?>): AccountBootstrap? {
        val existing = bootstrapState.value?.preferences?.playback ?: PlaybackPreferences()
        patchPreferences(
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
                ),
            ),
        )
        return refreshBootstrap()
    }

    suspend fun updateAppPreferences(partial: Map<String, Any?>): AccountBootstrap? {
        val existing = bootstrapState.value?.preferences?.app ?: AppPreferences()
        patchPreferences(
            mapOf(
                "app" to mapOf(
                    "theme" to (partial["theme"] ?: existing.theme),
                    "colorMode" to (partial["colorMode"] ?: existing.colorMode),
                    "startScreen" to (partial["startScreen"] ?: existing.startScreen),
                    "homeRowCardStyle" to (partial["homeRowCardStyle"] ?: existing.homeRowCardStyle),
                    "compactMode" to (partial["compactMode"] ?: existing.compactMode),
                    "syncOverCellular" to (partial["syncOverCellular"] ?: existing.syncOverCellular),
                ),
            ),
        )
        return refreshBootstrap()
    }

    suspend fun updateStreamsPreferences(partial: Map<String, Any?>): AccountBootstrap? {
        val existing = bootstrapState.value?.preferences?.streams ?: StreamsPreferences()
        patchPreferences(
            mapOf(
                "streams" to mapOf(
                    "fusionBadgesEnabled" to (partial["fusionBadgesEnabled"] ?: existing.fusionBadgesEnabled),
                    "showSizeBadges" to (partial["showSizeBadges"] ?: existing.showSizeBadges),
                    "badgePosition" to (partial["badgePosition"] ?: existing.badgePosition),
                    "fusionBadgeUrls" to (partial["fusionBadgeUrls"] ?: existing.fusionBadgeUrls),
                    "activeFusionBadgeUrl" to (if (partial.containsKey("activeFusionBadgeUrl")) partial["activeFusionBadgeUrl"] else existing.activeFusionBadgeUrl),
                ),
            ),
        )
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
        if (forceRefresh) {
            refreshBootstrap()
        }
        return api.get<List<AddonManifest>>("/addons/manifests")
            ?: bootstrapState.value?.integrations?.addons?.items.orEmpty()
    }

    suspend fun toggleAddon(id: String, enabled: Boolean) {
        api.post<Map<String, String>>("/addons/toggle", mapOf("id" to id, "enabled" to enabled))
        refreshBootstrap()
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

    suspend fun fetchHomeContent(forceRefresh: Boolean = false): HomeContent {
        val cacheKey = buildSessionProfileCacheKey()
        if (!forceRefresh) {
            homeCache[cacheKey]?.let { return it }
        }
        TvDebugLogger.i("Home", "fetchHomeContent forceRefresh=$forceRefresh user=$cacheKey")

        val content = supervisorScope {
            val trendingMovie = async { safeResults<RailResponse>("/tmdb/trending/movie") }
            val trendingTv = async { safeResults<RailResponse>("/tmdb/trending/tv") }
            val popularMovie = async { safeResults<RailResponse>("/tmdb/popular/movie") }
            val popularTv = async { safeResults<RailResponse>("/tmdb/popular/tv") }
            val browseMovie = async { safeResults<RailResponse>("/tmdb/browse/movie") }
            val browseTv = async { safeResults<RailResponse>("/tmdb/browse/tv") }
            val networks = async { safeResults<NetworkResponse>("/tmdb/networks") }
            val recMovie = async { safeResults<RailResponse>("/trakt/recommendations/movies") }
            val recTv = async { safeResults<RailResponse>("/trakt/recommendations/shows") }
            val library = async { runCatching { fetchLibrary() }.getOrDefault(LibraryResponse()) }
            val addonRails = async { runCatching { fetchAddonCatalogRails() }.getOrDefault(emptyList()) }

            val trendingMovies = trendingMovie.await()
            val trendingShows = trendingTv.await()
            val popularMovies = popularMovie.await().ifEmpty { trendingMovies }
            val popularShows = popularTv.await().ifEmpty { trendingShows }
            val browseMovies = browseMovie.await().ifEmpty { popularMovies }
            val browseShows = browseTv.await().ifEmpty { popularShows }
            val streamingNetworks = networks.await()
            val recommendedMovies = recMovie.await().ifEmpty { popularMovies }
            val recommendedShows = recTv.await().ifEmpty { popularShows }
            val continueWatching = library.await().continueWatching.map {
                MediaItem(
                    id = it.id,
                    tmdbId = it.tmdbId,
                    title = it.title,
                    type = it.type,
                    poster = it.poster,
                    backdrop = it.backdrop,
                    description = it.description,
                    rating = it.rating,
                    year = it.year,
                    titleLogo = null,
                    progress = it.progress,
                    positionSec = it.positionSec ?: it.resumeAt,
                    durationSec = it.durationSec,
                    episode = it.episode,
                )
            }

            val recentlyAdded = (browseMovies + browseShows)
                .distinctBy { "${it.type}:${it.id}" }
                .sortedByDescending { it.year?.toIntOrNull() ?: 0 }
                .take(20)
            val heroCandidates = trendingMovies + popularMovies + trendingShows + popularShows

            HomeContent(
                featured = heroCandidates.firstOrNull(),
                rails = buildList {
                    if (continueWatching.isNotEmpty()) {
                        add(HomeRail("continue-watching", "Continue Watching", continueWatching))
                    }
                    add(HomeRail("popular-movies", "Popular Movies", popularMovies))
                    add(HomeRail("popular-series", "Popular Series", popularShows))
                    add(HomeRail("trending", "Trending", (trendingMovies + trendingShows).take(20)))
                    add(HomeRail("recently-added", "Recently Added", recentlyAdded))
                    if (streamingNetworks.isNotEmpty()) {
                        add(HomeRail("networks", "Streaming Services", streamingNetworks))
                    }
                    add(HomeRail("recommended", "Recommended For You", (recommendedMovies + recommendedShows).take(20)))
                }.let { baseRails ->
                    // Match the mobile app's ordering: live TV addon rows sit directly
                    // below Streaming Services, other addon catalogs stay at the end.
                    val allAddonRails = addonRails.await()
                    val (liveAddonRails, otherAddonRails) = allAddonRails.partition { it.isLive }
                    val ordered = baseRails.toMutableList()
                    val networksIndex = ordered.indexOfFirst { it.id == "networks" }
                    if (liveAddonRails.isNotEmpty()) {
                        if (networksIndex >= 0) {
                            ordered.addAll(networksIndex + 1, liveAddonRails)
                        } else {
                            ordered.addAll(liveAddonRails)
                        }
                    }
                    ordered.addAll(otherAddonRails)
                    ordered
                }.filter { it.items.isNotEmpty() }
            )
        }

        TvDebugLogger.i(
            "Home",
            "fetchHomeContent ok featured=${content.featured?.id ?: "none"} rails=${content.rails.joinToString { "${it.id}:${it.items.size}" }}",
        )
        homeCache[cacheKey] = content
        return content
    }

    suspend fun fetchDetail(id: String, type: String, forceRefresh: Boolean = false): MediaDetail? {
        val cacheKey = "$type:$id"
        if (!forceRefresh) {
            detailsCache[cacheKey]?.let { return it }
        }
        val detail = api.get<MediaDetail>("/tmdb/details/$type/$id")
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
        val library = runCatching {
            api.get<LibraryResponse>("/sync/library")
        }.onFailure {
            TvDebugLogger.e("Library", "fetchLibrary failed", it)
        }.getOrNull() ?: LibraryResponse()
        val traktContinueWatching = fetchTraktContinueWatching()
        val mergedContinueWatching = mergeContinueWatching(
            primary = library.continueWatching,
            secondary = traktContinueWatching,
        )
        val merged = library.copy(
            continueWatching = mergedContinueWatching,
        )
        TvDebugLogger.i(
            "Library",
            "fetchLibrary ok continue=${merged.continueWatching.size} watchlist=${merged.watchlist.size} traktContinue=${traktContinueWatching.size}",
        )
        libraryCache[cacheKey] = merged
        return merged
    }

    suspend fun searchMedia(query: String, forceRefresh: Boolean = false): List<MediaItem> {
        val normalized = query.trim()
        if (normalized.isBlank()) return emptyList()
        val cacheKey = normalized.lowercase()
        if (!forceRefresh) {
            searchCache[cacheKey]?.let { return it }
        }
        val encoded = URLEncoder.encode(normalized, "UTF-8")
        val results = api.get<PagedRailResponse>("/tmdb/search?q=$encoded")?.results.orEmpty()
            .filter { it.type == "movie" || it.type == "tv" }
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

    suspend fun addToWatchlist(item: MediaItem) {
        val tmdbId = item.tmdbId.takeIf { it > 0 } ?: item.id.toIntOrNull()
        val entry: Map<String, Any?> = mapOf(
            "title" to item.title,
            "year" to item.year?.toIntOrNull(),
            "ids" to mapOf<String, Any?>("tmdb" to tmdbId),
        )
        api.post<Map<String, Any>>(
            "/trakt/sync/watchlist/add",
            if (item.type == "tv") mapOf("movies" to emptyList<Any>(), "shows" to listOf(entry))
            else mapOf("movies" to listOf(entry), "shows" to emptyList<Any>()),
        )
        fetchLibrary(forceRefresh = true)
    }

    suspend fun removeFromWatchlist(item: MediaItem) {
        val tmdbId = item.tmdbId.takeIf { it > 0 } ?: item.id.toIntOrNull()
        val entry: Map<String, Any?> = mapOf(
            "title" to item.title,
            "year" to item.year?.toIntOrNull(),
            "ids" to mapOf<String, Any?>("tmdb" to tmdbId),
        )
        api.post<Map<String, Any>>(
            "/trakt/sync/watchlist/remove",
            if (item.type == "tv") mapOf("movies" to emptyList<Any>(), "shows" to listOf(entry))
            else mapOf("movies" to listOf(entry), "shows" to emptyList<Any>()),
        )
        fetchLibrary(forceRefresh = true)
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
        sessionStore.setActiveProfileId(profileId)
        reloadFavouriteChannels()
        libraryCache.clear()
        homeCache.clear()
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


    private fun rememberLastSourceEnabled(): Boolean =
        bootstrapState.value?.preferences?.playback?.rememberLastSource ?: true

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
        if (preferredQuality == "best") return 0
        val normalized = quality.orEmpty().lowercase()
        val is4k = "2160" in normalized || "4k" in normalized || "uhd" in normalized
        val is1080 = "1080" in normalized
        val is720 = "720" in normalized
        return when (preferredQuality) {
            "4k" -> if (is4k) 4 else -1
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

    private suspend fun patchPreferences(payload: Map<String, Any?>) {
        api.patch<Map<String, PreferencesEnvelope>>(
            "/account/preferences",
            mapOf("preferences" to payload),
        )
    }

    private suspend fun fetchTraktContinueWatching(): List<ContinueWatchingItem> {
        val session = currentSession() ?: return emptyList()
        val traktConnected = bootstrapState.value?.syncStatus?.traktConnected == true ||
            bootstrapState.value?.integrations?.trakt?.connected == true
        if (!traktConnected) return emptyList()
        return runCatching {
            api.get<TraktPlaybackResponse>("/trakt/sync/playback", session)?.results.orEmpty()
        }.onFailure {
            TvDebugLogger.e("Library", "fetchTraktContinueWatching failed", it)
        }.getOrDefault(emptyList())
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


