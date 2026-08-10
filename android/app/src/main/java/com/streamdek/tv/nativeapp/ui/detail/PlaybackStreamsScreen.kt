package com.streamdek.tv.nativeapp.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.streamdek.tv.nativeapp.data.AddonStream
import com.streamdek.tv.nativeapp.data.FusionBadgeSource
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.PlaybackRequest
import com.streamdek.tv.nativeapp.data.ResolvedPlaybackCandidate
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.StreamsPreferences
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import com.streamdek.tv.nativeapp.data.flattenFusionBadges
import com.streamdek.tv.nativeapp.data.matchFusionBadges
import com.streamdek.tv.nativeapp.data.mergeProgressiveStreamSnapshot
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.FusionBadgeRow
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.TvMotion
import com.streamdek.tv.nativeapp.ui.TvSkeletonBox
import com.streamdek.tv.nativeapp.ui.TvSpacing
import com.streamdek.tv.nativeapp.ui.search.SearchChip

private sealed interface PlaybackStreamsUiState {
    data class Loading(val pendingSources: Int = 0) : PlaybackStreamsUiState
    data class Ready(
        val detail: MediaDetail?,
        val candidate: ResolvedPlaybackCandidate,
        /** Addons still to answer; results render while this is above zero. */
        val pendingSources: Int = 0,
    ) : PlaybackStreamsUiState
    data class Error(val message: String) : PlaybackStreamsUiState
}

private val StreamsInset = TvSpacing.ScreenHorizontal

private val QualityPattern = Regex("""(2160p|1080p|720p|480p|4k|uhd)""", RegexOption.IGNORE_CASE)
private val SizePattern = Regex("""(\d+(?:[.,]\d+)?)\s?(GB|MB)""", RegexOption.IGNORE_CASE)

/**
 * Quality and size as shown in the columns.
 *
 * Add-ons rarely populate the dedicated fields — the information is almost always inside the
 * release name — so the row fell back to a dash on nearly every line. Reading it out of the label
 * makes the columns worth having; when a value genuinely is not there the column is dropped for
 * the whole list rather than filled with placeholders.
 */
internal fun streamQualityLabel(stream: AddonStream, label: String): String? =
    stream.quality?.takeIf { it.isNotBlank() }
        ?: QualityPattern.find(label)?.value?.uppercase()?.replace("UHD", "2160P")

internal fun streamSizeLabel(stream: AddonStream, label: String): String? =
    stream.size?.takeIf { it.isNotBlank() }
        ?: SizePattern.find(label)?.let { "${it.groupValues[1]} ${it.groupValues[2].uppercase()}" }

/**
 * Stream picker.
 *
 * This is a decision list, not a browse screen, and it is rebuilt to read like one. The layout it
 * replaces gave a fixed 430dp column to metadata the viewer had just read on the detail page and
 * left the streams — the only thing being chosen between — squeezed into what was left. Here the
 * title compresses to one header strip and the list gets the width.
 *
 * Each row is laid out on a fixed grid: source, then description, then quality, size and
 * availability in aligned columns. Scanning down a column to compare is the whole task, and the
 * previous free-flowing chips made that impossible — the same fact sat in a different place on
 * every row.
 */
@Composable
fun PlaybackStreamsScreen(
    repository: StreamDekRepository,
    request: PlaybackRequest,
    onBack: () -> Unit,
    onPlayRequest: (PlaybackRequest) -> Unit,
) {
    val cachedDetail = remember(request) { repository.peekCachedDetail(request.mediaId, request.mediaType) }
    val cachedCandidate = remember(request) { repository.peekCachedResolvedPlayback(request) }
    var uiState by remember(request) {
        mutableStateOf<PlaybackStreamsUiState>(
            cachedCandidate?.let { PlaybackStreamsUiState.Ready(cachedDetail, it) } ?: PlaybackStreamsUiState.Loading(),
        )
    }
    var detail by remember(request) { mutableStateOf(cachedDetail) }
    var refreshGeneration by remember(request) { mutableIntStateOf(0) }
    val firstCardRequester = remember(request) { FocusRequester() }
    val firstTabRequester = remember(request) { FocusRequester() }
    val context = androidx.compose.ui.platform.LocalContext.current

    val bootstrap by repository.bootstrap.collectAsState()
    val fusionBadgeSourcesByUrl by repository.fusionBadgeSources.collectAsState()
    val streamsPrefs = bootstrap?.preferences?.streams ?: StreamsPreferences()
    val activeFusionBadgeSources = remember(
        streamsPrefs.fusionBadgeUrls, streamsPrefs.activeFusionBadgeUrl, fusionBadgeSourcesByUrl,
    ) {
        val urls = streamsPrefs.fusionBadgeUrls
        val active = streamsPrefs.activeFusionBadgeUrl
        // When multiple imports exist and one was chosen as active, match badges against that
        // source only; otherwise merge all configured sources.
        val effectiveUrls = if (urls.size > 1 && !active.isNullOrBlank() && urls.contains(active)) listOf(active) else urls
        effectiveUrls.mapNotNull { fusionBadgeSourcesByUrl[it] }
    }

    LaunchedEffect(Unit) { repository.ensureFusionBadgeSourcesLoaded() }

    // Metadata loads alongside the stream lookup rather than gating it, so the first streams can
    // render as soon as any addon answers.
    LaunchedEffect(request) {
        if (detail == null) {
            detail = runCatching { repository.fetchDetail(request.mediaId, request.mediaType) }.getOrNull()
        }
    }

    LaunchedEffect(request, refreshGeneration) {
        cachedCandidate?.takeIf { refreshGeneration == 0 }?.let { candidate ->
            uiState = PlaybackStreamsUiState.Ready(detail, candidate)
            return@LaunchedEffect
        }
        uiState = PlaybackStreamsUiState.Loading()
        var accumulatedStreams = emptyList<AddonStream>()
        TvDebugLogger.i(
            "Streams",
            "lookup start title=${request.title.orEmpty()} media=${request.mediaType}:${request.mediaId} generation=$refreshGeneration",
        )
        runCatching {
            repository.streamCandidates(
                mediaType = request.mediaType,
                mediaId = request.mediaId,
                imdbId = request.imdbId,
                episode = request.episode,
                preferredStreamKey = request.selectedStreamKey,
                streamType = request.streamType,
                directStreamUrl = request.directStreamUrl,
                requestHeaders = request.requestHeaders,
                sourceAddonId = request.sourceAddonId,
                sourceAddonName = request.sourceAddonName,
                forceRefresh = refreshGeneration > 0,
            ).collect { progress ->
                accumulatedStreams = mergeProgressiveStreamSnapshot(accumulatedStreams, progress.streams)
                TvDebugLogger.i(
                    "Streams",
                    "lookup progress media=${request.mediaType}:${request.mediaId} generation=$refreshGeneration " +
                        "streams=${accumulatedStreams.size} addons=${accumulatedStreams.map { it.addonId.ifBlank { it.addonName } }.distinct().size} " +
                        "pending=${progress.pendingSources} done=${progress.done}",
                )
                // Stay on the loading state only until the first stream arrives.
                if (accumulatedStreams.isEmpty() && !progress.done) {
                    uiState = PlaybackStreamsUiState.Loading(progress.pendingSources)
                    return@collect
                }
                uiState = PlaybackStreamsUiState.Ready(
                    detail = detail,
                    candidate = ResolvedPlaybackCandidate(null, null, accumulatedStreams),
                    pendingSources = progress.pendingSources,
                )
            }
        }.onFailure {
            if (uiState !is PlaybackStreamsUiState.Ready) {
                uiState = PlaybackStreamsUiState.Error(it.message ?: "Could not load streams")
            }
        }
    }

    LaunchedEffect(detail) {
        (uiState as? PlaybackStreamsUiState.Ready)?.let { ready ->
            if (ready.detail == null && detail != null) uiState = ready.copy(detail = detail)
        }
        detail?.backdrop?.let { url ->
            context.imageLoader.enqueue(
                ImageRequest.Builder(context).data(url).memoryCacheKey(url).diskCacheKey(url)
                    .crossfade(false).allowHardware(true).build(),
            )
        }
    }

    // Focus the first row once, when results first appear. Later batches must not yank focus back
    // while the viewer is already reading the list.
    var initialFocusApplied by remember(request) { mutableStateOf(false) }
    val hasStreams = (uiState as? PlaybackStreamsUiState.Ready)?.candidate?.streams?.isNotEmpty() == true
    LaunchedEffect(hasStreams) {
        if (hasStreams && !initialFocusApplied) {
            kotlinx.coroutines.delay(120)
            runCatching { firstCardRequester.requestFocus() }
            initialFocusApplied = true
        }
    }

    val ready = uiState as? PlaybackStreamsUiState.Ready
    val pendingSourceCount = when (val state = uiState) {
        is PlaybackStreamsUiState.Loading -> state.pendingSources
        is PlaybackStreamsUiState.Ready -> state.pendingSources
        is PlaybackStreamsUiState.Error -> 0
    }
    val streamRows = ready?.candidate?.streams.orEmpty().filter { stream ->
        if (!repository.isPlayableStreamOption(stream)) {
            false
        } else if (request.mediaType == "live") {
            true
        } else {
            val url = stream.url
            if (url != null && stream.infoHash == null && stream.size == null) {
                !url.substringBefore('?').lowercase().endsWith(".m3u8")
            } else {
                true
            }
        }
    }
    val addonNames = remember(streamRows) { streamRows.map { it.addonName.ifBlank { "Other" } }.distinct() }
    val sourceTabs = remember(addonNames) { if (addonNames.size <= 1) emptyList() else listOf("All") + addonNames }
    // Keyed to the request, not the stream list: progressive batches must not reset the chosen tab.
    var selectedTab by remember(request) { mutableStateOf("All") }
    val filteredStreams = if (sourceTabs.isEmpty() || selectedTab == "All") {
        streamRows
    } else {
        streamRows.filter { it.addonName.ifBlank { "Other" } == selectedTab }
    }

    // Decided across the whole list so the columns line up and empty ones disappear entirely.
    val anyQuality = remember(filteredStreams) {
        filteredStreams.any { streamQualityLabel(it, repository.describeStreamOption(it)) != null }
    }
    val anySize = remember(filteredStreams, streamsPrefs.showSizeBadges) {
        streamsPrefs.showSizeBadges &&
            filteredStreams.any { streamSizeLabel(it, repository.describeStreamOption(it)) != null }
    }

    LaunchedEffect(selectedTab) {
        if (filteredStreams.isNotEmpty() && initialFocusApplied) {
            kotlinx.coroutines.delay(80)
            runCatching { firstCardRequester.requestFocus() }
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
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val wash = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to backgroundColor.copy(alpha = 0.82f),
                        0.35f to backgroundColor.copy(alpha = 0.95f),
                        1f to backgroundColor,
                    ),
                )
                onDrawBehind { drawRect(wash) }
            },
        )

        Column(Modifier.fillMaxSize()) {
            StreamsHeader(
                detail = detail,
                request = request,
                pendingSources = pendingSourceCount,
                streamCount = filteredStreams.size,
                onReload = {
                    selectedTab = "All"
                    initialFocusApplied = false
                    uiState = PlaybackStreamsUiState.Loading()
                    refreshGeneration += 1
                },
            )

            if (sourceTabs.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .focusGroup()
                        .padding(horizontal = StreamsInset, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sourceTabs.forEachIndexed { index, tab ->
                        SearchChip(
                            label = tab,
                            selected = tab == selectedTab,
                            modifier = if (index == 0) Modifier.focusRequester(firstTabRequester) else Modifier,
                            onClick = { selectedTab = tab },
                        )
                    }
                }
            }

            when (val state = uiState) {
                is PlaybackStreamsUiState.Loading -> StreamsSkeleton()

                is PlaybackStreamsUiState.Error -> DetailError(
                    message = state.message,
                    onRetry = {
                        initialFocusApplied = false
                        uiState = PlaybackStreamsUiState.Loading()
                        refreshGeneration += 1
                    },
                    onBack = onBack,
                )

                is PlaybackStreamsUiState.Ready -> LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().focusGroup(),
                    contentPadding = PaddingValues(
                        start = StreamsInset, end = StreamsInset, top = 6.dp, bottom = 64.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (filteredStreams.isEmpty()) {
                        // Only declare "nothing found" once every source has replied.
                        if (state.pendingSources > 0) {
                            item("loading") { StreamRowSkeleton() }
                        } else {
                            item("empty") { NoStreamsRow(onReload = { refreshGeneration += 1 }) }
                        }
                    } else {
                        itemsIndexed(
                            filteredStreams,
                            // The index is part of the key on purpose: two addons can return the
                            // same file, and duplicate keys are fatal in a lazy list.
                            key = { index, stream -> "${repository.streamSelectionKey(stream)}:$index:$selectedTab" },
                        ) { index, stream ->
                            val rowLabel = repository.describeStreamOption(stream)
                            StreamRow(
                                stream = stream,
                                label = rowLabel,
                                showQuality = anyQuality,
                                showSize = anySize,
                                requestFocus = if (index == 0) firstCardRequester else null,
                                streamsPrefs = streamsPrefs,
                                fusionBadgeSources = activeFusionBadgeSources,
                                onPressed = {
                                    onPlayRequest(
                                        request.copy(
                                            selectedStreamKey = repository.streamSelectionKey(stream),
                                            selectedStreamLabel = repository.describeStreamOption(stream),
                                            selectedStream = stream,
                                            availableStreams = streamRows,
                                        ),
                                    )
                                },
                            )
                        }
                        if (state.pendingSources > 0) {
                            item("more") { StreamRowSkeleton() }
                        }
                    }
                }
            }
        }
    }
}

/** One strip: what is being played, how many options, and a way to search again. */
@Composable
private fun StreamsHeader(
    detail: MediaDetail?,
    request: PlaybackRequest,
    pendingSources: Int,
    streamCount: Int,
    onReload: () -> Unit,
) {
    // `?:` does not catch an empty string, and a blank request title left the header showing
    // nothing at all while the reload chip stretched across the whole row.
    val title = detail?.title?.takeIf { it.isNotBlank() }
        ?: request.title?.takeIf { it.isNotBlank() }
        ?: "Streams"
    val episode = request.episode
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = StreamsInset, end = StreamsInset, top = 30.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Width pinned deliberately. A tv-material3 Card fills the space offered to it, and
            // unweighted children are measured first — so in a bounded row the chip swallowed the
            // whole width and the weighted title next to it was measured at zero.
            if (pendingSources > 0) {
                StreamsSearchStatus(Modifier.width(186.dp))
            } else {
                SearchChip(
                    label = "Reload sources",
                    selected = false,
                    modifier = Modifier.width(186.dp),
                    onClick = onReload,
                )
            }
        }
        Text(
            text = buildString {
                episode?.let {
                    append("S${it.seasonNumber} E${it.episodeNumber}")
                    it.title?.takeIf { name -> name.isNotBlank() }?.let { name -> append("  ·  $name") }
                    append("  ·  ")
                }
                append(if (streamCount == 1) "1 stream" else "$streamCount streams")
                if (pendingSources > 0) {
                    append("  ·  $pendingSources source${if (pendingSources == 1) "" else "s"} still loading")
                }
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.58f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Loading status is deliberately not focusable: OK must not restart a lookup mid fan-out. */
@Composable
private fun StreamsSearchStatus(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.height(40.dp)
            .background(Color.White.copy(alpha = 0.07f), AppPillShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Searching...",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
        )
    }
}

/**
 * One stream, laid out on a fixed grid so the same fact is in the same place on every row.
 *
 * Source and release text on the left; quality, size and availability right-aligned in that order.
 * Comparing options means running an eye down one column, which the previous free-flowing chip row
 * made impossible.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StreamRow(
    stream: AddonStream,
    label: String,
    showQuality: Boolean,
    showSize: Boolean,
    requestFocus: FocusRequester?,
    streamsPrefs: StreamsPreferences,
    fusionBadgeSources: List<FusionBadgeSource>,
    onPressed: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val highContrast = LocalTvExperienceSettings.current.highContrast
    val fusionBadges = remember(stream, fusionBadgeSources, streamsPrefs.fusionBadgesEnabled) {
        if (streamsPrefs.fusionBadgesEnabled && fusionBadgeSources.isNotEmpty()) {
            flattenFusionBadges(matchFusionBadges(stream, fusionBadgeSources))
        } else {
            emptyList()
        }
    }
    val availability = when {
        stream.cachedBy.isNotEmpty() -> "Cached" to true
        !stream.url.isNullOrBlank() -> "Direct" to false
        else -> "Torrent" to false
    }

    Card(
        onClick = onPressed,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (requestFocus != null) Modifier.focusRequester(requestFocus) else Modifier)
            .onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(AppCardShape),
        colors = CardDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.05f),
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            pressedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                BorderStroke(if (highContrast) 3.dp else 2.dp, MaterialTheme.colorScheme.primary),
                shape = AppCardShape,
            ),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = TvMotion.focusScale()),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = stream.addonName.ifBlank { "Stream source" },
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (focused) 0.95f else 0.78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Fixed-width columns so these line up down the list.
                if (showQuality) StreamFact(streamQualityLabel(stream, label) ?: "—", 92.dp)
                if (showSize) StreamFact(streamSizeLabel(stream, label) ?: "—", 92.dp)
                StreamFact(availability.first, 88.dp, emphasised = availability.second)
            }
            if (fusionBadges.isNotEmpty()) {
                FusionBadgeRow(badges = fusionBadges)
            }
        }
    }
}

@Composable
private fun StreamFact(text: String, width: androidx.compose.ui.unit.Dp, emphasised: Boolean = false) {
    Box(Modifier.width(width), contentAlignment = Alignment.CenterEnd) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = if (emphasised) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StreamRowSkeleton() {
    TvSkeletonBox(Modifier.fillMaxWidth().height(72.dp))
}

@Composable
private fun StreamsSkeleton() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = StreamsInset, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(5) { StreamRowSkeleton() }
    }
}

/** Nothing came back. Offers another attempt rather than just stating the fact. */
@Composable
private fun NoStreamsRow(onReload: () -> Unit) {
    val retryRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        runCatching { retryRequester.requestFocus() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppCardShape)
            .background(Color.White.copy(alpha = 0.05f))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "No playable streams",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Every source answered without a usable link. Reloading asks them all again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
        )
        Box(Modifier.padding(top = 4.dp)) {
            SearchChip(
                label = "Reload sources",
                selected = false,
                modifier = Modifier.focusRequester(retryRequester),
                onClick = onReload,
            )
        }
    }
}
