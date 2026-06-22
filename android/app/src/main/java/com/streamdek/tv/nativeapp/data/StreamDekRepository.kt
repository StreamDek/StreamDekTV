package com.streamdek.tv.nativeapp.data

import com.streamdek.tv.BuildConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.supervisorScope
import java.util.LinkedHashMap
import java.util.Locale
import java.net.URLEncoder
import java.time.Instant

// Stremio-native catalog types that represent live content. Native 'tv' means
// live television channels — series catalogs use 'series'.
private val LIVE_ADDON_CATALOG_TYPES = setOf(
    "tv", "channel", "channels", "event", "events", "live", "sport", "sports",
)

private const val MAX_ADDON_RAIL_TITLE_LENGTH = 30

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
    private val watchedHistoryCache = lruCache<String, Set<String>>(4)
    private val bootstrapState = MutableStateFlow<AccountBootstrap?>(null)
    private val fusionBadgeSourcesState = MutableStateFlow<Map<String, FusionBadgeSource>>(emptyMap())
    private var lastPlaybackRequest: PlaybackRequest? = null

    val session: StateFlow<AuthSession?> = sessionStore.session
    val bootstrap: StateFlow<AccountBootstrap?> = bootstrapState
    val fusionBadgeSources: StateFlow<Map<String, FusionBadgeSource>> = fusionBadgeSourcesState

    fun currentSession(): AuthSession? = sessionStore.currentSession()

    fun savePlaybackRequest(request: PlaybackRequest) {
        lastPlaybackRequest = request
    }

    fun currentPlaybackRequest(): PlaybackRequest? = lastPlaybackRequest

    fun consumePlaybackRequest(): PlaybackRequest? = lastPlaybackRequest

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
        detailsCache.clear()
        seasonCache.clear()
        homeCache.clear()
        libraryCache.clear()
        searchCache.clear()
        networkCache.clear()
        genreCache.clear()
        resolvedPlaybackCache.clear()
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
                    "decoderMode" to (partial["decoderMode"] ?: existing.decoderMode),
                    "renderSurface" to (partial["renderSurface"] ?: existing.renderSurface),
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
        includeCatalog: (rawType: String, mappedType: String) -> Boolean = { _, _ -> true },
    ): List<AddonCatalogCollection> {
        val addons = runCatching { fetchAddonManifests() }.getOrDefault(emptyList())
            .filter { it.enabled }
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
                        val items = metas.mapNotNull { normalizeAddonCatalogMeta(it, mappedType, rawType) }
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
                items = collection.items,
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

    private fun normalizeAddonCatalogMeta(
        meta: AddonCatalogMetaItem,
        fallbackType: String,
        nativeFallbackType: String,
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
        )
    }

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
        val cacheKey = currentSession()?.user?.uid ?: "guest"
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
                    addAll(addonRails.await())
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
        val cacheKey = currentSession()?.user?.uid ?: "guest"
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
    ): ResolvedPlaybackCandidate {
        val episodeKey = buildEpisodeKey(episode)
        val effectivePreferredStreamKey = preferredStreamKey
            ?: sessionStore.preferredStreamKey(mediaType, mediaId, episodeKey)
        val cacheKey = playbackCacheKey(
            mediaType = mediaType,
            mediaId = mediaId,
            imdbId = imdbId,
            episode = episode,
            preferredStreamKey = effectivePreferredStreamKey,
            preferredAddonName = preferredAddonName,
            preferredQualityGroup = preferredQualityGroup,
        )
        if (!forceRefresh) {
            resolvedPlaybackCache[cacheKey]?.let { return it }
        }
        // Live items request streams with the addon's native type; Stremio-native
        // 'tv' (live channels) goes out as 'live-tv' so the backend doesn't
        // confuse it with the app-internal 'tv' (= series).
        val lookupType = when (mediaType) {
            "live" -> {
                val native = streamType?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() } ?: "tv"
                if (native == "tv") "live-tv" else native
            }
            "tv" -> "series"
            else -> "movie"
        }
        val videoId = buildStreamVideoId(imdbId ?: mediaId, episode)
        val streams = api.get<AddonStreamsResponse>("/addons/streams/$lookupType/$videoId")?.streams.orEmpty()
        for (stream in rankStreams(streams, effectivePreferredStreamKey, preferredAddonName, preferredQualityGroup)) {
            val resolvedUrl = resolveStreamToUrl(stream)
            if (!resolvedUrl.isNullOrBlank()) {
                val resolvedStreamKey = streamSelectionKey(stream)
                val candidate = ResolvedPlaybackCandidate(
                    source = ResolvedPlaybackSource(
                        url = resolvedUrl,
                        contentType = guessContentType(resolvedUrl),
                        label = describeStream(stream),
                        filename = stream.behaviorHints?.filename ?: stream.title ?: stream.name,
                    ),
                    stream = stream,
                    streams = streams,
                )
                sessionStore.savePreferredStreamKey(mediaType, mediaId, episodeKey, resolvedStreamKey)
                resolvedPlaybackCache[cacheKey] = candidate
                return candidate
            }
        }
        return ResolvedPlaybackCandidate(null, null, streams).also {
            resolvedPlaybackCache[cacheKey] = it
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

    suspend fun resolvePlaybackSource(stream: AddonStream): ResolvedPlaybackSource? {
        val resolvedUrl = resolveStreamToUrl(stream) ?: return null
        return ResolvedPlaybackSource(
            url = resolvedUrl,
            contentType = guessContentType(resolvedUrl),
            label = describeStream(stream),
            filename = stream.behaviorHints?.filename ?: stream.title ?: stream.name,
        )
    }

    suspend fun fetchEpisodeSegments(
        imdbId: String,
        season: Int,
        episode: Int,
    ): List<PlaybackSegment> {
        val params = "imdb_id=${URLEncoder.encode(imdbId, "UTF-8")}&season=$season&episode=$episode"
        return runCatching {
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
        }.getOrDefault(emptyList())
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

    private suspend fun resolveStreamToUrl(stream: AddonStream): String? {
        stream.url?.let { return it }
        val infoHash = stream.infoHash ?: return null
        val filename = stream.behaviorHints?.filename ?: stream.title ?: stream.name
        val magnetLink = buildMagnetLink(infoHash, filename)
        return runCatching {
            val debrid = api.post<DebridResolveResponse>(
                "/debrid/resolve",
                mapOf(
                    "infoHash" to infoHash,
                    "magnetLink" to magnetLink,
                    "filename" to filename,
                ),
            )
            if (!debrid?.url.isNullOrBlank()) return@runCatching debrid.url
            val torrent = api.post<TorrentResolveResponse>(
                "/stream/torrent/add",
                mapOf(
                    "infoHash" to infoHash,
                    "magnetLink" to magnetLink,
                    "filename" to filename,
                ),
            )
            torrent?.streamUrl
        }.onFailure {
            TvDebugLogger.e("Playback", "resolveStreamToUrl failed infoHash=$infoHash", it)
        }.getOrNull()
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
        return streams.sortedWith(
            compareByDescending<AddonStream> { if (preferredStreamKey != null && streamSelectionKey(it) == preferredStreamKey) 10 else 0 }
                .thenByDescending {
                    if (!normalizedPreferredAddon.isNullOrBlank() && it.addonName.trim().lowercase(Locale.US) == normalizedPreferredAddon) 6 else 0
                }
                .thenByDescending {
                    if (!normalizedPreferredQuality.isNullOrBlank() && it.quality?.trim()?.lowercase(Locale.US) == normalizedPreferredQuality) 4 else 0
                }
                .thenByDescending { if (!it.url.isNullOrBlank()) 3 else 0 }
                .thenByDescending { if (it.cachedBy.isNotEmpty()) 2 else 0 }
                .thenByDescending { if (!it.infoHash.isNullOrBlank()) 1 else 0 }
                .thenByDescending { preferredQualityScore(it.quality, preferredQuality) }
                .thenByDescending { parseQualityScore(it.quality) }
        )
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
    ): String {
        return listOf(
            mediaType,
            mediaId,
            imdbId.orEmpty(),
            buildEpisodeKey(episode).orEmpty(),
            preferredStreamKey.orEmpty(),
            preferredAddonName.orEmpty(),
            preferredQualityGroup.orEmpty(),
        ).joinToString(":")
    }

    private fun invalidatePlaybackDerivedCaches() {
        libraryCache.clear()
        homeCache.clear()
        watchedHistoryCache.clear()
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
            .url("https://api.introdb.app/intro?imdb=${URLEncoder.encode(imdbId, "UTF-8")}&season=$season&episode=$episode")
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

