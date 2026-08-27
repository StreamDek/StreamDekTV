package com.streamdek.tv.nativeapp.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamdek.tv.mpv.MpvTrackInfo
import com.streamdek.tv.nativeapp.data.AddonStream
import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.ExternalSubtitleTrack
import com.streamdek.tv.nativeapp.data.ExternalSubtitleOrigin
import com.streamdek.tv.nativeapp.data.subtitleOriginVisible
import com.streamdek.tv.nativeapp.data.preferredSubtitleLanguageAllowed
import com.streamdek.tv.nativeapp.data.Languages
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.PlaybackStats
import com.streamdek.tv.nativeapp.data.ProfilePluginState
import com.streamdek.tv.nativeapp.data.ResolvedPlaybackCandidate
import com.streamdek.tv.nativeapp.data.formatBitrate
import com.streamdek.tv.nativeapp.data.formatResolution
import com.streamdek.tv.nativeapp.data.formatTransferRate
import com.streamdek.tv.nativeapp.data.prettyCodecName
import com.streamdek.tv.nativeapp.data.streamOriginLabel
import com.streamdek.tv.nativeapp.data.streamProviderLabel
import com.streamdek.tv.nativeapp.data.streamTransport
import com.streamdek.tv.nativeapp.debrid.readyServiceLabel
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.TvMotion
import com.streamdek.tv.nativeapp.ui.detail.streamQualityLabel
import com.streamdek.tv.nativeapp.ui.detail.streamSizeLabel
import com.streamdek.tv.nativeapp.ui.detail.streamTextFingerprint
import com.streamdek.tv.nativeapp.ui.formatPlaybackClock
import kotlinx.coroutines.launch
import java.util.Locale

internal enum class OverlayPanel {
    Streams,
    Engine,
    Audio,
    Subtitles,
    Speed,
    Info,
}

/** One predictable remote press, with a slightly larger step for feature-length playback. */
internal fun tvSeekStepSeconds(durationSec: Double): Double = when {
    durationSec >= 7200.0 -> 20.0
    durationSec >= 3600.0 -> 12.0
    else -> 10.0
}

/** Subtitle sizing offered in the player, around mpv's default of 55. */
internal val SubtitleSizeRange = 28..84

/** Subtitle vertical placement. Higher sits nearer the bottom of the picture. */
internal val SubtitlePositionRange = 50..110

/** Subtitle timing nudge, in seconds. mpv only — Media3 cannot shift a track's timestamps. */
internal val SubtitleDelayRange = -5.0..5.0

internal data class SpeedOption(
    val label: String,
    val value: Double,
)

internal fun normalizeSubtitleDefaultSource(value: String?): String = when (value?.trim()?.lowercase()) {
    "builtin", "built-in", "embedded" -> "BuiltIn"
    "addons", "add-ons", "addon" -> "Addons"
    else -> "All"
}

internal fun subtitleSourceIncludesBuiltIn(value: String?): Boolean =
    normalizeSubtitleDefaultSource(value) != "Addons"

internal fun subtitleSourceIncludesAddons(value: String?): Boolean =
    normalizeSubtitleDefaultSource(value) != "BuiltIn"

/** Subtitle-source views plus the appearance controls. */
internal enum class SubtitlePanelTab(val label: String) {
    All("All sources"),
    BuiltIn("Built-in"),
    Addons("Add-ons"),
    Adjust("Adjust"),
}

private val PlayerPanelShape = RoundedCornerShape(22.dp)

@Composable
internal fun PlayerOverlayVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reducedMotion = LocalTvExperienceSettings.current.reducedMotion
    // Asymmetric on purpose. The controls arrive on a decelerating curve because the viewer has
    // just asked for them and needs to read them; they leave faster, on an accelerating one,
    // because by then the viewer is watching the picture behind them.
    val enterSpec = TvMotion.enterSpec<Float>()
    val enterOffset = TvMotion.enterSpec<androidx.compose.ui.unit.IntOffset>()
    val exitSpec = TvMotion.exitSpec<Float>()
    val exitOffset = TvMotion.exitSpec<androidx.compose.ui.unit.IntOffset>()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reducedMotion) EnterTransition.None else fadeIn(enterSpec) + slideInVertically(enterOffset, initialOffsetY = { it / 6 }),
        exit = if (reducedMotion) ExitTransition.None else fadeOut(exitSpec) + slideOutVertically(exitOffset, targetOffsetY = { it / 8 }),
    ) {
        content()
    }
}

@Composable
internal fun PlayerGlassSurface(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(PlayerPanelShape)
            .background(
                Brush.verticalGradient(
                    colors = if (LocalTvExperienceSettings.current.backgroundBlur) listOf(Color(0xED12141C), Color(0xF1181B24)) else listOf(Color(0xFF101218), Color(0xFF161921)),
                ),
            )
            .border(1.dp, Color(0x1FFFFFFF), PlayerPanelShape)
            .padding(contentPadding),
    ) {
        content()
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerBottomBar(
    detail: MediaDetail?,
    requestTitle: String?,
    currentEpisode: EpisodeContext?,
    currentLabel: String,
    error: String?,
    paused: Boolean,
    hasNext: Boolean,
    positionSec: Double,
    durationSec: Double,
    selectedPanel: OverlayPanel?,
    playRequester: FocusRequester,
    subtitlesRequester: FocusRequester,
    audioRequester: FocusRequester,
    sourcesRequester: FocusRequester,
    engineRequester: FocusRequester,
    nextRequester: FocusRequester,
    watchedRequester: FocusRequester,
    speedRequester: FocusRequester,
    infoRequester: FocusRequester,
    progressRequester: FocusRequester,
    liveProgressRequester: FocusRequester,
    favouriteRequester: FocusRequester,
    onInteract: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onMarkWatched: () -> Unit,
    /** Seconds to move by, signed. Owned by the seek row and by nothing else. */
    onSeekBy: (Double) -> Unit,
    focusRegion: PlayerControlsFocusRegion,
    /** Which control the highlight should land on next. Null means Play, the row's default. */
    controlsEntryRequester: FocusRequester? = null,
    /** Changes when the host wants that highlight placed again. */
    controlsFocusToken: Int = 0,
    /** Reported once placed, so a named target is used once rather than becoming the new default. */
    onControlsEntryPlaced: () -> Unit = {},
    onFocusRegionChanged: (PlayerControlsFocusRegion) -> Unit,
    onOpenPanel: (OverlayPanel) -> Unit,
    modifier: Modifier = Modifier,
    isLive: Boolean = false,
    isVod: Boolean = false,
    showLiveProgress: Boolean = false,
    onToggleLiveProgress: () -> Unit = {},
    /** Whether the channel playing is already a favourite. Live only. */
    isFavourite: Boolean = false,
    onToggleFavourite: () -> Unit = {},
) {
    // Live broadcasts have no seekable timeline — the progress bar is replaced
    // by a LIVE indicator, so focus targets that pointed at it move to Play.
    val hasSeekableTimeline = !isLive || (showLiveProgress && durationSec > 0.0)
    // Null when there is no seek row to go up to, which the buttons read as "cancel the search"
    // rather than as "find something". A live channel has nothing above Play.
    val timelineUpRequester = progressRequester.takeIf { hasSeekableTimeline }
    val onControlsFocused = {
        onFocusRegionChanged(PlayerControlsFocusRegion.Controls)
        onInteract()
    }
    LaunchedEffect(focusRegion, hasSeekableTimeline, controlsFocusToken) {
        // Visibility and focus ownership change together. This is the only automatic handoff
        // between the two rows; horizontal movement never participates in focus search.
        //
        // It is also the only place the highlight is placed inside either row. The bar is what
        // knows when its own buttons are attached — which is not the same frame the host asked, and
        // reliably later still when the bar is arriving from behind a drawer that just closed.
        val target = when {
            focusRegion == PlayerControlsFocusRegion.Seek && hasSeekableTimeline -> progressRequester
            focusRegion == PlayerControlsFocusRegion.Controls -> controlsEntryRequester ?: playRequester
            else -> return@LaunchedEffect
        }
        repeat(6) { attempt ->
            kotlinx.coroutines.delay(if (attempt == 0) 16L else 32L)
            if (runCatching { target.requestFocus() }.isSuccess) {
                if (focusRegion == PlayerControlsFocusRegion.Controls) onControlsEntryPlaced()
                return@LaunchedEffect
            }
        }
    }
    // Read the state object directly in the key handler. Capturing the delegated Boolean leaves
    // the first key press after focus with the previous composition's value; left appeared to
    // "arm" scrubbing only because it gave recomposition time to catch up before right was used.
    val timelineFocused = remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color(0xBB000000), Color(0xF2000000)),
                ),
            )
            .padding(start = 36.dp, end = 36.dp, top = 56.dp, bottom = 28.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val title = detail?.title ?: requestTitle
                if (!detail?.titleLogo.isNullOrBlank()) {
                    AsyncImage(
                        model = detail!!.titleLogo,
                        contentDescription = title,
                        modifier = Modifier.height(30.dp),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                    )
                } else if (!title.isNullOrBlank()) {
                    Text(
                        text = buildString {
                            currentEpisode?.let { ep -> append("S${ep.seasonNumber} E${ep.episodeNumber}  ·  ") }
                            append(title)
                        },
                        style = androidx.tv.material3.MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = error ?: currentLabel,
                    style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                    color = if (error != null) Color(0xFFFFB4AB) else Color.White.copy(alpha = 0.56f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                AnimatedVisibility(
                    visible = timelineFocused.value,
                    enter = TvMotion.fadeInSpec(TvMotion.Quick),
                    exit = TvMotion.fadeOutSpec(TvMotion.Instant),
                ) {
                    Text(
                        text = "Hold down Left/Right button to scrub",
                        style = androidx.tv.material3.MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.42f),
                        maxLines = 1,
                    )
                }
            }

            if (isLive && !showLiveProgress) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    if (isVod) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFF60A5FA),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444)),
                        )
                    }
                    Text(
                        text = if (isVod) "VOD" else "LIVE",
                        style = androidx.tv.material3.MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
                        color = Color.White,
                    )
                }
            } else if (hasSeekableTimeline) {
                PlayerSeekFocusGroup(
                    positionSec = positionSec,
                    durationSec = durationSec,
                    requester = progressRequester,
                    controlsEntryRequester = playRequester,
                    onSeekBy = onSeekBy,
                    onEnterControls = {
                        onFocusRegionChanged(PlayerControlsFocusRegion.Controls)
                        onInteract()
                    },
                    onInteract = onInteract,
                    onFocusedChanged = {
                        timelineFocused.value = it
                        if (it) onFocusRegionChanged(PlayerControlsFocusRegion.Seek)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (isLive) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = formatPlaybackClock(positionSec),
                        style = androidx.tv.material3.MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                    Box(
                        modifier = Modifier.weight(1f).height(3.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.22f)),
                    )
                    Text(
                        text = if (isVod) "VOD" else "LIVE",
                        style = androidx.tv.material3.MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
                        color = if (isVod) Color(0xFF60A5FA) else Color.White,
                    )
                }
            }

            Row(
                // PlayerControlsFocusGroup: the second of the bottom bar's two focus islands.
                //
                // Horizontal traversal is internal to this row and cannot leave it — the first
                // control cancels a Left search and the last cancels a Right one, so a held button
                // stops at the end of the row instead of falling out of the bar. Up is the single
                // sanctioned way back to the seek row above; there is nothing below, so Down is
                // cancelled outright rather than left to spatial search.
                modifier = Modifier
                    .fillMaxWidth()
                    .focusGroup()
                    .focusProperties {
                        enter = { playRequester }
                        exit = { direction ->
                            when (direction) {
                                FocusDirection.Left, FocusDirection.Right, FocusDirection.Down ->
                                    FocusRequester.Cancel
                                FocusDirection.Up ->
                                    timelineUpRequester ?: FocusRequester.Cancel
                                else -> FocusRequester.Default
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PlayerControlIconButton(
                    icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    label = if (paused) "Play" else "Pause",
                    primary = true,
                    requester = playRequester,
                    upRequester = timelineUpRequester,
                    rightRequester = if (isLive) liveProgressRequester else subtitlesRequester,
                    onFocused = onControlsFocused,
                    onClick = onPlayPause,
                )
                Spacer(Modifier.width(8.dp))
                if (isLive) {
                    PlayerControlIconButton(
                        icon = Icons.Filled.Timeline,
                        label = "Progress",
                        active = showLiveProgress,
                        requester = liveProgressRequester,
                        upRequester = timelineUpRequester,
                        leftRequester = playRequester,
                        rightRequester = sourcesRequester,
                        onFocused = onControlsFocused,
                        onClick = onToggleLiveProgress,
                    )
                } else {
                    PlayerControlIconButton(
                        icon = Icons.Filled.ClosedCaption,
                        label = "Subtitles",
                        active = selectedPanel == OverlayPanel.Subtitles,
                        requester = subtitlesRequester,
                        upRequester = timelineUpRequester,
                        leftRequester = playRequester,
                        rightRequester = audioRequester,
                        onFocused = onControlsFocused,
                        onClick = { onOpenPanel(OverlayPanel.Subtitles) },
                    )
                    PlayerControlIconButton(
                        icon = Icons.Filled.VolumeUp,
                        label = "Audio",
                        active = selectedPanel == OverlayPanel.Audio,
                        requester = audioRequester,
                        upRequester = timelineUpRequester,
                        leftRequester = subtitlesRequester,
                        rightRequester = sourcesRequester,
                        onFocused = onControlsFocused,
                        onClick = { onOpenPanel(OverlayPanel.Audio) },
                    )
                }
                PlayerControlIconButton(
                    icon = Icons.Filled.Cloud,
                    label = "Sources",
                    active = selectedPanel == OverlayPanel.Streams,
                    requester = sourcesRequester,
                    upRequester = timelineUpRequester,
                    leftRequester = if (isLive) liveProgressRequester else audioRequester,
                    rightRequester = engineRequester,
                    onFocused = onControlsFocused,
                    onClick = { onOpenPanel(OverlayPanel.Streams) },
                )
                PlayerControlIconButton(
                    icon = Icons.Filled.Tune,
                    label = "Engine",
                    active = selectedPanel == OverlayPanel.Engine,
                    requester = engineRequester,
                    upRequester = timelineUpRequester,
                    leftRequester = sourcesRequester,
                    rightRequester = if (isLive) favouriteRequester else if (hasNext) nextRequester else watchedRequester,
                    onFocused = onControlsFocused,
                    onClick = { onOpenPanel(OverlayPanel.Engine) },
                )
                if (isLive) {
                    // Favouriting was only possible by holding OK on a channel in the grid, which
                    // is no use once you are watching it — this is where you decide you want it.
                    PlayerControlIconButton(
                        icon = if (isFavourite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        label = if (isFavourite) "Favourited" else "Favourite",
                        active = isFavourite,
                        requester = favouriteRequester,
                        upRequester = timelineUpRequester,
                        leftRequester = engineRequester,
                        rightRequester = infoRequester,
                        onFocused = onControlsFocused,
                        onClick = onToggleFavourite,
                    )
                }
                if (!isLive) {
                    if (hasNext) {
                        PlayerControlIconButton(
                            icon = Icons.Filled.SkipNext,
                            label = "Next",
                            requester = nextRequester,
                            upRequester = timelineUpRequester,
                            leftRequester = engineRequester,
                            rightRequester = watchedRequester,
                            onFocused = onControlsFocused,
                            onClick = onNext,
                        )
                    }
                    PlayerControlIconButton(
                        icon = Icons.Filled.CheckCircle,
                        label = "Watched",
                        requester = watchedRequester,
                        upRequester = timelineUpRequester,
                        leftRequester = if (hasNext) nextRequester else engineRequester,
                        rightRequester = speedRequester,
                        onFocused = onControlsFocused,
                        onClick = onMarkWatched,
                    )
                    PlayerControlIconButton(
                        icon = Icons.Filled.Speed,
                        label = "Speed",
                        active = selectedPanel == OverlayPanel.Speed,
                        requester = speedRequester,
                        upRequester = timelineUpRequester,
                        leftRequester = watchedRequester,
                        rightRequester = infoRequester,
                        onFocused = onControlsFocused,
                        onClick = { onOpenPanel(OverlayPanel.Speed) },
                    )
                }

                PlayerControlIconButton(
                    icon = Icons.Outlined.Info,
                    label = "Stream info",
                    active = selectedPanel == OverlayPanel.Info,
                    requester = infoRequester,
                    upRequester = timelineUpRequester,
                    leftRequester = if (isLive) favouriteRequester else speedRequester,
                    onFocused = onControlsFocused,
                    onClick = { onOpenPanel(OverlayPanel.Info) },
                )

                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * One control in [PlayerControlsFocusGroup].
 *
 * Every direction is named. A null neighbour means "there is nothing that way", which is expressed
 * as [FocusRequester.Cancel] rather than left to spatial focus search: the search is what used to
 * carry the highlight out of the bar and onto whatever happened to be laid out nearby.
 */
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PlayerControlIconButton(
    icon: ImageVector,
    label: String,
    primary: Boolean = false,
    active: Boolean = false,
    requester: FocusRequester,
    upRequester: FocusRequester? = null,
    leftRequester: FocusRequester? = null,
    rightRequester: FocusRequester? = null,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val size = if (primary) 44.dp else 40.dp
    val iconSize = 20.dp
    Button(
        onClick = onClick,
        shape = ButtonDefaults.shape(CircleShape),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) Color(0xBBF4EDE2) else Color.Transparent,
            focusedContainerColor = if (primary) Color.White else Color(0x28FFFFFF),
            contentColor = if (primary) Color(0xFF111111) else if (active) Color(0xFFF0BA66) else Color.White,
            focusedContentColor = if (primary) Color(0xFF111111) else Color.White,
        ),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(
                    width = if (focused) 2.dp else if (primary) 1.dp else 0.dp,
                    color = when {
                        focused -> Color(0xFFF0BA66)
                        primary -> Color(0x30FFFFFF)
                        else -> Color.Transparent
                    },
                ),
                shape = CircleShape,
            ),
        ),
        scale = ButtonDefaults.scale(focusedScale = TvMotion.focusScale()),
        modifier = Modifier
            .size(size)
            .focusRequester(requester)
            .focusProperties {
                up = upRequester ?: FocusRequester.Cancel
                left = leftRequester ?: FocusRequester.Cancel
                right = rightRequester ?: FocusRequester.Cancel
                down = FocusRequester.Cancel
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            },
        contentPadding = PaddingValues(0.dp),
    ) {
        val iconTint = when {
            primary -> Color(0xFF111111)
            active -> Color(0xFFF0BA66)
            else -> Color(0xE0FFFFFF)
        }
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(iconSize),
            tint = iconTint,
        )
    }
}

/**
 * PlayerSeekFocusGroup: the bottom bar's first focus island.
 *
 * The row is one focus target, not a row of them — the current time, the bar and the duration are
 * presentation, and there is nothing inside for a horizontal search to travel to. The group then
 * cancels a Left, Right or Up search outright, so Compose never runs the spatial search that used
 * to hand the highlight to Sources or Play mid-scrub. Down is the only direction that resolves to
 * a real target, and it is the sanctioned way into [PlayerControlsFocusGroup] below.
 *
 * Both halves matter. Cancelling the search is what makes the boundary structural: consuming the
 * key press alone left a window during a recomposition, or during the repeats of a held button,
 * where the framework got there first.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PlayerSeekFocusGroup(
    positionSec: Double,
    durationSec: Double,
    requester: FocusRequester,
    controlsEntryRequester: FocusRequester,
    onSeekBy: (Double) -> Unit,
    onEnterControls: () -> Unit,
    onInteract: () -> Unit,
    onFocusedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .focusGroup()
            .focusProperties {
                enter = { requester }
                exit = { direction ->
                    when (direction) {
                        FocusDirection.Left, FocusDirection.Right, FocusDirection.Up ->
                            FocusRequester.Cancel
                        else -> FocusRequester.Default
                    }
                }
            },
    ) {
        PlayerTimeline(
            positionSec = positionSec,
            durationSec = durationSec,
            requester = requester,
            controlsEntryRequester = controlsEntryRequester,
            onSeekBy = onSeekBy,
            onEnterControls = onEnterControls,
            onInteract = onInteract,
            onFocusedChanged = onFocusedChanged,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerTimeline(
    positionSec: Double,
    durationSec: Double,
    requester: FocusRequester,
    controlsEntryRequester: FocusRequester? = null,
    onSeekBy: (Double) -> Unit = {},
    onEnterControls: () -> Unit = {},
    onInteract: () -> Unit,
    onFocusedChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val progress = if (durationSec > 0.0) (positionSec / durationSec).coerceIn(0.0, 1.0).toFloat() else 0f
    // Read from the state object inside the key handler rather than from a captured Boolean, so
    // the first press after focus arrives sees this frame's value and not the previous one.
    val durationState = remember { mutableStateOf(durationSec) }
    durationState.value = durationSec
    Row(
        modifier = modifier
            .focusRequester(requester)
            .focusProperties {
                // Named in full, so no direction is left to spatial search.
                left = FocusRequester.Cancel
                right = FocusRequester.Cancel
                up = FocusRequester.Cancel
                down = controlsEntryRequester ?: FocusRequester.Cancel
            }
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyDown) {
                            val step = tvSeekStepSeconds(durationState.value)
                            onSeekBy(if (event.key == Key.DirectionRight) step else -step)
                        }
                        // Both edges. tv-material fires clicks on key-up without requiring the
                        // matching key-down, so a release that escaped here would land on whatever
                        // took focus next.
                        true
                    }
                    Key.DirectionUp -> true
                    Key.DirectionDown -> {
                        if (event.type == KeyEventType.KeyDown) onEnterControls()
                        // Not consumed: `down` above names the controls row, and letting the
                        // framework perform that move keeps one mechanism responsible for it.
                        false
                    }
                    else -> false
                }
            }
            .onFocusChanged {
                focused = it.isFocused
                onFocusedChanged(it.isFocused)
                if (it.isFocused) onInteract()
            }
            .focusable(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimePill(formatPlaybackClock(positionSec))
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (focused) Color(0x14000000) else Color.Transparent),
        ) {
            val barWidth = maxWidth
            val thumbR = if (focused) 11.dp else 8.dp
            val thumbOffset = (barWidth * progress - thumbR).coerceAtLeast(0.dp)
            val trackH = if (focused) 10.dp else 7.dp

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .height(trackH)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x2EFFFFFF)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceAtLeast(0f))
                    .align(Alignment.CenterStart)
                    .height(trackH)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFF3EA6FF)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = thumbOffset)
                    .size(thumbR * 2)
                    .clip(CircleShape)
                    .background(if (focused) Color(0xFFF0BA66) else Color.White),
            )
        }

        TimePill(formatPlaybackClock(durationSec))
    }
}

@Composable
internal fun PlayerOptionPanel(
    panel: OverlayPanel,
    candidate: ResolvedPlaybackCandidate?,
    audioTracks: List<MpvTrackInfo>,
    subtitleTracks: List<MpvTrackInfo>,
    externalSubtitles: List<ExternalSubtitleTrack>,
    showOnlyPreferredSubtitleLanguages: Boolean = false,
    preferredSubtitleLanguages: List<String> = emptyList(),
    subtitlesLoading: Boolean,
    selectedAudioId: Int,
    selectedSubtitleId: Int,
    selectedExternalSubtitleId: String?,
    currentSpeed: Double,
    activeEngine: ActivePlaybackEngine,
    closeRequester: FocusRequester,
    firstItemRequester: FocusRequester,
    onClose: () -> Unit,
    onInteract: () -> Unit,
    onSelectStream: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onDisableSubtitles: () -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onSelectExternalSubtitle: (ExternalSubtitleTrack) -> Unit,
    onSelectSpeed: (Double) -> Unit,
    onSelectEngine: (ActivePlaybackEngine) -> Unit,
    modifier: Modifier = Modifier,
    /** Subtitle appearance, adjusted in place from the Subtitles panel rather than in Settings. */
    subtitleFontSize: Int = 55,
    subtitlePosition: Int = 92,
    subtitleDelay: Double = 0.0,
    onSubtitleFontSize: (Int) -> Unit = {},
    onSubtitlePosition: (Int) -> Unit = {},
    onSubtitleDelay: (Double) -> Unit = {},
    /** Subtitle delay is an mpv property; Media3 has nothing to shift. */
    subtitleDelaySupported: Boolean = true,
    onReloadStreams: () -> Unit = {},
    streamsReloading: Boolean = false,
    /** What the info panel reads. Null until the first sample comes back from the engine. */
    playbackStats: PlaybackStats? = null,
    currentStreamUrl: String? = null,
    currentLabel: String? = null,
    engineLabel: String = "",
    durationSec: Double = 0.0,
    isLive: Boolean = false,
    /** Resolves a plugin source back to the collection it was installed from. */
    pluginState: ProfilePluginState = ProfilePluginState(),
    subtitleDefaultSource: String = "All",
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Reset per opening, but honour the account preference rather than always choosing one source.
    var subtitleTab by remember(panel, subtitleDefaultSource) {
        mutableStateOf(
            when (normalizeSubtitleDefaultSource(subtitleDefaultSource)) {
                "BuiltIn" -> SubtitlePanelTab.BuiltIn
                "Addons" -> SubtitlePanelTab.Addons
                else -> SubtitlePanelTab.All
            },
        )
    }
    val configuredSubtitleSource = normalizeSubtitleDefaultSource(subtitleDefaultSource)
    val availableSubtitleTabs = remember(configuredSubtitleSource) {
        when (configuredSubtitleSource) {
            "BuiltIn" -> listOf(SubtitlePanelTab.BuiltIn, SubtitlePanelTab.Adjust)
            "Addons" -> listOf(SubtitlePanelTab.Addons, SubtitlePanelTab.Adjust)
            else -> SubtitlePanelTab.entries
        }
    }
    val allowedSubtitleLanguages = remember(preferredSubtitleLanguages) {
        preferredSubtitleLanguages.map(Languages::normalize)
            .filter { it.isNotBlank() && it != Languages.NONE }
            .distinct()
    }

    PlayerGlassSurface(
        modifier = modifier
            .width(540.dp)
            .height(640.dp)
            // Stream info has nothing to select, so nothing in it takes focus and the list had no
            // way to move: focus sat on Close and the rows below the fold were unreachable. The
            // panel drives its own scroll here instead of making read-only rows pretend to be
            // controls.
            .then(
                if (panel == OverlayPanel.Info) {
                    Modifier.onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val delta = when (event.key) {
                            Key.DirectionDown -> 160f
                            Key.DirectionUp -> -160f
                            else -> return@onPreviewKeyEvent false
                        }
                        onInteract()
                        scope.launch { listState.animateScrollBy(delta) }
                        true
                    }
                } else {
                    Modifier
                },
            ),
        contentPadding = PaddingValues(0.dp),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = when (panel) {
                                OverlayPanel.Streams -> "Sources"
                                OverlayPanel.Engine -> "Player Engine"
                                OverlayPanel.Audio -> "Audio"
                                OverlayPanel.Subtitles -> "Subtitles"
                                OverlayPanel.Speed -> "Playback Speed"
                                OverlayPanel.Info -> "Stream info"
                            },
                            style = androidx.tv.material3.MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                            color = Color.White,
                        )
                        Text(
                            text = when (panel) {
                                OverlayPanel.Streams -> "Switch streams without leaving playback."
                                OverlayPanel.Engine -> "Switch engines without losing your place."
                                OverlayPanel.Audio -> "Pick a different audio track."
                                OverlayPanel.Subtitles -> "Change the track, then size and place it."
                                OverlayPanel.Speed -> "Match playback speed to your preference."
                                OverlayPanel.Info -> "Where this stream comes from and how it is arriving."
                            },
                            style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.58f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (panel == OverlayPanel.Streams) {
                            OutlinedButton(
                                onClick = onReloadStreams,
                                shape = ButtonDefaults.shape(AppPillShape),
                                colors = ButtonDefaults.colors(
                                    containerColor = Color(0x10FFFFFF),
                                    focusedContainerColor = Color(0x22FFFFFF),
                                    contentColor = Color.White,
                                    focusedContentColor = Color.White,
                                ),
                            ) {
                                Text(if (streamsReloading) "Reloading…" else "Reload")
                            }
                        }
                        OutlinedButton(
                            onClick = onClose,
                            shape = ButtonDefaults.shape(AppPillShape),
                            modifier = Modifier.focusRequester(closeRequester),
                            colors = ButtonDefaults.colors(
                                containerColor = Color(0x10FFFFFF),
                                focusedContainerColor = Color(0x22FFFFFF),
                                contentColor = Color.White,
                                focusedContentColor = Color.White,
                            ),
                        ) {
                            Text("Close")
                        }
                    }
                }
            }

            if (panel == OverlayPanel.Subtitles) {
                item {
                    PanelTabRow(
                        tabs = availableSubtitleTabs,
                        selected = subtitleTab,
                        labelOf = { it.label },
                        onInteract = onInteract,
                        onSelect = { subtitleTab = it },
                    )
                }
            }

            when (panel) {
                OverlayPanel.Streams -> {
                    val streams = candidate?.streams.orEmpty()
                    if (streams.isEmpty()) {
                        item {
                            PanelNote(if (streamsReloading) "Searching for sources…" else "No sources loaded yet.")
                        }
                    }
                    itemsIndexed(streams) { index, stream ->
                        StreamOptionButton(
                            stream = stream,
                            origin = streamOriginLabel(stream, pluginState),
                            fallbackLabel = "Source ${index + 1}",
                            playing = candidate?.stream == stream,
                            requestFocus = if (index == 0) firstItemRequester else null,
                            onInteract = onInteract,
                            onClick = { onSelectStream(index) },
                        )
                    }
                }
                OverlayPanel.Engine -> {
                    item {
                        OptionButton(
                            label = "ExoPlayer",
                            subtitle = "Media3 playback engine",
                            active = activeEngine == ActivePlaybackEngine.Media3,
                            activeBadge = if (activeEngine == ActivePlaybackEngine.Media3) "Selected" else null,
                            requestFocus = firstItemRequester,
                            onInteract = onInteract,
                            onClick = { onSelectEngine(ActivePlaybackEngine.Media3) },
                        )
                    }
                    item {
                        OptionButton(
                            label = "mpv",
                            subtitle = "libMPV playback engine",
                            active = activeEngine == ActivePlaybackEngine.MPV,
                            activeBadge = if (activeEngine == ActivePlaybackEngine.MPV) "Selected" else null,
                            onInteract = onInteract,
                            onClick = { onSelectEngine(ActivePlaybackEngine.MPV) },
                        )
                    }
                    item {
                        PanelNote("Switching keeps your playback position. If a stream has no sound or a black screen, try the other engine.")
                    }
                }
                OverlayPanel.Audio -> {
                    itemsIndexed(audioTracks) { index, track ->
                        val language = trackLanguageName(track.language)
                        OptionButton(
                            label = track.title ?: language ?: "Track ${track.id}",
                            subtitle = listOfNotNull(language, track.codec).joinToString(" • ").ifBlank { null },
                            active = selectedAudioId == track.id,
                            activeBadge = if (selectedAudioId == track.id) "Selected" else null,
                            requestFocus = if (index == 0) firstItemRequester else null,
                            onInteract = onInteract,
                            onClick = { onSelectAudio(track.id) },
                        )
                    }
                }
                OverlayPanel.Subtitles -> if (subtitleTab != SubtitlePanelTab.Adjust) {
                    val visibleEmbeddedTracks = if (subtitleSourceIncludesBuiltIn(subtitleTab.name)) subtitleTracks.filter { track ->
                        preferredSubtitleLanguageAllowed(
                            track.language ?: track.title,
                            preferredSubtitleLanguages.getOrNull(0),
                            preferredSubtitleLanguages.getOrNull(1),
                            showOnlyPreferredSubtitleLanguages,
                        )
                    } else emptyList()
                    val visibleExternalSubtitles = externalSubtitles.filter { subtitle ->
                        subtitleOriginVisible(subtitleTab.name, subtitle.origin) &&
                            preferredSubtitleLanguageAllowed(
                                subtitle.language,
                                preferredSubtitleLanguages.getOrNull(0),
                                preferredSubtitleLanguages.getOrNull(1),
                                showOnlyPreferredSubtitleLanguages,
                            )
                    }
                    item {
                        OptionButton(
                            label = "Subtitles Off",
                            subtitle = "Disable every subtitle track",
                            active = selectedSubtitleId < 0 && selectedExternalSubtitleId == null,
                            activeBadge = if (selectedSubtitleId < 0 && selectedExternalSubtitleId == null) "Selected" else null,
                            requestFocus = firstItemRequester,
                            onInteract = onInteract,
                            onClick = onDisableSubtitles,
                        )
                    }
                    if (visibleEmbeddedTracks.isNotEmpty()) item {
                        Text(
                            "Embedded in video",
                            color = Color.White.copy(alpha = 0.62f),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 30.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
                        )
                    }
                    itemsIndexed(visibleEmbeddedTracks) { index, track ->
                        val language = trackLanguageName(track.language)
                        OptionButton(
                            label = language ?: track.title ?: "Subtitle ${track.id}",
                            subtitle = listOfNotNull("Embedded", track.title, track.codec).distinct().joinToString(" • "),
                            active = selectedExternalSubtitleId == null && selectedSubtitleId == track.id,
                            activeBadge = if (selectedExternalSubtitleId == null && selectedSubtitleId == track.id) "Selected" else null,
                            requestFocus = if (index == 0 && subtitleTracks.isEmpty()) firstItemRequester else null,
                            onInteract = onInteract,
                            onClick = { onSelectSubtitle(track.id) },
                        )
                    }
                    if (subtitlesLoading) {
                        item {
                            OptionButton(
                                label = "Searching subtitle sources...",
                                subtitle = when (subtitleTab) {
                                    SubtitlePanelTab.BuiltIn -> "OpenSubtitles and StreamDek sources"
                                    SubtitlePanelTab.Addons -> "Installed subtitle add-ons"
                                    else -> "Built-in sources and installed add-ons"
                                },
                                active = false,
                                onInteract = onInteract,
                                onClick = {},
                            )
                        }
                    }
                    if (visibleExternalSubtitles.isNotEmpty()) item {
                        Text(
                            when (subtitleTab) {
                                SubtitlePanelTab.BuiltIn -> "StreamDek sources"
                                SubtitlePanelTab.Addons -> "Subtitle add-ons"
                                else -> "Online subtitles"
                            },
                            color = Color.White.copy(alpha = 0.62f),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 30.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
                        )
                    }
                    itemsIndexed(visibleExternalSubtitles) { index, subtitle ->
                        val duplicateNumber = visibleExternalSubtitles.take(index + 1).count {
                            it.language == subtitle.language && it.sourceName == subtitle.sourceName
                        }
                        val duplicateCount = visibleExternalSubtitles.count {
                            it.language == subtitle.language && it.sourceName == subtitle.sourceName
                        }
                        OptionButton(
                            label = Languages.label(subtitle.language),
                            subtitle = listOfNotNull(
                                subtitle.sourceName,
                                if (subtitle.origin == ExternalSubtitleOrigin.BuiltIn) "Built-in source" else "Add-on",
                                subtitle.release,
                                if (duplicateCount > 1) "Option $duplicateNumber" else null,
                            ).joinToString(" • "),
                            active = selectedExternalSubtitleId == subtitle.id,
                            activeBadge = if (selectedExternalSubtitleId == subtitle.id) "Selected" else null,
                            onInteract = onInteract,
                            onClick = { onSelectExternalSubtitle(subtitle) },
                        )
                    }
                    if (!subtitlesLoading && visibleEmbeddedTracks.isEmpty() && visibleExternalSubtitles.isEmpty()) item {
                        val requestedLanguages = allowedSubtitleLanguages.joinToString(" or ") { Languages.label(it) }
                        OptionButton(
                            label = if (showOnlyPreferredSubtitleLanguages && requestedLanguages.isNotBlank()) {
                                "No subtitles found for $requestedLanguages"
                            } else when (subtitleTab) {
                                SubtitlePanelTab.BuiltIn -> "No built-in subtitles found"
                                SubtitlePanelTab.Addons -> "No subtitle add-on results found"
                                else -> "No matching subtitles found"
                            },
                            subtitle = when (subtitleTab) {
                                SubtitlePanelTab.BuiltIn -> "No embedded tracks or StreamDek source results"
                                SubtitlePanelTab.Addons -> "Try another language or enable another subtitle add-on"
                                else -> "Try another language or subtitle source"
                            },
                            active = false,
                            onInteract = onInteract,
                            onClick = {},
                        )
                    }
                } else {
                    // Adjustment is its own tab. Under a long track list it sat several screens
                    // down, so the viewer scrolled past every subtitle the add-ons found to reach
                    // the one control they wanted — and a list that grew while they scrolled kept
                    // moving it further away.
                    item {
                        PlayerStepperRow(
                            label = "Text size",
                            value = subtitleFontSize.toString(),
                            onInteract = onInteract,
                            onDecrease = { onSubtitleFontSize((subtitleFontSize - 2).coerceIn(SubtitleSizeRange)) },
                            onIncrease = { onSubtitleFontSize((subtitleFontSize + 2).coerceIn(SubtitleSizeRange)) },
                        )
                    }
                    item {
                        PlayerStepperRow(
                            label = "Position",
                            value = subtitlePosition.toString(),
                            hint = "Higher sits nearer the bottom",
                            onInteract = onInteract,
                            onDecrease = { onSubtitlePosition((subtitlePosition - 2).coerceIn(SubtitlePositionRange)) },
                            onIncrease = { onSubtitlePosition((subtitlePosition + 2).coerceIn(SubtitlePositionRange)) },
                        )
                    }
                    if (subtitleDelaySupported) {
                        item {
                            PlayerStepperRow(
                                label = "Delay",
                                value = String.format(Locale.US, "%+.1f s", subtitleDelay),
                                hint = "Nudge subtitles ahead of or behind the audio",
                                onInteract = onInteract,
                                onDecrease = {
                                    onSubtitleDelay((subtitleDelay - 0.25).coerceIn(SubtitleDelayRange))
                                },
                                onIncrease = {
                                    onSubtitleDelay((subtitleDelay + 0.25).coerceIn(SubtitleDelayRange))
                                },
                            )
                        }
                    }
                    item {
                        PanelNote("Colour, outline and background are in Settings › Subtitles.")
                    }
                }
                OverlayPanel.Speed -> {
                    itemsIndexed(
                        listOf(
                            SpeedOption("0.75x", 0.75),
                            SpeedOption("1.0x", 1.0),
                            SpeedOption("1.25x", 1.25),
                            SpeedOption("1.5x", 1.5),
                            SpeedOption("2.0x", 2.0),
                        ),
                    ) { index, option ->
                        OptionButton(
                            label = option.label,
                            subtitle = null,
                            active = currentSpeed == option.value,
                            activeBadge = if (currentSpeed == option.value) "Selected" else null,
                            requestFocus = if (index == 0) firstItemRequester else null,
                            onInteract = onInteract,
                            onClick = { onSelectSpeed(option.value) },
                        )
                    }
                }
                OverlayPanel.Info -> {
                    val stream = candidate?.stream
                    val transport = streamTransport(stream, currentStreamUrl.orEmpty())
                    val sourceRows = buildList {
                        streamProviderLabel(stream, currentLabel)?.let { add("Provider" to it) }
                        streamOriginLabel(stream, pluginState)?.let { add("Installed as" to it) }
                        add("Delivery" to transport.label)
                        stream?.size?.takeIf { it.isNotBlank() }?.let { add("Size" to it) }
                        stream?.quality?.takeIf { it.isNotBlank() }?.let { add("Quality" to it) }
                        (stream?.filename ?: stream?.behaviorHints?.filename)?.takeIf { it.isNotBlank() }
                            ?.let { add("File" to it) }
                    }
                    val playbackRows = buildList {
                        formatTransferRate(playbackStats?.bytesPerSecond)?.let { add("Speed" to it) }
                        formatResolution(playbackStats?.width ?: 0, playbackStats?.height ?: 0)?.let { add("Resolution" to it) }
                        val videoLine = listOfNotNull(
                            prettyCodecName(playbackStats?.videoCodec),
                            formatBitrate(playbackStats?.videoBitrateBps),
                            playbackStats?.frameRate?.let { String.format(Locale.US, "%.0f fps", it) },
                        ).joinToString(" · ")
                        if (videoLine.isNotBlank()) add("Video" to videoLine)
                        val audioLine = listOfNotNull(
                            prettyCodecName(playbackStats?.audioCodec),
                            playbackStats?.audioChannels?.let { channels ->
                                when {
                                    channels > 2 -> "${channels}ch"
                                    channels == 2 -> "Stereo"
                                    else -> "Mono"
                                }
                            },
                        ).joinToString(" · ")
                        if (audioLine.isNotBlank()) add("Audio" to audioLine)
                        playbackStats?.bufferedSeconds?.let { add("Buffered" to String.format(Locale.US, "%.0f s ahead", it)) }
                        playbackStats?.hardwareDecoder?.let { add("Decoder" to it) }
                        if (engineLabel.isNotBlank()) add("Engine" to engineLabel)
                        if (!isLive && durationSec > 0.0) add("Runtime" to formatPlaybackClock(durationSec))
                    }
                    item { PanelSectionHeading("Source") }
                    item { PlayerInfoTable(sourceRows) }
                    item { PanelSectionHeading("Playback") }
                    item { PlayerInfoTable(playbackRows) }
                    if (playbackStats == null) {
                        item { PanelNote("Reading playback details from the engine…") }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(14.dp)) }
        }
    }
}

/**
 * The skip / next-episode prompt.
 *
 * It holds focus for as long as it is on screen, which is the point: while it is up it is the only
 * thing the remote can act on, so a press cannot half-open the transport controls behind it and
 * leave the viewer wondering which of the two the next press will hit.
 */
@Composable
internal fun PlayerSkipActionChip(
    label: String,
    bottomPadding: androidx.compose.ui.unit.Dp,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The one thing in the player that should announce itself. It appears unbidden, over content
    // the viewer is already watching, and it has a few seconds to be noticed before the moment it
    // offers to skip has passed — so it springs in with a small overshoot rather than fading up,
    // which at this size against moving video is easy to miss entirely.
    val appeared = remember { androidx.compose.animation.core.Animatable(0.82f) }
    val emphasis = TvMotion.emphasisSpec<Float>()
    LaunchedEffect(label) { appeared.animateTo(1f, emphasis) }
    OutlinedButton(
        onClick = onClick,
        shape = ButtonDefaults.shape(AppPillShape),
        colors = ButtonDefaults.colors(
            containerColor = Color(0xEE12141C),
            focusedContainerColor = Color(0xFF1A1E28),
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color(0x28FFFFFF)),
                shape = AppPillShape,
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, Color(0xFFF0BA66)),
                shape = AppPillShape,
            ),
        ),
        modifier = modifier
            .padding(end = 24.dp, bottom = bottomPadding)
            .graphicsLayer {
                scaleX = appeared.value
                scaleY = appeared.value
                alpha = appeared.value
            }
            .focusRequester(focusRequester),
    ) {
        Icon(
            imageVector = Icons.Filled.SkipNext,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = Color(0xFFF0BA66),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = androidx.tv.material3.MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Black),
            color = Color.White,
        )
    }
}

@Composable
internal fun NextEpisodeDialog(
    detail: MediaDetail?,
    episode: EpisodeContext,
    streams: List<AddonStream>,
    loading: Boolean,
    countdown: Int?,
    playRequester: FocusRequester,
    cancelRequester: FocusRequester,
    onPlayNow: () -> Unit,
    onSelectStream: (Int) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xB8000000)),
        contentAlignment = Alignment.Center,
    ) {
        PlayerGlassSurface(
            modifier = Modifier.width(760.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                ) {
                    val heroArt = episode.still ?: detail?.backdrop ?: detail?.poster
                    if (!heroArt.isNullOrBlank()) {
                        AsyncImage(
                            model = heroArt,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1A1E28)))
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(Color.Transparent, Color(0xD912141C)),
                                ),
                            ),
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = detail?.title ?: "Next Episode",
                            style = androidx.tv.material3.MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                            color = Color.White,
                        )
                        Text(
                            text = buildString {
                                append("S${episode.seasonNumber.toString().padStart(2, '0')}E${episode.episodeNumber.toString().padStart(2, '0')}")
                                episode.title?.takeIf { it.isNotBlank() }?.let {
                                    append("  ·  ")
                                    append(it)
                                }
                            },
                            style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.82f),
                        )
                    }
                }

                if (countdown != null && countdown > 0 && streams.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Auto-playing in ${countdown}s",
                            style = androidx.tv.material3.MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White.copy(alpha = 0.9f),
                        )
                        OutlinedButton(onClick = onCancel) {
                            Text("Cancel")
                        }
                    }
                }

                when {
                    loading && streams.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Finding streams…",
                                style = androidx.tv.material3.MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                        }
                    }
                    streams.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No streams found for the next episode.",
                                style = androidx.tv.material3.MaterialTheme.typography.bodyLarge,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            itemsIndexed(streams) { index, stream ->
                                val meta = buildList {
                                    stream.quality?.takeIf { it.isNotBlank() }?.let(::add)
                                    stream.size?.takeIf { it.isNotBlank() }?.let(::add)
                                    stream.addonName.takeIf { it.isNotBlank() }?.let(::add)
                                }.joinToString(" • ")
                                OptionButton(
                                    label = stream.name?.takeIf { it.isNotBlank() }
                                        ?: stream.title?.takeIf { it.isNotBlank() }
                                        ?: stream.addonName.takeIf { it.isNotBlank() }
                                        ?: "Source ${index + 1}",
                                    subtitle = meta.ifBlank { null },
                                    active = index == 0,
                                    activeBadge = if (index == 0) "Auto" else null,
                                    trailingPill = stream.quality,
                                    requestFocus = null,
                                    onInteract = {},
                                    onClick = { onSelectStream(index) },
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.focusRequester(cancelRequester),
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = onPlayNow,
                        enabled = streams.isNotEmpty(),
                        modifier = Modifier.focusRequester(playRequester),
                    ) {
                        Text("Play Now")
                    }
                }
            }
        }
    }
}

/**
 * A track's language spelled out, rather than the tag the container happened to carry.
 *
 * Containers say "eng", "fre", "pt-BR" and occasionally "English"; none of those is what a viewer
 * is looking for when they open this list to find their own language. [Languages] already knows
 * every spelling, so the tag is resolved through it and the full name shown instead. A tag it does
 * not recognise is left as it was written — a track labelled with something private to one encoder
 * is still better identified by that than by "Unknown".
 */
private fun trackLanguageName(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val normalized = Languages.normalize(value)
    return if (normalized.isEmpty()) value.uppercase() else Languages.label(normalized)
}

@Composable
private fun OptionButton(
    label: String,
    subtitle: String?,
    active: Boolean,
    activeBadge: String? = null,
    trailingPill: String? = null,
    requestFocus: FocusRequester? = null,
    onInteract: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = onClick,
        scale = ButtonDefaults.scale(focusedScale = TvMotion.focusScale()),
        shape = ButtonDefaults.shape(RoundedCornerShape(14.dp)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .then(if (requestFocus != null) Modifier.focusRequester(requestFocus) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onInteract()
            },
        colors = ButtonDefaults.colors(
            containerColor = if (active) Color(0x268B5CF6) else Color(0x10FFFFFF),
            focusedContainerColor = if (active) Color(0x338B5CF6) else Color(0x22FFFFFF),
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(
                    if (focused) 2.dp else 1.dp,
                    when {
                        focused -> Color(0xFFF0BA66)
                        active -> Color(0x668B5CF6)
                        else -> Color(0x12FFFFFF)
                    },
                ),
                shape = RoundedCornerShape(14.dp),
            ),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = androidx.tv.material3.MaterialTheme.typography.titleSmall.copy(fontWeight = if (focused) FontWeight.Black else FontWeight.Bold),
                        color = Color.White,
                    )
                    activeBadge?.let { ActiveBadge(it) }
                }
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White.copy(alpha = 0.55f),
                        style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                    )
                }
            }
            trailingPill?.takeIf { it.isNotBlank() }?.let {
                QualityPill(text = it, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

/**
 * One source in the player's list, carrying what the picker screen carries.
 *
 * The row used to be a bare release name over a "quality • size • add-on" string, so choosing a
 * replacement mid-film meant guessing at exactly the facts — who is serving it, whether a debrid
 * service already holds it — that the full picker shows plainly. These are the same three columns
 * that screen uses, in the same order, so a viewer moves between them without relearning the row.
 */
@Composable
private fun StreamOptionButton(
    stream: AddonStream,
    /** "Add-on", or "Plugin · <collection>" — which of the two setups this source belongs to. */
    origin: String?,
    fallbackLabel: String,
    playing: Boolean,
    requestFocus: FocusRequester?,
    onInteract: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val releaseLabel = stream.name?.takeIf { it.isNotBlank() }
        ?: stream.title?.takeIf { it.isNotBlank() }
        ?: stream.addonName.takeIf { it.isNotBlank() }
        ?: fallbackLabel
    val quality = streamQualityLabel(stream, releaseLabel)
    val size = streamSizeLabel(stream, releaseLabel)
    val availability = when {
        // Same wording as the streams picker, so the row a viewer chose there is recognisable
        // here — two names for the same promise reads as two different things.
        stream.cachedBy.isNotEmpty() -> readyServiceLabel(stream.cachedBy).orEmpty() to true
        !stream.url.isNullOrBlank() -> "Direct" to false
        !stream.nzbUrl.isNullOrBlank() -> "Usenet" to false
        else -> "Torrent" to false
    }
    OutlinedButton(
        onClick = onClick,
        scale = ButtonDefaults.scale(focusedScale = TvMotion.focusScale()),
        shape = ButtonDefaults.shape(RoundedCornerShape(14.dp)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .then(if (requestFocus != null) Modifier.focusRequester(requestFocus) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onInteract()
            },
        colors = ButtonDefaults.colors(
            containerColor = if (playing) Color(0x268B5CF6) else Color(0x10FFFFFF),
            focusedContainerColor = if (playing) Color(0x338B5CF6) else Color(0x22FFFFFF),
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(
                    if (focused) 2.dp else 1.dp,
                    when {
                        focused -> Color(0xFFF0BA66)
                        playing -> Color(0x668B5CF6)
                        else -> Color(0x12FFFFFF)
                    },
                ),
                shape = RoundedCornerShape(14.dp),
            ),
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stream.addonName.ifBlank { "Stream source" },
                    style = androidx.tv.material3.MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
                    color = Color(0xFFD4B8FF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                origin?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = androidx.tv.material3.MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.45f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (playing) ActiveBadge("Playing")
            }
            Text(
                text = releaseLabel,
                style = androidx.tv.material3.MaterialTheme.typography.titleSmall.copy(
                    fontWeight = if (focused) FontWeight.Black else FontWeight.Bold,
                ),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Second line only when it has something the first did not say. Several sources fill
            // every text field with the same string, and this row printed it twice.
            stream.description
                ?.takeIf { it.isNotBlank() && streamTextFingerprint(it) != streamTextFingerprint(releaseLabel) }
                ?.let {
                    Text(
                        text = it,
                        style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                quality?.let { QualityPill(it) }
                size?.let { QualityPill(it) }
                StreamAvailabilityPill(availability.first, cached = availability.second)
            }
        }
    }
}

/** "Cached by Real-Debrid" reads differently from "Torrent", so it is coloured differently too. */
@Composable
private fun StreamAvailabilityPill(text: String, cached: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (cached) Color(0x3322C55E) else Color(0x14FFFFFF))
            .border(1.dp, if (cached) Color(0x6622C55E) else Color(0x22FFFFFF), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = androidx.tv.material3.MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
            color = if (cached) Color(0xFF9DE8B4) else Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A value the remote's left and right keys move, rather than a list of every step.
 *
 * A slider needs a pointer and a list of sixty text sizes needs sixty presses; this is one focus
 * stop that reports the value as it changes, which is how the rest of the TV's numeric settings
 * behave. Explicit −/+ targets stay for a remote whose ring is unreliable.
 */
@Composable
private fun PlayerStepperRow(
    label: String,
    value: String,
    onInteract: () -> Unit,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    hint: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = onIncrease,
        scale = ButtonDefaults.scale(focusedScale = TvMotion.focusScale()),
        shape = ButtonDefaults.shape(RoundedCornerShape(14.dp)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onInteract()
            }
            .onPreviewKeyEvent { event ->
                if (!focused || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onDecrease(); true }
                    Key.DirectionRight -> { onIncrease(); true }
                    else -> false
                }
            },
        colors = ButtonDefaults.colors(
            containerColor = Color(0x10FFFFFF),
            focusedContainerColor = Color(0x22FFFFFF),
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(
                    if (focused) 2.dp else 1.dp,
                    if (focused) Color(0xFFF0BA66) else Color(0x12FFFFFF),
                ),
                shape = RoundedCornerShape(14.dp),
            ),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = label,
                    style = androidx.tv.material3.MaterialTheme.typography.titleSmall.copy(
                        fontWeight = if (focused) FontWeight.Black else FontWeight.Bold,
                    ),
                    color = Color.White,
                )
                (hint ?: "Left and right to adjust").let {
                    Text(
                        text = it,
                        style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "−",
                    style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = if (focused) 0.9f else 0.4f),
                )
                Text(
                    text = value,
                    style = androidx.tv.material3.MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                    color = Color(0xFFF0BA66),
                )
                Text(
                    text = "+",
                    style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = if (focused) 0.9f else 0.4f),
                )
            }
        }
    }
}

/**
 * A row of pills that swaps what the panel below is showing.
 *
 * Each pill is a real focus stop, so the strip is reached by pressing down from the header and
 * crossed with left and right — the same two axes every other row in the panel uses.
 */
@Composable
private fun <T> PanelTabRow(
    tabs: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onInteract: () -> Unit,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusGroup()
            .padding(start = 14.dp, end = 14.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEach { tab ->
            val active = tab == selected
            var focused by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { onSelect(tab) },
                scale = ButtonDefaults.scale(focusedScale = TvMotion.focusScale()),
                shape = ButtonDefaults.shape(AppPillShape),
                modifier = Modifier.onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onInteract()
                },
                colors = ButtonDefaults.colors(
                    containerColor = if (active) Color(0x338B5CF6) else Color(0x10FFFFFF),
                    focusedContainerColor = if (active) Color(0x448B5CF6) else Color(0x22FFFFFF),
                    contentColor = Color.White,
                    focusedContentColor = Color.White,
                ),
                border = ButtonDefaults.border(
                    border = Border(
                        border = BorderStroke(
                            if (focused) 2.dp else 1.dp,
                            when {
                                focused -> Color(0xFFF0BA66)
                                active -> Color(0x668B5CF6)
                                else -> Color(0x12FFFFFF)
                            },
                        ),
                        shape = AppPillShape,
                    ),
                ),
            ) {
                Text(
                    text = labelOf(tab),
                    style = androidx.tv.material3.MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (active) FontWeight.Black else FontWeight.SemiBold,
                    ),
                )
            }
        }
    }
}

@Composable
private fun PanelSectionHeading(text: String) {
    Text(
        text = text.uppercase(Locale.US),
        style = androidx.tv.material3.MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Black,
            letterSpacing = 1.2.sp,
        ),
        color = Color.White.copy(alpha = 0.44f),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun PanelNote(text: String) {
    Text(
        text = text,
        style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

/** Label-and-value pairs. Rows with nothing to say are dropped rather than printed as a dash. */
@Composable
private fun PlayerInfoTable(rows: List<Pair<String, String>>) {
    if (rows.isEmpty()) {
        PanelNote("Nothing reported yet.")
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x10FFFFFF))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        rows.forEach { (label, value) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = label,
                    style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.56f),
                )
                Text(
                    text = value,
                    style = androidx.tv.material3.MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ActiveBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0x268B5CF6))
            .border(1.dp, Color(0x668B5CF6), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = androidx.tv.material3.MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
            color = Color(0xFFE9DDFF),
        )
    }
}

@Composable
private fun QualityPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0x338B5CF6))
            .border(1.dp, Color(0x668B5CF6), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = androidx.tv.material3.MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
            color = Color(0xFFD4B8FF),
        )
    }
}

@Composable
private fun TimePill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x6B000000))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            style = androidx.tv.material3.MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
        )
    }
}
