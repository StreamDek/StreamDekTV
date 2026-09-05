package com.streamdek.tv.nativeapp.ui

import android.content.Context
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.streamdek.tv.R
import kotlin.math.roundToInt

/**
 * StreamDek's canonical animation-speed model.
 *
 * # One specification, two installations
 *
 * This file is the specification, and it is deliberately duplicated verbatim - modulo the package
 * line - in the mobile app at `nativeapp/AnimationSpeed.kt`. The two apps share no code, so
 * the alternative to duplicating it is the two of them drifting into different ideas of what
 * "Standard" means, which is the thing this is meant to prevent. Mode names, keys, scale values and
 * override behaviour must stay identical in both copies; only the storage differs.
 *
 * The *selection* is emphatically not shared. It is a property of the installation, not of the
 * account or the viewing profile: a television across the room is watched from three metres away
 * and a phone from thirty centimetres, and the same person can reasonably want different answers on
 * each. So it is written to device-local storage on each side and never travels through SyncDek,
 * the profile document, cloud preferences or the backend.
 *
 * # What the scale may touch
 *
 * Durations of things that move, and nothing else. Every animation in the app should get its timing
 * from [MotionDuration] scaled by [MotionSettings.scaled], rather than naming a number of its own.
 *
 * What it must never touch: loading indicators' own progress, playback and buffering timing, network
 * timeouts, long-press thresholds, auto-hide and auto-advance delays, retry backoff, or any other
 * functional timer. A viewer who asked for slower transitions did not ask for a longer long-press,
 * and one who asked for faster transitions did not ask for a shorter network timeout. Those stay
 * measured in real milliseconds.
 *
 * Scaling changes *duration*, never distance: a Cinematic reveal travels exactly as far as a Fast
 * one, it simply takes longer to get there. Growing the travel with the time is what turns a
 * setting for people who like watching transitions into a setting that makes the app feel loose.
 */
enum class AnimationSpeed(
  /** The persisted form. Stable across releases; the enum name is not the storage contract. */
  val key: String,
  /**
   * The wording, as resources rather than as text.
   *
   * This enum is a specification about *motion* - which speeds exist and what each multiplies by -
   * and the two lines a viewer reads are not part of that. Holding English here would pin the
   * Animation speed row to English on a translated device, and would make the one file that is
   * meant to be identical in both apps the file that decides how a setting reads.
   *
   * The two `R` imports are the only line on which the copies differ, alongside the package.
   */
  @StringRes val labelRes: Int,
  /** Multiplier applied to every duration in [MotionDuration]. */
  val scale: Float,
  @StringRes val descriptionRes: Int,
) {
  Off(
    key = "off",
    labelRes = R.string.animation_speed_off,
    scale = 0f,
    descriptionRes = R.string.animation_speed_off_description,
  ),
  Fast(
    key = "fast",
    labelRes = R.string.animation_speed_fast,
    scale = 0.7f,
    descriptionRes = R.string.animation_speed_fast_description,
  ),
  Standard(
    key = "standard",
    labelRes = R.string.animation_speed_standard,
    scale = 1f,
    descriptionRes = R.string.animation_speed_standard_description,
  ),
  Cinematic(
    key = "cinematic",
    labelRes = R.string.animation_speed_cinematic,
    scale = 1.45f,
    descriptionRes = R.string.animation_speed_cinematic_description,
  );

  companion object {
    val Default = Standard

    /** Accepts the stored key, the enum name, and the three legacy television values. */
    fun fromKey(key: String?): AnimationSpeed {
      val normalized = key?.trim()?.lowercase().orEmpty()
      if (normalized.isEmpty()) return Default
      return entries.firstOrNull { it.key == normalized || it.name.lowercase() == normalized }
        ?: when (normalized) {
          // The television shipped "slow"/"normal"/"fast" before this model existed.
          "slow" -> Cinematic
          "normal" -> Standard
          else -> Default
        }
    }
  }
}

/**
 * Semantic motion tokens: the base durations, in milliseconds, before the viewer's scale.
 *
 * Six names, because animations in an app fall into about six jobs and a longer list only invites
 * everyone to invent a seventh. Nothing outside this object should name a duration.
 */
object MotionDuration {
  /** Press and focus acknowledgement. Anything slower than this reads as a missed input. */
  const val instant = 90

  /** Small swaps in place: a badge changing, a label crossfading, a chip selecting. */
  const val short = 150

  /** The default. Content appearing, a panel expanding, most state changes. */
  const val standard = 250

  /** Whole screens, sheets, the player - larger travel needs longer to stay legible. */
  const val long = 380

  /** The offset between neighbours in a staggered sequence, per step. */
  const val stagger = 60

  /** One thing replacing another in the same place. */
  const val crossfade = 220

  /**
   * What a crossfade collapses to when motion is off, rather than to zero.
   *
   * An instant swap of a full-screen image is its own kind of jarring, and reduce-motion guidance
   * treats a short opacity change as the accessible replacement for movement, not as movement.
   */
  const val motionlessCrossfade = 90
}

/**
 * The viewer's choice, and the system setting that can overrule it.
 *
 * Read from [LocalMotionSettings] rather than constructed: one instance is provided at the top of
 * the app so every animation in the tree is working from the same answer.
 */
@Immutable
data class MotionSettings(
  /** What the viewer chose in Settings. */
  val speed: AnimationSpeed = AnimationSpeed.Default,
  /**
   * The device's own Reduce Motion preference.
   *
   * Android has no single switch for it; what it has is the animator duration scale, which is what
   * accessibility services and battery savers turn down and what motion-sensitive users are widely
   * advised to set to zero. A zero scale is taken as the request.
   *
   * It wins over [speed] outright. Somebody who has told the operating system they do not want
   * motion has said something more important than which flavour of motion they last picked in one
   * app, and Settings says so on the row rather than quietly ignoring the selection.
   */
  val systemReducedMotion: Boolean = false,
) {
  /** What actually applies, after the system override. */
  val effective: AnimationSpeed get() = if (systemReducedMotion) AnimationSpeed.Off else speed

  val scale: Float get() = effective.scale

  /** True when movement and scale must be dropped in favour of an immediate change or a crossfade. */
  val motionless: Boolean get() = effective == AnimationSpeed.Off

  /** True when the system setting is overruling a selection that would otherwise have animated. */
  val overriddenBySystem: Boolean get() = systemReducedMotion && speed != AnimationSpeed.Off

  /** A duration from [MotionDuration], at the viewer's speed. Zero when motion is off. */
  fun scaled(baseMillis: Int): Int = if (motionless) 0 else (baseMillis * scale).roundToInt()

  /** As [scaled], but never zero: see [MotionDuration.motionlessCrossfade]. */
  fun crossfade(baseMillis: Int = MotionDuration.crossfade): Int =
    if (motionless) MotionDuration.motionlessCrossfade else (baseMillis * scale).roundToInt()

  /** The per-step offset of a staggered sequence. Collapses to zero, landing every item together. */
  fun stagger(baseMillis: Int = MotionDuration.stagger): Int = scaled(baseMillis)
}

val LocalMotionSettings: ProvidableCompositionLocal<MotionSettings> =
  staticCompositionLocalOf { MotionSettings() }

// -------------------------------------------------------------------------------------------------
// Television-side storage. The specification above is shared with the phone; this half is not.
// -------------------------------------------------------------------------------------------------

/**
 * The selected speed, on this television.
 *
 * Deliberately *not* `AppPreferences.animationSpeed`, which is part of the account bootstrap and
 * therefore travels to every device on the household: see [AnimationSpeed] for why this one setting
 * stays put. The account field is left in the model for backward compatibility with older clients
 * still writing it, and is no longer read here.
 *
 * Follows [com.streamdek.tv.nativeapp.data.TvIdlePreferences], the app's existing pattern for
 * settings that describe the box in the room rather than the person using it.
 *
 * The current value is snapshot state, so choosing a new one recomposes the theme and every
 * animation in the tree picks it up on the spot - no restart, and no screen to back out of first.
 */
internal class TvAnimationPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "streamdek_tv_motion",
        Context.MODE_PRIVATE,
    )

    var speed: AnimationSpeed by mutableStateOf(
        AnimationSpeed.fromKey(preferences.getString(KEY, null)),
    )
        private set

    fun select(value: AnimationSpeed) {
        speed = value
        preferences.edit().putString(KEY, value.key).apply()
    }

    private companion object {
        const val KEY = "animation_speed"
    }
}

/**
 * The television's own reduce-motion signal.
 *
 * The same animator-duration-scale reading the phone uses, plus the account's "Reduced motion"
 * accessibility toggle, which predates this system and stays where it is: it is an accessibility
 * preference about the person, so unlike the speed it is reasonable for it to follow the account.
 * Either one being set means motion off.
 */
@Composable
internal fun rememberTvMotionSettings(
    preferences: TvAnimationPreferences,
    accountReducedMotion: Boolean,
): MotionSettings {
    val context = LocalContext.current
    val systemReduced = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) == 0f
    }
    val speed = preferences.speed
    return remember(speed, systemReduced, accountReducedMotion) {
        MotionSettings(speed = speed, systemReducedMotion = systemReduced || accountReducedMotion)
    }
}

/** So Settings can write the selection without it being threaded through every screen. */
internal val LocalTvAnimationPreferences = staticCompositionLocalOf<TvAnimationPreferences?> { null }
