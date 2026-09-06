package com.streamdek.tv.nativeapp.ui.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.data.StreamProfile
import com.streamdek.tv.nativeapp.ui.AnimationSpeed
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.MotionDuration
import com.streamdek.tv.nativeapp.ui.MotionSettings
import com.streamdek.tv.nativeapp.ui.ProfileAvatarCircle
import com.streamdek.tv.nativeapp.ui.TvChromePanel
import com.streamdek.tv.nativeapp.ui.TvMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The television's "Who's watching?" page and the loading state in front of it.
 *
 * The same choreography as the phone's picker, adapted to what this screen actually has: no hero
 * artwork here, so the sequence begins at the headline. Its counterpart lives in the mobile app at
 * `nativeapp/ProfilePicker.kt`; the two apps share no code, so the cue tables are deliberately
 * written the same way in both places rather than drifting into different ideas of the same
 * transition.
 */

// ---------------------------------------------------------------------------------------------
// Entrance choreography
// ---------------------------------------------------------------------------------------------

/** When each part of the page arrives, in milliseconds from the start of the reveal. */
private object TvPickerCue {
    const val HeadingStart = 0f
    const val HeadingDuration = 300f

    const val SubtitleStart = 90f
    const val SubtitleDuration = 300f

    const val CardsStart = 200f
    const val CardDuration = 320f
    const val CardStagger = 60f

    /** Past this many cards the stagger stops growing, so a long row does not become a queue. */
    const val MaxStaggeredCards = 4

    /**
     * The whole reveal at [AnimationSpeed.Standard]. Every number above is a base value, multiplied
     * by the viewer's chosen scale - so Fast lands this around 630ms and Cinematic around 1.3s, on
     * the same cues in the same order.
     */
    const val Total = 900f

    /**
     * Motion off: one short crossfade for the whole page, no travel and no scale.
     *
     * Twice [MotionDuration.motionlessCrossfade] rather than one, because the elements still overlap
     * on this timeline - it is a single fade for the page, not three of them end to end.
     */
    const val MotionlessTotal = MotionDuration.motionlessCrossfade * 2f

    /**
     * How far into a card's own entrance it is worth handing the remote control to it. Early
     * enough that nobody waits to press down, late enough that the highlight lands on something
     * they can already see.
     */
    const val FocusAt = 0.7f
}

/**
 * One clock for the whole page.
 *
 * Every animated part of the picker asks this object where it is rather than owning an animation
 * of its own, which is what keeps the headline, the subtitle and the cards reading as one scene
 * assembling. Read from inside `graphicsLayer` lambdas, so the reveal recomposes nothing, and it
 * cannot be restarted by a state change because nothing but this screen's entry creates it.
 */
@Stable
internal class TvProfilePickerReveal(
    /**
     * The viewer's animation speed and every reduce-motion signal, from [LocalTvExperienceSettings].
     *
     * Captured once, at entry. This timeline is a single choreographed run, and re-length-ing it
     * halfway through would be exactly the animation restart the page must not have.
     */
    private val motion: MotionSettings,
) {
    private val clock = Animatable(0f)

    val reducedMotion: Boolean get() = motion.motionless

    private val totalMs: Float
        get() = if (motion.motionless) TvPickerCue.MotionlessTotal else TvPickerCue.Total * motion.scale

    suspend fun run() {
        clock.animateTo(totalMs, tween(durationMillis = totalMs.toInt(), easing = LinearEasing))
    }

    /** Land everything at once. Called on the first button press, whatever it was aimed at. */
    suspend fun skip() {
        if (clock.value >= totalMs) return
        clock.snapTo(totalMs)
    }

    /** Eased 0..1 progress of one element's slice of the shared clock. */
    fun progress(startMs: Float, durationMs: Float): Float {
        val raw = if (motion.motionless) {
            clock.value / TvPickerCue.MotionlessTotal
        } else {
            (clock.value - startMs * motion.scale) / (durationMs * motion.scale)
        }
        return TvMotion.EnterEasing.transform(raw.coerceIn(0f, 1f))
    }

    fun settled(startMs: Float, durationMs: Float): Boolean =
        progress(startMs, durationMs) >= TvPickerCue.FocusAt
}

/** Opacity, a small upward settle and an optional scale, all read from the page's single clock. */
private fun Modifier.tvPickerCue(
    reveal: TvProfilePickerReveal,
    startMs: Float,
    durationMs: Float,
    rise: Dp = 16.dp,
    fromScale: Float = 1f,
): Modifier = graphicsLayer {
    val progress = reveal.progress(startMs, durationMs)
    alpha = progress
    if (!reveal.reducedMotion) {
        translationY = rise.toPx() * (1f - progress)
        if (fromScale != 1f) {
            val scale = fromScale + (1f - fromScale) * progress
            scaleX = scale
            scaleY = scale
        }
    }
}

private fun tvProfileCardCue(index: Int): Float =
    TvPickerCue.CardsStart + TvPickerCue.CardStagger * index.coerceAtMost(TvPickerCue.MaxStaggeredCards)

/**
 * A slow breath of light on the app's black, used both by the bootstrap gate and behind the picker
 * as it opens. Deliberately not a spinner: it reads as the screen being dark rather than as
 * something being broken, and it is the only thing the real content has to replace.
 */
@Composable
private fun TvAmbientGlow(modifier: Modifier = Modifier, reduced: Boolean, alpha: () -> Float = { 1f }) {
    val breathDuration = TvMotion.duration(2200).coerceAtLeast(1)
    val breath = if (reduced) {
        null
    } else {
        rememberInfiniteTransition(label = "tv_picker_glow").animateFloat(
            initialValue = 0.34f,
            targetValue = 0.72f,
            animationSpec = infiniteRepeatable(tween(breathDuration, easing = TvMotion.StandardEasing), RepeatMode.Reverse),
            label = "tv_picker_breath",
        )
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha() }
            .drawBehind {
                val strength = breath?.value ?: 0.5f
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.10f * strength), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.44f),
                        radius = size.maxDimension * 0.62f,
                    ),
                )
            },
    )
}

// ---------------------------------------------------------------------------------------------
// The page
// ---------------------------------------------------------------------------------------------

/**
 * Shown while account bootstrap is still deciding whether a profile picker is required.
 *
 * Home's first-load skeleton was otherwise visible for a frame before the picker, and the picker
 * itself must never appear half-populated - so the wordmark and this glow are the whole loading
 * state, and the picker's own reveal begins from the same black they are painted on.
 */
@Composable
internal fun StartupBootstrapGate() {
    val reduced = LocalTvExperienceSettings.current.motion.motionless
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        TvAmbientGlow(reduced = reduced)
        Text(
            "StreamDek",
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
            color = Color.White,
        )
    }
}

@Composable
internal fun StartupProfilePicker(
    profiles: List<StreamProfile>,
    activeProfileId: String?,
    switching: Boolean,
    onVerifyPin: suspend (StreamProfile, String) -> Boolean,
    onChoose: (StreamProfile) -> Unit,
) {
    val firstRequester = remember(profiles) { FocusRequester() }
    val pinRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val motion = LocalTvExperienceSettings.current.motion
    val reducedMotion = motion.motionless
    // Created once, when this screen enters composition, and never keyed on anything that changes
    // while it is open - so no later state change, focus move or image load can rewind it.
    val reveal = remember { TvProfilePickerReveal(motion) }
    var lockedProfile by remember { mutableStateOf<StreamProfile?>(null) }
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var checkingPin by remember { mutableStateOf(false) }
    var selectedProfileId by remember { mutableStateOf<String?>(null) }
    var focusClaimed by remember { mutableStateOf(false) }
    val avatarBounds = remember { mutableStateMapOf<String, Rect>() }
    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }
        ?: profiles.firstOrNull { it.id == activeProfileId }
        ?: profiles.firstOrNull()

    fun choose(profile: StreamProfile) {
        selectedProfileId = profile.id
        onChoose(profile)
    }

    BackHandler(enabled = true) { /* A profile is required before entering the app. */ }

    // The profiles are already in hand by the time this screen composes - the bootstrap gate above
    // is what waited for them - so there is nothing left to load and the reveal starts at once.
    LaunchedEffect(reveal) { reveal.run() }

    // Focus is handed over once, when the first card has carried far enough to be worth looking
    // at, and never again: the cards still arriving behind it must not pull the highlight around.
    LaunchedEffect(reveal, switching) {
        if (switching || focusClaimed || profiles.isEmpty()) return@LaunchedEffect
        snapshotFlow { reveal.settled(TvPickerCue.CardsStart, TvPickerCue.CardDuration) }.first { it }
        focusClaimed = true
        runCatching { firstRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Any button press lands the rest of the sequence immediately. Previewed and never
            // consumed, so the press still does whatever it was going to do - by the time the
            // remote's key-up arrives, the page is fully drawn.
            .onPreviewKeyEvent {
                scope.launch { reveal.skip() }
                false
            },
        contentAlignment = Alignment.Center,
    ) {
        TvAmbientGlow(
            reduced = reducedMotion,
            // The inverse of the headline's cue: the glow is gone by the time there is something
            // to read through it. Both sides of the handover run off the same clock.
            alpha = { 1f - reveal.progress(TvPickerCue.HeadingStart, TvPickerCue.HeadingDuration * 0.7f) },
        )
        Crossfade(
            targetState = switching,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(durationMillis = TvMotion.crossfadeDuration()),
            label = "profile-entry-transition",
        ) { enteringProfile ->
            if (enteringProfile && selectedProfile != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ProfileEntryTransition(
                        profile = selectedProfile,
                        startBounds = avatarBounds[selectedProfile.id],
                    )
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 96.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(30.dp),
                    ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.profiles_who_is_watching),
                            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
                            color = Color.White,
                            modifier = Modifier.tvPickerCue(reveal, TvPickerCue.HeadingStart, TvPickerCue.HeadingDuration),
                        )
                        Text(
                            stringResource(R.string.profiles_choose_for_tv),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.64f),
                            modifier = Modifier.tvPickerCue(reveal, TvPickerCue.SubtitleStart, TvPickerCue.SubtitleDuration, rise = 12.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(22.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        profiles.forEachIndexed { index, profile ->
                            Card(
                                onClick = {
                                    if (!switching) {
                                        if (profile.hasPinSet) {
                                            lockedProfile = profile
                                            pin = ""
                                            pinError = null
                                        } else {
                                            choose(profile)
                                        }
                                    }
                                },
                                modifier = Modifier
                                    // The entrance layer sits outside the card's own focus scale
                                    // and border, so the highlight is drawn at full strength the
                                    // moment focus lands rather than fading up with the card.
                                    .tvPickerCue(reveal, tvProfileCardCue(index), TvPickerCue.CardDuration, fromScale = 0.94f)
                                    .width(210.dp)
                                    .height(220.dp)
                                    .then(if (index == 0) Modifier.focusRequester(firstRequester) else Modifier),
                                colors = CardDefaults.colors(
                                    containerColor = Color.White.copy(alpha = 0.07f),
                                    focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                                ),
                                border = CardDefaults.border(
                                    focusedBorder = Border(
                                        androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                                        shape = RoundedCornerShape(18.dp),
                                    ),
                                ),
                                scale = CardDefaults.scale(focusedScale = 1.04f),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(22.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Box(
                                        modifier = Modifier.onGloballyPositioned { coordinates ->
                                            avatarBounds[profile.id] = coordinates.boundsInRoot()
                                        },
                                    ) {
                                        ProfileAvatarCircle(profile.avatarIndex, profile.name, 92.dp)
                                    }
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        profile.name,
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White,
                                    )
                                    Text(
                                        when {
                                            profile.hasPinSet -> stringResource(R.string.profile_pin_required)
                                            profile.id == activeProfileId -> stringResource(R.string.profile_last_used)
                                            profile.isDefault -> stringResource(R.string.profile_default)
                                            else -> stringResource(R.string.profile_ready_to_watch)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.58f),
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

    lockedProfile?.let { profile ->
        LaunchedEffect(profile.id) {
            delay(80)
            runCatching { pinRequester.requestFocus() }
        }
        Dialog(
            onDismissRequest = { if (!checkingPin) lockedProfile = null },
            properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color(0xC7000000)), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.42f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(TvChromePanel)
                        .padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text(
                        stringResource(R.string.profiles_enter_pin_for, profile.name),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { value -> pin = value.filter(Char::isDigit).take(4); pinError = null },
                        singleLine = true,
                        enabled = !checkingPin,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        label = { androidx.compose.material3.Text(stringResource(R.string.profiles_pin_hint)) },
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color.White.copy(alpha = 0.08f),
                            unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(pinRequester),
                    )
                    pinError?.let {
                        Text(it, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            enabled = pin.length == 4 && !checkingPin,
                            onClick = {
                                checkingPin = true
                                scope.launch {
                                    if (onVerifyPin(profile, pin)) {
                                        lockedProfile = null
                                        choose(profile)
                                    } else {
                                        pinError = "That PIN is incorrect."
                                        pin = ""
                                    }
                                    checkingPin = false
                                }
                            },
                        ) { Text(if (checkingPin) stringResource(R.string.credential_status_checking) else stringResource(R.string.action_continue)) }
                        OutlinedButton(
                            enabled = !checkingPin,
                            onClick = { lockedProfile = null },
                        ) { Text(stringResource(R.string.action_back)) }
                    }
                }
            }
        }
    }
}

/** Moves the chosen portrait from its card into the centre while the profile bootstrap refreshes. */
@Composable
private fun ProfileEntryTransition(profile: StreamProfile, startBounds: Rect?) {
    // Read in composition: the LaunchedEffect below is not composable scope.
    val travelMillis = TvMotion.duration(TvMotion.Expand)
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val progress = remember(profile.id) { Animatable(0f) }
    val screenCenterX = with(density) { configuration.screenWidthDp.dp.toPx() / 2f }
    val screenCenterY = with(density) { configuration.screenHeightDp.dp.toPx() / 2f }
    val startOffsetX = startBounds?.center?.x?.minus(screenCenterX) ?: 0f
    val startOffsetY = startBounds?.center?.y?.minus(screenCenterY) ?: 0f

    LaunchedEffect(profile.id, startBounds) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = travelMillis, easing = FastOutSlowInEasing),
        )
    }

    val amount = progress.value
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val ringSize = lerp(92.dp, 144.dp, amount)
        val portraitSize = lerp(92.dp, 116.dp, amount)
        Box(
            modifier = Modifier
                .size(ringSize)
                .graphicsLayer {
                    translationX = startOffsetX * (1f - amount)
                    translationY = startOffsetY * (1f - amount)
                },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = amount },
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
            ProfileAvatarCircle(profile.avatarIndex, profile.name, portraitSize)
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = amount
                translationY = 12.dp.toPx() * (1f - amount)
            },
        ) {
            Text(
                stringResource(R.string.profiles_welcome_back),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.62f),
            )
            Text(
                profile.name,
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
                color = Color.White,
            )
        }
    }
}
