package com.streamdek.tv.nativeapp.ui.detail

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.streamdek.tv.nativeapp.data.CastMember
import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.PlaybackRequest
import com.streamdek.tv.nativeapp.data.SeasonDetail
import com.streamdek.tv.nativeapp.data.SeasonEpisode
import com.streamdek.tv.nativeapp.data.SeasonRef
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.TraktCommentItem
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.ProgressMeter
import com.streamdek.tv.nativeapp.ui.animateToAnchoredItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Ready(val detail: MediaDetail) : DetailUiState
    data class Error(val message: String) : DetailUiState
}

private data class AmbientBackdropPalette(
    val leftGlow: Color,
    val rightGlow: Color,
    val accentGlow: Color,
)

private data class ShareSheetState(
    val title: String,
    val shareUrl: String,
    val shareText: String,
)

private val DetailSectionInset = 42.dp
private val HeroTopSpacerHeight = 35.dp
private val HeroContentTopPadding = 0.dp

@OptIn(ExperimentalFoundationApi::class)
private val HeroActionNoScrollResponder = object : BringIntoViewResponder {
    override fun calculateRectForParent(localRect: Rect): Rect = localRect
    override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
}

@OptIn(ExperimentalFoundationApi::class)
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
    var detailRefreshing by remember(mediaType, mediaId) { mutableStateOf(cachedDetail != null) }
    var selectedSeasonNumber by rememberSaveable(mediaType, mediaId) { mutableIntStateOf(1) }
    var selectedEpisodeIndex by rememberSaveable(mediaType, mediaId) { mutableIntStateOf(0) }
    var selectedSeason by remember(mediaType, mediaId) { mutableStateOf<SeasonDetail?>(null) }
    var resumeEpisodeContext by remember(mediaType, mediaId) { mutableStateOf<EpisodeContext?>(null) }
    var progressFraction by remember(mediaType, mediaId) { mutableStateOf<Float?>(null) }
    var progressLabel by remember(mediaType, mediaId) { mutableStateOf<String?>(null) }
    var inWatchlist by remember(mediaType, mediaId) { mutableStateOf(false) }
    var markedWatched by remember(mediaType, mediaId) { mutableStateOf(false) }
    var watchedEpisodesInSeason by remember(mediaType, mediaId, selectedSeasonNumber) { mutableStateOf<Set<Int>>(emptySet()) }
    var comments by remember(mediaType, mediaId) { mutableStateOf<List<TraktCommentItem>>(emptyList()) }
    var shareSheet by remember(mediaType, mediaId) { mutableStateOf<ShareSheetState?>(null) }
    val bootstrap by repository.bootstrap.collectAsState()
    val preferManualStreamSelection = bootstrap?.preferences?.playback?.manualStreamSelectionEnabled != false
    val entryFocusRequester = remember(mediaType, mediaId) { FocusRequester() }
    val playButtonRequester = remember(mediaType, mediaId) { FocusRequester() }
    val commentsRequester = remember(mediaType, mediaId) { FocusRequester() }
    val castRequester = remember(mediaType, mediaId) { FocusRequester() }
    val similarRequester = remember(mediaType, mediaId) { FocusRequester() }
    val detailListState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var ambientPalette by remember(mediaType, mediaId) {
        mutableStateOf(
            AmbientBackdropPalette(
                leftGlow = Color(0xFF1A2633),
                rightGlow = Color(0xFF111820),
                accentGlow = Color(0xFF24384A),
            ),
        )
    }

    LaunchedEffect(mediaType, mediaId) {
        val existingDetail = (uiState as? DetailUiState.Ready)?.detail
        if (existingDetail == null) {
            uiState = DetailUiState.Loading
        } else {
            detailRefreshing = true
        }
        comments = emptyList()
        progressFraction = null
        progressLabel = null
        inWatchlist = false
        markedWatched = false
        watchedEpisodesInSeason = emptySet()
        shareSheet = null
        resumeEpisodeContext = null
        runCatching { repository.refreshBootstrap() }
        val libraryDeferred = supervisorScope {
            async {
                runCatching { repository.fetchLibrary() }.getOrNull()
            }
        }
        val detail = repository.fetchDetail(mediaId, mediaType)
        if (detail == null) {
            if (existingDetail == null) {
                uiState = DetailUiState.Error("Could not load title details")
            }
            detailRefreshing = false
            return@LaunchedEffect
        }
        uiState = DetailUiState.Ready(detail)
        if (detail.seasons.none { it.seasonNumber == selectedSeasonNumber }) {
            selectedSeasonNumber = detail.seasons.firstOrNull()?.seasonNumber ?: 1
            selectedEpisodeIndex = 0
        }
        selectedSeason = supervisorScope {
            async {
                if (mediaType == "tv" && detail.seasons.isNotEmpty()) {
                    repository.fetchSeason(mediaId, selectedSeasonNumber)
                } else {
                    null
                }
            }.await()
        }
        libraryDeferred.await()?.let { library ->
            inWatchlist = library.watchlist.any { it.id == mediaId && it.type == mediaType }
            resumeEpisodeContext = library.continueWatching
                .firstOrNull { it.id == mediaId && it.type == mediaType }
                ?.episode
        }
        detailRefreshing = false
    }

    val detail = (uiState as? DetailUiState.Ready)?.detail
    val selectedEpisode = selectedSeason?.episodes?.getOrNull(selectedEpisodeIndex)
    val selectedEpisodeContext = selectedEpisode?.toEpisodeContext(selectedSeasonNumber)
    val seasonFullyWatched = selectedSeason?.episodes?.isNotEmpty() == true &&
        watchedEpisodesInSeason.size >= selectedSeason?.episodes.orEmpty().size

    LaunchedEffect(selectedSeasonNumber, detail?.id) {
        if (detail?.type == "tv") {
            selectedSeason = repository.fetchSeason(detail.id, selectedSeasonNumber)
        }
    }

    LaunchedEffect(mediaType, mediaId, selectedSeasonNumber, selectedEpisodeIndex, resumeEpisodeContext) {
        val progressEpisode = if (mediaType == "tv") resumeEpisodeContext ?: selectedEpisodeContext else selectedEpisodeContext
        val progress = repository.fetchProgress(mediaType, mediaId, progressEpisode)
        progressFraction = progress?.progress?.div(100.0)?.toFloat()?.coerceIn(0f, 1f)
        progressLabel = progress?.takeIf { it.positionSec > 0 && it.durationSec > 0 }?.let {
            "${formatTime(it.positionSec)} / ${formatTime(it.durationSec)}"
        }
    }

    LaunchedEffect(mediaType, mediaId, selectedSeasonNumber, selectedSeason?.episodes, detail?.id) {
        if (mediaType != "tv" || detail == null) {
            watchedEpisodesInSeason = emptySet()
            return@LaunchedEffect
        }
        val watchedKeys = repository.fetchWatchedKeys(forceRefresh = true)
        watchedEpisodesInSeason = selectedSeason?.episodes
            ?.mapNotNull { episode ->
                episode.episodeNumber.takeIf {
                    watchedKeys.contains("tv:${detail.id}:s$selectedSeasonNumber:e$it")
                }
            }
            ?.toSet()
            .orEmpty()
    }

    LaunchedEffect(mediaType, mediaId, selectedSeasonNumber, selectedEpisodeIndex, resumeEpisodeContext, progressFraction, detail?.id) {
        val currentDetail = detail ?: return@LaunchedEffect
        val watchedEpisode = playbackEpisodeContext(
            detail = currentDetail,
            progressFraction = progressFraction,
            resumeEpisodeContext = resumeEpisodeContext,
            selectedEpisode = selectedEpisodeContext,
        )
        markedWatched = repository.isWatched(
            mediaType = mediaType,
            mediaId = mediaId,
            episode = watchedEpisode,
            forceRefresh = true,
        )
    }

    LaunchedEffect(mediaType, mediaId, detail?.id) {
        if (detail == null) return@LaunchedEffect
        comments = runCatching { repository.fetchTraktComments(mediaId, mediaType) }.getOrDefault(emptyList())
    }

    LaunchedEffect(detail?.backdrop, detail?.poster) {
        val artUrl = detail?.backdrop ?: detail?.poster
        if (artUrl.isNullOrBlank()) return@LaunchedEffect
        runCatching {
            extractAmbientPalette(context, artUrl)
        }.onSuccess { extracted ->
            ambientPalette = extracted
        }
    }

    LaunchedEffect(detail?.id, comments) {
        detail ?: return@LaunchedEffect
        delay(220)
        buildList {
            detail.backdrop?.let(::add)
            detail.poster?.let(::add)
            detail.titleLogo?.let(::add)
            detail.cast.take(3).forEach { it.photo?.let(::add) }
            detail.similarTitles.take(3).forEach {
                it.backdrop?.let(::add)
                it.poster?.let(::add)
            }
            comments.take(2).forEach { it.avatar?.let(::add) }
        }.distinct().take(8).forEach { url ->
            context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .memoryCacheKey(url)
                    .diskCacheKey(url)
                    .crossfade(false)
                    .allowHardware(true)
                    .build(),
            )
        }
    }

    val backgroundColor = MaterialTheme.colorScheme.background

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
        if (!detail?.backdrop.isNullOrBlank()) {
            AsyncImage(
                model = detail?.backdrop,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val leftFade = Brush.horizontalGradient(
                        colors = listOf(
                            backgroundColor.copy(alpha = 0.90f),
                            ambientPalette.leftGlow.copy(alpha = 0.68f),
                            Color.Transparent,
                        ),
                        endX = size.width * 0.60f,
                    )
                    val verticalFade = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            backgroundColor.copy(alpha = 0.16f),
                            backgroundColor.copy(alpha = 0.74f),
                        ),
                        startY = size.height * 0.60f,
                    )
                    val centerLeftGlow = Brush.radialGradient(
                        colors = listOf(
                            ambientPalette.leftGlow.copy(alpha = 0.34f),
                            ambientPalette.accentGlow.copy(alpha = 0.22f),
                            Color.Transparent,
                        ),
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.34f, size.height * 0.34f),
                        radius = size.minDimension * 0.52f,
                    )
                    val lowerLeftGlow = Brush.radialGradient(
                        colors = listOf(
                            ambientPalette.accentGlow.copy(alpha = 0.46f),
                            Color.Transparent,
                        ),
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.31f, size.height * 0.72f),
                        radius = size.minDimension * 0.56f,
                    )
                    val lowerMidGlow = Brush.radialGradient(
                        colors = listOf(
                            ambientPalette.rightGlow.copy(alpha = 0.18f),
                            Color.Transparent,
                        ),
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.46f, size.height * 0.82f),
                        radius = size.minDimension * 0.40f,
                    )
                    val bottomFade = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            ambientPalette.rightGlow.copy(alpha = 0.28f),
                            backgroundColor.copy(alpha = 0.68f),
                        ),
                        startY = size.height * 0.72f,
                    )
                    val topRightClear = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            backgroundColor.copy(alpha = 0.04f),
                            Color.Transparent,
                        ),
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.86f, size.height * 0.16f),
                        radius = size.minDimension * 0.38f,
                    )
                    onDrawBehind {
                        drawRect(leftFade)
                        drawRect(verticalFade)
                        drawRect(centerLeftGlow)
                        drawRect(lowerLeftGlow)
                        drawRect(lowerMidGlow)
                        drawRect(bottomFade)
                        drawRect(topRightClear)
                    }
                },
        )


        when (val state = uiState) {
            DetailUiState.Loading -> DetailLoading()
            is DetailUiState.Error -> DetailError(state.message)
            is DetailUiState.Ready -> {
                LazyColumn(
                    state = detailListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(26.dp),
                ) {
                    item("hero_spacer") {
                        HeroEntrySentinel(
                            height = HeroTopSpacerHeight,
                            focusRequester = entryFocusRequester,
                            downRequester = playButtonRequester,
                        )
                    }
                    item("hero_copy") {
                        Box(
                            modifier = Modifier.padding(horizontal = DetailSectionInset)
                        ) {
                            HeroCopySection(
                                detail = state.detail,
                                selectedEpisode = selectedEpisodeContext,
                            )
                        }
                    }

                    item("hero_actions") {
                        Box(
                            modifier = Modifier.padding(horizontal = DetailSectionInset),
                        ) {
                            HeroActionRow(
                                detail = state.detail,
                                selectedEpisode = selectedEpisodeContext,
                                progressFraction = progressFraction,
                                progressLabel = progressLabel,
                                inWatchlist = inWatchlist,
                                markedWatched = markedWatched,
                                playButtonRequester = playButtonRequester,
                                preferManualStreamSelection = preferManualStreamSelection,
                                onToggleWatchlist = {
                                    val item = MediaItem(
                                        id = state.detail.id,
                                        tmdbId = state.detail.tmdbId,
                                        title = state.detail.title,
                                        type = state.detail.type,
                                        poster = state.detail.poster,
                                        backdrop = state.detail.backdrop,
                                        description = state.detail.description,
                                        rating = state.detail.rating,
                                        year = state.detail.year,
                                    )
                                    if (inWatchlist) repository.removeFromWatchlist(item) else repository.addToWatchlist(item)
                                    inWatchlist = !inWatchlist
                                },
                                onPlay = {
                                    if (repository.currentSession() == null) {
                                        onRequireAuth()
                                    } else {
                                        onPlay(
                                            PlaybackRequest(
                                                mediaId = state.detail.id,
                                                mediaType = state.detail.type,
                                                imdbId = state.detail.imdbId,
                                                episode = playbackEpisodeContext(
                                                    detail = state.detail,
                                                    progressFraction = progressFraction,
                                                    resumeEpisodeContext = resumeEpisodeContext,
                                                    selectedEpisode = selectedEpisodeContext,
                                                ),
                                                title = state.detail.title,
                                            ),
                                        )
                                    }
                                },
                                onMarkWatched = {
                                    if (repository.currentSession() == null) {
                                        onRequireAuth()
                                    } else {
                                        val episodeContext = if (state.detail.type == "tv") {
                                            selectedEpisodeContext
                                        } else {
                                            playbackEpisodeContext(
                                                detail = state.detail,
                                                progressFraction = progressFraction,
                                                resumeEpisodeContext = resumeEpisodeContext,
                                                selectedEpisode = selectedEpisodeContext,
                                            )
                                        }
                                        val ok = repository.markWatched(
                                            mediaType = state.detail.type,
                                            mediaId = state.detail.id,
                                            imdbId = state.detail.imdbId,
                                            title = state.detail.title,
                                            year = state.detail.year,
                                            episode = episodeContext,
                                        )
                                        if (ok) {
                                            markedWatched = true
                                            repository.clearProgress(state.detail.type, state.detail.id, episodeContext)
                                            progressFraction = null
                                            progressLabel = null
                                            episodeContext?.takeIf { state.detail.type == "tv" }?.let {
                                                watchedEpisodesInSeason = watchedEpisodesInSeason + it.episodeNumber
                                            }
                                        }
                                    }
                                },
                                onShare = {
                                    shareSheet = buildShareSheetState(state.detail)
                                },
                                onTrailer = {
                                    trailerUrlFor(state.detail)?.let {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        })
                                    }
                                },
                            )
                        }
                    }

                    item("details") {
                        Box(
                            modifier = Modifier
                                .padding(horizontal = DetailSectionInset),
                        ) {
                            DetailsPanel(
                                detail = state.detail,
                                selectedEpisode = selectedEpisode,
                                progressLabel = progressLabel,
                            )
                        }
                    }

                    if (state.detail.type == "tv" && state.detail.seasons.isNotEmpty()) {
                        item("episodes") {
                            EpisodesSection(
                                seasons = state.detail.seasons,
                                selectedSeasonNumber = selectedSeasonNumber,
                                seasonDetail = selectedSeason,
                                selectedEpisodeIndex = selectedEpisodeIndex,
                                watchedEpisodes = watchedEpisodesInSeason,
                                seasonWatched = seasonFullyWatched,
                                onSeasonFocused = {
                                    if (selectedSeasonNumber != it) {
                                        selectedSeasonNumber = it
                                        selectedEpisodeIndex = 0
                                    }
                                },
                                onSeasonPressed = {
                                    if (selectedSeasonNumber != it) {
                                        selectedSeasonNumber = it
                                        selectedEpisodeIndex = 0
                                    }
                                },
                                onEpisodeFocused = { selectedEpisodeIndex = it },
                                onEpisodePressed = { episode ->
                                    if (repository.currentSession() == null) {
                                        onRequireAuth()
                                    } else {
                                        onPlay(
                                            PlaybackRequest(
                                                mediaId = state.detail.id,
                                                mediaType = state.detail.type,
                                                imdbId = state.detail.imdbId,
                                                episode = episode,
                                                title = state.detail.title,
                                            ),
                                        )
                                    }
                                },
                                onMarkSeasonWatched = {
                                    if (repository.currentSession() == null) {
                                        onRequireAuth()
                                    } else {
                                        val ok = repository.markSeasonWatched(
                                            mediaId = state.detail.id,
                                            title = state.detail.title,
                                            year = state.detail.year,
                                            seasonNumber = selectedSeasonNumber,
                                        )
                                        if (ok) {
                                            watchedEpisodesInSeason = selectedSeason?.episodes
                                                ?.map { it.episodeNumber }
                                                ?.toSet()
                                                .orEmpty()
                                            if (selectedEpisodeContext?.seasonNumber == selectedSeasonNumber) {
                                                markedWatched = true
                                                repository.clearProgress(state.detail.type, state.detail.id, selectedEpisodeContext)
                                                progressFraction = null
                                                progressLabel = null
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }

                    if (state.detail.cast.isNotEmpty()) {
                        item("cast") {
                            CastSection(
                                cast = state.detail.cast,
                                firstRequester = castRequester,
                            )
                        }
                    }

                    if (comments.isNotEmpty()) {
                        item("comments") {
                            CommentsSection(
                                comments = comments,
                                commentsRequester = commentsRequester,
                            )
                        }
                    }

                    if (state.detail.similarTitles.isNotEmpty()) {
                        item("similar") {
                            SimilarSection(
                                items = state.detail.similarTitles,
                                firstRequester = similarRequester,
                                onOpenDetail = onOpenDetail,
                            )
                        }
                    }
                }

                LaunchedEffect(state.detail.id) {
                    // Land the initial highlight directly on the play CTA without scrolling
                    // the page. The play button suppresses bring-into-view, so focusing it
                    // never moves the list; the page only scrolls when the user navigates
                    // down themselves. Retry briefly while the hero row is composing.
                    var focused = false
                    repeat(6) { attempt ->
                        if (focused) return@repeat
                        kotlinx.coroutines.delay(if (attempt == 0) 120L else 80L)
                        focused = runCatching { playButtonRequester.requestFocus() }.isSuccess
                    }
                    if (!focused) {
                        runCatching { entryFocusRequester.requestFocus() }
                    }
                }

            }
        }

        if (detailRefreshing && uiState is DetailUiState.Ready) {
            Text(
                text = "Updating…",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 28.dp, end = 42.dp)
                    .clip(AppPillShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
            )
        }

        shareSheet?.let { sheet ->
            ShareTitleDialog(
                sheet = sheet,
                onDismiss = { shareSheet = null },
                onShareNow = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, sheet.shareText)
                            },
                            "Share title",
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                },
                onOpenLink = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sheet.shareUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                },
            )
        }
    }
}

@Composable
internal fun DetailLoading(label: String = "Loading") {
    Box(modifier = Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.CenterStart) {
        Text(
            text = label,
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
internal fun DetailError(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(48.dp),
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
    }
}

@Composable
private fun HeroEntrySentinel(
    height: androidx.compose.ui.unit.Dp,
    focusRequester: FocusRequester,
    downRequester: FocusRequester,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .focusRequester(focusRequester)
            .focusProperties {
                down = downRequester
            }
            .onFocusChanged { state ->
                // The sentinel is an invisible entry point; whenever it gains focus,
                // hand the highlight straight to the play CTA so it never appears
                // as if focus vanished (e.g. when pressing up from the hero row).
                if (state.isFocused) {
                    runCatching { downRequester.requestFocus() }
                }
            }
            .focusable(),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShareTitleDialog(
    sheet: ShareSheetState,
    onDismiss: () -> Unit,
    onShareNow: () -> Unit,
    onOpenLink: () -> Unit,
) {
    val shareRequester = remember { FocusRequester() }
    val openRequester = remember { FocusRequester() }
    val closeRequester = remember { FocusRequester() }

    LaunchedEffect(sheet.shareUrl) {
        delay(80)
        runCatching { shareRequester.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB8000000)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.52f)
                    .heightIn(max = 520.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF10141B))
                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 26.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Share ${sheet.title}",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Open the title link or continue to the system share sheet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.74f),
                )
                Text(
                    text = sheet.shareUrl,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HeroActionButton(
                        onClick = onShareNow,
                        containerColor = Color(0xFFF4EDE2),
                        contentColor = Color(0xFF18120A),
                        modifier = Modifier
                            .width(156.dp)
                            .focusRequester(shareRequester),
                    ) {
                        Text(
                            text = "System Share",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black),
                        )
                    }
                    HeroActionButton(
                        onClick = onOpenLink,
                        containerColor = Color(0xD62A3442),
                        contentColor = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .width(126.dp)
                            .focusRequester(openRequester),
                    ) {
                        Text(
                            text = "Open Link",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black),
                        )
                    }
                    HeroActionButton(
                        onClick = onDismiss,
                        containerColor = Color(0xD62A3442),
                        contentColor = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .width(94.dp)
                            .focusRequester(closeRequester),
                    ) {
                        Text(
                            text = "Close",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroCopySection(
    detail: MediaDetail,
    selectedEpisode: EpisodeContext?,
) {
    Column(
        modifier = Modifier.fillMaxWidth(0.7f),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        detail.titleLogo?.takeIf { it.isNotBlank() }?.let { logo ->
            AsyncImage(
                model = logo,
                contentDescription = detail.title,
                modifier = Modifier
                    .height(78.dp)
                    .fillMaxWidth(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
            )
        } ?: Text(
            text = detail.title,
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        HeroMetaBlock(
            detail = detail,
            selectedEpisode = selectedEpisode,
        )

        detail.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }

        detail.tagline?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.76f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroActionRow(
    detail: MediaDetail,
    selectedEpisode: EpisodeContext?,
    progressFraction: Float?,
    progressLabel: String?,
    inWatchlist: Boolean,
    markedWatched: Boolean,
    playButtonRequester: FocusRequester,
    preferManualStreamSelection: Boolean,
    onToggleWatchlist: suspend () -> Unit,
    onPlay: () -> Unit,
    onMarkWatched: suspend () -> Unit,
    onShare: () -> Unit,
    onTrailer: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .bringIntoViewResponder(HeroActionNoScrollResponder)
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContinuePlayButton(
            detail = detail,
            selectedEpisode = selectedEpisode,
            progressLabel = progressLabel,
            progressFraction = progressFraction,
            playButtonRequester = playButtonRequester,
            preferManualStreamSelection = preferManualStreamSelection,
            onPlay = onPlay,
            modifier = Modifier.width(176.dp),
        )
        HeroIconButton(
            onClick = { scope.launch { onToggleWatchlist() } },
            icon = if (inWatchlist) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            contentDescription = if (inWatchlist) "Remove from watchlist" else "Add to watchlist",
            selected = inWatchlist,
        )
        HeroIconButton(
            onClick = { scope.launch { onMarkWatched() } },
            icon = if (markedWatched) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircleOutline,
            contentDescription = if (detail.type == "tv" && selectedEpisode != null) "Mark selected episode as watched" else "Mark as watched",
            selected = markedWatched,
        )
        HeroIconButton(
            onClick = onShare,
            icon = Icons.Filled.Share,
            contentDescription = "Share",
        )
        HeroIconButton(
            onClick = onTrailer,
            icon = Icons.Filled.LiveTv,
            contentDescription = "Trailer",
            enabled = !detail.trailerKey.isNullOrBlank(),
        )
    }
}

private fun playbackEpisodeContext(
    detail: MediaDetail,
    progressFraction: Float?,
    resumeEpisodeContext: EpisodeContext?,
    selectedEpisode: EpisodeContext?,
): EpisodeContext? {
    return if (detail.type == "tv" && (progressFraction ?: 0f) > 0f) {
        resumeEpisodeContext ?: selectedEpisode
    } else {
        selectedEpisode
    }
}

private fun buildShareSheetState(detail: MediaDetail): ShareSheetState {
    val shareUrl = detail.imdbId?.takeIf { it.isNotBlank() }?.let {
        "https://www.imdb.com/title/$it/"
    } ?: "https://www.themoviedb.org/${detail.type}/${detail.tmdbId}"
    val shareText = buildString {
        append(detail.title)
        detail.year?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
        append("\n")
        append(shareUrl)
    }
    return ShareSheetState(
        title = detail.title,
        shareUrl = shareUrl,
        shareText = shareText,
    )
}

private fun trailerUrlFor(detail: MediaDetail): String? {
    return when {
        detail.trailerKey.isNullOrBlank() -> null
        detail.trailerSite.equals("Vimeo", ignoreCase = true) ->
            "https://player.vimeo.com/video/${detail.trailerKey}"
        else -> "https://www.youtube.com/watch?v=${detail.trailerKey}"
    }
}

@Composable
private fun HeroMetaBlock(
    detail: MediaDetail,
    selectedEpisode: EpisodeContext?,
) {
    val metaLine = buildList {
        detail.year?.takeIf { it.isNotBlank() }?.let(::add)
        detail.runtime?.takeIf { it > 0 }?.let { add(formatRuntime(it)) }
        detail.releaseDate?.takeIf { it.isNotBlank() }?.let { add(formatReleaseDate(it)) }
        selectedEpisode?.let { add("S${it.seasonNumber} E${it.episodeNumber}") }
    }.joinToString("   ")

    val genreLine = detail.genreNames.take(3).joinToString(", ").takeIf { it.isNotBlank() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (metaLine.isNotBlank()) {
            Text(
                text = metaLine,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.88f),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.Start),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            genreLine?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                )
            }
            detail.rating?.let { rating ->
                ImdbBadge(rating)
            }
        }
    }
}

@Composable
private fun DetailsPanel(
    detail: MediaDetail,
    selectedEpisode: SeasonEpisode?,
    progressLabel: String?,
) {
    val items = buildList<Pair<String, String>> {
        detail.releaseDate?.takeIf { it.isNotBlank() }?.let { add("Release" to formatReleaseDate(it)) }
        detail.runtime?.takeIf { it > 0 }?.let { add("Duration" to formatRuntime(it)) }
        detail.status?.takeIf { it.isNotBlank() }?.let { add("Status" to it) }
        detail.genreNames.take(3).joinToString(", ").takeIf { it.isNotBlank() }?.let { add("Genres" to it) }
        detail.numberOfSeasons?.takeIf { it > 0 }?.let { add("Seasons" to it.toString()) }
        detail.numberOfEpisodes?.takeIf { it > 0 }?.let { add("Episodes" to it.toString()) }
        selectedEpisode?.let { add("Episode" to "E${it.episodeNumber} ${it.name}") }
        progressLabel?.takeIf { it.isNotBlank() }?.let { add("Progress" to it) }
    }

    if (items.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Details",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (items.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(start = 0.dp, end = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(items) { _, item ->
                    DetailBox(label = item.first, value = item.second)
                }
            }
        }
    }
}

@Composable
private fun DetailBox(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x1A11141B))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.52f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun CommentsSection(
    comments: List<TraktCommentItem>,
    commentsRequester: FocusRequester,
) {
    val visibleComments = comments.take(8)
    val rowState = rememberLazyListState()
    var anchoredIndex by remember(visibleComments) { mutableIntStateOf(0) }

    LaunchedEffect(anchoredIndex, visibleComments.size) {
        rowState.animateToAnchoredItem(
            focusedIndex = anchoredIndex,
            itemCount = visibleComments.size,
            leadingItems = 0,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Trakt Comments",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = DetailSectionInset),
        )
        LazyRow(
            state = rowState,
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            contentPadding = PaddingValues(start = DetailSectionInset, end = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(visibleComments, key = { _, item -> item.id }) { index, comment ->
                CommentCard(
                    comment = comment,
                    requestFocus = if (index == 0) commentsRequester else null,
                    onFocused = { anchoredIndex = index },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CommentCard(
    comment: TraktCommentItem,
    requestFocus: FocusRequester?,
    onFocused: () -> Unit = {},
) {
    Card(
        onClick = {},
        modifier = Modifier
            .width(340.dp)
            .height(196.dp)
            .then(if (requestFocus != null) Modifier.focusRequester(requestFocus) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(
            containerColor = Color(0x3311141B),
            focusedContainerColor = Color(0x4411141B),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape = AppCardShape),
        ),
        scale = CardDefaults.scale(focusedScale = 1.015f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AsyncImage(
                model = comment.avatar,
                contentDescription = comment.author,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF15181D)),
                contentScale = ContentScale.Crop,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = comment.author,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = listOfNotNull(
                        comment.userRating?.let { "★$it" },
                        comment.likes.takeIf { it > 0 }?.let { "♥ $it" },
                        comment.replies.takeIf { it > 0 }?.let { "↩ $it" },
                    ).joinToString("  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.66f),
                )
            }
            Text(
                text = if (comment.spoiler) "Spoiler comment hidden on TV." else comment.comment,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinuePlayButton(
    detail: MediaDetail,
    selectedEpisode: EpisodeContext?,
    progressLabel: String?,
    progressFraction: Float?,
    playButtonRequester: FocusRequester,
    preferManualStreamSelection: Boolean,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasProgress = (progressFraction ?: 0f) > 0f
    val containerColor = Color(0xFFF4EDE2)
    val subtitleColor = Color(0xAA18120A)
    val title = when {
        hasProgress -> "Continue Watching"
        preferManualStreamSelection -> "Choose Stream"
        else -> "Play"
    }
    val subtitle = when {
        hasProgress -> progressLabel
        detail.type == "tv" && selectedEpisode != null -> "S${selectedEpisode.seasonNumber} E${selectedEpisode.episodeNumber}${selectedEpisode.title?.let { "  •  $it" } ?: ""}"
        else -> null
    }
    HeroActionButton(
        onClick = onPlay,
        containerColor = containerColor,
        contentColor = Color(0xFF18120A),
        noScrollResponder = HeroActionNoScrollResponder,
        modifier = modifier
            .focusRequester(playButtonRequester),
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        if (subtitle.isNullOrBlank()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = MaterialTheme.typography.labelMedium.fontSize * 0.6f,
                    ),
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroIconButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    HeroActionButton(
        onClick = onClick,
        containerColor = if (selected) Color(0xFFF0BA66) else Color(0xD62A3442),
        contentColor = if (selected) Color(0xFF18120A) else Color.White,
        noScrollResponder = HeroActionNoScrollResponder,
        enabled = enabled,
        iconOnly = true,
        modifier = Modifier.size(46.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = if (selected) Color(0xFF18120A) else Color.White,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroActionButton(
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    noScrollResponder: BringIntoViewResponder? = null,
    enabled: Boolean = true,
    iconOnly: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val highlightColor = MaterialTheme.colorScheme.primary
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonDefaults.shape(if (iconOnly) CircleShape else AppPillShape),
        colors = ButtonDefaults.colors(
            containerColor = containerColor,
            focusedContainerColor = containerColor,
            contentColor = contentColor,
            focusedContentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.4f),
            disabledContentColor = contentColor.copy(alpha = 0.45f),
        ),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(2.dp, Color.Transparent),
                shape = if (iconOnly) CircleShape else AppPillShape,
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, highlightColor),
                shape = if (iconOnly) CircleShape else AppPillShape,
            ),
        ),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        modifier = modifier
            .height(if (iconOnly) 46.dp else 45.dp)
            .then(if (noScrollResponder != null) Modifier.bringIntoViewResponder(noScrollResponder) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
            }
            .border(
                width = 3.dp,
                color = if (focused) highlightColor else Color.Transparent,
                shape = if (iconOnly) CircleShape else AppPillShape,
            ),
        contentPadding = if (iconOnly) PaddingValues(0.dp) else PaddingValues(horizontal = 14.dp, vertical = 0.dp),
    ) {
        Row(
            modifier = if (iconOnly) Modifier.fillMaxSize() else Modifier.fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodesSection(
    seasons: List<SeasonRef>,
    selectedSeasonNumber: Int,
    seasonDetail: SeasonDetail?,
    selectedEpisodeIndex: Int,
    watchedEpisodes: Set<Int>,
    seasonWatched: Boolean,
    onSeasonFocused: (Int) -> Unit,
    onSeasonPressed: (Int) -> Unit,
    onEpisodeFocused: (Int) -> Unit,
    onEpisodePressed: (EpisodeContext) -> Unit,
    onMarkSeasonWatched: suspend () -> Unit,
) {
    val episodes = seasonDetail?.episodes.orEmpty()
    val seasonRowState = rememberLazyListState()
    val rowState = rememberLazyListState()
    val focusedSeasonIndex = seasons.indexOfFirst { it.seasonNumber == selectedSeasonNumber }.coerceAtLeast(0)
    val scope = rememberCoroutineScope()

    LaunchedEffect(focusedSeasonIndex, seasons.size) {
        seasonRowState.animateToAnchoredItem(
            focusedIndex = focusedSeasonIndex,
            itemCount = seasons.size,
            leadingItems = 0,
        )
    }

    LaunchedEffect(selectedEpisodeIndex, episodes.size, selectedSeasonNumber) {
        rowState.animateToAnchoredItem(
            focusedIndex = selectedEpisodeIndex,
            itemCount = episodes.size,
            leadingItems = 0,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DetailSectionInset),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Episodes",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (episodes.isNotEmpty()) {
                HeroActionButton(
                    onClick = { scope.launch { onMarkSeasonWatched() } },
                    containerColor = if (seasonWatched) Color(0xFFF0BA66) else Color(0xD62A3442),
                    contentColor = if (seasonWatched) Color(0xFF18120A) else MaterialTheme.colorScheme.onBackground,
                    enabled = !seasonWatched,
                    modifier = Modifier.width(198.dp),
                ) {
                    Icon(
                        imageVector = if (seasonWatched) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircleOutline,
                        contentDescription = if (seasonWatched) "Season watched" else "Mark season watched",
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (seasonWatched) "Season Watched" else "Mark Season Watched",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        LazyRow(
            state = seasonRowState,
            modifier = Modifier.fillMaxWidth().focusGroup(),
            contentPadding = PaddingValues(start = DetailSectionInset, end = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(seasons, key = { _, season -> season.seasonNumber }) { _, season ->
                SeasonChip(
                    title = season.name,
                    selected = season.seasonNumber == selectedSeasonNumber,
                    onFocused = { onSeasonFocused(season.seasonNumber) },
                    onPressed = { onSeasonPressed(season.seasonNumber) },
                )
            }
        }
        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxWidth().focusGroup(),
            contentPadding = PaddingValues(start = DetailSectionInset, end = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(episodes, key = { _, episode -> episode.id }) { index, episode ->
                EpisodeCard(
                    episode = episode,
                    seasonNumber = selectedSeasonNumber,
                    watched = watchedEpisodes.contains(episode.episodeNumber),
                    selected = index == selectedEpisodeIndex,
                    onFocused = { onEpisodeFocused(index) },
                    onPressed = { onEpisodePressed(episode.toEpisodeContext(selectedSeasonNumber)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SeasonChip(
    title: String,
    selected: Boolean,
    onFocused: () -> Unit,
    onPressed: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onPressed,
        modifier = Modifier.onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocused()
        },
        shape = CardDefaults.shape(AppPillShape),
        colors = CardDefaults.colors(
            containerColor = if (selected) Color(0x2AF4EDE2) else Color(0x15191D22),
            focusedContainerColor = Color(0xFF2A2D36),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape = AppPillShape),
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (focused) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeCard(
    episode: SeasonEpisode,
    seasonNumber: Int,
    watched: Boolean,
    selected: Boolean,
    onFocused: () -> Unit,
    onPressed: () -> Unit,
) {
    val unreleased = !watched && !isEpisodeReleased(episode.airDate)
    Card(
        onClick = onPressed,
        modifier = Modifier.size(width = 300.dp, height = 182.dp).onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(
            containerColor = if (selected) Color(0x1EF4EDE2) else Color(0xFF181A1F),
            focusedContainerColor = if (selected) Color(0x1EF4EDE2) else Color(0xFF181A1F),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape = AppCardShape),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = 1.025f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(AppCardShape),
        ) {
            AsyncImage(
                model = episode.still,
                contentDescription = episode.name,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (unreleased) Modifier.blur(18.dp) else Modifier),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        colors = if (unreleased) {
                            listOf(Color(0x66000000), Color(0x99000000), Color(0xF0000000))
                        } else {
                            listOf(Color.Transparent, Color(0x30000000), Color(0xE2000000))
                        },
                    ),
                ),
            )
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("S$seasonNumber E${episode.episodeNumber}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (unreleased) {
                    Text(
                        text = "Unreleased",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFFFD38B),
                    )
                } else if (watched) {
                    Text(
                        text = "Watched",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFA6F0B3),
                    )
                }
                Text(
                    text = episode.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                episode.airDate?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                        textAlign = TextAlign.Start,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (watched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color(0xE61A2A1E)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Watched episode",
                        tint = Color(0xFFA6F0B3),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CastSection(
    cast: List<CastMember>,
    firstRequester: FocusRequester,
) {
    val rowState = rememberLazyListState()
    var anchoredIndex by remember(cast) { mutableIntStateOf(0) }

    LaunchedEffect(anchoredIndex, cast.size) {
        rowState.animateToAnchoredItem(
            focusedIndex = anchoredIndex,
            itemCount = cast.size,
            leadingItems = 0,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Cast",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = DetailSectionInset),
        )
        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxWidth().focusGroup(),
            contentPadding = PaddingValues(start = DetailSectionInset, end = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(cast, key = { _, member -> member.id }) { index, member ->
                CastCard(
                    member,
                    requestFocus = if (index == 0) firstRequester else null,
                    onFocused = { anchoredIndex = index },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CastCard(
    member: CastMember,
    requestFocus: FocusRequester? = null,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = {},
        modifier = Modifier
            .width(124.dp)
            .then(if (requestFocus != null) Modifier.focusRequester(requestFocus) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
        shape = CardDefaults.shape(RoundedCornerShape(24.dp)),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(0.dp, Color.Transparent), shape = RoundedCornerShape(24.dp)),
            focusedBorder = Border(BorderStroke(0.dp, Color.Transparent), shape = RoundedCornerShape(24.dp)),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = 1f),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AsyncImage(
                model = member.photo,
                contentDescription = member.name,
                modifier = Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF15181D))
                    .border(
                        width = if (focused) 3.dp else 1.dp,
                        color = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                        shape = CircleShape,
                    ),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                member.character?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun SimilarSection(
    items: List<MediaItem>,
    firstRequester: FocusRequester,
    onOpenDetail: (String, String) -> Unit,
) {
    val rowState = rememberLazyListState()
    var anchoredIndex by remember(items) { mutableIntStateOf(0) }

    LaunchedEffect(anchoredIndex, items.size) {
        rowState.animateToAnchoredItem(
            focusedIndex = anchoredIndex,
            itemCount = items.size,
            leadingItems = 0,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "More Like This",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = DetailSectionInset),
        )
        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxWidth().focusGroup(),
            contentPadding = PaddingValues(start = DetailSectionInset, end = 0.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                SimilarCard(
                    item,
                    modifier = if (index == 0) Modifier.focusRequester(firstRequester) else Modifier,
                    onFocused = { anchoredIndex = index },
                    onPressed = { onOpenDetail(item.type, item.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SimilarCard(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onPressed: () -> Unit,
) {
    Card(
        onClick = onPressed,
        modifier = modifier
            .size(width = 220.dp, height = 124.dp)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(
            containerColor = Color(0xFF181A1F),
            focusedContainerColor = Color(0xFF181A1F),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape = AppCardShape),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(AppCardShape),
        ) {
            AsyncImage(model = item.backdrop ?: item.poster, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors = listOf(Color.Transparent, Color(0xCC000000)))))
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        }
    }
}

@Composable
private fun ImdbBadge(rating: Double) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFF5C518)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("IMDb", color = Color(0xFF111111), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black))
        Text("%.1f".format(rating), color = Color(0xFF111111), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
    }
}

private fun SeasonEpisode.toEpisodeContext(seasonNumber: Int): EpisodeContext {
    return EpisodeContext(seasonNumber, episodeNumber, name, overview, still, runtime, airDate, id)
}

private fun isEpisodeReleased(airDate: String?): Boolean {
    val parsed = airDate
        ?.takeIf { it.isNotBlank() }
        ?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()
        }
        ?: return true
    return !parsed.isAfter(LocalDate.now())
}

private fun formatTime(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val remainder = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder) else "%d:%02d".format(minutes, remainder)
}

private fun formatRuntime(minutes: Int): String {
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours > 0 && remainder > 0 -> "${hours}h ${remainder}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

private fun formatReleaseDate(raw: String): String {
    return try {
        LocalDate.parse(raw).format(DateTimeFormatter.ofPattern("d MMM yyyy"))
    } catch (_: DateTimeParseException) {
        raw
    }
}

private suspend fun extractAmbientPalette(
    context: android.content.Context,
    imageUrl: String,
): AmbientBackdropPalette = withContext(Dispatchers.IO) {
    val request = ImageRequest.Builder(context)
        .data(imageUrl)
        .allowHardware(false)
        .crossfade(false)
        .size(320, 180)
        .build()
    val result = context.imageLoader.execute(request).drawable
    val bitmap = requireNotNull(result?.toBitmap(config = Bitmap.Config.ARGB_8888)) {
        "Could not decode ambient artwork"
    }
    val palette = Palette.Builder(bitmap)
        .clearFilters()
        .maximumColorCount(12)
        .generate()

    val fallback = Color(0xFF1A2633)
    val swatches = palette.swatches
        .sortedByDescending { it.population }

    val primary = swatches.firstOrNull()?.rgb?.let(::Color) ?: fallback
    val secondary = swatches
        .drop(1)
        .firstOrNull { colorDistance(primary, Color(it.rgb)) > 0.12f }
        ?.rgb
        ?.let(::Color)
        ?: swatches.getOrNull(1)?.rgb?.let(::Color)
        ?: primary

    AmbientBackdropPalette(
        leftGlow = primary.ambientize(boost = 1.04f),
        rightGlow = secondary.ambientize(),
        accentGlow = lerpColor(primary, secondary, 0.5f).ambientize(boost = 1.10f),
    )
}

private fun Color.ambientize(boost: Float = 1f): Color {
    val lightMix = if (luminance() < 0.24f) 0.20f else 0.10f
    return lerpColor(this, Color.White, lightMix)
        .let { lerpColor(it, Color.Black, 0.42f) }
        .copy(alpha = 1f)
        .saturate(boost)
}

private fun Color.saturate(factor: Float): Color {
    val grey = (red + green + blue) / 3f
    return Color(
        red = (grey + (red - grey) * factor).coerceIn(0f, 1f),
        green = (grey + (green - grey) * factor).coerceIn(0f, 1f),
        blue = (grey + (blue - grey) * factor).coerceIn(0f, 1f),
        alpha = alpha,
    )
}

private fun lerpColor(start: Color, end: Color, fraction: Float): Color {
    val clamped = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * clamped,
        green = start.green + (end.green - start.green) * clamped,
        blue = start.blue + (end.blue - start.blue) * clamped,
        alpha = start.alpha + (end.alpha - start.alpha) * clamped,
    )
}

private fun colorDistance(a: Color, b: Color): Float {
    val dr = a.red - b.red
    val dg = a.green - b.green
    val db = a.blue - b.blue
    return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble()).toFloat()
}

