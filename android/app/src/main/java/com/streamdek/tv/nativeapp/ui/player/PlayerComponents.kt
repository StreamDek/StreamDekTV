package com.streamdek.tv.nativeapp.ui.player

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ClosedCaptionOff
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.streamdek.tv.R
import com.streamdek.tv.mpv.MpvTrackInfo
import com.streamdek.tv.nativeapp.data.AddonStream
import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.ExternalSubtitleOrigin
import com.streamdek.tv.nativeapp.data.ExternalSubtitleTrack
import com.streamdek.tv.nativeapp.data.Languages
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.NextEpisodeAvailability
import com.streamdek.tv.nativeapp.data.PlaybackStats
import com.streamdek.tv.nativeapp.data.ProfilePluginState
import com.streamdek.tv.nativeapp.data.ResolvedPlaybackCandidate
import com.streamdek.tv.nativeapp.data.formatBitrate
import com.streamdek.tv.nativeapp.data.formatResolution
import com.streamdek.tv.nativeapp.data.formatTransferRate
import com.streamdek.tv.nativeapp.data.preferredSubtitleLanguageAllowed
import com.streamdek.tv.nativeapp.data.prettyCodecName
import com.streamdek.tv.nativeapp.data.streamOriginLabel
import com.streamdek.tv.nativeapp.data.streamProviderLabel
import com.streamdek.tv.nativeapp.data.streamTransport
import com.streamdek.tv.nativeapp.data.subtitleOriginVisible
import com.streamdek.tv.nativeapp.debrid.readyServiceLabel
import com.streamdek.tv.nativeapp.data.AppLanguage
import com.streamdek.tv.nativeapp.ui.AppFormats
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.LocalAppLanguage
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.MotionDuration
import com.streamdek.tv.nativeapp.ui.TvMotion
import com.streamdek.tv.nativeapp.ui.detail.streamQualityLabel
import com.streamdek.tv.nativeapp.ui.detail.streamSizeLabel
import com.streamdek.tv.nativeapp.ui.detail.streamTextFingerprint
import com.streamdek.tv.nativeapp.ui.formatPlaybackClock
import com.streamdek.tv.nativeapp.ui.tvCardLongPress
import java.util.Locale
import kotlinx.coroutines.launch

internal enum class OverlayPanel {
    Streams,
    Engine,
    Audio,
    Subtitles,
    Speed,
    Info,
    /** A live channel's own captions: off, or one of the caption tracks the stream was seen to carry. */
    Captions,
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

/** Subtitle timing in seconds, either way. Both engines honour it; see [SyncAdjustableRenderersFactory]. */
internal val SubtitleDelayRange = -SUBTITLE_DELAY_LIMIT_SECONDS..SUBTITLE_DELAY_LIMIT_SECONDS

/** Audio timing in seconds, either way. */
internal val AudioDelayRange = -AUDIO_DELAY_LIMIT_SECONDS..AUDIO_DELAY_LIMIT_SECONDS

/**
 * How far one press of left or right moves a delay, by how long the key has been held.
 *
 * A remote has no slider, and two minutes in tenths is twelve hundred presses. So a tap is the
 * finest step, holding moves to whole seconds, and holding on moves in tens - the same key, faster
 * the longer it is held, the way a remote's seek already behaves.
 */
internal fun subtitleDelayStep(repeatCount: Int): Double = when {
    repeatCount < 8 -> 0.1
    repeatCount < 24 -> 1.0
    else -> 10.0
}

internal fun audioDelayStep(repeatCount: Int): Double = if (repeatCount < 8) 0.05 else 0.5

/** "+1.5" or "−0.25": always signed, since which way is which is the point of the number. */
internal fun signedDelay(language: AppLanguage, seconds: Double, decimals: Int): String {
    val magnitude = AppFormats.number(language, kotlin.math.abs(seconds), decimals)
    return when {
        seconds > 0 -> "+$magnitude"
        seconds < 0 -> "−$magnitude"
        else -> magnitude
    }
}

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

/**
 * Subtitle-source views plus the appearance controls.
 *
 * The constant name is what gets stored and compared; only [labelRes] is read by anyone.
 */
internal enum class SubtitlePanelTab(@StringRes val labelRes: Int) {
    All(R.string.browse_all_sources),
    BuiltIn(R.string.settings_opt_built_in),
    Addons(R.string.settings_opt_add_ons),
    Adjust(R.string.subtitle_tab_adjust),
}

private val PlayerPanelShape = RoundedCornerShape(28.dp)

/**
 * How much of the header the source line may occupy, and how far it must stay from the end time.
 *
 * The line under the title carries whatever the add-on calls itself, and some of them are a
 * paragraph: provider, release group, codec, size. Left to fill the row it ran the whole width of
 * the screen and stopped a hair from "Ends at", which read as a collision even when it was not
 * one. It is given a share of the header instead, with a ceiling for wide panels and a floor under
 * the gap, and anything longer scrolls inside that space rather than growing into the gap.
 */
private const val PlayerSourceInfoWidthFraction = 0.55f
private val PlayerSourceInfoMaxWidth = 440.dp
private val PlayerHeaderGap = 36.dp

/** How the source line scrolls when it does not fit: slowly, and not right away. */
private const val PlayerSourceMarqueeInitialDelayMs = 700
private const val PlayerSourceMarqueeRepeatDelayMs = 1_400
private val PlayerSourceMarqueeVelocity = 26.dp
private val PlayerSourceMarqueeSpacing = 56.dp

/**
 * The player's own palette. One place, so the bar, the timeline and the panels agree on what
 * "focused", "on" and "quiet" look like instead of each naming its own shade.
 */
internal object PlayerTokens {
    /** The StreamDek gold: the focus ring, the scrub head and anything the viewer is acting on. */
    val Accent = Color(0xFFF0BA66)
    val AccentSoft = Color(0x33F0BA66)
    /** A control that is on - a panel open, a toggle set - but not focused. */
    val Active = Color(0xFFF0BA66)
    val Ink = Color(0xFF111318)
    val TextPrimary = Color.White
    val TextSecondary = Color(0xB8FFFFFF)
    val TextTertiary = Color(0x7AFFFFFF)
    val Hairline = Color(0x1FFFFFFF)
    val TrackRest = Color(0x33FFFFFF)
    val LiveRed = Color(0xFFEF4444)
    val VodBlue = Color(0xFF60A5FA)
}

/**
 * Whether this television can afford the player's layered effects.
 *
 * Checked once per process from what Android says about the box: a low-RAM device, or one that
 * gives an app a small heap, is the Fire TV Stick class of hardware where stacked translucent
 * surfaces and several simultaneous animations cost frames. Those get opaque surfaces and a single
 * fade in place of the staggered reveal; nothing is taken away, it only costs less to draw.
 */
internal object PlayerEffects {
    @Volatile private var cached: Boolean? = null

    fun reduced(context: android.content.Context): Boolean = cached ?: run {
        val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val reduced = manager == null || manager.isLowRamDevice || manager.memoryClass <= 128
        cached = reduced
        reduced
    }
}

@Composable
internal fun rememberPlayerEffectsReduced(): Boolean {
    val context = LocalContext.current
    return remember(context) { PlayerEffects.reduced(context) }
}

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
    val enterSpec = TvMotion.enterSpec<Float>(TvMotion.Expand)
    val enterOffset = TvMotion.enterSpec<androidx.compose.ui.unit.IntOffset>(TvMotion.Expand)
    val exitSpec = TvMotion.exitSpec<Float>()
    val exitOffset = TvMotion.exitSpec<androidx.compose.ui.unit.IntOffset>()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reducedMotion) fadeIn(TvMotion.enterSpec(TvMotion.Quick)) else fadeIn(enterSpec) + slideInVertically(enterOffset, initialOffsetY = { it / 10 }),
        exit = if (reducedMotion) fadeOut(TvMotion.exitSpec(TvMotion.Quick)) else fadeOut(exitSpec) + slideOutVertically(exitOffset, targetOffsetY = { it / 12 }),
    ) {
        content()
    }
}

/**
 * How a side panel comes and goes.
 *
 * It grows out of the bottom-right, where the control that opened it sits, rather than sliding in
 * from nowhere: a short travel from the right, a small scale from that corner and a fade, all on the
 * same decelerating curve. Leaving is the same movement reversed and quicker. With motion off it is
 * a plain crossfade, which is the accessible replacement for movement rather than no transition.
 */
@Composable
internal fun PlayerPanelVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reducedMotion = LocalTvExperienceSettings.current.reducedMotion
    val origin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (reducedMotion) {
            fadeIn(TvMotion.enterSpec(TvMotion.Quick))
        } else {
            fadeIn(TvMotion.enterSpec(TvMotion.Standard)) +
                slideInHorizontally(TvMotion.enterSpec(TvMotion.Expand), initialOffsetX = { it / 12 }) +
                scaleIn(TvMotion.enterSpec(TvMotion.Expand), initialScale = 0.96f, transformOrigin = origin)
        },
        exit = if (reducedMotion) {
            fadeOut(TvMotion.exitSpec(TvMotion.Quick))
        } else {
            fadeOut(TvMotion.exitSpec(TvMotion.Quick)) +
                slideOutHorizontally(TvMotion.exitSpec(TvMotion.Standard), targetOffsetX = { it / 16 }) +
                scaleOut(TvMotion.exitSpec(TvMotion.Standard), targetScale = 0.97f, transformOrigin = origin)
        },
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
    // Layered translucency is the expensive part of a glass panel on a stick; an opaque surface with
    // the same shape and hairline reads the same from the sofa.
    val layered = LocalTvExperienceSettings.current.backgroundBlur && !rememberPlayerEffectsReduced()
    Box(
        modifier = modifier
            .clip(PlayerPanelShape)
            .background(
                Brush.verticalGradient(
                    colors = if (layered) listOf(Color(0xF0171A23), Color(0xF40F1117)) else listOf(Color(0xFF161922), Color(0xFF0F1117)),
                ),
            )
            .border(1.dp, PlayerTokens.Hairline, PlayerPanelShape)
            .padding(contentPadding),
    ) {
        content()
    }
}

/**
 * A 0..1 value that climbs once when the thing reading it arrives, [step] staggers behind the first.
 *
 * The bar's three tiers - title, timeline, controls - read it so they land one after another rather
 * than as a single slab. Each tier moves on its own graphics layer, so the reveal costs a few
 * property changes rather than any relayout. Motion off, or a television that cannot spare it,
 * starts at 1 and never animates.
 */
@Composable
private fun rememberStaggeredReveal(step: Int, enabled: Boolean): State<Float> {
    val motion = LocalTvExperienceSettings.current.motion
    val animate = enabled && !motion.motionless
    val progress = remember { androidx.compose.animation.core.Animatable(if (animate) 0f else 1f) }
    val delayMs = motion.stagger(MotionDuration.stagger) * step
    val duration = motion.scaled(MotionDuration.long)
    LaunchedEffect(Unit) {
        if (!animate) return@LaunchedEffect
        kotlinx.coroutines.delay(delayMs.toLong())
        progress.animateTo(1f, tween(duration, easing = TvMotion.EnterEasing))
    }
    return progress.asState()
}

private fun Modifier.revealLayer(progress: State<Float>): Modifier = graphicsLayer {
    val value = progress.value
    alpha = value
    translationY = (1f - value) * 22.dp.toPx()
}

/** One entry in the controls row. The order of the list is the order on screen and on the remote. */
internal data class PlayerControlSpec(
    val key: String,
    val icon: ImageVector,
    val label: String,
    val requester: FocusRequester,
    val onClick: () -> Unit,
    val active: Boolean = false,
    val primary: Boolean = false,
)

/** "S1 · E4", the way the header's small line puts it. */
private fun episodeEyebrow(episode: EpisodeContext): String = "S${episode.seasonNumber} · E${episode.episodeNumber}"

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
    /** Whether the channel is known to carry captions, and whether they are showing. Live only. */
    captionsAvailable: Boolean = false,
    captionsOn: Boolean = false,
    /** Whether the Live / VOD indicator is drawn. Visual only; see PlaybackPreferences.liveBadgeEnabled. */
    showLiveBadge: Boolean = true,
    onToggleLiveBadge: () -> Unit = {},
    /** The badge toggle's place in the row. Null leaves the toggle out. Live only. */
    liveBadgeRequester: FocusRequester? = null,
    /** Where a scrub in flight will land, for the bubble over the timeline. Null when not scrubbing. */
    scrubTargetSec: Double? = null,
    /** The current playback speed, so "ends at" is honest at 1.5x. */
    playbackSpeed: Double = 1.0,
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

    val playLabel = stringResource(if (paused) R.string.action_play else R.string.action_pause)
    val subtitlesLabel = stringResource(R.string.player_subtitles)
    val captionsLabel = stringResource(if (captionsOn) R.string.player_captions_on else R.string.player_captions_off)
    val audioLabel = stringResource(R.string.player_audio)
    val sourcesLabel = stringResource(R.string.player_sources)
    val engineLabel = stringResource(R.string.player_engine)
    val progressLabel = stringResource(R.string.player_progress)
    val favouriteLabel = stringResource(if (isFavourite) R.string.action_favourited else R.string.action_favourite)
    val badgeLabel = stringResource(if (showLiveBadge) R.string.player_live_badge_on else R.string.player_live_badge_off)
    val nextLabel = stringResource(R.string.player_next)
    val watchedLabel = stringResource(R.string.player_watched)
    val speedLabel = stringResource(R.string.player_playback_speed)
    val infoLabel = stringResource(R.string.player_stream_info)

    // The row, in the order the remote walks it. Neighbours are worked out from this list rather than
    // named by hand on every button, so a control that comes or goes - captions arriving mid-broadcast,
    // Next on the last episode - can never leave a neighbour pointing at something that is not there.
    val controls = buildList {
        add(PlayerControlSpec("play", if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, playLabel, playRequester, onPlayPause, primary = true))
        if (isLive) {
            // A channel's own captions sit where a film's subtitles do: first after Play. Only once
            // the stream has actually been seen to carry some; see ExoPlaybackView.setCaptionProbe.
            if (captionsAvailable) {
                add(
                    PlayerControlSpec(
                        "captions",
                        if (captionsOn) Icons.Rounded.ClosedCaption else Icons.Rounded.ClosedCaptionOff,
                        captionsLabel,
                        subtitlesRequester,
                        { onOpenPanel(OverlayPanel.Captions) },
                        active = captionsOn || selectedPanel == OverlayPanel.Captions,
                    ),
                )
            }
            add(PlayerControlSpec("timeline", Icons.Rounded.Timeline, progressLabel, liveProgressRequester, onToggleLiveProgress, active = showLiveProgress))
        } else {
            add(PlayerControlSpec("subtitles", Icons.Rounded.ClosedCaption, subtitlesLabel, subtitlesRequester, { onOpenPanel(OverlayPanel.Subtitles) }, active = selectedPanel == OverlayPanel.Subtitles))
            add(PlayerControlSpec("audio", Icons.AutoMirrored.Rounded.VolumeUp, audioLabel, audioRequester, { onOpenPanel(OverlayPanel.Audio) }, active = selectedPanel == OverlayPanel.Audio))
        }
        add(PlayerControlSpec("sources", Icons.Rounded.Cloud, sourcesLabel, sourcesRequester, { onOpenPanel(OverlayPanel.Streams) }, active = selectedPanel == OverlayPanel.Streams))
        add(PlayerControlSpec("engine", Icons.Rounded.Tune, engineLabel, engineRequester, { onOpenPanel(OverlayPanel.Engine) }, active = selectedPanel == OverlayPanel.Engine))
        if (isLive) {
            // Favouriting was only possible by holding OK on a channel in the grid, which is no use
            // once you are watching it — this is where you decide you want it.
            add(PlayerControlSpec("favourite", if (isFavourite) Icons.Rounded.Star else Icons.Rounded.StarBorder, favouriteLabel, favouriteRequester, onToggleFavourite, active = isFavourite))
            liveBadgeRequester?.let { requester ->
                add(PlayerControlSpec("badge", Icons.Rounded.LiveTv, badgeLabel, requester, onToggleLiveBadge, active = showLiveBadge))
            }
        } else {
            if (hasNext) add(PlayerControlSpec("next", Icons.Rounded.SkipNext, nextLabel, nextRequester, onNext))
            add(PlayerControlSpec("watched", Icons.Rounded.CheckCircle, watchedLabel, watchedRequester, onMarkWatched))
            add(PlayerControlSpec("speed", Icons.Rounded.Speed, speedLabel, speedRequester, { onOpenPanel(OverlayPanel.Speed) }, active = selectedPanel == OverlayPanel.Speed))
        }
        add(PlayerControlSpec("info", Icons.Rounded.Info, infoLabel, infoRequester, { onOpenPanel(OverlayPanel.Info) }, active = selectedPanel == OverlayPanel.Info))
    }
    val controlRequesters = controls.map { it.requester }

    LaunchedEffect(focusRegion, hasSeekableTimeline, controlsFocusToken) {
        // Visibility and focus ownership change together. This is the only automatic handoff
        // between the two rows; horizontal movement never participates in focus search.
        //
        // It is also the only place the highlight is placed inside either row. The bar is what
        // knows when its own buttons are attached — which is not the same frame the host asked, and
        // reliably later still when the bar is arriving from behind a drawer that just closed.
        val target = when {
            focusRegion == PlayerControlsFocusRegion.Seek && hasSeekableTimeline -> progressRequester
            // A named target that is no longer in the row - captions that went away with the
            // channel - falls back to Play rather than to nothing at all.
            focusRegion == PlayerControlsFocusRegion.Controls ->
                controlsEntryRequester?.takeIf { it in controlRequesters } ?: playRequester
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
    val effectsReduced = rememberPlayerEffectsReduced()
    val headerReveal = rememberStaggeredReveal(step = 0, enabled = !effectsReduced)
    val timelineReveal = rememberStaggeredReveal(step = 1, enabled = !effectsReduced)
    val controlsReveal = rememberStaggeredReveal(step = 2, enabled = !effectsReduced)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color(0x99000000), Color(0xE6000000), Color(0xF5000000)),
                ),
            )
            .padding(start = 48.dp, end = 48.dp, top = 88.dp, bottom = 30.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PlayerHeader(
                detail = detail,
                requestTitle = requestTitle,
                currentEpisode = currentEpisode,
                currentLabel = currentLabel,
                error = error,
                isLive = isLive,
                isVod = isVod,
                showLiveBadge = showLiveBadge,
                positionSec = positionSec,
                durationSec = durationSec,
                playbackSpeed = playbackSpeed,
                paused = paused,
                modifier = Modifier.revealLayer(headerReveal),
            )

            Box(modifier = Modifier.revealLayer(timelineReveal)) {
                if (hasSeekableTimeline) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            scrubTargetSec = scrubTargetSec,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        AnimatedVisibility(
                            visible = timelineFocused.value,
                            enter = TvMotion.fadeInSpec(TvMotion.Quick),
                            exit = TvMotion.fadeOutSpec(TvMotion.Instant),
                        ) {
                            Text(
                                text = stringResource(R.string.player_scrub_hint),
                                style = androidx.tv.material3.MaterialTheme.typography.labelSmall,
                                color = PlayerTokens.TextTertiary,
                                maxLines = 1,
                            )
                        }
                    }
                } else if (isLive && showLiveProgress) {
                    // Asked for a timeline on a channel that has no window to seek in: how long it has
                    // been playing, and a rule standing in for the bar.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = formatPlaybackClock(positionSec),
                            style = androidx.tv.material3.MaterialTheme.typography.labelLarge,
                            color = PlayerTokens.TextSecondary,
                        )
                        Box(
                            modifier = Modifier.weight(1f).height(4.dp).clip(CircleShape)
                                .background(PlayerTokens.TrackRest),
                        )
                        if (showLiveBadge) LiveBadgeChip(isVod = isVod)
                    }
                }
            }

            PlayerControlsRow(
                controls = controls,
                upRequester = timelineUpRequester,
                playRequester = playRequester,
                timelineFocused = timelineFocused.value,
                onFocused = onControlsFocused,
                modifier = Modifier.revealLayer(controlsReveal),
            )
        }
    }
}

/**
 * Title, what is playing and the small facts around it.
 *
 * An eyebrow line over the title - the episode for a series, the Live or VOD mark for a channel -
 * then the title (the logo when there is one), then the source in quieter type. On the right, when
 * there is a runtime to go on, the time the film will end: the question people actually have when
 * they pull the controls up at night.
 */
@Composable
private fun PlayerHeader(
    detail: MediaDetail?,
    requestTitle: String?,
    currentEpisode: EpisodeContext?,
    currentLabel: String,
    error: String?,
    isLive: Boolean,
    isVod: Boolean,
    showLiveBadge: Boolean,
    positionSec: Double,
    durationSec: Double,
    playbackSpeed: Double,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(PlayerHeaderGap),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val eyebrow = currentEpisode?.let(::episodeEyebrow)
            // Only drawn when it has something in it. With the badge switched off a channel has no
            // eyebrow at all, and the title moves up rather than leaving the gap where it was.
            if (eyebrow != null || (isLive && showLiveBadge)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (isLive && showLiveBadge) LiveBadgeChip(isVod = isVod)
                    eyebrow?.let {
                        Text(
                            text = it,
                            style = androidx.tv.material3.MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.4.sp,
                            ),
                            color = PlayerTokens.Accent,
                            maxLines = 1,
                        )
                    }
                }
            }
            val title = detail?.title ?: requestTitle
            if (!detail?.titleLogo.isNullOrBlank()) {
                AsyncImage(
                    model = detail!!.titleLogo,
                    contentDescription = title,
                    modifier = Modifier.height(44.dp).widthIn(max = 420.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                )
            } else if (!title.isNullOrBlank()) {
                Text(
                    text = title,
                    style = androidx.tv.material3.MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                    color = PlayerTokens.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val subline = if (error != null) error else listOfNotNull(
                currentEpisode?.title?.takeIf { it.isNotBlank() },
                currentLabel.takeIf { it.isNotBlank() },
            ).distinct().joinToString("  ·  ")
            if (subline.isNotBlank()) {
                // A television that has asked for less motion, or one that cannot afford the
                // redraw, gets the line still and ellipsised. It is bounded either way: the point
                // of the width is the layout, and the scrolling is only how the rest is reached.
                val stillLine = LocalTvExperienceSettings.current.reducedMotion ||
                    rememberPlayerEffectsReduced()
                val lineWidth = Modifier
                    .fillMaxWidth(PlayerSourceInfoWidthFraction)
                    .widthIn(max = PlayerSourceInfoMaxWidth)
                if (stillLine) {
                    Text(
                        text = subline,
                        modifier = lineWidth.wrapContentWidth(Alignment.Start),
                        style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                        color = if (error != null) Color(0xFFFFB4AB) else PlayerTokens.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = subline,
                        // Restarted whenever the line changes, so a source switch reads from its
                        // own beginning rather than continuing the previous one's scroll.
                        modifier = lineWidth.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = PlayerSourceMarqueeInitialDelayMs,
                            repeatDelayMillis = PlayerSourceMarqueeRepeatDelayMs,
                            spacing = MarqueeSpacing(PlayerSourceMarqueeSpacing),
                            velocity = PlayerSourceMarqueeVelocity,
                        ),
                        style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                        color = if (error != null) Color(0xFFFFB4AB) else PlayerTokens.TextTertiary,
                        maxLines = 1,
                        // Both required by the marquee: it scrolls what does not fit, which means
                        // the line has to be allowed to measure past its bounds rather than being
                        // wrapped or cut short with an ellipsis it would then scroll.
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
        if (!isLive && durationSec > 0.0 && positionSec >= 0.0) {
            val context = LocalContext.current
            // Recomputed as the position moves, so pausing pushes the end time back as it should.
            val remainingMs = ((durationSec - positionSec).coerceAtLeast(0.0) / playbackSpeed.coerceAtLeast(0.25) * 1000.0).toLong()
            val endsAt = remember(remainingMs / 60_000L, paused) {
                android.text.format.DateFormat.getTimeFormat(context)
                    .format(java.util.Date(System.currentTimeMillis() + remainingMs))
            }
            Text(
                text = stringResource(R.string.player_ends_at, endsAt),
                // Never squeezed and never wrapped: it is short, it is the same length all
                // evening, and the source line beside it has already been told where to stop.
                modifier = Modifier.wrapContentWidth(Alignment.End),
                style = androidx.tv.material3.MaterialTheme.typography.labelLarge,
                color = PlayerTokens.TextTertiary,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** The Live / VOD mark, as it appears in the bar's header and beside a live timeline. */
@Composable
internal fun LiveBadgeChip(isVod: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (isVod) Color(0x3360A5FA) else Color(0x33EF4444))
            .border(1.dp, if (isVod) Color(0x6660A5FA) else Color(0x66EF4444), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (isVod) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = PlayerTokens.VodBlue,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(PlayerTokens.LiveRed),
            )
        }
        Text(
            text = if (isVod) "VOD" else "LIVE",
            style = androidx.tv.material3.MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Black,
                letterSpacing = 1.2.sp,
            ),
            color = Color.White,
        )
    }
}

/**
 * PlayerControlsFocusGroup: the second of the bottom bar's two focus islands.
 *
 * Horizontal traversal is internal to this row and cannot leave it — the first control cancels a
 * Left search and the last cancels a Right one, so a held button stops at the end of the row
 * instead of falling out of the bar. Up is the single sanctioned way back to the seek row above;
 * there is nothing below, so Down is cancelled outright rather than left to spatial search.
 *
 * The focused control's name rides above it on a single label that glides from button to button,
 * so the row stays a quiet strip of icons and still says what each one does the moment it is
 * reached.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PlayerControlsRow(
    controls: List<PlayerControlSpec>,
    upRequester: FocusRequester?,
    playRequester: FocusRequester,
    timelineFocused: Boolean,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val centers = remember { mutableStateMapOf<String, Float>() }
    var labelWidth by remember { mutableIntStateOf(0) }
    val focusedControl = controls.firstOrNull { it.key == focusedKey }
    val labelVisible = focusedControl != null && !timelineFocused
    val targetX = focusedControl?.let { (centers[it.key] ?: 0f) - labelWidth / 2f } ?: 0f
    val labelX by animateFloatAsState(
        targetValue = targetX,
        animationSpec = TvMotion.standardSpec(TvMotion.Standard),
        label = "control-label-x",
    )
    val labelAlpha by animateFloatAsState(
        targetValue = if (labelVisible) 1f else 0f,
        animationSpec = if (labelVisible) TvMotion.enterSpec(TvMotion.Quick) else TvMotion.exitSpec(TvMotion.Instant),
        label = "control-label-alpha",
    )
    // Held so the label keeps saying the last control while it fades out, rather than going blank.
    var shownLabel by remember { mutableStateOf("") }
    focusedControl?.label?.let { shownLabel = it }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.fillMaxWidth().height(22.dp)) {
            Text(
                text = shownLabel,
                style = androidx.tv.material3.MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = PlayerTokens.TextPrimary,
                maxLines = 1,
                modifier = Modifier
                    .onSizeChanged { labelWidth = it.width }
                    .graphicsLayer {
                        translationX = labelX.coerceAtLeast(0f)
                        alpha = labelAlpha
                    },
            )
        }
        Row(
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
                                upRequester ?: FocusRequester.Cancel
                            else -> FocusRequester.Default
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            controls.forEachIndexed { index, control ->
                PlayerControlButton(
                    control = control,
                    upRequester = upRequester,
                    leftRequester = controls.getOrNull(index - 1)?.requester,
                    rightRequester = controls.getOrNull(index + 1)?.requester,
                    onFocusChanged = { focused ->
                        if (focused) {
                            focusedKey = control.key
                            onFocused()
                        } else if (focusedKey == control.key) {
                            focusedKey = null
                        }
                    },
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        centers[control.key] = coordinates.positionInParent().x + coordinates.size.width / 2f
                    },
                )
                // Play stands apart from the options that follow it.
                if (control.primary) Spacer(Modifier.width(14.dp))
            }
        }
    }
}

/**
 * One control in [PlayerControlsRow].
 *
 * Every direction is named. A null neighbour means "there is nothing that way", which is expressed
 * as [FocusRequester.Cancel] rather than left to spatial focus search: the search is what used to
 * carry the highlight out of the bar and onto whatever happened to be laid out nearby.
 *
 * Bare icons, no disc behind them. Focus turns the icon gold and lifts it well past its neighbours,
 * which reads across the room without a container. An option that is on is full white with a small
 * gold pip beneath it, so "on" and "focused" never have to share one signal.
 */
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PlayerControlButton(
    control: PlayerControlSpec,
    upRequester: FocusRequester?,
    leftRequester: FocusRequester?,
    rightRequester: FocusRequester?,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val size = if (control.primary) 58.dp else 48.dp
    val iconSize = if (control.primary) 38.dp else 28.dp
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Button(
            onClick = control.onClick,
            shape = ButtonDefaults.shape(CircleShape),
            colors = ButtonDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                pressedContainerColor = Color.Transparent,
                contentColor = Color.White,
                focusedContentColor = PlayerTokens.Accent,
                pressedContentColor = PlayerTokens.Accent,
            ),
            border = ButtonDefaults.border(border = Border.None, focusedBorder = Border.None),
            scale = ButtonDefaults.scale(focusedScale = 1.3f, pressedScale = 1.15f),
            modifier = Modifier
                .size(size)
                .focusRequester(control.requester)
                .focusProperties {
                    up = upRequester ?: FocusRequester.Cancel
                    left = leftRequester ?: FocusRequester.Cancel
                    right = rightRequester ?: FocusRequester.Cancel
                    down = FocusRequester.Cancel
                }
                .onFocusChanged {
                    focused = it.isFocused
                    onFocusChanged(it.isFocused)
                },
            contentPadding = PaddingValues(0.dp),
        ) {
            val iconTint = when {
                focused -> PlayerTokens.Accent
                control.primary || control.active -> Color.White
                else -> Color(0xB3FFFFFF)
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = control.icon,
                    contentDescription = control.label,
                    modifier = Modifier.size(iconSize),
                    tint = iconTint,
                )
            }
        }
        val pipAlpha by animateFloatAsState(
            targetValue = if (control.active && !control.primary) 1f else 0f,
            animationSpec = TvMotion.standardSpec(TvMotion.Quick),
            label = "control-pip",
        )
        Box(
            modifier = Modifier
                .size(4.dp)
                .graphicsLayer { alpha = pipAlpha }
                .clip(CircleShape)
                .background(PlayerTokens.Active),
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
    scrubTargetSec: Double? = null,
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
            scrubTargetSec = scrubTargetSec,
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
    /** Where a scrub in flight will land. Shown in a bubble over the head while it is non-null. */
    scrubTargetSec: Double? = null,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val progress = if (durationSec > 0.0) (positionSec / durationSec).coerceIn(0.0, 1.0).toFloat() else 0f
    // Read from the state object inside the key handler rather than from a captured Boolean, so
    // the first press after focus arrives sees this frame's value and not the previous one.
    val durationState = remember { mutableStateOf(durationSec) }
    durationState.value = durationSec
    // The bar thickens and the head grows when the row is reached, so "you are on the timeline" is
    // said by the timeline itself. Both ride graphics layers: nothing around the bar moves.
    val emphasis by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = TvMotion.standardSpec(TvMotion.Quick),
        label = "timeline-emphasis",
    )
    val scrubbing = focused && scrubTargetSec != null
    val bubbleAlpha by animateFloatAsState(
        targetValue = if (scrubbing) 1f else 0f,
        animationSpec = if (scrubbing) TvMotion.enterSpec(TvMotion.Instant) else TvMotion.exitSpec(TvMotion.Standard),
        label = "timeline-bubble",
    )
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
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeLabel(formatPlaybackClock(positionSec), emphasised = focused)
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(32.dp),
        ) {
            val barWidth = maxWidth
            val headX = barWidth * progress
            val trackShape = RoundedCornerShape(999.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .height(8.dp)
                    .graphicsLayer { scaleY = 0.5f + 0.5f * emphasis }
                    .clip(trackShape)
                    .background(PlayerTokens.TrackRest),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceAtLeast(0f))
                        .fillMaxHeight()
                        .clip(trackShape)
                        .background(
                            if (focused) {
                                Brush.horizontalGradient(listOf(Color(0xFFE9A94F), PlayerTokens.Accent))
                            } else {
                                Brush.horizontalGradient(listOf(Color(0xE6FFFFFF), Color.White))
                            },
                        ),
                )
            }
            // The head: a small white dot at rest that swells into the gold scrub handle.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = headX - 9.dp)
                    .size(18.dp)
                    .graphicsLayer {
                        val scale = 0.55f + 0.45f * emphasis
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape)
                    .background(if (focused) PlayerTokens.Accent else Color.White),
            )
            if (scrubTargetSec != null || bubbleAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = (headX - 38.dp).coerceIn(0.dp, (barWidth - 76.dp).coerceAtLeast(0.dp)), y = (-34).dp)
                        .width(76.dp)
                        .graphicsLayer {
                            alpha = bubbleAlpha
                            translationY = (1f - bubbleAlpha) * 6.dp.toPx()
                        }
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xF2171A23))
                        .border(1.dp, PlayerTokens.AccentSoft, RoundedCornerShape(10.dp))
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = formatPlaybackClock(scrubTargetSec ?: positionSec),
                        style = androidx.tv.material3.MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
                        color = PlayerTokens.Accent,
                        maxLines = 1,
                    )
                }
            }
        }

        TimeLabel(formatPlaybackClock(durationSec), emphasised = false)
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
    /** Whether the active subtitle renderer can apply a timing offset. */
    subtitleDelaySupported: Boolean = true,
    /** Sound against picture, positive later; adjusted from the Audio panel. */
    audioDelay: Double = 0.0,
    onAudioDelay: (Double) -> Unit = {},
    /** False when the engine cannot move the sound for what is playing - see [MpvPlayerController.audioDelaySupported]. */
    audioDelaySupported: Boolean = false,
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
    favoriteSourceKeys: Set<String> = emptySet(),
    onToggleSourceFavourite: (String) -> Unit = {},
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

    // Resolved once, up here, because each of these is read from inside a lazy-list item builder or
    // a `buildList` block, and a resource lookup is a composable call that does not belong in either.
    val appLanguage = LocalAppLanguage.current
    val selectedBadge = stringResource(R.string.a11y_selected)
    val embeddedTag = stringResource(R.string.player_subtitle_embedded_tag)
    val builtInSourceLabel = stringResource(R.string.player_subtitle_source_builtin)
    val addonOriginLabel = stringResource(R.string.source_origin_addon)
    val pluginOriginLabel = stringResource(R.string.source_origin_plugin)
    val sourceHeading = stringResource(R.string.player_info_source)
    val playbackHeading = stringResource(R.string.player_info_playback)
    // The info panel names each row and formats several of its values, and it builds both lists in
    // the lazy list's content block, which is not a composition. These come from the resources the
    // composition is already using, which ProvideAppLocale has wrapped in the chosen language.
    val panelResources = LocalContext.current.resources

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
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(PlayerTokens.AccentSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = when (panel) {
                                OverlayPanel.Streams -> Icons.Rounded.Cloud
                                OverlayPanel.Engine -> Icons.Rounded.Tune
                                OverlayPanel.Audio -> Icons.AutoMirrored.Rounded.VolumeUp
                                OverlayPanel.Subtitles, OverlayPanel.Captions -> Icons.Rounded.ClosedCaption
                                OverlayPanel.Speed -> Icons.Rounded.Speed
                                OverlayPanel.Info -> Icons.Rounded.Info
                            },
                            contentDescription = null,
                            tint = PlayerTokens.Accent,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(
                                when (panel) {
                                    OverlayPanel.Captions -> R.string.player_captions
                                    OverlayPanel.Streams -> R.string.player_sources
                                    OverlayPanel.Engine -> R.string.player_engine_panel_title
                                    OverlayPanel.Audio -> R.string.player_audio
                                    OverlayPanel.Subtitles -> R.string.player_subtitles
                                    OverlayPanel.Speed -> R.string.player_playback_speed
                                    OverlayPanel.Info -> R.string.player_stream_info
                                },
                            ),
                            style = androidx.tv.material3.MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                            color = Color.White,
                        )
                        Text(
                            text = stringResource(
                                when (panel) {
                                    OverlayPanel.Streams -> R.string.player_panel_streams_description
                                    OverlayPanel.Engine -> R.string.player_panel_engine_description
                                    OverlayPanel.Audio -> R.string.player_panel_audio_description
                                    OverlayPanel.Subtitles -> R.string.player_panel_subtitles_description
                                    OverlayPanel.Speed -> R.string.player_panel_speed_description
                                    OverlayPanel.Info -> R.string.player_panel_info_description
                                    OverlayPanel.Captions -> R.string.player_panel_captions_description
                                },
                            ),
                            style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.58f),
                        )
                    }
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
                                Text(stringResource(if (streamsReloading) R.string.player_streams_reloading else R.string.player_reload))
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
                            Text(stringResource(R.string.action_close))
                        }
                    }
                }
            }

            if (panel == OverlayPanel.Subtitles) {
                item {
                    PanelTabRow(
                        tabs = availableSubtitleTabs,
                        selected = subtitleTab,
                        labelOf = { stringResource(it.labelRes) },
                        onInteract = onInteract,
                        onSelect = { subtitleTab = it },
                    )
                }
            }

            when (panel) {
                OverlayPanel.Streams -> {
                    val originalStreams = candidate?.streams.orEmpty()
                    // What is playing always leads, so the panel opens on it; favourites follow.
                    val playingStream = candidate?.stream
                    val streams = originalStreams.sortedWith(
                        compareByDescending<AddonStream> { it == playingStream }
                            .thenByDescending { stableSourceFavouriteKey(it) in favoriteSourceKeys },
                    )
                    if (streams.isEmpty()) {
                        item {
                            PanelNote(stringResource(if (streamsReloading) R.string.player_streams_searching else R.string.player_streams_none_yet))
                        }
                    }
                    itemsIndexed(streams) { index, stream ->
                        StreamOptionButton(
                            stream = stream,
                            origin = streamOriginLabel(stream, pluginState, addonOriginLabel, pluginOriginLabel),
                            fallbackLabel = stringResource(R.string.player_stream_fallback_label, index + 1),
                            playing = candidate?.stream == stream,
                            requestFocus = if (index == 0) firstItemRequester else null,
                            onInteract = onInteract,
                            favourite = stableSourceFavouriteKey(stream) in favoriteSourceKeys,
                            onToggleFavourite = { onToggleSourceFavourite(stableSourceFavouriteKey(stream)) },
                            onClick = { onSelectStream(originalStreams.indexOf(stream)) },
                        )
                    }
                }
                OverlayPanel.Engine -> {
                    item {
                        OptionButton(
                            label = "ExoPlayer",
                            subtitle = stringResource(R.string.player_engine_media3_description),
                            active = activeEngine == ActivePlaybackEngine.Media3,
                            activeBadge = selectedBadge.takeIf { activeEngine == ActivePlaybackEngine.Media3 },
                            requestFocus = firstItemRequester,
                            onInteract = onInteract,
                            onClick = { onSelectEngine(ActivePlaybackEngine.Media3) },
                        )
                    }
                    item {
                        OptionButton(
                            label = "mpv",
                            subtitle = stringResource(R.string.player_engine_mpv_description),
                            active = activeEngine == ActivePlaybackEngine.MPV,
                            activeBadge = selectedBadge.takeIf { activeEngine == ActivePlaybackEngine.MPV },
                            onInteract = onInteract,
                            onClick = { onSelectEngine(ActivePlaybackEngine.MPV) },
                        )
                    }
                    item {
                        PanelNote(stringResource(R.string.player_engine_switch_note))
                    }
                }
                OverlayPanel.Audio -> {
                    itemsIndexed(audioTracks) { index, track ->
                        val language = trackLanguageName(track.language)
                        OptionButton(
                            label = track.title ?: language ?: stringResource(R.string.player_audio_track_fallback, track.id),
                            subtitle = listOfNotNull(language, track.codec).joinToString(" • ").ifBlank { null },
                            active = selectedAudioId == track.id,
                            activeBadge = selectedBadge.takeIf { selectedAudioId == track.id },
                            requestFocus = if (index == 0) firstItemRequester else null,
                            onInteract = onInteract,
                            onClick = { onSelectAudio(track.id) },
                        )
                    }
                    // Timing sits under the tracks: choosing a language is why most people open this
                    // panel, and the delay is the thing to reach for only when that one is out.
                    if (audioDelaySupported) {
                        item {
                            val amount = AppFormats.number(appLanguage, kotlin.math.abs(audioDelay), 2)
                            PlayerStepperRow(
                                label = stringResource(R.string.player_audio_delay),
                                value = stringResource(R.string.player_subtitle_delay_seconds, signedDelay(appLanguage, audioDelay, 2)),
                                hint = when {
                                    audioDelay > 0 -> stringResource(R.string.player_audio_delay_later, amount)
                                    audioDelay < 0 -> stringResource(R.string.player_audio_delay_earlier, amount)
                                    else -> stringResource(R.string.player_delay_hold_hint)
                                },
                                requestFocus = if (audioTracks.isEmpty()) firstItemRequester else null,
                                onInteract = onInteract,
                                onDecrease = { onAudioDelay(steppedDelay(audioDelay, -audioDelayStep(0), AUDIO_DELAY_LIMIT_SECONDS)) },
                                onIncrease = { onAudioDelay(steppedDelay(audioDelay, audioDelayStep(0), AUDIO_DELAY_LIMIT_SECONDS)) },
                                onAdjust = { direction, repeat ->
                                    onAudioDelay(steppedDelay(audioDelay, direction * audioDelayStep(repeat), AUDIO_DELAY_LIMIT_SECONDS))
                                },
                            )
                        }
                        item {
                            OptionButton(
                                label = stringResource(R.string.player_audio_delay_reset),
                                subtitle = stringResource(R.string.player_audio_delay_scope),
                                active = false,
                                onInteract = onInteract,
                                onClick = { onAudioDelay(0.0) },
                            )
                        }
                    } else {
                        item { PanelNote(stringResource(R.string.player_audio_delay_unavailable_tunneled)) }
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
                            label = stringResource(R.string.player_subtitles_off),
                            subtitle = stringResource(R.string.player_subtitles_off_description),
                            active = selectedSubtitleId < 0 && selectedExternalSubtitleId == null,
                            activeBadge = selectedBadge.takeIf { selectedSubtitleId < 0 && selectedExternalSubtitleId == null },
                            requestFocus = firstItemRequester,
                            onInteract = onInteract,
                            onClick = onDisableSubtitles,
                        )
                    }
                    if (visibleEmbeddedTracks.isNotEmpty()) item {
                        Text(
                            stringResource(R.string.player_subtitles_embedded),
                            color = Color.White.copy(alpha = 0.62f),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 30.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
                        )
                    }
                    itemsIndexed(visibleEmbeddedTracks) { index, track ->
                        val language = trackLanguageName(track.language)
                        OptionButton(
                            label = language ?: track.title ?: stringResource(R.string.player_subtitle_track_fallback, track.id),
                            subtitle = listOfNotNull(embeddedTag, track.title, track.codec).distinct().joinToString(" • "),
                            active = selectedExternalSubtitleId == null && selectedSubtitleId == track.id,
                            activeBadge = selectedBadge.takeIf { selectedExternalSubtitleId == null && selectedSubtitleId == track.id },
                            requestFocus = if (index == 0 && subtitleTracks.isEmpty()) firstItemRequester else null,
                            onInteract = onInteract,
                            onClick = { onSelectSubtitle(track.id) },
                        )
                    }
                    if (subtitlesLoading) {
                        item {
                            OptionButton(
                                label = stringResource(R.string.player_searching_subtitle_sources),
                                subtitle = stringResource(
                                    when (subtitleTab) {
                                        SubtitlePanelTab.BuiltIn -> R.string.player_subtitles_searching_builtin
                                        SubtitlePanelTab.Addons -> R.string.player_subtitles_searching_addons
                                        else -> R.string.player_subtitles_searching_all
                                    },
                                ),
                                active = false,
                                onInteract = onInteract,
                                onClick = {},
                            )
                        }
                    }
                    if (visibleExternalSubtitles.isNotEmpty()) item {
                        Text(
                            stringResource(
                                when (subtitleTab) {
                                    SubtitlePanelTab.BuiltIn -> R.string.player_subtitles_heading_builtin
                                    SubtitlePanelTab.Addons -> R.string.player_subtitles_heading_addons
                                    else -> R.string.player_subtitles_heading_online
                                },
                            ),
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
                            label = Languages.displayLabel(panelResources, subtitle.language),
                            subtitle = listOfNotNull(
                                subtitle.sourceName,
                                if (subtitle.origin == ExternalSubtitleOrigin.BuiltIn) builtInSourceLabel else addonOriginLabel,
                                subtitle.release,
                                if (duplicateCount > 1) stringResource(R.string.player_subtitle_option_number, duplicateNumber) else null,
                            ).joinToString(" • "),
                            active = selectedExternalSubtitleId == subtitle.id,
                            activeBadge = selectedBadge.takeIf { selectedExternalSubtitleId == subtitle.id },
                            onInteract = onInteract,
                            onClick = { onSelectExternalSubtitle(subtitle) },
                        )
                    }
                    if (!subtitlesLoading && visibleEmbeddedTracks.isEmpty() && visibleExternalSubtitles.isEmpty()) item {
                        val requestedLanguages = allowedSubtitleLanguages.joinToString(" or ") {
                            Languages.displayLabel(panelResources, it)
                        }
                        OptionButton(
                            label = if (showOnlyPreferredSubtitleLanguages && requestedLanguages.isNotBlank()) {
                                stringResource(R.string.player_subtitles_none_for_languages, requestedLanguages)
                            } else stringResource(
                                when (subtitleTab) {
                                    SubtitlePanelTab.BuiltIn -> R.string.player_subtitles_none_builtin
                                    SubtitlePanelTab.Addons -> R.string.player_subtitles_none_addons
                                    else -> R.string.player_subtitles_none_all
                                },
                            ),
                            subtitle = stringResource(
                                when (subtitleTab) {
                                    SubtitlePanelTab.BuiltIn -> R.string.player_subtitles_none_hint_builtin
                                    SubtitlePanelTab.Addons -> R.string.player_subtitles_none_hint_addons
                                    else -> R.string.player_subtitles_none_hint_all
                                },
                            ),
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
                            label = stringResource(R.string.player_subtitle_text_size),
                            value = subtitleFontSize.toString(),
                            onInteract = onInteract,
                            onDecrease = { onSubtitleFontSize((subtitleFontSize - 2).coerceIn(SubtitleSizeRange)) },
                            onIncrease = { onSubtitleFontSize((subtitleFontSize + 2).coerceIn(SubtitleSizeRange)) },
                        )
                    }
                    item {
                        PlayerStepperRow(
                            label = stringResource(R.string.player_subtitle_position),
                            value = subtitlePosition.toString(),
                            hint = stringResource(R.string.player_subtitle_position_hint),
                            onInteract = onInteract,
                            onDecrease = { onSubtitlePosition((subtitlePosition - 2).coerceIn(SubtitlePositionRange)) },
                            onIncrease = { onSubtitlePosition((subtitlePosition + 2).coerceIn(SubtitlePositionRange)) },
                        )
                    }
                    if (subtitleDelaySupported) {
                        item {
                            val amount = AppFormats.number(appLanguage, kotlin.math.abs(subtitleDelay), 1)
                            PlayerStepperRow(
                                label = stringResource(R.string.player_subtitle_delay_title),
                                value = stringResource(
                                    R.string.player_subtitle_delay_seconds,
                                    // The sign is what makes "ahead" and "behind" readable at a
                                    // glance, and no number format adds one to a positive value.
                                    signedDelay(appLanguage, subtitleDelay, 1),
                                ),
                                // What the number means once there is one; until then, how to move it.
                                hint = when {
                                    subtitleDelay > 0 -> stringResource(R.string.player_subtitle_delay_later, amount)
                                    subtitleDelay < 0 -> stringResource(R.string.player_subtitle_delay_earlier, amount)
                                    else -> stringResource(R.string.player_delay_hold_hint)
                                },
                                onInteract = onInteract,
                                onDecrease = { onSubtitleDelay(steppedDelay(subtitleDelay, -subtitleDelayStep(0), SUBTITLE_DELAY_LIMIT_SECONDS)) },
                                onIncrease = { onSubtitleDelay(steppedDelay(subtitleDelay, subtitleDelayStep(0), SUBTITLE_DELAY_LIMIT_SECONDS)) },
                                onAdjust = { direction, repeat ->
                                    onSubtitleDelay(steppedDelay(subtitleDelay, direction * subtitleDelayStep(repeat), SUBTITLE_DELAY_LIMIT_SECONDS))
                                },
                            )
                        }
                        item {
                            OptionButton(
                                label = stringResource(R.string.player_subtitle_delay_reset),
                                subtitle = stringResource(R.string.player_subtitle_delay_scope),
                                active = false,
                                onInteract = onInteract,
                                onClick = { onSubtitleDelay(0.0) },
                            )
                        }
                    }
                    item {
                        PanelNote(stringResource(R.string.player_subtitle_style_note))
                    }
                }
                OverlayPanel.Captions -> {
                    // Only tracks the stream has been seen to carry. A placeholder the container
                    // merely allows for would be a row that does nothing when chosen.
                    val captionTracks = subtitleTracks.filterNot { it.speculative }
                    val captionsOff = selectedSubtitleId < 0 && selectedExternalSubtitleId == null
                    item {
                        OptionButton(
                            label = stringResource(R.string.player_captions_off_option),
                            subtitle = stringResource(R.string.player_captions_off_description),
                            active = captionsOff,
                            activeBadge = selectedBadge.takeIf { captionsOff },
                            requestFocus = firstItemRequester,
                            onInteract = onInteract,
                            onClick = onDisableSubtitles,
                        )
                    }
                    itemsIndexed(captionTracks) { index, track ->
                        val language = trackLanguageName(track.language)
                        OptionButton(
                            label = language ?: track.title ?: stringResource(R.string.player_caption_track_fallback, index + 1),
                            subtitle = listOfNotNull(captionFormatLabel(track.codec), track.title.takeIf { language != null })
                                .distinct().joinToString(" • ").ifBlank { null },
                            active = !captionsOff && selectedSubtitleId == track.id,
                            activeBadge = selectedBadge.takeIf { !captionsOff && selectedSubtitleId == track.id },
                            onInteract = onInteract,
                            onClick = { onSelectSubtitle(track.id) },
                        )
                    }
                    if (captionTracks.isEmpty()) item {
                        PanelNote(stringResource(R.string.player_captions_none_now))
                    }
                    item { PanelNote(stringResource(R.string.player_captions_detected_note)) }
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
                            activeBadge = selectedBadge.takeIf { currentSpeed == option.value },
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
                        streamProviderLabel(stream, currentLabel)
                            ?.let { add(panelResources.getString(R.string.player_info_provider) to it) }
                        streamOriginLabel(stream, pluginState, addonOriginLabel, pluginOriginLabel)
                            ?.let { add(panelResources.getString(R.string.player_info_installed_as) to it) }
                        add(panelResources.getString(R.string.player_info_delivery) to panelResources.getString(transport.labelRes))
                        stream?.size?.takeIf { it.isNotBlank() }
                            ?.let { add(panelResources.getString(R.string.player_info_size) to it) }
                        stream?.quality?.takeIf { it.isNotBlank() }
                            ?.let { add(panelResources.getString(R.string.player_quality) to it) }
                        (stream?.filename ?: stream?.behaviorHints?.filename)?.takeIf { it.isNotBlank() }
                            ?.let { add(panelResources.getString(R.string.player_info_file) to it) }
                    }
                    val playbackRows = buildList {
                        formatTransferRate(playbackStats?.bytesPerSecond)
                            ?.let { add(panelResources.getString(R.string.player_info_speed) to it) }
                        formatResolution(playbackStats?.width ?: 0, playbackStats?.height ?: 0)
                            ?.let { add(panelResources.getString(R.string.player_info_resolution) to it) }
                        val videoLine = listOfNotNull(
                            prettyCodecName(playbackStats?.videoCodec),
                            formatBitrate(playbackStats?.videoBitrateBps),
                            playbackStats?.frameRate?.let {
                                panelResources.getString(R.string.player_video_fps, AppFormats.number(appLanguage, it))
                            },
                        ).joinToString(" · ")
                        if (videoLine.isNotBlank()) add(panelResources.getString(R.string.player_info_video) to videoLine)
                        val audioLine = listOfNotNull(
                            prettyCodecName(playbackStats?.audioCodec),
                            playbackStats?.audioChannels?.let { channels ->
                                when {
                                    channels > 2 -> panelResources.getString(
                                        R.string.player_audio_channels,
                                        AppFormats.number(appLanguage, channels),
                                    )
                                    channels == 2 -> panelResources.getString(R.string.player_audio_stereo)
                                    else -> panelResources.getString(R.string.player_audio_mono)
                                }
                            },
                        ).joinToString(" · ")
                        if (audioLine.isNotBlank()) add(panelResources.getString(R.string.player_audio) to audioLine)
                        playbackStats?.bufferedSeconds?.let {
                            add(
                                panelResources.getString(R.string.player_info_buffered) to
                                    panelResources.getString(R.string.player_buffered_ahead, AppFormats.number(appLanguage, it)),
                            )
                        }
                        playbackStats?.hardwareDecoder?.let { add(panelResources.getString(R.string.player_info_decoder) to it) }
                        if (engineLabel.isNotBlank()) add(panelResources.getString(R.string.player_engine) to engineLabel)
                        if (!isLive && durationSec > 0.0) {
                            add(panelResources.getString(R.string.player_info_runtime) to formatPlaybackClock(durationSec))
                        }
                    }
                    item { PanelSectionHeading(sourceHeading) }
                    item { PlayerInfoTable(sourceRows) }
                    item { PanelSectionHeading(playbackHeading) }
                    item { PlayerInfoTable(playbackRows) }
                    if (playbackStats == null) {
                        item { PanelNote(stringResource(R.string.player_reading_details)) }
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
            border = Border.None,
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
            imageVector = Icons.Rounded.SkipNext,
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

/**
 * The up-next card is drawn to the same measurements as an episode card on the title page: 268dp
 * over a 150dp still, which is 16:9 exactly.
 *
 * One shape for "here is an episode", wherever the viewer meets it. It was built at 700dp, which
 * is a third of the screen standing over the credits it interrupts. The padding and type inside
 * follow the same card too -- 12dp and 9dp, a bold titleSmall over bodySmall -- so the two read as
 * the same object rather than as two designs that happen to share a width.
 *
 * Kept in step with the episode band in DetailComponents by hand: they are two screens with no
 * shared layout between them, and a stray dp is invisible until they sit side by side.
 */
private val NextEpisodeCardWidth = 268.dp
private val NextEpisodeCardStillHeight = 150.dp

@Composable
internal fun NextEpisodeDialog(
    detail: MediaDetail?,
    episode: EpisodeContext,
    streams: List<AddonStream>,
    playRequested: Boolean,
    countdown: Int?,
    availability: NextEpisodeAvailability,
    playRequester: FocusRequester,
    cancelRequester: FocusRequester,
    recommendations: List<MediaItem>,
    currentTitle: String,
    savedRecommendationIds: Set<String>,
    onPlayNow: () -> Unit,
    onSelectStream: (Int) -> Unit,
    onPlayRecommendation: (MediaItem) -> Unit,
    onAddRecommendationToWatchlist: (MediaItem) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        PlayerGlassSurface(
            // Inset first, then width: the 36dp is the card's distance from the screen edge, and
            // applying it after the width would take those 36dp out of the card instead.
            modifier = Modifier.padding(end = 36.dp, bottom = 36.dp)
                .width(if (recommendations.isEmpty()) NextEpisodeCardWidth else 500.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(NextEpisodeCardStillHeight),
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
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = detail?.title ?: stringResource(R.string.player_next_episode),
                            style = androidx.tv.material3.MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = buildString {
                                append("S${episode.seasonNumber.toString().padStart(2, '0')}E${episode.episodeNumber.toString().padStart(2, '0')}")
                                episode.title?.takeIf { it.isNotBlank() }?.let {
                                    append("  ·  ")
                                    append(it)
                                }
                            },
                            style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.82f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (countdown != null && countdown > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.player_autoplay_countdown, countdown, countdown),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = androidx.tv.material3.MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }

                Text(
                    text = when {
                        availability == NextEpisodeAvailability.Unaired -> stringResource(R.string.player_next_unaired_message)
                        availability == NextEpisodeAvailability.Unknown -> stringResource(R.string.player_next_release_unknown_message)
                        countdown != null -> stringResource(R.string.player_next_autoplay_message)
                        else -> stringResource(R.string.player_next_manual_message)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.76f),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.height(32.dp).focusRequester(cancelRequester),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) {
                        Text(stringResource(R.string.action_cancel), style = androidx.tv.material3.MaterialTheme.typography.labelMedium)
                    }
                    Button(
                        onClick = onPlayNow,
                        enabled = !playRequested,
                        modifier = Modifier.height(32.dp).focusRequester(playRequester),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) {
                        Text(
                            if (availability == NextEpisodeAvailability.Unaired) {
                                stringResource(R.string.player_skip_ending)
                            } else if (playRequested) {
                                stringResource(R.string.player_next_preparing)
                            } else {
                                stringResource(R.string.player_next_episode)
                            },
                            style = androidx.tv.material3.MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                if (recommendations.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.player_recommendations_heading),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                        style = androidx.tv.material3.MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.58f),
                    )
                    recommendations.forEach { item ->
                        TvRecommendationChoice(
                            item = item,
                            reason = stringResource(R.string.player_recommendation_reason, currentTitle),
                            queued = false,
                            saved = item.id in savedRecommendationIds,
                            focusRequester = null,
                            onClick = { onPlayRecommendation(item) },
                            onAddToWatchlist = { onAddRecommendationToWatchlist(item) },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun NextRecommendationDialog(
    currentTitle: String,
    items: List<MediaItem>,
    queuedItemId: String?,
    savedItemIds: Set<String>,
    playRequester: FocusRequester,
    cancelRequester: FocusRequester,
    onPlayNext: (MediaItem) -> Unit,
    onAddToWatchlist: (MediaItem) -> Unit,
    onDismiss: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visibleItems = items.take(2)
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomEnd,
    ) {
        PlayerGlassSurface(
            modifier = Modifier
                .width(if (visibleItems.size == 2) 860.dp else 500.dp)
                .padding(end = 36.dp, bottom = 36.dp)
                .onFocusChanged { onFocusChanged(it.hasFocus) }
                .focusGroup(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text(
                    stringResource(R.string.player_recommended_for_you),
                    color = Color.White.copy(alpha = 0.58f),
                    style = androidx.tv.material3.MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    visibleItems.forEachIndexed { index, item ->
                        TvRecommendationChoice(
                            item = item,
                            reason = stringResource(R.string.player_recommendation_reason, currentTitle),
                            queued = queuedItemId == item.id,
                            saved = item.id in savedItemIds,
                            focusRequester = playRequester.takeIf { index == 0 },
                            onClick = { onPlayNext(item) },
                            onAddToWatchlist = { onAddToWatchlist(item) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End).focusRequester(cancelRequester),
                    colors = ButtonDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color(0x22FFFFFF)),
                ) { Text(stringResource(R.string.action_dismiss), color = Color.White.copy(alpha = 0.74f)) }
            }
        }
    }
}

@Composable
private fun TvRecommendationChoice(
    item: MediaItem,
    reason: String?,
    queued: Boolean,
    saved: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    onAddToWatchlist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var watchlistFocused by remember(item.id) { mutableStateOf(false) }
    Row(
        modifier = modifier
            .widthIn(min = 380.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0x0AFFFFFF))
            .border(1.dp, if (queued) Color(0x66F0BA66) else Color(0x12FFFFFF), RoundedCornerShape(13.dp))
            .padding(11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val artwork = item.backdrop ?: item.poster
        Box(
            modifier = Modifier.width(142.dp).height(82.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF272C35)),
            contentAlignment = Alignment.Center,
        ) {
            if (!artwork.isNullOrBlank()) {
                AsyncImage(model = artwork, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White.copy(alpha = 0.28f))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, color = Color.White, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            reason?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Color.White.copy(alpha = 0.54f), style = androidx.tv.material3.MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (queued) Text(stringResource(R.string.player_queued_next), color = Color(0xFFF0BA66), style = androidx.tv.material3.MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onClick,
                    modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
                    colors = ButtonDefaults.colors(
                        containerColor = if (queued) Color(0x18FFFFFF) else Color(0xFFF0BA66),
                        focusedContainerColor = Color.White,
                        contentColor = if (queued) Color.White else Color(0xFF171A20),
                        focusedContentColor = Color.Black,
                    ),
                ) {
                    Text(
                        if (queued) stringResource(R.string.a11y_selected) else stringResource(R.string.action_play_now),
                        fontWeight = FontWeight.Bold,
                    )
                }
                OutlinedButton(
                    onClick = onAddToWatchlist,
                    enabled = !saved,
                    modifier = Modifier.onFocusChanged { watchlistFocused = it.isFocused },
                    contentPadding = PaddingValues(horizontal = 11.dp, vertical = 8.dp),
                    colors = ButtonDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = Color.White,
                        contentColor = Color.White.copy(alpha = 0.88f),
                        focusedContentColor = Color.Black,
                        disabledContentColor = Color.White.copy(alpha = 0.42f),
                    ),
                ) {
                    Icon(
                        imageVector = if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        contentDescription = stringResource(R.string.action_add_to_watchlist),
                        modifier = Modifier.size(18.dp),
                    )
                    AnimatedVisibility(visible = watchlistFocused && !saved) {
                        Text(
                            text = stringResource(R.string.action_add_to_watchlist),
                            modifier = Modifier.padding(start = 7.dp),
                            style = androidx.tv.material3.MaterialTheme.typography.labelSmall,
                        )
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
            border = if (active) Border(BorderStroke(1.dp, Color(0x668B5CF6)), shape = RoundedCornerShape(14.dp)) else Border.None,
            focusedBorder = Border(BorderStroke(2.dp, Color(0xFFF0BA66)), shape = RoundedCornerShape(14.dp)),
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
internal fun stableSourceFavouriteKey(stream: AddonStream): String = listOf(
    stream.addonId.ifBlank { stream.addonName },
    stream.source.orEmpty(),
    stream.name.orEmpty(),
    stream.title.orEmpty(),
    stream.quality.orEmpty(),
).joinToString("|") { it.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ") }.take(512)

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
    favourite: Boolean = false,
    onToggleFavourite: () -> Unit = {},
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
        !stream.url.isNullOrBlank() -> stringResource(R.string.player_info_route_direct) to false
        !stream.nzbUrl.isNullOrBlank() -> stringResource(R.string.transport_usenet) to false
        else -> stringResource(R.string.transport_torrent) to false
    }
    OutlinedButton(
        onClick = onClick,
        scale = ButtonDefaults.scale(focusedScale = TvMotion.focusScale()),
        shape = ButtonDefaults.shape(RoundedCornerShape(14.dp)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .tvCardLongPress(onToggleFavourite)
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
            border = if (playing) Border(BorderStroke(1.dp, Color(0x668B5CF6)), shape = RoundedCornerShape(14.dp)) else Border.None,
            focusedBorder = Border(BorderStroke(2.dp, Color(0xFFF0BA66)), shape = RoundedCornerShape(14.dp)),
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stream.addonName.ifBlank { stringResource(R.string.player_stream_source_fallback) },
                    style = androidx.tv.material3.MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
                    color = Color(0xFFD4B8FF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = if (favourite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = stringResource(if (favourite) R.string.player_source_pinned_hint else R.string.player_source_pin_hint),
                    tint = if (favourite) Color(0xFFF0BA66) else Color.White.copy(alpha = 0.42f),
                    modifier = Modifier.size(18.dp),
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
                if (playing) ActiveBadge(stringResource(R.string.player_now_playing))
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
    requestFocus: FocusRequester? = null,
    /**
     * Left or right as a direction (-1, +1) with how many times the key has repeated, for a value
     * whose step should grow while the key is held. Replaces [onDecrease] and [onIncrease] for the
     * keys when given; the on-screen press still uses them.
     */
    onAdjust: ((direction: Int, repeatCount: Int) -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = onIncrease,
        scale = ButtonDefaults.scale(focusedScale = TvMotion.focusScale()),
        shape = ButtonDefaults.shape(RoundedCornerShape(14.dp)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .then(if (requestFocus != null) Modifier.focusRequester(requestFocus) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onInteract()
            }
            .onPreviewKeyEvent { event ->
                if (!focused || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val repeat = event.nativeKeyEvent.repeatCount
                when (event.key) {
                    Key.DirectionLeft -> { onAdjust?.invoke(-1, repeat) ?: onDecrease(); true }
                    Key.DirectionRight -> { onAdjust?.invoke(1, repeat) ?: onIncrease(); true }
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
            border = Border.None,
            focusedBorder = Border(BorderStroke(2.dp, Color(0xFFF0BA66)), shape = RoundedCornerShape(14.dp)),
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
                (hint ?: stringResource(R.string.player_stepper_hint)).let {
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
    labelOf: @Composable (T) -> String,
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
                    border = if (active) Border(BorderStroke(1.dp, Color(0x668B5CF6)), shape = AppPillShape) else Border.None,
                    focusedBorder = Border(BorderStroke(2.dp, Color(0xFFF0BA66)), shape = AppPillShape),
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
        PanelNote(stringResource(R.string.player_info_nothing_yet))
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

/** The clock either side of the timeline. Tabular figures, so the digits do not jitter as they tick. */
@Composable
private fun TimeLabel(text: String, emphasised: Boolean) {
    Text(
        text = text,
        style = androidx.tv.material3.MaterialTheme.typography.labelLarge.copy(
            fontWeight = if (emphasised) FontWeight.Black else FontWeight.Medium,
            fontFeatureSettings = "tnum",
        ),
        color = if (emphasised) PlayerTokens.TextPrimary else PlayerTokens.TextSecondary,
        maxLines = 1,
        modifier = Modifier.widthIn(min = 64.dp),
    )
}

/** "CEA-608" and the like, for a caption row, from whatever the engine called the format. */
private fun captionFormatLabel(codec: String?): String? {
    val value = codec?.lowercase(Locale.US) ?: return null
    return when {
        "608" in value -> "CEA-608"
        "708" in value -> "CEA-708"
        "webvtt" in value || "vtt" in value -> "WebVTT"
        "ttml" in value || "stpp" in value -> "TTML"
        "dvb" in value -> "DVB"
        else -> null
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
