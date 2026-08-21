package com.streamdek.tv.nativeapp.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clipToBounds
import com.streamdek.tv.nativeapp.ui.TvScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.streamdek.tv.nativeapp.AppGraph
import com.streamdek.tv.nativeapp.data.DefaultTrailerCacheClearHours
import com.streamdek.tv.nativeapp.data.LiveCatalogSection
import com.streamdek.tv.nativeapp.data.TrailerCache
import com.streamdek.tv.nativeapp.data.TrailerCacheClearHourOfDay
import com.streamdek.tv.nativeapp.data.clearTrailerState
import com.streamdek.tv.nativeapp.data.mapAddonCatalogType
import com.streamdek.tv.nativeapp.data.PlaybackHandoff
import com.streamdek.tv.nativeapp.data.PlaybackRequest
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.StreamProfile
import com.streamdek.tv.nativeapp.ui.account.SettingsScreen
import com.streamdek.tv.nativeapp.ui.auth.AuthScreen
import com.streamdek.tv.nativeapp.ui.detail.DetailScreen
import com.streamdek.tv.nativeapp.ui.detail.PlaybackStreamsScreen
import com.streamdek.tv.nativeapp.ui.home.HomeScreen
import com.streamdek.tv.nativeapp.ui.library.LibraryScreen
import com.streamdek.tv.nativeapp.ui.live.LiveScreen
import com.streamdek.tv.nativeapp.ui.live.LiveBrowseScreen
import com.streamdek.tv.nativeapp.ui.network.NetworkBrowseScreen
import com.streamdek.tv.nativeapp.ui.player.PlayerScreen
import com.streamdek.tv.nativeapp.ui.search.SearchScreen
import com.streamdek.tv.nativeapp.update.AppUpdateManager
import com.streamdek.tv.nativeapp.update.AppUpdateUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class TopLevelDestination(
    val route: String,
    val label: String,
    val width: androidx.compose.ui.unit.Dp,
    val icon: ImageVector?,
) {
    Home("home", "Home", 92.dp, Icons.Outlined.Home),
    Search("search", "Search", 98.dp, Icons.Outlined.Search),
    Live("live", "Live", 82.dp, Icons.Outlined.LiveTv),
    Library("library", "Library", 104.dp, Icons.Outlined.VideoLibrary),
    Profile("profile", "Profile", 42.dp, null),
}

private data class LiveNavigationState(
    val loading: Boolean = true,
    val sections: List<LiveCatalogSection> = emptyList(),
    /** What the load is doing, shown while the page has nothing yet. Null once it is done. */
    val statusMessage: String? = null,
    val progress: Float? = null,
)

private data class LiveBrowseSelection(
    val addonId: String? = null,
    val catalogId: String? = null,
)
private const val ExitBackPressWindowMs = 2500L

/**
 * How far the expanded navigation rail is allowed to let the backdrop through.
 *
 * The ceiling is the point, not the value: expanded, the rail is a menu being read, and any further
 * and it stops reading as a surface at all against bright artwork. Collapsed it has no surface at
 * all — see [TvSideNav].
 */
private const val NavRailTransparentAlpha = 0.85f

/** Presses that mean a viewer is using the menu rather than passing through it. */
private val RailInteractionKeys = setOf(
    Key.DirectionUp,
    Key.DirectionDown,
    Key.DirectionLeft,
    Key.DirectionCenter,
    Key.Enter,
    Key.NumPadEnter,
)

/** The navigation route the title page is registered under, kept in one place. */
private const val DetailRoutePattern = "detail/{type}/{id}"
private const val PersonRoutePattern = "person/{id}"

private fun detailRoute(mediaType: String, mediaId: String): String {
    val canonicalType = if (mediaType == "series") "tv" else mediaType
    return "detail/$canonicalType/$mediaId"
}
@Composable
fun StreamDekTvApp(repository: StreamDekRepository = remember { AppGraph.repository }) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val activity = context as? Activity
    val appUpdateManager = remember { AppGraph.appUpdateManager }
    val session by repository.session.collectAsState()
    val bootstrap by repository.bootstrap.collectAsState()
    val sessionExpired by repository.sessionExpired.collectAsState()
    val appUpdateState by appUpdateManager.uiState.collectAsState()
    val favouriteChannels by repository.favouriteChannels.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val activeProfile = repository.activeStreamProfile(bootstrap)
    val appPrefs = bootstrap?.preferences?.app
    // Live presentation is synced with mobile under `home`, not `app`.
    val homePrefs = bootstrap?.preferences?.home
    val homeContentRequester = remember { FocusRequester() }
    val searchContentRequester = remember { FocusRequester() }
    val liveContentRequester = remember { FocusRequester() }
    val libraryContentRequester = remember { FocusRequester() }
    // The two screens that gained the rail: they need somewhere for it to hand focus back to.
    val liveBrowseContentRequester = remember { FocusRequester() }
    val networkContentRequester = remember { FocusRequester() }
    val profileNavRequester = remember { FocusRequester() }
    val settingsContentRequester = remember { FocusRequester() }
    /** The title page's own way back in from the rail, and the rail's way of being reached. */
    val detailContentRequester = remember { FocusRequester() }
    val personContentRequester = remember { FocusRequester() }
    val navRailRequester = remember { FocusRequester() }
    var liveNavigationState by remember { mutableStateOf(LiveNavigationState()) }
    var loadedLiveCatalogKey by remember { mutableStateOf<String?>(null) }
    var liveBrowseSelection by remember { mutableStateOf(LiveBrowseSelection()) }
    var handledHandoffId by remember(session?.user?.uid) { mutableStateOf<String?>(null) }
    var pendingHandoff by remember(session?.user?.uid) { mutableStateOf<PlaybackHandoff?>(null) }
    var handoffProcessing by remember(session?.user?.uid) { mutableStateOf(false) }
    var handoffError by remember(session?.user?.uid) { mutableStateOf<String?>(null) }
    val handoffScope = rememberCoroutineScope()
    val startupProfileScope = rememberCoroutineScope()
    var startupProfileHandled by remember(session?.user?.uid) { mutableStateOf(false) }
    var startupProfileSwitching by remember(session?.user?.uid) { mutableStateOf(false) }
    var startupBootstrapResolved by remember(session?.user?.uid) {
        mutableStateOf(session == null || bootstrap != null)
    }
    val startupProfiles = bootstrap?.streamProfiles.orEmpty()
    val showStartupProfilePicker = session != null &&
        bootstrap != null &&
        startupProfiles.isNotEmpty() &&
        !repository.rememberLastProfileAtStartup() &&
        !startupProfileHandled
    val liveAddonKey = remember(bootstrap) {
        bootstrap?.integrations?.addons?.items.orEmpty().joinToString("|") {
            "${it.id}:${it.enabled}:${it.position}:${it.manifest.catalogs.size}"
        }
    }

    // A lapsed sign-in used to show up as screens that were simply empty forever. Send the viewer
    // somewhere they can act instead.
    LaunchedEffect(sessionExpired) {
        if (!sessionExpired || session == null) return@LaunchedEffect
        TvDebugLogger.w("Auth", "stored sign-in rejected; returning to the sign-in screen")
        repository.signOut()
        navController.navigate("auth") {
            launchSingleTop = true
        }
    }

    LaunchedEffect(session?.user?.uid) {
        while (session != null) {
            if (pendingHandoff == null) {
                runCatching { repository.fetchPendingHandoff() }
                    .onSuccess { handoff ->
                        if (handoff != null && handoff.id != handledHandoffId) {
                            pendingHandoff = handoff
                            handoffError = null
                        }
                    }
                    .onFailure { TvDebugLogger.e("Handoff", "Could not poll for a pending handoff", it) }
            }
            delay(3_000L)
        }
    }
    LaunchedEffect(session?.user?.uid, activeProfile?.id, liveAddonKey, currentRoute) {
        if (currentRoute != TopLevelDestination.Live.route || session == null || bootstrap == null) {
            return@LaunchedEffect
        }
        val catalogKey = "${session?.user?.uid}:${activeProfile?.id}:$liveAddonKey"
        if (loadedLiveCatalogKey == catalogKey && liveNavigationState.sections.isNotEmpty()) {
            return@LaunchedEffect
        }
        liveNavigationState = liveNavigationState.copy(loading = true, statusMessage = null, progress = null)
        val sections = runCatching {
            repository.fetchLiveCatalogSections { progress ->
                liveNavigationState = liveNavigationState.copy(
                    statusMessage = progress.message,
                    progress = progress.fraction,
                )
            }
        }.getOrDefault(emptyList())
        loadedLiveCatalogKey = catalogKey
        liveNavigationState = LiveNavigationState(
            loading = false,
            sections = sections,
        )
    }

    // An enabled addon that publishes a live catalog is enough to surface the Live tab.
    // Waiting for catalog items to load made the tab appear late, or not at all when a
    // single catalog request failed.
    val favouriteChannelKeys = remember(favouriteChannels) {
        favouriteChannels.mapTo(linkedSetOf()) { "${it.sourceAddonId}:${it.sourceCatalogId}:${it.id}" }
    }
    val hasEnabledLiveAddon = remember(bootstrap) {
        bootstrap?.integrations?.addons?.items.orEmpty().any { addon ->
            addon.enabled && addon.manifest.catalogs.any { catalog ->
                mapAddonCatalogType(catalog.type.trim().lowercase(java.util.Locale.US)) == "live"
            }
        }
    }
    val hasLoadedLiveContent = liveNavigationState.sections.any { section ->
        section.rails.any { rail -> rail.items.isNotEmpty() }
    }
    val showLiveDestination = hasEnabledLiveAddon || hasLoadedLiveContent
    val topLevelDestinations = remember(showLiveDestination) {
        buildList {
            add(TopLevelDestination.Home)
            add(TopLevelDestination.Search)
            if (showLiveDestination) add(TopLevelDestination.Live)
            add(TopLevelDestination.Library)
            add(TopLevelDestination.Profile)
        }
    }
    val preferredStartRoute = when (appPrefs?.startScreen) {
        TopLevelDestination.Library.route,
        "continue-watching" -> TopLevelDestination.Library.route
        TopLevelDestination.Search.route -> TopLevelDestination.Search.route
        TopLevelDestination.Live.route -> if (showLiveDestination) TopLevelDestination.Live.route else TopLevelDestination.Home.route
        TopLevelDestination.Profile.route -> TopLevelDestination.Profile.route
        else -> TopLevelDestination.Home.route
    }
    var startScreenApplied by remember(session?.user?.uid) { mutableStateOf(false) }
    var exitHintVisible by remember { mutableStateOf(false) }
    var lastExitBackPressAt by remember { mutableStateOf(0L) }
    var previousRoute by remember { mutableStateOf<String?>(null) }
    var lastLiveFocusedItemKey by remember { mutableStateOf<String?>(null) }
    var liveFocusRestoreToken by remember { mutableStateOf(0) }
    // Home position is held here, above the NavHost, so it survives the Home
    // destination being disposed while the viewer is on another screen.
    var lastHomeRowId by remember { mutableStateOf<String?>(null) }
    var lastHomeItemKey by remember { mutableStateOf<String?>(null) }
    var homeFocusRestoreToken by remember { mutableStateOf(0) }
    var pendingDestinationFocus by remember { mutableStateOf<String?>(null) }
    // The rail is always composed on browse routes. During a pop the returning screen may not
    // have a focus target until its content is ready, so Android's spatial fallback briefly
    // focuses (and opens) the persistent rail. Suppress only that transition-time expansion;
    // never remove the rail as a valid focus target for normal remote navigation.
    var railFocusHandoffPending by remember { mutableStateOf(false) }
    // The route whose own content last held focus.
    //
    // The window above is bounded, and it has to be — but a screen that takes longer than it to
    // produce something focusable left the rail holding focus, and holding focus is what opens the
    // rail. So every Back press onto a slow screen played the whole expansion and then closed it
    // again the moment the content arrived. Expansion now waits on the arriving screen having had
    // focus rather than on a timer: pressing left out of a page into the rail is deliberate, being
    // handed focus because there was nowhere else to put it is not, and the two are only
    // distinguishable by whether the page ever had it.
    var contentFocusedRoute by remember { mutableStateOf<String?>(null) }
    var detailNavigationInProgress by remember { mutableStateOf(false) }
    val openDetail: (String, String) -> Unit = { mediaType, mediaId ->
        detailNavigationInProgress = true
        navController.navigate(detailRoute(mediaType, mediaId))
    }

    LaunchedEffect(currentRoute) {
        if (currentRoute != DetailRoutePattern && detailNavigationInProgress) {
            detailNavigationInProgress = false
        }
    }

    LaunchedEffect(session?.user?.uid) {
        startupBootstrapResolved = session == null || bootstrap != null
        if (session != null) {
            try {
                runCatching { repository.refreshBootstrap() }
                    .onFailure { TvDebugLogger.e("Bootstrap", "Could not refresh account bootstrap", it) }
            } finally {
                // A failed bootstrap must not trap the viewer on this gate forever. It only owns
                // the interval in which the profile-picker decision is genuinely unresolved.
                startupBootstrapResolved = true
            }
        }
    }

    // A television is left running for hours, so a change made on the phone or the web portal
    // should reach it while it sits there rather than waiting for the next cold start.
    LaunchedEffect(session?.user?.uid) {
        if (session == null) return@LaunchedEffect
        repository.watchProfilePlugins(this)
    }

    LaunchedEffect(Unit) {
        // Defer non-critical OTA work until the shell has painted and the user
        // has had a chance to begin navigating.
        delay(3500)
        appUpdateManager.runAutomaticCheck()
    }

    // Trailer housekeeping, once the bootstrap has said how often the household wants it.
    //
    // A television is left on standby for weeks rather than restarted, so the YouTube cookies and
    // site storage the embed accumulates are exactly the kind of state that goes stale unnoticed —
    // and the symptom is trailers that stop playing, with nothing on screen to explain it.
    // Deliberately on the main thread and after the shell has painted: WebView and CookieManager
    // are main-thread only, and this is housekeeping rather than anything the viewer is waiting on.
    LaunchedEffect(bootstrap?.preferences?.detail?.trailerCacheClearHours) {
        delay(5000)
        val hours = bootstrap?.preferences?.detail?.trailerCacheClearHours ?: DefaultTrailerCacheClearHours
        if (TrailerCache.isClearDue(context, hours, TrailerCacheClearHourOfDay)) {
            clearTrailerState(context, "scheduled every ${hours}h")
        }
    }

    LaunchedEffect(preferredStartRoute, currentRoute, startScreenApplied, showStartupProfilePicker) {
        if (!showStartupProfilePicker && !startScreenApplied && currentRoute == TopLevelDestination.Home.route) {
            startScreenApplied = true
            if (preferredStartRoute != TopLevelDestination.Home.route) {
                pendingDestinationFocus = preferredStartRoute
                navController.navigate(preferredStartRoute) {
                    popUpTo(TopLevelDestination.Home.route) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    LaunchedEffect(showLiveDestination, liveNavigationState.loading, currentRoute) {
        // Only evict the viewer from the Live tab once loading has settled and there is
        // genuinely no live content. Bouncing on a transient empty result used to throw
        // the viewer back to Home mid-browse.
        if (!showLiveDestination &&
            !liveNavigationState.loading &&
            currentRoute == TopLevelDestination.Live.route
        ) {
            pendingDestinationFocus = TopLevelDestination.Home.route
            navController.navigate(TopLevelDestination.Home.route) {
                popUpTo(TopLevelDestination.Home.route) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(currentRoute) {
        if (currentRoute == TopLevelDestination.Live.route && previousRoute == "player") {
            liveFocusRestoreToken += 1
        }
        // Returning to Home from anywhere else restores the last highlighted card.
        if (currentRoute == TopLevelDestination.Home.route &&
            previousRoute != null &&
            previousRoute != TopLevelDestination.Home.route &&
            lastHomeRowId != null
        ) {
            homeFocusRestoreToken += 1
        }
        if (exitHintVisible) {
            exitHintVisible = false
        }
        lastExitBackPressAt = 0L
        previousRoute = currentRoute
    }

    val destinationContentRequesters = remember {
        mapOf(
            TopLevelDestination.Home.route to homeContentRequester,
            TopLevelDestination.Search.route to searchContentRequester,
            TopLevelDestination.Live.route to liveContentRequester,
            TopLevelDestination.Library.route to libraryContentRequester,
            TopLevelDestination.Profile.route to settingsContentRequester,
            DetailRoutePattern to detailContentRequester,
            PersonRoutePattern to personContentRequester,
            "live-view-all" to liveBrowseContentRequester,
            "network/{id}/{name}" to networkContentRequester,
        )
    }
    LaunchedEffect(currentRoute, pendingDestinationFocus, showStartupProfilePicker) {
        val route = currentRoute
        if (route == null || showStartupProfilePicker) {
            railFocusHandoffPending = false
            return@LaunchedEffect
        }
        if (pendingDestinationFocus != null && pendingDestinationFocus != route) {
            railFocusHandoffPending = true
            return@LaunchedEffect
        }
        val requester = destinationContentRequesters[route]
        if (requester == null) {
            railFocusHandoffPending = false
            return@LaunchedEffect
        }

        // This runs for every route change, including plain popBackStack calls from nested
        // screens. The short bounded retry window covers the spatial-focus fallback that occurs
        // while the returning destination is being attached. It must remain bounded: some entry
        // requesters legitimately reject programmatic focus (or the content is already focused),
        // and that must never leave the navigation rail permanently disabled.
        railFocusHandoffPending = true
        try {
            repeat(7) { attempt ->
                delay(if (attempt == 0) 32L else 80L)
                val accepted = runCatching { requester.requestFocus() }.getOrDefault(false) == true
                if (accepted) {
                    pendingDestinationFocus = null
                    return@LaunchedEffect
                }
            }
            pendingDestinationFocus = null
        } finally {
            // Focusability is never gated, and expansion suppression always has a bounded exit.
            railFocusHandoffPending = false
        }
    }

    LaunchedEffect(exitHintVisible) {
        if (!exitHintVisible) return@LaunchedEffect
        delay(ExitBackPressWindowMs)
        exitHintVisible = false
        lastExitBackPressAt = 0L
    }

    // Do not compose Home while account bootstrap is still deciding whether a profile picker is
    // required. Its first-load skeleton was otherwise visible for a frame before the picker.
    if (!startupBootstrapResolved) {
        StartupBootstrapGate()
        return
    }

    // This is a startup destination, not a dialog over Home. Keeping the NavHost composed behind
    // it lets Home's late row loads request focus after the picker has appeared, leaving the
    // highlight somewhere invisible underneath. Return here so profile cards are the only focus
    // targets in the window until one is chosen.
    if (showStartupProfilePicker) {
        StartupProfilePicker(
            profiles = startupProfiles,
            activeProfileId = activeProfile?.id,
            switching = startupProfileSwitching,
            onVerifyPin = { profile, pin -> repository.verifyProfilePin(profile.id, pin) },
            onChoose = { profile ->
                if (!startupProfileSwitching) {
                    startupProfileSwitching = true
                    startupProfileScope.launch {
                        val transitionStartedAt = System.currentTimeMillis()
                        repository.setActiveStreamProfile(profile.id)
                        repository.refreshBootstrap()
                        val remainingTransitionMs = 520L - (System.currentTimeMillis() - transitionStartedAt)
                        if (remainingTransitionMs > 0L) delay(remainingTransitionMs)
                        startupProfileHandled = true
                        startupProfileSwitching = false
                    }
                }
            },
        )
        return
    }

    val showUpdatePrompt =
        currentRoute != "player" &&
            currentRoute != "streams" &&
            appUpdateState.showPrompt &&
            appUpdateState.availableRelease != null

    val playLiveItem: (com.streamdek.tv.nativeapp.data.MediaItem) -> Unit = { item ->
        repository.savePlaybackRequest(
            PlaybackRequest(
                mediaId = item.id,
                mediaType = "live",
                title = item.title,
                streamType = item.streamType,
                sourceAddonId = item.sourceAddonId,
                sourceAddonName = item.sourceAddonName,
                sourceCatalogId = item.sourceCatalogId,
                sourceCatalogName = item.sourceCatalogName,
                directStreamUrl = item.directStreamUrl,
                requestHeaders = item.requestHeaders,
            ),
        )
        navController.navigate("player")
    }

    val resumeContinueWatching: (com.streamdek.tv.nativeapp.data.MediaItem) -> Unit = { item ->
        repository.savePlaybackRequest(
            PlaybackRequest(
                mediaId = item.id,
                mediaType = item.type,
                episode = item.episode,
                title = item.title,
                returnToDetailOnBack = true,
            ),
        )
        navController.navigate("player")
    }
    BackHandler(enabled = !showUpdatePrompt && currentRoute != "player") {
        val isTopLevelRoute = currentRoute in topLevelDestinations.map { it.route }
        when {
            isTopLevelRoute && currentRoute != TopLevelDestination.Home.route -> {
                pendingDestinationFocus = TopLevelDestination.Home.route
                navController.navigate(TopLevelDestination.Home.route) {
                    popUpTo(TopLevelDestination.Home.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
            navController.popBackStack() -> Unit
            else -> {
                val now = System.currentTimeMillis()
                if (exitHintVisible && now - lastExitBackPressAt <= ExitBackPressWindowMs) {
                    activity?.finish()
                } else {
                    lastExitBackPressAt = now
                    exitHintVisible = true
                }
            }
        }
    }

    StreamDekTvTheme(appPreferences = appPrefs) {
        // Screen transitions, stated once for the whole graph. Navigation's defaults slide a full
        // screen of artwork sideways, which on a stick is a lot of pixels to push and reads as a
        // lurch; and nothing here is laid out side by side, so sideways was never the right
        // metaphor. Going deeper grows very slightly out of the screen and going back settles into
        // it, which matches what actually happened. Reduced motion collapses these to nothing,
        // since the durations come from TvMotion.
        val forwardScaleIn = 0.97f
        val backScaleOut = 0.98f
        val screenEnter = androidx.compose.animation.fadeIn(TvMotion.enterSpec()) +
            androidx.compose.animation.scaleIn(TvMotion.enterSpec(), initialScale = forwardScaleIn)
        val screenExit = androidx.compose.animation.fadeOut(TvMotion.exitSpec())
        val screenPopEnter = androidx.compose.animation.fadeIn(TvMotion.enterSpec()) +
            androidx.compose.animation.scaleIn(TvMotion.enterSpec(), initialScale = 1.02f)
        val screenPopExit = androidx.compose.animation.fadeOut(TvMotion.exitSpec()) +
            androidx.compose.animation.scaleOut(TvMotion.exitSpec(), targetScale = backScaleOut)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            // Set by whichever screen has taken the display — today the title page, while a trailer
            // is playing over it. The shell's own furniture stands down for the duration.
            var immersiveContent by remember { mutableStateOf(false) }
            // The shell's furniture leaves on the same curve the screen underneath is using, rather
            // than blinking out from over a page that is still fading. One value for both pieces,
            // so the clock and the rail go together.
            val chromeAlpha by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (immersiveContent) 0f else 1f,
                animationSpec = TvMotion.standardSpec(TvMotion.Expand),
                label = "app-chrome",
            )
            // A title page is somewhere you browse, not somewhere you commit to: you arrive on it
            // from a row, decide against it, and want to be somewhere else. Without the rail the
            // only way out was Back, and only back the way you came. It still stays off the player
            // and the stream picker, where it would sit over the picture or over a decision.
            // The rail is furniture, not a feature of certain screens.
            //
            // It used to be listed on per route, which meant a viewer three screens deep — a
            // network's catalogue, all of Live — had no way to anywhere except back the way they
            // came. Every browsing screen now carries it, and the exclusions below are the screens
            // where it would be in the way rather than the ones nobody thought to add.
            //
            // Off the player, where it would sit over the picture. Off the stream picker, which is
            // a decision to make rather than a place to browse from. Off a cast page, whose whole
            // width is one person's work and which is reached from a title rather than from the
            // menu. Off the sign-in screen, which is not somewhere to navigate away from.
            val railRoutes = remember {
                topLevelDestinations.map { it.route } +
                    listOf(DetailRoutePattern, "live-view-all", "network/{id}/{name}")
            }
            val railOnScreen = currentRoute in railRoutes && !detailNavigationInProgress &&
                !showUpdatePrompt && chromeAlpha > 0.001f
            CompositionLocalProvider(
                LocalImmersiveContent provides { active -> immersiveContent = active },
                LocalNavRailFocus provides navRailRequester.takeIf { railOnScreen },
            ) {
            NavHost(
                navController = navController,
                startDestination = TopLevelDestination.Home.route,
                // Insets belong to individual destinations below. If this shared NavHost changes
                // padding when the route changes, the outgoing page moves before its exit fade.
                //
                // One observer for every destination there will ever be: whatever a screen does to
                // place its own first focus, the shell only needs to know that the focus landed
                // inside the page rather than on the furniture around it.
                modifier = Modifier
                    .fillMaxSize()
                    .onFocusChanged { if (it.hasFocus) contentFocusedRoute = currentRoute },
                enterTransition = { screenEnter },
                exitTransition = { screenExit },
                popEnterTransition = { screenPopEnter },
                popExitTransition = { screenPopExit },
            ) {
                composable(TopLevelDestination.Home.route) {
                    HomeScreen(
                        repository = repository,
                        entryFocusRequester = homeContentRequester,
                        onOpenDetail = openDetail,
                        onOpenNetwork = { networkId, networkName ->
                            navController.navigate("network/$networkId/${Uri.encode(networkName)}")
                        },
                        onOpenAccount = {
                            navController.navigate(TopLevelDestination.Profile.route)
                        },

                        onPlayLive = playLiveItem,
                        onResumePlayback = resumeContinueWatching,

                        restoreRowId = lastHomeRowId,
                        restoreItemKey = lastHomeItemKey,
                        restoreToken = homeFocusRestoreToken,
                        onPositionChanged = { rowId, itemKey ->
                            lastHomeRowId = rowId
                            lastHomeItemKey = itemKey
                        },
                    )
                }
                composable(PersonRoutePattern) { backStackEntryInner ->
                    com.streamdek.tv.nativeapp.ui.detail.CastDetailScreen(
                        repository = repository,
                        personId = backStackEntryInner.arguments?.getString("id").orEmpty(),
                        entryFocusRequester = personContentRequester,
                        onBack = { navController.popBackStack() },
                        onOpenDetail = openDetail,
                    )
                }
                composable(TopLevelDestination.Search.route) {
                    RailInsetDestination {
                    SearchScreen(
                        repository = repository,
                        entryFocusRequester = searchContentRequester,
                        onOpenDetail = openDetail,
                        onPlayLive = playLiveItem,
                    )
                    }
                }
                composable(TopLevelDestination.Live.route) {
                    RailInsetDestination {
                    LiveScreen(
                        sections = liveNavigationState.sections,
                        isLoading = liveNavigationState.loading,
                        loadingStatus = liveNavigationState.statusMessage,
                        loadingProgress = liveNavigationState.progress,
                        compactMode = appPrefs?.compactMode == true,
                        landscapeCards = homePrefs?.liveLandscapeCards != false,
                        categoriesEnabled = homePrefs?.liveCategoriesEnabled != false,
                        entryFocusRequester = liveContentRequester,
                        restoreFocusedItemKey = lastLiveFocusedItemKey,
                        restoreFocusToken = liveFocusRestoreToken,
                        favouriteKeys = favouriteChannelKeys,
                        onItemFocused = { key ->
                            lastLiveFocusedItemKey = key
                        },
                        onToggleFavourite = repository::toggleFavouriteChannel,
                        onViewAll = { addonId, railId ->
                            val rail = liveNavigationState.sections.flatMap { it.rails }.firstOrNull { it.id == railId }
                            liveBrowseSelection = LiveBrowseSelection(
                                addonId = addonId,
                                catalogId = rail?.items?.firstOrNull()?.sourceCatalogId,
                            )
                            navController.navigate("live-view-all")
                        },
                        onPlayLive = playLiveItem,
                    )
                    }
                }
                composable("live-view-all") {
                    RailInsetDestination {
                    LiveBrowseScreen(
                        sections = liveNavigationState.sections,
                        initialAddonId = liveBrowseSelection.addonId,
                        initialCatalogId = liveBrowseSelection.catalogId,
                        favouriteKeys = favouriteChannelKeys,
                        entryFocusRequester = liveBrowseContentRequester,
                        onToggleFavourite = repository::toggleFavouriteChannel,
                        onPlayLive = playLiveItem,
                        onBack = { navController.popBackStack() },
                    )
                    }
                }
                composable(TopLevelDestination.Library.route) {
                    RailInsetDestination {
                    LibraryScreen(
                        repository = repository,
                        entryFocusRequester = libraryContentRequester,
                        onOpenDetail = openDetail,
                    )
                    }
                }
                composable(TopLevelDestination.Profile.route) {
                    RailInsetDestination {
                    SettingsScreen(
                        repository = repository,
                        appUpdateManager = appUpdateManager,
                        navFocusRequester = profileNavRequester,
                        entryFocusRequester = settingsContentRequester,
                        onSignIn = { navController.navigate("auth") },
                    )
                    }
                }
                composable("network/{id}/{name}") { backStackEntryInner ->
                    RailInsetDestination {
                    NetworkBrowseScreen(
                        repository = repository,
                        networkId = backStackEntryInner.arguments?.getString("id").orEmpty(),
                        networkName = Uri.decode(backStackEntryInner.arguments?.getString("name").orEmpty()),
                        entryFocusRequester = networkContentRequester,
                        onBack = { navController.popBackStack() },
                        onOpenDetail = openDetail,
                    )
                    }
                }
                composable("auth") {
                    AuthScreen(
                        repository = repository,
                        onBack = { navController.popBackStack() },
                        onSignedIn = {
                            navController.popBackStack()
                        },
                    )
                }
                composable("player") {
                    // Read once per entry. The request is a plain field on the repository, so a
                    // recomposition — of which there are several during the transition in — would
                    // otherwise pick up whatever was written most recently and hand the player a
                    // different object than the one it started with.
                    val request = remember { repository.consumePlaybackRequest() }
                    if (request == null) {
                        navController.popBackStack()
                    } else {
                        PlayerScreen(
                            repository = repository,
                            request = request,
                            onBack = { navController.popBackStack() },
                            onExitToStreams = {
                                if (!navController.popBackStack("streams", inclusive = false)) {
                                    repository.savePlaybackRequest(request)
                                    navController.navigate("streams") {
                                        popUpTo(TopLevelDestination.Home.route) { inclusive = false }
                                        launchSingleTop = true
                                    }
                                }
                            },
                        )
                    }
                }
                composable("streams") {
                    // Selecting a stream writes a new request and navigates away, but this screen
                    // stays composed through the transition. Re-reading the repository here handed
                    // it that new request, whose identity keys the whole screen — so the list it
                    // was already showing was thrown away and rebuilt as skeletons on the way out,
                    // and the viewer saw the search start over instead of the player opening.
                    val request = remember { repository.currentPlaybackRequest() }
                    if (request == null) {
                        navController.popBackStack()
                    } else {
                        PlaybackStreamsScreen(
                            repository = repository,
                            request = request,
                            onBack = { navController.popBackStack() },
                            onPlayRequest = { selectedRequest ->
                                repository.savePlaybackRequest(selectedRequest)
                                navController.navigate("player")
                            },
                        )
                    }
                }
                composable(DetailRoutePattern) { backStackEntryInner ->
                    DetailScreen(
                        repository = repository,
                        mediaType = backStackEntryInner.arguments?.getString("type").orEmpty(),
                        mediaId = backStackEntryInner.arguments?.getString("id").orEmpty(),
                        entryFocusRequester = detailContentRequester,
                        onBack = { navController.popBackStack() },
                        onOpenDetail = openDetail,
                        onContentReady = { detailNavigationInProgress = false },
                        onOpenPerson = { personId -> navController.navigate("person/${Uri.encode(personId)}") },
                        onPlay = { request: PlaybackRequest ->
                            val preferences = bootstrap?.preferences
                            val useAutoSelection = preferences?.streams?.showStreamsList == false ||
                                preferences?.playback?.manualStreamSelectionEnabled == false
                            repository.savePlaybackRequest(
                                if (useAutoSelection) request.copy(returnToDetailOnBack = true) else request.copy(returnToDetailOnBack = false)
                            )
                            if (useAutoSelection) {
                                navController.navigate("player")
                            } else {
                                navController.navigate("streams")
                            }
                        },
                        onRequireAuth = {
                            navController.navigate("auth")
                        },
                    )
                }
            }
            }

            if (chromeAlpha > 0.001f) {
                CurrentTimePill(
                    modifier = Modifier
                        .align(if (currentRoute == "player") Alignment.TopStart else Alignment.TopEnd)
                        .graphicsLayer { alpha = chromeAlpha }
                        .padding(
                            top = 22.dp,
                            start = if (currentRoute == "player") 26.dp else 0.dp,
                            end = if (currentRoute == "player") 0.dp else 26.dp,
                        ),
                )
            }

            if (railOnScreen) {
                TvSideNav(
                    destinations = topLevelDestinations,
                    avatarIndex = activeProfile?.avatarIndex ?: 0,
                    avatarLabel = activeProfile?.name ?: "P",
                    profileFocusRequester = profileNavRequester,
                    currentRoute = currentRoute.orEmpty(),
                    contentRequesters = destinationContentRequesters,
                    transparent = appPrefs?.transparentNavigation != false,
                    railRequester = navRailRequester,
                    suppressExpansion = railFocusHandoffPending || contentFocusedRoute != currentRoute,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .graphicsLayer { alpha = chromeAlpha },
                    onNavigate = { route ->
                        pendingDestinationFocus = route
                        if (route != currentRoute) {
                            navController.navigate(route) {
                                popUpTo(TopLevelDestination.Home.route) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    },
                )
            }

            if (showUpdatePrompt) {
                AppUpdatePrompt(
                    state = appUpdateState,
                    updateManager = appUpdateManager,
                    modifier = Modifier
                        .fillMaxSize(),
                )
            }

            if (!showUpdatePrompt) {
                pendingHandoff?.let { handoff ->
                    HandoffPrompt(
                        processing = handoffProcessing,
                        errorMessage = handoffError,
                        onAccept = {
                            if (!handoffProcessing) {
                                handoffProcessing = true
                                handoffError = null
                                handoffScope.launch {
                                    runCatching { repository.acceptHandoff(handoff) }
                                        .onSuccess { request ->
                                            handledHandoffId = handoff.id
                                            repository.acknowledgeHandoff(handoff.id, "accepted")
                                            repository.savePlaybackRequest(request)
                                            pendingHandoff = null
                                            handoffProcessing = false
                                            navController.popBackStack("player", inclusive = true)
                                            navController.navigate("player") { launchSingleTop = true }
                                            repository.acknowledgeHandoff(handoff.id, "playing")
                                        }
                                        .onFailure { error ->
                                            TvDebugLogger.e("Handoff", "Could not decrypt or open the handoff", error)
                                            handledHandoffId = handoff.id
                                            repository.acknowledgeHandoff(handoff.id, "failed")
                                            handoffProcessing = false
                                            handoffError = "This handoff could not be opened securely. Reopen StreamDek on your phone and try again."
                                        }
                                }
                            }
                        },
                        onDismiss = {
                            if (!handoffProcessing) {
                                handoffScope.launch { repository.acknowledgeHandoff(handoff.id, "dismissed") }
                                handledHandoffId = handoff.id
                                pendingHandoff = null
                                handoffError = null
                            }
                        },
                    )
                }
            }

            if (exitHintVisible) {
                ExitBackHint(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 34.dp),
                )
            }

        }
    }
}

@Composable
private fun StartupBootstrapGate() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "StreamDek",
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
            color = Color.White,
        )
    }
}

@Composable
private fun StartupProfilePicker(
    profiles: List<StreamProfile>,
    activeProfileId: String?,
    switching: Boolean,
    onVerifyPin: suspend (StreamProfile, String) -> Boolean,
    onChoose: (StreamProfile) -> Unit,
) {
    val firstRequester = remember(profiles) { FocusRequester() }
    val pinRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var lockedProfile by remember { mutableStateOf<StreamProfile?>(null) }
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var checkingPin by remember { mutableStateOf(false) }
    var selectedProfileId by remember { mutableStateOf<String?>(null) }
    val avatarBounds = remember { mutableStateMapOf<String, Rect>() }
    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }
        ?: profiles.firstOrNull { it.id == activeProfileId }
        ?: profiles.firstOrNull()

    fun choose(profile: StreamProfile) {
        selectedProfileId = profile.id
        onChoose(profile)
    }

    BackHandler(enabled = true) { /* A profile is required before entering the app. */ }
    LaunchedEffect(profiles, switching) {
        if (switching) return@LaunchedEffect
        delay(100)
        runCatching { firstRequester.requestFocus() }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = switching,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(durationMillis = 280),
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
                            "Who's watching?",
                            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
                            color = Color.White,
                        )
                        Text(
                            "Choose a profile for this TV",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.64f),
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
                                            profile.hasPinSet -> "PIN required"
                                            profile.id == activeProfileId -> "Last used"
                                            profile.isDefault -> "Default"
                                            else -> "Ready to watch"
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
                        "Enter PIN for ${profile.name}",
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
                        label = { androidx.compose.material3.Text("4-digit PIN") },
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
                        ) { Text(if (checkingPin) "Checking…" else "Continue") }
                        OutlinedButton(
                            enabled = !checkingPin,
                            onClick = { lockedProfile = null },
                        ) { Text("Back") }
                    }
                }
            }
        }
    }
}

/** Moves the chosen portrait from its card into the centre while the profile bootstrap refreshes. */
@Composable
private fun ProfileEntryTransition(profile: StreamProfile, startBounds: Rect?) {
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
            animationSpec = tween(durationMillis = 460, easing = FastOutSlowInEasing),
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
                "Welcome back,",
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

@Composable
private fun HandoffPrompt(
    processing: Boolean,
    errorMessage: String?,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    val acceptRequester = remember { FocusRequester() }
    LaunchedEffect(errorMessage) {
        delay(80)
        runCatching { acceptRequester.requestFocus() }
    }
    Dialog(
        onDismissRequest = { if (!processing) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !processing, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xC7000000)), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.46f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(TvChromePanel)
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.42f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 30.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // A Dialog's content sits outside any Surface, so LocalContentColor is still the
                // black default here — an unstyled Text disappears against the dark panel.
                Text(
                    "Continue on this TV?",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    errorMessage ?: "StreamDek Mobile wants to continue the current movie or episode here. The playback details are encrypted and expire automatically.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (errorMessage == null) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.74f) else Color(0xFFFFB4AB),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(
                        onClick = if (errorMessage == null) onAccept else onDismiss,
                        enabled = !processing,
                        modifier = Modifier.focusRequester(acceptRequester),
                    ) { Text(if (processing) "Opening…" else if (errorMessage == null) "Continue watching" else "Close") }
                    if (errorMessage == null) {
                        OutlinedButton(onClick = onDismiss, enabled = !processing) { Text("Not now") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExitBackHint(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(TvChromePanel)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.36f), RoundedCornerShape(999.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(
            text = "Press back again to exit StreamDek TV",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun AppUpdatePrompt(
    state: AppUpdateUiState,
    updateManager: AppUpdateManager,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val release = state.availableRelease ?: return
    val notesRequester = remember(release.versionCode) { FocusRequester() }
    val installRequester = remember(release.versionCode, state.blockedByUnknownSources) { FocusRequester() }
    val laterRequester = remember(release.versionCode) { FocusRequester() }
    val notesScrollState = rememberScrollState()
    val scrollStepPx = 180
    val canDismiss = !release.required
    var notesHasFocus by remember(release.versionCode) { mutableStateOf(false) }

    LaunchedEffect(release.versionCode, state.showPrompt, state.blockedByUnknownSources, state.isInstalling) {
        if (!state.showPrompt) return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        val preferredRequester = if (!release.releaseNotes.isNullOrBlank()) notesRequester else installRequester
        runCatching { preferredRequester.requestFocus() }
    }

    Dialog(
        onDismissRequest = {
            if (canDismiss) {
                updateManager.dismissPrompt()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = canDismiss,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xB8000000)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.58f)
                    .heightIn(max = 760.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(TvChromePanel)
                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 26.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Update Available",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onBackground,
                )

                release.releaseNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                    Card(
                        onClick = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 180.dp)
                            .focusRequester(notesRequester)
                            .focusProperties {
                                down = installRequester
                                up = notesRequester
                                left = notesRequester
                                right = notesRequester
                            }
                            .onFocusChanged { notesHasFocus = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        if (notesScrollState.canScrollForward) {
                                            scope.launch {
                                                notesScrollState.animateScrollTo(
                                                    (notesScrollState.value + scrollStepPx).coerceAtMost(notesScrollState.maxValue),
                                                )
                                            }
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                    Key.DirectionUp -> {
                                        if (notesScrollState.canScrollBackward) {
                                            scope.launch {
                                                notesScrollState.animateScrollTo(
                                                    (notesScrollState.value - scrollStepPx).coerceAtLeast(0),
                                                )
                                            }
                                            true
                                        } else {
                                            true
                                        }
                                    }
                                    else -> false
                                }
                            },
                        shape = CardDefaults.shape(RoundedCornerShape(22.dp)),
                        colors = CardDefaults.colors(
                            containerColor = Color(0xC8161B24),
                            focusedContainerColor = Color(0xD21A202B),
                        ),
                        border = CardDefaults.border(
                            border = Border(
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x1FFFFFFF)),
                                shape = RoundedCornerShape(22.dp),
                            ),
                            focusedBorder = Border(
                                border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(22.dp),
                            ),
                        ),
                        scale = CardDefaults.scale(focusedScale = 1f),
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(notesScrollState)
                                    .padding(start = 18.dp, top = 18.dp, end = 28.dp, bottom = 18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "What's New",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                Text(
                                    text = notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                )
                            }

                            val canScroll = notesScrollState.maxValue > 0
                            if (canScroll) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(vertical = 18.dp, horizontal = 8.dp),
                                    contentAlignment = Alignment.TopEnd,
                                ) {
                                    val thumbRatio = 0.28f
                                    val trackTravel = 180f
                                    val progress = if (notesScrollState.maxValue == 0) 0f
                                    else notesScrollState.value.toFloat() / notesScrollState.maxValue.toFloat()
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                                    )
                                    Box(
                                        modifier = Modifier
                                            .offset(y = (trackTravel * progress).dp)
                                            .width(4.dp)
                                            .fillMaxHeight(thumbRatio)
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.95f)),
                                    )
                                }
                            }
                        }
                    }
                }

                state.statusText?.let { status ->
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                state.errorMessage?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF8A80),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { scope.launch { updateManager.startUpdate() } },
                        enabled = !state.isInstalling,
                        shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
                        modifier = Modifier
                            .focusRequester(installRequester)
                            .focusProperties {
                                up = notesRequester
                                right = if (canDismiss) laterRequester else installRequester
                                down = installRequester
                                left = installRequester
                            },
                    ) {
                        Text(
                            when {
                                state.blockedByUnknownSources -> "Open Install Settings"
                                state.downloadProgressPercent != null -> "Downloading ${state.downloadProgressPercent}%"
                                state.isInstalling -> "Preparing Update"
                                else -> "Install Update"
                            },
                        )
                    }
                    OutlinedButton(
                        onClick = { updateManager.dismissPrompt() },
                        enabled = canDismiss,
                        shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
                        modifier = Modifier
                            .focusRequester(laterRequester)
                            .focusProperties {
                                up = notesRequester
                                left = installRequester
                                right = laterRequester
                                down = laterRequester
                            },
                    ) {
                        Text("Later")
                    }
                }
            }
        }
    }
}

/**
 * Clears flat-background top-level screens past the collapsed rail without coupling their
 * geometry to the currently selected navigation route. Each NavHost entry retains this wrapper
 * for its entire enter/exit transition, so neither page can shift underneath the fade.
 */
@Composable
private fun RailInsetDestination(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(start = TvNavRailInset)) { content() }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TvSideNav(
    destinations: List<TopLevelDestination>,
    avatarIndex: Int,
    avatarLabel: String,
    profileFocusRequester: FocusRequester,
    currentRoute: String,
    contentRequesters: Map<String, FocusRequester>,
    transparent: Boolean,
    /** Attached to the rail itself, so a screen can send focus here without naming a destination. */
    railRequester: FocusRequester,
    /** True until the route on screen has held focus on its own content at least once. */
    suppressExpansion: Boolean,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit,
) {
    var highlightedRoute by remember { mutableStateOf(currentRoute) }
    var navHasFocus by remember { mutableStateOf(false) }
    // The viewer pressed something while the rail had focus, which no transition does.
    //
    // A screen with nothing focusable on it — an empty library, a search with no results yet, a
    // page that failed to load — would otherwise leave the rail suppressed for as long as the
    // viewer stayed there, which is exactly when the menu is the thing they want. A key arriving
    // here is the viewer, so it releases the suppression for this visit.
    var railInteracted by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val itemRequesters = remember(destinations, profileFocusRequester) {
        destinations.associateWith { destination ->
            if (destination == TopLevelDestination.Profile) profileFocusRequester else FocusRequester()
        }
    }
    // One value for the whole rail, and the same one in both directions.
    //
    // The width, the labels and the surface each had their own animation before, on three
    // durations, and the surface in particular arrived by fading up in place — a hard-edged panel
    // materialising over the artwork rather than coming from anywhere. Driving everything from one
    // 0-to-1 figure means opening and closing are the same movement played each way, and lets the
    // surface be slid in from behind the icons instead of switched on.
    val railExpanded = navHasFocus && (!suppressExpansion || railInteracted)
    val railOpen by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (railExpanded) 1f else 0f,
        animationSpec = TvScroll.spec(TvMotion.duration(TvMotion.Expand)),
        label = "side-nav-open",
    )
    val railWidth = TvNavRailWidth + (196.dp - TvNavRailWidth) * railOpen
    // Trails the panel slightly: the words arrive once there is room for them, not while the space
    // is still opening up.
    val labelAlpha = ((railOpen - 0.35f) / 0.65f).coerceIn(0f, 1f)
    val railSurfaceAlpha = railOpen * if (transparent) NavRailTransparentAlpha else 1f

    LaunchedEffect(currentRoute) {
        highlightedRoute = currentRoute
        // Navigation can complete while the select/back key release is still owned by the rail.
        // Collapse immediately, then hand focus to the destination if it is already composed.
        // Its own restore effect will take over when content finishes loading.
        if (navHasFocus) {
            navHasFocus = false
            delay(32L)
            val accepted = contentRequesters[currentRoute]?.let { requester ->
                runCatching { requester.requestFocus() }.getOrDefault(false)
            } == true
            if (!accepted) focusManager.clearFocus(force = true)
        }
    }

    val displayedRoute = if (railExpanded) highlightedRoute else currentRoute
    // The highlight stays solid while the rail around it does not. It used to be a 22% primary tint
    // relying on opaque black underneath it; with the rail translucent that tint would sit on
    // artwork and the marker for where you are would change colour with whatever is behind it.
    // Mixing the same 22% into the chrome colour up front reproduces exactly the old appearance as
    // one opaque value.
    val navHighlight = androidx.compose.ui.graphics.lerp(
        TvChromeSurface,
        MaterialTheme.colorScheme.primary,
        0.22f,
    )
    // Back out of the rail goes back to the page, not off it.
    //
    // The rail is opened by pressing left out of whatever you were doing, so Back is the obvious
    // way to undo that — and it used to fall through to the app's own handler, which read it as
    // "leave this screen" and dropped a viewer who had merely glanced at the menu onto Home.
    //
    // Only while the menu is actually open, though. Focus can be parked here by a transition with
    // the rail still shut, and a viewer pressing Back through that means to leave the screen; the
    // press was being eaten to close a menu that was never on screen.
    BackHandler(enabled = railExpanded) {
        val returned = contentRequesters[currentRoute]?.let { requester ->
            runCatching { requester.requestFocus() }.getOrDefault(false)
        } == true
        // Nothing to hand focus back to on this screen: at least let the rail collapse rather than
        // swallowing the press entirely.
        if (!returned) focusManager.clearFocus()
    }

    Box(
        modifier = modifier
            .width(railWidth)
            .fillMaxHeight()
            .clipToBounds(),
    ) {
        // The surface slides out from under the icons rather than fading up in place.
        //
        // Collapsed, the rail has no surface at all: it is five icons over the artwork, and a strip
        // behind them only cut a band out of the backdrop to hold markers that read fine on their
        // own. It comes back as the rail opens, because at that width it is a menu being read and
        // the labels need something behind them — but it used to arrive by turning on, which put a
        // hard-edged rectangle over the artwork out of nowhere. Translated in from the left and
        // clipped, the same panel appears to come from behind the icons, and closing is the identical
        // movement run backwards.
        //
        // The colour is applied here rather than to TvChromeSurface itself — the settings sidebar
        // and the live filter column use that same value and want to stay opaque, since they sit
        // over content rather than over artwork.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = -size.width * (1f - railOpen) }
                .background(TvChromeSurface.copy(alpha = railSurfaceAlpha)),
        )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(railRequester)
            .focusProperties {
                enter = {
                    itemRequesters[destinations.firstOrNull { it.route == currentRoute }]
                        ?: FocusRequester.Default
                }
            }
            // `enter` is only consulted for a focus group. Without this the rail was an ordinary
            // container, so returning to it used plain spatial navigation and landed on whichever
            // item was nearest — in practice always Home — instead of the page you are on.
            .focusGroup()
            .onFocusChanged {
                navHasFocus = it.hasFocus
                if (!it.hasFocus) {
                    // Each visit to the rail earns its own release. Leaving it puts the suppression
                    // back, so the next arrival is judged on where the focus came from again.
                    railInteracted = false
                    // And every visit starts from where the viewer actually is.
                    //
                    // The highlight is where the last visit left off, which is right while the rail
                    // is open and wrong the moment it is re-entered: if something took focus away
                    // mid-press, the rail would re-open showing an item the viewer never chose, one
                    // step down from the one before. Reset on the way out, so opening the menu
                    // always points at the page behind it.
                    highlightedRoute = currentRoute
                }
            }
            // Never consumes: this only notes that the press happened, and the item handlers and
            // the focus system below still see it. Back is deliberately not on the list — through
            // a transition that is a viewer leaving the screen, not opening the menu.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key in RailInteractionKeys) {
                    railInteracted = true
                }
                false
            }
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        destinations.forEachIndexed { index, destination ->
            val highlighted = destination.route == displayedRoute
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        androidx.compose.animation.animateColorAsState(
                            // Fades on its own alpha rather than towards Color.Transparent, so the
                            // in-between frames are the same hue getting fainter instead of a slide
                            // through transparent black.
                            targetValue = if (highlighted) navHighlight else navHighlight.copy(alpha = 0f),
                            animationSpec = TvScroll.spec(TvMotion.duration(200)),
                            label = "side-nav-highlight",
                        ).value,
                    )
                    .focusRequester(itemRequesters.getValue(destination))
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            // Do not leave vertical movement to spatial focus search while the rail
                            // is changing width. On Fire TV that search can choose page content for
                            // a frame, collapsing the rail; the same Down press then appears to do
                            // nothing. An explicit neighbour makes every press move exactly once.
                            Key.DirectionUp -> {
                                val target = destinations.getOrNull(index - 1)
                                target?.let { itemRequesters[it] }
                                    ?.let { requester -> runCatching { requester.requestFocus(); true }.getOrDefault(false) }
                                    ?: true
                            }
                            Key.DirectionDown -> {
                                val target = destinations.getOrNull(index + 1)
                                target?.let { itemRequesters[it] }
                                    ?.let { requester -> runCatching { requester.requestFocus(); true }.getOrDefault(false) }
                                    ?: true
                            }
                            Key.DirectionRight -> {
                                // Back to the page the viewer is actually on, not the one the
                                // highlighted item would open. On a top-level screen those are usually
                                // the same and the difference never showed; on a title page every item
                                // names somewhere else, so this looked up a requester belonging to a
                                // screen that was not composed, failed, and left the viewer in the menu
                                // with no way out.
                                (contentRequesters[currentRoute] ?: contentRequesters[destination.route])
                                    ?.let { requester -> runCatching { requester.requestFocus(); true }.getOrDefault(false) }
                                    ?: false
                            }
                            else -> false
                        }
                    }
                    .onFocusChanged {
                        if (it.isFocused) highlightedRoute = destination.route
                    }
                    .clickable {
                        onNavigate(destination.route)
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.width(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (destination == TopLevelDestination.Profile) {
                        ProfileAvatarCircle(
                            avatarIndex = avatarIndex,
                            fallbackLabel = avatarLabel,
                            size = 24.dp,
                        )
                    } else {
                        destination.icon?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = destination.label,
                                tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                if (labelAlpha > 0.01f) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = destination.label,
                        color = (if (highlighted) Color.White else MaterialTheme.colorScheme.onBackground)
                            .copy(alpha = labelAlpha),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
    }
}


























