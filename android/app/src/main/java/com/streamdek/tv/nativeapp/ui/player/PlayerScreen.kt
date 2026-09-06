package com.streamdek.tv.nativeapp.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamdek.tv.R
import com.streamdek.tv.TvRemoteKeyRouter
import com.streamdek.tv.mpv.MPVTextureView
import com.streamdek.tv.mpv.MPVView
import com.streamdek.tv.mpv.MpvPlayerController
import com.streamdek.tv.mpv.MpvTrackInfo
import com.streamdek.tv.nativeapp.data.AddonStream
import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.ExternalSubtitleOrigin
import com.streamdek.tv.nativeapp.data.ExternalSubtitleTrack
import com.streamdek.tv.nativeapp.data.Languages
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.PlaybackPreferences
import com.streamdek.tv.nativeapp.data.PlaybackRequest
import com.streamdek.tv.nativeapp.data.PlaybackSegment
import com.streamdek.tv.nativeapp.data.PlaybackStats
import com.streamdek.tv.nativeapp.data.ProfilePluginState
import com.streamdek.tv.nativeapp.data.ResolvedPlaybackCandidate
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.Telemetry
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import com.streamdek.tv.nativeapp.data.TvIdlePreferences
import com.streamdek.tv.nativeapp.data.TvPowerActions
import com.streamdek.tv.nativeapp.data.classifyPlaybackFailure
import com.streamdek.tv.nativeapp.data.contentScopedResumePosition
import com.streamdek.tv.nativeapp.data.idleTimeoutMillis
import com.streamdek.tv.nativeapp.data.subtitleSourceAllowsOrigin
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.AppFormats
import com.streamdek.tv.nativeapp.ui.LocalAppLanguage
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.TvMotion
import com.streamdek.tv.nativeapp.ui.tvCardLongPress
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** How many copies of the same language to try before telling the viewer none of them loaded. */
private const val SUBTITLE_ATTEMPT_LIMIT = 4

private const val ControlsHideDelayMs = 3000L
private const val LiveControlsHideDelayMs = 2000L
private const val LiveChannelInfoHideDelayMs = 5000L
private const val LiveHintVisibleMs = 3_000L
private const val LiveHintCycleMs = 15_000L
private const val AutoPlayNextEpisodeCountdownSeconds = 5
private const val NextEpisodeDiscoveryTimeoutMs = 8_000L
internal const val NextEpisodeSourceResolveTimeoutMs = 12_000L

/**
 * Live feeds drop out routinely (upstream restarts, ad breaks, CDN switches). Mobile retries
 * indefinitely; the TV app uses a bounded retry budget before surfacing a manual retry so a broken
 * outage never dumps the viewer out of the channel.
 */
private const val LiveReconnectMaxAttempts = 6
private const val LiveStallTimeoutMs = 15_000L

private data class SegmentAction(
    val kind: SegmentActionKind,
    val segmentType: String,
    /**
     * The chip's wording, as a resource rather than as text.
     *
     * [activeSegmentAction] decides *which* action is due from the playback position and the
     * viewer's skip preferences, which is a question about playback and not about language. Holding
     * the resource id here keeps the decision where it belongs while leaving the wording to be
     * resolved at the point it is drawn - which is also the only place a `stringResource` may be
     * read from, and the only place that re-reads it when the interface language changes.
     */
    @StringRes val labelRes: Int,
    val targetTimeSec: Double? = null,
)

private data class PendingEpisodeSelection(
    val episode: EpisodeContext,
    val stream: AddonStream,
    val streams: List<AddonStream>,
)

private class PlayerFocusRequesters {
    val errorBack = FocusRequester()
    val errorSources = FocusRequester()
    val watchlistKeep = FocusRequester()
    val watchlistRemove = FocusRequester()
    val play = FocusRequester()
    val subtitles = FocusRequester()
    val audio = FocusRequester()
    val sources = FocusRequester()
    val engine = FocusRequester()
    val next = FocusRequester()
    val watched = FocusRequester()
    val speed = FocusRequester()
    val info = FocusRequester()
    val segmentChip = FocusRequester()
    val nextEpisodePlay = FocusRequester()
    val nextEpisodeCancel = FocusRequester()
    val smartSwitch = FocusRequester()
    val progress = FocusRequester()
    val liveProgress = FocusRequester()
    val panelClose = FocusRequester()
    val panelFirstItem = FocusRequester()
    val playerRoot = FocusRequester()
    val liveChannelFirst = FocusRequester()
    val liveFavouriteFirst = FocusRequester()
    val favourite = FocusRequester()
}

private enum class SegmentActionKind {
    Skip,
    NextEpisode,
}

internal enum class ActivePlaybackEngine { Media3, MPV }

internal enum class LiveRetryAction { Reload, Refetch, GiveUp }

internal enum class PlayerInteractionLayer { Playback, Controls, Seeking, Drawer, Dialog }
internal enum class PlayerControlsFocusRegion { Seek, Controls }

internal fun playerInteractionLayer(
    dialogVisible: Boolean,
    drawerVisible: Boolean,
    seeking: Boolean,
    controlsVisible: Boolean,
): PlayerInteractionLayer = when {
    dialogVisible -> PlayerInteractionLayer.Dialog
    drawerVisible -> PlayerInteractionLayer.Drawer
    seeking -> PlayerInteractionLayer.Seeking
    controlsVisible -> PlayerInteractionLayer.Controls
    else -> PlayerInteractionLayer.Playback
}

internal fun liveRetryAction(attempt: Int): LiveRetryAction = when (attempt) {
    in 1..2 -> LiveRetryAction.Reload
    in 3..LiveReconnectMaxAttempts -> LiveRetryAction.Refetch
    else -> LiveRetryAction.GiveUp
}

internal fun continueWatchingCameFromAnotherPlatform(lastPlatform: String?, destination: String): Boolean {
    val origin = lastPlatform?.trim()?.lowercase().orEmpty()
    if (origin.isBlank()) return false
    return when (destination.trim().lowercase()) {
        "tv" -> origin !in setOf("tv", "androidtv", "firetv")
        "mobile" -> origin !in setOf("mobile", "android", "android-mobile")
        else -> origin != destination.trim().lowercase()
    }
}

internal fun crossDeviceContinueNotice(
    mediaType: String,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
): String {
    val isSeries = mediaType.trim().lowercase() in setOf("tv", "series", "show")
    val kind = if (isSeries) "series" else "movie"
    val sourceTarget = if (isSeries && seasonNumber != null && episodeNumber != null) {
        " for Season $seasonNumber, Episode $episodeNumber"
    } else {
        ""
    }
    return "You started this $kind on another device. Choose a source$sourceTarget to continue watching from where you left off."
}

internal fun normalizePlayerEngineSetting(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "media3", "exo", "exoplayer" -> "Media3"
    "mpv" -> "MPV"
    else -> "Auto"
}

internal fun initialPlaybackEngine(preference: String?): ActivePlaybackEngine =
    if (preference.equals("MPV", ignoreCase = true)) ActivePlaybackEngine.MPV else ActivePlaybackEngine.Media3

/** How often playback position is written back while a title is running. */
private const val PROGRESS_CHECKPOINT_INTERVAL_MS = 30_000L

internal fun shouldAutoFallbackToMpv(
    preference: String?,
    activeEngine: ActivePlaybackEngine,
    fallbackUsed: Boolean,
): Boolean = preference.equals("Auto", ignoreCase = true) &&
    activeEngine == ActivePlaybackEngine.Media3 &&
    !fallbackUsed

@Composable
fun PlayerScreen(
    repository: StreamDekRepository,
    request: PlaybackRequest,
    onBack: () -> Unit,
    onExitToStreams: () -> Unit,
    onExitToDetail: () -> Unit,
    onPlayRecommendation: (MediaItem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Playback failures are reported from coroutines and callbacks, none of which are a
    // composition; these are the resources the composition is already using.
    val playerResources = context.resources
    // Counts inside those notices are written the way the interface language writes them.
    val appLanguage = LocalAppLanguage.current
    val view = LocalView.current
    val bootstrap by repository.bootstrap.collectAsState()
    val playbackPreferences = bootstrap?.preferences?.playback ?: PlaybackPreferences()
    val favoriteSourceKeys = bootstrap?.preferences?.streams?.favoriteSourceKeys.orEmpty().toSet()
    // Live broadcasts: no detail/progress/watched bookkeeping, no seeking, and
    // back exits to the previous screen rather than the streams picker.
    val isLive = request.mediaType == "live"
    var activeLiveRequest by remember(request) { mutableStateOf<PlaybackRequest?>(null) }
    var liveChannelHistory by remember(request) { mutableStateOf<List<PlaybackRequest>>(emptyList()) }
    val playbackRequest = activeLiveRequest ?: request
    val isVod = isLive && playbackRequest.streamType.equals("movie", ignoreCase = true)
    val favouriteChannels by repository.favouriteChannels.collectAsState()
    val liveAddonFavourites = if (isLive) favouriteChannels else emptyList()
    val favouriteChannelKeys = favouriteChannels.mapTo(linkedSetOf()) { "${it.sourceAddonId}:${it.id}" }
    val currentChannelTitle = playbackRequest.title?.takeIf { it.isNotBlank() } ?: "Live TV"
    val completePlaybackExit: () -> Unit = if (isLive) onBack else onExitToDetail
    val backExitPlayback: () -> Unit = if (isLive || request.returnToDetailOnBack) onBack else onExitToStreams

    // Seeded from the cache the detail and streams screens already filled, so the start-up screen
    // shows this title's artwork immediately instead of a black hold while the fetch repeats.
    var detail by remember { mutableStateOf(if (isLive) null else repository.peekCachedDetail(request.mediaId, request.mediaType)) }
    var currentEpisode by remember(request) { mutableStateOf(request.episode) }
    var nextEpisode by remember { mutableStateOf<EpisodeContext?>(null) }
    var candidate by remember { mutableStateOf<ResolvedPlaybackCandidate?>(null) }
    var currentSourceUrl by remember { mutableStateOf<String?>(null) }
    val defaultPlaybackHeaders = remember { mapOf("User-Agent" to "Mozilla/5.0 StreamDekTV") }
    var currentRequestHeaders by remember { mutableStateOf(defaultPlaybackHeaders) }
    var currentLabel by remember { mutableStateOf(playerResources.getString(R.string.player_selecting_stream)) }
    var paused by remember { mutableStateOf(false) }
    var positionSec by remember { mutableDoubleStateOf(0.0) }
    var durationSec by remember { mutableDoubleStateOf(0.0) }
    var audioTracks by remember { mutableStateOf<List<MpvTrackInfo>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<MpvTrackInfo>>(emptyList()) }
    var externalSubtitles by remember { mutableStateOf<List<ExternalSubtitleTrack>>(emptyList()) }
    var externalSubtitlesPreparedForSource by remember { mutableStateOf<String?>(null) }
    var subtitlesLoading by remember { mutableStateOf(false) }
    var selectedExternalSubtitleId by remember { mutableStateOf<String?>(null) }
    var externalSubtitleAppliedKey by remember { mutableStateOf<String?>(null) }
    var subtitleErrorMessage by remember { mutableStateOf<String?>(null) }
    var subtitleSelectionGeneration by remember { mutableIntStateOf(0) }
    var selectedAudioId by remember { mutableIntStateOf(-1) }
    var selectedSubtitleId by remember { mutableIntStateOf(-1) }
    var speed by remember { mutableDoubleStateOf(1.0) }
    var panel by remember { mutableStateOf<OverlayPanel?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var playerView: MpvPlayerController? by remember { mutableStateOf(null) }
    /**
     * Which engine is playing, and whether the automatic swap to mpv has been spent.
     *
     * Deliberately NOT `remember(currentSourceUrl, ...)`. Keying them on the source reset them by
     * building new state objects every time the source changed -- and the source changes once
     * during every normal playback, when it resolves from null to a URL. The player view is
     * created before that happens, and `AndroidView`'s factory runs exactly once, so every
     * callback the view holds closes over the state objects that existed at that moment. After
     * the source resolved those objects were orphaned: the callbacks kept writing to them and
     * nothing was left reading. That is why the profile 7 switch logged its line and then did
     * nothing at all, and why the error-driven fallback to mpv could never have worked either.
     *
     * They are reset explicitly instead, by [resetPlaybackEngineForNewSource] at each point a
     * genuinely new source starts.
     */
    var activePlaybackEngine by remember { mutableStateOf(initialPlaybackEngine(playbackPreferences.playerEngine)) }
    var autoEngineFallbackUsed by remember { mutableStateOf(false) }
    var failedStreamKeys by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(emptySet<String>()) }
    var sourceFallbackInProgress by remember(request.mediaId, request.mediaType) { mutableStateOf(false) }
    /**
     * What the loading screen says while the player works down the list.
     *
     * Kept apart from [error], which is only drawn once loading has stopped — so the "trying
     * another stream" line that used to be written there was never on screen at the one moment it
     * had something to say. Walking three dead sources can take the better part of a minute, and
     * without this the viewer is looking at a still logo wondering whether anything is happening.
     */
    var sourceFallbackNotice by remember(request.mediaId, request.mediaType) { mutableStateOf<String?>(null) }
    var continueSourceNotice by remember(request.mediaId, request.mediaType) { mutableStateOf<String?>(null) }
    var continueSourceChoiceRequired by remember(request.mediaId, request.mediaType) { mutableStateOf(false) }
    /** Where to pick up after an engine swap. Unkeyed for the same reason as the two above. */
    var pendingEngineResumePositionSec by remember { mutableStateOf<Double?>(null) }
    var loading by remember { mutableStateOf(true) }
    var media3Buffering by remember { mutableStateOf(false) }
    var recentPlaybackStalls by remember(request.mediaId, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(emptyList<Long>()) }
    var smartSwitchCandidate by remember(request.mediaId, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf<com.streamdek.tv.nativeapp.data.AddonStream?>(null) }
    var smartSwitchCooldownUntil by remember(request.mediaId, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(0L) }

    /**
     * Puts the engine back to the viewer's preference for a genuinely new source.
     *
     * Called where the old `remember` keys used to do it implicitly. The two places that change
     * source *and* mean to carry something across -- the source-fallback walk and the manual
     * engine switch -- set these themselves and do not call this.
     */
    fun resetPlaybackEngineForNewSource() {
        activePlaybackEngine = initialPlaybackEngine(playbackPreferences.playerEngine)
        autoEngineFallbackUsed = false
        pendingEngineResumePositionSec = null
    }

    // Funnel state for the attempt currently on screen. Keyed on the title so opening something
    // else starts a fresh attempt rather than inheriting the previous one's outcome.
    val attemptCorrelationId = remember(request.mediaId, request.mediaType) { Telemetry.newCorrelationId() }
    val attemptStartedAt = remember(request.mediaId, request.mediaType) { System.currentTimeMillis() }
    var sourcesTried by remember(request.mediaId, request.mediaType) { mutableStateOf(1) }
    /** One outcome per attempt: whichever of started/failed happens first wins. */
    var playbackOutcomeReported by remember(request.mediaId, request.mediaType) { mutableStateOf(false) }

    var controlsVisible by remember { mutableStateOf(false) }
    var controlsFocusRegion by remember { mutableStateOf(PlayerControlsFocusRegion.Controls) }
    /** Where in the controls row the highlight belongs next. Null means Play, the row's default. */
    var controlsEntryRequester by remember { mutableStateOf<FocusRequester?>(null) }
    /** Bumped to ask the bar to place that highlight, so repeating the same request still lands. */
    var controlsFocusToken by remember { mutableIntStateOf(0) }
    var showLiveProgress by remember(playbackRequest.mediaId, playbackPreferences.liveProgressBarEnabled) {
        mutableStateOf(playbackPreferences.liveProgressBarEnabled)
    }
    var controlsHideJob by remember { mutableStateOf<Job?>(null) }
    var liveChannelInfoVisible by remember { mutableStateOf(false) }
    var liveChannelInfoHideJob by remember { mutableStateOf<Job?>(null) }
    var liveChannels by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var liveChannelsLoading by remember { mutableStateOf(false) }
    var liveChannelRowVisible by remember { mutableStateOf(false) }
    var liveFavouritesDrawerVisible by remember { mutableStateOf(false) }
    // Opens in whichever view the profile has synced from mobile. The in-player toggle below still
    // switches it for this session; it just no longer starts from nothing every time.
    var liveFavouritesCardView by remember(bootstrap?.preferences?.home?.liveFavouriteDrawerCards) {
        mutableStateOf(bootstrap?.preferences?.home?.liveFavouriteDrawerCards == true)
    }
    var liveHintsVisible by remember { mutableStateOf(true) }
    var liveRefetchGeneration by remember { mutableIntStateOf(0) }
    var lastLiveProgressAtMs by remember { mutableStateOf(0L) }
    var lastLiveProgressPositionSec by remember { mutableDoubleStateOf(-1.0) }
    var pendingSeekJob by remember { mutableStateOf<Job?>(null) }
    var pendingResumePositionSec by remember { mutableStateOf<Double?>(null) }
    var pendingResumeContentKey by remember { mutableStateOf<String?>(null) }
    var lastWorkingSourceUrl by remember { mutableStateOf<String?>(null) }
    /**
     * Whether this attempt started from the stored URL rather than from a resolve.
     *
     * Two things hang off it: the stream list behind the Sources panel has not been fetched, so it
     * is filled in on demand; and if the picture never arrives, the stored URL has gone stale and
     * must be dropped rather than tried again on the next resume.
     */
    var startedFromRememberedSource by remember { mutableStateOf(false) }
    var lastWorkingLabel by remember { mutableStateOf<String?>(null) }
    var lastWorkingRequestHeaders by remember { mutableStateOf(defaultPlaybackHeaders) }
    var liveReconnectJob by remember { mutableStateOf<Job?>(null) }
    var liveReconnectAttempt by remember { mutableIntStateOf(0) }
    var pauseInfoVisible by remember { mutableStateOf(false) }
    var lastSeekInputAt by remember { mutableStateOf(0L) }
    var lastSeekDirection by remember { mutableIntStateOf(0) }
    var seekBurstCount by remember { mutableIntStateOf(0) }
    // Scrub state: while a seek is in flight, MPV progress events still report the old
    // position; seekTargetSec pins the UI position until the seek settles so the bar
    // never snaps backwards mid-scrub.
    var seekTargetSec by remember { mutableStateOf<Double?>(null) }
    var seekIssuedAtMs by remember { mutableStateOf(0L) }
    var lastSeekCommandAtMs by remember { mutableStateOf(0L) }
    // Guards against ghost clicks landing on the play/pause button right after an
    // option panel closes (tv-material fires onClick on KeyUp, so a selection's key
    // release can reach the newly focused control).
    var panelClosedAtMs by remember { mutableStateOf(0L) }
    var audioPreferenceAppliedForSource by remember { mutableStateOf<String?>(null) }
    var subtitlePreferenceAppliedForSource by remember { mutableStateOf<String?>(null) }
    var traktScrobbledStart by remember { mutableStateOf(false) }
    var inWatchlist by remember(request.mediaId, request.mediaType) { mutableStateOf(false) }
    var completionThresholdReached by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    var watchedMarked by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    var watchlistPromptVisible by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    var watchlistPromptShown by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    var completionExitTriggered by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    var segments by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf<List<PlaybackSegment>>(emptyList()) }
    var handledSegmentTypes by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) {
        mutableStateOf(setOf<String>())
    }
    var nextEpisodeDialogVisible by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    var nextEpisodeCandidate by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf<ResolvedPlaybackCandidate?>(null) }
    var nextEpisodeLoading by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    var nextEpisodeCountdown by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf<Int?>(null) }
    var queuedNextEpisode by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    var recommendationDialogVisible by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    var recommendationDismissed by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    var queuedRecommendation by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf<MediaItem?>(null) }
    var recommendationHasFocus by remember(request.mediaId, request.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    var pendingEpisodeSelection by remember(request.mediaId, request.mediaType) { mutableStateOf<PendingEpisodeSelection?>(null) }
    var nextEpisodeTransitionInProgress by remember(request.mediaId, request.mediaType) { mutableStateOf(false) }
    var episodeLoadGeneration by remember(request.mediaId, request.mediaType) { mutableIntStateOf(0) }
    var streamKeyOverride by remember(request.mediaId, request.mediaType) { mutableStateOf(request.selectedStreamKey) }
    var streamLabelOverride by remember(request.mediaId, request.mediaType) { mutableStateOf(request.selectedStreamLabel) }
    // Subtitle appearance, seeded from what this device last settled on and applied to whichever
    // engine is playing. Kept per-device rather than synced: it is a property of the panel and the
    // seat in front of it.
    var subtitleFontSize by remember { mutableIntStateOf(repository.subtitleFontSize()) }
    var subtitlePosition by remember { mutableIntStateOf(repository.subtitlePosition()) }
    // Not persisted: a delay corrects one badly-timed subtitle file, and carrying it into the next
    // episode would silently desynchronise a file that was fine.
    var subtitleDelay by remember(currentSourceUrl) { mutableDoubleStateOf(0.0) }
    var playbackStats by remember { mutableStateOf<PlaybackStats?>(null) }
    var streamsReloading by remember { mutableStateOf(false) }
    /**
     * A skip prompt owns the remote while it is up.
     *
     * Nothing else may be summoned until it is taken or dismissed: without this the same press that
     * was meant for "Skip Intro" also raised the transport controls behind it, and the viewer ended
     * up with two things on screen competing for the next press.
     */
    var segmentPromptActive by remember { mutableStateOf(false) }
    var autoSkipNotice by remember(request.mediaId, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf<String?>(null) }
    var playerActivityVersion by remember { mutableIntStateOf(0) }
    var pausedSleepTriggered by remember(request.mediaId, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber) { mutableStateOf(false) }
    val pausedIdleTimeoutMillis = remember(context) {
        idleTimeoutMillis(TvIdlePreferences(context).pausedTimeoutMinutes)
    }

    val resolvedRenderSurface = remember(playbackPreferences.renderSurface) {
        normalizeRenderSurfacePreference(playbackPreferences.renderSurface)
    }

    val focusRequesters = remember { PlayerFocusRequesters() }
    val errorBackRequester = focusRequesters.errorBack
    val errorSourcesRequester = focusRequesters.errorSources
    val watchlistPromptKeepRequester = focusRequesters.watchlistKeep
    val watchlistPromptRemoveRequester = focusRequesters.watchlistRemove
    val playRequester = focusRequesters.play
    val subtitlesRequester = focusRequesters.subtitles
    val audioRequester = focusRequesters.audio
    val sourcesRequester = focusRequesters.sources
    val engineRequester = focusRequesters.engine
    val nextRequester = focusRequesters.next
    val watchedRequester = focusRequesters.watched
    val speedRequester = focusRequesters.speed
    val infoRequester = focusRequesters.info
    val segmentChipRequester = focusRequesters.segmentChip
    val nextEpisodePlayRequester = focusRequesters.nextEpisodePlay
    val nextEpisodeCancelRequester = focusRequesters.nextEpisodeCancel
    val smartSwitchRequester = focusRequesters.smartSwitch
    val progressRequester = focusRequesters.progress
    val liveProgressRequester = focusRequesters.liveProgress
    val panelCloseRequester = focusRequesters.panelClose
    val panelFirstItemRequester = focusRequesters.panelFirstItem
    val interactionLayer = playerInteractionLayer(
        dialogVisible = segmentPromptActive || nextEpisodeDialogVisible || recommendationDialogVisible || watchlistPromptVisible ||
            smartSwitchCandidate != null || (error != null && !loading),
        drawerVisible = panel != null || liveFavouritesDrawerVisible || liveChannelRowVisible,
        seeking = controlsVisible && seekTargetSec != null,
        controlsVisible = controlsVisible || (paused && !pauseInfoVisible),
    )
    // Whether the bottom bar — and therefore the seek row — is on screen and able to own focus.
    //
    // This is deliberately the same expression the bar itself is composed under. The key handler
    // used to gate on `controlsVisible` alone, which is a different thing: the bar is also shown
    // while paused, and in that state the seek row held focus while the root declined to treat
    // horizontal input as seeking, so the framework's spatial search took the press and carried
    // the highlight into the controls.
    val bottomBarOnScreen = !loading && error == null && !nextEpisodeDialogVisible && !recommendationDialogVisible &&
        (interactionLayer == PlayerInteractionLayer.Controls ||
            interactionLayer == PlayerInteractionLayer.Seeking)
    /** Mirrors PlayerBottomBar: a live channel only has a seek row once its progress bar is on. */
    val hasSeekableTimeline = !isLive || (showLiveProgress && durationSec > 0.0)
    val playerRootRequester = focusRequesters.playerRoot
    val liveChannelFirstRequester = focusRequesters.liveChannelFirst
    val liveChannelListState = rememberLazyListState()
    val liveFavouriteFirstRequester = focusRequesters.liveFavouriteFirst
    val liveFavouriteListState = rememberLazyListState()
    val favouriteRequester = focusRequesters.favourite

    // Keep the screen on while something is actually playing - and only then.
    @Composable
    fun KeepScreenOnEffect() {
    // Paused is the case that matters: a film left paused used to hold the display awake all
    // night, because the flag went up once and came down only when the player closed.
    LaunchedEffect(paused, loading) {
        view.keepScreenOn = !paused || loading
    }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            // Closes the news server connections and drops the partially assembled file. A no-op
            // unless what was playing came from a usenet source.
            com.streamdek.tv.nativeapp.usenet.UsenetPlayback.release()
            // Stops the local libtorrent session and loopback server. Cached pieces remain under
            // the bounded device cache; no torrent state is sent to StreamDek Backend.
            com.streamdek.tv.nativeapp.peer.LocalTorrentPlayback.release()
        }
    }
    }
    KeepScreenOnEffect()

    fun hideControlsNow() {
        controlsHideJob?.cancel()
        controlsHideJob = null
        controlsVisible = false
    }

    fun showLiveChannelInfo() {
        if (!isLive || loading || error != null) return
        liveChannelInfoHideJob?.cancel()
        liveChannelInfoVisible = true
        liveChannelInfoHideJob = scope.launch {
            delay(LiveChannelInfoHideDelayMs)
            liveChannelInfoVisible = false
        }
    }

    fun showLiveChannelRow() {
        if (!isLive || liveChannels.size <= 1 || loading || error != null) {
            showLiveChannelInfo()
            return
        }
        liveChannelInfoHideJob?.cancel()
        liveChannelInfoVisible = false
        liveChannelRowVisible = true
        scope.launch {
            val currentIndex = liveChannels.indexOfFirst { it.id == playbackRequest.mediaId }.coerceAtLeast(0)
            liveChannelListState.scrollToItem(currentIndex)
            delay(100)
            runCatching { liveChannelFirstRequester.requestFocus() }
        }
    }

    /**
     * The channel playing, as something that can be favourited.
     *
     * Prefers the item from the loaded channel list, which carries artwork and category — the
     * favourites drawer draws cards from what is stored, so a stub saved from the playback request
     * alone would come back as a blank tile. The stub is the fallback for a channel opened from
     * somewhere the list was never loaded.
     */
    fun currentChannelAsItem(): MediaItem =
        liveChannels.firstOrNull {
            it.id == playbackRequest.mediaId && it.sourceAddonId == playbackRequest.sourceAddonId
        } ?: liveAddonFavourites.firstOrNull {
            it.id == playbackRequest.mediaId && it.sourceAddonId == playbackRequest.sourceAddonId
        } ?: MediaItem(
            id = playbackRequest.mediaId,
            title = currentChannelTitle,
            type = "live",
            streamType = playbackRequest.streamType,
            sourceAddonId = playbackRequest.sourceAddonId,
            sourceAddonName = playbackRequest.sourceAddonName,
            sourceCatalogId = playbackRequest.sourceCatalogId,
            sourceCatalogName = playbackRequest.sourceCatalogName,
            // Carried so a playlist channel still plays when it is picked out of favourites later:
            // those have no add-on to resolve a stream from, only this URL.
            directStreamUrl = playbackRequest.directStreamUrl,
            requestHeaders = playbackRequest.requestHeaders,
        )

    val currentChannelIsFavourite = isLive &&
        "${playbackRequest.sourceAddonId}:${playbackRequest.mediaId}" in favouriteChannelKeys

    fun showLiveFavouritesDrawer() {
        if (!isLive || liveAddonFavourites.isEmpty() || loading || error != null) return
        liveChannelInfoHideJob?.cancel()
        liveChannelInfoVisible = false
        liveChannelRowVisible = false
        liveFavouritesDrawerVisible = true
        scope.launch {
            val currentIndex = liveAddonFavourites.indexOfFirst { it.id == playbackRequest.mediaId }.coerceAtLeast(0)
            liveFavouriteListState.scrollToItem(currentIndex)
            delay(100)
            runCatching { liveFavouriteFirstRequester.requestFocus() }
        }
    }
    fun selectLiveChannel(item: MediaItem) {
        if (!isLive || (item.id == playbackRequest.mediaId && item.sourceAddonId == playbackRequest.sourceAddonId)) {
            // The clicked channel is already playing — this is the common case when the
            // favourites drawer opens with the current channel pre-focused and the user just
            // presses OK on it. Both overlays need to close here, not just the channel row,
            // or selecting the already-playing favourite silently does nothing.
            liveChannelRowVisible = false
            liveFavouritesDrawerVisible = false
            scope.launch { runCatching { playerRootRequester.requestFocus() } }
            return
        }
        val nextRequest = PlaybackRequest(
            mediaId = item.id,
            mediaType = "live",
            title = item.title,
            streamType = item.streamType,
            sourceAddonId = item.sourceAddonId,
            sourceAddonName = item.sourceAddonName,
            sourceCatalogId = item.sourceCatalogId,
            sourceCatalogName = item.sourceCatalogName,
            directStreamUrl = item.directStreamUrl,
            requestHeaders = item.requestHeaders,
        )
        TvDebugLogger.i("Player", "switch live channel from=${playbackRequest.mediaId} to=${item.id} addon=${item.sourceAddonName}")
        liveChannelHistory = (liveChannelHistory + playbackRequest).takeLast(20)
        repository.savePlaybackRequest(nextRequest)
        loading = true
        error = null
        currentLabel = "Loading ${item.title}…"
        liveChannelRowVisible = false
        liveFavouritesDrawerVisible = false
        liveReconnectAttempt = 0
        liveRefetchGeneration = 0
        streamKeyOverride = null
        streamLabelOverride = null
        activeLiveRequest = nextRequest
    }

    fun scheduleLiveReconnect(message: String) {
        if (!isLive || liveReconnectJob?.isActive == true) return
        val attempt = liveReconnectAttempt + 1
        val retryAction = liveRetryAction(attempt)
        if (retryAction == LiveRetryAction.GiveUp) {
            if (!playbackOutcomeReported) {
                playbackOutcomeReported = true
                Telemetry.playbackFailed(
                    correlationId = attemptCorrelationId,
                    mediaId = request.mediaId,
                    mediaType = request.mediaType,
                    title = request.title,
                    addonKey = candidate?.stream?.addonId?.takeIf { it.isNotBlank() }
                        ?: candidate?.stream?.addonName,
                    provider = candidate?.stream?.cachedBy?.firstOrNull(),
                    errorCategory = classifyPlaybackFailure(message),
                    errorCode = "live_reconnect_exhausted",
                    durationMs = System.currentTimeMillis() - attemptStartedAt,
                    sourcesTried = sourcesTried,
                )
            }
            error = "Live feed unavailable after $LiveReconnectMaxAttempts retries: $message"
            loading = false
            controlsVisible = true
            return
        }
        liveReconnectAttempt = attempt
        error = null
        loading = true
        liveChannelRowVisible = false
        controlsVisible = false
        currentLabel = if (retryAction == LiveRetryAction.Reload) {
            playerResources.getString(R.string.player_reloading_live)
        } else {
            playerResources.getString(R.string.player_refreshing_live_attempt, AppFormats.number(appLanguage, attempt))
        }
        TvDebugLogger.w("Player", "live retry attempt=$attempt mediaId=${playbackRequest.mediaId} reason=$message")
        liveReconnectJob = scope.launch {
            delay((attempt * 500L).coerceIn(500L, 5_000L))
            if (retryAction == LiveRetryAction.Reload && !currentSourceUrl.isNullOrBlank()) {
                playerView?.reloadSource()
                playerView?.setPaused(false)
            } else {
                liveReconnectJob = null
                liveRefetchGeneration += 1
            }
        }
    }
    fun scheduleSeek(targetSeconds: Double, fast: Boolean = false) {
        val target = targetSeconds
            .coerceAtLeast(0.0)
            .coerceAtMost(durationSec.takeIf { it > 0.0 } ?: targetSeconds)
        seekTargetSec = target
        seekIssuedAtMs = System.currentTimeMillis()
        positionSec = target
        pendingSeekJob?.cancel()
        val now = System.currentTimeMillis()
        if (now - lastSeekCommandAtMs >= 350L) {
            // Throttled immediate seek keeps held-button scrubbing responsive
            // instead of waiting for the key repeats to stop.
            lastSeekCommandAtMs = now
            if (fast) playerView?.seekToFast(target) else playerView?.seekTo(target)
        }
        // Always schedule a trailing settle so the final resting position is exact,
        // including the last step of a held scrub.
        pendingSeekJob = scope.launch {
            delay(if (fast) 220 else 160)
            lastSeekCommandAtMs = System.currentTimeMillis()
            seekIssuedAtMs = lastSeekCommandAtMs
            playerView?.seekTo(target)
        }
    }

    fun scheduleRelativeSeek(baseDeltaSeconds: Double) {
        val now = System.currentTimeMillis()
        val direction = if (baseDeltaSeconds >= 0.0) 1 else -1
        seekBurstCount = if (lastSeekDirection == direction && now - lastSeekInputAt <= 450L) {
            seekBurstCount + 1
        } else {
            0
        }
        lastSeekDirection = direction
        lastSeekInputAt = now
        val multiplier = when {
            seekBurstCount >= 10 -> 8.0
            seekBurstCount >= 6 -> 4.0
            seekBurstCount >= 3 -> 2.0
            else -> 1.0
        }
        // Base the next step on the in-flight scrub target, not the (stale) playback
        // position, so consecutive presses always accumulate in the scrub direction.
        scheduleSeek((seekTargetSec ?: positionSec) + (baseDeltaSeconds * multiplier), fast = true)
    }

    fun scheduleControlsHide() {
        controlsHideJob?.cancel()
        controlsHideJob = null
        if (paused || panel != null || loading || error != null) return
        controlsHideJob = scope.launch {
            delay(if (isLive) LiveControlsHideDelayMs else ControlsHideDelayMs)
            if (!paused && panel == null && !loading && error == null) {
                controlsVisible = false
            }
        }
    }

    /**
     * Hands the controls row focus, naming where in it the highlight should land.
     *
     * The bar places it, because the bar is the thing that knows when its buttons exist. The shell
     * used to place it too — a delayed request fired from here — and the two raced: whichever ran
     * after the bar attached won, so dismissing a drawer landed on Play about as often as it landed
     * on the icon that opened the drawer. One side asks, the other places.
     */
    fun focusControls(target: FocusRequester? = null) {
        controlsEntryRequester = target
        controlsFocusRegion = PlayerControlsFocusRegion.Controls
        controlsFocusToken += 1
    }

    fun showControls(focusPlay: Boolean = false) {
        // Every route into the controls funnels through here — key handling, the engine's own
        // remote callbacks, the end of a load — so one check covers all of them.
        if (segmentPromptActive) return
        pauseInfoVisible = false
        controlsVisible = true
        scheduleControlsHide()
        if (focusPlay) focusControls()
    }

    fun restoreControlsAfterPanel(closedPanel: OverlayPanel) {
        pauseInfoVisible = false
        controlsVisible = true
        scheduleControlsHide()
        // Back to the icon that opened it. A drawer is somewhere the viewer went from a specific
        // place, and coming out somewhere else — always Play, several steps away — makes trying a
        // second option cost a journey back across the row.
        focusControls(
            when (closedPanel) {
                OverlayPanel.Streams -> sourcesRequester
                OverlayPanel.Engine -> engineRequester
                OverlayPanel.Audio -> audioRequester
                OverlayPanel.Subtitles -> subtitlesRequester
                OverlayPanel.Speed -> speedRequester
                OverlayPanel.Info -> infoRequester
            },
        )
    }

    /**
     * Refills the list behind the Sources panel.
     *
     * Also the price of the remembered fast path: starting from a stored URL means no stream lookup
     * ran, so the panel would otherwise open onto the single source that is playing. Deferring the
     * lookup to the moment someone actually opens the panel keeps the resume instant and charges
     * nobody who never looks.
     */
    fun reloadStreamCandidates(forceRefresh: Boolean) {
        if (streamsReloading) return
        streamsReloading = true
        scope.launch {
            runCatching {
                repository.streamCandidates(
                    mediaType = playbackRequest.mediaType,
                    mediaId = playbackRequest.mediaId,
                    imdbId = playbackRequest.imdbId ?: detail?.imdbId,
                    episode = currentEpisode,
                    streamType = playbackRequest.streamType,
                    directStreamUrl = playbackRequest.directStreamUrl,
                    requestHeaders = playbackRequest.requestHeaders,
                    sourceAddonId = playbackRequest.sourceAddonId,
                    sourceAddonName = playbackRequest.sourceAddonName,
                    forceRefresh = forceRefresh,
                ).collect { progress ->
                    // Published per batch, so the panel fills as the scrapers answer rather than
                    // at the end.
                    candidate = candidate?.copy(streams = progress.streams)
                        ?: ResolvedPlaybackCandidate(null, null, progress.streams)
                }
            }
            streamsReloading = false
        }
    }

    fun registerInteraction() {
        playerActivityVersion += 1
        if (segmentPromptActive) return
        pauseInfoVisible = false
        if (!controlsVisible) controlsVisible = true
        scheduleControlsHide()
    }

    fun toggleCurrentChannelFavourite() {
        if (!isLive) return
        repository.toggleFavouriteChannel(currentChannelAsItem())
        // Keeps the control bar up so the star is seen to change rather than vanishing with it.
        registerInteraction()
    }

    fun traktProgressPercent(): Double {
        if (durationSec <= 0.0) return 0.0
        return ((positionSec / durationSec) * 100.0).coerceIn(0.0, 100.0)
    }

    fun activeSegmentAction(): SegmentAction? {
        val outro = segments.firstOrNull { it.segmentType == "outro" }
        val nextEpisodeAvailable = nextEpisode != null &&
            playbackPreferences.isAutoPlayNextEpisodeEnabled() &&
            !handledSegmentTypes.contains("outro") &&
            playbackPreferences.isNextEpisodeThresholdReached(
                positionSec = positionSec,
                durationSec = durationSec,
                segmentStartSec = outro?.startSec,
            )
        if (nextEpisodeAvailable) {
            return SegmentAction(
                kind = SegmentActionKind.NextEpisode,
                segmentType = "outro",
                labelRes = R.string.player_next_episode,
            )
        }

        val activeSegment = segments
            .filter { segment ->
                playbackPreferences.isSegmentEnabled(segment.segmentType) &&
                    !handledSegmentTypes.contains(segment.segmentType) &&
                    positionSec >= segment.startSec &&
                    positionSec < segment.endSec
            }
            .sortedWith(
                compareBy<PlaybackSegment>(
                    { segmentPriority(it.segmentType) },
                    { it.startSec },
                    { it.endSec },
                ),
            )
            .firstOrNull() ?: return null

        return when (activeSegment.segmentType) {
            "intro" -> SegmentAction(
                kind = SegmentActionKind.Skip,
                segmentType = activeSegment.segmentType,
                labelRes = R.string.player_skip_intro,
                targetTimeSec = activeSegment.endSec,
            )
            "recap" -> SegmentAction(
                kind = SegmentActionKind.Skip,
                segmentType = activeSegment.segmentType,
                labelRes = R.string.player_skip_recap,
                targetTimeSec = activeSegment.endSec,
            )
            "outro" -> SegmentAction(
                kind = SegmentActionKind.Skip,
                segmentType = activeSegment.segmentType,
                labelRes = R.string.player_skip_ending,
                targetTimeSec = activeSegment.endSec,
            )
            else -> null
        }
    }

    fun markSegmentHandled(segmentType: String) {
        handledSegmentTypes = handledSegmentTypes + segmentType
    }

    fun currentWatchlistItem(): com.streamdek.tv.nativeapp.data.MediaItem? {
        val currentDetail = detail ?: return null
        return com.streamdek.tv.nativeapp.data.MediaItem(
            id = request.mediaId,
            tmdbId = currentDetail.tmdbId,
            title = currentDetail.title,
            type = request.mediaType,
            poster = currentDetail.poster,
            backdrop = currentDetail.backdrop,
            description = currentDetail.description,
            rating = currentDetail.rating,
            year = currentDetail.year,
            titleLogo = currentDetail.titleLogo,
            progress = traktProgressPercent(),
            positionSec = positionSec,
            durationSec = durationSec,
            episode = currentEpisode,
        )
    }

    suspend fun syncProgressIfEligible() {
        if (isLive || completionThresholdReached) return
        repository.syncProgress(request.mediaType, request.mediaId, positionSec, durationSec, currentEpisode, detail)
    }

    suspend fun markWatchedAndClearProgressIfNeeded(
        episodeToMark: EpisodeContext? = currentEpisode,
        completedPositionSec: Double = positionSec,
        completedDurationSec: Double = durationSec,
    ) {
        if (watchedMarked) return
        val syncCompleted = repository.completeProgress(
            request.mediaType,
            request.mediaId,
            episodeToMark,
            detail,
            completedPositionSec,
            completedDurationSec,
        )
        val marked = repository.markWatched(
            mediaType = request.mediaType,
            mediaId = request.mediaId,
            title = detail?.title ?: request.title ?: "",
            year = detail?.year,
            episode = episodeToMark,
            imdbId = request.imdbId ?: detail?.imdbId,
        )
        watchedMarked = syncCompleted || marked
    }

    suspend fun removeFromWatchlistIfNeeded() {
        if (!inWatchlist) return
        currentWatchlistItem()?.let { repository.removeFromWatchlist(it) }
        inWatchlist = false
    }

    suspend fun openNextEpisodeDialog() {
        if (nextEpisodeTransitionInProgress) return
        val targetEpisode = nextEpisode ?: return
        val currentStream = candidate?.stream
        val effectiveImdbId = request.imdbId ?: detail?.imdbId
        markSegmentHandled("outro")
        controlsVisible = false
        // The next-episode card deliberately remains on the left. Only retire the completed
        // episode's delayed synopsis overlay on the right while source discovery is in progress.
        pauseInfoVisible = false
        nextEpisodeDialogVisible = true
        nextEpisodeLoading = true
        nextEpisodeCountdown = null
        nextEpisodeCandidate = null
        // Discovery must never resolve every torrent before the dialog can render. The canonical
        // progressive pipeline publishes provider results as they arrive; keep collecting briefly
        // so late sources can join, but expose the first ranked batch immediately and put a hard
        // ceiling on the otherwise unbounded provider fan-out.
        withTimeoutOrNull(NextEpisodeDiscoveryTimeoutMs) {
            repository.streamCandidates(
                mediaType = request.mediaType,
                mediaId = request.mediaId,
                imdbId = effectiveImdbId,
                episode = targetEpisode,
                preferredAddonName = if (playbackPreferences.preferBingeGroupNextEpisode) currentStream?.addonName else null,
                preferredQualityGroup = if (playbackPreferences.preferBingeGroupNextEpisode) currentStream?.quality else null,
                forceRefresh = false,
            ).collect { progress ->
                if (!nextEpisodeDialogVisible || nextEpisode != targetEpisode) return@collect
                if (progress.streams.isNotEmpty()) {
                    val ranked = progress.streams
                    nextEpisodeCandidate = ResolvedPlaybackCandidate(
                        source = null,
                        stream = ranked.firstOrNull(),
                        streams = ranked,
                    )
                }
            }
        }
        nextEpisodeLoading = false
    }

    fun beginNextEpisode(streamIndex: Int? = null) {
        if (nextEpisodeTransitionInProgress) return
        val targetEpisode = nextEpisode ?: return
        val fromEpisode = currentEpisode
        val completedPositionSec = positionSec
        val completedDurationSec = durationSec
        val completedTraktProgress = traktProgressPercent()
        val targetIsLater = isForwardEpisodeTransition(
            fromSeason = fromEpisode?.seasonNumber,
            fromEpisode = fromEpisode?.episodeNumber,
            targetSeason = targetEpisode.seasonNumber,
            targetEpisode = targetEpisode.episodeNumber,
        )
        if (!targetIsLater) {
            nextEpisodeDialogVisible = false
            nextEpisodeLoading = false
            nextEpisodeCountdown = null
            error = "The next episode could not be identified safely. Return to the series and choose an episode."
            controlsVisible = true
            TvDebugLogger.w(
                "EpisodeTransition",
                "rejected non-forward transition from=S${fromEpisode?.seasonNumber}E${fromEpisode?.episodeNumber} to=S${targetEpisode.seasonNumber}E${targetEpisode.episodeNumber}",
            )
            return
        }
        val selectedStream = when {
            streamIndex != null -> nextEpisodeCandidate?.streams?.getOrNull(streamIndex)
            else -> nextEpisodeCandidate?.stream ?: nextEpisodeCandidate?.streams?.firstOrNull()
        } ?: return
        val shouldStopCompletedEpisodeScrobble = traktScrobbledStart
        traktScrobbledStart = false
        pendingEpisodeSelection = PendingEpisodeSelection(
            episode = targetEpisode,
            stream = selectedStream,
            streams = nextEpisodeCandidate?.streams.orEmpty(),
        )
        nextEpisodeTransitionInProgress = true
        nextEpisodeDialogVisible = false
        nextEpisodeCountdown = null
        // Retire the completed episode visually before any watched/sync write is allowed to wait.
        // Keeping its source attached was what left the paused synopsis over the next-episode
        // search and also made the old player position look authoritative during the hand-off.
        paused = true
        pauseInfoVisible = false
        controlsVisible = false
        panel = null
        loading = true
        currentLabel = "Searching for next episode…"
        continueSourceNotice = null
        sourceFallbackNotice = null
        currentSourceUrl = null
        candidate = null
        pendingResumePositionSec = null
        pendingResumeContentKey = null
        positionSec = 0.0
        durationSec = 0.0
        scope.launch {
            markWatchedAndClearProgressIfNeeded(
                episodeToMark = fromEpisode,
                completedPositionSec = completedPositionSec,
                completedDurationSec = completedDurationSec,
            )
            if (shouldStopCompletedEpisodeScrobble) {
                repository.traktScrobble(
                    action = "stop",
                    mediaType = request.mediaType,
                    mediaId = request.mediaId,
                    title = detail?.title ?: request.title,
                    year = detail?.year,
                    progress = completedTraktProgress,
                )
            }
            TvDebugLogger.i(
                "EpisodeTransition",
                "from=S${fromEpisode?.seasonNumber}E${fromEpisode?.episodeNumber} to=S${targetEpisode.seasonNumber}E${targetEpisode.episodeNumber} previousEpisodeCompleted=$watchedMarked",
            )
        }
        // Playback preparation must not wait for SyncDek/Trakt writes. They carry the captured
        // completed-episode identity above and can finish independently while the selected next
        // source starts resolving now.
        streamKeyOverride = repository.streamSelectionKey(selectedStream)
        streamLabelOverride = repository.describeStreamOption(selectedStream)
        nextEpisodeCandidate = null
        paused = false
        currentEpisode = targetEpisode
        // A generation guarantees a fresh load even if a stale callback races with Compose's
        // episode-key update. The in-progress guard prevents the repeated onEnd/click path
        // observed on .15 from queueing the same episode twice.
        episodeLoadGeneration += 1
    }

    fun completePlaybackWithRecommendation(item: MediaItem) {
        if (completionExitTriggered) return
        completionExitTriggered = true
        if (traktScrobbledStart) {
            traktScrobbledStart = false
            scope.launch {
                repository.traktScrobble(
                    action = "stop",
                    mediaType = request.mediaType,
                    mediaId = request.mediaId,
                    title = detail?.title ?: request.title,
                    year = detail?.year,
                    progress = traktProgressPercent(),
                )
            }
        }
        scope.launch {
            markWatchedAndClearProgressIfNeeded()
            onPlayRecommendation(item)
        }
    }

    fun completePlaybackAndExit() {
        if (completionExitTriggered) return
        completionExitTriggered = true
        if (traktScrobbledStart) {
            traktScrobbledStart = false
            scope.launch {
                repository.traktScrobble(
                    action = "stop",
                    mediaType = request.mediaType,
                    mediaId = request.mediaId,
                    title = detail?.title ?: request.title,
                    year = detail?.year,
                    progress = traktProgressPercent(),
                )
            }
        }
        scope.launch {
            markWatchedAndClearProgressIfNeeded()
            TvDebugLogger.i("Player", "completion exit to streams mediaType=${request.mediaType} mediaId=${request.mediaId}")
            completePlaybackExit()
        }
    }

    fun queueTraktStop() {
        if (!traktScrobbledStart) return
        traktScrobbledStart = false
        scope.launch {
            repository.traktScrobble(
                action = "stop",
                mediaType = request.mediaType,
                mediaId = request.mediaId,
                title = detail?.title ?: request.title,
                year = detail?.year,
                progress = traktProgressPercent(),
            )
        }
    }

    suspend fun loadPlayback(forceRefresh: Boolean = false, resetReconnectBudget: Boolean = true) {
        liveReconnectJob?.cancel()
        liveReconnectJob = null
        if (resetReconnectBudget) liveReconnectAttempt = 0
        pendingSeekJob?.cancel()
        pendingSeekJob = null
        seekTargetSec = null
        pendingResumePositionSec = null
        pendingResumeContentKey = null
        loading = true
        controlsVisible = false
        continueSourceChoiceRequired = false
        pauseInfoVisible = false
        watchlistPromptVisible = false
        watchlistPromptShown = false
        completionThresholdReached = false
        watchedMarked = false
        completionExitTriggered = false
        nextEpisodeDialogVisible = false
        nextEpisodeCandidate = null
        nextEpisodeLoading = false
        nextEpisodeCountdown = null
        queuedNextEpisode = false
        recommendationDialogVisible = false
        recommendationDismissed = false
        queuedRecommendation = null
        handledSegmentTypes = emptySet()
        segments = emptyList()
        val loadStartedAt = android.os.SystemClock.elapsedRealtime()
        val activeRequest = playbackRequest
        val loadResult = runCatching {
            val queuedEpisodeSelection = pendingEpisodeSelection?.takeIf { selection ->
                selection.episode.seasonNumber == currentEpisode?.seasonNumber &&
                    selection.episode.episodeNumber == currentEpisode?.episodeNumber
            }
            if (queuedEpisodeSelection != null) pendingEpisodeSelection = null
            val perf = com.streamdek.tv.nativeapp.data.Perf.beginPlayback(
                "${activeRequest.mediaType}:${activeRequest.mediaId}",
            )
            val prepared = preparePlayback(
                resources = playerResources,
                repository = repository,
                request = activeRequest,
                initialEpisode = currentEpisode,
                queuedStream = queuedEpisodeSelection?.stream,
                queuedStreams = queuedEpisodeSelection?.streams.orEmpty(),
                streamKeyOverride = streamKeyOverride,
                streamLabelOverride = streamLabelOverride,
                forceRefresh = forceRefresh,
                isLive = isLive,
                playbackPreferences = playbackPreferences,
                failedStreamKeys = failedStreamKeys,
                perf = perf,
                onInitialSource = { initial ->
                    startedFromRememberedSource = initial.rememberedSource
                    if (initial.rememberedSource) continueSourceNotice = "Resuming your remembered source…"
                    initial.candidate?.source?.let { source ->
                        candidate = initial.candidate
                        currentRequestHeaders = defaultPlaybackHeaders + source.requestHeaders
                        currentSourceUrl = source.url
                        currentLabel = streamLabelOverride ?: source.label
                        perf.mark("urlReady", "fastPath=true")
                        resetPlaybackEngineForNewSource()
                        TvDebugLogger.i(
                            "Player",
                            "source ready addon=${initial.candidate.stream?.addonName} remembered=${initial.rememberedSource} elapsedMs=${android.os.SystemClock.elapsedRealtime() - loadStartedAt}",
                        )
                    }
                },
                onPreflight = { preflight ->
                    detail = preflight.detail
                    inWatchlist = preflight.inWatchlist
                    currentEpisode = preflight.episode
                    pendingResumePositionSec = preflight.resumePositionSec
                    pendingResumeContentKey = preflight.resumeContentKey
                    TvDebugLogger.i(
                        "ContinueWatching",
                        "contentId=${activeRequest.mediaId} contentType=${activeRequest.mediaType} season=${preflight.episode?.seasonNumber} episode=${preflight.episode?.episodeNumber} originDevice=${preflight.continueWatchingItem?.lastPlatform ?: "this-tv"} destinationDevice=tv resumePosition=${preflight.resumePositionSec ?: 0.0}",
                    )
                },
                onResolved = { resolved ->
                    candidate = resolved
                    TvDebugLogger.i(
                        "Player",
                        "playback load complete addon=${resolved.stream?.addonName} elapsedMs=${android.os.SystemClock.elapsedRealtime() - loadStartedAt}",
                    )
                    currentRequestHeaders = defaultPlaybackHeaders + resolved.source?.requestHeaders.orEmpty()
                    if (resolved.source != null && currentSourceUrl != resolved.source.url) {
                        perf.mark("urlReady", "fastPath=false")
                    }
                    currentSourceUrl = resolved.source?.url
                    currentLabel = streamLabelOverride ?: resolved.source?.label ?: "No playable stream found"
                    resetPlaybackEngineForNewSource()
                    positionSec = pendingResumePositionSec ?: 0.0
                },
                onFallbackNotice = { sourceFallbackNotice = it },
            )
            continueSourceNotice = prepared.continueSourceNotice
            if (prepared.sourceChoiceError != null) {
                continueSourceChoiceRequired = true
                error = prepared.sourceChoiceError
                loading = false
                controlsVisible = false
                return@runCatching
            }
            durationSec = prepared.preflight.progress?.durationSec ?: 0.0
            nextEpisode = prepared.nextEpisode
            segments = prepared.segments
            watchedMarked = prepared.watched
            failedStreamKeys = prepared.failedStreamKeys
            streamLabelOverride = null
            prepared.resolved?.let { resolved ->
                if (resolved.source != null && candidate?.source?.url != resolved.source.url) {
                    candidate = resolved
                    currentRequestHeaders = defaultPlaybackHeaders + resolved.source.requestHeaders
                    currentSourceUrl = resolved.source.url
                    currentLabel = resolved.source.label
                    resetPlaybackEngineForNewSource()
                } else {
                    candidate = resolved
                }
            }
            sourceFallbackNotice = null
            if (candidate?.source == null) {
                error = repository.lastUsenetFailureMessage ?: playerResources.getString(
                    when {
                        prepared.initialSource.viewerChoseSource -> R.string.player_source_failed_no_alternative
                        prepared.autoContinueResume -> R.string.player_no_saved_source_resumed
                        else -> R.string.player_no_playable_stream_resolved
                    },
                )
                loading = false
                controlsVisible = true
            } else error = null
        }
        loadResult.onFailure { throwable ->
            // Changing from an unresolved series request to its exact episode restarts the keyed
            // effect. Compose cancels the old load with LeftCompositionCancellationException;
            // cancellation is control flow, not a playback failure the viewer can retry.
            if (throwable is kotlinx.coroutines.CancellationException && throwable !is TimeoutCancellationException) throw throwable
            TvDebugLogger.e("Player", "loadPlayback failed mediaType=${activeRequest.mediaType} mediaId=${activeRequest.mediaId}", throwable)
            candidate = null
            currentSourceUrl = null
            currentLabel = streamLabelOverride ?: playerResources.getString(R.string.player_no_playable_stream_found)
            resetPlaybackEngineForNewSource()
            error = when (throwable) {
                is java.net.SocketTimeoutException, is TimeoutCancellationException -> playerResources.getString(
                    when {
                        // Recomputed here: the load body's locals are out of scope, and the wording
                        // has to match what the viewer actually did rather than how they arrived.
                        activeRequest.selectedStreamKey != null || streamKeyOverride != null ->
                            R.string.player_source_taking_too_long
                        activeRequest.fromContinueWatching -> R.string.player_remembered_taking_too_long
                        else -> R.string.player_stream_lookup_timed_out
                    },
                )
                else -> throwable.message ?: playerResources.getString(R.string.player_could_not_prepare)
            }
            loading = false
            controlsVisible = true
        }
        nextEpisodeTransitionInProgress = false
    }

    @Composable
    fun PlaybackLoadEffects() {
    LaunchedEffect(playbackRequest.mediaId, playbackRequest.mediaType, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber, episodeLoadGeneration, liveRefetchGeneration) {
        val reconnectRefetch = isLive && liveRefetchGeneration > 0
        loadPlayback(forceRefresh = reconnectRefetch, resetReconnectBudget = !reconnectRefetch)
    }

LaunchedEffect(isLive, playbackRequest.sourceAddonId, playbackRequest.sourceCatalogId) {
        if (!isLive || playbackRequest.sourceAddonId.isNullOrBlank()) {
            liveChannels = emptyList()
            liveChannelsLoading = false
            return@LaunchedEffect
        }
        liveChannelsLoading = true
        liveChannels = runCatching {
            repository.fetchRelatedLiveChannels(
                addonId = playbackRequest.sourceAddonId,
                catalogId = playbackRequest.sourceCatalogId,
            )
        }.onFailure {
            TvDebugLogger.w("Player", "live channel row unavailable addon=${playbackRequest.sourceAddonId}: ${it.message}")
        }.getOrDefault(emptyList())
        liveChannelsLoading = false
        TvDebugLogger.i("Player", "live channel row loaded addon=${playbackRequest.sourceAddonId} count=${liveChannels.size}")
    }

    LaunchedEffect(isLive, currentSourceUrl, loading, error) {
        if (!isLive || currentSourceUrl.isNullOrBlank() || loading || error != null) return@LaunchedEffect
        lastLiveProgressAtMs = System.currentTimeMillis()
        while (isLive && !loading && error == null && !currentSourceUrl.isNullOrBlank()) {
            delay(5_000L)
            if (System.currentTimeMillis() - lastLiveProgressAtMs >= LiveStallTimeoutMs) {
                scheduleLiveReconnect(playerResources.getString(R.string.player_live_feed_stalled))
                break
            }
        }
    }
    // Drive MPV state from LaunchedEffect so JNI calls only happen when values change,
    // not on every recomposition triggered by overlay animations.
    LaunchedEffect(currentSourceUrl, currentRequestHeaders, activePlaybackEngine, externalSubtitlesPreparedForSource) {
        audioPreferenceAppliedForSource = null
        subtitlePreferenceAppliedForSource = null
        seekTargetSec = null
        val source = currentSourceUrl
        // Media3 cannot add a sidecar to an active MediaItem. Doing so releases ExoPlayer and
        // starts a second prepare/first-frame sequence, so its automatically selected subtitle is
        // downloaded and validated before the one initial prepare. MPV can still attach live.
        if (
            activePlaybackEngine == ActivePlaybackEngine.Media3 &&
            !isLive &&
            !source.isNullOrBlank() &&
            externalSubtitlesPreparedForSource != source
        ) return@LaunchedEffect
        playerView?.setHeaders(currentRequestHeaders)
        if (activePlaybackEngine == ActivePlaybackEngine.Media3) {
            playerView?.setExternalSubtitleTracks(
                externalSubtitles.filter { it.id == selectedExternalSubtitleId },
            )
        }
        if (!source.isNullOrBlank()) playerView?.setSource(source)
        // Re-asserted per source: both engines reset caption styling when they reconfigure their
        // subtitle chain, so a size chosen on the last episode would otherwise be lost on this one.
        playerView?.setSubtitleFontSize(subtitleFontSize)
        playerView?.setSubtitlePosition(subtitlePosition)
        playerView?.setSubtitleDelay(subtitleDelay)
        // Resume is applied by onLoad after the engine reports this media ready. A timed seek here
        // could outlive an episode switch and land the previous episode's timestamp on the next.
    }

    // The engines are polled rather than made to push, and only while the panel that reads them is
    // open — a transfer rate is a moving number nothing else on screen depends on, so sampling it
    // for the whole of a two-hour film to answer a question nobody asked is waste.
    LaunchedEffect(panel, playerView) {
        if (panel != OverlayPanel.Info) {
            playbackStats = null
            return@LaunchedEffect
        }
        while (true) {
            playbackStats = playerView?.playbackStats()
            delay(1_000)
        }
    }


    LaunchedEffect(currentSourceUrl, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber, detail?.imdbId) {
        externalSubtitles = emptyList()
        externalSubtitlesPreparedForSource = null
        selectedExternalSubtitleId = null
        externalSubtitleAppliedKey = null
        subtitlesLoading = false
        val source = currentSourceUrl
        if (isLive || source.isNullOrBlank()) {
            externalSubtitlesPreparedForSource = source
            return@LaunchedEffect
        }
        subtitlesLoading = true
        val results = runCatching {
            repository.fetchExternalSubtitles(
                request.copy(
                    imdbId = request.imdbId ?: detail?.imdbId,
                    episode = currentEpisode,
                ),
            )
        }.getOrDefault(emptyList())
        if (currentSourceUrl != source) return@LaunchedEffect
        val allowedLanguages = listOf(
            playbackPreferences.defaultSubtitleLanguage,
            playbackPreferences.secondarySubtitleLanguage,
        ).map(Languages::normalize).filter { it.isNotBlank() && it != Languages.NONE }.toSet()
        externalSubtitles = if (playbackPreferences.showOnlyPreferredSubtitleLanguages) {
            results.filter { Languages.normalize(it.language) in allowedLanguages }
        } else results
        if (playbackPreferences.autoLoadSubtitles && selectedSubtitleId < 0 && selectedExternalSubtitleId == null) {
            val preferredLanguage = playbackPreferences.defaultSubtitleLanguage
            val preferred = externalSubtitles.firstOrNull { Languages.matches(it.language, preferredLanguage) }
                ?: externalSubtitles.firstOrNull { Languages.matches(it.language, playbackPreferences.secondarySubtitleLanguage) }
            if (preferred != null) {
                if (activePlaybackEngine == ActivePlaybackEngine.Media3) {
                    // A local, validated sidecar keeps the robust subtitle fallback without
                    // rebuilding ExoPlayer after video has already reached READY.
                    val candidates = listOf(preferred) + externalSubtitles.filter {
                        it.id != preferred.id && Languages.matches(it.language, preferred.language)
                    }
                    val prepared = candidates.take(SUBTITLE_ATTEMPT_LIMIT).firstNotNullOfOrNull { candidate ->
                        repository.downloadSubtitleToCache(candidate.url, context.cacheDir)?.let { localPath ->
                            preferred.copy(url = localPath)
                        }
                    }
                    if (prepared != null) {
                        externalSubtitles = externalSubtitles.map { track ->
                            if (track.id == preferred.id) prepared else track
                        }
                        selectedExternalSubtitleId = preferred.id
                    }
                } else {
                    selectedExternalSubtitleId = preferred.id
                }
            }
        }
        externalSubtitlesPreparedForSource = source
        subtitlesLoading = false
    }

    }
    PlaybackLoadEffects()

    fun switchPlaybackEngine(target: ActivePlaybackEngine) {
        panel = null
        panelClosedAtMs = System.currentTimeMillis()
        if (target == activePlaybackEngine) {
            showControls(focusPlay = !isLive)
            return
        }
        pendingEngineResumePositionSec = positionSec.takeIf { it > 0.0 }
        loading = true
        error = null
        controlsVisible = false
        audioTracks = emptyList()
        subtitleTracks = emptyList()
        selectedAudioId = -1
        selectedSubtitleId = -1
        externalSubtitleAppliedKey = null
        TvDebugLogger.i("Player", "Manual engine switch ${activePlaybackEngine.name} -> ${target.name} at ${positionSec}s")
        activePlaybackEngine = target
    }

    // Changing the engine in Settings while something is playing still takes hold, which the
    // preference key used to do. A no-op on first composition: it writes the values the state was
    // just built with.

    @Composable
    fun EngineAndStallEffects() {
    LaunchedEffect(playbackPreferences.playerEngine) { resetPlaybackEngineForNewSource() }

    LaunchedEffect(activePlaybackEngine, loading, selectedExternalSubtitleId, currentSourceUrl, subtitleTracks) {
        val selected = externalSubtitles.firstOrNull { it.id == selectedExternalSubtitleId } ?: return@LaunchedEffect
        val source = currentSourceUrl ?: return@LaunchedEffect
        val key = "${activePlaybackEngine.name}:$source:${selected.id}"
        if (externalSubtitleAppliedKey == key) return@LaunchedEffect
        if (
            activePlaybackEngine == ActivePlaybackEngine.Media3 &&
            playerView?.selectExternalSubtitleTrack(selected.id) == true
        ) {
            selectedSubtitleId = -1
            externalSubtitleAppliedKey = key
            TvDebugLogger.i("Subtitle", "source=${selected.id.substringBefore(':')} language=${selected.language} load=success trackAttached=true")
            return@LaunchedEffect
        }
        if (loading) return@LaunchedEffect
        val localPath = repository.downloadSubtitleToCache(selected.url, context.cacheDir) ?: return@LaunchedEffect
        if (currentSourceUrl == source && selectedExternalSubtitleId == selected.id) {
            playerView?.addSubtitleFile(localPath)
            externalSubtitleAppliedKey = key
            TvDebugLogger.i("Subtitle", "source=${selected.id.substringBefore(':')} language=${selected.language} format=${localPath.substringAfterLast('.')} load=success trackAttached=true")
        }
    }
    LaunchedEffect(speed) {

        playerView?.setSpeed(speed)
    }

    LaunchedEffect(paused, panel, loading, error, watchlistPromptVisible, nextEpisodeDialogVisible) {
        playerView?.setPaused(paused)
        if (watchlistPromptVisible || nextEpisodeDialogVisible) {
            pauseInfoVisible = false
        } else if (paused && panel == null && !loading && error == null) {
            pauseInfoVisible = false
            delay(2500)
            if (paused && panel == null && !loading && error == null && !watchlistPromptVisible && !nextEpisodeDialogVisible) {
                controlsVisible = false
                pauseInfoVisible = true
            }
        } else {
            pauseInfoVisible = false
        }
    }

    LaunchedEffect(currentSourceUrl, paused, completionThresholdReached) {
        val activeSource = currentSourceUrl
        if (activeSource.isNullOrBlank() || paused || completionThresholdReached) return@LaunchedEffect
        while (currentSourceUrl == activeSource && !paused && !completionThresholdReached) {
            // Every thirty seconds rather than fifteen. The position only has to be right when
            // playback stops, and pausing, leaving and finishing each write on their own, so the
            // tighter cadence was twice the traffic to the account for a number nobody reads in
            // between.
            delay(PROGRESS_CHECKPOINT_INTERVAL_MS)
            if (currentSourceUrl == activeSource && !paused && !completionThresholdReached) {
                syncProgressIfEligible()
            }
        }
    }

    // A resolved URL and a player that never reaches READY are a preparation/startup stall, not a
    // source-discovery timeout. Bound this stage independently so a broken range implementation or
    // a server that sends no media bytes cannot leave a large file spinning forever.
    LaunchedEffect(currentSourceUrl, loading, error) {
        val source = currentSourceUrl
        if (isLive || source.isNullOrBlank() || !loading || error != null) return@LaunchedEffect
        // A stored URL gets a much shorter rope. It is a guess — a good one, but a guess — and an
        // expired link often hangs rather than erroring, so waiting the full window would turn the
        // fast path into the slowest one there is. There is a proper resolve to fall back to, and
        // no reason to make anyone watch a spinner while deciding to use it.
        val startedRemembered = startedFromRememberedSource
        delay(if (startedRemembered) 9_000L else 30_000L)
        if (currentSourceUrl != source || !loading || error != null) return@LaunchedEffect
        if (startedRemembered) {
            TvDebugLogger.w("Player", "remembered source did not start; resolving from scratch")
            startedFromRememberedSource = false
            repository.forgetRememberedStream(request.mediaType, request.mediaId, currentEpisode)
            candidate = null
            currentSourceUrl = null
            continueSourceNotice = playerResources.getString(R.string.player_source_expired_finding)
            scope.launch { loadPlayback(forceRefresh = true) }
            return@LaunchedEffect
        }
        TvDebugLogger.w("Player", "startup stalled before first frame source=${source.substringBefore('?')}")
        loading = false
        error = playerResources.getString(R.string.player_source_not_delivering)
        controlsVisible = true
    }

    // Once playback has started, only sustained buffering with no position progress is a stall.
    // Ordinary short rebuffers remain untouched, including high-bitrate 4K streams filling their
    // forward buffer.
    LaunchedEffect(media3Buffering, currentSourceUrl, loading, error) {
        val source = currentSourceUrl
        if (isLive || !media3Buffering || source.isNullOrBlank() || loading || error != null) return@LaunchedEffect
        val positionAtStall = positionSec
        delay(30_000L)
        if (media3Buffering && currentSourceUrl == source && !loading && error == null && kotlin.math.abs(positionSec - positionAtStall) < 1.0) {
            TvDebugLogger.w("Player", "playback buffering stalled source=${source.substringBefore('?')} position=$positionSec")
            paused = true
            error = playerResources.getString(R.string.player_playback_stalled)
            controlsVisible = true
        }
    }

    // Several distinct rebuffers in a short window are a quality problem even when none lasts the
    // full hard-stall timeout. Offer the next already-ranked source once, excluding the current
    // source and anything that has failed in this episode.
    LaunchedEffect(media3Buffering) {
        if (isLive || !media3Buffering || loading || error != null || panel != null || nextEpisodeDialogVisible || watchlistPromptVisible) return@LaunchedEffect
        val now = android.os.SystemClock.elapsedRealtime()
        recentPlaybackStalls = (recentPlaybackStalls + now).filter { now - it <= 120_000L }
        if (recentPlaybackStalls.size < 3 || now < smartSwitchCooldownUntil || smartSwitchCandidate != null) return@LaunchedEffect
        val currentKey = candidate?.stream?.let(repository::streamSelectionKey)
        currentKey?.let { failedStreamKeys = failedStreamKeys + it }
        smartSwitchCandidate = candidate?.streams.orEmpty().firstOrNull { stream ->
            val key = repository.streamSelectionKey(stream)
            key != currentKey && key !in failedStreamKeys
        }
        if (smartSwitchCandidate != null) {
            controlsHideJob?.cancel()
            controlsHideJob = null
            controlsVisible = false
            delay(80)
            runCatching { smartSwitchRequester.requestFocus() }
        }
    }

    }
    EngineAndStallEffects()

    @Composable
    fun ProgressAndDialogEffects() {
    LaunchedEffect(currentSourceUrl, paused, loading) {
        if (isLive) return@LaunchedEffect
        val activeSource = currentSourceUrl
        if (activeSource.isNullOrBlank() || paused || loading) return@LaunchedEffect

        if (!traktScrobbledStart) {
            traktScrobbledStart = repository.traktScrobble(
                action = "start",
                mediaType = request.mediaType,
                mediaId = request.mediaId,
                title = detail?.title ?: request.title,
                year = detail?.year,
                progress = 0.0,
            )
        }

        while (currentSourceUrl == activeSource && !paused && !loading) {
            delay(60000)
            if (currentSourceUrl == activeSource && !paused && !loading) {
                repository.traktScrobble(
                    action = "pause",
                    mediaType = request.mediaType,
                    mediaId = request.mediaId,
                    title = detail?.title ?: request.title,
                    year = detail?.year,
                    progress = traktProgressPercent(),
                )
            }
        }
    }

    LaunchedEffect(positionSec, durationSec, loading, error, watchlistPromptVisible) {
        if (isLive || loading || error != null || watchlistPromptVisible) return@LaunchedEffect
        if (completionThresholdReached || durationSec < 60.0 || positionSec < 120.0) return@LaunchedEffect
        val thresholdReached = traktProgressPercent() >= 95.0 ||
            (durationSec - positionSec) <= 60.0
        if (!thresholdReached) return@LaunchedEffect
        completionThresholdReached = true
        TvDebugLogger.i(
            "Player",
            "completion threshold reached mediaType=${request.mediaType} mediaId=${request.mediaId} progress=${traktProgressPercent()} remaining=${durationSec - positionSec} inWatchlist=$inWatchlist",
        )
        scope.launch {
            markWatchedAndClearProgressIfNeeded()
        }
        if (inWatchlist && nextEpisode == null && !watchlistPromptShown) {
            watchlistPromptShown = true
            paused = true
            controlsVisible = false
            watchlistPromptVisible = true
        }
    }

    LaunchedEffect(positionSec, currentEpisode?.seasonNumber, currentEpisode?.episodeNumber, nextEpisode, playbackPreferences.isAutoPlayNextEpisodeEnabled()) {
        val action = activeSegmentAction()
        if (!playbackPreferences.isAutoPlayNextEpisodeEnabled() || action?.kind != SegmentActionKind.NextEpisode || nextEpisodeDialogVisible) {
            return@LaunchedEffect
        }
        delay(1200)
        val refreshedAction = activeSegmentAction()
        if (playbackPreferences.isAutoPlayNextEpisodeEnabled() && refreshedAction?.kind == SegmentActionKind.NextEpisode && !nextEpisodeDialogVisible) {
            openNextEpisodeDialog()
        }
    }

    LaunchedEffect(
        positionSec,
        durationSec,
        segments,
        nextEpisode,
        detail?.similarTitles,
        playbackPreferences.endOfPlaybackRecommendationsEnabled,
        playbackPreferences.recommendationTiming,
    ) {
        if (!playbackPreferences.endOfPlaybackRecommendationsEnabled || isLive || loading || error != null ||
            recommendationDismissed || nextEpisodeDialogVisible || recommendationDialogVisible ||
            queuedNextEpisode || queuedRecommendation != null
        ) return@LaunchedEffect
        val outroStart = segments.firstOrNull { it.segmentType == "outro" }?.startSec
        val estimate = com.streamdek.tv.nativeapp.data.AdaptiveEndOfPlaybackTrigger.estimate(
            durationSec = durationSec,
            timing = com.streamdek.tv.nativeapp.data.RecommendationTiming.fromKey(playbackPreferences.recommendationTiming),
            structuralOutroStartSec = outroStart,
        )
        if (!com.streamdek.tv.nativeapp.data.AdaptiveEndOfPlaybackTrigger.isReached(positionSec, estimate)) return@LaunchedEffect
        delay(450)
        if (nextEpisode != null) {
            openNextEpisodeDialog()
        } else if (detail?.similarTitles?.any { it.id != request.mediaId } == true) {
            controlsVisible = false
            recommendationDialogVisible = true
        }
    }

    LaunchedEffect(
        positionSec,
        loading,
        error,
        playbackPreferences.autoSkipIntroEnabled,
        playbackPreferences.autoSkipRecapEnabled,
        playbackPreferences.autoSkipEndingEnabled,
    ) {
        if (isLive || loading || error != null || nextEpisodeDialogVisible || watchlistPromptVisible) return@LaunchedEffect
        val segment = segments
            .filter {
                playbackPreferences.isAutoSkipEnabled(it.segmentType) &&
                    it.segmentType !in handledSegmentTypes &&
                    positionSec >= it.startSec && positionSec < it.endSec
            }
            .minWithOrNull(compareBy<PlaybackSegment>({ segmentPriority(it.segmentType) }, { it.startSec }))
            ?: return@LaunchedEffect
        // The next-episode dialog owns ending transitions, completion writes and source selection.
        if (segment.segmentType == "outro" && nextEpisode != null && playbackPreferences.isAutoPlayNextEpisodeEnabled()) return@LaunchedEffect
        markSegmentHandled(segment.segmentType)
        scheduleSeek(maxOf(segment.endSec, positionSec))
        autoSkipNotice = playerResources.getString(
            when (segment.segmentType) {
                "recap" -> R.string.player_recap_skipped
                "outro" -> R.string.player_ending_skipped
                else -> R.string.player_intro_skipped
            },
        )
    }

    LaunchedEffect(autoSkipNotice) {
        if (autoSkipNotice == null) return@LaunchedEffect
        delay(2_200)
        autoSkipNotice = null
    }

    LaunchedEffect(subtitleErrorMessage) {
        if (subtitleErrorMessage == null) return@LaunchedEffect
        delay(3_000)
        subtitleErrorMessage = null
    }

    LaunchedEffect(paused, loading, playerActivityVersion, pausedIdleTimeoutMillis) {
        val timeout = pausedIdleTimeoutMillis ?: return@LaunchedEffect
        if (!paused || loading || pausedSleepTriggered) return@LaunchedEffect
        delay(timeout)
        if (!paused || loading || pausedSleepTriggered) return@LaunchedEffect
        pausedSleepTriggered = true
        queueTraktStop()
        syncProgressIfEligible()
        onExitToDetail()
        // Back to the title page and then the screensaver — not out of the app. Handing the
        // foreground back, which is what this used to do, was a blunt stand-in for sleep: it took
        // the viewer out of StreamDek entirely, so coming back from a paused film meant relaunching
        // rather than pressing play. Leaving the player already releases the keep-screen-on flag,
        // so the set's own idle timer will start the daydream on its own schedule; this only asks
        // for it now. Where the platform refuses — which is every retail set, the permission being
        // a signature one — the viewer is simply left on the title page, which is where they were
        // going anyway.
        TvPowerActions.startScreensaver()
    }

    LaunchedEffect(nextEpisodeDialogVisible, nextEpisodeCountdown) {
        val countdown = nextEpisodeCountdown
        if (!nextEpisodeDialogVisible || countdown == null || countdown <= 0) return@LaunchedEffect
        delay(1000)
        if (countdown == 1) {
            beginNextEpisode()
        } else {
            nextEpisodeCountdown = countdown - 1
        }
    }

    LaunchedEffect(panel) {
        if (panel == null) return@LaunchedEffect
        delay(80)
        // Info is read, not chosen from, so it has no first row to land on — and an empty source
        // list has no row either. Both focus Close, which is the only thing the remote can do there
        // and the way back out.
        val hasFirstRow = when (panel) {
            OverlayPanel.Info -> false
            OverlayPanel.Streams -> candidate?.streams?.isNotEmpty() == true
            else -> true
        }
        val target = if (hasFirstRow) panelFirstItemRequester else panelCloseRequester
        runCatching { target.requestFocus() }
            .onFailure { TvDebugLogger.w("Player", "panel focus request skipped: ${it.message}") }
    }

    LaunchedEffect(error) {
        if (error != null) {
            delay(80)
            try { errorBackRequester.requestFocus() } catch (_: Exception) { }
        }
    }

    LaunchedEffect(watchlistPromptVisible) {
        if (watchlistPromptVisible) {
            delay(80)
            runCatching { watchlistPromptRemoveRequester.requestFocus() }
                .onFailure { TvDebugLogger.w("Player", "watchlist prompt focus skipped: ${it.message}") }
        }
    }

    LaunchedEffect(isLive, loading, error) {
        if (!isLive || loading || error != null) {
            liveHintsVisible = false
            return@LaunchedEffect
        }
        while (true) {
            liveHintsVisible = true
            delay(LiveHintVisibleMs)
            liveHintsVisible = false
            delay((LiveHintCycleMs - LiveHintVisibleMs).coerceAtLeast(0L))
        }
    }
    }
    ProgressAndDialogEffects()

    @Composable
    fun RemoteLifecycleEffects() {
    DisposableEffect(
        isLive,
        liveChannelRowVisible,
        liveFavouritesDrawerVisible,
        liveChannels.size,
        liveAddonFavourites.size,
        loading,
        error,
        controlsVisible,
        panel,
        playbackRequest.mediaId,
    ) {
        TvRemoteKeyRouter.onKeyUp = { keyCode ->
            when (keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (isLive && !controlsVisible && panel == null && !liveChannelRowVisible && !liveFavouritesDrawerVisible) {
                        showLiveChannelRow()
                        true
                    } else {
                        false
                    }
                }
                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isLive && !controlsVisible && panel == null && !liveChannelRowVisible && !liveFavouritesDrawerVisible && liveAddonFavourites.isNotEmpty()) {
                        showLiveFavouritesDrawer()
                        true
                    } else {
                        false
                    }
                }
                AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                AndroidKeyEvent.KEYCODE_ENTER -> {
                    if (isLive && !controlsVisible && panel == null && !liveChannelRowVisible && !liveFavouritesDrawerVisible) {
                        showControls(focusPlay = true)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
        onDispose { TvRemoteKeyRouter.onKeyUp = null }
    }
    DisposableEffect(request.mediaId, request.mediaType) {
        onDispose {
            controlsHideJob?.cancel()
            liveChannelInfoHideJob?.cancel()
            liveReconnectJob?.cancel()
            pendingSeekJob?.cancel()
            queueTraktStop()
            scope.launch {
                syncProgressIfEligible()
            }
        }
    }
    }
    RemoteLifecycleEffects()

    // The prompt currently owed a decision — skip intro, skip recap, skip ending, or next episode.
    // Computed here, before the handlers that have to respect it, so there is exactly one answer
    // per composition rather than each call site asking again.
    val segmentAction = if (!isLive && !loading && error == null && !nextEpisodeDialogVisible && !watchlistPromptVisible) {
        activeSegmentAction()
    } else {
        null
    }

    @Composable
    fun PromptAndBackEffects() {
    LaunchedEffect(segmentAction?.kind, segmentAction?.segmentType) {
        segmentPromptActive = segmentAction != null
        if (segmentAction == null) return@LaunchedEffect
        // Anything already open is taken down: the prompt is the only thing to answer.
        panel = null
        hideControlsNow()
        delay(60)
        runCatching { segmentChipRequester.requestFocus() }
            .onFailure { TvDebugLogger.w("Player", "skip chip focus request skipped: ${it.message}") }
    }

    LaunchedEffect(nextEpisodeDialogVisible, nextEpisodeLoading, nextEpisodeCandidate?.streams?.size) {
        if (!nextEpisodeDialogVisible) return@LaunchedEffect
        delay(80)
        // Play Now is disabled until a stream lands, and a disabled button cannot take focus —
        // Cancel holds it in the meantime so the dialog is never focus-less.
        val requester = if (nextEpisodeCandidate?.streams.isNullOrEmpty()) {
            nextEpisodeCancelRequester
        } else {
            nextEpisodePlayRequester
        }
        runCatching { requester.requestFocus() }
            .onFailure { TvDebugLogger.w("Player", "next episode focus request skipped: ${it.message}") }
    }

    BackHandler {
        if (segmentPromptActive) {
            // Dismissing is a decision too: the segment is marked handled so the prompt does not
            // come straight back on the next progress tick.
            segmentAction?.let { markSegmentHandled(it.segmentType) }
            segmentPromptActive = false
            scope.launch { runCatching { playerRootRequester.requestFocus() } }
        } else if (smartSwitchCandidate != null) {
            smartSwitchCandidate = null
            recentPlaybackStalls = emptyList()
            smartSwitchCooldownUntil = android.os.SystemClock.elapsedRealtime() + 180_000L
            showControls(focusPlay = !isLive)
        } else if (liveFavouritesDrawerVisible) {
            liveFavouritesDrawerVisible = false
            scope.launch { runCatching { playerRootRequester.requestFocus() } }
        } else if (liveChannelRowVisible) {
            liveChannelRowVisible = false
            scope.launch { runCatching { playerRootRequester.requestFocus() } }
        } else if (recommendationDialogVisible) {
            val restorePlayerFocus = recommendationHasFocus
            recommendationDialogVisible = false
            recommendationDismissed = true
            queuedRecommendation = null
            if (restorePlayerFocus) showControls(focusPlay = true) else scheduleControlsHide()
        } else if (nextEpisodeDialogVisible) {
            nextEpisodeDialogVisible = false
            nextEpisodeCountdown = null
            queuedNextEpisode = false
            recommendationDismissed = true
            scheduleControlsHide()
        } else if (watchlistPromptVisible) {
            watchlistPromptVisible = false
            paused = false
            scheduleControlsHide()
        } else if (panel != null) {
            val closedPanel = panel!!
            panel = null
            restoreControlsAfterPanel(closedPanel)
        } else if (controlsVisible) {
            hideControlsNow()
            scope.launch { runCatching { playerRootRequester.requestFocus() } }
        } else if (isLive && liveChannelHistory.isNotEmpty()) {
            val previousRequest = liveChannelHistory.last()
            liveChannelHistory = liveChannelHistory.dropLast(1)
            TvDebugLogger.i("Player", "back to previous live channel from=${playbackRequest.mediaId} to=${previousRequest.mediaId}")
            repository.savePlaybackRequest(previousRequest)
            loading = true
            error = null
            currentLabel = "Loading ${previousRequest.title ?: "previous channel"}…"
            liveChannelRowVisible = false
            liveFavouritesDrawerVisible = false
            liveReconnectAttempt = 0
            liveRefetchGeneration = 0
            streamKeyOverride = null
            streamLabelOverride = null
            activeLiveRequest = previousRequest
        } else {
            TvDebugLogger.i("Player", "back exit to streams mediaType=${request.mediaType} mediaId=${request.mediaId}")
            queueTraktStop()
            scope.launch {
                syncProgressIfEligible()
            }
            backExitPlayback()
        }
    }
    }
    PromptAndBackEffects()

    @Composable
    fun RenderPlayerRoot() {
    // Do not keep a frame clock alive throughout a film for decoration that is not on screen.
    // Loading and the two live hints are the only consumers. Reduced motion holds their resting
    // values, while the viewer's speed still governs the decorative cycle when it is enabled.
    val animateAmbient = !LocalTvExperienceSettings.current.reducedMotion &&
        (loading || (isLive && liveHintsVisible))
    val breathing = if (animateAmbient) rememberInfiniteTransition(label = "player-ambient") else null
    val ambientDuration = TvMotion.duration(1600).coerceAtLeast(1)
    val hintDuration = TvMotion.duration(850).coerceAtLeast(1)
    val logoScale = breathing?.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(animation = tween(ambientDuration), repeatMode = RepeatMode.Reverse),
        label = "logo-breathe",
    )?.value ?: 1f
    val logoAlpha = breathing?.animateFloat(
        initialValue = 0.68f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(ambientDuration), repeatMode = RepeatMode.Reverse),
        label = "logo-alpha",
    )?.value ?: 1f
    val liveCaretOffset = breathing?.animateFloat(
        initialValue = 0f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(animation = tween(hintDuration), repeatMode = RepeatMode.Reverse),
        label = "live-channel-caret",
    )?.value ?: 0f

    // The video surface deliberately does not take focus, so the player root holds it
    // whenever no control is focused. Without this, D-pad presses while the controls
    // are hidden would not reach any key handler at all.
    LaunchedEffect(controlsVisible, panel, loading, error, isLive, watchlistPromptVisible, nextEpisodeDialogVisible, segmentPromptActive, smartSwitchCandidate) {
        // Taking the controls down to show a skip prompt would otherwise land focus back here and
        // pull it straight off the chip that was just raised.
        if (interactionLayer == PlayerInteractionLayer.Playback) {
            delay(40)
            runCatching { playerRootRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) playerActivityVersion += 1
                // While a skip prompt is up the D-pad is swallowed whole — both edges, since focus
                // moves on key-down — so the only ways out are OK on the chip and Back. OK and Back
                // pass through untouched to the chip and the back handler.
                if (segmentPromptActive) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> true
                        else -> false
                    }
                }
                if (recommendationDialogVisible && !recommendationHasFocus &&
                    event.key in setOf(Key.DirectionDown, Key.DirectionRight)
                ) {
                    if (event.type == KeyEventType.KeyDown) {
                        runCatching { nextEpisodePlayRequester.requestFocus() }
                    }
                    return@onPreviewKeyEvent true
                }
                // SEEK is an authoritative interaction region, not merely whichever child happens
                // to hold focus this frame. The seek row cancels horizontal focus search in its own
                // right — see PlayerSeekFocusGroup — and this is the region-level backstop above it,
                // covering the frames in which the row is being composed or recomposed and the
                // presses that arrive while the bar is on screen but the row has not been given
                // focus yet. Down is the sole transition into the controls row.
                if (bottomBarOnScreen && controlsFocusRegion == PlayerControlsFocusRegion.Seek &&
                    hasSeekableTimeline &&
                    panel == null && !nextEpisodeDialogVisible && smartSwitchCandidate == null
                ) {
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            if (event.type == KeyEventType.KeyDown) {
                                scheduleRelativeSeek(
                                    if (event.key == Key.DirectionRight) tvSeekStepSeconds(durationSec)
                                    else -tvSeekStepSeconds(durationSec),
                                )
                                scheduleControlsHide()
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionDown -> {
                            if (event.type == KeyEventType.KeyDown) {
                                focusControls()
                                registerInteraction()
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionUp -> return@onPreviewKeyEvent true
                        else -> Unit
                    }
                }
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                if (nextEpisodeDialogVisible) return@onPreviewKeyEvent false
                if (smartSwitchCandidate != null) return@onPreviewKeyEvent false
                if (isLive && !controlsVisible && panel == null && !loading && error == null && !watchlistPromptVisible && !liveFavouritesDrawerVisible) {
                    if (liveChannelRowVisible) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> {
                            showLiveChannelRow()
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Menu -> {
                            showControls(focusPlay = true)
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionRight -> {
                            if (liveAddonFavourites.isNotEmpty()) showLiveFavouritesDrawer() else showLiveChannelInfo()
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionLeft, Key.DirectionUp -> {
                            showLiveChannelInfo()
                            return@onPreviewKeyEvent true
                        }
                        Key.Back -> return@onPreviewKeyEvent false
                        else -> return@onPreviewKeyEvent false
                    }
                }
                if (!loading && interactionLayer == PlayerInteractionLayer.Playback) {
                    when (event.key) {
                        Key.DirectionLeft, Key.DirectionRight -> {
                            controlsFocusRegion = PlayerControlsFocusRegion.Seek
                            controlsVisible = true
                            scheduleRelativeSeek(
                                if (event.key == Key.DirectionRight) tvSeekStepSeconds(durationSec) else -tvSeekStepSeconds(durationSec),
                            )
                            scheduleControlsHide()
                            scope.launch {
                                delay(60)
                                try { progressRequester.requestFocus() } catch (_: Exception) { }
                            }
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
                        Key.DirectionUp, Key.DirectionDown -> {
                            showControls(focusPlay = true)
                            return@onPreviewKeyEvent true
                        }
                        else -> {}
                    }
                }
                false
            }
            // The dispatcher sits outside this focus target in the modifier chain, making it an
            // ancestor of both focus rows instead of a sibling modifier that child focus can skip.
            .focusRequester(playerRootRequester)
            .focusable(),
    ) {
        @Composable
        fun RenderPlayerSurface() {
        key(resolvedRenderSurface, activePlaybackEngine) {
            AndroidView(
            factory = { context ->
                val player = createPlayerView(context, resolvedRenderSurface, activePlaybackEngine)
                TvDebugLogger.i("Player", "player view created engine=${activePlaybackEngine.name} view=${player.javaClass.simpleName}")
                // Dolby Vision profile 7 is the one case Media3 loses silently: it decodes, reports
                // frames, and shows black, so the error-driven fallback below never fires. The
                // stream is recognised the moment its track is selected and handed to mpv, which
                // decodes the HEVC base layer and plays it. See Dv7Hevc.
                (player as? ExoPlaybackView)?.onDolbyVisionProfile7Callback = {
                    if (activePlaybackEngine == ActivePlaybackEngine.Media3 && !autoEngineFallbackUsed) {
                        autoEngineFallbackUsed = true
                        pendingEngineResumePositionSec = positionSec.takeIf { it > 0.0 }
                        loading = true
                        error = null
                        audioTracks = emptyList()
                        subtitleTracks = emptyList()
                        TvDebugLogger.i("Player", "Dolby Vision profile 7 detected; switching to libMPV at ${positionSec}s")
                        activePlaybackEngine = ActivePlaybackEngine.MPV
                    }
                }
                (player as? ExoPlaybackView)?.onStallChangedCallback = { buffering ->
                    media3Buffering = buffering
                }
                val controller = player as MpvPlayerController
                controller.apply {
                    setDecoderMode(playbackPreferences.decoderMode)
                    setHeaders(currentRequestHeaders)
                    onRemoteCenterCallback = {
                        if (segmentPromptActive) {
                            // Left for the skip chip. Reporting it handled here would consume the
                            // press without doing anything, and the prompt would never be taken.
                            false
                        } else if (isLive) {
                            showControls(focusPlay = true)
                            true
                        } else if (!controlsVisible || panel != null) {
                            showControls(focusPlay = true)
                            true
                        } else {
                            false
                        }
                    }
                    onRemoteDownCallback = {
                        if (isLive && !liveFavouritesDrawerVisible) {
                            showLiveChannelRow()
                            true
                        } else {
                            false
                        }
                    }
                    onLoadCallback = { _, _, _ ->
                        TvDebugLogger.i(
                            "Player",
                            "onLoad mediaType=${request.mediaType} mediaId=${request.mediaId} source=${currentSourceUrl ?: "none"} label=$currentLabel resume=${pendingResumePositionSec ?: 0.0}",
                        )
                        // Only the first load of an attempt is the playback start. This callback
                        // also fires for a source fallback and for an engine switch, and counting
                        // those would report several starts for one thing the viewer watched once.
                        if (!playbackOutcomeReported) {
                            playbackOutcomeReported = true
                            Telemetry.playbackStarted(
                                correlationId = attemptCorrelationId,
                                mediaId = request.mediaId,
                                mediaType = request.mediaType,
                                title = request.title,
                                addonKey = candidate?.stream?.addonId?.takeIf { it.isNotBlank() }
                                    ?: candidate?.stream?.addonName,
                                provider = candidate?.stream?.cachedBy?.firstOrNull(),
                                durationMs = System.currentTimeMillis() - attemptStartedAt,
                                sourcesTried = sourcesTried,
                            )
                        }
                        loading = false
                        error = null
                        // The load narration described getting here. It has no meaning once the
                        // picture is up, and leaving it set is what made the next load — a source
                        // the viewer had just chosen by hand — open under the previous journey's
                        // sentence.
                        continueSourceNotice = null
                        sourceFallbackNotice = null
                        liveReconnectJob?.cancel()
                        liveReconnectJob = null
                        liveReconnectAttempt = 0
                        lastWorkingSourceUrl = currentSourceUrl
                        lastWorkingLabel = currentLabel
                        lastWorkingRequestHeaders = currentRequestHeaders
                        // A first frame is the only honest proof that a URL plays, so this is where
                        // it is written down. Remembering at resolve time instead would enshrine
                        // URLs that resolve and then refuse to open — and they would be tried first
                        // on every future resume, failing first every time.
                        if (!isLive) {
                            candidate?.let { played ->
                                repository.rememberPlaybackSource(
                                    mediaType = request.mediaType,
                                    mediaId = request.mediaId,
                                    episode = currentEpisode,
                                    candidate = played,
                                )
                            }
                        }
                        if (isLive) {
                            lastLiveProgressAtMs = System.currentTimeMillis()
                            lastLiveProgressPositionSec = positionSec
                            controlsVisible = false
                            liveChannelInfoVisible = false
                        } else {
                            // The engine reaching READY is not the viewer asking for anything, and
                            // it does not happen once. It fires again on every rebuffer — including
                            // the one a scrub causes — so taking focus here is what carried the
                            // highlight out of the seek row and onto Play in the middle of
                            // scrubbing, after which Right walked along the controls instead of
                            // seeking. This was the focus escape; the seek row was never the thing
                            // letting go.
                            //
                            // Raising the controls is still right: that is playback starting, and
                            // they time out on their own. Focus is only placed when nobody owns it.
                            val nobodyOwnsFocus = !controlsVisible &&
                                controlsFocusRegion != PlayerControlsFocusRegion.Seek
                            showControls(focusPlay = nobodyOwnsFocus)
                        }
                        val loadedContentKey = listOf(
                            request.mediaType,
                            request.mediaId,
                            currentEpisode?.seasonNumber ?: -1,
                            currentEpisode?.episodeNumber ?: -1,
                        ).joinToString(":")
                        (pendingEngineResumePositionSec ?: pendingResumePositionSec)
                            ?.takeIf { it > 0.0 && (pendingEngineResumePositionSec != null || pendingResumeContentKey == loadedContentKey) }
                            ?.let { resumeAt ->
                            pendingEngineResumePositionSec = null
                            pendingResumePositionSec = null
                            pendingResumeContentKey = null
                            seekTo(resumeAt)
                            TvDebugLogger.i("Player", "contentId=${request.mediaId} requestedResumePosition=$resumeAt seekApplied=$resumeAt")
                        }
                    }
                    onProgressCallback = { position, duration ->
                        durationSec = duration
                        if (isLive && kotlin.math.abs(position - lastLiveProgressPositionSec) >= 0.25) {
                            lastLiveProgressPositionSec = position
                            lastLiveProgressAtMs = System.currentTimeMillis()
                        }
                        val pendingTarget = seekTargetSec
                        if (pendingTarget == null) {
                            positionSec = position
                        } else {
                            val settled = kotlin.math.abs(position - pendingTarget) <= 1.5
                            val timedOut = System.currentTimeMillis() - seekIssuedAtMs > 4000L
                            if (settled || timedOut) {
                                // Seek landed (or MPV never confirmed it) — resume
                                // following real playback progress.
                                seekTargetSec = null
                                positionSec = position
                            }
                            // Otherwise keep the UI pinned to the scrub target so the
                            // progress bar does not jump back to the pre-seek position.
                        }
                    }
                    onEndCallback = {
                        TvDebugLogger.i(
                            "Player",
                            "onEnd mediaType=${request.mediaType} mediaId=${request.mediaId} nextEpisode=${nextEpisode != null} position=$positionSec duration=$durationSec source=${currentSourceUrl ?: "none"} inWatchlist=$inWatchlist",
                        )
                        if (isLive) {
                            scheduleLiveReconnect("The feed ended")
                        } else if (queuedNextEpisode && nextEpisode != null) {
                            beginNextEpisode()
                        } else if (queuedRecommendation != null) {
                            completePlaybackWithRecommendation(queuedRecommendation!!)
                        } else if (nextEpisode != null && playbackPreferences.isAutoPlayNextEpisodeEnabled()) {
                            if (nextEpisodeCandidate?.stream != null) beginNextEpisode() else scope.launch { openNextEpisodeDialog() }
                        } else {
                            completePlaybackAndExit()
                        }
                    }
                    fun beginSourceFallback(message: String) {
                        if (sourceFallbackInProgress || isLive) return
                        sourceFallbackInProgress = true
                        scope.launch {
                            candidate?.stream?.let { failed ->
                                failedStreamKeys = failedStreamKeys + repository.streamSelectionKey(failed)
                                sourceFallbackNotice =
                                    "${repository.streamDeliveryLabel(failed)} playback failed. Trying the next source…"
                            }
                            var streams = candidate?.streams.orEmpty()
                            if (streams.none { repository.streamSelectionKey(it) !in failedStreamKeys }) {
                                streams = runCatching {
                                    repository.resolvePlayback(
                                        mediaType = playbackRequest.mediaType,
                                        mediaId = playbackRequest.mediaId,
                                        imdbId = playbackRequest.imdbId ?: detail?.imdbId,
                                        episode = currentEpisode,
                                        preferredStreamKey = null,
                                        forceRefresh = true,
                                        streamType = playbackRequest.streamType,
                                    ).streams
                                }.getOrDefault(streams)
                            }
                            val selected = repository.resolveFirstPlayableSource(
                                request = playbackRequest.copy(episode = currentEpisode),
                                streams = streams,
                                skipKeys = failedStreamKeys,
                                forceRefresh = true,
                                onAttempt = { next ->
                                    sourceFallbackNotice = "Trying ${next.addonName.ifBlank { "the next source" }}…"
                                },
                                onAttemptFailed = { failed, key ->
                                    failedStreamKeys = failedStreamKeys + key
                                    sourceFallbackNotice =
                                        "${repository.streamDeliveryLabel(failed)} source failed. Trying the next source…"
                                },
                            )
                            if (selected?.source != null) {
                                val resumeAt = positionSec.coerceAtLeast(0.0)
                                candidate = selected
                                currentRequestHeaders = defaultPlaybackHeaders + selected.source.requestHeaders
                                currentSourceUrl = selected.source.url
                                currentLabel = selected.source.label
                                pendingEngineResumePositionSec = resumeAt.takeIf { it > 0.0 }
                                sourcesTried += 1
                                activePlaybackEngine = initialPlaybackEngine(playbackPreferences.playerEngine)
                                autoEngineFallbackUsed = false
                                audioTracks = emptyList()
                                subtitleTracks = emptyList()
                                loading = true
                                error = null
                                TvDebugLogger.w("Player", "Source failed; switching to ${selected.source.label} at ${resumeAt}s")
                            } else {
                                // Every ranked source is gone, so this is a failure the viewer
                                // actually saw. Reporting it at the first failed source instead
                                // would count sources the app successfully replaced.
                                if (!playbackOutcomeReported) {
                                    playbackOutcomeReported = true
                                    Telemetry.playbackFailed(
                                        correlationId = attemptCorrelationId,
                                        mediaId = request.mediaId,
                                        mediaType = request.mediaType,
                                        title = request.title,
                                        addonKey = candidate?.stream?.addonId?.takeIf { it.isNotBlank() }
                                            ?: candidate?.stream?.addonName,
                                        provider = candidate?.stream?.cachedBy?.firstOrNull(),
                                        errorCategory = classifyPlaybackFailure(message),
                                        errorCode = null,
                                        durationMs = System.currentTimeMillis() - attemptStartedAt,
                                        sourcesTried = sourcesTried,
                                    )
                                }
                                error = "All available sources failed. ${message.take(160)}"
                                loading = false
                                showControls(focusPlay = true)
                            }
                            sourceFallbackNotice = null
                            sourceFallbackInProgress = false
                        }
                    }
                    if (this is ExoPlaybackView) {
                        onExternalSubtitleErrorCallback = { message -> subtitleErrorMessage = message }
                    }
                    onErrorCallback = { message ->
                        TvDebugLogger.w(
                            "Player",
                            "onError mediaType=${request.mediaType} mediaId=${request.mediaId} source=${currentSourceUrl ?: "none"} label=$currentLabel position=$positionSec duration=$durationSec message=$message",
                        )
                        if (shouldAutoFallbackToMpv(playbackPreferences.playerEngine, activePlaybackEngine, autoEngineFallbackUsed)) {
                            autoEngineFallbackUsed = true
                            pendingEngineResumePositionSec = positionSec.takeIf { it > 0.0 }
                            loading = true
                            error = null
                            audioTracks = emptyList()
                            subtitleTracks = emptyList()
                            TvDebugLogger.w("Player", "Media3 failed; falling back to libMPV at ${positionSec}s: $message")
                            activePlaybackEngine = ActivePlaybackEngine.MPV
                        } else if (isLive) {
                            scheduleLiveReconnect(message)
                        } else {
                            // A stored URL that will not open has gone stale — a debrid link that
                            // expired, a signed CDN URL that lapsed. Drop it before recovering, so
                            // the next resume resolves properly instead of starting here again.
                            if (startedFromRememberedSource) {
                                startedFromRememberedSource = false
                                repository.forgetRememberedStream(
                                    request.mediaType,
                                    request.mediaId,
                                    currentEpisode,
                                )
                                TvDebugLogger.i("Player", "remembered source stale; resolving from scratch")
                            }
                            error = "Source failed. Trying another stream…"
                            loading = true
                            beginSourceFallback(message)
                        }
                    }
                    onTracksChangedCallback = { audio, subtitles, selectedAudioTrackId, selectedSubtitleTrackId ->
                        audioTracks = audio
                        subtitleTracks = subtitles
                        selectedAudioId = selectedAudioTrackId ?: -1
                        selectedSubtitleId = selectedSubtitleTrackId ?: -1
                        val currentSource = currentSourceUrl
                        val currentBootstrap = repository.bootstrap.value
                        val activeProfile = repository.activeStreamProfile(currentBootstrap)
                        if (
                            currentSource != null &&
                            audioPreferenceAppliedForSource != currentSource
                        ) {
                            audioPreferenceAppliedForSource = currentSource
                            preferredAudioTrack(
                                audioTracks = audio,
                                preferredLanguage = activeProfile?.audioLanguage?.takeIf { it.isNotBlank() }
                                    ?: currentBootstrap?.preferences?.playback?.defaultAudioLanguage
                                    ?: "en",
                            )?.let { preferredTrack ->
                                if (selectedAudioTrackId != preferredTrack.id) {
                                    setAudioTrack(preferredTrack.id)
                                }
                            }
                        }
                        if (
                            currentSource != null &&
                            playbackPreferences.autoLoadSubtitles &&
                            subtitleSourceAllowsOrigin(
                                playbackPreferences.subtitleDefaultSource,
                                ExternalSubtitleOrigin.BuiltIn,
                            ) &&
                            selectedExternalSubtitleId == null &&
                            subtitlePreferenceAppliedForSource != currentSource
                        ) {
                            subtitlePreferenceAppliedForSource = currentSource
                            preferredSubtitleTrack(
                                subtitles = subtitles,
                                preferredLanguage = currentBootstrap?.preferences?.playback?.defaultSubtitleLanguage
                                    ?: "en",
                            )?.let { preferredTrack ->
                                if (selectedSubtitleTrackId != preferredTrack.id) {
                                    setSubtitleTrack(preferredTrack.id)
                                }
                            }
                        }
                    }
                    playerView = this
                }
                player
            },
            update = { view: android.view.View ->
                val controller = view as MpvPlayerController
                playerView = controller
                controller.setDecoderMode(playbackPreferences.decoderMode)
                controller.onRemoteCenterCallback = {
                    if (segmentPromptActive) {
                        false
                    } else if (isLive) {
                        showControls(focusPlay = true)
                        true
                    } else if (!controlsVisible || panel != null) {
                        showControls(focusPlay = true)
                        true
                    } else {
                        false
                    }
                }
                controller.onRemoteDownCallback = {
                    if (isLive && !liveFavouritesDrawerVisible) {
                        showLiveChannelRow()
                        true
                    } else {
                        false
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        }
        }
        RenderPlayerSurface()

        @Composable
        fun RenderLoadingAndErrors() {
        // Loading screen — backdrop + breathing logo only, no controls
        if (loading) {
            val loadingBackdrop = detail?.backdrop ?: detail?.poster
            if (!loadingBackdrop.isNullOrBlank()) {
                AsyncImage(
                    model = loadingBackdrop,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xE0000000), Color(0xAA000000), Color(0xF0000000)),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                detail?.titleLogo?.takeIf { it.isNotBlank() }?.let { logo ->
                    AsyncImage(
                        model = logo,
                        contentDescription = detail?.title ?: request.title,
                        modifier = Modifier
                            .width(390.dp)
                            .scale(logoScale)
                            .alpha(logoAlpha),
                        contentScale = ContentScale.Fit,
                    )
                } ?: Text(
                    text = if (isLive) currentChannelTitle else detail?.title ?: request.title ?: "Loading",
                    style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.scale(logoScale).alpha(logoAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp, start = 48.dp, end = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = sourceFallbackNotice ?: continueSourceNotice ?: currentLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (sourceFallbackNotice != null || continueSourceNotice != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.60f),
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Error overlay — shown when playback fails so user always has a clear exit path
        if (watchlistPromptVisible && !loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000)),
                contentAlignment = Alignment.Center,
            ) {
                PlayerGlassSurface(
                    modifier = Modifier.width(560.dp),
                    contentPadding = PaddingValues(24.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        androidx.tv.material3.Text(
                            text = stringResource(R.string.watchlist_still_in),
                            style = androidx.tv.material3.MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Black),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        androidx.tv.material3.Text(
                            text = stringResource(R.string.watchlist_finished_prompt),
                            style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.tv.material3.Button(
                                onClick = {
                                    scope.launch {
                                        removeFromWatchlistIfNeeded()
                                    }
                                    watchlistPromptVisible = false
                                    paused = false
                                    scheduleControlsHide()
                                },
                                modifier = Modifier.focusRequester(watchlistPromptRemoveRequester),
                            ) {
                                androidx.tv.material3.Text(stringResource(R.string.action_remove))
                            }
                            androidx.tv.material3.OutlinedButton(
                                onClick = {
                                    watchlistPromptVisible = false
                                    paused = false
                                    scheduleControlsHide()
                                },
                                modifier = Modifier.focusRequester(watchlistPromptKeepRequester),
                            ) {
                                androidx.tv.material3.Text(stringResource(R.string.action_not_now))
                            }
                        }
                    }
                }
            }
        }

        if (error != null && !loading) {
            val canResume = lastWorkingSourceUrl != null && lastWorkingSourceUrl != currentSourceUrl
            val hasMultipleStreams = (candidate?.streams?.size ?: 0) > 1
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC000000)),
                contentAlignment = Alignment.Center,
            ) {
                PlayerGlassSurface(
                    modifier = Modifier.width(500.dp),
                    contentPadding = PaddingValues(24.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        androidx.tv.material3.Text(
                            text = if (continueSourceChoiceRequired) "Continue watching from another device" else "Playback Error",
                            style = androidx.tv.material3.MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Black),
                            color = if (continueSourceChoiceRequired) MaterialTheme.colorScheme.onSurface else Color(0xFFFFB4AB),
                        )
                        androidx.tv.material3.Text(
                            text = error!!,
                            style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.tv.material3.Button(
                                onClick = {
                                    TvDebugLogger.i("Player", "error overlay go back to streams mediaType=${request.mediaType} mediaId=${request.mediaId}")
                                    scope.launch {
                                        syncProgressIfEligible()
                                    }
                                    if (request.fromContinueWatching) onExitToStreams() else backExitPlayback()
                                },
                                modifier = Modifier.focusRequester(errorBackRequester),
                            ) {
                                androidx.tv.material3.Text(if (request.fromContinueWatching) "Choose a Source" else "Go Back")
                            }
                            if (!continueSourceChoiceRequired) {
                                androidx.tv.material3.OutlinedButton(
                                    onClick = {
                                        TvDebugLogger.i("Player", "error overlay retry with fresh streams mediaType=${request.mediaType} mediaId=${request.mediaId}")
                                        error = null
                                        scope.launch { loadPlayback(forceRefresh = true) }
                                    },
                                ) {
                                    androidx.tv.material3.Text(stringResource(R.string.action_retry))
                                }
                            }
                            if (hasMultipleStreams) {
                                androidx.tv.material3.OutlinedButton(
                                    onClick = {
                                        error = null
                                        panel = OverlayPanel.Streams
                                        controlsVisible = true
                                    },
                                    modifier = Modifier.focusRequester(errorSourcesRequester),
                                ) {
                                    androidx.tv.material3.Text(stringResource(R.string.player_try_another_source))
                                }
                            }
                            if (canResume) {
                                androidx.tv.material3.OutlinedButton(
                                    onClick = {
                                        error = null
                                        loading = true
                                        controlsVisible = false
                                        pendingResumePositionSec = positionSec.takeIf { it > 0.0 }
                                        currentRequestHeaders = lastWorkingRequestHeaders
                                        currentSourceUrl = lastWorkingSourceUrl
                                        currentLabel = lastWorkingLabel ?: "Previous source"
                                        resetPlaybackEngineForNewSource()
                                    },
                                ) {
                                    androidx.tv.material3.Text(
                                        stringResource(
                                            R.string.player_resume_source,
                                            lastWorkingLabel?.take(24) ?: stringResource(R.string.player_last_source),
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        RenderLoadingAndErrors()

        @Composable
        fun RenderLiveChrome() {
        // Playback controls — bottom bar
        if (isLive && !loading && error == null) {
            LiveStatusBadge(
                isVod = isVod,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 26.dp, start = 26.dp),
            )
        }

        if (isLive && liveChannelInfoVisible && !loading && error == null) {
            LiveChannelInfoOverlay(
                channelTitle = currentChannelTitle,
                sourceName = playbackRequest.sourceAddonName ?: currentLabel,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 78.dp, start = 26.dp),
            )
        }

        if (isLive && liveHintsVisible && liveChannels.size > 1 && !liveChannelRowVisible && !loading && error == null) {
            LiveChannelDownHint(
                offsetY = liveCaretOffset,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            )
        }

        if (isLive && liveHintsVisible && liveAddonFavourites.isNotEmpty() && !liveFavouritesDrawerVisible && !loading && error == null) {
            LiveFavouritesRightHint(
                offsetX = liveCaretOffset,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp),
            )
        }

        if (isLive && liveFavouritesDrawerVisible && liveAddonFavourites.isNotEmpty() && !loading && error == null) {
            LiveFavouritesDrawer(
                channels = liveAddonFavourites,
                currentChannelId = playbackRequest.mediaId,
                listState = liveFavouriteListState,
                initialFocusRequester = liveFavouriteFirstRequester,
                onSelect = ::selectLiveChannel,
                onToggleFavourite = repository::toggleFavouriteChannel,
                cardView = liveFavouritesCardView,
                onToggleView = { liveFavouritesCardView = !liveFavouritesCardView },
                onDismiss = {
                    liveFavouritesDrawerVisible = false
                    scope.launch { runCatching { playerRootRequester.requestFocus() } }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
        if (isLive && liveChannelRowVisible && liveChannels.size > 1 && !loading && error == null) {
            val currentChannelIndex = liveChannels.indexOfFirst { it.id == playbackRequest.mediaId }.coerceAtLeast(0)
            LiveChannelCarousel(
                channels = liveChannels,
                currentChannelId = playbackRequest.mediaId,
                listState = liveChannelListState,
                initialFocusIndex = currentChannelIndex,
                initialFocusRequester = liveChannelFirstRequester,
                favouriteKeys = favouriteChannelKeys,
                onSelect = ::selectLiveChannel,
                onToggleFavourite = repository::toggleFavouriteChannel,
                onDismiss = {
                    liveChannelRowVisible = false
                    scope.launch { runCatching { playerRootRequester.requestFocus() } }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        }
        RenderLiveChrome()

        @Composable
        fun RenderControlsAndDialogs() {
        if (!loading && error == null && !nextEpisodeDialogVisible) {
            PlayerOverlayVisibility(
                visible = interactionLayer == PlayerInteractionLayer.Controls || interactionLayer == PlayerInteractionLayer.Seeking,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                PlayerBottomBar(
                    detail = detail,
                    requestTitle = request.title,
                    currentEpisode = currentEpisode,
                    currentLabel = currentLabel,
                    error = error,
                    paused = paused,
                    hasNext = nextEpisode != null,
                    positionSec = positionSec,
                    durationSec = durationSec,
                    selectedPanel = panel,
                    playRequester = playRequester,
                    subtitlesRequester = subtitlesRequester,
                    audioRequester = audioRequester,
                    sourcesRequester = sourcesRequester,
                    engineRequester = engineRequester,
                    nextRequester = nextRequester,
                    watchedRequester = watchedRequester,
                    speedRequester = speedRequester,
                    infoRequester = infoRequester,
                    progressRequester = progressRequester,
                    liveProgressRequester = liveProgressRequester,
                    favouriteRequester = favouriteRequester,
                    isFavourite = currentChannelIsFavourite,
                    onToggleFavourite = ::toggleCurrentChannelFavourite,
                    onInteract = ::registerInteraction,
                    onPlayPause = {
                        // tv-material fires onClick on key-up without requiring the
                        // matching key-down, so the release that confirmed a panel
                        // selection can land on the freshly focused play button and
                        // pause playback. Ignore toggles immediately after a panel closes.
                        if (System.currentTimeMillis() - panelClosedAtMs > 450L) {
                            paused = !paused
                            if (!paused) scheduleControlsHide()
                        }
                    },
                    onNext = {
                        nextEpisode?.let { currentEpisode = it }
                        registerInteraction()
                    },
                    // The seek row's own path, used on the frames the region backstop above does
                    // not cover. Both end up in the same place, and only one of them ever runs for
                    // a given press.
                    onSeekBy = { delta ->
                        controlsFocusRegion = PlayerControlsFocusRegion.Seek
                        scheduleRelativeSeek(delta)
                        scheduleControlsHide()
                    },
                    onMarkWatched = {
                        scope.launch {
                            markWatchedAndClearProgressIfNeeded()
                            onExitToStreams()
                        }
                    },
                    focusRegion = controlsFocusRegion,
                    controlsEntryRequester = controlsEntryRequester,
                    controlsFocusToken = controlsFocusToken,
                    onControlsEntryPlaced = { controlsEntryRequester = null },
                    onFocusRegionChanged = { controlsFocusRegion = it },
                    onOpenPanel = {
                        panel = it
                        controlsHideJob?.cancel()
                        controlsHideJob = null
                        controlsVisible = false
                        // A remembered start never looked any streams up, so this is the first
                        // moment the list is actually wanted. See reloadStreamCandidates.
                        if (it == OverlayPanel.Streams && candidate?.streams.orEmpty().size <= 1) {
                            reloadStreamCandidates(forceRefresh = false)
                        }
                    },
                    isLive = isLive,
                    isVod = isVod,
                    showLiveProgress = showLiveProgress,
                    onToggleLiveProgress = {
                        showLiveProgress = !showLiveProgress
                        registerInteraction()
                    },
                )
            }
        }

        // Skip intro / recap / ending, and the hand-off into the next episode.
        segmentAction?.let { action ->
            PlayerSkipActionChip(
                label = stringResource(action.labelRes),
                bottomPadding = if (controlsVisible) 112.dp else 24.dp,
                focusRequester = segmentChipRequester,
                onClick = {
                    when (action.kind) {
                        SegmentActionKind.Skip -> {
                            val target = maxOf(action.targetTimeSec ?: positionSec, positionSec)
                            scheduleSeek(target)
                            markSegmentHandled(action.segmentType)
                            // Released before showing the controls, or the guard this same press
                            // relies on would swallow the call that puts them back.
                            segmentPromptActive = false
                            registerInteraction()
                        }
                        SegmentActionKind.NextEpisode -> {
                            segmentPromptActive = false
                            scope.launch { openNextEpisodeDialog() }
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }


        AnimatedVisibility(
            visible = autoSkipNotice != null,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp),
            enter = TvMotion.fadeInSpec(TvMotion.Quick),
            exit = TvMotion.fadeOutSpec(TvMotion.Quick),
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xDD151820), RoundedCornerShape(999.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(autoSkipNotice.orEmpty(), color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        AnimatedVisibility(
            visible = subtitleErrorMessage != null,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 144.dp),
            enter = TvMotion.fadeInSpec(TvMotion.Quick),
            exit = TvMotion.fadeOutSpec(TvMotion.Quick),
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xE62B171A), RoundedCornerShape(999.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(subtitleErrorMessage.orEmpty(), color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }

        if (nextEpisodeDialogVisible && nextEpisode != null) {
            NextEpisodeDialog(
                detail = detail,
                episode = nextEpisode!!,
                streams = nextEpisodeCandidate?.streams.orEmpty(),
                loading = nextEpisodeLoading,
                countdown = nextEpisodeCountdown,
                playRequester = nextEpisodePlayRequester,
                cancelRequester = nextEpisodeCancelRequester,
                onPlayNow = {
                    queuedNextEpisode = true
                    nextEpisodeDialogVisible = false
                    recommendationDismissed = true
                    scheduleControlsHide()
                },
                onSelectStream = { index ->
                    nextEpisodeCandidate = nextEpisodeCandidate?.copy(
                        stream = nextEpisodeCandidate?.streams?.getOrNull(index),
                    )
                    queuedNextEpisode = true
                    nextEpisodeDialogVisible = false
                    recommendationDismissed = true
                    scheduleControlsHide()
                },
                onCancel = {
                    nextEpisodeDialogVisible = false
                    nextEpisodeCountdown = null
                    queuedNextEpisode = false
                    recommendationDismissed = true
                    scheduleControlsHide()
                },
            )
        }

        val recommendedItems = detail?.similarTitles
            ?.filter { it.id != request.mediaId }
            ?.take(playbackPreferences.recommendationItemCount.coerceIn(1, 2))
            .orEmpty()
        PlayerOverlayVisibility(
            visible = recommendationDialogVisible && recommendedItems.isNotEmpty(),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (recommendedItems.isNotEmpty()) {
                NextRecommendationDialog(
                    currentTitle = detail?.title ?: request.title.orEmpty(),
                    items = recommendedItems,
                    queuedItemId = queuedRecommendation?.id,
                    playRequester = nextEpisodePlayRequester,
                    cancelRequester = nextEpisodeCancelRequester,
                    onPlayNext = { item -> queuedRecommendation = item },
                    onDismiss = {
                        val restorePlayerFocus = recommendationHasFocus
                        recommendationDialogVisible = false
                        recommendationDismissed = true
                        queuedRecommendation = null
                        if (restorePlayerFocus) showControls(focusPlay = true) else scheduleControlsHide()
                    },
                    onFocusChanged = { recommendationHasFocus = it },
                )
            }
        }

        smartSwitchCandidate?.let { suggested ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xB8000000))
                    .focusGroup(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                PlayerGlassSurface(
                    modifier = Modifier.width(720.dp).padding(bottom = 24.dp),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(stringResource(R.string.player_source_keeps_buffering), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black), color = Color.White)
                            Text(stringResource(R.string.player_switch_source_prompt, suggested.addonName.ifBlank { stringResource(R.string.player_next_ranked_source) }), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.72f))
                        }
                        androidx.tv.material3.OutlinedButton(onClick = {
                            smartSwitchCandidate = null
                            recentPlaybackStalls = emptyList()
                            smartSwitchCooldownUntil = android.os.SystemClock.elapsedRealtime() + 180_000L
                            showControls(focusPlay = !isLive)
                        }) { Text(stringResource(R.string.action_keep)) }
                        androidx.tv.material3.Button(
                            onClick = {
                                val resumeAt = positionSec.coerceAtLeast(0.0)
                                smartSwitchCandidate = null
                                recentPlaybackStalls = emptyList()
                                smartSwitchCooldownUntil = android.os.SystemClock.elapsedRealtime() + 60_000L
                                scope.launch {
                                    val resolved = repository.resolveSelectedPlayback(
                                        request = playbackRequest.copy(episode = currentEpisode),
                                        stream = suggested,
                                        streams = candidate?.streams.orEmpty(),
                                        forceRefresh = true,
                                    )
                                    if (resolved.source != null) {
                                        candidate = resolved
                                        currentRequestHeaders = defaultPlaybackHeaders + resolved.source.requestHeaders
                                        currentSourceUrl = resolved.source.url
                                        currentLabel = resolved.source.label
                                        pendingEngineResumePositionSec = resumeAt.takeIf { it > 0.0 }
                                        activePlaybackEngine = initialPlaybackEngine(playbackPreferences.playerEngine)
                                        autoEngineFallbackUsed = false
                                        continueSourceNotice = suggested.addonName.takeIf { it.isNotBlank() }
                                            ?.let { "Switching to $it and keeping your position…" }
                                            ?: "Switching source and keeping your position…"
                                        sourceFallbackNotice = null
                                        loading = true
                                        error = null
                                    } else {
                                        error = "That replacement source could not be prepared. Choose another source."
                                        continueSourceNotice = null
                                        controlsVisible = false
                                    }
                                }
                            },
                            modifier = Modifier.focusRequester(smartSwitchRequester),
                        ) { Text(stringResource(R.string.action_switch)) }
                    }
                }
            }
        }

        if (pauseInfoVisible && paused && !loading && error == null && panel == null && !controlsVisible && !nextEpisodeDialogVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(520.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color.Transparent, Color(0x38000000), Color(0xC0000000)),
                            ),
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(360.dp)
                        .padding(end = 52.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                        if (!detail?.titleLogo.isNullOrBlank()) {
                            AsyncImage(
                                model = detail!!.titleLogo,
                                contentDescription = detail?.title ?: request.title,
                                modifier = Modifier.height(52.dp),
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.CenterEnd,
                            )
                        } else {
                            androidx.tv.material3.Text(
                                text = detail?.title ?: request.title ?: "",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                                color = Color.White,
                                textAlign = TextAlign.End,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        currentEpisode?.let { ep ->
                            androidx.tv.material3.Text(
                                // Two resources rather than one plus concatenation: a translator
                                // needs to be able to move the title, the dash and the numbering
                                // relative to one another, which a glued-on suffix does not allow.
                                text = ep.title?.let { title ->
                                    stringResource(R.string.season_episode_dot_title, ep.seasonNumber, ep.episodeNumber, title)
                                } ?: stringResource(R.string.season_episode_dot, ep.seasonNumber, ep.episodeNumber),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFF0BA66),
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val synopsis = currentEpisode?.overview ?: detail?.description
                        if (!synopsis.isNullOrBlank()) {
                            androidx.tv.material3.Text(
                                text = synopsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.74f),
                                textAlign = TextAlign.End,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        androidx.tv.material3.Text(
                            text = if (isLive) "LIVE" else "${com.streamdek.tv.nativeapp.ui.formatPlaybackClock(positionSec)} / ${com.streamdek.tv.nativeapp.ui.formatPlaybackClock(durationSec)}",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White.copy(alpha = 0.55f),
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }
        RenderControlsAndDialogs()

        @Composable
        fun RenderOptionPanel() {
        // The scrim used to snap to full strength the instant a panel was asked for, and vanish the
        // instant it closed, while the panel itself slid. Driving it from `panel != null` outside
        // the panel's own composition means it fades both ways on the same curve the panel moves
        // on, so the two read as one movement rather than a flash and a slide.
        val scrimAlpha by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (panel != null) 0.33f else 0f,
            animationSpec = if (panel != null) TvMotion.enterSpec() else TvMotion.exitSpec(),
            label = "panel-scrim",
        )
        if (scrimAlpha > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha)),
            )
        }

        // Option panel (sources / audio / subtitles)
        panel?.let { activePanel ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                PlayerOverlayVisibility(
                    visible = true,
                    modifier = Modifier.padding(end = 36.dp, top = 52.dp, bottom = 52.dp),
                ) {
                    PlayerOptionPanel(
                        panel = activePanel,
                        candidate = candidate,
                        audioTracks = audioTracks,
                        subtitleTracks = subtitleTracks,
                        externalSubtitles = externalSubtitles,
                        showOnlyPreferredSubtitleLanguages = playbackPreferences.showOnlyPreferredSubtitleLanguages,
                        preferredSubtitleLanguages = listOf(
                            playbackPreferences.defaultSubtitleLanguage,
                            playbackPreferences.secondarySubtitleLanguage,
                        ),
                        subtitlesLoading = subtitlesLoading,
                        selectedAudioId = selectedAudioId,
                        selectedSubtitleId = selectedSubtitleId,
                        selectedExternalSubtitleId = selectedExternalSubtitleId,
                        currentSpeed = speed,
                        activeEngine = activePlaybackEngine,
                        closeRequester = panelCloseRequester,
                        firstItemRequester = panelFirstItemRequester,
                        onClose = {
                            val closedPanel = activePanel
                            panel = null
                            panelClosedAtMs = System.currentTimeMillis()
                            restoreControlsAfterPanel(closedPanel)
                        },
                        onInteract = { if (!isLive) registerInteraction() },
                        onSelectStream = { index ->
                            scope.launch {
                                val stream = candidate?.streams?.getOrNull(index) ?: return@launch
                                panel = null
                                panelClosedAtMs = System.currentTimeMillis()
                                loading = true
                                controlsVisible = false
                                // Say what is happening now. This path never re-enters
                                // loadPlayback, so without it the loading screen carried whatever
                                // sentence the previous load had left behind.
                                continueSourceNotice = stream.addonName.takeIf { it.isNotBlank() }
                                    ?.let { "Opening $it…" }
                                    ?: "Opening the source you chose…"
                                sourceFallbackNotice = null
                                pendingResumePositionSec = positionSec.takeIf { it > 0.0 }
                                val selected = try {
                                    repository.resolveSelectedPlayback(
                                        request = request.copy(
                                            episode = currentEpisode,
                                            selectedStreamKey = repository.streamSelectionKey(stream),
                                            selectedStream = stream,
                                        ),
                                        stream = stream,
                                        streams = candidate?.streams.orEmpty(),
                                    )
                                } catch (e: Exception) {
                                    error = "Could not load this source: ${e.message ?: "Unknown error"}"
                                    continueSourceNotice = null
                                    loading = false
                                    controlsVisible = true
                                    return@launch
                                }
                                if (selected.source == null) {
                                    error = if (repository.isUsenetStream(stream)) {
                                        // The assembler's own words when it has any: a packed post
                                        // and an unreachable server are different problems, and
                                        // only one of them is worth trying again.
                                        repository.lastUsenetFailureMessage
                                            ?: "This usenet source could not be opened. The news server may be unreachable, or the post may be incomplete."
                                    } else {
                                        "This source could not be resolved. Please try another."
                                    }
                                    continueSourceNotice = null
                                    loading = false
                                    controlsVisible = true
                                    return@launch
                                }
                                candidate = selected
                                currentRequestHeaders = defaultPlaybackHeaders + selected.source.requestHeaders
                                currentSourceUrl = selected.source.url
                                currentLabel = selected.source.label ?: repository.describeStreamOption(stream)
                                resetPlaybackEngineForNewSource()
                            }
                        },
                        onSelectAudio = {
                            audioPreferenceAppliedForSource = currentSourceUrl
                            playerView?.setAudioTrack(it)
                            panel = null
                            panelClosedAtMs = System.currentTimeMillis()
                            playerView?.setPaused(paused)
                            restoreControlsAfterPanel(OverlayPanel.Audio)
                        },
                        onDisableSubtitles = {
                            subtitleSelectionGeneration += 1
                            selectedExternalSubtitleId = null
                            externalSubtitleAppliedKey = null
                            subtitlePreferenceAppliedForSource = currentSourceUrl
                            playerView?.disableSubtitleTrack()
                            panel = null
                            panelClosedAtMs = System.currentTimeMillis()
                            restoreControlsAfterPanel(OverlayPanel.Subtitles)
                        },
                        onSelectSubtitle = {
                            subtitleSelectionGeneration += 1
                            selectedExternalSubtitleId = null
                            externalSubtitleAppliedKey = null
                            subtitlePreferenceAppliedForSource = currentSourceUrl
                            playerView?.setSubtitleTrack(it)
                            panel = null
                            panelClosedAtMs = System.currentTimeMillis()
                            restoreControlsAfterPanel(OverlayPanel.Subtitles)
                        },
                        onSelectExternalSubtitle = { subtitle ->
                            scope.launch {
                                val source = currentSourceUrl ?: return@launch
                                selectedExternalSubtitleId = subtitle.id
                                subtitleErrorMessage = null
                                val requestGeneration = ++subtitleSelectionGeneration
                                subtitlePreferenceAppliedForSource = source
                                if (
                                    activePlaybackEngine == ActivePlaybackEngine.Media3 &&
                                    playerView?.selectExternalSubtitleTrack(subtitle.id) == true
                                ) {
                                    selectedSubtitleId = -1
                                    externalSubtitleAppliedKey = "${activePlaybackEngine.name}:$source:${subtitle.id}"
                                    panel = null
                                    panelClosedAtMs = System.currentTimeMillis()
                                    restoreControlsAfterPanel(OverlayPanel.Subtitles)
                                    return@launch
                                }
                                // These files sit on hosts that expire links and refuse requests,
                                // so one failing is ordinary rather than exceptional. The next copy
                                // in the same language is tried before the viewer is told anything,
                                // exactly as a failed stream falls through to the next source.
                                val candidates = listOf(subtitle) + externalSubtitles.filter {
                                    it.id != subtitle.id && Languages.matches(it.language, subtitle.language)
                                }
                                var localPath: String? = null
                                for (candidate in candidates.take(SUBTITLE_ATTEMPT_LIMIT)) {
                                    if (currentSourceUrl != source || selectedExternalSubtitleId != subtitle.id || subtitleSelectionGeneration != requestGeneration) return@launch
                                    localPath = repository.downloadSubtitleToCache(candidate.url, context.cacheDir)
                                    if (localPath != null) {
                                        if (candidate.id != subtitle.id) {
                                            TvDebugLogger.i("Subtitles", "fell through to ${candidate.label}")
                                        }
                                        break
                                    }
                                }
                                if (localPath == null) {
                                    if (subtitleSelectionGeneration != requestGeneration) return@launch
                                    subtitleErrorMessage = "That subtitle could not be loaded. Try another subtitle source."
                                    selectedExternalSubtitleId = null
                                    return@launch
                                }
                                if (currentSourceUrl != source || selectedExternalSubtitleId != subtitle.id || subtitleSelectionGeneration != requestGeneration) return@launch
                                selectedSubtitleId = -1
                                playerView?.addSubtitleFile(localPath)
                                externalSubtitleAppliedKey = "${activePlaybackEngine.name}:$source:${subtitle.id}"
                                panel = null
                                panelClosedAtMs = System.currentTimeMillis()
                                restoreControlsAfterPanel(OverlayPanel.Subtitles)
                            }
                        },
                        onSelectSpeed = {
                            speed = it
                            panel = null
                            panelClosedAtMs = System.currentTimeMillis()
                            restoreControlsAfterPanel(OverlayPanel.Speed)
                        },
                        onSelectEngine = ::switchPlaybackEngine,
                        // Appearance changes stay in the panel rather than closing it: these are
                        // adjusted by eye against the subtitle currently on screen, which takes
                        // several presses, and dismissing after each one would make that unusable.
                        subtitleFontSize = subtitleFontSize,
                        subtitlePosition = subtitlePosition,
                        subtitleDelay = subtitleDelay,
                        subtitleDelaySupported = true,
                        onSubtitleFontSize = {
                            subtitleFontSize = it
                            playerView?.setSubtitleFontSize(it)
                            repository.saveSubtitleFontSize(it)
                        },
                        onSubtitlePosition = {
                            subtitlePosition = it
                            playerView?.setSubtitlePosition(it)
                            repository.saveSubtitlePosition(it)
                        },
                        onSubtitleDelay = {
                            subtitleDelay = it
                            playerView?.setSubtitleDelay(it)
                        },
                        // Refreshes the list in place. Leaving for the picker would tear down
                        // playback to answer a question about what else is available, which is the
                        // opposite of what an in-player source list is for.
                        onReloadStreams = { reloadStreamCandidates(forceRefresh = true) },
                        streamsReloading = streamsReloading,
                        playbackStats = playbackStats,
                        currentStreamUrl = currentSourceUrl,
                        currentLabel = currentLabel,
                        engineLabel = if (activePlaybackEngine == ActivePlaybackEngine.MPV) "mpv" else "ExoPlayer",
                        durationSec = durationSec,
                        isLive = isLive,
                        pluginState = bootstrap?.profilePlugins ?: ProfilePluginState(),
                        subtitleDefaultSource = playbackPreferences.subtitleDefaultSource,
                        favoriteSourceKeys = favoriteSourceKeys,
                        onToggleSourceFavourite = { key ->
                            scope.launch {
                                val next = favoriteSourceKeys.toMutableSet().apply { if (!add(key)) remove(key) }.take(250)
                                repository.updateStreamsPreferences(mapOf("favoriteSourceKeys" to next))
                            }
                        },
                    )
                }
            }
        }
        }
        RenderOptionPanel()
    }
    }
    RenderPlayerRoot()
}

@Composable
private fun LiveStatusBadge(
    isVod: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
            .background(Color(0xD911141B))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isVod) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = Color(0xFF60A5FA),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color(0xFFEF4444)),
            )
        }
        Text(
            text = if (isVod) "VOD" else "LIVE",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
            color = if (isVod) Color(0xFF93C5FD) else Color.White,
        )
    }
}

@Composable
private fun LiveChannelInfoOverlay(
    channelTitle: String,
    sourceName: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(Color(0xD911141B))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = channelTitle,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = sourceName,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LiveChannelDownHint(
    offsetY: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.alpha(0.78f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            text = stringResource(R.string.player_channels),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(R.string.player_live_channels_hint),
            tint = Color.White,
            modifier = Modifier
                .size(24.dp)
                .offset(y = offsetY.dp),
        )
    }
}

@Composable
private fun LiveFavouritesRightHint(
    offsetX: Float,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.alpha(0.82f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.live_favourites),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = stringResource(R.string.player_favourite_channels_hint),
            tint = Color.White,
            modifier = Modifier.size(26.dp).offset(x = offsetX.dp),
        )
    }
}

@Composable
private fun LiveFavouritesDrawer(
    channels: List<MediaItem>,
    currentChannelId: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    initialFocusRequester: FocusRequester,
    onSelect: (MediaItem) -> Unit,
    onToggleFavourite: (MediaItem) -> Unit,
    cardView: Boolean,
    onToggleView: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusedIndex = channels.indexOfFirst { it.id == currentChannelId }.coerceAtLeast(0)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth(0.33f)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color(0xD9050505), Color(0xFF050505)),
                ),
            )
            .padding(start = 46.dp, end = 22.dp, top = 88.dp, bottom = 34.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(R.string.live_favourite_channels),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                    color = Color.White,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = pluralStringResource(R.plurals.live_favourites_saved_hint, channels.size, channels.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.66f),
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Card(
                onClick = onToggleView,
                modifier = Modifier.padding(start = 10.dp).size(40.dp),
                shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(999.dp)),
                colors = CardDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color.Black,
                ),
                border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
                glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
                scale = CardDefaults.scale(focusedScale = 1.08f),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (cardView) Icons.Filled.ViewList else Icons.Filled.GridView,
                        contentDescription = if (cardView) "Switch to text list" else "Switch to card view",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().focusGroup().onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && event.key == Key.DirectionLeft) {
                    onDismiss()
                    true
                } else false
            },
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(if (cardView) 8.dp else 5.dp),
            contentPadding = PaddingValues(bottom = 36.dp),
        ) {
            items(channels, key = { "${it.sourceAddonId}:${it.id}" }) { item ->
                val index = channels.indexOf(item)
                val itemModifier = if (index == focusedIndex) Modifier.focusRequester(initialFocusRequester) else Modifier
                if (cardView) {
                    LiveFavouriteDrawerCard(
                        item = item,
                        selected = item.id == currentChannelId,
                        modifier = itemModifier,
                        onLongPress = { onToggleFavourite(item) },
                        onClick = { onSelect(item) },
                    )
                } else {
                    LiveFavouriteTextItem(
                        item = item,
                        selected = item.id == currentChannelId,
                        modifier = itemModifier,
                        onLongPress = { onToggleFavourite(item) },
                        onClick = { onSelect(item) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveFavouriteTextItem(
    item: MediaItem,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(44.dp).tvCardLongPress(onLongPress),
        shape = CardDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
        colors = CardDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                item.title,
                color = Color.White,
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (selected) FontWeight.Black else FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) Text("  " + stringResource(R.string.live_on_now), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveFavouriteDrawerCard(
    item: MediaItem,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(60.dp).tvCardLongPress(onLongPress).onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(containerColor = Color(0xFF181A1F), focusedContainerColor = Color(0xFF20232A)),
        border = CardDefaults.border(border = Border.None, focusedBorder = Border.None),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = 1.03f),
    ) {
        Box(Modifier.fillMaxSize().clip(AppCardShape)) {
            AsyncImage(item.backdrop ?: item.poster, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xE6000000)))))
            if (focused) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)))
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(8.dp), horizontalAlignment = Alignment.End) {
                if (selected) Text(stringResource(R.string.live_on_now), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black))
                Text(item.title, color = Color.White, textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LiveChannelCarousel(
    channels: List<MediaItem>,
    currentChannelId: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    initialFocusIndex: Int,
    initialFocusRequester: FocusRequester,
    favouriteKeys: Set<String>,
    onSelect: (MediaItem) -> Unit,
    onToggleFavourite: (MediaItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color(0xE6000000), Color(0xFF050505)),
                ),
            )
            .padding(top = 34.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.live_channels),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
            color = Color.White,
            modifier = Modifier.padding(horizontal = 36.dp),
        )
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.DirectionUp) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
            contentPadding = PaddingValues(horizontal = 36.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(
                items = channels,
                key = { index, item -> "${item.sourceAddonId}:${item.streamType}:${item.id}:$index" },
            ) { index, item ->
                LivePlayerChannelCard(
                    item = item,
                    selected = item.id == currentChannelId,
                    favourite = "${item.sourceAddonId}:${item.id}" in favouriteKeys,
                    modifier = if (index == initialFocusIndex) {
                        Modifier.focusRequester(initialFocusRequester)
                    } else {
                        Modifier
                    },
                    onLongPress = { onToggleFavourite(item) },
                    onClick = { onSelect(item) },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LivePlayerChannelCard(
    item: MediaItem,
    selected: Boolean,
    favourite: Boolean = false,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = modifier
            .width(240.dp)
            .height(135.dp)
            .tvCardLongPress(onLongPress)
            .onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.24f) else Color(0xFF181A1F),
            focusedContainerColor = Color(0xFF20232A),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                shape = AppCardShape,
            ),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = 1.035f),
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(AppCardShape)) {
            AsyncImage(
                model = item.backdrop ?: item.poster,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x10000000), Color(0x44000000), Color(0xEE000000)),
                        ),
                    ),
            )
            if (favourite) {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = stringResource(R.string.player_favourite_channel),
                    tint = Color(0xFFFACC15),
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(20.dp),
                )
            }
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (selected) {
                    Text(
                        text = stringResource(R.string.live_now_playing),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (focused) FontWeight.Black else FontWeight.Bold),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
internal suspend fun resolveNextEpisode(
    repository: StreamDekRepository,
    request: PlaybackRequest,
    detail: MediaDetail?,
    currentEpisode: EpisodeContext?,
): EpisodeContext? {
    if (request.mediaType != "tv" || currentEpisode == null || detail == null) return null
    val currentSeason = repository.fetchSeason(request.mediaId, currentEpisode.seasonNumber) ?: return null
    val nextInSeason = currentSeason.episodes.firstOrNull { it.episodeNumber == currentEpisode.episodeNumber + 1 }
    if (nextInSeason != null) {
        return EpisodeContext(
            seasonNumber = currentEpisode.seasonNumber,
            episodeNumber = nextInSeason.episodeNumber,
            title = nextInSeason.name,
            overview = nextInSeason.overview,
            still = nextInSeason.still,
            runtime = nextInSeason.runtime,
            airDate = nextInSeason.airDate,
            tmdbEpisodeId = nextInSeason.id,
        )
    }
    val nextSeasonNumber = detail.seasons.firstOrNull { it.seasonNumber > currentEpisode.seasonNumber }?.seasonNumber ?: return null
    val nextSeason = repository.fetchSeason(request.mediaId, nextSeasonNumber) ?: return null
    val first = nextSeason.episodes.firstOrNull() ?: return null
    return EpisodeContext(
        seasonNumber = nextSeasonNumber,
        episodeNumber = first.episodeNumber,
        title = first.name,
        overview = first.overview,
        still = first.still,
        runtime = first.runtime,
        airDate = first.airDate,
        tmdbEpisodeId = first.id,
    )
}

internal fun isForwardEpisodeTransition(
    fromSeason: Int?,
    fromEpisode: Int?,
    targetSeason: Int,
    targetEpisode: Int,
): Boolean = fromSeason == null || fromEpisode == null ||
    targetSeason > fromSeason ||
    (targetSeason == fromSeason && targetEpisode > fromEpisode)

private fun segmentPriority(segmentType: String): Int {
    return when (segmentType) {
        "intro" -> 0
        "recap" -> 1
        "outro" -> 2
        else -> 3
    }
}

private fun preferredAudioTrack(
    audioTracks: List<MpvTrackInfo>,
    preferredLanguage: String,
): MpvTrackInfo? {
    val normalizedPreference = preferredLanguage.trim().lowercase()
    if (normalizedPreference.isBlank() || normalizedPreference == "off") return null
    // "Original language" is a decision to leave the release's own track alone.
    if (normalizedPreference == Languages.ORIGINAL) return null
    return audioTracks.firstOrNull { track ->
        trackMatchesLanguagePreference(track, normalizedPreference)
    }
}

private fun preferredSubtitleTrack(
    subtitles: List<MpvTrackInfo>,
    preferredLanguage: String,
): MpvTrackInfo? {
    val normalizedPreference = preferredLanguage.trim().lowercase()
    if (normalizedPreference.isBlank() || normalizedPreference == "off") return null
    if (normalizedPreference == Languages.NONE || normalizedPreference == Languages.ORIGINAL) return null
    return subtitles.firstOrNull { track ->
        trackMatchesLanguagePreference(track, normalizedPreference)
    }
}

private fun trackMatchesLanguagePreference(track: MpvTrackInfo, preferredLanguage: String): Boolean {
    val normalizedLanguage = track.language?.trim()?.lowercase().orEmpty()
    val normalizedTitle = track.title?.trim()?.lowercase().orEmpty()
    val aliases = languageAliases(preferredLanguage)
    return aliases.any { alias ->
        normalizedLanguage == alias ||
            normalizedLanguage.startsWith("$alias-") ||
            titleMatchesLanguageAlias(normalizedTitle, alias)
    }
}

/**
 * Every tag a container might use for [preferredLanguage], plus the written-out name.
 *
 * Only English used to be spelled out here; every other language matched the preference string
 * exactly and nothing else. A profile set to French therefore matched a track tagged "fr" and
 * missed the same track tagged "fra" or "fre" or titled "French" — which on most releases is all
 * of them. [Languages] derives the tags from the JVM's ISO tables, both three-letter forms
 * included, for every language rather than one.
 */
private fun languageAliases(preferredLanguage: String): Set<String> {
    val tags = Languages.tags(preferredLanguage)
    if (tags.isEmpty()) return setOf(preferredLanguage)
    return (tags + Languages.label(preferredLanguage).lowercase()).filter { it.isNotBlank() }.toSet()
}

private fun titleMatchesLanguageAlias(title: String, alias: String): Boolean {
    if (title.isBlank()) return false
    val tokenizedTitle = title.replace(Regex("[^a-z0-9]+"), " ")
    return Regex("(^| )${Regex.escape(alias)}( |$)").containsMatchIn(tokenizedTitle)
}













private fun createPlayerView(
    context: android.content.Context,
    renderSurface: String,
    engine: ActivePlaybackEngine,
): android.view.View {
    if (engine == ActivePlaybackEngine.Media3) return ExoPlaybackView(context)
    return when (renderSurface) {
        "texture" -> MPVTextureView(context)
        else -> MPVView(context)
    }
}

private fun normalizeRenderSurfacePreference(value: String?): String {
    return when (value?.trim()?.lowercase()) {
        "texture", "textureview" -> "texture"
        "surface", "surfaceview" -> "surface"
        "auto", "standard", null, "" -> "auto"
        else -> "auto"
    }
}

