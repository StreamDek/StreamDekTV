package com.streamdek.tv.nativeapp.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** Shared vNext geometry keeps every content surface visually related and limits focus zoom. */
val AppCardShape = RoundedCornerShape(14.dp)

object TvSpacing {
    val ScreenHorizontal = 48.dp
    val ScreenTop = 56.dp
    val Section = 24.dp
    val Card = 14.dp
    val Compact = 8.dp
}

object TvMotion {
    @Composable
    fun duration(baseMillis: Int): Int {
        val settings = LocalTvExperienceSettings.current
        return if (settings.reducedMotion) 0 else (baseMillis * settings.animationScale).toInt()
    }

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

    val Easing = androidx.compose.animation.core.CubicBezierEasing(0.22f, 0.9f, 0.24f, 1f)

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
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
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
