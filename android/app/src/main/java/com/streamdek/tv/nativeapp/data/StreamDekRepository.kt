package com.streamdek.tv.nativeapp.data

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.supervisorScope
import java.net.URLEncoder
import java.time.Instant

class StreamDekRepository(
    private val sessionStore: AuthSessionStore,
    private val api: StreamDekApi = StreamDekApi(sessionStore),
) {
    private val detailsCache = mutableMapOf<String, MediaDetail>()
    private val seasonCache = mutableMapOf<String, SeasonDetail>()
    private val homeCache = mutableMapOf<String, HomeContent>()
    private val libraryCache = mutableMapOf<String, LibraryResponse>()
    private val searchCache = mutableMapOf<String, List<MediaItem>>()
    private val networkCache = mutableMapOf<String, PagedRailResponse>()
    private val genreCache = mutableMapOf<String, List<GenreItem>>()
    private val resolvedPlaybackCache = mutableMapOf<String, ResolvedPlaybackCandidate>()
    private val bootstrapState = MutableStateFlow<AccountBootstrap?>(null)
    private var lastPlaybackRequest: PlaybackRequest? = null

    val session: StateFlow<AuthSession?> = sessionStore.session
    val bootstrap: StateFlow<AccountBootstrap?> = bootstrapState

    fun currentSession(): AuthSession? = sessionStore.currentSession()

    fun savePlaybackRequest(request: PlaybackRequest) {
        lastPlaybackRequest = request
    }

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
        return api.post<TvSessionInfo>("/auth/tv/session", emptyMap<String, String>(), session = null)
            ?: error("Could not create TV sign-in session")
    }

    suspend fun pollTvSession(deviceCode: String): TvPollResult {
        return api.post<TvPollResult>("/auth/tv/token", mapOf("device_code" to deviceCode), session = null)
            ?: TvPollResult(status = "invalid_grant")
    }

    suspend fun completeTvSession(result: TvPollResult): AuthSession {
        val token = result.token ?: error("Missing TV auth token")
        val session = AuthSession(
            token = token,
            user = normalizeUser(result.user, token),
        )
        sessionStore.saveSession(session)
        refreshBootstrap()
        return session
    }

    fun signOut() {
        sessionStore.clearSession()
        bootstrapState.value = null
        detailsCache.clear()
        seasonCache.clear()
        homeCache.clear()
        libraryCache.clear()
        searchCache.clear()
        networkCache.clear()
        genreCache.clear()
        resolvedPlaybackCache.clear()
    }

    suspend fun refreshBootstrap(): AccountBootstrap? {
        val session = currentSession() ?: run {
            bootstrapState.value = null
            return null
        }
        val bootstrap = api.get<AccountBootstrap>("/account/bootstrap", session)
        bootstrapState.value = bootstrap
        return bootstrap
    }

    suspend fun updatePlaybackPreferences(partial: Map<String, Any?>): AccountBootstrap? {
        val existing = bootstrapState.value?.preferences?.playback ?: PlaybackPreferences()
        api.patch<Map<String, PreferencesEnvelope>>(
            "/account/preferences",
            mapOf(
                "preferences" to mapOf(
                    "playback" to mapOf(
                        "autoplayNextEpisode" to (partial["autoplayNextEpisode"] ?: existing.autoplayNextEpisode),
                        "defaultSubtitleLanguage" to (partial["defaultSubtitleLanguage"] ?: existing.defaultSubtitleLanguage),
                        "defaultAudioLanguage" to (partial["defaultAudioLanguage"] ?: existing.defaultAudioLanguage),
                        "resolvedPlaybackUrlCacheTtl" to (partial["resolvedPlaybackUrlCacheTtl"] ?: existing.resolvedPlaybackUrlCacheTtl),
                    ),
                ),
            ),
        )
        return refreshBootstrap()
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

    suspend fun fetchHomeContent(forceRefresh: Boolean = false): HomeContent {
        val cacheKey = currentSession()?.user?.uid ?: "guest"
        if (!forceRefresh) {
            homeCache[cacheKey]?.let { return it }
        }

        val content = supervisorScope {
            val trendingMovie = async { api.get<RailResponse>("/tmdb/trending/movie")?.results.orEmpty() }
            val trendingTv = async { api.get<RailResponse>("/tmdb/trending/tv")?.results.orEmpty() }
            val popularMovie = async { api.get<RailResponse>("/tmdb/popular/movie")?.results.orEmpty() }
            val popularTv = async { api.get<RailResponse>("/tmdb/popular/tv")?.results.orEmpty() }
            val browseMovie = async { api.get<RailResponse>("/tmdb/browse/movie")?.results.orEmpty() }
            val browseTv = async { api.get<RailResponse>("/tmdb/browse/tv")?.results.orEmpty() }
            val networks = async { api.get<NetworkResponse>("/tmdb/networks")?.results.orEmpty() }
            val recMovie = async { api.get<RailResponse>("/trakt/recommendations/movies")?.results.orEmpty() }
            val recTv = async { api.get<RailResponse>("/trakt/recommendations/shows")?.results.orEmpty() }
            val library = async { fetchLibrary() }

            val trendingMovies = trendingMovie.await()
            val trendingShows = trendingTv.await()
            val popularMovies = popularMovie.await().ifEmpty { trendingMovies }
            val popularShows = popularTv.await().ifEmpty { trendingShows }
            val browseMovies = browseMovie.await().ifEmpty { popularMovies }
            val browseShows = browseTv.await().ifEmpty { popularShows }
            val streamingNetworks = networks.await().map { network ->
                MediaItem(
                    id = network.id.toString(),
                    tmdbId = network.id,
                    title = network.name,
                    type = "network",
                    titleLogo = network.logo,
                    poster = network.logo,
                )
            }
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
                }.filter { it.items.isNotEmpty() }
            )
        }

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
        val library = api.get<LibraryResponse>("/sync/library") ?: LibraryResponse()
        libraryCache[cacheKey] = library
        return library
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
    }

    suspend fun fetchProgress(mediaType: String, mediaId: String, episode: EpisodeContext? = null): PlaybackProgressRecord? {
        val episodeKey = buildEpisodeKey(episode)
        val query = buildString {
            append("/sync/progress?entityType=$mediaType&entityId=$mediaId")
            if (episodeKey != null) append("&episodeKey=$episodeKey")
        }
        return api.get<PlaybackProgressResponse>(query)?.progress
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

    suspend fun resolvePlayback(
        mediaType: String,
        mediaId: String,
        imdbId: String?,
        episode: EpisodeContext? = null,
        forceRefresh: Boolean = false,
    ): ResolvedPlaybackCandidate {
        val cacheKey = playbackCacheKey(mediaType, mediaId, imdbId, episode)
        if (!forceRefresh) {
            resolvedPlaybackCache[cacheKey]?.let { return it }
        }
        val lookupType = if (mediaType == "tv") "series" else "movie"
        val videoId = buildStreamVideoId(imdbId ?: mediaId, episode)
        val streams = api.get<AddonStreamsResponse>("/addons/streams/$lookupType/$videoId")?.streams.orEmpty()
        for (stream in rankStreams(streams)) {
            val resolvedUrl = resolveStreamToUrl(stream)
            if (!resolvedUrl.isNullOrBlank()) {
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
        resolvePlayback(mediaType, mediaId, imdbId, episode, forceRefresh = false)
    }

    private suspend fun resolveStreamToUrl(stream: AddonStream): String? {
        stream.url?.let { return it }
        val infoHash = stream.infoHash ?: return null
        val filename = stream.behaviorHints?.filename ?: stream.title ?: stream.name
        val magnetLink = buildMagnetLink(infoHash, filename)
        val debrid = api.post<DebridResolveResponse>(
            "/debrid/resolve",
            mapOf(
                "infoHash" to infoHash,
                "magnetLink" to magnetLink,
                "filename" to filename,
            ),
        )
        if (!debrid?.url.isNullOrBlank()) return debrid?.url
        val torrent = api.post<TorrentResolveResponse>(
            "/stream/torrent/add",
            mapOf(
                "infoHash" to infoHash,
                "magnetLink" to magnetLink,
                "filename" to filename,
            ),
        )
        return torrent?.streamUrl
    }

    private fun rankStreams(streams: List<AddonStream>): List<AddonStream> {
        val preferredQuality = bootstrapState.value?.preferences?.playback?.preferredQuality ?: "best"
        return streams.sortedWith(
            compareByDescending<AddonStream> { if (!it.url.isNullOrBlank()) 3 else 0 }
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
    ): String {
        return listOf(mediaType, mediaId, imdbId.orEmpty(), buildEpisodeKey(episode).orEmpty()).joinToString(":")
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
}
