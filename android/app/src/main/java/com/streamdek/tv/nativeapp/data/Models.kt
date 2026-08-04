package com.streamdek.tv.nativeapp.data

import com.google.gson.annotations.SerializedName

data class SessionUser(
    val uid: String,
    val email: String? = null,
    val displayName: String? = null,
    val subscriptionStatus: String = "free",
    val accessToken: String,
)

data class AuthSession(
    val token: String,
    val user: SessionUser,
)

data class AuthResponse(
    val token: String? = null,
    val user: AuthUserPayload? = null,
)

data class AuthUserPayload(
    val id: String? = null,
    val uid: String? = null,
    val email: String? = null,
    val displayName: String? = null,
    val subscriptionStatus: String? = null,
)

data class TvSessionInfo(
    @SerializedName("device_code") val deviceCode: String,
    @SerializedName("user_code") val userCode: String,
    @SerializedName("verification_url") val verificationUrl: String,
    @SerializedName("verification_uri_complete") val verificationUriComplete: String,
    @SerializedName("expires_in") val expiresIn: Int,
    val interval: Int,
)

data class TvPollResult(
    val status: String,
    val token: String? = null,
    val user: AuthUserPayload? = null,
)

data class MediaItem(
    val id: String,
    val tmdbId: Int = 0,
    val title: String,
    val type: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val description: String? = null,
    val rating: Double? = null,
    val year: String? = null,
    val titleLogo: String? = null,
    val progress: Double? = null,
    val positionSec: Double? = null,
    val durationSec: Double? = null,
    val episode: EpisodeContext? = null,
    /** Stremio-native stream type for live addon items (e.g. 'tv', 'events', 'sport'). */
    val streamType: String? = null,
    val sourceAddonId: String? = null,
    val sourceAddonName: String? = null,
    val sourceCatalogId: String? = null,
    val sourceCatalogName: String? = null,
    val directStreamUrl: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
)

data class NetworkItem(
    val id: Int,
    val name: String,
    val logo: String? = null,
)

data class CastMember(
    val id: Int,
    val name: String,
    val character: String? = null,
    val photo: String? = null,
)

data class SeasonRef(
    @SerializedName("season_number") val seasonNumber: Int,
    val name: String,
)

data class MediaDetail(
    val id: String,
    val tmdbId: Int = 0,
    val title: String,
    val type: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val description: String? = null,
    val rating: Double? = null,
    val year: String? = null,
    val imdbId: String? = null,
    val titleLogo: String? = null,
    val trailerKey: String? = null,
    val trailerSite: String? = null,
    val genreNames: List<String> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val seasons: List<SeasonRef> = emptyList(),
    val similarTitles: List<MediaItem> = emptyList(),
    val runtime: Int? = null,
    val releaseDate: String? = null,
    val tagline: String? = null,
    val status: String? = null,
    val numberOfSeasons: Int? = null,
    val numberOfEpisodes: Int? = null,
)

data class SeasonEpisode(
    val id: Int,
    @SerializedName("episode_number") val episodeNumber: Int,
    val name: String,
    val overview: String? = null,
    val still: String? = null,
    val runtime: Int? = null,
    @SerializedName("air_date") val airDate: String? = null,
)

data class SeasonDetail(
    val seasonNumber: Int,
    val name: String,
    val overview: String? = null,
    val episodes: List<SeasonEpisode> = emptyList(),
)

data class RailResponse(
    val results: List<MediaItem> = emptyList(),
)

data class NetworkResponse(
    val results: List<NetworkItem> = emptyList(),
)

data class GenreItem(
    val id: Int,
    val name: String,
)

data class GenreResponse(
    val genres: List<GenreItem> = emptyList(),
)

data class PagedRailResponse(
    val results: List<MediaItem> = emptyList(),
    val total_pages: Int = 1,
    val page: Int = 1,
)

data class TraktCommentItem(
    val id: Long,
    val author: String,
    val avatar: String? = null,
    val comment: String,
    val likes: Int = 0,
    val replies: Int = 0,
    val userRating: Int? = null,
    val spoiler: Boolean = false,
    val createdAt: String? = null,
)

data class TraktCommentsResponse(
    val results: List<TraktCommentItem> = emptyList(),
)

data class HomeRail(
    val id: String,
    val title: String,
    val items: List<MediaItem>,
    /** True for addon catalogs that carry live channels, which are grouped together on Home. */
    val isLive: Boolean = false,
)

data class LiveCatalogRail(
    val id: String,
    val title: String,
    val items: List<MediaItem>,
)

data class LiveCatalogSection(
    val id: String,
    val title: String,
    val rails: List<LiveCatalogRail>,
)

data class HomeContent(
    val featured: MediaItem?,
    val rails: List<HomeRail>,
)

data class AccountProfile(
    val email: String? = null,
    val displayName: String? = null,
)

data class DeviceInfo(
    val id: String? = null,
    val name: String? = null,
    val platform: String? = null,
    val deviceType: String? = null,
    val appVersion: String? = null,
    val lastSeenAt: String? = null,
    val isCurrent: Boolean = false,
)

data class SessionInfo(
    val id: String? = null,
    val clientName: String? = null,
    val clientPlatform: String? = null,
    val deviceId: String? = null,
    val lastSeenAt: String? = null,
    val isCurrent: Boolean = false,
)

data class SyncStatus(
    val lastSettingsSyncAt: String? = null,
    val cloudSyncEnabled: Boolean = true,
    val playbackSyncEnabled: Boolean = true,
    val mobileReady: Boolean = true,
    val tvReady: Boolean = true,
    val currentTheme: String? = null,
    val traktConnected: Boolean = false,
)

data class AppPreferences(
    val theme: String = "cinema-blue",
    val colorMode: String = "night",
    val startScreen: String = "home",
    val homeRowCardStyle: String = "landscape",
    val compactMode: Boolean = false,
    val syncOverCellular: Boolean = false,
)

data class SubtitleSourcePreference(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val url: String? = null,
    val enabled: Boolean = true,
)

data class ExternalSubtitleTrack(
    val id: String,
    val language: String,
    val label: String,
    val url: String,
)
data class StremioSubtitleItem(
    val id: String = "",
    @SerializedName("lang") val language: String = "",
    @SerializedName("m") val release: String = "",
    val url: String = "",
)

data class StremioSubtitlesResponse(
    val subtitles: List<StremioSubtitleItem> = emptyList(),
)
data class PlaybackPreferences(
    val autoplayNextEpisode: Boolean = true,
    val autoPlayNextEpisodeEnabled: Boolean? = null,
    val preferredQuality: String = "1080p",
    val maxFileSizeGB: String = "2",
    val streamingServer: String = "addon",
    val defaultSubtitleLanguage: String = "en",
    val defaultAudioLanguage: String = "en",
    val externalPlayerEnabled: Boolean = false,
    val preferEmbeddedMpvByDefault: Boolean = true,
    val skipSegmentsEnabled: Boolean? = null,
    val skipIntroEnabled: Boolean? = null,
    val skipRecapEnabled: Boolean? = null,
    val skipEndingEnabled: Boolean? = null,
    val introContributionEnabled: Boolean = false,
    val introDbApiKey: String = "",
    val preferBingeGroupNextEpisode: Boolean = true,
    val autoLoadSubtitles: Boolean = true,
    val nextEpisodeThresholdMode: String = "minutes",
    val nextEpisodeThresholdPercent: Int = 95,
    val nextEpisodeThresholdMinutes: Int = 2,
    val decoderMode: String = "hardware_plus",
    val renderSurface: String = "auto",
    /** Mobile-managed cloud setting: Auto starts Media3 and falls back once to libMPV. */
    val playerEngine: String = "Auto",
    val rememberLastSource: Boolean = true,
    /** Mobile-managed subtitle sources mirrored through cloud preferences. */
    val subtitleSources: List<SubtitleSourcePreference> = emptyList(),
    val customSubtitleSources: List<SubtitleSourcePreference> = emptyList(),
    val manualStreamSelectionEnabled: Boolean = true,
) {
    fun isAutoPlayNextEpisodeEnabled(): Boolean = autoPlayNextEpisodeEnabled ?: autoplayNextEpisode

    fun areSkipSegmentsEnabled(): Boolean = skipSegmentsEnabled ?: skipIntroEnabled ?: true

    fun isSegmentEnabled(segmentType: String): Boolean {
        if (!areSkipSegmentsEnabled()) return false
        return when (segmentType) {
            "intro" -> skipIntroEnabled ?: true
            "recap" -> skipRecapEnabled ?: true
            "outro" -> skipEndingEnabled ?: true
            else -> false
        }
    }

    fun isNextEpisodeThresholdReached(positionSec: Double, durationSec: Double, segmentStartSec: Double? = null): Boolean {
        if (durationSec <= 0.0) return false
        val configuredStart = if (nextEpisodeThresholdMode.equals("percent", ignoreCase = true)) {
            durationSec * (nextEpisodeThresholdPercent.coerceIn(50, 99) / 100.0)
        } else {
            (durationSec - nextEpisodeThresholdMinutes.coerceIn(1, 15) * 60.0).coerceAtLeast(0.0)
        }
        return positionSec >= maxOf(configuredStart, segmentStartSec ?: 0.0)
    }
}

data class StreamsPreferences(
    val fusionBadgesEnabled: Boolean = true,
    val showSizeBadges: Boolean = true,
    val badgePosition: String = "bottom",
    val fusionBadgeUrls: List<String> = listOf(DEFAULT_FUSION_BADGE_URL),
    val activeFusionBadgeUrl: String? = null,
)

data class PreferencesEnvelope(
    val app: AppPreferences = AppPreferences(),
    val playback: PlaybackPreferences = PlaybackPreferences(),
    val streams: StreamsPreferences = StreamsPreferences(),
)

data class FusionBadgeGroup(
    val id: String = "",
    val name: String = "",
    val isExpanded: Boolean? = null,
    val color: String? = null,
    val borderColor: String? = null,
)

data class FusionBadgeFilter(
    val id: String = "",
    val groupId: String = "",
    val name: String = "",
    val pattern: String = "",
    val imageURL: String = "",
    val tagColor: String? = null,
    val borderColor: String? = null,
    val textColor: String? = null,
    val tagStyle: String? = null,
    val isEnabled: Boolean? = null,
    val type: String? = null,
)

data class FusionBadgeSource(
    val url: String = "",
    val groups: List<FusionBadgeGroup> = emptyList(),
    val filters: List<FusionBadgeFilter> = emptyList(),
    val fetchedAt: String = "",
)

data class TraktIntegration(
    val connected: Boolean = false,
    val username: String? = null,
)

data class DebridAccount(
    val provider: String,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val username: String? = null,
)

data class DebridIntegration(
    val accounts: List<DebridAccount> = emptyList(),
)

data class AddonCatalogRef(
    val type: String = "",
    val id: String = "",
    val name: String? = null,
)

data class AddonManifestMeta(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val description: String? = null,
    val catalogs: List<AddonCatalogRef> = emptyList(),
    /** Stremio content types this addon serves (e.g. movie, series, tv). Empty means unknown/all. */
    val types: List<String> = emptyList(),
    /** Stremio resources this addon exposes; entries may be strings or objects with a name field. */
    val resources: List<Any> = emptyList(),
)

data class AddonManifest(
    val id: String,
    val enabled: Boolean = true,
    val position: Int = 0,
    val transportUrl: String? = null,
    val manifestUrl: String? = null,
    val manifest: AddonManifestMeta = AddonManifestMeta(),
)

data class AddonCatalogMetaItem(
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val imdbRating: String? = null,
    val releaseInfo: String? = null,
    @SerializedName("moviedb_id") val movieDbId: Int? = null,
    val url: Any? = null,
    val externalUrl: Any? = null,
    val headers: Map<String, Any?> = emptyMap(),
    val behaviorHints: Map<String, Any?>? = null,
)

data class AddonCatalogResponse(
    val metas: List<AddonCatalogMetaItem> = emptyList(),
)

data class AddonsIntegration(
    val items: List<AddonManifest> = emptyList(),
)

data class IntegrationsEnvelope(
    val trakt: TraktIntegration = TraktIntegration(),
    val debrid: DebridIntegration = DebridIntegration(),
    val addons: AddonsIntegration = AddonsIntegration(),
)

data class AccountBootstrap(
    val profile: AccountProfile? = null,
    val streamProfiles: List<StreamProfile> = emptyList(),
    val preferences: PreferencesEnvelope = PreferencesEnvelope(),
    val integrations: IntegrationsEnvelope = IntegrationsEnvelope(),
    val devices: List<DeviceInfo> = emptyList(),
    val sessions: List<SessionInfo> = emptyList(),
    val syncStatus: SyncStatus? = null,
)

data class StreamProfile(
    val id: String,
    val userId: String,
    val name: String,
    val avatarIndex: Int = 0,
    val hasPinSet: Boolean = false,
    val isDefault: Boolean = false,
    val maturityRating: String = "all",
    val subtitleLanguage: String? = null,
    val audioLanguage: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

data class EpisodeContext(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String? = null,
    val overview: String? = null,
    val still: String? = null,
    val runtime: Int? = null,
    val airDate: String? = null,
    val tmdbEpisodeId: Int? = null,
)

data class ContinueWatchingItem(
    val id: String,
    val tmdbId: Int = 0,
    val title: String,
    val type: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val description: String? = null,
    val rating: Double? = null,
    val year: String? = null,
    val progress: Double? = null,
    val positionSec: Double? = null,
    val durationSec: Double? = null,
    val resumeAt: Double? = null,
    val episodeKey: String? = null,
    val episode: EpisodeContext? = null,
)

data class LibraryResponse(
    val continueWatching: List<ContinueWatchingItem> = emptyList(),
    val watchlist: List<MediaItem> = emptyList(),
)

data class TraktPlaybackResponse(
    val results: List<ContinueWatchingItem> = emptyList(),
)

data class PlaybackProgressRecord(
    val positionSec: Double,
    val durationSec: Double,
    val progress: Double,
)

data class PlaybackProgressResponse(
    val progress: PlaybackProgressRecord? = null,
)

data class PlaybackSegment(
    val segmentType: String,
    val startSec: Double,
    val endSec: Double,
)

data class TraktHistoryResponse(
    val results: List<TraktHistoryItem> = emptyList(),
)

data class TraktHistoryItem(
    val type: String? = null,
    val movie: TraktHistoryMediaRef? = null,
    val show: TraktHistoryMediaRef? = null,
    val episode: TraktHistoryEpisodeRef? = null,
)

data class TraktHistoryMediaRef(
    val ids: TraktHistoryIds? = null,
)

data class TraktHistoryEpisodeRef(
    val season: Int? = null,
    val number: Int? = null,
)

data class TraktHistoryIds(
    val tmdb: Int? = null,
    val imdb: String? = null,
)

data class AddonStreamsResponse(
    val streams: List<AddonStream> = emptyList(),
)

data class AddonStream(
    val addonId: String = "",
    val addonName: String = "",
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val filename: String? = null,
    val behaviorHints: BehaviorHints? = null,
    val quality: String? = null,
    val size: String? = null,
    val cachedBy: List<String> = emptyList(),
    val bingeGroup: String? = null,
    val source: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
)

data class BehaviorHints(
    val filename: String? = null,
    val bingeGroup: String? = null,
)

data class DebridResolveResponse(
    val url: String? = null,
    val filename: String? = null,
)

data class TorrentResolveResponse(
    val streamUrl: String? = null,
    val filename: String? = null,
)

data class ResolvedPlaybackSource(
    val url: String,
    val contentType: String,
    val label: String,
    val filename: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
)

data class ResolvedPlaybackCandidate(
    val source: ResolvedPlaybackSource?,
    val stream: AddonStream?,
    val streams: List<AddonStream>,
)

/**
 * Incremental result of a stream lookup. [pendingSources] counts addons that have not
 * answered yet, so the UI can keep showing a subtle progress hint while already
 * rendering the streams that have arrived.
 */
data class StreamCandidatesProgress(
    val streams: List<AddonStream>,
    val pendingSources: Int,
    val done: Boolean,
)

data class PlaybackRequest(
    val mediaId: String,
    val mediaType: String,
    val imdbId: String? = null,
    val episode: EpisodeContext? = null,
    val title: String? = null,
    val selectedStreamKey: String? = null,
    val selectedStreamLabel: String? = null,
    /** Already-discovered stream selected on the source screen; avoids querying every addon again. */
    val selectedStream: AddonStream? = null,
    val availableStreams: List<AddonStream> = emptyList(),
    /** Stremio-native stream type for live playback (mediaType == "live"). */
    val streamType: String? = null,
    val sourceAddonId: String? = null,
    val sourceAddonName: String? = null,
    val sourceCatalogId: String? = null,
    val sourceCatalogName: String? = null,
    val directStreamUrl: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val startPositionSec: Double? = null,
    val returnToDetailOnBack: Boolean = false,
)

data class PlaybackHandoffPayload(
    val mediaId: String = "",
    val mediaType: String = "movie",
    val imdbId: String? = null,
    val title: String? = null,
    val year: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val positionSeconds: Double = 0.0,
    val sourceLabel: String? = null,
    val quality: String? = null,
    val stream: AddonStream = AddonStream(),
)

data class PlaybackHandoff(
    val id: String,
    val profileId: String? = null,
    val payload: PlaybackHandoffPayload,
    val expiresAt: String? = null,
    val createdAt: String? = null,
)

data class PlaybackHandoffEnvelope(val handoff: PlaybackHandoff? = null)

data class PlaybackHandoffAck(val success: Boolean = false, val status: String? = null)
data class AppReleaseManifest(
    val versionCode: Int = 0,
    val versionName: String = "",
    val apkUrl: String = "",
    val releaseNotes: String? = null,
    val required: Boolean = false,
    val publishedAt: String? = null,
    val checksumSha256: String? = null,
    val minSupportedVersionCode: Int? = null,
    val requiredReason: String? = null,
    val packageName: String? = null,
    val assetName: String? = null,
    val fileSizeBytes: Long? = null,
)




