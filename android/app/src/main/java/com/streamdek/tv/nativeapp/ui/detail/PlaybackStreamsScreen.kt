package com.streamdek.tv.nativeapp.ui.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.data.AddonStream
import com.streamdek.tv.nativeapp.data.FusionBadgeSource
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.PlaybackRequest
import com.streamdek.tv.nativeapp.data.ProfilePluginState
import com.streamdek.tv.nativeapp.data.ResolvedPlaybackCandidate
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.StreamsPreferences
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import com.streamdek.tv.nativeapp.data.addonStreamDisplayLabel
import com.streamdek.tv.nativeapp.data.flattenFusionBadges
import com.streamdek.tv.nativeapp.data.matchFusionBadges
import com.streamdek.tv.nativeapp.data.mergeProgressiveStreamSnapshot
import com.streamdek.tv.nativeapp.data.streamOriginLabel
import com.streamdek.tv.nativeapp.debrid.cachedAvailabilityLabel
import com.streamdek.tv.nativeapp.debrid.readyServiceLabel
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.FusionBadgeRow
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.TvMotion
import com.streamdek.tv.nativeapp.ui.TvSkeletonBox
import com.streamdek.tv.nativeapp.ui.TvSpacing
import com.streamdek.tv.nativeapp.ui.search.SearchChip
import java.util.Locale

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
// The trailing boundary keeps bitrates out: `9.6 Mbps` is not a 9.6 MB movie.
private val SizePattern = Regex("""(\d+(?:[.,]\d+)?)\s?(GB|MB)\b""", RegexOption.IGNORE_CASE)

/**
 * Quality and size as shown in the columns.
 *
 * Add-ons rarely populate the dedicated fields — the information is almost always inside the
 * release name — so the row fell back to a dash on nearly every line. Reading it out of the label
 * makes the columns worth having; when a value genuinely is not there the column is dropped for
 * the whole list rather than filled with placeholders.
 */
private fun qualityToken(text: String?): String? = text
    ?.takeIf { it.isNotBlank() }
    ?.let(QualityPattern::find)
    ?.value
    ?.uppercase()
    ?.replace("UHD", "2160P")

internal fun streamQualityLabel(stream: AddonStream, label: String): String? =
    qualityToken(stream.quality) ?: qualityToken(label)

internal fun streamSizeLabel(stream: AddonStream, label: String): String? =
    sequenceOf(stream.size, label, stream.title, stream.name, stream.behaviorHints?.filename, stream.filename, stream.description)
        .filterNotNull()
        .flatMap { SizePattern.findAll(it) }
        .mapNotNull { match ->
            val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            val unit = match.groupValues[2].uppercase()
            val gigabytes = if (unit == "GB") value else value / 1024.0
            Triple(gigabytes, match.groupValues[1], unit)
        }
        // Some providers put a response/chunk size in `size` and the actual release size in their
        // display text. A film-sized value is the useful one, so choose the largest valid value.
        .maxByOrNull { it.first }
        ?.let { (_, value, unit) -> "$value $unit" }


/**
 * The quality band a result is filed under, in the order the bands appear on Auto.
 *
 * Deliberately coarse. Sources describe quality in whatever words they like — "4K", "2160p",
 * "UHD", "FullHD" — and a list that made a separate heading of each spelling would be as hard to
 * read as the ungrouped one it replaced. [Unknown] is a real band rather than a bin for failures:
 * plenty of sources genuinely do not say, and those results still have to appear.
 */
internal enum class StreamQualityTier(
    /** The resolution as it is written everywhere, which is the same in every language. */
    val label: String,
    /** Set only for the band that is a word rather than a resolution. */
    @StringRes val labelRes: Int? = null,
) {
    Uhd("4K"),
    Qhd("1440p"),
    Fhd("1080p"),
    Hd("720p"),
    Sd("480p"),
    Ld("360p"),
    Unknown("Other", labelRes = R.string.quality_tier_other),
    // Last whatever else is present. A camera recording is not a quality tier so much as a
    // warning, and it belongs under the results somebody actually wants.
    Cam("CAM"),
}

/**
 * Matched against the whole of a result's text, most specific first.
 *
 * Word boundaries throughout, because the loose forms are the dangerous ones: a bare "hd" matches
 * inside "UHD", "FHD" and "HDR", and "ts" matches inside any number of release names — which is
 * why the camera-recording forms are spelled out rather than abbreviated.
 */
private val StreamQualityTierPatterns: List<Pair<Regex, StreamQualityTier>> = listOf(
    Regex("\\b(cam|camrip|hdcam|hdts|hdtc|telesync|telecine|ts-?rip)\\b", RegexOption.IGNORE_CASE) to StreamQualityTier.Cam,
    Regex("\\b(2160p?|4k|uhd|ultra\\s?hd)\\b", RegexOption.IGNORE_CASE) to StreamQualityTier.Uhd,
    Regex("\\b(1440p?|2k|qhd)\\b", RegexOption.IGNORE_CASE) to StreamQualityTier.Qhd,
    Regex("\\b(1080p?|fhd|full\\s?hd)\\b", RegexOption.IGNORE_CASE) to StreamQualityTier.Fhd,
    Regex("\\b(720p?|hd\\s?rip|hdtv|hd)\\b", RegexOption.IGNORE_CASE) to StreamQualityTier.Hd,
    Regex("\\b(480p?|sd|dvd\\s?rip|dvdrip)\\b", RegexOption.IGNORE_CASE) to StreamQualityTier.Sd,
    Regex("\\b(360p?|240p?|144p?)\\b", RegexOption.IGNORE_CASE) to StreamQualityTier.Ld,
)

/**
 * Which band a result belongs to.
 *
 * The source's own `quality` field is asked first and on its own: it is the only field that means
 * one thing, and reading it together with the title lets a filename like "Movie.2160p.sample" pull
 * a 720p stream into the 4K band.
 */
internal fun streamQualityTier(stream: AddonStream): StreamQualityTier {
    fun classify(text: String?): StreamQualityTier? {
        if (text.isNullOrBlank()) return null
        return StreamQualityTierPatterns.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.second
    }
    classify(stream.quality)?.let { return it }
    val evidence = listOfNotNull(
        stream.name, stream.title, stream.behaviorHints?.filename, stream.filename, stream.description,
    ).joinToString(" ")
    return classify(evidence) ?: StreamQualityTier.Unknown
}

private val StreamSizePattern = Regex("([\\d.,]+)\\s*(TB|TiB|GB|GiB|MB|MiB)\\b", RegexOption.IGNORE_CASE)

/**
 * A result's size in gigabytes, for ordering a band.
 *
 * The trailing boundary keeps bitrates out: without it "~7.71 Mbps" reads as 7.71 MB, and a 6 GB
 * result sorts below a 700 MB one.
 */
internal fun streamSizeGigabytes(stream: AddonStream): Double? {
    val evidence = listOfNotNull(
        stream.size, stream.title, stream.name, stream.behaviorHints?.filename, stream.filename, stream.description,
    ).joinToString(" ")
    val match = StreamSizePattern.find(evidence) ?: return null
    val value = match.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
    return when (match.groupValues[2].lowercase(Locale.US)) {
        "tb", "tib" -> value * 1024.0
        "mb", "mib" -> value / 1024.0
        else -> value
    }
}

/**
 * Where a band sits under the viewer's chosen quality.
 *
 * The Preferred Quality setting already decides what gets played; this makes it decide what gets
 * seen first too, so the setting means the same thing on a list as it does on a press. Everything
 * else keeps its natural order behind the preferred band, so the list is still highest-first below
 * the top.
 */
internal fun streamQualityBandOrder(tier: StreamQualityTier, preferredQuality: String): Int {
    val preferred = when (preferredQuality.trim().lowercase(Locale.US)) {
        "2160p", "4k" -> StreamQualityTier.Uhd
        "1440p", "2k" -> StreamQualityTier.Qhd
        "1080p" -> StreamQualityTier.Fhd
        "720p" -> StreamQualityTier.Hd
        "480p" -> StreamQualityTier.Sd
        "360p" -> StreamQualityTier.Ld
        else -> null
    }
    return if (preferred != null && tier == preferred) -1 else tier.ordinal
}

/** One line of the picker: a heading the remote skips over, or a result it lands on. */
internal sealed interface StreamListEntry {
    data class SourceHeading(val source: String, val count: Int) : StreamListEntry
    data class QualityHeading(val tier: StreamQualityTier, val count: Int) : StreamListEntry
    data class Result(val stream: AddonStream) : StreamListEntry
}

/**
 * Flattens the picker into source headings, quality headings and results.
 *
 * Flat rather than nested so the list stays lazy — a title with two hundred results must not
 * compose two hundred rows to draw ten of them on a television.
 *
 * Source, then quality, then size. Grouped rather than run together into one ranked list because
 * the question this screen is asked is not "what is best" — the app already answers that by
 * playing the top result — it is "what has each source got". Size descending within a band: the
 * larger file is the better encode often enough to be the right default, and results whose size
 * could not be read sort to the end of their own band rather than being treated as zero.
 *
 * Nothing is dropped: every source that answered keeps every result it returned.
 */
internal fun buildStreamListEntries(
    streams: List<AddonStream>,
    preferredQuality: String,
    includeSourceHeadings: Boolean,
    /**
     * What to head results from an add-on that gave no name. Passed in because this is a pure
     * function with no composition to read a resource from, and it has to match the tab label the
     * screen builds from the same fallback.
     */
    unnamedSourceLabel: String = "Other",
): List<StreamListEntry> = buildList {
    // groupBy keeps first-appearance order, and the list arriving here is already ranked, so the
    // source holding the best single result leads.
    streams.groupBy { it.addonName.ifBlank { unnamedSourceLabel } }.forEach { (source, items) ->
        if (includeSourceHeadings) add(StreamListEntry.SourceHeading(source, items.size))
        items.groupBy(::streamQualityTier)
            .toList()
            .sortedBy { (tier, _) -> streamQualityBandOrder(tier, preferredQuality) }
            .forEach { (tier, banded) ->
                add(StreamListEntry.QualityHeading(tier, banded.size))
                banded.sortedByDescending { streamSizeGigabytes(it) ?: -1.0 }
                    .forEach { add(StreamListEntry.Result(it)) }
            }
    }
}

/** Whitespace and case removed, so two spellings of the same sentence compare equal. */
internal fun streamTextFingerprint(value: String): String =
    value.replace(Regex("""\s+"""), " ").trim().lowercase(Locale.US)

/**
 * Verbatim add-on fields used when StreamDek result formatting is disabled.
 *
 * The second line is the add-on's detail block -- unless it is the first line over again. Plenty
 * of sources fill `name` and `title` with the same string, and every one of their results rendered
 * as two identical lines because of it. Printing a line twice is not the add-on's text being
 * respected, it is a row that wastes half its height saying nothing new, so a detail that matches
 * the label is skipped and the next field that has something else to say is used instead.
 */
internal fun rawAddonStreamText(stream: AddonStream): Pair<String?, String?> {
    val label = stream.name?.takeIf { it.isNotBlank() }
    val labelPrint = label?.let(::streamTextFingerprint)
    val detail = listOfNotNull(stream.title, stream.description)
        .firstOrNull { it.isNotBlank() && streamTextFingerprint(it) != labelPrint }
    return if (label == null && detail == null) {
        (stream.filename?.takeIf { it.isNotBlank() } ?: "Stream source") to null
    } else {
        label to detail
    }
}

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
    // The request used to enter the player carries the complete picker snapshot. Navigation may
    // dispose this destination while video is playing, and when it is composed again the selected
    // stream changes the repository cache key. Prefer the snapshot so Back restores the exact list
    // immediately instead of starting a second lookup that can briefly (or permanently) be empty.
    val cachedCandidate = remember(request) {
        request.availableStreams.takeIf { it.isNotEmpty() }
            ?.let { ResolvedPlaybackCandidate(null, null, it) }
            ?: repository.peekCachedResolvedPlayback(request)
    }
    // The load failure is recorded in a coroutine, which is not a composition.
    val streamsResources = LocalContext.current.resources
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
    // Names the collection a plugin scraper came from, so a plugin row cannot be mistaken for an
    // add-on row when the list mixes both.
    val pluginState = bootstrap?.profilePlugins ?: ProfilePluginState()
    // Read here rather than per row: every row asks for the same two words, and the lazy list's item
    // builder is not the place for a resource lookup.
    val addonOriginLabel = stringResource(R.string.source_origin_addon)
    val pluginOriginLabel = stringResource(R.string.source_origin_plugin)
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
        }
        if (cachedCandidate == null || refreshGeneration > 0) {
            uiState = PlaybackStreamsUiState.Loading()
        }
        // A request/player snapshot makes the picker instant, but is not proof that every enabled
        // provider has finished. Keep it visible while the same canonical progressive pipeline
        // completes, merging late plugin results instead of returning early with a partial list.
        var accumulatedStreams = cachedCandidate?.takeIf { refreshGeneration == 0 }?.streams.orEmpty()
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
                uiState = PlaybackStreamsUiState.Error(streamsResources.getString(R.string.streams_load_failed))
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
    /** Bumped when the filter moved under the list, so the viewer's place can be taken back. */
    var refocusListAfterFilter by remember(request) { mutableIntStateOf(0) }
    /**
     * How long the tab strip must ignore focus arriving at one of its chips.
     *
     * Each chip selects itself when focused, which is right when someone walks up into the strip
     * and wrong in the one case that matters here. Moving the filter rebuilds the list underneath
     * the focused row; for the frame in which the replacement has not been composed there is
     * nothing focusable in the list, so Compose searches outward and lands on the *first* chip --
     * "All" -- which promptly selects itself. That is the whole "it snaps back to All" bug: the
     * filter was moving one step and then being overwritten a frame later.
     *
     * A deadline rather than a flag, because it has to survive however many frames the relayout
     * takes and clear itself without anyone remembering to.
     */
    var ignoreTabFocusUntil by remember(request) { mutableStateOf(0L) }
    val streamsListState = rememberLazyListState()
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
    val otherSourceLabel = stringResource(R.string.source_group_other)
    val addonNames = remember(streamRows, otherSourceLabel) {
        streamRows.map { it.addonName.ifBlank { otherSourceLabel } }.distinct()
    }
    val sourceTabs = remember(addonNames) { if (addonNames.size <= 1) emptyList() else listOf("All") + addonNames }
    // Keyed to the request, not the stream list: progressive batches must not reset the chosen tab.
    var selectedTab by remember(request) { mutableStateOf("All") }
    val filteredStreams = if (sourceTabs.isEmpty() || selectedTab == "All") {
        streamRows
    } else {
        streamRows.filter { it.addonName.ifBlank { otherSourceLabel } == selectedTab }
    }

    // Decided across the whole list so the columns line up and empty ones disappear entirely.
    //
    // In both modes now. These columns are read out of what the add-on already sent -- the same
    // fields the player's loading screen prints beside the release name -- so a result that showed
    // "FebBox - 4K" here and "FebBox - 4K | 4K | 5.01 GB" a second later on the loading screen was
    // hiding a size it had all along. Verbatim mode's promise is that the add-on's own text is
    // never rewritten, and an aligned column beside that text does not touch a word of it.
    val anyQuality = remember(filteredStreams) {
        filteredStreams.any { streamQualityLabel(it, repository.describeStreamOption(it)) != null }
    }
    val anySize = remember(filteredStreams, streamsPrefs.showSizeBadges) {
        streamsPrefs.showSizeBadges &&
            filteredStreams.any { streamSizeLabel(it, repository.describeStreamOption(it)) != null }
    }

    // Source headings are redundant while a single source is being shown -- the tab strip above
    // already says which one -- so they appear only on "All", and only when there is more than one.
    val preferredQuality = bootstrap?.preferences?.playback?.preferredQuality ?: "Auto"
    val streamFallbackName = remember(detail?.title, request.episode) {
        listOfNotNull(
            detail?.title?.takeIf { it.isNotBlank() } ?: request.title?.takeIf { it.isNotBlank() },
            request.episode?.let { episode ->
                "S%02dE%02d".format(episode.seasonNumber, episode.episodeNumber)
            },
        ).joinToString(" · ").ifBlank { "Stream source" }
    }
    val streamEntries = remember(filteredStreams, preferredQuality, selectedTab, addonNames) {
        buildStreamListEntries(
            unnamedSourceLabel = otherSourceLabel,
            streams = filteredStreams,
            preferredQuality = preferredQuality,
            includeSourceHeadings = addonNames.size > 1 && selectedTab == "All",
        )
    }

    LaunchedEffect(refocusListAfterFilter) {
        if (refocusListAfterFilter == 0) return@LaunchedEffect
        // Back to the top of the new source and onto its first result: a different provider is a
        // different set of files, so there is no "same place" to hold, and the first result is
        // where reading it starts.
        runCatching { streamsListState.scrollToItem(0) }
        // Asked for after the recomposition the new first row arrives in, not before it. A
        // FocusRequester attached to nothing throws rather than doing nothing.
        kotlinx.coroutines.delay(60)
        runCatching { firstCardRequester.requestFocus() }
        // Held a little past the request: focus can still be settling, and a stray arrival at the
        // strip in that window is the thing being guarded against.
        kotlinx.coroutines.delay(200)
        ignoreTabFocusUntil = 0L
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
                            // Tabs are previews on TV: moving the highlight is the selection. Keep
                            // focus in the strip so left/right can inspect every provider without
                            // an OK press or being pulled down into the first result row.
                            //
                            // Except while the list below is mid-switch, when focus arriving here
                            // is Compose looking for somewhere to put it rather than the viewer
                            // walking up. Taking that as a selection is what reset the filter to
                            // "All" on every left or right press.
                            onFocused = {
                                if (System.currentTimeMillis() >= ignoreTabFocusUntil) selectedTab = tab
                            },
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
                    state = streamsListState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // Left and right change the source filter without leaving the list.
                        //
                        // A vertical list has nothing either side of it, so both presses were dead
                        // keys — while the thing a viewer wants while reading results is to see the
                        // same position under another provider. Going back up to the strip to do it
                        // loses the row they were on, so the filter moves under them instead.
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val step = when (event.key) {
                                Key.DirectionLeft -> -1
                                Key.DirectionRight -> 1
                                else -> return@onPreviewKeyEvent false
                            }
                            if (sourceTabs.isEmpty()) return@onPreviewKeyEvent false
                            val next = sourceTabs.indexOf(selectedTab) + step
                            // Clamped rather than wrapped: on a remote, running off the end and
                            // reappearing at the other one reads as the list having jumped.
                            if (next !in sourceTabs.indices) return@onPreviewKeyEvent true
                            // Armed before the change, so the strip is already deaf by the time the
                            // relayout lets focus loose. See ignoreTabFocusUntil.
                            ignoreTabFocusUntil = System.currentTimeMillis() + 1_500L
                            selectedTab = sourceTabs[next]
                            refocusListAfterFilter += 1
                            true
                        }
                        .focusGroup(),
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
                        // Headings are deliberately not focusable, so the requester goes on the
                        // first thing that can actually be pressed.
                        val focusTargetIndex = streamEntries.indexOfFirst { it is StreamListEntry.Result }
                        itemsIndexed(
                            streamEntries,
                            // The index is part of the key on purpose: two addons can return the
                            // same file, and duplicate keys are fatal in a lazy list.
                            //
                            // The selected tab is deliberately *not* part of it any more, and that
                            // is the crash fix. With the tab in every key, moving the filter gave
                            // every row a new identity at once, so the whole list -- including the
                            // row currently holding focus -- was disposed inside the same frame
                            // that had to measure its replacement. Compose answers that with
                            // "measure is called on a deactivated node" and takes the app down.
                            // Keyed on the file and its position instead, a switch reuses the slots
                            // it can and disposes only the rows that genuinely went.
                            key = { index, entry ->
                                when (entry) {
                                    is StreamListEntry.SourceHeading -> "source:${entry.source}:$index"
                                    is StreamListEntry.QualityHeading -> "band:${entry.tier}:$index"
                                    is StreamListEntry.Result ->
                                        "${repository.streamSelectionKey(entry.stream)}:$index"
                                }
                            },
                        ) { index, entry ->
                            when (entry) {
                                is StreamListEntry.SourceHeading -> StreamSourceHeading(entry.source, entry.count)
                                is StreamListEntry.QualityHeading -> StreamQualityHeading(entry.tier, entry.count)
                                is StreamListEntry.Result -> {
                                    val stream = entry.stream
                                    // The title being watched stands in when the source's own text
                                    // named nothing, so a row is never just a resolution and a size.
                                    val rowLabel = addonStreamDisplayLabel(stream, streamFallbackName)
                                    StreamRow(
                                        stream = stream,
                                        label = rowLabel,
                                        origin = streamOriginLabel(stream, pluginState, addonOriginLabel, pluginOriginLabel),
                                        showQuality = anyQuality,
                                        showSize = anySize,
                                        requestFocus = if (index == focusTargetIndex) firstCardRequester else null,
                                        streamsPrefs = streamsPrefs,
                                        fusionBadgeSources = activeFusionBadgeSources,
                                        onPressed = {
                                            onPlayRequest(
                                                request.copy(
                                                    selectedStreamKey = repository.streamSelectionKey(stream),
                                                    selectedStreamLabel = rowLabel,
                                                    selectedStream = stream,
                                                    availableStreams = streamRows,
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
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
                    label = stringResource(R.string.streams_reload),
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
                append(pluralStringResource(R.plurals.streams_count, streamCount, streamCount))
                if (pendingSources > 0) {
                    append("  ·  ")
                    append(pluralStringResource(R.plurals.streams_sources_still_loading, pendingSources, pendingSources))
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
            text = stringResource(R.string.streams_searching),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
        )
    }
}

/**
 * A source's name over its results.
 *
 * Not focusable and not a card: on a remote, anything that takes focus is another press between
 * the viewer and the thing they are choosing. The heading scrolls into view with the results
 * underneath it rather than being stopped on.
 */
@Composable
private fun StreamSourceHeading(source: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A short accent stroke rather than a filled bar: it marks where a source begins without
        // turning every group into a slab the results then have to sit inside.
        Box(
            Modifier
                .width(4.dp)
                .height(22.dp)
                .clip(AppPillShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            text = source,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
        )
    }
}

/** A quality band's label, a rule across to its count, and the results below it. */
@Composable
private fun StreamQualityHeading(tier: StreamQualityTier, count: Int) {
    val foreground = MaterialTheme.colorScheme.onSurface
    val accent = when (tier) {
        StreamQualityTier.Uhd -> Color(0xFFF59E0B)
        StreamQualityTier.Qhd -> Color(0xFFA78BFA)
        StreamQualityTier.Fhd -> Color(0xFF38BDF8)
        StreamQualityTier.Hd -> Color(0xFF34D399)
        StreamQualityTier.Sd, StreamQualityTier.Ld -> Color(0xFF94A3B8)
        StreamQualityTier.Cam -> Color(0xFFF87171)
        StreamQualityTier.Unknown -> foreground.copy(alpha = 0.55f)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(AppPillShape)
                .background(accent.copy(alpha = 0.16f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = tier.labelRes?.let { stringResource(it) } ?: tier.label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
                color = accent,
                maxLines = 1,
            )
        }
        // The rule carries the eye across to the count and gives the band a floor to sit on, which
        // is what stops a long list reading as one undifferentiated run of cards.
        Box(Modifier.weight(1f).height(1.dp).background(foreground.copy(alpha = 0.10f)))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = foreground.copy(alpha = 0.42f),
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
    /** "Add-on", or "Plugin · <collection>" — what kind of source this is, not just its name. */
    origin: String?,
    showQuality: Boolean,
    showSize: Boolean,
    requestFocus: FocusRequester?,
    streamsPrefs: StreamsPreferences,
    fusionBadgeSources: List<FusionBadgeSource>,
    onFocused: () -> Unit = {},
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
        stream.cachedBy.isNotEmpty() -> cachedAvailabilityLabel(stream.cachedBy).orEmpty() to true
        !stream.url.isNullOrBlank() -> stringResource(R.string.player_info_route_direct) to false
        // Assembled on this device from the NZB and the news servers the stream names, the same
        // way the phone does it — named in the column because how it arrives changes how long it
        // takes to start, not because it cannot be played.
        !stream.nzbUrl.isNullOrBlank() -> stringResource(R.string.transport_usenet) to false
        else -> stringResource(R.string.transport_torrent) to false
    }
    val formatted = streamsPrefs.streamDekFormattingEnabled
    val readyLabel = remember(stream.cachedBy) { readyServiceLabel(stream.cachedBy) }
    val (rawLabel, rawDetail) = remember(stream) { rawAddonStreamText(stream) }

    Card(
        onClick = onPressed,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (requestFocus != null) Modifier.focusRequester(requestFocus) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                // Reported outward so the screen can put the viewer back on the same result when
                // the source filter moves under them -- see restoreFocusOrdinal.
                if (it.isFocused) onFocused()
            },
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
        Box(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    // Above the release rather than out in the columns, and in both modes.
                    //
                    // It is the thing a viewer scans by when the same release is offered by five
                    // sources, so it sits where the eye already is — on the source's own name.
                    // Verbatim mode keeps it too: that mode's promise is that the add-on's text is
                    // never rewritten, and a line above it does not touch a word of what was sent.
                    if (formatted) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (formatted) {
                                Text(
                                    text = stream.addonName.ifBlank { "Stream source" },
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                origin?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    if (formatted) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (focused) 0.95f else 0.78f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        rawLabel?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (focused) 0.98f else 0.86f),
                            )
                        }
                        rawDetail?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (focused) 0.90f else 0.74f),
                            )
                        }
                    }
                }

                // Fixed-width columns so these line up down the list.
                if (showQuality) StreamFact(streamQualityLabel(stream, label) ?: "—", 92.dp)
                if (showSize) StreamFact(streamSizeLabel(stream, label) ?: "—", 92.dp)
                // Cached rows say so in the tag above; the column keeps the cases the tag has
                // nothing to say about, so how a stream arrives is still one glance.
                if (readyLabel == null) StreamFact(availability.first, 88.dp)
            }
            if (fusionBadges.isNotEmpty()) {
                FusionBadgeRow(badges = fusionBadges)
            }
            }
            readyLabel?.let { ready ->
                // This is a card-level status, not source metadata. Overlaying it against the card
                // edge keeps it at the real top-right even when the quality/size columns change.
                ReadyServiceTag(
                    text = ready,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 13.dp, end = 18.dp),
                )
            }
        }
    }
}

/** The premium service promising an instant start. See [readyServiceLabel]. */
@Composable
private fun ReadyServiceTag(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.surface,
            maxLines = 1,
        )
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
            text = stringResource(R.string.streams_none_playable),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.streams_none_playable_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.70f),
        )
        Box(Modifier.padding(top = 4.dp)) {
            SearchChip(
                label = stringResource(R.string.streams_reload),
                selected = false,
                modifier = Modifier.focusRequester(retryRequester),
                onClick = onReload,
            )
        }
    }
}
