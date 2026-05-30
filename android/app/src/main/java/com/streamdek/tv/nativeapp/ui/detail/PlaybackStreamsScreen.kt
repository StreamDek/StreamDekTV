package com.streamdek.tv.nativeapp.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.streamdek.tv.nativeapp.data.AddonStream
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.PlaybackRequest
import com.streamdek.tv.nativeapp.data.ResolvedPlaybackCandidate
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.AppPillShape

private sealed interface PlaybackStreamsUiState {
    data object Loading : PlaybackStreamsUiState
    data class Ready(val detail: MediaDetail?, val candidate: ResolvedPlaybackCandidate) : PlaybackStreamsUiState
    data class Error(val message: String) : PlaybackStreamsUiState
}

@Composable
fun PlaybackStreamsScreen(
    repository: StreamDekRepository,
    request: PlaybackRequest,
    onBack: () -> Unit,
    onPlayRequest: (PlaybackRequest) -> Unit,
) {
    var uiState by remember(request) { mutableStateOf<PlaybackStreamsUiState>(PlaybackStreamsUiState.Loading) }
    var detail by remember(request) { mutableStateOf<MediaDetail?>(null) }
    val firstCardRequester = remember(request) { FocusRequester() }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(request) {
        uiState = PlaybackStreamsUiState.Loading
        detail = repository.fetchDetail(request.mediaId, request.mediaType)
        runCatching {
            val candidate = repository.resolvePlayback(
                request.mediaType,
                request.mediaId,
                request.imdbId,
                request.episode,
                preferredStreamKey = request.selectedStreamKey,
                forceRefresh = false,
            )
            PlaybackStreamsUiState.Ready(detail, candidate)
        }.onSuccess {
            uiState = it
        }.onFailure {
            uiState = PlaybackStreamsUiState.Error(it.message ?: "Could not load streams")
        }
    }

    LaunchedEffect(uiState) {
        val ready = uiState as? PlaybackStreamsUiState.Ready ?: return@LaunchedEffect
        buildList {
            ready.detail?.backdrop?.let(::add)
            ready.detail?.poster?.let(::add)
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
        if (ready.candidate.streams.isNotEmpty()) {
            kotlinx.coroutines.delay(180)
            firstCardRequester.requestFocus()
        }
    }

    val ready = uiState as? PlaybackStreamsUiState.Ready
    val overview = request.episode?.overview ?: detail?.description
    val streamRows = ready?.candidate?.streams.orEmpty()
    val addonNames = remember(streamRows) {
        streamRows.map { it.addonName.ifBlank { "Other" } }.distinct()
    }
    val sourceTabs = remember(addonNames) {
        if (addonNames.size <= 1) emptyList() else listOf("All") + addonNames
    }
    var selectedTab by remember(streamRows) { mutableStateOf("All") }
    val filteredStreams = if (sourceTabs.isEmpty() || selectedTab == "All") streamRows
        else streamRows.filter { it.addonName.ifBlank { "Other" } == selectedTab }

    LaunchedEffect(selectedTab) {
        if (filteredStreams.isNotEmpty()) {
            kotlinx.coroutines.delay(80)
            firstCardRequester.requestFocus()
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
                    .graphicsLayer { renderEffect = BlurEffect(radiusX = 28f, radiusY = 28f) }
                    .blur(22.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val leftFade = Brush.horizontalGradient(
                        colors = listOf(Color(0xF0040404), Color(0xC0040404), Color.Transparent),
                        endX = size.width * 0.62f,
                    )
                    val verticalFade = Brush.verticalGradient(
                        colors = listOf(Color(0x65040404), Color(0xC8040404), Color(0xFF040404)),
                        startY = size.height * 0.18f,
                    )
                    onDrawBehind {
                        drawRect(leftFade)
                        drawRect(verticalFade)
                    }
                },
        )

        when (val state = uiState) {
            PlaybackStreamsUiState.Loading -> {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 40.dp, end = 40.dp, top = 36.dp, bottom = 44.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .width(430.dp)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        StreamsInfoPanel(detail, request, overview)
                    }
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentPadding = PaddingValues(bottom = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item("title") {
                            Text(
                                text = "Streams",
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        item("loading") {
                            LoadingStreamsCard()
                        }
                    }
                }
            }
            is PlaybackStreamsUiState.Error -> DetailError(state.message)
            is PlaybackStreamsUiState.Ready -> {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 40.dp, end = 40.dp, top = 36.dp, bottom = 44.dp),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .width(430.dp)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        StreamsInfoPanel(detail, request, overview)
                    }

                    // Right column: title + tabs pinned, stream cards scroll below
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = if (filteredStreams.isEmpty() && sourceTabs.isEmpty()) "No streams found" else "Streams",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        if (sourceTabs.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().focusGroup(),
                                contentPadding = PaddingValues(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                itemsIndexed(sourceTabs) { _, tab ->
                                    SourceTabChip(
                                        label = tab,
                                        selected = tab == selectedTab,
                                        onClick = { selectedTab = tab },
                                    )
                                }
                            }
                        }
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(top = 10.dp, bottom = 48.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            if (filteredStreams.isEmpty()) {
                                item("empty") { EmptyStreamsMessage() }
                            } else {
                                itemsIndexed(filteredStreams, key = { index, stream -> "${repository.streamSelectionKey(stream)}:$index:$selectedTab" }) { index, stream ->
                                    StreamOptionCard(
                                        stream = stream,
                                        label = repository.describeStreamOption(stream),
                                        requestFocus = if (index == 0) firstCardRequester else null,
                                        onPressed = {
                                            onPlayRequest(
                                                request.copy(
                                                    selectedStreamKey = repository.streamSelectionKey(stream),
                                                    selectedStreamLabel = repository.describeStreamOption(stream),
                                                ),
                                            )
                                        },
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SourceTabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.isFocused },
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
            text = label,
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
            ),
            color = if (selected || focused) MaterialTheme.colorScheme.onBackground
                else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.70f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun LoadingStreamsCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0x1611141B))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Finding streams",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Searching your configured sources…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
        )
    }
}

@Composable
private fun StreamsInfoPanel(detail: MediaDetail?, request: PlaybackRequest, overview: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0x1611141B))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        detail?.titleLogo?.takeIf { it.isNotBlank() }?.let { logo ->
            AsyncImage(
                model = logo,
                contentDescription = detail.title,
                modifier = Modifier
                    .height(84.dp)
                    .fillMaxWidth(),
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center,
            )
        } ?: Text(
            text = detail?.title ?: request.title ?: "Playback",
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
        )

        request.episode?.let {
            Text(
                text = "S${it.seasonNumber} E${it.episodeNumber}${it.title?.let { name -> "  •  $name" } ?: ""}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFFF0BA66),
            )
        }

        val meta = buildList {
            detail?.year?.let(::add)
            detail?.runtime?.takeIf { it > 0 }?.let { add("${it}m") }
            detail?.rating?.let { add("IMDb ${"%.1f".format(it)}") }
        }.joinToString("  •  ")

        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.74f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }

        overview?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.84f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
            )
        }
    }
}

@Composable
private fun EmptyStreamsMessage() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0x1AFFFFFF))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "No playable links are available right now.",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Try again in a moment, change addons, or pick another title.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StreamOptionCard(
    stream: AddonStream,
    label: String,
    requestFocus: FocusRequester?,
    onPressed: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val chips = buildList {
        stream.quality?.takeIf { it.isNotBlank() }?.let(::add)
        stream.size?.takeIf { it.isNotBlank() }?.let(::add)
        if (stream.cachedBy.isNotEmpty()) add("Cached: ${stream.cachedBy.joinToString()}") else if (!stream.url.isNullOrBlank()) add("Direct")
    }

    Card(
        onClick = onPressed,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (requestFocus != null) Modifier.focusRequester(requestFocus) else Modifier)
            .onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(
            containerColor = Color(0x19161A20),
            focusedContainerColor = Color(0xFF181A1F),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color(0xFFF0BA66)), shape = AppCardShape),
        ),
        scale = CardDefaults.scale(focusedScale = 1.015f),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stream.addonName.ifBlank { "Stream source" },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                        color = if (focused) Color(0xFFF7E4B8) else MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.74f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "Play",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
                    color = Color(0xFFF0BA66),
                )
            }
            if (chips.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.take(4).forEach { chip ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color(0x20F4EDE2))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = chip,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                            )
                        }
                    }
                }
            }
        }
    }
}
