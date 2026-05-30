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

data class PlaybackPreferences(
    val autoplayNextEpisode: Boolean = true,
    val preferredQuality: String = "1080p",
    val maxFileSizeGB: String = "2",
    val streamingServer: String = "addon",
    val defaultSubtitleLanguage: String = "en",
    val defaultAudioLanguage: String = "en",
    val externalPlayerEnabled: Boolean = false,
    val preferEmbeddedMpvByDefault: Boolean = true,
    val decoderMode: String = "auto",
    val renderSurface: String = "standard",
)

data class PreferencesEnvelope(
    val app: AppPreferences = AppPreferences(),
    val playback: PlaybackPreferences = PlaybackPreferences(),
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

data class AddonManifestMeta(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val description: String? = null,
)

data class AddonManifest(
    val id: String,
    val enabled: Boolean = true,
    val position: Int = 0,
    val manifest: AddonManifestMeta = AddonManifestMeta(),
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

data class AddonStreamsResponse(
    val streams: List<AddonStream> = emptyList(),
)

data class AddonStream(
    val addonId: String = "",
    val addonName: String = "",
    val name: String? = null,
    val title: String? = null,
    val url: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val behaviorHints: BehaviorHints? = null,
    val quality: String? = null,
    val size: String? = null,
    val cachedBy: List<String> = emptyList(),
)

data class BehaviorHints(
    val filename: String? = null,
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
)

data class ResolvedPlaybackCandidate(
    val source: ResolvedPlaybackSource?,
    val stream: AddonStream?,
    val streams: List<AddonStream>,
)

data class PlaybackRequest(
    val mediaId: String,
    val mediaType: String,
    val imdbId: String? = null,
    val episode: EpisodeContext? = null,
    val title: String? = null,
    val selectedStreamKey: String? = null,
    val selectedStreamLabel: String? = null,
)

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
