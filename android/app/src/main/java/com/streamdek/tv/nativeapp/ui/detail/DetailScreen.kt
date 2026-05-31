package com.streamdek.tv.nativeapp.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
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
import kotlinx.coroutines.launch

private sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Ready(val detail: MediaDetail) : DetailUiState
    data class Error(val message: String) : DetailUiState
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
    var uiState by remember(mediaType, mediaId) { mutableStateOf<DetailUiState>(DetailUiState.Loading) }
    var selectedSeasonNumber by remember(mediaType, mediaId) { mutableIntStateOf(1) }
    var selectedEpisodeIndex by remember(mediaType, mediaId) { mutableIntStateOf(0) }
    var selectedSeason by remember(mediaType, mediaId) { mutableStateOf<SeasonDetail?>(null) }
    var resumeEpisodeContext by remember(mediaType, mediaId) { mutableStateOf<EpisodeContext?>(null) }
    var progressFraction by remember(mediaType, mediaId) { mutableStateOf<Float?>(null) }
    var progressLabel by remember(mediaType, mediaId) { mutableStateOf<String?>(null) }
    var inWatchlist by remember(mediaType, mediaId) { mutableStateOf(false) }
    var comments by remember(mediaType, mediaId) { mutableStateOf<List<TraktCommentItem>>(emptyList()) }
    val playButtonRequester = remember(mediaType, mediaId) { FocusRequester() }
    val commentsRequester = remember(mediaType, mediaId) { FocusRequester() }
    val detailListState = rememberLazyListState()
    val noScrollResponder = remember {
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect = localRect
            override suspend fun bringChildIntoView(localRect: () -> Rect?) { }
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(mediaType, mediaId) {
        uiState = DetailUiState.Loading
        val detail = repository.fetchDetail(mediaId, mediaType)
        if (detail == null) {
            uiState = DetailUiState.Error("Could not load title details")
            return@LaunchedEffect
        }
        selectedSeasonNumber = detail.seasons.firstOrNull()?.seasonNumber ?: 1
        selectedEpisodeIndex = 0
        selectedSeason = if (mediaType == "tv" && detail.seasons.isNotEmpty()) {
            repository.fetchSeason(mediaId, selectedSeasonNumber)
        } else {
            null
        }
        uiState = DetailUiState.Ready(detail)
    }

    val detail = (uiState as? DetailUiState.Ready)?.detail
    val selectedEpisode = selectedSeason?.episodes?.getOrNull(selectedEpisodeIndex)

    fun currentEpisodeContext(): EpisodeContext? = selectedEpisode?.toEpisodeContext(selectedSeasonNumber)

    LaunchedEffect(selectedSeasonNumber, detail?.id) {
        if (detail?.type == "tv") {
            selectedSeason = repository.fetchSeason(detail.id, selectedSeasonNumber)
        }
    }

    LaunchedEffect(mediaType, mediaId, selectedSeasonNumber, selectedEpisodeIndex, resumeEpisodeContext) {
        val progressEpisode = if (mediaType == "tv") resumeEpisodeContext ?: currentEpisodeContext() else currentEpisodeContext()
        val progress = repository.fetchProgress(mediaType, mediaId, progressEpisode)
        progressFraction = progress?.progress?.div(100.0)?.toFloat()?.coerceIn(0f, 1f)
        progressLabel = progress?.takeIf { it.positionSec > 0 && it.durationSec > 0 }?.let {
            "${formatTime(it.positionSec)} / ${formatTime(it.durationSec)}"
        }
    }

    LaunchedEffect(mediaType, mediaId) {
        val library = repository.fetchLibrary()
        inWatchlist = library.watchlist.any { it.id == mediaId && it.type == mediaType }
        resumeEpisodeContext = library.continueWatching
            .firstOrNull { it.id == mediaId && it.type == mediaType }
            ?.episode
        comments = repository.fetchTraktComments(mediaId, mediaType)
    }

    LaunchedEffect(detail?.id, selectedSeasonNumber, selectedEpisodeIndex) {
        detail ?: return@LaunchedEffect
        buildList {
            detail.backdrop?.let(::add)
            detail.poster?.let(::add)
            detail.titleLogo?.let(::add)
            detail.cast.take(10).forEach { it.photo?.let(::add) }
            detail.similarTitles.take(10).forEach {
                it.backdrop?.let(::add)
                it.poster?.let(::add)
            }
            comments.take(6).forEach { it.avatar?.let(::add) }
        }.distinct().forEach { url ->
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

    LaunchedEffect(mediaType, mediaId) {
        detailListState.scrollToItem(0)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (!detail?.backdrop.isNullOrBlank()) {
            AsyncImage(
                model = detail?.backdrop,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { renderEffect = BlurEffect(radiusX = 34f, radiusY = 34f) }
                    .blur(28.dp),
                contentScale = ContentScale.Crop,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val leftFade = Brush.horizontalGradient(
                        colors = listOf(Color(0xF0040404), Color(0xAA040404), Color.Transparent),
                        endX = size.width * 0.58f,
                    )
                    val verticalFade = Brush.verticalGradient(
                        colors = listOf(Color(0x55040404), Color(0xC8040404), Color(0xFF040404)),
                        startY = size.height * 0.16f,
                    )
                    onDrawBehind {
                        drawRect(leftFade)
                        drawRect(verticalFade)
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
                    contentPadding = PaddingValues(start = 42.dp, end = 42.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(26.dp),
                ) {
                    item("top-anchor") {
                        Box(modifier = Modifier.fillMaxWidth().height(36.dp))
                    }
                    item("hero") {
                        // Wrapped in no-op BringIntoViewResponder so focusing Back/CTA/Trailer/Watchlist
                        // doesn't trigger LazyColumn to auto-scroll to center them.
                        Box(
                            modifier = Modifier
                                .bringIntoViewResponder(noScrollResponder)
                                .onFocusChanged { state ->
                                    if (state.hasFocus && detailListState.firstVisibleItemIndex > 0) {
                                        scope.launch { detailListState.animateScrollToItem(0) }
                                    }
                                },
                        ) {
                            HeroSection(
                                detail = state.detail,
                                selectedEpisode = currentEpisodeContext(),
                                progressFraction = progressFraction,
                                progressLabel = progressLabel,
                                inWatchlist = inWatchlist,
                                playButtonRequester = playButtonRequester,
                                onTrailer = {
                                    val trailerUrl = when {
                                        state.detail.trailerKey.isNullOrBlank() -> null
                                        state.detail.trailerSite.equals("Vimeo", ignoreCase = true) ->
                                            "https://player.vimeo.com/video/${state.detail.trailerKey}"
                                        else -> "https://www.youtube.com/watch?v=${state.detail.trailerKey}"
                                    }
                                    trailerUrl?.let {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        })
                                    }
                                },
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
                                                episode = if (state.detail.type == "tv" && (progressFraction ?: 0f) > 0f) {
                                                    resumeEpisodeContext ?: currentEpisodeContext()
                                                } else {
                                                    currentEpisodeContext()
                                                },
                                                title = state.detail.title,
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                    }

                    item("details") {
                        Box(modifier = Modifier.bringIntoViewResponder(noScrollResponder)) {
                            DetailsPanel(
                                detail = state.detail,
                                selectedEpisode = selectedEpisode,
                                progressLabel = progressLabel,
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

                    if (state.detail.type == "tv" && state.detail.seasons.isNotEmpty()) {
                        item("episodes") {
                            EpisodesSection(
                                seasons = state.detail.seasons,
                                selectedSeasonNumber = selectedSeasonNumber,
                                seasonDetail = selectedSeason,
                                selectedEpisodeIndex = selectedEpisodeIndex,
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
                            )
                        }
                    }

                    if (state.detail.cast.isNotEmpty()) {
                        item("cast") { CastSection(state.detail.cast) }
                    }

                    if (state.detail.similarTitles.isNotEmpty()) {
                        item("similar") { SimilarSection(state.detail.similarTitles, onOpenDetail) }
                    }
                }

                LaunchedEffect(state.detail.id) {
                    kotlinx.coroutines.delay(220)
                    playButtonRequester.requestFocus()
                    kotlinx.coroutines.delay(50)
                    detailListState.scrollToItem(0)
                }
            }
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
private fun HeroSection(
    detail: MediaDetail,
    selectedEpisode: EpisodeContext?,
    progressFraction: Float?,
    progressLabel: String?,
    inWatchlist: Boolean,
    playButtonRequester: FocusRequester,
    onTrailer: () -> Unit,
    onToggleWatchlist: suspend () -> Unit,
    onPlay: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        detail.rating?.let { rating ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.Top,
            ) {
                ImdbBadge(rating)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .padding(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            detail.titleLogo?.takeIf { it.isNotBlank() }?.let { logo ->
                AsyncImage(
                    model = logo,
                    contentDescription = detail.title,
                    modifier = Modifier
                        .height(58.dp)
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

            Text(
                text = buildList {
                    detail.year?.let(::add)
                    detail.genreNames.take(3).joinToString(" • ").takeIf { it.isNotBlank() }?.let(::add)
                    selectedEpisode?.let { add("S${it.seasonNumber} E${it.episodeNumber}") }
                }.joinToString("  •  "),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            )

            detail.tagline?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFFF0BA66),
                )
            }

            detail.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.86f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.Start),
                verticalAlignment = Alignment.Top,
            ) {
                ContinuePlayButton(
                    detail = detail,
                    selectedEpisode = selectedEpisode,
                    progressLabel = progressLabel,
                    progressFraction = progressFraction,
                    playButtonRequester = playButtonRequester,
                    onPlay = onPlay,
                    modifier = Modifier.width(236.dp),
                )
                if (!detail.trailerKey.isNullOrBlank()) {
                    Button(
                        onClick = onTrailer,
                        shape = ButtonDefaults.shape(AppPillShape),
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xFFF0BA66),
                            focusedContainerColor = Color(0xFFFFD792),
                            contentColor = Color(0xFF18120A),
                            focusedContentColor = Color(0xFF18120A),
                        ),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                "Trailer",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black),
                            )
                        }
                    }
                }
                Button(
                    onClick = { scope.launch { onToggleWatchlist() } },
                    shape = ButtonDefaults.shape(AppPillShape),
                    colors = ButtonDefaults.colors(
                        containerColor = if (inWatchlist) Color(0xFFF0BA66) else Color(0xD62A3442),
                        focusedContainerColor = if (inWatchlist) Color(0xFFFFD792) else Color(0xFF44566E),
                        contentColor = if (inWatchlist) Color(0xFF18120A) else MaterialTheme.colorScheme.onBackground,
                        focusedContentColor = if (inWatchlist) Color(0xFF18120A) else MaterialTheme.colorScheme.onBackground,
                    ),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (inWatchlist) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            if (inWatchlist) "In Watchlist" else "Watchlist",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black),
                        )
                    }
                }
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
        detail.releaseDate?.takeIf { it.isNotBlank() }?.let { add("Release" to it) }
        detail.runtime?.takeIf { it > 0 }?.let { add("Runtime" to "$it min") }
        detail.status?.takeIf { it.isNotBlank() }?.let { add("Status" to it) }
        detail.numberOfSeasons?.takeIf { it > 0 }?.let { add("Seasons" to it.toString()) }
        detail.numberOfEpisodes?.takeIf { it > 0 }?.let { add("Episodes" to it.toString()) }
        selectedEpisode?.let { add("Episode" to "E${it.episodeNumber} ${it.name}") }
    }

    if (items.isEmpty() && detail.tagline.isNullOrBlank()) return

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
        val targetIndex = anchoredIndex.coerceIn(0, (visibleComments.size - 1).coerceAtLeast(0))
        if (visibleComments.isNotEmpty() && rowState.firstVisibleItemIndex != targetIndex) {
            rowState.scrollToItem(targetIndex)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Trakt Comments",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
        )
        LazyRow(
            state = rowState,
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = 4.dp),
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
            focusedBorder = Border(BorderStroke(2.dp, Color(0xFFF0BA66)), shape = AppCardShape),
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


@Composable
private fun ContinuePlayButton(
    detail: MediaDetail,
    selectedEpisode: EpisodeContext?,
    progressLabel: String?,
    progressFraction: Float?,
    playButtonRequester: FocusRequester,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasProgress = (progressFraction ?: 0f) > 0f
    val title = when {
        hasProgress -> "Continue Watching"
        detail.type == "tv" && selectedEpisode != null -> "Choose Stream"
        detail.type == "tv" -> "Choose Stream"
        else -> "Play"
    }
    val subtitle = when {
        hasProgress -> progressLabel
        detail.type == "tv" && selectedEpisode != null -> "S${selectedEpisode.seasonNumber} E${selectedEpisode.episodeNumber}${selectedEpisode.title?.let { "  •  $it" } ?: ""}"
        else -> null
    }
    Button(
        onClick = onPlay,
        shape = ButtonDefaults.shape(AppPillShape),
        colors = ButtonDefaults.colors(
            containerColor = Color(0xFFF4EDE2),
            focusedContainerColor = Color.White,
            contentColor = Color(0xFF18120A),
        ),
        modifier = modifier
            .focusRequester(playButtonRequester)
            .height(56.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier.padding(start = 10.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = MaterialTheme.typography.labelMedium.fontSize * 0.6f,
                        ),
                        color = Color(0xAA18120A),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } 
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun BackPill(onBack: () -> Unit) {
    Card(
        onClick = onBack,
        shape = CardDefaults.shape(AppPillShape),
        colors = CardDefaults.colors(
            containerColor = Color(0xCC111317),
            focusedContainerColor = Color(0xFF1B2028),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color(0xFFF0BA66)), shape = AppPillShape),
        ),
    ) {
        Text(
            text = "Back",
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun EpisodesSection(
    seasons: List<SeasonRef>,
    selectedSeasonNumber: Int,
    seasonDetail: SeasonDetail?,
    selectedEpisodeIndex: Int,
    onSeasonFocused: (Int) -> Unit,
    onSeasonPressed: (Int) -> Unit,
    onEpisodeFocused: (Int) -> Unit,
    onEpisodePressed: (EpisodeContext) -> Unit,
) {
    val episodes = seasonDetail?.episodes.orEmpty()
    val rowState = rememberLazyListState()

    LaunchedEffect(selectedEpisodeIndex, episodes.size, selectedSeasonNumber) {
        val targetIndex = selectedEpisodeIndex.coerceIn(0, (episodes.size - 1).coerceAtLeast(0))
        if (episodes.isNotEmpty() && rowState.firstVisibleItemIndex != targetIndex) {
            rowState.scrollToItem(targetIndex)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "Episodes",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().focusGroup(),
            contentPadding = PaddingValues(horizontal = 4.dp),
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
            contentPadding = PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(episodes, key = { _, episode -> episode.id }) { index, episode ->
                EpisodeCard(
                    episode = episode,
                    seasonNumber = selectedSeasonNumber,
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
private fun SeasonChip(title: String, selected: Boolean, onFocused: () -> Unit, onPressed: () -> Unit) {
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
            focusedBorder = Border(BorderStroke(2.dp, Color(0xFFF0BA66)), shape = AppPillShape),
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
    selected: Boolean,
    onFocused: () -> Unit,
    onPressed: () -> Unit,
) {
    Card(
        onClick = onPressed,
        modifier = Modifier.size(width = 300.dp, height = 182.dp).onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(
            containerColor = if (selected) Color(0x1EF4EDE2) else Color(0xFF181A1F),
            focusedContainerColor = Color(0xFF181A1F),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color(0xFFF0BA66)), shape = AppCardShape),
        ),
        scale = CardDefaults.scale(focusedScale = 1.025f),
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(AppCardShape)) {
            AsyncImage(model = episode.still, contentDescription = episode.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(colors = listOf(Color.Transparent, Color(0x30000000), Color(0xE2000000))),
                ),
            )
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("S$seasonNumber E${episode.episodeNumber}", style = MaterialTheme.typography.labelLarge, color = Color(0xFFF0BA66))
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
        }
    }
}

@Composable
private fun CastSection(cast: List<CastMember>) {
    val firstRequester = remember { FocusRequester() }
    val rowState = rememberLazyListState()
    var anchoredIndex by remember(cast) { mutableIntStateOf(0) }

    LaunchedEffect(anchoredIndex, cast.size) {
        val targetIndex = anchoredIndex.coerceIn(0, (cast.size - 1).coerceAtLeast(0))
        if (cast.isNotEmpty() && rowState.firstVisibleItemIndex != targetIndex) {
            rowState.scrollToItem(targetIndex)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Cast",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
        )
        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxWidth().focusGroup(),
            contentPadding = PaddingValues(horizontal = 4.dp),
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
    val cardShape = RoundedCornerShape(14.dp)
    Card(
        onClick = {},
        modifier = Modifier
            .width(90.dp)
            .then(if (requestFocus != null) Modifier.focusRequester(requestFocus) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(cardShape),
        colors = CardDefaults.colors(
            containerColor = Color(0xFF15181D),
            focusedContainerColor = Color(0xFF1E2128),
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color(0xFFF0BA66)), shape = cardShape),
        ),
    ) {
        Column {
            AsyncImage(
                model = member.photo,
                contentDescription = member.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(Color(0xFF15181D)),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                member.character?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SimilarSection(items: List<MediaItem>, onOpenDetail: (String, String) -> Unit) {
    val firstRequester = remember { FocusRequester() }
    val rowState = rememberLazyListState()
    var anchoredIndex by remember(items) { mutableIntStateOf(0) }

    LaunchedEffect(anchoredIndex, items.size) {
        val targetIndex = anchoredIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (items.isNotEmpty() && rowState.firstVisibleItemIndex != targetIndex) {
            rowState.scrollToItem(targetIndex)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "More Like This",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
        )
        LazyRow(
            state = rowState,
            modifier = Modifier.fillMaxWidth().focusGroup(),
            contentPadding = PaddingValues(horizontal = 4.dp),
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
        colors = CardDefaults.colors(containerColor = Color(0xFF181A1F), focusedContainerColor = Color(0xFF181A1F)),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color(0xFFF0BA66)), shape = AppCardShape),
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Box(modifier = Modifier.fillMaxSize().clip(AppCardShape)) {
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

private fun formatTime(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val remainder = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder) else "%d:%02d".format(minutes, remainder)
}
