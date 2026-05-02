package com.streamdek.tv.nativeapp.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.streamdek.tv.mpv.MpvTrackInfo
import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.ResolvedPlaybackCandidate
import com.streamdek.tv.nativeapp.ui.AppCardShape
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
            .clip(AppCardShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xD8111318), Color(0xCC0B0D12)),
                ),
            )
            .border(1.dp, Color(0x22FFFFFF), AppCardShape)
            .padding(contentPadding),
    ) {
        content()
    }
}

@Composable
internal fun PlayerTopInfo(
    detail: MediaDetail?,
    requestTitle: String?,
    currentEpisode: EpisodeContext?,
    currentLabel: String,
    error: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = detail?.title ?: requestTitle ?: "Loading",
            style = androidx.tv.material3.MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        currentEpisode?.let { ep ->
            Text(
                text = buildString {
                    append("S${ep.seasonNumber}  E${ep.episodeNumber}")
                    if (!ep.title.isNullOrBlank()) append("  •  ${ep.title}")
                },
                style = androidx.tv.material3.MaterialTheme.typography.titleSmall,
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (error != null) {
            Text(
                text = error,
                style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFB4AB),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = currentLabel,
                style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.56f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun PlayerCenterControls(
    paused: Boolean,
    hasNext: Boolean,
    playPauseRequester: FocusRequester,
    rewindRequester: FocusRequester,
    forwardRequester: FocusRequester,
    nextRequester: FocusRequester,
    progressRequester: FocusRequester,
    onInteract: () -> Unit,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerIconButton(
            icon = Icons.Filled.Replay10,
            iconSize = 34.dp,
            buttonSize = 72.dp,
            requester = rewindRequester,
            downRequester = progressRequester,
            onFocused = onInteract,
            onPressed = onRewind,
        )
        PlayerIconButton(
            icon = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            iconSize = 48.dp,
            buttonSize = 96.dp,
            primary = true,
            requester = playPauseRequester,
            downRequester = progressRequester,
            onFocused = onInteract,
            onPressed = onPlayPause,
        )
        PlayerIconButton(
            icon = Icons.Filled.Forward10,
            iconSize = 34.dp,
            buttonSize = 72.dp,
            requester = forwardRequester,
            downRequester = progressRequester,
            onFocused = onInteract,
            onPressed = onForward,
        )
        if (hasNext) {
            PlayerIconButton(
                icon = Icons.Filled.SkipNext,
                iconSize = 32.dp,
                buttonSize = 64.dp,
                requester = nextRequester,
                downRequester = progressRequester,
                onFocused = onInteract,
                onPressed = onNext,
            )
        }
    }
}

@Composable
private fun PlayerIconButton(
    icon: ImageVector,
    iconSize: Dp,
    buttonSize: Dp,
    primary: Boolean = false,
    requester: FocusRequester,
    downRequester: FocusRequester,
    onFocused: () -> Unit,
    onPressed: () -> Unit,
) {
    Button(
        onClick = onPressed,
        shape = ButtonDefaults.shape(CircleShape),
        colors = ButtonDefaults.colors(
            containerColor = if (primary) Color(0xFFF4EDE2) else Color(0x38FFFFFF),
            focusedContainerColor = if (primary) Color.White else Color(0x66FFFFFF),
            contentColor = if (primary) Color(0xFF18120A) else Color.White,
        ),
        modifier = Modifier
            .padding(horizontal = 10.dp)
            .size(buttonSize)
            .focusRequester(requester)
            .focusProperties { down = downRequester }
            .onFocusChanged {
                if (it.isFocused) onFocused()
            },
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = if (primary) Color(0xFF18120A) else Color.White,
        )
    }
}

@Composable
internal fun PlayerTimeline(
    positionSec: Double,
    durationSec: Double,
    requester: FocusRequester,
    controlsRequester: FocusRequester,
    settingsRequester: FocusRequester,
    onInteract: () -> Unit,
    onSeekRelative: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    PlayerGlassSurface(
        modifier = modifier
            .focusRequester(requester)
            .focusProperties {
                up = controlsRequester
                down = settingsRequester
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onInteract()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onSeekRelative(-10.0)
                        true
                    }
                    Key.DirectionRight -> {
                        onSeekRelative(10.0)
                        true
                    }
                    else -> false
                }
            },
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (focused) 8.dp else 5.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x3FFFFFFF)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(
                            if (durationSec > 0.0) (positionSec / durationSec).coerceIn(0.0, 1.0).toFloat() else 0f,
                        )
                        .height(if (focused) 8.dp else 5.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFFF4EDE2)),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatPlaybackClock(positionSec),
                    style = androidx.tv.material3.MaterialTheme.typography.titleSmall,
                    color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                )
                Text(
                    text = formatPlaybackClock(durationSec),
                    style = androidx.tv.material3.MaterialTheme.typography.titleSmall,
                    color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
internal fun SettingsPanel(
    requester: FocusRequester,
    progressRequester: FocusRequester,
    selectedPanel: OverlayPanel?,
    onInteract: () -> Unit,
    onOpenPanel: (OverlayPanel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        OverlayPanel.Streams to "Quality",
        OverlayPanel.Audio to "Audio",
        OverlayPanel.Subtitles to "Subtitles",
        OverlayPanel.Speed to "Speed",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(requester)
            .focusProperties { up = progressRequester },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, (panel, label) ->
            OutlinedButton(
                onClick = { onOpenPanel(panel) },
                shape = ButtonDefaults.shape(AppPillShape),
                modifier = Modifier
                    .padding(start = if (index == 0) 0.dp else 10.dp)
                    .onFocusChanged { if (it.isFocused) onInteract() },
                colors = ButtonDefaults.colors(
                    containerColor = if (selectedPanel == panel) Color(0x22F4EDE2) else Color.Transparent,
                    contentColor = androidx.tv.material3.MaterialTheme.colorScheme.onBackground,
                ),
            ) {
                Text(label)
            }
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
            .width(440.dp)
            .height(660.dp),
        contentPadding = PaddingValues(20.dp),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = when (panel) {
                        OverlayPanel.Streams -> "Quality"
                        OverlayPanel.Audio -> "Audio"
                        OverlayPanel.Subtitles -> "Subtitles"
                        OverlayPanel.Speed -> "Playback Speed"
                    },
                    style = androidx.tv.material3.MaterialTheme.typography.headlineSmall,
                    color = androidx.tv.material3.MaterialTheme.colorScheme.onBackground,
                )
            }
            when (panel) {
                OverlayPanel.Streams -> {
                    items(candidate?.streams.orEmpty().mapIndexed { index, stream -> index to stream }) { (index, stream) ->
                        OptionButton(
                            label = "${stream.addonName} ${stream.quality ?: ""} ${stream.size ?: ""}".trim(),
                            active = candidate?.stream == stream,
                            onInteract = onInteract,
                            onClick = { onSelectStream(index) },
                        )
                    }
                }
                OverlayPanel.Audio -> {
                    items(audioTracks) { track ->
                        OptionButton(
                            label = track.title ?: track.language ?: "Track ${track.id}",
                            active = selectedAudioId == track.id,
                            onInteract = onInteract,
                            onClick = { onSelectAudio(track.id) },
                        )
                    }
                }
                OverlayPanel.Subtitles -> {
                    item {
                        OptionButton(
                            label = if (selectedSubtitleId < 0) "Subtitles Off (Selected)" else "Subtitles Off",
                            active = selectedSubtitleId < 0,
                            onInteract = onInteract,
                            onClick = onDisableSubtitles,
                        )
                    }
                    items(subtitleTracks) { track ->
                        OptionButton(
                            label = track.title ?: track.language ?: "Subtitle ${track.id}",
                            active = selectedSubtitleId == track.id,
                            onInteract = onInteract,
                            onClick = { onSelectSubtitle(track.id) },
                        )
                    }
                }
                OverlayPanel.Speed -> {
                    items(
                        listOf(
                            SpeedOption("0.75x", 0.75),
                            SpeedOption("1.0x", 1.0),
                            SpeedOption("1.25x", 1.25),
                            SpeedOption("1.5x", 1.5),
                            SpeedOption("2.0x", 2.0),
                        ),
                    ) { speed ->
                        OptionButton(
                            label = speed.label,
                            active = currentSpeed == speed.value,
                            onInteract = onInteract,
                            onClick = { onSelectSpeed(speed.value) },
                        )
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = onClose,
                    shape = ButtonDefaults.shape(AppPillShape),
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun OptionButton(
    label: String,
    active: Boolean,
    onInteract: () -> Unit,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        shape = ButtonDefaults.shape(AppPillShape),
        modifier = Modifier.onFocusChanged { if (it.isFocused) onInteract() },
        colors = ButtonDefaults.colors(
            containerColor = if (active) Color(0x22F4EDE2) else Color.Transparent,
            contentColor = androidx.tv.material3.MaterialTheme.colorScheme.onBackground,
        ),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, if (active) Color(0x88F0BA66) else Color(0x22FFFFFF)),
                shape = AppPillShape,
            ),
        ),
    ) {
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
