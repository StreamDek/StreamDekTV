package com.streamdek.tv.nativeapp.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shared vNext geometry keeps every content surface visually related and limits focus zoom. */
val AppCardShape = RoundedCornerShape(14.dp)

object TvSpacing {
    val ScreenHorizontal = 48.dp
    val ScreenTop = 56.dp
    val Section = 24.dp
    val Card = 14.dp
    val Compact = 8.dp
}

/** The navigation rail at rest, before focus widens it. */
val TvNavRailWidth = 64.dp

/**
 * How far content has to start from the left edge to clear the rail.
 *
 * A screen that lays out inside this is not just overlapped — it is unreachable from the rail and
 * cannot reach it. Focus moves left by looking for something further left than what is focused, and
 * a card sitting on top of the rail is not further left than anything.
 */
val TvNavRailInset = TvNavRailWidth + 4.dp

/**
 * Whether the side navigation currently owns the D-pad.
 *
 * Only one region is authoritative at a time. Screens place their own focus over a short retry
 * window as their content arrives — an opening shot, a restored position, a row that just landed —
 * and every one of those has to stand down while the menu is open. Otherwise a viewer who reached
 * for the menu mid-load has the highlight dragged back into the page one retry at a time, which is
 * indistinguishable, from the sofa, from the drawer collapsing on its own.
 *
 * Not `static`: it changes, and the screens reading it need to recompose when it does.
 */
val LocalSideNavOwnsFocus = androidx.compose.runtime.compositionLocalOf { false }

/**
 * Ask for focus, and say whether the request could be made at all.
 *
 * [FocusRequester.requestFocus] returns nothing and throws when nothing is attached to it, so "did
 * that land" is the absence of an exception and nothing else. This exists because the shell kept
 * writing it out by hand as `runCatching { requestFocus() }.getOrDefault(false) == true`, which
 * compares `Unit` against `true` and is therefore *always* false — every focus handoff in the
 * navigation rail was silently treated as having failed, and the fallback for a failed handoff was
 * to clear focus outright. That is what left screens with no visible focus owner at all.
 *
 * A true result means the requester was attached and the request was dispatched. It is not a
 * promise that a particular child accepted it; where that matters, observe the focus instead.
 */
internal fun FocusRequester.requestFocusOrFalse(): Boolean =
    runCatching { requestFocus() }.isSuccess

/**
 * The app's motion vocabulary.
 *
 * Every animation in the app used to name its own duration inline — 110, 140, 150, 170, 200, 220,
 * all on Compose's default easing — so the same gesture was timed differently depending on which
 * screen it happened on, and most of them bypassed the viewer's reduced-motion and speed settings
 * entirely because those only applied where someone remembered to call [duration].
 *
 * There are four jobs here and each gets one answer:
 *
 * - **Focus response** ([Instant]) is not decoration. It is the acknowledgement of a button press,
 *   and anything slow enough to notice reads as the remote having missed it.
 * - **Arriving** ([Standard], [EnterEasing]) decelerates: fast at the start so the thing is legible
 *   immediately, settling at the end so it does not stop dead.
 * - **Leaving** ([Quick], [ExitEasing]) accelerates and is shorter than arriving. A viewer who
 *   dismissed something has already moved on; matching the enter duration makes it linger.
 * - **Confirming** ([emphasisSpec]) is the one place overshoot belongs — a toggle that flipped,
 *   where a slight rebound reads as "that registered". Never on focus, where it makes the ring feel
 *   loose.
 *
 * All of them collapse to zero under reduced motion and stretch or compress with the animation
 * speed setting, because they route through [duration].
 */
object TvMotion {
    /**
     * The four names below are aliases of the shared [MotionDuration] tokens rather than numbers of
     * their own, so the television and the phone cannot drift apart on what "the default duration"
     * means. The names are kept because eighty-odd call sites already read well with them.
     */
    /** Focus rings, pill tints — the direct acknowledgement of a press. */
    const val Instant = MotionDuration.instant

    /** Small fades and swaps: a badge changing, a label crossfading. */
    const val Quick = MotionDuration.short

    /** The default for something arriving: a panel, a row of content, an overlay. */
    const val Standard = MotionDuration.standard

    /** A layout changing size — a hero collapsing, a section expanding. */
    const val Expand = MotionDuration.long

    /** Deceleration. Arrives quickly and settles rather than stopping dead. */
    val EnterEasing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Acceleration. Leaves without lingering. */
    val ExitEasing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** In-and-out, for something that moves from one resting place to another and stays. */
    val StandardEasing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /**
     * A base duration at the viewer's chosen speed. The single point every animation on the
     * television passes through, which is what makes one setting govern all of them.
     */
    @Composable
    fun duration(baseMillis: Int): Int = LocalTvExperienceSettings.current.motion.scaled(baseMillis)

    /**
     * One thing replacing another in the same place.
     *
     * Never zero-length, even with motion off: an instant swap of a full-screen image is its own
     * kind of jarring, and a short opacity change is the accessible replacement for movement rather
     * than an instance of it.
     */
    @Composable
    fun crossfadeDuration(baseMillis: Int = MotionDuration.crossfade): Int =
        LocalTvExperienceSettings.current.motion.crossfade(baseMillis)

    /** The per-step offset of a staggered sequence, at the viewer's speed. */
    @Composable
    fun staggerStep(baseMillis: Int = MotionDuration.stagger): Int =
        LocalTvExperienceSettings.current.motion.stagger(baseMillis)

    /** A decelerating tween for something arriving or settling into a new value. */
    @Composable
    fun <T> enterSpec(baseMillis: Int = Standard): androidx.compose.animation.core.TweenSpec<T> =
        androidx.compose.animation.core.tween(duration(baseMillis), easing = EnterEasing)

    /** An accelerating tween, shorter by default, for something being dismissed. */
    @Composable
    fun <T> exitSpec(baseMillis: Int = Quick): androidx.compose.animation.core.TweenSpec<T> =
        androidx.compose.animation.core.tween(duration(baseMillis), easing = ExitEasing)

    /** In-out, for a value moving between two resting states. */
    @Composable
    fun <T> standardSpec(baseMillis: Int = Standard): androidx.compose.animation.core.TweenSpec<T> =
        androidx.compose.animation.core.tween(duration(baseMillis), easing = StandardEasing)

    /** The immediate-feedback tween. Linear would do at this length; the curve is for consistency. */
    @Composable
    fun <T> instantSpec(): androidx.compose.animation.core.TweenSpec<T> =
        androidx.compose.animation.core.tween(duration(Instant), easing = StandardEasing)

    /**
     * A spring with a small overshoot, for a discrete change the viewer just caused.
     *
     * Reduced motion drops it to a critically damped spring rather than to nothing: the value still
     * has to travel, and snapping a scale to its target is more jarring than easing to it. What is
     * removed is the rebound, which is the part that reads as motion for its own sake.
     */
    @Composable
    fun <T> emphasisSpec(): androidx.compose.animation.core.SpringSpec<T> =
        if (LocalTvExperienceSettings.current.reducedMotion) {
            androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessHigh,
            )
        } else {
            androidx.compose.animation.core.spring(
                dampingRatio = 0.58f,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
            )
        }

    /** Fade in, decelerating — the app's default way for anything to appear. */
    @Composable
    fun fadeInSpec(baseMillis: Int = Standard) =
        androidx.compose.animation.fadeIn(enterSpec(baseMillis))

    /** Fade out, accelerating and shorter than the matching fade in. */
    @Composable
    fun fadeOutSpec(baseMillis: Int = Quick) =
        androidx.compose.animation.fadeOut(exitSpec(baseMillis))

    /**
     * Cards keep their size when focused.
     *
     * A grid whose focused tile grows pushes its neighbours around and makes the row look like it
     * is breathing as focus travels, which is exactly the wrong signal when the point of a grid is
     * that every item is the same weight. Focus is shown with the accent ring instead, so card
     * geometry is fixed and the layout can spend the space on more items per row.
     */
    @Composable
    fun focusScale(): Float = 1f
}
val AppPillShape = RoundedCornerShape(999.dp)


/**
 * Motion shared by every scrolling surface.
 *
 * Two things made row-to-row movement feel like a cut rather than a glide. The focus system runs
 * its own `bringIntoView` scroll the instant focus lands on an off-screen child, and screens that
 * also position rows themselves then ran a second scroll on top of it — two animations, different
 * curves, same axis. And `animateScrollToItem` takes no easing, so long moves arrived abruptly.
 *
 * [SuppressBringIntoView] hands the axis to whoever is positioning rows explicitly, and
 * [glideToItem] gives every one of those a single consistent curve.
 */
object TvScroll {
    const val DurationMs = 340

    /**
     * The same deceleration every other arrival uses.
     *
     * This used to be its own curve, close to but not the same as the one panels and overlays ran
     * on, so a row gliding into place and the content fading in over it settled at visibly
     * different rates.
     */
    val Easing = TvMotion.EnterEasing

    fun <T> spec(durationMs: Int = DurationMs): androidx.compose.animation.core.TweenSpec<T> =
        androidx.compose.animation.core.tween(durationMs, easing = Easing)
}

/**
 * Stops the focus system scrolling an axis, for lists that position themselves.
 *
 * Provide this around a list whose scroll position is driven by an effect. Without it the focus
 * system moves the list first and the effect corrects it a frame later, which is the visible
 * double-step.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
val SuppressBringIntoView = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** Scrolls [index] to the top on one easing curve. */
suspend fun androidx.compose.foundation.lazy.LazyListState.glideToItem(
    index: Int,
    durationMs: Int = TvScroll.DurationMs,
) {
    val entry = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (entry != null && entry.offset != 0) {
        animateScrollBy(
            value = entry.offset.toFloat(),
            animationSpec = TvScroll.spec<Float>(durationMs),
        )
    } else if (entry == null) {
        animateScrollToItem(index)
    }
}

/**
 * Chrome surfaces — the navigation rail, control columns and settings sidebar.
 *
 * Deliberately neutral rather than the near-blacks these replaced (`0x07090D`, `0x10141B` and
 * friends), every one of which carried more blue than red and read as navy against content.
 * They were also translucent, so the tinted backdrop bled through and the cast got stronger the
 * brighter the artwork behind it. Opaque and neutral keeps the chrome receding instead of
 * competing with the poster art.
 */
val TvChromeSurface = Color(0xFF060607)

/** One step up from [TvChromeSurface], for panels that sit on top of chrome. */
val TvChromePanel = Color(0xFF0E0E11)

/**
 * Hands an intent to whatever app can take it, reporting failure instead of crashing.
 *
 * TV boxes are a hostile place for implicit intents: plenty of Fire TV and Android TV devices ship
 * with no browser, and the YouTube app on them does not always accept a `watch?v=` view intent.
 * An unhandled intent throws [android.content.ActivityNotFoundException], which on a remote means
 * the whole app disappears back to the launcher with no explanation.
 *
 * @return null when the intent was handed off, or a message to show the viewer when it was not.
 */
fun launchExternalIntent(context: android.content.Context, intent: android.content.Intent, label: String): String? {
    val withFlags = intent.apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
    return runCatching { context.startActivity(withFlags) }
        .fold(
            onSuccess = { null },
            onFailure = { error ->
                TvDebugLogger.w("Intent", "no activity accepted $label", error)
                "This TV has no app that can open $label."
            },
        )
}

private val CurrentTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun formatPlaybackClock(seconds: Double?): String {
    val safe = (seconds ?: 0.0).toInt().coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}

@Composable
fun CurrentTimePill(
    modifier: Modifier = Modifier,
) {
    var currentTime by remember {
        mutableStateOf(LocalTime.now().format(CurrentTimeFormatter))
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now().format(CurrentTimeFormatter)
            delay(30_000)
        }
    }

    Text(
        text = currentTime,
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
            // The clock sits directly on artwork with no plate behind it, so on a pale backdrop it
            // had nothing to separate it from the image. Tight and close in: enough to hold an edge
            // against a bright frame without reading as a drop shadow in its own right.
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.72f),
                offset = Offset(0f, 1f),
                blurRadius = 5f,
            ),
        ),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f),
    )
}

@Composable
fun ProgressMeter(
    progress: Double?,
    modifier: Modifier = Modifier,
) {
    val ratio = ((progress ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
    Box(
        modifier = modifier
            .background(Color(0x40FFFFFF), RoundedCornerShape(999.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(ratio)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp)),
        )
    }
}


/**
 * Placeholder card for a row that has not arrived yet.
 *
 * The shimmer is a single animated alpha on a solid box rather than a moving gradient: it reads as
 * "working" without the per-frame shader cost of a sweep, which matters when a dozen of these are
 * on screen at once on a Firestick. Reduced-motion holds it at a flat tint.
 */
@Composable
fun TvSkeletonBox(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = AppCardShape) {
    val reducedMotion = LocalTvExperienceSettings.current.reducedMotion
    val alpha = if (reducedMotion) {
        0.10f
    } else {
        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
        transition.animateFloat(
            initialValue = 0.06f,
            targetValue = 0.16f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(TvMotion.duration(900).coerceAtLeast(1)),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "skeleton-alpha",
        ).value
    }
    Box(modifier.background(Color.White.copy(alpha = alpha), shape))
}

/**
 * The one way this app says "there is nothing here".
 *
 * Home, Search, Live and Library each grew their own centred line of grey text, which read
 * differently on every screen and — worse — gave the remote nothing to land on. A state that can
 * be reached but not left is the single most frustrating thing on a TV, so an action is offered
 * whenever there is one to offer, and it takes focus on arrival.
 */
@Composable
fun TvEmptyState(
    title: String,
    message: String? = null,
    actionLabel: String? = null,
    modifier: Modifier = Modifier,
    onAction: (() -> Unit)? = null,
) {
    val actionRequester = remember { FocusRequester() }

    LaunchedEffect(title, actionLabel) {
        if (onAction == null || actionLabel == null) return@LaunchedEffect
        delay(120)
        runCatching { actionRequester.requestFocus() }
    }

    androidx.compose.foundation.layout.Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = TvSpacing.ScreenHorizontal, vertical = 40.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
        )
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f),
            )
        }
        if (onAction != null && actionLabel != null) {
            androidx.tv.material3.Button(
                onClick = onAction,
                modifier = Modifier.padding(top = 8.dp).focusRequester(actionRequester),
                shape = androidx.tv.material3.ButtonDefaults.shape(AppPillShape),
            ) {
                Text(actionLabel)
            }
        }
    }
}

/** A grid of placeholders, used while a results page is still loading. */
@Composable
fun TvSkeletonGrid(
    columns: Int = 5,
    rows: Int = 2,
    portrait: Boolean = true,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.padding(horizontal = TvSpacing.ScreenHorizontal),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(TvSpacing.Card),
    ) {
        repeat(rows) {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(TvSpacing.Card),
            ) {
                repeat(columns) {
                    TvSkeletonBox(
                        Modifier
                            .width(if (portrait) 132.dp else 208.dp)
                            .height(if (portrait) 198.dp else 122.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun TvSectionHeading(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(modifier = modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = MaterialTheme.colorScheme.onBackground)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f)) }
    }
}

/**
 * The affordance that says "there is more text than this".
 *
 * Hero copy is clamped to whatever fits the band, which on a long synopsis means the sentence stops
 * mid-thought with no way to read the rest. This is the way through: small enough that it does not
 * compete with the artwork, focusable so a remote can actually reach it.
 */
@Composable
fun TvMoreButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "More",
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(32.dp),
        shape = ButtonDefaults.shape(AppPillShape),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        colors = ButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.10f),
            contentColor = MaterialTheme.colorScheme.onBackground,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = Color.Black,
        ),
        border = ButtonDefaults.border(border = androidx.tv.material3.Border.None),
        scale = ButtonDefaults.scale(focusedScale = 1f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black),
        )
    }
}

/**
 * The full synopsis, over the screen it was opened from.
 *
 * Close holds focus rather than the text, because a remote has no other way to leave: up and down
 * on it scroll the copy, so a long synopsis is readable without ever moving focus somewhere the
 * viewer then has to find their way back from.
 */
@Composable
fun TvSynopsisDialog(
    title: String,
    synopsis: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
) {
    val closeRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val scrollStepPx = 200

    LaunchedEffect(title) {
        delay(80)
        runCatching { closeRequester.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xC4000000)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.62f)
                    .heightIn(max = 520.dp)
                    .background(TvChromePanel, RoundedCornerShape(24.dp))
                    .padding(horizontal = 32.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = synopsis,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.84f),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(scrollState),
                )
                Button(
                    onClick = onDismiss,
                    shape = ButtonDefaults.shape(AppPillShape),
                    modifier = Modifier
                        .focusRequester(closeRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (!scrollState.canScrollForward) return@onPreviewKeyEvent true
                                    scope.launch {
                                        scrollState.animateScrollTo(
                                            (scrollState.value + scrollStepPx).coerceAtMost(scrollState.maxValue),
                                        )
                                    }
                                    true
                                }
                                Key.DirectionUp -> {
                                    if (scrollState.canScrollBackward) {
                                        scope.launch {
                                            scrollState.animateScrollTo(
                                                (scrollState.value - scrollStepPx).coerceAtLeast(0),
                                            )
                                        }
                                    }
                                    true
                                }
                                else -> false
                            }
                        },
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
fun TvStatePanel(title: String, message: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), AppCardShape).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
    }
}
