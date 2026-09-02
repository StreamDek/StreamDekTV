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
    /**
     * The IMDb id, when the source supplied one separately.
     *
     * Add-ons frequently put an IMDb id in [id] instead -- see [detailLookupId] -- so this is not
     * the only place one can be found. Both are read by `mediaIdentityOf`, which is what lets a
     * Continue Watching removal recorded against one spelling suppress the same title arriving
     * under another.
     */
    val imdbId: String? = null,
    val title: String,
    @SerializedName(value = "type", alternate = ["mediaType"])
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
    /**
     * A line the row wants on the card, above the title.
     *
     * Set by New Episodes to say which episode landed and when. Kept off the wire deliberately --
     * it is presentation the client decides, not something a catalogue supplies.
     */
    @Transient val cardSubtitle: String? = null,
    /** Draws that line as news rather than as metadata. */
    @Transient val cardHighlight: Boolean = false,
    /** Stremio-native stream type for live addon items (e.g. 'tv', 'events', 'sport'). */
    val streamType: String? = null,
    val sourceAddonId: String? = null,
    val sourceAddonName: String? = null,
    val sourceCatalogId: String? = null,
    val sourceCatalogName: String? = null,
    val directStreamUrl: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
) {
    /** TMDB detail routes require the numeric TMDB id, while add-ons often expose IMDb as id. */
    fun detailLookupId(): String = tmdbId.takeIf { it > 0 }?.toString() ?: id
}

data class TmdbFindResponse(
    val id: Int = 0,
    val type: String? = null,
)

data class LiveFavouriteChannelsEnvelope(
    val success: Boolean = true,
    val items: List<MediaItem> = emptyList(),
    val updatedAt: Long = 0L,
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

data class PersonDetail(
    val id: String = "",
    val name: String = "",
    val photo: String? = null,
    val biography: String? = null,
    val birthday: String? = null,
    val placeOfBirth: String? = null,
    val knownFor: String? = null,
    val popularWorks: List<MediaItem> = emptyList(),
)

data class SeasonRef(
    @SerializedName("season_number") val seasonNumber: Int,
    val name: String,
    @SerializedName("episode_count") val episodeCount: Int = 0,
    @SerializedName("air_date") val airDate: String? = null,
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
    /**
     * Every video the metadata service listed, roughly newest first.
     *
     * That order is not the useful one — it puts theatre stings and ticket spots ahead of the
     * actual trailer — so the resolver reads their running times and picks. Without these it can
     * only take [trailerKey], which is how a title opened on a fifteen-second notice.
     */
    val trailerKeys: List<String> = emptyList(),
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

/**
 * One default catalog, as declared by the backend catalog registry (`GET /tmdb/catalogs`).
 *
 * The registry — not the app — decides which rows exist, what they are called and in what order
 * they appear, so a new default row is a backend deploy rather than a TV release. [id] is stable
 * and independent of [title]: it is what the synced row layout persists, so the same row switched
 * off on the phone is the row switched off here.
 */
data class CatalogDefinition(
    val id: String,
    val title: String,
    /** "movie", "tv" or "network". */
    val mediaType: String,
    val group: String,
    val previewLimit: Int,
    val maxItems: Int?,
    val paginated: Boolean,
)

/** Wire shape of `GET /tmdb/catalogs`. */
data class CatalogManifestResponse(
    val version: Int = 0,
    val region: String? = null,
    val catalogs: List<CatalogManifestEntry> = emptyList(),
)

data class CatalogManifestEntry(
    val id: String? = null,
    val title: String? = null,
    val media_type: String? = null,
    val group: String? = null,
    val preview_limit: Int = 0,
    val max_items: Int? = null,
    val paginated: Boolean = true,
)

/** Wire shape of `GET /tmdb/home` — every enabled row's preview in one response. */
data class CatalogHomeResponse(
    val version: Int = 0,
    val region: String? = null,
    val sections: List<CatalogHomeSection> = emptyList(),
)

data class CatalogHomeSection(
    val id: String? = null,
    val title: String? = null,
    val media_type: String? = null,
    val results: List<CatalogSectionItem> = emptyList(),
    /**
     * Page this row carries on from. A preview is not always one clean page — a row short on
     * titles the viewer has not already seen reads further into its catalog — so the server says
     * where it stopped rather than the client inferring it from the item count.
     */
    val next_page: Int? = null,
    val total_pages: Int = 0,
)

/**
 * One item inside a catalog section.
 *
 * Content rows and the Streaming Networks row disagree on field names — a network tile carries
 * `name`/`logo` where a title carries `title`/`poster` — so both are read here and reconciled
 * when the row is turned into cards, rather than giving [MediaItem] alternates that would then
 * apply to every other payload that happens to have a `name`.
 */
data class CatalogSectionItem(
    val id: String? = null,
    val tmdbId: Int = 0,
    val type: String? = null,
    val title: String? = null,
    val name: String? = null,
    val poster: String? = null,
    val logo: String? = null,
    val backdrop: String? = null,
    val description: String? = null,
    val rating: Double? = null,
    val year: String? = null,
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
    /**
     * Rows still being fetched, in the position they will occupy. Home draws a skeleton for each
     * so the rails already on screen keep their place: a row appearing above the focused one would
     * otherwise shove it down mid-browse, which on a remote reads as the app losing your place.
     */
    val pendingRails: List<PendingRail> = emptyList(),
) {
    /** True once every row has either arrived or been ruled out. */
    val isComplete: Boolean get() = pendingRails.isEmpty()
}

/** A reserved slot for a row that has not resolved yet. */
data class PendingRail(
    val id: String,
    val title: String,
    /** Matches the card shape the finished row will use, so the swap is not a visual jump. */
    val portrait: Boolean = false,
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
    // TV-first presentation preferences. Older payloads omit these fields, so defaults
    // remain conservative and backwards compatible.
    val cardDensity: String = "comfortable",
    /**
     * No longer read. Animation speed is a property of the installation rather than the account -
     * see `ui/AnimationSpeed.kt` - and now lives in this television's own preferences. The field
     * stays on the model so older payloads still parse and older clients on the same account keep
     * working; nothing here writes it any more.
     */
    @Deprecated("Device-local now; see TvAnimationPreferences.")
    val animationSpeed: String = "normal",
    val navigationStyle: String = "adaptive",
    val gridSize: Int = 5,
    val backgroundBlur: Boolean = true,
    val highContrast: Boolean = false,
    val largeText: Boolean = false,
    val reducedMotion: Boolean = false,
    /**
     * Drops the synopsis from the Home spotlight, leaving the badge, title and metadata centred in
     * the band. On by default: at sofa distance a paragraph under the spotlight is rarely read and
     * costs the artwork the room it earns.
     */
    val hideHomeSynopsis: Boolean = true,
    /**
     * Drops the title block from Home cards, leaving year and rating along the top.
     *
     * Only read when the Home rows are portrait, which is why the setting is only offered there: a
     * poster carries its own title in the artwork, so the overlay repeats in worse type what the
     * picture already says and covers the bottom third of it doing so. Landscape stills carry no
     * such lettering and are frequently unidentifiable without the line, so they keep it whatever
     * this says. Off by default -- the title block is what the screen has always shown.
     */
    val hideHomeCardTitles: Boolean = false,
    /**
     * Lets the backdrop show through the navigation rail. Capped at 15% by design: the rail carries
     * the only persistent sense of where you are in the app, and past that it stops reading as a
     * surface at all over bright artwork.
     */
    val transparentNavigation: Boolean = true,
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
    val origin: ExternalSubtitleOrigin,
    val sourceName: String,
    val release: String? = null,
)
enum class ExternalSubtitleOrigin { BuiltIn, Addon }
fun externalSubtitleOrigin(sourceId: String): ExternalSubtitleOrigin =
    if (sourceId.startsWith("addon:")) ExternalSubtitleOrigin.Addon else ExternalSubtitleOrigin.BuiltIn

fun subtitleOriginVisible(tab: String, origin: ExternalSubtitleOrigin): Boolean = when (tab) {
    "All" -> true
    "BuiltIn" -> origin == ExternalSubtitleOrigin.BuiltIn
    "Addons" -> origin == ExternalSubtitleOrigin.Addon
    else -> false
}
fun subtitleSourceAllowsOrigin(selection: String?, origin: ExternalSubtitleOrigin): Boolean {
    val normalized = when (selection?.lowercase()) {
        "builtin", "built-in", "built_in" -> "BuiltIn"
        "addons", "add-ons", "addon" -> "Addons"
        else -> "All"
    }
    return subtitleOriginVisible(normalized, origin)
}

fun preferredSubtitleLanguageAllowed(
    language: String?,
    primary: String?,
    secondary: String?,
    strict: Boolean,
): Boolean {
    if (!strict) return true
    val allowed = listOf(primary, secondary).map(Languages::normalize)
        .filter { it.isNotBlank() && it != Languages.NONE }.toSet()
    return Languages.normalize(language) in allowed
}
data class StremioSubtitleItem(
    val id: String = "",
    @SerializedName(value = "lang", alternate = ["language", "languageCode", "locale"]) val language: String = "",
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
    val autoSkipIntroEnabled: Boolean = false,
    val autoSkipRecapEnabled: Boolean = false,
    val autoSkipEndingEnabled: Boolean = false,
    val introContributionEnabled: Boolean = false,
    val introDbApiKey: String = "",
    val preferBingeGroupNextEpisode: Boolean = true,
    val autoLoadSubtitles: Boolean = true,
    val showOnlyPreferredSubtitleLanguages: Boolean = false,
    val secondarySubtitleLanguage: String = "none",
    val addonSubtitleLoading: String = "preferred",
    /** Which subtitle sources are searched and shown: All, BuiltIn or Addons. */
    val subtitleDefaultSource: String = "All",
    val nextEpisodeThresholdMode: String = "minutes",
    val nextEpisodeThresholdPercent: Int = 95,
    val nextEpisodeThresholdMinutes: Int = 2,
    val endOfPlaybackRecommendationsEnabled: Boolean = false,
    val recommendationTiming: String = "standard",
    val recommendationItemCount: Int = 1,
    val timingProvider: String = "introdb",
    val timingProviderFallbackEnabled: Boolean = true,
    val decoderMode: String = "hardware_plus",
    val renderSurface: String = "auto",
    /** Mobile-managed cloud setting: Auto starts Media3 and falls back once to libMPV. */
    val playerEngine: String = "Auto",
    val rememberLastSource: Boolean = true,
    /** Mobile-managed subtitle sources mirrored through cloud preferences. */
    val subtitleSources: List<SubtitleSourcePreference> = emptyList(),
    val customSubtitleSources: List<SubtitleSourcePreference> = emptyList(),
    val manualStreamSelectionEnabled: Boolean = true,
    /** Initial visibility of the seek/progress row for live and live-style VOD playback. */
    val liveProgressBarEnabled: Boolean = false,
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

    fun isAutoSkipEnabled(segmentType: String): Boolean = when (segmentType) {
        "intro" -> autoSkipIntroEnabled
        "recap" -> autoSkipRecapEnabled
        "outro" -> autoSkipEndingEnabled
        else -> false
    }

    fun isNextEpisodeThresholdReached(positionSec: Double, durationSec: Double, segmentStartSec: Double? = null): Boolean {
        val estimate = AdaptiveEndOfPlaybackTrigger.estimate(
            durationSec = durationSec,
            timing = RecommendationTiming.fromKey(recommendationTiming),
            structuralOutroStartSec = segmentStartSec,
        )
        return AdaptiveEndOfPlaybackTrigger.isReached(positionSec, estimate)
    }
}

data class StreamsPreferences(
    val fusionBadgesEnabled: Boolean = true,
    val showSizeBadges: Boolean = true,
    val badgePosition: String = "bottom",
    val fusionBadgeUrls: List<String> = listOf(DEFAULT_FUSION_BADGE_URL),
    val activeFusionBadgeUrl: String? = null,
    /** Mobile-managed: whether the stream picker is offered before playback starts. */
    val showStreamsList: Boolean = true,
    val rememberLastSource: Boolean = true,
    val blurUnwatchedEpisodes: Boolean = true,
    val streamDekFormattingEnabled: Boolean = false,
    val showAddonTmdbRatings: Boolean = false,
    /** Stable descriptors shared with mobile; resolved playback URLs are never stored. */
    val favoriteSourceKeys: List<String> = emptyList(),
)

/**
 * Home-surface choices. Profile-scoped: two people sharing an account can pick different rows and
 * different tracking services.
 */
data class HomePreferences(
    /**
     * Which tracking service backs the watchlist and continue-watching for this profile:
     * `trakt`, `simkl` or `mdblist`. See [SyncServiceId].
     */
    val primarySyncService: String = SyncServiceId.TRAKT,
    val defaultAppCatalogsEnabled: Boolean = true,
    val continueWatchingStyle: String? = null,
    /**
     * "Classic" or "Branded": which artwork the Streaming Networks row draws.
     *
     * Lives under `home` rather than `app` because it is one choice for the account, not one
     * per screen size -- the phone and the television draw the same services from the same row,
     * and picking the branded tiles on one is a statement about both.
     */
    val networkCardStyle: String? = null,
    val liveCategoriesEnabled: Boolean = true,
    val liveLandscapeCards: Boolean = true,
    val liveFavouriteDrawerCards: Boolean = false,
    val showHeroSynopsis: Boolean = true,
    val detailPageStyle: String? = null,
    val vividAmbient: Boolean = true,
    val ambientTintPercent: Int = 100,
    val homeCatalogRows: List<HomeCatalogRowPreference> = emptyList(),
)

/** One customised home row, as laid out on mobile or the web portal. */
data class HomeCatalogRowPreference(
    val id: String = "",
    val enabled: Boolean = true,
    val position: Int = 0,
    val title: String? = null,
)

/**
 * How long a title page is left alone before its trailer starts, and the range it is held to.
 *
 * Kept here rather than beside either screen because two of them need it: the page that waits and
 * the setting that changes it. The ceiling is a product decision — past about five seconds a viewer
 * has finished with the page and the trailer arrives as an interruption rather than as the next
 * thing — and it is enforced on read as well as on write, since the value also arrives from the
 * phone and the web portal.
 */
const val DefaultTrailerDelaySeconds = 3
const val MaxTrailerDelaySeconds = 5

/** Trailer cache housekeeping: daily, at nine in the morning. Matches the phone. */
const val DefaultTrailerCacheClearHours = 24
const val TrailerCacheClearHourOfDay = 9

/** The intervals offered for automatic trailer-cache clearing, as hours to label. */
val TrailerCacheClearChoices: List<Pair<Int, String>> = listOf(
    12 to "Every 12 hours",
    24 to "Every 24 hours",
    48 to "Every 48 hours",
)

/**
 * Label for an interval, including one this build does not offer.
 *
 * The value is synced, so the phone or the web portal can hold a choice the television's own list
 * does not carry — which would otherwise leave the row showing a bare number.
 */
fun trailerCacheClearLabel(hours: Int): String =
    TrailerCacheClearChoices.firstOrNull { it.first == hours }?.second ?: "Every $hours hours"

/** Detail-screen choices, profile-scoped apart from the account-wide MDBList key. */
data class DetailPreferences(
    val seasonTabStyle: String? = null,
    val heroTrailerAutoplay: Boolean = true,
    val heroTrailerDelaySeconds: Int = DefaultTrailerDelaySeconds,
    val heroTrailerResolution: Int = 2160,
    /** How often trailer state is thrown away. Synced, so the household agrees on the schedule. */
    val trailerCacheClearHours: Int = DefaultTrailerCacheClearHours,
    val ratingsEnabled: Boolean = true,
    val externalRatingsEnabled: Boolean = true,
    val enabledRatingProviders: List<String> = emptyList(),
    /**
     * Retired. The backend empties this during migration and no client writes it any more -- the
     * MDBList key lives in the encrypted credential store, reachable through Content Services.
     * The field is kept so an older stored document still deserializes.
     */
    val mdblistApiKey: String? = null,
)

data class PreferencesEnvelope(
    val app: AppPreferences = AppPreferences(),
    val home: HomePreferences = HomePreferences(),
    val detail: DetailPreferences = DetailPreferences(),
    val playback: PlaybackPreferences = PlaybackPreferences(),
    val streams: StreamsPreferences = StreamsPreferences(),
)

/** Reply shape of `PUT /profiles/:id/preferences`. */
data class ProfilePreferencesEnvelope(
    val success: Boolean = false,
    val preferences: com.google.gson.JsonObject? = null,
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

/**
 * Connection state for one of the tracking services a profile can pick. The backend reports Simkl
 * and MDBList in the same shape as Trakt, so all three can be handled uniformly.
 */
data class SyncServiceIntegration(
    val connected: Boolean = false,
    val username: String? = null,
    val slug: String? = null,
    val expiresAt: String? = null,
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

/**
 * One IPTV playlist saved against the profile. Only the pointer syncs — the channels are fetched
 * and parsed on this device by [M3uPlaylistEngine], since a playlist can carry tens of thousands
 * of entries and goes stale on the provider's schedule.
 */
data class RemotePlaylist(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val enabled: Boolean = true,
    val position: Int = 0,
)

data class RemotePlaylistResponse(
    val playlists: List<RemotePlaylist> = emptyList(),
)

data class AddonCatalogRef(
    val type: String = "",
    val id: String = "",
    val name: String? = null,
    /**
     * The catalog's declared extra properties. Typed as `Any` for the same reason [resources] is:
     * entries are normally objects (`{ "name": "search" }`) but some manifests list plain strings,
     * and one odd add-on must not fail the whole manifest parse.
     */
    val extra: List<Any> = emptyList(),
    /** Older, flat spelling of the same thing, still used by plenty of installed add-ons. */
    val extraSupported: List<String> = emptyList(),
    val extraRequired: List<String> = emptyList(),
    /** Genre options as older manifests list them, beside `extraSupported` rather than in `extra`. */
    val genres: List<String> = emptyList(),
) {
    /** Whether this catalog answers a `search` extra, in either manifest spelling. */
    val supportsSearch: Boolean
        get() = extra.any { addonExtraName(it).equals("search", ignoreCase = true) } ||
            extraSupported.any { it.equals("search", ignoreCase = true) }

    /**
     * Whether the catalog cannot be listed at all without a genre. Only a *required* genre is
     * filled in automatically: supplying one where it is optional would narrow the search.
     */
    val requiresGenre: Boolean
        get() = extra.any { entry ->
            addonExtraName(entry).equals("genre", ignoreCase = true) && addonExtraRequired(entry)
        } || extraRequired.any { it.equals("genre", ignoreCase = true) }

    /**
     * Whether the catalog answers nothing at all until it is given something to search for.
     *
     * Distinct from [supportsSearch]: plenty of browsable catalogs also accept a search term. One
     * that *requires* it is a search endpoint wearing a catalog's clothes — Xperience and
     * AIOStreams both publish one — and it has no business being a row on a home screen, where the
     * best it can do is cost a round trip to answer with nothing.
     */
    val requiresSearch: Boolean
        get() = extra.any { entry ->
            addonExtraName(entry).equals("search", ignoreCase = true) && addonExtraRequired(entry)
        } || extraRequired.any { it.equals("search", ignoreCase = true) }

    /** The genre to send when one is required, or null when the catalog does not need one. */
    val defaultGenre: String?
        get() = if (!requiresGenre) null else genreOptions.firstOrNull()

    private val genreOptions: List<String>
        get() {
            extra.forEach { entry ->
                if (!addonExtraName(entry).equals("genre", ignoreCase = true)) return@forEach
                addonExtraOptions(entry).takeIf { it.isNotEmpty() }?.let { return it }
            }
            return genres
        }
}

private fun addonExtraName(entry: Any?): String = when (entry) {
    is String -> entry
    is Map<*, *> -> entry["name"]?.toString().orEmpty()
    else -> ""
}

private fun addonExtraRequired(entry: Any?): Boolean = when (entry) {
    is Map<*, *> -> entry["isRequired"] == true
    else -> false
}

private fun addonExtraOptions(entry: Any?): List<String> = when (entry) {
    is Map<*, *> -> (entry["options"] as? List<*>).orEmpty().mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
    else -> emptyList()
}

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
    val favourite: Boolean = false,
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
    /** Some providers, including Eclipsia variants, publish synopsis text under these names. */
    val overview: String? = null,
    val synopsis: String? = null,
    val imdbRating: String? = null,
    val releaseInfo: String? = null,
    @SerializedName(value = "moviedb_id", alternate = ["tmdb_id", "tmdbId"]) val movieDbId: Int? = null,
    val url: Any? = null,
    val externalUrl: Any? = null,
    val headers: Map<String, Any?> = emptyMap(),
    val behaviorHints: Map<String, Any?>? = null,
)

data class AddonCatalogResponse(
    val metas: List<AddonCatalogMetaItem> = emptyList(),
)

/**
 * One item as an add-on describes it, from the backend's cross-addon `meta` route. Used when
 * TMDB cannot resolve a card's id — a metadata add-on's `tmdb:`/`kitsu:` id, or a bridge's own.
 */
data class AddonMetaItem(
    val id: String? = null,
    @SerializedName("imdb_id") val imdbId: String? = null,
    val type: String? = null,
    val name: String? = null,
    val description: String? = null,
    val overview: String? = null,
    val synopsis: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val videos: List<AddonMetaVideo> = emptyList(),
)

data class AddonMetaVideo(
    val id: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

data class AddonMetaResponse(
    val meta: AddonMetaItem? = null,
    val addonId: String? = null,
    val addonName: String? = null,
)

data class AddonsIntegration(
    val items: List<AddonManifest> = emptyList(),
)

data class ProfilePluginRepo(
    val url: String = "",
    val name: String = "",
    val version: String = "",
    val description: String? = null,
    val enabled: Boolean = true,
    val favourite: Boolean = false,
)

data class ProfilePluginProvider(
    val id: String = "",
    @SerializedName("repo") val repoUrl: String = "",
    val name: String = "",
    val types: List<String> = emptyList(),
    val enabled: Boolean = true,
    val code: String? = null,
    val hasSettings: Boolean = false,
    /**
     * The values this source needs to work at all — a FebBox cookie, an Eclipsia or Pynvix API
     * token — as the phone and the web portal wrote them into the profile.
     *
     * Held raw because a source decides its own field names and types: a token is a string, a
     * region a code, a toggle a boolean, and the schema that describes them belongs to the
     * scraper rather than to this client. Without it a token typed on the portal never reached
     * the television, which then ran the source with no credential and reported no streams.
     */
    val settings: com.google.gson.JsonObject? = null,
    /** The field list the scraper's own `onSettings` returned, as the writing client saw it. */
    val settingsSchema: com.google.gson.JsonArray? = null,
)

data class ProfilePluginState(
    val enabled: Boolean = true,
    val repos: List<ProfilePluginRepo> = emptyList(),
    val providers: List<ProfilePluginProvider> = emptyList(),
    /**
     * The CloudStream half of the document.
     *
     * Typed rather than carried opaquely, now that this television runs `.cs3` extensions itself
     * and has to read and write it. It still has to be *present* on this class whatever happens to
     * it: [updateProfilePlugins] sends the whole object back as the document, and a field Gson
     * does not know about is a field Gson drops -- which would delete every CloudStream collection
     * on the account the first time a plugin setting was saved here.
     *
     * `installedFilePath` is deliberately not part of it. Where a device put its own copy of a
     * `.cs3` is that device's business and means nothing to any other client.
     */
    val cloudstream: ProfileCloudStreamState? = null,
    val updatedAt: Long = 0L,
)

data class ProfileCloudStreamState(
    val repos: List<ProfileCloudStreamRepo> = emptyList(),
    val providers: List<ProfileCloudStreamProvider> = emptyList(),
    val updatedAt: Long = 0L,
)

data class ProfileCloudStreamRepo(
    val url: String = "",
    val name: String = "",
    val description: String? = null,
    val iconUrl: String? = null,
    val enabled: Boolean = true,
)

data class ProfileCloudStreamProvider(
    val repoUrl: String = "",
    val internalName: String = "",
    val name: String = "",
    val version: Int = 0,
    val downloadUrl: String = "",
    val tvTypes: List<String> = emptyList(),
    val language: String? = null,
    val description: String? = null,
    val enabled: Boolean = false,
)

/**
 * One content-service credential, as the account reports it.
 *
 * Only ever the masked form and the connection state -- the backend has no route that hands back
 * a stored key, this one included. `storage` is always "account" when it appears here; a key kept
 * on a device is known only to that device, which is what the choice means.
 */
data class ContentServiceCredential(
    val service: String = "",
    val configured: Boolean = false,
    val storage: String? = null,
    val maskedKey: String? = null,
    val label: String? = null,
    val status: String? = null,
    val lastValidatedAt: String? = null,
)

/**
 * What the account holds for TMDB and MDBList.
 *
 * Arrives on the ordinary bootstrap, which is what lets a television that has only just been
 * signed into discover that its keys are already there -- with nothing typed on the remote.
 */
data class ContentServicesIntegration(
    val services: List<ContentServiceCredential> = emptyList(),
    /** Whether StreamDek's shared TMDB key still answers for a viewer who has supplied none. */
    val sharedFallbackAvailable: Boolean = true,
)

data class IntegrationsEnvelope(
    val contentServices: ContentServicesIntegration = ContentServicesIntegration(),
    val trakt: TraktIntegration = TraktIntegration(),
    val simkl: SyncServiceIntegration = SyncServiceIntegration(),
    val mdblist: SyncServiceIntegration = SyncServiceIntegration(),
    val punchplay: SyncServiceIntegration = SyncServiceIntegration(),
    val debrid: DebridIntegration = DebridIntegration(),
    val addons: AddonsIntegration = AddonsIntegration(),
)

data class AccountBootstrap(
    val profile: AccountProfile? = null,
    val streamProfiles: List<StreamProfile> = emptyList(),
    val preferences: PreferencesEnvelope = PreferencesEnvelope(),
    val integrations: IntegrationsEnvelope = IntegrationsEnvelope(),
    /** Profile-scoped plugin snapshot synced by mobile and the control center. */
    val profilePlugins: ProfilePluginState = ProfilePluginState(),
    val devices: List<DeviceInfo> = emptyList(),
    val sessions: List<SessionInfo> = emptyList(),
    val syncStatus: SyncStatus? = null,
)

data class DebridCacheCheckResponse(
    val cachedBy: Map<String, List<String>> = emptyMap(),
)

/** Account capability that decides where add-on stream parsing is allowed to run. */
data class AddonEntitlements(
    val ultra: Boolean = false,
    val serverSideStreams: Boolean = false,
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
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val updatedAt: String? = null,
    val lastDevice: String? = null,
    val lastPlatform: String? = null,
) {
    /**
     * The exact episode represented by this progress row.
     *
     * Sync providers do not all serialize episode identity the same way: SyncDek and some mobile
     * writes expose season/episode beside the progress fields, while enriched provider rows may
     * carry a nested episode object. Normalize that wire variation once so cards and playback can
     * never disagree about which episode a timestamp belongs to.
     */
    fun exactEpisode(): EpisodeContext? = episode ?: seasonNumber?.let { season ->
        episodeNumber?.let { number -> EpisodeContext(seasonNumber = season, episodeNumber = number) }
    }
}

/**
 * When a series' next and most recent episodes air, as the backend reads them off TMDB.
 *
 * One record per series rather than a walk through its seasons: the two episodes a viewer
 * following a show cares about are exactly these, and asking for them by season costs a request
 * each -- which on a stick is the difference between a row that fills and one that does not.
 */
data class SeriesEpisodeStatus(
    val tmdbId: Int = 0,
    val title: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val status: String? = null,
    @com.google.gson.annotations.SerializedName("nextEpisodeToAir") val nextEpisode: AiringEpisode? = null,
    @com.google.gson.annotations.SerializedName("lastEpisodeToAir") val lastEpisode: AiringEpisode? = null,
)

data class AiringEpisode(
    val id: Int? = null,
    val name: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val airDate: String? = null,
    val still: String? = null,
)

data class SeriesEpisodeStatusResponse(
    val series: List<SeriesEpisodeStatus> = emptyList(),
)

data class LibraryResponse(
    val continueWatching: List<ContinueWatchingItem> = emptyList(),
    val watchlist: List<MediaItem> = emptyList(),
    val progress: List<PlaybackProgressRecord> = emptyList(),
)

data class TraktPlaybackResponse(
    val results: List<ContinueWatchingItem> = emptyList(),
)

/**
 * `/{service}/sync/watchlist/enriched` for every tracking service. The backend normalizes and
 * TMDB-enriches all three to the same item shape.
 */
data class WatchlistEnvelope(
    val results: List<MediaItem> = emptyList(),
)

data class PlaybackProgressRecord(
    val positionSec: Double,
    val durationSec: Double,
    val progress: Double,
    val episodeKey: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val updatedAt: String? = null,
    val lastDevice: String? = null,
    val lastPlatform: String? = null,
    val status: String = "in-progress",
    val entityType: String? = null,
    val entityId: String? = null,
    /**
     * The ids this row can also be recognised by, when SyncDek recorded them.
     *
     * `entityId` alone is whatever spelling the device that wrote the row was holding, so matching
     * on it is what let a removal made on one device fail to suppress the same title arriving from
     * a provider under a different id. See [mediaIdentityOf].
     */
    val tmdbId: Int? = null,
    val imdbId: String? = null,
)

data class PlaybackProgressResponse(
    val progress: PlaybackProgressRecord? = null,
)

data class PlaybackProgressListResponse(
    val results: List<PlaybackProgressRecord> = emptyList(),
)

data class SyncedEpisodeWatchState(
    val completed: Set<String> = emptySet(),
    val unwatched: Set<String> = emptySet(),
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
    /**
     * NZB pointer published by usenet sources (AIOStreams and friends). Such a stream has no
     * playable url and no info hash — it names an NZB plus the news [servers] to pull it from.
     */
    @SerializedName(value = "nzbUrl", alternate = ["nzb_url", "nzb"])
    val nzbUrl: String? = null,
    @SerializedName(value = "servers", alternate = ["nntpServers", "nntp_servers"])
    val servers: List<String> = emptyList(),
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

/**
 * This account's premium-service keys, so the television can reach those services itself.
 *
 * Only ever travels between StreamDek and the signed-in device that owns it, and is never held in
 * app state — it goes straight into the device's encrypted key store.
 */
data class DebridKeysResponse(
    val accounts: List<DebridKeyEntry> = emptyList(),
)

data class DebridKeyEntry(
    val provider: String? = null,
    val apiKey: String? = null,
    val priority: Int? = null,
    val enabled: Boolean? = null,
    val username: String? = null,
)

data class ResolvedPlaybackSource(
    val url: String,
    val contentType: String,
    val label: String,
    val filename: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
)

/**
 * The exact source a title last played from, kept so that resuming it does not have to be
 * rediscovered.
 *
 * "Remember last source" used to mean remembering only which *row* in the stream list was chosen.
 * Resuming still had to ask the add-on for its streams again and then resolve the chosen one back
 * into a playable URL — two network round trips, frequently slower than picking a source by hand,
 * and the reason a remembered resume could sit on a spinner for fifteen seconds or time out
 * entirely. Keeping the resolved URL alongside the key is what makes "remembered" mean instant.
 *
 * Written only once a source has actually played, so this is a URL known to have worked rather than
 * one that merely resolved. It can still go stale — debrid links and tokenised CDN URLs expire —
 * which is why [savedAtMs] exists and why the player treats a failure here as a cue to fall back to
 * a full resolve rather than as an error worth showing anyone.
 */
data class RememberedPlaybackSource(
    val streamKey: String,
    val url: String,
    val contentType: String = "",
    val label: String = "",
    val filename: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val addonId: String = "",
    val addonName: String = "",
    val savedAtMs: Long = 0L,
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
    /** Launched from a Continue Watching card, so stale-source recovery can explain itself. */
    val fromContinueWatching: Boolean = false,
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

data class EncryptedHandoffPayload(
    val version: Int = 0,
    val algorithm: String = "",
    val encryptedKey: String = "",
    val iv: String = "",
    val ciphertext: String = "",
)

data class PlaybackHandoff(
    val id: String,
    val profileId: String? = null,
    val encryptedPayload: EncryptedHandoffPayload = EncryptedHandoffPayload(),
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




