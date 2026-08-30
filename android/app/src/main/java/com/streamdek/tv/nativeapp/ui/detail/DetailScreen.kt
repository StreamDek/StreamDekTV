package com.streamdek.tv.nativeapp.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as episodeGridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import com.streamdek.tv.nativeapp.data.MaxTrailerDelaySeconds
import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.PlaybackRequest
import com.streamdek.tv.nativeapp.data.SeasonDetail
import com.streamdek.tv.nativeapp.data.SeasonEpisode
import com.streamdek.tv.nativeapp.data.SeriesEpisodeSlot
import com.streamdek.tv.nativeapp.data.buildEpisodeRanges
import com.streamdek.tv.nativeapp.data.episodeRangeIndexFor
import com.streamdek.tv.nativeapp.data.focusEpisodeNumber
import com.streamdek.tv.nativeapp.data.nextUnwatchedEpisodeNumber
import com.streamdek.tv.nativeapp.data.resolveJumpTarget
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.TraktCommentItem
import com.streamdek.tv.nativeapp.data.TrailerPlaybackSource
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import com.streamdek.tv.nativeapp.data.resolveTrailerPlaybackSource
import com.streamdek.tv.nativeapp.data.youtubeTrailerKey
import com.streamdek.tv.nativeapp.data.TrailerResetSignal
import com.streamdek.tv.nativeapp.data.kinocheckTrailerKey
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.LocalImmersiveContent
import com.streamdek.tv.nativeapp.ui.LocalSideNavOwnsFocus
import com.streamdek.tv.nativeapp.ui.requestFocusOrFalse
import com.streamdek.tv.nativeapp.ui.TvNavRailInset
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.SuppressBringIntoView
import com.streamdek.tv.nativeapp.ui.glideToItem
import com.streamdek.tv.nativeapp.ui.TvMotion
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
/** How long the title's own card holds the screen before the trailer starts. */
private const val TrailerIntroMs = 1_000L

/**
 * How long the card stays up once the trailer is behind it.
 *
 * The stage has to attach a surface, buffer and decode before there is a frame to show. Clearing
 * the card on the same tick showed black through the gap, which is the thing the card exists to
 * prevent — and at 300ms it was still doing it on a cold start. Six hundred covers it, and costs
 * nothing visible: the trailer's audio has already started, so the picture arriving under a
 * dissolving card reads as the card getting out of the way rather than as waiting.
 */
private const val TrailerIntroHandoverMs = 600L

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
    onContentReady: () -> Unit,
    onOpenPerson: (String) -> Unit,
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
    var resumeTargetSlot by remember(mediaType, mediaId) { mutableStateOf<SeriesEpisodeSlot?>(null) }
    var progressFraction by remember(mediaType, mediaId) { mutableStateOf<Float?>(null) }
    var progressLabel by remember(mediaType, mediaId) { mutableStateOf<String?>(null) }
    var inWatchlist by remember(mediaType, mediaId) { mutableStateOf(false) }
    var markedWatched by remember(mediaType, mediaId) { mutableStateOf(false) }
    /** Watched episodes across every loaded season, keyed by [watchedEpisodeKey]. */
    var watchedEpisodeKeys by remember(mediaType, mediaId) { mutableStateOf<Set<String>>(emptySet()) }
    var suppressRemoteWatchedRefreshUntil by remember(mediaType, mediaId) { mutableStateOf(0L) }
    var markingSeasonWatched by remember(mediaType, mediaId, activeSeasonNumber) { mutableStateOf(false) }
    var comments by remember(mediaType, mediaId) { mutableStateOf<List<TraktCommentItem>>(emptyList()) }
    var episodeAction by remember(mediaType, mediaId) { mutableStateOf<SeasonEpisodeEntry?>(null) }
    var episodeActionLoading by remember(mediaType, mediaId) { mutableStateOf(false) }
    var episodeActionError by remember(mediaType, mediaId) { mutableStateOf<String?>(null) }
    /** Full synopsis the viewer asked to read, shown over the screen until they close it. */
    var expandedSynopsis by remember(mediaType, mediaId) { mutableStateOf<String?>(null) }
    var similarArtworkReady by remember(mediaType, mediaId) { mutableStateOf(false) }
    val similarFadeInMillis = TvMotion.duration(TvMotion.Standard)
    val similarFadeOutMillis = TvMotion.duration(TvMotion.Quick)

    /**
     * The trailer, from asking for one to it being on screen.
     *
     * [trailerRequest] is bumped to ask; resolving runs off it and lands in [trailerSource], and a
     * source is what actually raises the trailer — a page whose content vanished the moment the
     * viewer pressed the button, and then sat on black for the several seconds YouTube takes to
     * answer, would read as the app having crashed. Nothing moves until there is something to show.
     */
    var trailerRequest by remember(mediaType, mediaId) { mutableIntStateOf(0) }
    var trailerPlayback by remember(mediaType, mediaId) { mutableStateOf<TrailerPlayback?>(null) }
    /**
     * Set when neither route to the trailer worked, and said on the button.
     *
     * This used to be a log line and nothing else: the press did nothing, no reason was given, and
     * the only reading available to the viewer was that the app was broken. It is cleared by the
     * next request, so the action is still worth pressing again — whatever YouTube refused this
     * minute it may well serve the next.
     */
    var trailerUnavailable by remember(mediaType, mediaId) { mutableStateOf(false) }
    /** Seconds left of the wait, or null when nothing is being waited for. Drives the action's label. */
    var trailerCountdown by remember(mediaType, mediaId) { mutableStateOf<Int?>(null) }
    /** The title's own card, held between the page leaving and the trailer starting. */
    var trailerIntroVisible by remember(mediaType, mediaId) { mutableStateOf(false) }
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
    val sideNavOwnsFocus = LocalSideNavOwnsFocus.current

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
        resumeTargetSlot = null
        runCatching { repository.refreshBootstrap() }

        val libraryDeferred = supervisorScope { async { runCatching { repository.fetchLibrary() }.getOrNull() } }
        val detail = repository.fetchDetail(mediaId, mediaType, forceRefresh = reloadToken > 0)
        if (detail == null) {
            if (existingDetail == null) uiState = DetailUiState.Error("Could not load title details")
            onContentReady()
            return@LaunchedEffect
        }
        val seriesResume = if (detail.type == "tv") {
            runCatching { repository.fetchSeriesResumeState(detail) }.getOrNull()
        } else null
        seriesResume?.let { state ->
            watchedEpisodeKeys = state.watchedEpisodeKeys
            resumeTargetSlot = state.target
            resumeEpisodeContext = state.target?.let { EpisodeContext(it.seasonNumber, it.episodeNumber) }
            state.target?.let { target ->
                anchorSeasonNumber = target.seasonNumber
                activeSeasonNumber = target.seasonNumber
            }
        }
        uiState = DetailUiState.Ready(detail)
        onContentReady()
        if (detail.seasons.none { it.seasonNumber == anchorSeasonNumber }) {
            anchorSeasonNumber = detail.seasons.firstOrNull()?.seasonNumber ?: 1
            activeSeasonNumber = anchorSeasonNumber
            selectedEpisodeIndex = 0
        }
        libraryDeferred.await()?.let { library ->
            inWatchlist = repository.isInWatchlist(
                MediaItem(id = mediaId, tmdbId = detail.tmdbId, title = detail.title, type = mediaType),
            )
            if (resumeEpisodeContext == null) {
                resumeEpisodeContext = library.continueWatching
                    .firstOrNull { it.id == mediaId && it.type == mediaType }?.episode
            }
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
        // Unless the viewer has reached for the menu while the page was loading. Only one region
        // owns the D-pad, and taking the highlight back off them here is what read as the drawer
        // closing by itself.
        if (sideNavOwnsFocus) return@LaunchedEffect
        playRequester.requestFocusOrFalse()
    }

    val bootstrap by repository.bootstrap.collectAsState()
    val detailPrefs = bootstrap?.preferences?.detail ?: DetailPreferences()
    // Clamped on the way in, the way the phone clamps it: the resolver would coerce this itself,
    // but the player's track selector is handed the same figure and a synced value from a client
    // that allowed something odd should not reach it unchecked.
    val trailerMaxHeight = detailPrefs.heroTrailerResolution.coerceIn(360, 2160)
    // Clamped on the way in as well as on the way out: this value also arrives from the phone and
    // the web portal, and a page that sat still for a stored minute would read as a page that had
    // simply stopped working.
    val autoTrailerDelayMs = detailPrefs.heroTrailerDelaySeconds
        .coerceIn(0, MaxTrailerDelaySeconds) * 1_000L

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
    // The card counts as the trailer having arrived, so the page runs its existing leaving movement
    // underneath it rather than being cut away by an opaque slab dropped on top.
    val trailerVisible = (trailerPlayback != null && trailerRunning) || trailerIntroVisible

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
        finishedListener = { progress -> if (progress <= 0.001f) trailerPlayback = null },
        label = "trailer-transition",
    )
    val pageAlpha = (1f - trailerTransition * 2f).coerceIn(0f, 1f)
    val trailerStageAlpha = ((trailerTransition - 0.5f) * 2f).coerceIn(0f, 1f)
    /**
     * The card's own fade, on its own curve in each direction.
     *
     * Arriving, it decelerates into place as the page recedes — the two halves of one movement. It
     * leaves more slowly than it came, and over a trailer that is already playing at full opacity
     * behind it, so the reveal is a dissolve into moving picture rather than a cut to it.
     */
    val trailerIntroAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (trailerIntroVisible) 1f else 0f,
        animationSpec = if (trailerIntroVisible) {
            TvMotion.enterSpec(TvMotion.Expand)
        } else {
            TvMotion.standardSpec(TvMotion.Expand + 160)
        },
        label = "trailer-intro",
    )
    // A little way back into the screen as it goes, so the page reads as making room rather than
    // simply dimming.
    val pageScale = 1f - 0.04f * (1f - pageAlpha)

    fun dismissTrailer() {
        if (!trailerRunning && !trailerIntroVisible) return
        // Cleared first: it is also how the raising sequence learns it has been called off, so a
        // Back pressed during the card does not have the trailer arrive a second later anyway.
        trailerIntroVisible = false
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

    // The wait, counted down on the action rather than spent in silence.
    //
    // A page that is about to take itself away should say so: without this the trailer arrives out
    // of a page the viewer is still reading, and the only warning was that it had happened before.
    // It runs off the same clock the wait itself uses, so the number on the button is the truth
    // rather than a second timer that can drift away from it.
    LaunchedEffect(hasTrailer, detailPrefs.heroTrailerAutoplay, autoTrailerDelayMs, trailerPlayed) {
        if (!hasTrailer || !detailPrefs.heroTrailerAutoplay || trailerPlayed || autoTrailerDelayMs <= 0L) {
            trailerCountdown = null
            return@LaunchedEffect
        }
        while (true) {
            val remaining = autoTrailerDelayMs - (System.currentTimeMillis() - pageOpenedAtMs)
            if (remaining <= 0L) break
            // Rounded up, so a wait of three seconds opens on "3" rather than flicking past it.
            trailerCountdown = ((remaining + 999L) / 1000L).toInt()
            delay(200)
        }
        trailerCountdown = null
    }

    // Re-resolves when trailer state is cleared, so a viewer who cleared the cache to fix
    // trailers is not left looking at the fallback the broken pipeline settled on.
    val trailerResetToken = TrailerResetSignal.current()
    LaunchedEffect(trailerRequest, trailerResetToken) {
        if (trailerRequest == 0 || trailerCandidateUrls.isEmpty()) return@LaunchedEffect
        trailerUnavailable = false
        trailerResolving = true
        // Resolved once and used twice: by the extractor as its preferred video, and by the embed
        // fallback below when extraction comes back with nothing.
        // Keyed on tmdbId rather than id: this screen's `id` is the catalogue's own identifier,
        // which for an add-on item is not a TMDB number at all.
        val kinocheckKey = detail?.let { current ->
            val tmdbId = current.tmdbId.takeIf { it > 0 }?.toString() ?: current.id
            kinocheckTrailerKey(tmdbId, current.type, context)?.let { "https://www.youtube.com/watch?v=$it" }
        }
        val resolved = runCatching {
            resolveTrailerPlaybackSource(
                url = trailerCandidateUrls.first(),
                maxHeight = trailerMaxHeight,
                alternates = trailerCandidateUrls.drop(1),
                // KinoCheck's curated pick, tried on its own before the metadata service's list.
                // Their list is every video a studio published, which around a release is a wall of
                // ticket adverts; this is one video, marked as the trailer.
                preferredUrl = kinocheckKey,
            )
        }.onFailure { TvDebugLogger.w("Trailer", "could not resolve a trailer", it) }.getOrNull()
        trailerResolving = false
        // Leaving the page cancels the resolve, and runCatching treats that cancellation as an
        // ordinary failure — so without this the embed is raised for a screen the viewer has
        // already walked away from, and the log claims YouTube refused something it never answered.
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        // A file if the resolver got one, and YouTube's own embed for the same video if it did not.
        //
        // The refusal being fallen back from is usually not about this title at all: the player API
        // answers "sign in to confirm you're not a bot" to whole networks at a time, and the embed
        // is unaffected by it because it is the published interface rather than an internal one. It
        // also carries the age-gated trailers extraction has never been able to reach.
        // KinoCheck's pick leads the embed too, not just the extraction.
        //
        // The embed was handed the head of `trailerCandidateUrls` — the metadata service's own
        // order, which is roughly newest first, and around a release that is a wall of ticket
        // adverts. So every time extraction was refused the fallback played a promo sting, and the
        // one video actually marked as the trailer was never seen. The embed is the path that
        // survives the bot wall, which makes it exactly the path that most needs the right video.
        val embedCandidates = listOfNotNull(kinocheckKey) + trailerCandidateUrls
        val playback = resolved?.source?.let(TrailerPlayback::Native)
            ?: embedCandidates.firstNotNullOfOrNull { youtubeTrailerKey(it) }?.let(TrailerPlayback::Embed)
        if (playback == null) {
            TvDebugLogger.i("Trailer", "no playable trailer for ${detail?.title.orEmpty()}")
            trailerUnavailable = true
            return@LaunchedEffect
        }
        if (playback is TrailerPlayback.Embed) {
            TvDebugLogger.i("Trailer", "player API gave nothing for ${detail?.title.orEmpty()}; using the embed")
        }
        // A moment with the page to itself before it is taken away.
        //
        // Timed from arriving rather than from the source being ready, and the resolving happens
        // inside it: waiting first and then resolving would have stacked one delay on the other and
        // left the viewer looking at a page they had finished reading. Manual presses skip it —
        // somebody who just asked for the trailer is not waiting four seconds to be shown it.
        if (trailerRequestIsAuto) {
            val elapsed = System.currentTimeMillis() - pageOpenedAtMs
            if (elapsed < autoTrailerDelayMs) delay(autoTrailerDelayMs - elapsed)
        }
        trailerCountdown = null
        // Three overlapping stages rather than three steps.
        //
        // The card is raised first, and the page runs its own leaving movement underneath it — the
        // card fades up as the page recedes, so what the viewer sees is one handover. The trailer
        // is then started *behind* the still-opaque card, given a moment to produce its first
        // frame, and only then is the card dissolved off it. Nothing here cuts: at no point does
        // something opaque appear or disappear on a single frame.
        trailerIntroVisible = true
        delay(TrailerIntroMs)
        // Back during the card cancels the whole thing, and the flag is how that arrives here.
        if (!trailerIntroVisible) return@LaunchedEffect
        trailerPlayback = playback
        trailerRunning = true
        trailerPlayed = true
        delay(TrailerIntroHandoverMs)
        trailerIntroVisible = false
    }

    // Flipped on the same two edges the page's own fade turns on, so the shell can run the rail and
    // the clock out and back on the same curve rather than blinking them off over a page that is
    // still there.
    // The card counts as the trailer having started, as far as the shell is concerned: the rail and
    // the clock should already be leaving while it is up, not arrive with the film.
    DisposableEffect(trailerVisible || trailerIntroVisible) {
        setImmersiveContent(trailerVisible || trailerIntroVisible)
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
    val watchedSeasons = remember(detail?.seasons, loadedSeasons, watchedEpisodeKeys) {
        watchedSeasonNumbers(detail?.seasons.orEmpty(), loadedSeasons, watchedEpisodeKeys)
    }
    val selectedSeasonWatched = activeSeasonNumber in watchedSeasons

    // Blocks of twenty for a season too long to cross with a d-pad. Empty for anything shorter,
    // which is what keeps an ordinary season exactly as it was.
    val activeSeasonEpisodeNumbers = remember(episodeEntries, activeSeasonNumber) {
        episodeEntries.filter { it.seasonNumber == activeSeasonNumber }.map { it.episode.episodeNumber }
    }
    val episodeRanges = remember(activeSeasonEpisodeNumbers) { buildEpisodeRanges(activeSeasonEpisodeNumbers) }
    val activeSeasonWatchedNumbers = remember(activeSeasonEpisodeNumbers, watchedEpisodeKeys, activeSeasonNumber) {
        activeSeasonEpisodeNumbers
            .filterTo(mutableSetOf()) { watchedEpisodeKey(activeSeasonNumber, it) in watchedEpisodeKeys }
    }
    val nextUnwatchedInSeason = remember(activeSeasonEpisodeNumbers, activeSeasonWatchedNumbers) {
        nextUnwatchedEpisodeNumber(activeSeasonEpisodeNumbers, activeSeasonWatchedNumbers)
    }
    // -1 until a block has been chosen, so the effect below can tell "not decided" from "the
    // viewer picked the first one".
    var selectedRangeIndex by rememberSaveable(mediaId, activeSeasonNumber) { mutableIntStateOf(-1) }
    var showEpisodeJump by remember(mediaId, activeSeasonNumber) { mutableStateOf(false) }
    /** Position within its block of the episode a jump chose, so the row opens on it. */
    var jumpFocusPosition by remember(mediaId, activeSeasonNumber) { mutableIntStateOf(0) }
    /** Bumped by a jump, to ask the episode row to take focus once it has redrawn. */
    var jumpFocusSignal by remember(mediaId, activeSeasonNumber) { mutableIntStateOf(0) }
    LaunchedEffect(episodeRanges, activeSeasonEpisodeNumbers, selectedEntry?.episode?.episodeNumber, resumeEpisodeContext) {
        if (selectedRangeIndex in episodeRanges.indices) return@LaunchedEffect
        // Open on the block holding whatever the viewer is up to, so carrying on where they left
        // off in a two-hundred-episode season costs no navigation at all.
        val resumeNumber = resumeEpisodeContext?.takeIf { it.seasonNumber == activeSeasonNumber }?.episodeNumber
        val focus = focusEpisodeNumber(
            episodeNumbers = activeSeasonEpisodeNumbers,
            selectedEpisodeNumber = selectedEntry?.takeIf { it.seasonNumber == activeSeasonNumber }?.episode?.episodeNumber,
            inProgressEpisodeNumber = resumeNumber,
            watchedEpisodeNumbers = activeSeasonWatchedNumbers,
        )
        selectedRangeIndex = episodeRangeIndexFor(episodeRanges, focus)
    }
    /** Moves the row onto [episodeNumber] of the active season, opening its block on the way. */
    fun jumpToEpisode(episodeNumber: Int?) {
        val target = resolveJumpTarget(activeSeasonEpisodeNumbers, episodeNumber) ?: return
        val rangeIndex = episodeRangeIndexFor(episodeRanges, target)
        selectedRangeIndex = rangeIndex
        // Where the episode sits inside its own block, which is what the row counts in.
        jumpFocusPosition = episodeRanges.getOrNull(rangeIndex)
            ?.let { range -> activeSeasonEpisodeNumbers.indexOf(target) - range.fromIndex }
            ?.coerceAtLeast(0)
            ?: 0
        val index = episodeEntries.indexOfFirst {
            it.seasonNumber == activeSeasonNumber && it.episode.episodeNumber == target
        }
        if (index >= 0) selectedEpisodeIndex = index
        showEpisodeJump = false
        // The dialog is about to close and it has nowhere to hand focus back to, so the row is
        // asked for it explicitly rather than left to spatial search from a dismissed window.
        jumpFocusSignal += 1
    }

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

    LaunchedEffect(loadedSeasons, resumeTargetSlot) {
        val target = resumeTargetSlot ?: return@LaunchedEffect
        val index = loadedSeasons.flatMap { season ->
            season.episodes.map { episode -> season.seasonNumber to episode.episodeNumber }
        }.indexOf(target.seasonNumber to target.episodeNumber)
        if (index >= 0) {
            selectedEpisodeIndex = index
            activeSeasonNumber = target.seasonNumber
        }
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

    LaunchedEffect(mediaType, mediaId, detail?.id) {
        val currentDetail = detail
        if (mediaType != "tv" || currentDetail == null) {
            watchedEpisodeKeys = emptySet()
            return@LaunchedEffect
        }
        // Keep the entire series history, not only episodes in seasons that happen to be loaded.
        // Season chips exist before their episode rows are fetched and still need their ticks.
        watchedEpisodeKeys = repository.fetchSeriesResumeState(currentDetail).watchedEpisodeKeys
    }

    LaunchedEffect(mediaType, mediaId, detail?.id) {
        if (mediaType != "tv" || detail == null) return@LaunchedEffect
        while (true) {
            delay(4_000L)
            if (System.currentTimeMillis() < suppressRemoteWatchedRefreshUntil) continue
            val synced = runCatching { repository.fetchSyncedEpisodeWatchState(mediaId) }.getOrNull() ?: continue
            watchedEpisodeKeys = (watchedEpisodeKeys - synced.unwatched) + synced.completed
        }
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
        }.distinct().take(8).forEach { url ->
            context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(url).memoryCacheKey(url).diskCacheKey(url)
                    .crossfade(false).allowHardware(true).build(),
            )
        }
    }

    // Recommendation metadata already arrives as one detail response. Hold the band behind one
    // shimmering row while all of its visible posters warm the cache, then reveal the completed
    // row in one fade instead of letting cards paint one-by-one on every visit.
    LaunchedEffect(detail?.id, detail?.similarTitles) {
        val current = detail ?: return@LaunchedEffect
        similarArtworkReady = current.similarTitles.isEmpty()
        val urls = current.similarTitles.mapNotNull { it.poster }.filter(String::isNotBlank).distinct()
        if (urls.isNotEmpty()) {
            supervisorScope {
                urls.map { url ->
                    async {
                        context.imageLoader.execute(
                            ImageRequest.Builder(context)
                                .data(url).memoryCacheKey(url).diskCacheKey(url)
                                .size(212, 318).crossfade(false).allowHardware(true).build(),
                        )
                    }
                }.awaitAll()
            }
        }
        similarArtworkReady = true
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
                        // only consulted when focus is on its way out of the page entirely.
                        //
                        // Left out of the page means the navigation menu, and the shell owns that
                        // transition — see the boundary handler in StreamDekTvApp. Cancelling here
                        // ends the search rather than letting it wander into whatever happens to be
                        // laid out under the page, and a cancelled search is precisely the signal
                        // the shell answers by opening the menu. Aiming the redirect straight at the
                        // rail, which is what this used to do, meant a collapsed drawer had to stay
                        // focusable — and then every stray search in the app could fall into it.
                        .focusGroup()
                        .focusProperties {
                            exit = { direction ->
                                if (direction == FocusDirection.Left) {
                                    FocusRequester.Cancel
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
                                    val target = !inWatchlist
                                    inWatchlist = target
                                    runCatching {
                                        if (target) repository.addToWatchlist(item) else repository.removeFromWatchlist(item)
                                    }.onFailure {
                                        // The write is authoritative; do not leave an optimistic
                                        // bookmark on screen when the service rejected it.
                                        inWatchlist = !target
                                    }
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
                            trailerUnavailable = trailerUnavailable,
                            trailerCountdown = trailerCountdown,
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
                                watchedEpisodeKeys = watchedEpisodeKeys,
                                nextUnwatchedEpisodeNumber = nextUnwatchedInSeason,
                                inProgressEpisode = progressFraction
                                    ?.takeIf { it > 0f && it < 0.95f }
                                    ?.let { fraction ->
                                        resumeEpisodeContext
                                            ?.takeIf { it.seasonNumber == activeSeasonNumber }
                                            ?.let { it.episodeNumber to fraction }
                                    },
                                seasonWatched = selectedSeasonWatched,
                                watchedSeasonNumbers = watchedSeasons,
                                markingSeason = markingSeasonWatched,
                                firstChipRequester = seasonChipRequester,
                                upRequester = playRequester,
                                episodeRanges = episodeRanges,
                                selectedRangeIndex = selectedRangeIndex.coerceAtLeast(0),
                                onSelectRange = {
                                    selectedRangeIndex = it
                                    // A block chosen from the chips opens at its first card; only a
                                    // jump names an episode within it.
                                    jumpFocusPosition = 0
                                },
                                onJumpToEpisode = { showEpisodeJump = true },
                                initialFocusPosition = jumpFocusPosition,
                                focusRowSignal = jumpFocusSignal,
                                onSelectSeason = {
                                    if (anchorSeasonNumber != it) {
                                        resumeTargetSlot = null
                                        resumeEpisodeContext = null
                                        anchorSeasonNumber = it
                                    }
                                },
                                onMarkSeasonWatched = markSeason@{
                                    val season = activeSeasonDetail ?: return@markSeason
                                    if (markingSeasonWatched) return@markSeason
                                    val targetWatched = !selectedSeasonWatched
                                    val seasonKeys = season.episodes.map {
                                        watchedEpisodeKey(season.seasonNumber, it.episodeNumber)
                                    }.toSet()
                                    val before = watchedEpisodeKeys
                                    watchedEpisodeKeys = if (targetWatched) before + seasonKeys else before - seasonKeys
                                    suppressRemoteWatchedRefreshUntil = System.currentTimeMillis() + 6_000L
                                    markingSeasonWatched = true
                                    scope.launch {
                                        val marked = repository.setSeasonWatched(
                                            mediaId = d.id,
                                            title = d.title,
                                            year = d.year,
                                            seasonNumber = season.seasonNumber,
                                            watched = targetWatched,
                                            seasonDetail = season,
                                        )
                                        if (!marked) watchedEpisodeKeys = before
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
                                onOpen = { onOpenPerson(it.id.toString()) },
                            )
                            }
                        }
                    }

                    if (d.similarTitles.isNotEmpty()) {
                        item("similar") {
                            Box(Modifier.bandRestorePoint(focusedRow == "similar", entryFocusRequester)) {
                            androidx.compose.animation.AnimatedContent(
                                targetState = similarArtworkReady,
                                transitionSpec = {
                                    androidx.compose.animation.fadeIn(tween(similarFadeInMillis)) togetherWith
                                        androidx.compose.animation.fadeOut(tween(similarFadeOutMillis))
                                },
                                label = "similar-ready",
                            ) { ready ->
                                if (ready) {
                                    SimilarBand(
                                        items = d.similarTitles,
                                        compact = focusedRow != null && focusedRow != "similar",
                                        onFocusChanged = { if (it) focusedRow = "similar" },
                                        onOpen = { onOpenDetail(it.type, it.detailLookupId()) },
                                    )
                                } else {
                                    SimilarBandSkeleton()
                                }
                            }
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

        // Above the stage, so the trailer mounts behind it rather than beside it. Kept in
        // composition until it has finished leaving — removing it on the flag would put the cut
        // back in, at the other end.
        if (trailerIntroAlpha > 0.001f) {
            detail?.let { current ->
                TrailerIntroCard(
                    backdropUrl = current.backdrop ?: current.poster,
                    titleLogoUrl = current.titleLogo,
                    title = current.title,
                    progress = trailerIntroAlpha,
                )
            }
        }

        trailerPlayback?.let { playback ->
            TrailerStage(
                playback = playback,
                maxHeight = trailerMaxHeight,
                active = trailerRunning,
                focusRequester = trailerRequester,
                onEnded = { dismissTrailer() },
                onFailed = {
                    // A resolved file that then fails is worth one more try by the other route
                    // before the viewer is put back on the page. These links are short-lived and
                    // single-use, so one that was good when it was resolved can be refused a moment
                    // later — and the embed does not depend on them at all.
                    val embedKey = trailerCandidateUrls.firstNotNullOfOrNull { youtubeTrailerKey(it) }
                    if (playback is TrailerPlayback.Native && embedKey != null) {
                        TvDebugLogger.w("Trailer", "native playback failed; falling back to the embed")
                        trailerPlayback = TrailerPlayback.Embed(embedKey)
                    } else {
                        trailerUnavailable = true
                        dismissTrailer()
                    }
                },
                onBack = { dismissTrailer() },
                modifier = Modifier.graphicsLayer { alpha = trailerStageAlpha },
            )
        }

        if (showEpisodeJump) {
            EpisodeJumpDialog(
                episodeNumbers = activeSeasonEpisodeNumbers,
                watchedNumbers = activeSeasonWatchedNumbers,
                seasonNumber = activeSeasonNumber,
                selectedNumber = selectedEntry?.takeIf { it.seasonNumber == activeSeasonNumber }?.episode?.episodeNumber,
                nextUnwatched = nextUnwatchedInSeason,
                onJump = ::jumpToEpisode,
                onDismiss = { showEpisodeJump = false },
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
                    onToggleWatched = {
                        if (!episodeActionLoading) {
                            episodeActionLoading = true
                            episodeActionError = null
                            scope.launch {
                                val context = entry.episode.toEpisodeContext(entry.seasonNumber)
                                val key = watchedEpisodeKey(entry.seasonNumber, entry.episode.episodeNumber)
                                val targetWatched = key !in watchedEpisodeKeys
                                val marked = repository.setEpisodeWatched(currentDetail, context, targetWatched)
                                if (marked) {
                                    // Re-run the same unified policy used when the detail page opens.
                                    // This advances after a completed episode and returns to the exact
                                    // episode after an explicit unwatched tombstone.
                                    val refreshed = repository.fetchSeriesResumeState(currentDetail)
                                    watchedEpisodeKeys = refreshed.watchedEpisodeKeys
                                    resumeTargetSlot = refreshed.target
                                    resumeEpisodeContext = refreshed.target?.let {
                                        EpisodeContext(it.seasonNumber, it.episodeNumber)
                                    }
                                    refreshed.target?.let { target ->
                                        anchorSeasonNumber = target.seasonNumber
                                        activeSeasonNumber = target.seasonNumber
                                    }
                                    episodeAction = null
                                } else {
                                    episodeActionError = "Could not update this episode's watched state."
                                }
                                episodeActionLoading = false
                            }
                        }
                    },
                    onMarkPreviousWatched = {
                        if (!episodeActionLoading) {
                            episodeActionLoading = true
                            episodeActionError = null
                            scope.launch {
                                val context = entry.episode.toEpisodeContext(entry.seasonNumber)
                                val marked = repository.markPreviousEpisodesWatched(currentDetail, context)
                                if (marked) {
                                    val refreshed = repository.fetchSeriesResumeState(currentDetail)
                                    watchedEpisodeKeys = refreshed.watchedEpisodeKeys
                                    resumeTargetSlot = refreshed.target
                                    resumeEpisodeContext = refreshed.target?.let { EpisodeContext(it.seasonNumber, it.episodeNumber) }
                                    episodeAction = null
                                } else {
                                    episodeActionError = "Could not mark the previous episodes watched."
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
    trailerUnavailable: Boolean,
    /** Seconds until the trailer takes the screen, or null when nothing is counting down. */
    trailerCountdown: Int?,
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
                            // First, because it is the only one of these that is about to change
                            // what is on screen. "Loading" is true at the same time and is the less
                            // useful half of it: the viewer wants to know when, not that work is
                            // happening.
                            trailerCountdown != null -> "Trailer in ${trailerCountdown}s"
                            trailerLoading -> "Loading trailer…"
                            // Ahead of "replay": if the last attempt came to nothing, that is the
                            // news, even on a title whose trailer has played before.
                            trailerUnavailable -> "Trailer unavailable"
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

/**
 * Choosing an episode by number, for a season too long to walk through.
 *
 * A grid rather than a list, because a grid is what a d-pad is good at: ten to a row means Down
 * moves ten episodes at a time, so anywhere in a two-hundred-episode season is about twenty presses
 * from anywhere else, and rather fewer from where the viewer actually is - the grid opens with the
 * current episode focused rather than at the top.
 *
 * The shortcuts above it are the short answers to the two questions people actually have: where was
 * I, and what is next. Neither needs the viewer to know a number.
 */
@Composable
private fun EpisodeJumpDialog(
    episodeNumbers: List<Int>,
    watchedNumbers: Set<Int>,
    seasonNumber: Int,
    selectedNumber: Int?,
    nextUnwatched: Int?,
    onJump: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    val entryRequester = remember { FocusRequester() }
    val focusNumber = selectedNumber ?: nextUnwatched ?: episodeNumbers.firstOrNull()
    LaunchedEffect(focusNumber, episodeNumbers) {
        // Open on the episode the viewer is on. Scrolled first, then focused: a tile that has not
        // been composed yet cannot take focus, and asking it to is how a dialog opens with nothing
        // focused and the remote doing nothing at all.
        val index = episodeNumbers.indexOf(focusNumber)
        if (index >= 0) gridState.scrollToItem((index - 5).coerceAtLeast(0))
        delay(120)
        runCatching { entryRequester.requestFocus() }
    }
    val shortcuts = remember(episodeNumbers, selectedNumber, nextUnwatched) {
        buildList {
            episodeNumbers.firstOrNull()?.let { add("First" to it) }
            nextUnwatched?.let { add("Next Up · E$it" to it) }
            episodeNumbers.lastOrNull()?.takeIf { it != episodeNumbers.firstOrNull() }?.let { add("Last · E$it" to it) }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(760.dp)
                .background(MaterialTheme.colorScheme.surface, AppCardShape)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Season $seasonNumber · go to episode",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (shortcuts.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    shortcuts.forEach { (label, number) ->
                        DetailFocusCard(
                            onClick = { onJump(number) },
                            shape = AppPillShape,
                            description = label,
                        ) {
                            Text(
                                text = label,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(10),
                state = gridState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                episodeGridItems(episodeNumbers, key = { it }) { number ->
                    val watched = number in watchedNumbers
                    val isFocusTarget = number == focusNumber
                    DetailFocusCard(
                        onClick = { onJump(number) },
                        shape = AppPillShape,
                        modifier = if (isFocusTarget) Modifier.focusRequester(entryRequester) else Modifier,
                        description = if (watched) "Episode $number, watched" else "Episode $number",
                    ) {
                        Text(
                            text = "$number",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp).fillMaxWidth(),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
                            // Watched fades; the content description above carries the same thing
                            // for anyone who cannot see the fade.
                            color = if (watched) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
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
    onToggleWatched: () -> Unit,
    onMarkPreviousWatched: () -> Unit,
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
                    watched -> "Marking it unwatched will make it eligible for series continuation again."
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
                onClick = onToggleWatched,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().focusRequester(actionRequester),
                shape = ButtonDefaults.shape(AppPillShape),
            ) {
                Text(
                    when {
                        loading -> "Updating..."
                        watched -> "Mark as Unwatched"
                        else -> "Mark as Watched"
                    },
                )
            }
            OutlinedButton(
                onClick = onMarkPreviousWatched,
                enabled = !loading && (seasonNumber > 1 || episode.episodeNumber > 1),
                modifier = Modifier.fillMaxWidth(),
                shape = ButtonDefaults.shape(AppPillShape),
            ) { Text("Mark All Previous Episodes as Watched") }
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
