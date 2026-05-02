package com.streamdek.tv.nativeapp.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.streamdek.tv.nativeapp.data.SeasonRef
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.AppPillShape
import kotlinx.coroutines.launch

private sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Ready(val detail: MediaDetail, val season: SeasonDetail?) : DetailUiState
    data class Error(val message: String) : DetailUiState
}

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
    var selectedSeason by remember(mediaType, mediaId) { mutableStateOf<SeasonDetail?>(null) }
    var selectedEpisodeIndex by remember(mediaType, mediaId) { mutableIntStateOf(0) }
    var progressFraction by remember(mediaType, mediaId) { mutableStateOf<Float?>(null) }
    var progressLabel by remember(mediaType, mediaId) { mutableStateOf<String?>(null) }
    var inWatchlist by remember(mediaType, mediaId) { mutableStateOf(false) }
    val verticalState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val playButtonRequester = remember(mediaType, mediaId) { FocusRequester() }

    LaunchedEffect(mediaType, mediaId) {
        uiState = DetailUiState.Loading
        val detail = repository.fetchDetail(mediaId, mediaType)
        if (detail == null) {
            uiState = DetailUiState.Error("Could not load title details")
            return@LaunchedEffect
        }
        val initialSeasonNumber = detail.seasons.firstOrNull()?.seasonNumber ?: 1
        selectedSeasonNumber = initialSeasonNumber
        val firstSeason = if (mediaType == "tv") repository.fetchSeason(mediaId, initialSeasonNumber) else null
        selectedSeason = firstSeason
        selectedEpisodeIndex = 0
        uiState = DetailUiState.Ready(detail, firstSeason)
    }

    val detail = (uiState as? DetailUiState.Ready)?.detail
    val seasonDetail = selectedSeason ?: (uiState as? DetailUiState.Ready)?.season
    val selectedEpisode = seasonDetail?.episodes?.getOrNull(selectedEpisodeIndex)

    fun currentEpisodeContext(): EpisodeContext? =
        selectedEpisode?.let {
            EpisodeContext(
                seasonNumber = selectedSeasonNumber,
                episodeNumber = it.episodeNumber,
                title = it.name,
                overview = it.overview,
                still = it.still,
                runtime = it.runtime,
                airDate = it.airDate,
                tmdbEpisodeId = it.id,
            )
        }

    LaunchedEffect(mediaType, mediaId, selectedEpisodeIndex, selectedSeasonNumber) {
        val progress = repository.fetchProgress(mediaType, mediaId, currentEpisodeContext())
        progressFraction = progress?.progress?.div(100.0)?.toFloat()?.coerceIn(0f, 1f)
        progressLabel = progress?.takeIf { it.positionSec > 0 && it.durationSec > 0 }?.let {
            "Continue Watching  ${formatTime(it.positionSec)} / ${formatTime(it.durationSec)}"
        }
    }

    LaunchedEffect(mediaType, mediaId) {
        inWatchlist = repository.fetchLibrary().watchlist.any { it.id == mediaId && it.type == mediaType }
    }

    LaunchedEffect(detail?.id, seasonDetail?.seasonNumber, selectedEpisodeIndex) {
        detail?.let { loaded ->
            buildList {
                loaded.backdrop?.let(::add)
                loaded.poster?.let(::add)
                loaded.titleLogo?.let(::add)
                loaded.cast.take(12).forEach { it.photo?.let(::add) }
                loaded.similarTitles.take(12).forEach {
                    it.backdrop?.let(::add)
                    it.poster?.let(::add)
                }
                seasonDetail?.episodes?.take(10)?.forEach { it.still?.let(::add) }
            }.distinct().forEach { url ->
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(url)
                        .memoryCacheKey(url)
                        .diskCacheKey(url)
                        .build(),
                )
            }
            if (repository.currentSession() != null) {
                runCatching {
                    repository.prefetchPlayback(
                        mediaType = loaded.type,
                        mediaId = loaded.id,
                        imdbId = loaded.imdbId,
                        episode = currentEpisodeContext(),
                    )
                }
            }
        }
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
                    .graphicsLayer {
                        renderEffect = BlurEffect(radiusX = 36f, radiusY = 36f)
                    }
                    .blur(30.dp),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x22000000)),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val leftFade = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF040404),
                            Color(0xF0040404),
                            Color(0x94040404),
                            Color.Transparent,
                        ),
                        endX = size.width * 0.56f,
                    )
                    val bottomFade = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0x55040404),
                            Color(0xE8040404),
                            Color(0xFF040404),
                        ),
                        startY = size.height * 0.64f,
                    )
                    onDrawBehind {
                        drawRect(leftFade)
                        drawRect(bottomFade)
                    }
                },
        )

        when (val state = uiState) {
            DetailUiState.Loading -> DetailLoading()
            is DetailUiState.Error -> DetailError(state.message)
            is DetailUiState.Ready -> {
                Row(modifier = Modifier.fillMaxSize()) {
                    DetailHeroPane(
                        detail = state.detail,
                        playButtonRequester = playButtonRequester,
                        progressFraction = progressFraction,
                        progressLabel = progressLabel,
                        inWatchlist = inWatchlist,
                        selectedEpisode = currentEpisodeContext(),
                        selectedEpisodeTitle = selectedEpisode?.name,
                        onBack = onBack,
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
                        onPlay = { episode ->
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
                        modifier = Modifier
                            .width(440.dp)
                            .fillMaxHeight()
                            .padding(start = 40.dp, top = 24.dp, end = 18.dp, bottom = 24.dp),
                    )

                    LazyColumn(
                        state = verticalState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(top = 32.dp, end = 24.dp, bottom = 24.dp),
                        contentPadding = PaddingValues(bottom = 64.dp),
                        verticalArrangement = Arrangement.spacedBy(26.dp),
                    ) {
                        if (mediaType == "tv" && state.detail.seasons.isNotEmpty()) {
                            item(key = "episodes") {
                                EpisodesSection(
                                    seasons = state.detail.seasons,
                                    selectedSeasonNumber = selectedSeasonNumber,
                                    seasonDetail = seasonDetail,
                                    selectedEpisodeIndex = selectedEpisodeIndex,
                                    onSeasonFocused = { seasonNumber ->
                                        if (selectedSeasonNumber != seasonNumber) {
                                            selectedSeasonNumber = seasonNumber
                                            selectedEpisodeIndex = 0
                                        }
                                    },
                                    onSeasonPressed = { seasonNumber ->
                                        if (selectedSeasonNumber != seasonNumber) {
                                            selectedSeasonNumber = seasonNumber
                                            selectedEpisodeIndex = 0
                                        }
                                    },
                                    onEpisodeFocused = { index ->
                                        selectedEpisodeIndex = index
                                    },
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
                            item(key = "cast") {
                                CastSection(state.detail.cast)
                            }
                        }

                        if (state.detail.similarTitles.isNotEmpty()) {
                            item(key = "similar") {
                                SimilarSection(
                                    items = state.detail.similarTitles,
                                    onOpenDetail = onOpenDetail,
                                )
                            }
                        }
                    }
                }

                LaunchedEffect(selectedSeasonNumber) {
                    if (mediaType == "tv") {
                        selectedSeason = repository.fetchSeason(mediaId, selectedSeasonNumber)
                    }
                }

                LaunchedEffect(state.detail.id) {
                    kotlinx.coroutines.delay(250)
                    playButtonRequester.requestFocus()
                }
            }
        }
    }
}

@Composable
private fun DetailLoading() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Loading details",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun DetailError(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Detail failed to load",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DetailHeroPane(
    detail: MediaDetail,
    playButtonRequester: FocusRequester,
    progressFraction: Float?,
    progressLabel: String?,
    inWatchlist: Boolean,
    selectedEpisode: EpisodeContext?,
    selectedEpisodeTitle: String?,
    onBack: () -> Unit,
    onTrailer: () -> Unit,
    onToggleWatchlist: suspend () -> Unit,
    onPlay: (EpisodeContext?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val meta = listOfNotNull(
        if (detail.type == "tv") "Series" else "Movie",
        detail.year,
        detail.genreNames.firstOrNull(),
    ).joinToString("  •  ")

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Button(
            onClick = onBack,
            shape = ButtonDefaults.shape(AppPillShape),
            colors = ButtonDefaults.colors(
                containerColor = Color(0xCC111317),
                contentColor = MaterialTheme.colorScheme.onBackground,
            ),
            modifier = Modifier
                .width(132.dp)
                .focusProperties { canFocus = false },
        ) {
            Text("Back")
        }

        detail.titleLogo?.takeIf { it.isNotBlank() }?.let { logoUrl ->
            AsyncImage(
                model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                    .data(logoUrl)
                    .crossfade(300)
                    .build(),
                contentDescription = detail.title,
                modifier = Modifier
                    .height(88.dp)
                    .fillMaxWidth(0.72f),
                contentScale = ContentScale.Fit,
            )
        } ?: Text(
            text = detail.title,
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.84f),
                )
            }
            detail.rating?.let { rating ->
                ImdbBadge(rating = rating)
            }
        }

        detail.tagline?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.76f),
            )
        }

        detail.description?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (!selectedEpisodeTitle.isNullOrBlank()) {
            Text(
                text = "Selected episode: $selectedEpisodeTitle",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFFF0BA66),
            )
        }

        Column(
            modifier = Modifier.padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Primary play / continue button
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                val hasProgress = (progressFraction ?: 0f) > 0f
                Button(
                    onClick = { onPlay(selectedEpisode) },
                    shape = ButtonDefaults.shape(
                        if (hasProgress)
                            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
                        else
                            RoundedCornerShape(16.dp),
                    ),
                    colors = ButtonDefaults.colors(
                        containerColor = Color(0xFFF4EDE2),
                        focusedContainerColor = Color.White,
                        contentColor = Color(0xFF18120A),
                    ),
                    modifier = Modifier
                        .width(280.dp)
                        .height(58.dp)
                        .focusRequester(playButtonRequester),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = when {
                                    hasProgress -> "Continue Watching"
                                    detail.type == "tv" && selectedEpisode != null ->
                                        "Play  S${selectedEpisode.seasonNumber} E${selectedEpisode.episodeNumber}"
                                    detail.type == "tv" -> "Play Episode"
                                    else -> "Play"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                            )
                            if (hasProgress && selectedEpisode != null) {
                                Text(
                                    text = "S${selectedEpisode.seasonNumber} E${selectedEpisode.episodeNumber}${selectedEpisodeTitle?.let { " • $it" } ?: ""}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0x8818120A),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (hasProgress) {
                    Box(
                        modifier = Modifier
                            .width(280.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                            .background(Color(0x2818120A)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction ?: 0f)
                                .height(4.dp)
                                .background(Color(0xFFB99352)),
                        )
                    }
                }
            }

            // Secondary actions row
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!detail.trailerKey.isNullOrBlank()) {
                    Button(
                        onClick = onTrailer,
                        shape = ButtonDefaults.shape(AppPillShape),
                        colors = ButtonDefaults.colors(
                            containerColor = Color(0xCC111317),
                            contentColor = MaterialTheme.colorScheme.onBackground,
                        ),
                    ) {
                        Text("Trailer")
                    }
                }
                Button(
                    onClick = { scope.launch { onToggleWatchlist() } },
                    shape = ButtonDefaults.shape(AppPillShape),
                    colors = ButtonDefaults.colors(
                        containerColor = if (inWatchlist) Color(0x2AF4EDE2) else Color(0xCC111317),
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                ) {
                    Icon(
                        imageVector = if (inWatchlist) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = if (inWatchlist) "In Watchlist" else "Add to Watchlist",
                    )
                }
            }
        }
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionTitle("Episodes")

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = 8.dp),
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

        seasonDetail?.episodes?.getOrNull(selectedEpisodeIndex)?.let { episode ->
            Text(
                text = "E${episode.episodeNumber} • ${episode.name}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            episode.overview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.74f),
                    modifier = Modifier.padding(horizontal = 8.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(seasonDetail?.episodes.orEmpty(), key = { _, episode -> episode.id }) { index, episode ->
                EpisodeCard(
                    episode = episode,
                    seasonNumber = selectedSeasonNumber,
                    selected = index == selectedEpisodeIndex,
                    onFocused = { onEpisodeFocused(index) },
                    onPressed = {
                        onEpisodePressed(
                            EpisodeContext(
                                seasonNumber = selectedSeasonNumber,
                                episodeNumber = episode.episodeNumber,
                                title = episode.name,
                                overview = episode.overview,
                                still = episode.still,
                                runtime = episode.runtime,
                                airDate = episode.airDate,
                                tmdbEpisodeId = episode.id,
                            ),
                        )
                    },
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
    var isFocused by remember { mutableStateOf(false) }
    Card(
        onClick = onPressed,
        modifier = Modifier.onFocusChanged {
            isFocused = it.isFocused
            if (it.isFocused) onFocused()
        },
        shape = CardDefaults.shape(AppPillShape),
        colors = CardDefaults.colors(
            containerColor = if (selected) Color(0x2AF4EDE2) else Color(0x15191D22),
            focusedContainerColor = Color(0xFF2A2D36),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                BorderStroke(2.dp, Color(0xFFF0BA66)),
                shape = AppPillShape,
            ),
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (isFocused) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EpisodeCard(
    episode: com.streamdek.tv.nativeapp.data.SeasonEpisode,
    seasonNumber: Int,
    selected: Boolean,
    onFocused: () -> Unit,
    onPressed: () -> Unit,
) {
    Card(
        onClick = onPressed,
        modifier = Modifier
            .size(width = 296.dp, height = 176.dp)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(
            containerColor = if (selected) Color(0x1EF4EDE2) else Color(0xFF181A1F),
            focusedContainerColor = Color(0xFF181A1F),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                BorderStroke(2.dp, Color(0xFFF0BA66)),
                shape = AppCardShape,
            ),
        ),
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
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0x22000000), Color(0xE0000000)),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "S$seasonNumber E${episode.episodeNumber}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFF0BA66),
                )
                Text(
                    text = episode.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CastSection(cast: List<CastMember>) {
    val castRowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("Cast")
        LazyRow(
            state = castRowState,
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(cast, key = { _, member -> member.id }) { index, member ->
                CastCard(
                    member = member,
                    onFocused = {
                        scope.launch {
                            castRowState.animateScrollToItem(index.coerceAtLeast(0))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SimilarSection(
    items: List<MediaItem>,
    onOpenDetail: (String, String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("More Like This")
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
                SimilarCard(item = item, onPressed = { onOpenDetail(item.type, item.id) })
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SimilarCard(
    item: MediaItem,
    onPressed: () -> Unit,
) {
    Card(
        onClick = onPressed,
        modifier = Modifier.size(width = 210.dp, height = 118.dp),
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(
            containerColor = Color(0xFF181A1F),
            focusedContainerColor = Color(0xFF181A1F),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                BorderStroke(2.dp, Color(0xFFF0BA66)),
                shape = AppCardShape,
            ),
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(AppCardShape),
        ) {
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
                            colors = listOf(Color.Transparent, Color(0xCC000000)),
                        ),
                    ),
            )
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CastCard(
    member: CastMember,
    onFocused: () -> Unit,
) {
    Card(
        onClick = {},
        modifier = Modifier
            .width(102.dp)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color(0x1811141B),
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
        border = CardDefaults.border(
            focusedBorder = Border(
                BorderStroke(2.dp, Color(0xFFF0BA66)),
                shape = AppCardShape,
            ),
        ),
    ) {
        Column(
            modifier = Modifier.padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AsyncImage(
                model = member.photo,
                contentDescription = member.name,
                modifier = Modifier
                    .size(94.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF15181D)),
                contentScale = ContentScale.Crop,
            )
            Text(
                text = member.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            member.character?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ImdbBadge(rating: Double) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF5C518))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "IMDb",
            color = Color(0xFF111111),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
        )
        Text(
            text = "%.1f".format(rating),
            color = Color(0xFF111111),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = 8.dp),
    )
}

private fun formatTime(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val remainder = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder) else "%d:%02d".format(minutes, remainder)
}
