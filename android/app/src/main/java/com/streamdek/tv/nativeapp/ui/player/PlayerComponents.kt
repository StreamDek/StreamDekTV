package com.streamdek.tv.nativeapp.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamdek.tv.mpv.MpvTrackInfo
import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.ResolvedPlaybackCandidate
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.formatPlaybackClock

internal enum class OverlayPanel {
    Streams,
    Audio,
    Subtitles,
    Speed,
}

internal data class SpeedOption(
    val label: String,
    val value: Double,
)

private val PlayerPanelShape = RoundedCornerShape(22.dp)

@Composable
internal fun PlayerOverlayVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 4 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 5 }),
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
                    colors = listOf(Color(0xED12141C), Color(0xF1181B24)),
                ),
            )
            .border(1.dp, Color(0x1FFFFFFF), PlayerPanelShape)
            .padding(contentPadding),
    ) {
        content()
    }
}

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
    rewindRequester: FocusRequester,
    nextRequester: FocusRequester,
    speedRequester: FocusRequester,
    progressRequester: FocusRequester,
    onInteract: () -> Unit,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onNext: () -> Unit,
    onSeekRelative: (Double) -> Unit,
    onOpenPanel: (OverlayPanel) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            }

            PlayerTimeline(
                positionSec = positionSec,
                durationSec = durationSec,
                requester = progressRequester,
                downRequester = playRequester,
                onInteract = onInteract,
                onSeekRelative = onSeekRelative,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PlayerControlIconButton(
                    icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    label = if (paused) "Play" else "Pause",
                    primary = true,
                    requester = playRequester,
                    upRequester = progressRequester,
                    rightRequester = subtitlesRequester,
                    onFocused = onInteract,
                    onClick = onPlayPause,
                )
                Spacer(Modifier.width(8.dp))
                PlayerControlIconButton(
                    icon = Icons.Filled.ClosedCaption,
                    label = "Subtitles",
                    active = selectedPanel == OverlayPanel.Subtitles,
                    requester = subtitlesRequester,
                    upRequester = progressRequester,
                    leftRequester = playRequester,
                    rightRequester = audioRequester,
                    onFocused = onInteract,
                    onClick = { onOpenPanel(OverlayPanel.Subtitles) },
                )
                PlayerControlIconButton(
                    icon = Icons.Filled.VolumeUp,
                    label = "Audio",
                    active = selectedPanel == OverlayPanel.Audio,
                    requester = audioRequester,
                    upRequester = progressRequester,
                    leftRequester = subtitlesRequester,
                    rightRequester = sourcesRequester,
                    onFocused = onInteract,
                    onClick = { onOpenPanel(OverlayPanel.Audio) },
                )
                PlayerControlIconButton(
                    icon = Icons.Filled.Cloud,
                    label = "Sources",
                    active = selectedPanel == OverlayPanel.Streams,
                    requester = sourcesRequester,
                    upRequester = progressRequester,
                    leftRequester = audioRequester,
                    rightRequester = rewindRequester,
                    onFocused = onInteract,
                    onClick = { onOpenPanel(OverlayPanel.Streams) },
                )
                PlayerControlIconButton(
                    icon = Icons.Filled.Replay10,
                    label = "Rewind",
                    requester = rewindRequester,
                    upRequester = progressRequester,
                    leftRequester = sourcesRequester,
                    rightRequester = if (hasNext) nextRequester else speedRequester,
                    onFocused = onInteract,
                    onClick = onRewind,
                )
                if (hasNext) {
                    PlayerControlIconButton(
                        icon = Icons.Filled.SkipNext,
                        label = "Next",
                        requester = nextRequester,
                        upRequester = progressRequester,
                        leftRequester = rewindRequester,
                        rightRequester = speedRequester,
                        onFocused = onInteract,
                        onClick = onNext,
                    )
                }
                PlayerControlIconButton(
                    icon = Icons.Filled.Speed,
                    label = "Speed",
                    active = selectedPanel == OverlayPanel.Speed,
                    requester = speedRequester,
                    upRequester = progressRequester,
                    leftRequester = if (hasNext) nextRequester else rewindRequester,
                    onFocused = onInteract,
                    onClick = { onOpenPanel(OverlayPanel.Speed) },
                )

                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
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
        scale = ButtonDefaults.scale(focusedScale = 1.04f),
        modifier = Modifier
            .size(size)
            .focusRequester(requester)
            .focusProperties {
                if (upRequester != null) up = upRequester
                if (leftRequester != null) left = leftRequester
                if (rightRequester != null) right = rightRequester
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

@Composable
internal fun PlayerTimeline(
    positionSec: Double,
    durationSec: Double,
    requester: FocusRequester,
    downRequester: FocusRequester,
    onInteract: () -> Unit,
    onSeekRelative: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val progress = if (durationSec > 0.0) (positionSec / durationSec).coerceIn(0.0, 1.0).toFloat() else 0f
    val seekStepSeconds = when {
        durationSec >= 7200.0 -> 20.0
        durationSec >= 3600.0 -> 12.0
        else -> 8.0
    }

    Column(
        modifier = modifier
            .focusRequester(requester)
            .focusable()
            .focusProperties { down = downRequester }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onInteract()
            }
            .onPreviewKeyEvent { event ->
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (event.type == KeyEventType.KeyDown) {
                            onSeekRelative(-seekStepSeconds)
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyDown) {
                            onSeekRelative(seekStepSeconds)
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (event.type == KeyEventType.KeyUp) {
                            onInteract()
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (focused) {
            Text(
                text = "Scrub with left  /  right",
                style = androidx.tv.material3.MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFFF0BA66),
                modifier = Modifier.padding(start = 2.dp),
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimePill(formatPlaybackClock(positionSec))
            TimePill(formatPlaybackClock(durationSec))
        }
    }
}

@Composable
internal fun PlayerOptionPanel(
    panel: OverlayPanel,
    candidate: ResolvedPlaybackCandidate?,
    audioTracks: List<MpvTrackInfo>,
    subtitleTracks: List<MpvTrackInfo>,
    selectedAudioId: Int,
    selectedSubtitleId: Int,
    currentSpeed: Double,
    closeRequester: FocusRequester,
    firstItemRequester: FocusRequester,
    onClose: () -> Unit,
    onInteract: () -> Unit,
    onSelectStream: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onDisableSubtitles: () -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onSelectSpeed: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerGlassSurface(
        modifier = modifier
            .width(540.dp)
            .height(640.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        LazyColumn(
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
                                OverlayPanel.Audio -> "Audio"
                                OverlayPanel.Subtitles -> "Subtitles"
                                OverlayPanel.Speed -> "Playback Speed"
                            },
                            style = androidx.tv.material3.MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                            color = Color.White,
                        )
                        Text(
                            text = when (panel) {
                                OverlayPanel.Streams -> "Switch streams without leaving playback."
                                OverlayPanel.Audio -> "Pick a different audio track."
                                OverlayPanel.Subtitles -> "Enable, disable, or change subtitle tracks."
                                OverlayPanel.Speed -> "Match playback speed to your preference."
                            },
                            style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.58f),
                        )
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

            when (panel) {
                OverlayPanel.Streams -> {
                    itemsIndexed(candidate?.streams.orEmpty()) { index, stream ->
                        val meta = buildList {
                            stream.quality?.takeIf { it.isNotBlank() }?.let { add(it) }
                            stream.size?.takeIf { it.isNotBlank() }?.let { add(it) }
                            stream.addonName.takeIf { it.isNotBlank() }?.let { add(it) }
                        }.joinToString(" • ")
                        OptionButton(
                            label = stream.name?.takeIf { it.isNotBlank() }
                                ?: stream.title?.takeIf { it.isNotBlank() }
                                ?: stream.addonName.takeIf { it.isNotBlank() }
                                ?: "Source ${index + 1}",
                            subtitle = meta.ifBlank { null },
                            active = candidate?.stream == stream,
                            activeBadge = if (candidate?.stream == stream) "Playing" else null,
                            trailingPill = stream.quality,
                            requestFocus = if (index == 0) firstItemRequester else null,
                            onInteract = onInteract,
                            onClick = { onSelectStream(index) },
                        )
                    }
                }
                OverlayPanel.Audio -> {
                    itemsIndexed(audioTracks) { index, track ->
                        OptionButton(
                            label = track.title ?: track.language ?: "Track ${track.id}",
                            subtitle = listOfNotNull(track.language, track.codec).joinToString(" • ").ifBlank { null },
                            active = selectedAudioId == track.id,
                            activeBadge = if (selectedAudioId == track.id) "Selected" else null,
                            requestFocus = if (index == 0) firstItemRequester else null,
                            onInteract = onInteract,
                            onClick = { onSelectAudio(track.id) },
                        )
                    }
                }
                OverlayPanel.Subtitles -> {
                    item {
                        OptionButton(
                            label = "Subtitles Off",
                            subtitle = "Disable subtitles for this stream",
                            active = selectedSubtitleId < 0,
                            activeBadge = if (selectedSubtitleId < 0) "Selected" else null,
                            requestFocus = firstItemRequester,
                            onInteract = onInteract,
                            onClick = onDisableSubtitles,
                        )
                    }
                    itemsIndexed(subtitleTracks) { index, track ->
                        OptionButton(
                            label = track.title ?: track.language ?: "Subtitle ${track.id}",
                            subtitle = listOfNotNull(track.language, track.codec).joinToString(" • ").ifBlank { null },
                            active = selectedSubtitleId == track.id,
                            activeBadge = if (selectedSubtitleId == track.id) "Selected" else null,
                            requestFocus = if (index == 0 && subtitleTracks.isEmpty()) firstItemRequester else null,
                            onInteract = onInteract,
                            onClick = { onSelectSubtitle(track.id) },
                        )
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
            }

            item { Spacer(modifier = Modifier.height(14.dp)) }
        }
    }
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
        scale = ButtonDefaults.scale(focusedScale = 1.02f),
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
