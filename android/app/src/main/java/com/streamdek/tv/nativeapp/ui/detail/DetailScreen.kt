package com.streamdek.tv.nativeapp.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.Icon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.streamdek.tv.nativeapp.data.DetailPreferences
import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.PlaybackRequest
import com.streamdek.tv.nativeapp.data.SeasonDetail
import com.streamdek.tv.nativeapp.data.SeasonEpisode
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.TraktCommentItem
import com.streamdek.tv.nativeapp.data.TrailerPlaybackSource
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import com.streamdek.tv.nativeapp.data.resolveTrailerPlaybackSource
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.LocalImmersiveContent
import com.streamdek.tv.nativeapp.ui.LocalNavRailFocus
import com.streamdek.tv.nativeapp.ui.TvNavRailInset
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.SuppressBringIntoView
import com.streamdek.tv.nativeapp.ui.glideToItem
import com.streamdek.tv.nativeapp.ui.TvMotion
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * Extra left inset on the page's content, so it starts clear of the navigation rail.
 *
 * The rail is drawn over this screen rather than beside it — the artwork is full-bleed and the
 * shell deliberately does not inset the route — so without this the first card in every row sat on
 * top of the rail. That is not only a collision: focus moves left by looking for something further
 * left than what is focused, and a card overlapping the rail is not further left than it, which is
 * why left never found the menu from anywhere below the hero.
 */
private val DetailNavRailClearance = (TvNavRailInset - DetailInset).coerceAtLeast(0.dp)

/** How long a title page is left alone before its trailer is asked for. */
private const val AutoTrailerDelayMs = 4_000L

/**
 * Marks a band as the place focus comes back to.
 *
 * The rail hands focus to one requester per screen, and pointing that permanently at Play meant
 * every trip to the menu — however brief, whatever it was for — dumped the viewer back at the top
 * of the page with the row they had been reading collapsed behind them. The requester moves to
 * whichever band was last in use instead, so leaving and returning costs nothing.
 *
 * The group is what makes it land properly: requesting a group consults its `enter`, and each
 * band's own row-focus entry then picks the card the viewer was actually on rather than the first.
 */
private fun Modifier.bandRestorePoint(active: Boolean, requester: FocusRequester): Modifier =
    focusGroup().then(if (active) Modifier.focusRequester(requester) else Modifier)

/**
 * Title detail.
 *
 * Rebuilt around three ideas the previous screen did not serve well:
 *
 *  - **One hero, not three stacked pieces.** Poster, copy and actions are a single band, so the
 *    first thing on screen answers "what is this and can I play it" without scrolling. The screen
 *    this replaced split them across separate list items joined by a fixed-height sentinel.
 *  - **Discovery before commentary.** Sections run Episodes, Cast, More Like This, Reviews.
 *    Reviews sat above the recommendations before, so getting to something else to watch meant
 *    scrolling past other people's opinions.
 *  - **Cheap to draw.** The backdrop is two linear gradients rather than the seven-layer stack
 *    (two of them large radial shaders) that was redrawn every frame over a full-bleed image.
 *    That stack was the main reason this screen felt heavier than the rest of the app on a stick.
 */
@OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)
@Composable
fun DetailScreen(
    repository: StreamDekRepository,
    mediaType: String,
    mediaId: String,
    /** Where the navigation rail sends focus on the way back in — the Play button. */
    entryFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
    onPlay: (PlaybackRequest) -> Unit,
    onRequireAuth: () -> Unit,
) {
    val cachedDetail = remember(mediaType, mediaId) { repository.peekCachedDetail(mediaId, mediaType) }
    var uiState by remember(mediaType, mediaId) {
        mutableStateOf<DetailUiState>(cachedDetail?.let(DetailUiState::Ready) ?: DetailUiState.Loading)
    }
    var reloadToken by remember(mediaType, mediaId) { mutableIntStateOf(0) }
    /**
     * Season the episode run starts at. Only a deliberate press on a season chip moves it, and
     * doing so throws away the loaded run and starts a new one there.
     */
    var anchorSeasonNumber by rememberSaveable(mediaType, mediaId) { mutableIntStateOf(1) }
    /**
     * Season the focused episode belongs to. Follows the row as it crosses a season boundary, and
     * is what the chips highlight and the season-level actions act on.
     */
    var activeSeasonNumber by rememberSaveable(mediaType, mediaId) { mutableIntStateOf(1) }
    var selectedEpisodeIndex by rememberSaveable(mediaType, mediaId) { mutableIntStateOf(0) }
    /** Seasons fetched so far, in order, starting at [anchorSeasonNumber]. */
    var loadedSeasons by remember(mediaType, mediaId) { mutableStateOf<List<SeasonDetail>>(emptyList()) }
    var loadingNextSeason by remember(mediaType, mediaId) { mutableStateOf(false) }
    var resumeEpisodeContext by remember(mediaType, mediaId) { mutableStateOf<EpisodeContext?>(null) }
    var progressFraction by remember(mediaType, mediaId) { mutableStateOf<Float?>(null) }
    var progressLabel by remember(mediaType, mediaId) { mutableStateOf<String?>(null) }
    var inWatchlist by remember(mediaType, mediaId) { mutableStateOf(false) }
    var markedWatched by remember(mediaType, mediaId) { mutableStateOf(false) }
    /** Watched episodes across every loaded season, keyed by [watchedEpisodeKey]. */
    var watchedEpisodeKeys by remember(mediaType, mediaId) { mutableStateOf<Set<String>>(emptySet()) }
    var markingSeasonWatched by remember(mediaType, mediaId, activeSeasonNumber) { mutableStateOf(false) }
    var comments by remember(mediaType, mediaId) { mutableStateOf<List<TraktCommentItem>>(emptyList()) }
    var episodeAction by remember(mediaType, mediaId) { mutableStateOf<SeasonEpisodeEntry?>(null) }
    var episodeActionLoading by remember(mediaType, mediaId) { mutableStateOf(false) }
    var episodeActionError by remember(mediaType, mediaId) { mutableStateOf<String?>(null) }
    /** Full synopsis the viewer asked to read, shown over the screen until they close it. */
    var expandedSynopsis by remember(mediaType, mediaId) { mutableStateOf<String?>(null) }

    /**
     * The trailer, from asking for one to it being on screen.
     *
     * [trailerRequest] is bumped to ask; resolving runs off it and lands in [trailerSource], and a
     * source is what actually raises the trailer — a page whose content vanished the moment the
     * viewer pressed the button, and then sat on black for the several seconds YouTube takes to
     * answer, would read as the app having crashed. Nothing moves until there is something to show.
     */
    var trailerRequest by remember(mediaType, mediaId) { mutableIntStateOf(0) }
    var trailerSource by remember(mediaType, mediaId) { mutableStateOf<TrailerPlaybackSource?>(null) }
    /** Whether the trailer should be on screen. Cleared by Back; the source outlives it by one fade. */
    var trailerRunning by remember(mediaType, mediaId) { mutableStateOf(false) }
    var trailerResolving by remember(mediaType, mediaId) { mutableStateOf(false) }
    /** True once a trailer has been raised for this title, which is what makes the action a replay. */
    var trailerPlayed by rememberSaveable(mediaType, mediaId) { mutableStateOf(false) }
    /**
     * Auto-play fires once per title, and saved rather than remembered so that it stays fired.
     *
     * Leaving for the stream picker and coming back tears this screen down and builds it again, so
     * ordinary state was gone by the time the page returned and the trailer played a second time —
     * over a viewer who had just been choosing a source and was on their way to watching. After the
     * first showing it is the replay button or nothing.
     */
    var autoTrailerFired by rememberSaveable(mediaType, mediaId) { mutableStateOf(false) }
    /** Whether the pending request came from arriving on the page or from the button. */
    var trailerRequestIsAuto by remember(mediaType, mediaId) { mutableStateOf(false) }
    val pageOpenedAtMs = remember(mediaType, mediaId) { System.currentTimeMillis() }
    val trailerRequester = remember(mediaType, mediaId) { FocusRequester() }
    val setImmersiveContent = LocalImmersiveContent.current
    /** Null wherever the rail is not on screen, in which case left out of the page stays put. */
    val navRailRequester = LocalNavRailFocus.current

    // Kept per title, so that opening one title page from another cannot leave two screens holding
    // the same requester while the transition crossfades and land focus on the one going away.
    // The shell's requester is attached to the same button alongside it.
    val playRequester = remember(mediaType, mediaId) { FocusRequester() }
    val synopsisMoreRequester = remember(mediaType, mediaId) { FocusRequester() }
    /**
     * Which row the viewer is on. Only that row is drawn at full size; the rest shrink, so moving
     * down the page keeps the row you are actually using on screen instead of pushing it under the
     * fold. Coming back up expands whatever you land on again.
     *
     * Null means the hero, and the hero is where the page sits whenever nothing below it has been
     * chosen. That is what this drives rather than the hero's own focus: focus also leaves the hero
     * for the navigation rail, the trailer, and a dialog, none of which are the viewer moving down
     * the page — and collapsing the hero for those left the page rearranged behind whatever they
     * had actually opened.
     */
    var focusedRow by remember(mediaType, mediaId) { mutableStateOf<String?>(null) }
    val heroExpanded = focusedRow == null
    val seasonChipRequester = remember(mediaType, mediaId) { FocusRequester() }
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var ambientPalette by remember(mediaType, mediaId) {
        mutableStateOf(AmbientBackdropPalette(Color(0xFF1A2633), Color(0xFF111820), Color(0xFF24384A)))
    }

    LaunchedEffect(mediaType, mediaId, reloadToken) {
        val existingDetail = (uiState as? DetailUiState.Ready)?.detail
        if (existingDetail == null) uiState = DetailUiState.Loading
        comments = emptyList()
        progressFraction = null
        progressLabel = null
        inWatchlist = false
        markedWatched = false
        watchedEpisodeKeys = emptySet()
        episodeAction = null
        episodeActionLoading = false
        episodeActionError = null
        resumeEpisodeContext = null
        runCatching { repository.refreshBootstrap() }

        val libraryDeferred = supervisorScope { async { runCatching { repository.fetchLibrary() }.getOrNull() } }
        val detail = repository.fetchDetail(mediaId, mediaType, forceRefresh = reloadToken > 0)
        if (detail == null) {
            if (existingDetail == null) uiState = DetailUiState.Error("Could not load title details")
            return@LaunchedEffect
        }
        uiState = DetailUiState.Ready(detail)
        if (detail.seasons.none { it.seasonNumber == anchorSeasonNumber }) {
            anchorSeasonNumber = detail.seasons.firstOrNull()?.seasonNumber ?: 1
            activeSeasonNumber = anchorSeasonNumber
            selectedEpisodeIndex = 0
        }
        libraryDeferred.await()?.let { library ->
            inWatchlist = library.watchlist.any { it.id == mediaId && it.type == mediaType }
            resumeEpisodeContext = library.continueWatching
                .firstOrNull { it.id == mediaId && it.type == mediaType }?.episode
        }
    }

    val detail = (uiState as? DetailUiState.Ready)?.detail

    // Something has to hold focus or the remote does nothing at all. Play is the right landing
    // spot: it is what the viewer opened the screen for, and pressing down from it walks into the
    // sections. Safe to do here now that the hero sits outside the scrolling list — this is the
    // request that used to drag the title off the top of the screen.
    LaunchedEffect(detail?.id) {
        detail ?: return@LaunchedEffect
        delay(140)
        runCatching { playRequester.requestFocus() }
    }

    val bootstrap by repository.bootstrap.collectAsState()
    val detailPrefs = bootstrap?.preferences?.detail ?: DetailPreferences()
    // Clamped on the way in, the way the phone clamps it: the resolver would coerce this itself,
    // but the player's track selector is handed the same figure and a synced value from a client
    // that allowed something odd should not reach it unchecked.
    val trailerMaxHeight = detailPrefs.heroTrailerResolution.coerceIn(360, 2160)

    /**
     * Every video this title has, with the metadata service's first pick at the front.
     *
     * All of them are handed to the resolver rather than just the first: that list is roughly
     * newest first, which puts theatre stings and ticket spots ahead of the actual trailer, and the
     * resolver reads their running times to tell them apart.
     */
    val trailerCandidateUrls = remember(detail?.id, detail?.trailerKey, detail?.trailerKeys) {
        val current = detail ?: return@remember emptyList()
        (listOfNotNull(current.trailerKey?.takeIf { it.isNotBlank() }) + current.trailerKeys)
            .filter { it.isNotBlank() }
            .distinct()
            .map { key -> "https://www.youtube.com/watch?v=$key" }
    }
    val hasTrailer = trailerCandidateUrls.isNotEmpty()
    val trailerVisible = trailerSource != null && trailerRunning

    // One value drives the whole handover, and the two halves of it do not overlap: the page leaves
    // over the first half and the trailer arrives over the second. A straight crossfade had both
    // half-visible in the middle, which on a full-screen takeover reads as a dissolve between two
    // things rather than one making way for the other. Reversing the same value on the way out
    // gives the trailer leaving before the page returns, for free.
    val trailerTransition by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (trailerVisible) 1f else 0f,
        animationSpec = TvMotion.standardSpec(TvMotion.Expand * 2),
        // Held in composition until it has finished leaving, or the picture would cut out on the
        // frame Back was pressed and only the page would animate.
        finishedListener = { progress -> if (progress <= 0.001f) trailerSource = null },
        label = "trailer-transition",
    )
    val pageAlpha = (1f - trailerTransition * 2f).coerceIn(0f, 1f)
    val trailerStageAlpha = ((trailerTransition - 0.5f) * 2f).coerceIn(0f, 1f)
    // A little way back into the screen as it goes, so the page reads as making room rather than
    // simply dimming.
    val pageScale = 1f - 0.04f * (1f - pageAlpha)

    fun dismissTrailer() {
        if (!trailerRunning) return
        trailerRunning = false
        scope.launch {
            // Focus goes back once the page is on its way in. Any earlier and the request lands on
            // a hero that is still invisible, and the viewer is left pointing at nothing.
            delay(TvMotion.Expand.toLong() / 2)
            runCatching { playRequester.requestFocus() }
        }
    }

    LaunchedEffect(detail?.id, detailPrefs.heroTrailerAutoplay, hasTrailer) {
        if (!hasTrailer || !detailPrefs.heroTrailerAutoplay || autoTrailerFired) return@LaunchedEffect
        autoTrailerFired = true
        trailerRequestIsAuto = true
        trailerRequest += 1
    }

    LaunchedEffect(trailerRequest) {
        if (trailerRequest == 0 || trailerCandidateUrls.isEmpty()) return@LaunchedEffect
        trailerResolving = true
        val resolved = runCatching {
            resolveTrailerPlaybackSource(
                url = trailerCandidateUrls.first(),
                maxHeight = trailerMaxHeight,
                alternates = trailerCandidateUrls.drop(1),
            )
        }.onFailure { TvDebugLogger.w("Trailer", "could not resolve a trailer", it) }.getOrNull()
        trailerResolving = false
        val source = resolved?.source
        if (source == null) {
            TvDebugLogger.i("Trailer", "no playable trailer for ${detail?.title.orEmpty()}")
            // Nothing to raise, and nothing said about it: the page is what the viewer came for and
            // it is already in front of them. The action stays, so a second press can try again.
            return@LaunchedEffect
        }
        // A moment with the page to itself before it is taken away.
        //
        // Timed from arriving rather than from the source being ready, and the resolving happens
        // inside it: waiting first and then resolving would have stacked one delay on the other and
        // left the viewer looking at a page they had finished reading. Manual presses skip it —
        // somebody who just asked for the trailer is not waiting four seconds to be shown it.
        if (trailerRequestIsAuto) {
            val elapsed = System.currentTimeMillis() - pageOpenedAtMs
            if (elapsed < AutoTrailerDelayMs) delay(AutoTrailerDelayMs - elapsed)
        }
        trailerSource = source
        trailerRunning = true
        trailerPlayed = true
    }

    // Flipped on the same two edges the page's own fade turns on, so the shell can run the rail and
    // the clock out and back on the same curve rather than blinking them off over a page that is
    // still there.
    DisposableEffect(trailerVisible) {
        setImmersiveContent(trailerVisible)
        onDispose { setImmersiveContent(false) }
    }

    BackHandler(enabled = trailerVisible) { dismissTrailer() }

    /** Every loaded season's episodes as one continuous run — what the episode row actually shows. */
    val episodeEntries = remember(loadedSeasons) {
        loadedSeasons.flatMap { season ->
            season.episodes.map { SeasonEpisodeEntry(season.seasonNumber, it) }
        }
    }
    val selectedEntry = episodeEntries.getOrNull(selectedEpisodeIndex)
    val selectedEpisodeContext = selectedEntry?.episode?.toEpisodeContext(selectedEntry.seasonNumber)
    val activeSeasonDetail = loadedSeasons.firstOrNull { it.seasonNumber == activeSeasonNumber }
    val selectedSeasonWatched = activeSeasonDetail?.episodes
        ?.takeIf { it.isNotEmpty() }
        ?.all { watchedEpisodeKeys.contains(watchedEpisodeKey(activeSeasonNumber, it.episodeNumber)) } == true

    // The run restarts whenever the viewer picks a season from the chips; everything after that
    // season is appended as they reach it.
    LaunchedEffect(detail?.id, anchorSeasonNumber) {
        val currentDetail = detail
        if (currentDetail?.type != "tv") {
            loadedSeasons = emptyList()
            return@LaunchedEffect
        }
        loadedSeasons = emptyList()
        loadingNextSeason = false
        selectedEpisodeIndex = 0
        activeSeasonNumber = anchorSeasonNumber
        loadedSeasons = listOfNotNull(repository.fetchSeason(currentDetail.id, anchorSeasonNumber))
    }

    /**
     * Pulls in the season after the last one loaded.
     *
     * A season that cannot be fetched is still recorded, empty, so the cursor moves past it — the
     * alternative is asking for the same failing season again on every focus move along the row.
     */
    val loadNextSeason: () -> Unit = load@{
        val currentDetail = detail ?: return@load
        if (loadingNextSeason) return@load
        val lastLoaded = loadedSeasons.lastOrNull()?.seasonNumber ?: return@load
        val nextSeasonNumber = currentDetail.seasons
            .map { it.seasonNumber }
            .filter { it > lastLoaded }
            .minOrNull() ?: return@load
        val anchorAtLaunch = anchorSeasonNumber
        loadingNextSeason = true
        scope.launch {
            val season = repository.fetchSeason(currentDetail.id, nextSeasonNumber)
            // Picking a season from the chips while this was in flight starts a different run;
            // appending to it here would splice a season onto the end of the wrong one.
            val stillCurrent = anchorSeasonNumber == anchorAtLaunch &&
                loadedSeasons.lastOrNull()?.seasonNumber == lastLoaded
            if (stillCurrent) {
                loadedSeasons = loadedSeasons + (
                    season ?: SeasonDetail(seasonNumber = nextSeasonNumber, name = "Season $nextSeasonNumber")
                    )
            }
            loadingNextSeason = false
        }
    }

    LaunchedEffect(mediaType, mediaId, selectedEpisodeContext, resumeEpisodeContext) {
        val progressEpisode = if (mediaType == "tv") resumeEpisodeContext ?: selectedEpisodeContext else selectedEpisodeContext
        val progress = repository.fetchProgress(mediaType, mediaId, progressEpisode)
        progressFraction = progress?.progress?.div(100.0)?.toFloat()?.coerceIn(0f, 1f)
        progressLabel = progress?.takeIf { it.positionSec > 0 && it.durationSec > 0 }?.let {
            "${formatTime(it.positionSec)} / ${formatTime(it.durationSec)}"
        }
    }

    LaunchedEffect(mediaType, mediaId, loadedSeasons, detail?.id) {
        val currentDetail = detail
        if (mediaType != "tv" || currentDetail == null) {
            watchedEpisodeKeys = emptySet()
            return@LaunchedEffect
        }
        // Refreshed when a new run starts; appending a season only needs the set already in hand,
        // and the row now appends often enough that asking Trakt each time would be a request per
        // season boundary the viewer scrolls past.
        val watchedKeys = repository.fetchWatchedKeys(forceRefresh = loadedSeasons.size <= 1)
        watchedEpisodeKeys = loadedSeasons.flatMap { season ->
            season.episodes.mapNotNull { episode ->
                watchedEpisodeKey(season.seasonNumber, episode.episodeNumber)
                    .takeIf {
                        watchedKeys.contains(
                            "tv:${currentDetail.id}:s${season.seasonNumber}:e${episode.episodeNumber}",
                        )
                    }
            }
        }.toSet()
    }

    LaunchedEffect(mediaType, mediaId, selectedEpisodeContext, resumeEpisodeContext, progressFraction, detail?.id) {
        val currentDetail = detail ?: return@LaunchedEffect
        markedWatched = repository.isWatched(
            mediaType = mediaType,
            mediaId = mediaId,
            episode = playbackEpisodeContext(currentDetail, progressFraction, resumeEpisodeContext, selectedEpisodeContext),
            forceRefresh = true,
        )
    }

    LaunchedEffect(mediaType, mediaId, detail?.id) {
        if (detail == null) return@LaunchedEffect
        comments = runCatching { repository.fetchTraktComments(mediaId, mediaType) }.getOrDefault(emptyList())
    }

    LaunchedEffect(detail?.backdrop, detail?.poster) {
        val artUrl = detail?.backdrop ?: detail?.poster ?: return@LaunchedEffect
        runCatching { extractAmbientPalette(context, artUrl) }.onSuccess { ambientPalette = it }
    }

    LaunchedEffect(detail?.id, comments) {
        detail ?: return@LaunchedEffect
        delay(220)
        detail.titleLogo?.takeIf { it.isNotBlank() }?.let { logoUrl ->
            context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(logoUrl)
                    .memoryCacheKey(logoUrl)
                    .diskCacheKey(logoUrl)
                    .size(640, 180)
                    .crossfade(false)
                    .allowHardware(true)
                    .allowRgb565(false)
                    .build(),
            )
        }
        buildList {
            detail.poster?.let(::add)
            detail.cast.take(3).forEach { it.photo?.let(::add) }
            detail.similarTitles.take(4).forEach { it.poster?.let(::add) }
        }.distinct().take(8).forEach { url ->
            context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(url).memoryCacheKey(url).diskCacheKey(url)
                    .crossfade(false).allowHardware(true).build(),
            )
        }
    }

    val backgroundColor = MaterialTheme.colorScheme.background

    Box(Modifier.fillMaxSize().background(backgroundColor)) {
        // The page as one layer, so the trailer exchanges with the whole thing — artwork, scrims,
        // hero and rows together — instead of each part fading on its own schedule.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = pageAlpha
                    scaleX = pageScale
                    scaleY = pageScale
                },
        ) {
        detail?.backdrop?.takeIf { it.isNotBlank() }?.let { backdrop ->
            AsyncImage(
                model = backdrop,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        // Two linear passes, tinted by the artwork's own palette. Linear gradients are far cheaper
        // than the radial ones this replaced, which matters when they cover the whole screen.
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val readingScrim = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to backgroundColor.copy(alpha = 0.95f),
                        0.45f to ambientPalette.leftGlow.copy(alpha = 0.55f),
                        1f to Color.Transparent,
                    ),
                    endX = size.width * 0.78f,
                )
                val baseFade = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.52f to backgroundColor.copy(alpha = 0.34f),
                        0.78f to ambientPalette.accentGlow.copy(alpha = 0.40f),
                        1f to backgroundColor.copy(alpha = 0.94f),
                    ),
                )
                onDrawBehind {
                    drawRect(readingScrim)
                    drawRect(baseFade)
                }
            },
        )

        when (val state = uiState) {
            DetailUiState.Loading -> DetailSkeleton()

            is DetailUiState.Error -> DetailError(
                message = state.message,
                onRetry = { reloadToken++ },
                onBack = onBack,
            )

            is DetailUiState.Ready -> {
                val d = state.detail
                // The bands actually on screen, in order. Needed because Episodes only exists for
                // series and any section can be absent, so "the row before this one" cannot be
                // derived from a fixed list.
                val bandIds = remember(d.id, d.seasons.size, d.cast.size, d.similarTitles.size, comments.size) {
                    buildList {
                        if (d.type == "tv" && d.seasons.isNotEmpty()) add("episodes")
                        if (d.cast.isNotEmpty()) add("cast")
                        if (d.similarTitles.isNotEmpty()) add("similar")
                        if (comments.isNotEmpty()) add("comments")
                    }
                }

                // Keep exactly one row of history on screen.
                //
                // The focused row sits second in the viewport with the previous row minified above
                // it, and everything earlier scrolls up under the pinned hero. That gives a sense
                // of place — you can always see where you just came from — without letting three
                // collapsed rows eat the space the focused one needs.
                // Read here rather than inside the effect: the list has to travel over exactly the
                // same span the hero and the bands do, or the row arrives after everything around
                // it has already settled.
                val bandGlideMs = TvMotion.duration(TvMotion.Expand)
                LaunchedEffect(focusedRow, bandIds) {
                    val index = bandIds.indexOf(focusedRow)
                    listState.glideToItem(
                        index = if (index < 0) 0 else (index - 1).coerceAtLeast(0),
                        durationMs = bandGlideMs,
                    )
                }
                // The hero sits outside the scrolling container.
                //
                // Anything inside a lazy list gets scrolled to when it takes focus, and since focus
                // opens on Play that dragged the poster and title clean off the top of the screen.
                // Pinning the hero removes the possibility rather than fighting it: only the
                // sections below can move, and the title is always the first thing on screen.
                val heroTop by androidx.compose.animation.core.animateDpAsState(
                    targetValue = if (heroExpanded) 72.dp else 28.dp,
                    // The same spec the hero's own sizes use, so the whole block settles as one.
                    animationSpec = TvMotion.standardSpec(TvMotion.Expand),
                    label = "hero-top",
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(top = heroTop, start = DetailNavRailClearance)
                        // One group around the whole page, purely so leaving it sideways can be
                        // aimed. Everything inside still moves by ordinary focus search; this is
                        // only consulted when focus is on its way out of the page entirely, which
                        // to the left means the rail — and the rail is drawn on top of the page
                        // rather than beside it, so a search for "something further left" found
                        // nothing to hand it to from anywhere below the hero.
                        .focusGroup()
                        .focusProperties {
                            exit = { direction ->
                                if (direction == FocusDirection.Left && navRailRequester != null) {
                                    navRailRequester
                                } else {
                                    FocusRequester.Default
                                }
                            }
                        },
                ) {
                    DetailHero(
                            compact = !heroExpanded,
                            onFocusChanged = { focused -> if (focused) focusedRow = null },
                            detail = d,
                            selectedEpisode = selectedEpisodeContext,
                            progressFraction = progressFraction,
                            progressLabel = progressLabel,
                            inWatchlist = inWatchlist,
                            markedWatched = markedWatched,
                            playRequester = playRequester,
                            onPlay = {
                                if (repository.currentSession() == null) {
                                    onRequireAuth()
                                } else {
                                    onPlay(
                                        PlaybackRequest(
                                            mediaId = d.id,
                                            mediaType = d.type,
                                            imdbId = d.imdbId,
                                            episode = playbackEpisodeContext(
                                                d, progressFraction, resumeEpisodeContext, selectedEpisodeContext,
                                            ),
                                            title = d.title,
                                        ),
                                    )
                                }
                            },
                            onToggleWatchlist = {
                                scope.launch {
                                    val item = MediaItem(
                                        id = d.id, tmdbId = d.tmdbId, title = d.title, type = d.type,
                                        poster = d.poster, backdrop = d.backdrop, description = d.description,
                                        rating = d.rating, year = d.year,
                                    )
                                    // Flipped first so the press registers immediately; a remote
                                    // press that appears to do nothing gets pressed again.
                                    inWatchlist = !inWatchlist
                                    if (inWatchlist) repository.addToWatchlist(item) else repository.removeFromWatchlist(item)
                                }
                            },
                            onMarkWatched = {
                                if (repository.currentSession() == null) {
                                    onRequireAuth()
                                } else {
                                    scope.launch {
                                        val episodeContext = if (d.type == "tv") {
                                            selectedEpisodeContext
                                        } else {
                                            playbackEpisodeContext(d, progressFraction, resumeEpisodeContext, selectedEpisodeContext)
                                        }
                                        val ok = repository.markWatched(
                                            mediaType = d.type, mediaId = d.id, imdbId = d.imdbId,
                                            title = d.title, year = d.year, episode = episodeContext,
                                        )
                                        if (ok) {
                                            markedWatched = true
                                            repository.clearProgress(d.type, d.id, episodeContext)
                                            progressFraction = null
                                            progressLabel = null
                                            episodeContext?.takeIf { d.type == "tv" }?.let {
                                                watchedEpisodeKeys = watchedEpisodeKeys +
                                                    watchedEpisodeKey(it.seasonNumber, it.episodeNumber)
                                            }
                                        }
                                    }
                                }
                            },
                            synopsisMoreRequester = synopsisMoreRequester,
                            entryRequester = entryFocusRequester,
                            heroIsRestorePoint = heroExpanded,
                            onExpandSynopsis = { expandedSynopsis = it },
                            hasTrailer = hasTrailer,
                            trailerPlayed = trailerPlayed,
                            trailerLoading = trailerResolving,
                            onPlayTrailer = {
                                if (!trailerResolving) {
                                    trailerRequestIsAuto = false
                                    trailerRequest += 1
                                }
                            },
                        )

                        Spacer(Modifier.height(26.dp))

                        // The bands position this list themselves, so the focus system must not
                        // also move it — that second scroll is the visible double-step.
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides SuppressBringIntoView,
                        ) {
                        LazyColumn(
                            state = listState,
                            // weight, not fillMaxSize: as the second child of a Column, filling
                            // the parent means the list claims the full screen height starting
                            // below the hero, so its viewport hangs off the bottom and the rows
                            // down there can never be scrolled into view. weight gives it exactly
                            // the room the hero left over.
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 72.dp),
                            verticalArrangement = Arrangement.spacedBy(28.dp),
                        ) {
                    if (d.type == "tv" && d.seasons.isNotEmpty()) {
                        item("episodes") {
                            Box(Modifier.bandRestorePoint(focusedRow == "episodes", entryFocusRequester)) {
                            EpisodesBand(
                                compact = focusedRow != null && focusedRow != "episodes",
                                onFocusChanged = { if (it) focusedRow = "episodes" },
                                seasons = d.seasons,
                                activeSeasonNumber = activeSeasonNumber,
                                entries = episodeEntries,
                                rowFocusKey = "${d.id}:$anchorSeasonNumber",
                                loadingNextSeason = loadingNextSeason,
                                isEpisodeWatched = { entry ->
                                    watchedEpisodeKeys.contains(
                                        watchedEpisodeKey(entry.seasonNumber, entry.episode.episodeNumber),
                                    )
                                },
                                seasonWatched = selectedSeasonWatched,
                                markingSeason = markingSeasonWatched,
                                firstChipRequester = seasonChipRequester,
                                upRequester = playRequester,
                                onSelectSeason = {
                                    if (anchorSeasonNumber != it) {
                                        anchorSeasonNumber = it
                                    }
                                },
                                onMarkSeasonWatched = markSeason@{
                                    val season = activeSeasonDetail ?: return@markSeason
                                    if (markingSeasonWatched || selectedSeasonWatched) return@markSeason
                                    markingSeasonWatched = true
                                    scope.launch {
                                        val marked = repository.markSeasonWatched(
                                            mediaId = d.id,
                                            title = d.title,
                                            year = d.year,
                                            seasonNumber = season.seasonNumber,
                                        )
                                        if (marked) {
                                            watchedEpisodeKeys = watchedEpisodeKeys + season.episodes.map {
                                                watchedEpisodeKey(season.seasonNumber, it.episodeNumber)
                                            }
                                        }
                                        markingSeasonWatched = false
                                    }
                                },
                                onEpisodeFocused = { index, entry ->
                                    selectedEpisodeIndex = index
                                    // The chips follow the row across a season boundary.
                                    if (entry.seasonNumber != activeSeasonNumber) {
                                        activeSeasonNumber = entry.seasonNumber
                                    }
                                    // Fetch the next season before the row runs out, so travelling
                                    // right never stops at the end of a season.
                                    if (index >= episodeEntries.size - 3) loadNextSeason()
                                },
                                onEpisodePressed = { entry ->
                                    if (repository.currentSession() == null) {
                                        onRequireAuth()
                                    } else {
                                        onPlay(
                                            PlaybackRequest(
                                                mediaId = d.id, mediaType = d.type, imdbId = d.imdbId,
                                                episode = entry.episode.toEpisodeContext(entry.seasonNumber),
                                                title = d.title,
                                            ),
                                        )
                                    }
                                },
                                onEpisodeMenu = { entry ->
                                    episodeActionError = null
                                    episodeAction = entry
                                },
                            )
                            }
                        }
                    }

                    if (d.cast.isNotEmpty()) {
                        item("cast") {
                            Box(Modifier.bandRestorePoint(focusedRow == "cast", entryFocusRequester)) {
                            CastBand(
                                cast = d.cast,
                                compact = focusedRow != null && focusedRow != "cast",
                                onFocusChanged = { if (it) focusedRow = "cast" },
                            )
                            }
                        }
                    }

                    if (d.similarTitles.isNotEmpty()) {
                        item("similar") {
                            Box(Modifier.bandRestorePoint(focusedRow == "similar", entryFocusRequester)) {
                            SimilarBand(
                                items = d.similarTitles,
                                compact = focusedRow != null && focusedRow != "similar",
                                onFocusChanged = { if (it) focusedRow = "similar" },
                                onOpen = { onOpenDetail(it.type, it.detailLookupId()) },
                            )
                            }
                        }
                    }

                    if (comments.isNotEmpty()) {
                        item("comments") {
                            Box(Modifier.bandRestorePoint(focusedRow == "comments", entryFocusRequester)) {
                            CommentsBand(
                                comments = comments,
                                compact = focusedRow != null && focusedRow != "comments",
                                onFocusChanged = { if (it) focusedRow = "comments" },
                            )
                            }
                        }
                    }
                        }
                }
            }
        }
        }
        }

        trailerSource?.let { source ->
            TrailerStage(
                source = source,
                maxHeight = trailerMaxHeight,
                active = trailerRunning,
                focusRequester = trailerRequester,
                onEnded = { dismissTrailer() },
                onFailed = { dismissTrailer() },
                onBack = { dismissTrailer() },
                modifier = Modifier.graphicsLayer { alpha = trailerStageAlpha },
            )
        }

        episodeAction?.let { entry ->
            val currentDetail = detail
            if (currentDetail != null) {
                EpisodeActionDialog(
                    episode = entry.episode,
                    seasonNumber = entry.seasonNumber,
                    watched = watchedEpisodeKeys.contains(
                        watchedEpisodeKey(entry.seasonNumber, entry.episode.episodeNumber),
                    ),
                    loading = episodeActionLoading,
                    error = episodeActionError,
                    onDismiss = {
                        if (!episodeActionLoading) {
                            episodeAction = null
                            episodeActionError = null
                        }
                    },
                    onMarkWatched = {
                        if (!episodeActionLoading) {
                            episodeActionLoading = true
                            episodeActionError = null
                            scope.launch {
                                val context = entry.episode.toEpisodeContext(entry.seasonNumber)
                                val marked = repository.markWatched(
                                    mediaType = "tv",
                                    mediaId = currentDetail.id,
                                    title = currentDetail.title,
                                    year = currentDetail.year,
                                    episode = context,
                                    imdbId = currentDetail.imdbId,
                                )
                                if (marked) {
                                    repository.clearProgress("tv", currentDetail.id, context)
                                    watchedEpisodeKeys = watchedEpisodeKeys +
                                        watchedEpisodeKey(entry.seasonNumber, entry.episode.episodeNumber)
                                    episodeAction = null
                                } else {
                                    episodeActionError = "Could not mark this episode watched."
                                }
                                episodeActionLoading = false
                            }
                        }
                    },
                )
            }
        }

        expandedSynopsis?.let { synopsis ->
            com.streamdek.tv.nativeapp.ui.TvSynopsisDialog(
                title = detail?.title.orEmpty(),
                subtitle = selectedEpisodeContext?.let { episode ->
                    "S${episode.seasonNumber} E${episode.episodeNumber}" +
                        (episode.title?.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: "")
                },
                synopsis = synopsis,
                onDismiss = {
                    expandedSynopsis = null
                    scope.launch {
                        delay(40)
                        runCatching { synopsisMoreRequester.requestFocus() }
                    }
                },
            )
        }
    }
}

/** Poster, copy and actions as one band: everything needed to decide and press play. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun DetailHero(
    detail: MediaDetail,
    compact: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    selectedEpisode: EpisodeContext?,
    progressFraction: Float?,
    progressLabel: String?,
    inWatchlist: Boolean,
    markedWatched: Boolean,
    playRequester: FocusRequester,
    onPlay: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onMarkWatched: () -> Unit,
    synopsisMoreRequester: FocusRequester,
    /** The shell's handle on this page, attached to Play alongside the page's own requester. */
    entryRequester: FocusRequester,
    onExpandSynopsis: (String) -> Unit,
    /** True while no band below has claimed [entryRequester]. */
    heroIsRestorePoint: Boolean,
    hasTrailer: Boolean,
    trailerPlayed: Boolean,
    trailerLoading: Boolean,
    onPlayTrailer: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val titleLogoRequest = remember(detail.titleLogo) {
        detail.titleLogo?.takeIf { it.isNotBlank() }?.let { logoUrl ->
            ImageRequest.Builder(context)
                .data(logoUrl)
                .memoryCacheKey(logoUrl)
                .diskCacheKey(logoUrl)
                .size(640, 180)
                .crossfade(90)
                .allowHardware(true)
                .allowRgb565(false)
                .build()
        }
    }
    // Poster, logo and spacing all collapse together as the hero compacts, so they share one spec —
    // three sizes easing on different curves is what made the compaction read as a stutter.
    val heroTween = TvMotion.standardSpec<Dp>(TvMotion.Expand)
    /**
     * The poster's collapse, as one GPU transform.
     *
     * It used to be dropped out of the layout on the frame focus left the hero: 188dp of the row
     * gone at once while the logo, the spacing and the bands below were all still easing. That
     * single snap in the middle of four eased values is what the whole movement read as. Scaling
     * leaves the image measured at full size — nothing about it is re-laid-out or re-decoded on the
     * way down — while the box holding it gives up its width and height on the shared curve.
     */
    val posterScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (compact) 0f else 1f,
        animationSpec = TvMotion.standardSpec(TvMotion.Expand),
        label = "hero-poster",
    )
    val heroGap by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (compact) 20.dp else 30.dp,
        animationSpec = heroTween,
        label = "hero-gap",
    )
    val logoHeight by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (compact) 44.dp else 74.dp,
        animationSpec = heroTween,
        label = "hero-logo",
    )
    val heroSpacing by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (compact) 10.dp else 14.dp,
        animationSpec = heroTween,
        label = "hero-spacing",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Coming back up from the bands below lands on Play every time, rather than on
            // whichever secondary icon happened to be nearest the column the viewer came from.
            //
            // focusGroup is what makes that true: `enter` is only consulted for a group, so without
            // it this was an ordinary container and the redirect never ran.
            .focusGroup()
            .focusProperties { enter = { playRequester } }
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .padding(horizontal = DetailInset),
        horizontalArrangement = Arrangement.spacedBy(heroGap),
    ) {
        // The poster is the first thing to go when focus moves below: it is the largest element
        // and, once the viewer is browsing recommendations, the least useful.
        if (posterScale > 0.001f) {
            Box(
                Modifier
                    .width(HeroPosterWidth * posterScale)
                    .height(HeroPosterHeight * posterScale)
                    .clipToBounds(),
            ) {
                HeroPoster(
                    detail = detail,
                    modifier = Modifier
                        .requiredSize(HeroPosterWidth, HeroPosterHeight)
                        .graphicsLayer {
                            scaleX = posterScale
                            scaleY = posterScale
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        },
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(heroSpacing),
        ) {
            if (titleLogoRequest != null) {
                AsyncImage(
                    model = titleLogoRequest,
                    contentDescription = detail.title,
                    modifier = Modifier.height(logoHeight),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    text = detail.title,
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HeroDetailVisibility(visible = !compact) {
                detail.tagline?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.90f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                detail.rating?.takeIf { it > 0 }?.let { MetaChip("★ %.1f".format(it), emphasised = true) }
                detail.year?.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
                detail.runtime?.takeIf { it > 0 }?.let { MetaChip(formatRuntime(it)) }
                detail.numberOfSeasons?.takeIf { it > 0 }?.let {
                    MetaChip(if (it == 1) "1 season" else "$it seasons")
                }
                detail.genreNames.take(2).forEach { MetaChip(it) }
            }

            selectedEpisode?.let { episode ->
                Text(
                    text = "S${episode.seasonNumber} E${episode.episodeNumber}" +
                        (episode.title?.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: ""),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HeroDetailVisibility(visible = !compact) {
                detail.description?.takeIf { it.isNotBlank() }?.let { description ->
                    // Whether the three-line clamp actually cut anything, measured rather than
                    // guessed from length — the same synopsis wraps differently per title.
                    var truncated by remember(description) { mutableStateOf(false) }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.80f),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(720.dp),
                            onTextLayout = { truncated = it.hasVisualOverflow },
                        )
                        if (truncated) {
                            com.streamdek.tv.nativeapp.ui.TvMoreButton(
                                onClick = { onExpandSynopsis(description) },
                                modifier = Modifier.focusRequester(synopsisMoreRequester),
                            )
                        }
                    }
                }
            }

            HeroDetailVisibility(visible = !compact) {
                progressFraction?.takeIf { it > 0f }?.let { ResumeProgress(it, progressLabel) }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onPlay,
                    // Roughly a third wider than the label needs, and 15% shorter than it was. It
                    // is the one action that matters on this screen and should read as the primary
                    // target rather than the first of five equal buttons.
                    modifier = Modifier
                        .focusRequester(playRequester)
                        // Only while no band below has claimed it. A requester attached in two
                        // places at once resolves to whichever node it finds first, which would
                        // put the rail's exit back at the top of the page half the time.
                        .then(if (heroIsRestorePoint) Modifier.focusRequester(entryRequester) else Modifier)
                        .height(41.dp)
                        .width(208.dp),
                    shape = ButtonDefaults.shape(AppPillShape),
                    // Stays white in every state. Tinting it with the theme accent on focus made
                    // the primary action change identity as the highlight landed on it; a slight
                    // step to off-white reads as "selected" without that.
                    colors = ButtonDefaults.colors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF101013),
                        focusedContainerColor = Color(0xFFEDEDEA),
                        focusedContentColor = Color(0xFF101013),
                        pressedContainerColor = Color(0xFFE2E2DF),
                        pressedContentColor = Color(0xFF101013),
                    ),
                ) {
                    Text(
                        text = if ((progressFraction ?: 0f) > 0f) {
                            progressLabel?.substringBefore(" / ")?.takeIf { it.isNotBlank() }?.let { "Resume · $it" } ?: "Resume"
                        } else {
                            "Play"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    )
                }
                // Two secondary actions, both about this title's place in the viewer's own library.
                // Trailer and share used to sit here too and were removed: a trailer opened an
                // external app the TV may not have, and there is nothing on a TV to share to.
                HeroAction(
                    icon = if (inWatchlist) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    label = if (inWatchlist) "Remove from watchlist" else "Add to watchlist",
                    active = inWatchlist,
                    onClick = onToggleWatchlist,
                )
                HeroAction(
                    icon = if (markedWatched) Icons.Rounded.CheckCircle else Icons.Rounded.CheckCircleOutline,
                    label = if (markedWatched) "Watched" else "Mark as watched",
                    active = markedWatched,
                    onClick = onMarkWatched,
                )
                // Last in the row, and only when the title actually has one. It is how a trailer is
                // seen again after Back, and the only way to see it at all with auto-play off —
                // which is why it is here before the trailer has run rather than appearing only
                // afterwards.
                //
                // Written out rather than left as an icon like its neighbours. Watchlist and
                // watched are bookmark and tick, which everyone reads at a glance; there is no
                // glyph for "trailer" that does the same — a film clapper reads as "play the film"
                // and a circular arrow reads as "watch this again", which is the opposite of what
                // this does.
                if (hasTrailer) {
                    HeroTrailerAction(
                        label = when {
                            trailerLoading -> "Loading trailer…"
                            trailerPlayed -> "Replay trailer"
                            else -> "Watch trailer"
                        },
                        loading = trailerLoading,
                        onClick = onPlayTrailer,
                    )
                }
            }
        }
    }
}


/**
 * Fades and collapses the parts of the hero that only exist at full size.
 *
 * Dropping them from the composition outright made the hero snap between two layouts; expanding
 * and shrinking the height means the rows below slide rather than jump.
 */
@Composable
private fun HeroDetailVisibility(visible: Boolean, content: @Composable () -> Unit) {
    // Both directions run the length of the hero's own collapse, on the hero's own curve. Leaving
    // the exit at the shorter dismissal timing meant the synopsis was gone while the poster beside
    // it and the bands below were still a third of the way through moving — the fast half of a
    // movement that is otherwise one piece.
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = TvMotion.fadeInSpec(TvMotion.Expand) +
            androidx.compose.animation.expandVertically(TvMotion.standardSpec(TvMotion.Expand)),
        exit = androidx.compose.animation.fadeOut(TvMotion.standardSpec(TvMotion.Expand)) +
            androidx.compose.animation.shrinkVertically(TvMotion.standardSpec(TvMotion.Expand)),
    ) {
        content()
    }
}

/**
 * The trailer action, as a pill with its name on it.
 *
 * The icon row beside it works because a bookmark and a tick are understood without being read.
 * "Trailer" has no such glyph, and the two candidates both mislead: a clapperboard reads as playing
 * the film and a circular arrow as watching it again. Four words of outline pill cost a little room
 * at the end of a row that has it to spare, and are unambiguous from the back of a room.
 */
@Composable
private fun HeroTrailerAction(
    label: String,
    loading: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .height(41.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = ButtonDefaults.shape(AppPillShape),
        scale = ButtonDefaults.scale(focusedScale = TvMotion.focusScale()),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.86f),
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = Color(0xFF101013),
        ),
        border = ButtonDefaults.border(
            border = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.onBackground.copy(alpha = if (loading) 0.55f else 0.34f),
                ),
                shape = AppPillShape,
            ),
            focusedBorder = androidx.tv.material3.Border.None,
        ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Movie,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (focused) Color(0xFF101013) else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HeroAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // Two separate motions, deliberately on different curves.
    //
    // Focus growth is a response to the remote and eases straight to its target — an overshoot
    // there would have the icon wobbling every time focus passed over it, which reads as the row
    // being unsteady rather than as anything having happened.
    val focusSize by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (focused) 28.dp else 24.dp,
        animationSpec = TvMotion.instantSpec(),
        label = "hero-action-size",
    )
    // Toggling is a change the viewer just caused and wants confirmed, so this one rebounds. It is
    // the only place in the row where the state, not the focus, moved.
    val activeScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (active) 1f else 0.86f,
        animationSpec = TvMotion.emphasisSpec(),
        label = "hero-action-active",
    )
    // Colour alone carries focus here. Outlined circles next to the Play pill read as a row of
    // empty buttons and pull attention off the one action that matters, and a background plate is
    // the same problem in softer form. The accent tint is unmistakable from across a room.
    Box(
        modifier = Modifier
            .size(48.dp)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = label }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
                .size(focusSize)
                .graphicsLayer {
                    scaleX = activeScale
                    scaleY = activeScale
                },
            tint = when {
                focused -> MaterialTheme.colorScheme.primary
                active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.88f)
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            },
        )
    }
}

/**
 * Failure state with somewhere to go. The screen this replaced showed text and nothing focusable,
 * which on a remote is a dead end.
 */
@Composable
internal fun DetailError(message: String, onRetry: (() -> Unit)? = null, onBack: (() -> Unit)? = null) {
    val retryRequester = remember { FocusRequester() }

    LaunchedEffect(message) {
        delay(120)
        runCatching { retryRequester.requestFocus() }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(DetailInset),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Could not load this title",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.74f),
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(
            modifier = Modifier.padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            onRetry?.let {
                Button(
                    onClick = it,
                    modifier = Modifier.focusRequester(retryRequester),
                    shape = ButtonDefaults.shape(AppPillShape),
                ) { Text("Try Again") }
            }
            onBack?.let {
                OutlinedButton(onClick = it, shape = ButtonDefaults.shape(AppPillShape)) { Text("Go Back") }
            }
        }
    }
}

@Composable
private fun EpisodeActionDialog(
    episode: SeasonEpisode,
    seasonNumber: Int,
    watched: Boolean,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onMarkWatched: () -> Unit,
) {
    val actionRequester = remember { FocusRequester() }

    LaunchedEffect(episode.id, watched) {
        delay(80)
        runCatching { actionRequester.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .background(MaterialTheme.colorScheme.surface, AppCardShape)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "S${seasonNumber} E${episode.episodeNumber} - ${episode.name}",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    watched -> "This episode is already marked watched."
                    loading -> "Updating your watched history..."
                    else -> "Choose an action for this episode."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
            )
            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFFB4AB))
            }
            Button(
                onClick = onMarkWatched,
                enabled = !watched && !loading,
                modifier = Modifier.fillMaxWidth().focusRequester(actionRequester),
                shape = ButtonDefaults.shape(AppPillShape),
            ) {
                Text(if (watched) "Episode watched" else if (loading) "Marking..." else "Mark episode watched")
            }
            OutlinedButton(
                onClick = onDismiss,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
                shape = ButtonDefaults.shape(AppPillShape),
            ) { Text("Close") }
        }
    }
}
/** Kept for the streams screen, which shares this screen's loading language. */
@Composable
internal fun DetailLoading(label: String = "Loading") {
    Box(Modifier.fillMaxSize().padding(DetailInset), contentAlignment = Alignment.CenterStart) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.size(4.dp))
        }
    }
}
