package com.streamdek.tv.nativeapp.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.PlaybackRequest
import com.streamdek.tv.nativeapp.data.SeasonDetail
import com.streamdek.tv.nativeapp.data.SeasonEpisode
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.TraktCommentItem
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.SuppressBringIntoView
import com.streamdek.tv.nativeapp.ui.glideToItem
import com.streamdek.tv.nativeapp.ui.launchExternalIntent
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    repository: StreamDekRepository,
    mediaType: String,
    mediaId: String,
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
    var shareSheet by remember(mediaType, mediaId) { mutableStateOf<ShareSheetState?>(null) }
    var episodeAction by remember(mediaType, mediaId) { mutableStateOf<SeasonEpisodeEntry?>(null) }
    var episodeActionLoading by remember(mediaType, mediaId) { mutableStateOf(false) }
    var episodeActionError by remember(mediaType, mediaId) { mutableStateOf<String?>(null) }
    /** Full synopsis the viewer asked to read, shown over the screen until they close it. */
    var expandedSynopsis by remember(mediaType, mediaId) { mutableStateOf<String?>(null) }
    /** Set when no installed app could take an intent, so the press is not silently swallowed. */
    var externalIntentNotice by remember(mediaType, mediaId) { mutableStateOf<String?>(null) }

    val playRequester = remember(mediaType, mediaId) { FocusRequester() }
    val synopsisMoreRequester = remember(mediaType, mediaId) { FocusRequester() }
    /** Whether focus is still in the hero. Drives the collapse when the viewer moves below it. */
    var heroFocused by remember(mediaType, mediaId) { mutableStateOf(true) }
    /**
     * Which row the viewer is on. Only that row is drawn at full size; the rest shrink, so moving
     * down the page keeps the row you are actually using on screen instead of pushing it under the
     * fold. Coming back up expands whatever you land on again.
     */
    var focusedRow by remember(mediaType, mediaId) { mutableStateOf<String?>(null) }
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
        shareSheet = null
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
                LaunchedEffect(focusedRow, bandIds) {
                    val index = bandIds.indexOf(focusedRow)
                    listState.glideToItem(if (index < 0) 0 else (index - 1).coerceAtLeast(0))
                }
                // The hero sits outside the scrolling container.
                //
                // Anything inside a lazy list gets scrolled to when it takes focus, and since focus
                // opens on Play that dragged the poster and title clean off the top of the screen.
                // Pinning the hero removes the possibility rather than fighting it: only the
                // sections below can move, and the title is always the first thing on screen.
                val heroTop by androidx.compose.animation.core.animateDpAsState(
                    targetValue = if (heroFocused) 72.dp else 28.dp,
                    animationSpec = androidx.compose.animation.core.tween(220),
                    label = "hero-top",
                )
                Column(Modifier.fillMaxSize().padding(top = heroTop)) {
                    DetailHero(
                            compact = !heroFocused,
                            onFocusChanged = {
                                heroFocused = it
                                if (it) focusedRow = null
                            },
                            detail = d,
                            selectedEpisode = selectedEpisodeContext,
                            progressFraction = progressFraction,
                            progressLabel = progressLabel,
                            inWatchlist = inWatchlist,
                            markedWatched = markedWatched,
                            hasTrailer = trailerUrlFor(d) != null,
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
                            onTrailer = {
                                trailerUrlFor(d)?.let { url ->
                                    externalIntentNotice = launchExternalIntent(
                                        context, Intent(Intent.ACTION_VIEW, Uri.parse(url)), "trailers",
                                    )
                                }
                            },
                            onShare = { shareSheet = buildShareSheetState(d) },
                            synopsisMoreRequester = synopsisMoreRequester,
                            onExpandSynopsis = { expandedSynopsis = it },
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

                    if (d.cast.isNotEmpty()) {
                        item("cast") {
                            CastBand(
                                cast = d.cast,
                                compact = focusedRow != null && focusedRow != "cast",
                                onFocusChanged = { if (it) focusedRow = "cast" },
                            )
                        }
                    }

                    if (d.similarTitles.isNotEmpty()) {
                        item("similar") {
                            SimilarBand(
                                items = d.similarTitles,
                                compact = focusedRow != null && focusedRow != "similar",
                                onFocusChanged = { if (it) focusedRow = "similar" },
                                onOpen = { onOpenDetail(it.type, it.detailLookupId()) },
                            )
                        }
                    }

                    if (comments.isNotEmpty()) {
                        item("comments") {
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

        shareSheet?.let { sheet ->
            ShareDialog(
                sheet = sheet,
                onDismiss = { shareSheet = null },
                onShareNow = {
                    externalIntentNotice = launchExternalIntent(
                        context,
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, sheet.shareText)
                            },
                            "Share title",
                        ),
                        "sharing",
                    )
                },
                onOpenLink = {
                    externalIntentNotice = launchExternalIntent(
                        context, Intent(Intent.ACTION_VIEW, Uri.parse(sheet.shareUrl)), "web links",
                    )
                },
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
        externalIntentNotice?.let { message ->
            NoticeDialog(
                title = "Nothing can open this",
                message = message,
                onDismiss = { externalIntentNotice = null },
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
    hasTrailer: Boolean,
    playRequester: FocusRequester,
    onPlay: () -> Unit,
    onToggleWatchlist: () -> Unit,
    onMarkWatched: () -> Unit,
    onTrailer: () -> Unit,
    onShare: () -> Unit,
    synopsisMoreRequester: FocusRequester,
    onExpandSynopsis: (String) -> Unit,
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
    val heroTween = androidx.compose.animation.core.tween<Dp>(220)
    val posterWidth by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (compact) 0.dp else 188.dp,
        animationSpec = heroTween,
        label = "hero-poster",
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
        horizontalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 30.dp),
    ) {
        // The poster is the first thing to go when focus moves below: it is the largest element
        // and, once the viewer is browsing recommendations, the least useful.
        if (!compact) HeroPoster(detail)

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
                    modifier = Modifier.focusRequester(playRequester).height(41.dp).width(208.dp),
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
                // Secondary actions are labelled but compact, so the whole row is reachable in a
                // few presses instead of five full-width buttons strung across the screen.
                if (hasTrailer) {
                    HeroAction(Icons.Rounded.Movie, "Play trailer", false, onTrailer)
                }
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
                HeroAction(Icons.Rounded.Share, "Share", false, onShare)
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
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) +
            androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(220)),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)) +
            androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(220)),
    ) {
        content()
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
            modifier = Modifier.size(if (focused) 28.dp else 24.dp),
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
@Composable
private fun ShareDialog(
    sheet: ShareSheetState,
    onDismiss: () -> Unit,
    onShareNow: () -> Unit,
    onOpenLink: () -> Unit,
) {
    val shareRequester = remember { FocusRequester() }

    LaunchedEffect(sheet.shareUrl) {
        delay(80)
        runCatching { shareRequester.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .background(MaterialTheme.colorScheme.surface, AppCardShape)
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = sheet.title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sheet.shareUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onShareNow,
                    modifier = Modifier.focusRequester(shareRequester),
                    shape = ButtonDefaults.shape(AppPillShape),
                ) { Text("Share") }
                OutlinedButton(onClick = onOpenLink, shape = ButtonDefaults.shape(AppPillShape)) { Text("Open Link") }
                OutlinedButton(onClick = onDismiss, shape = ButtonDefaults.shape(AppPillShape)) { Text("Close") }
            }
        }
    }
}

@Composable
private fun NoticeDialog(title: String, message: String, onDismiss: () -> Unit) {
    val closeRequester = remember { FocusRequester() }

    LaunchedEffect(message) {
        delay(80)
        runCatching { closeRequester.requestFocus() }
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
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f),
            )
            Button(
                onClick = onDismiss,
                modifier = Modifier.focusRequester(closeRequester),
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
