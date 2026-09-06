package com.streamdek.tv.nativeapp.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.AppGraph
import com.streamdek.tv.nativeapp.data.DefaultTrailerCacheClearHours
import com.streamdek.tv.nativeapp.data.LiveCatalogSection
import com.streamdek.tv.nativeapp.data.PlaybackHandoff
import com.streamdek.tv.nativeapp.data.PlaybackRequest
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.TrailerCache
import com.streamdek.tv.nativeapp.data.TrailerCacheClearHourOfDay
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import com.streamdek.tv.nativeapp.data.TvIdlePreferences
import com.streamdek.tv.nativeapp.data.TvPowerActions
import com.streamdek.tv.nativeapp.data.clearTrailerState
import com.streamdek.tv.nativeapp.data.idleTimeoutMillis
import com.streamdek.tv.nativeapp.data.mapAddonCatalogType
import com.streamdek.tv.nativeapp.ui.AppFormats
import com.streamdek.tv.nativeapp.ui.LocalAppLanguage
import com.streamdek.tv.nativeapp.ui.TvScroll
import com.streamdek.tv.nativeapp.ui.account.SettingsScreen
import com.streamdek.tv.nativeapp.ui.auth.AuthScreen
import com.streamdek.tv.nativeapp.ui.detail.DetailScreen
import com.streamdek.tv.nativeapp.ui.detail.PlaybackStreamsScreen
import com.streamdek.tv.nativeapp.ui.home.HomeEntryMode
import com.streamdek.tv.nativeapp.ui.home.HomeEntryRequest
import com.streamdek.tv.nativeapp.ui.home.HomeScreen
import com.streamdek.tv.nativeapp.ui.library.LibraryScreen
import com.streamdek.tv.nativeapp.ui.live.LiveBrowseScreen
import com.streamdek.tv.nativeapp.ui.live.LiveScreen
import com.streamdek.tv.nativeapp.ui.network.NetworkBrowseScreen
import com.streamdek.tv.nativeapp.ui.player.PlayerScreen
import com.streamdek.tv.nativeapp.ui.profile.StartupBootstrapGate
import com.streamdek.tv.nativeapp.ui.profile.StartupProfilePicker
import com.streamdek.tv.nativeapp.ui.search.SearchScreen
import com.streamdek.tv.nativeapp.update.AppUpdateManager
import com.streamdek.tv.nativeapp.update.AppUpdateUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The five places the navigation rail can take you.
 *
 * [route] is the identity and never changes; the word on the rail is a resource. The `width` each
 * entry used to carry was measured from the English label and read by nothing - the rail sizes
 * itself - so it is gone rather than left to mislead the next person who translates this.
 */
private enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector?,
) {
    Home("home", R.string.nav_home, Icons.Outlined.Home),
    Search("search", R.string.nav_search, Icons.Outlined.Search),
    Live("live", R.string.nav_live, Icons.Outlined.LiveTv),
    Library("library", R.string.nav_library, Icons.Outlined.VideoLibrary),
    Profile("profile", R.string.nav_settings, null),
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

private enum class NavigationFocusRegion { Content, SideNav }

/**
 * How far the expanded navigation rail is allowed to let the backdrop through.
 *
 * The ceiling is the point, not the value: expanded, the rail is a menu being read, and any further
 * and it stops reading as a surface at all against bright artwork. Collapsed it has no surface at
 * all — see [TvSideNav].
 */
private const val NavRailTransparentAlpha = 0.85f

/**
 * How long the rail waits before believing it has lost focus.
 *
 * Long enough to absorb a frame in which focus is in flight between two nav items, short enough
 * that a viewer who has genuinely left never sees the drawer linger.
 */
private const val NavRailCloseSettleMs = 90L

/**
 * How long a claim on the navigation region waits for the rail to answer it by taking focus.
 *
 * Comfortably more than the few frames the rail needs to become focusable and place its highlight,
 * and short enough that a route which carries no rail at all does not appear to swallow the press.
 */
private const val NavRailOpenConfirmMs = 260L

/** The navigation route the title page is registered under, kept in one place. */
private const val DetailRoutePattern = "detail/{type}/{id}"
private const val PersonRoutePattern = "person/{id}"

private fun detailRoute(mediaType: String, mediaId: String): String {
    val canonicalType = if (mediaType == "series") "tv" else mediaType
    return "detail/$canonicalType/$mediaId"
}
/**
 * The app, in the selected interface language.
 *
 * Only the locale wrapper lives here; everything else is [StreamDekTvAppContent]. The split is what
 * lets a language change take effect in place: [ProvideAppLocale] replaces the composition locals
 * `stringResource` reads, so the tree below re-resolves every string on the next recomposition
 * without the activity being rebuilt. On a television that distinction is the whole feature -
 * rebuilding would reconstruct every [FocusRequester] in the tree and drop the remote's focus,
 * leaving the viewer on a page where nothing responds until they press their way back into it.
 */
@Composable
fun StreamDekTvApp(repository: StreamDekRepository = remember { AppGraph.repository }) {
    val context = LocalContext.current
    // Device-local, and read here so that choosing a language in Settings recomposes the tree on the
    // spot. See AppLanguage.kt for why this setting does not travel with the account.
    val languagePreferences = remember(context) { TvAppLanguagePreferences(context) }
    CompositionLocalProvider(LocalTvAppLanguagePreferences provides languagePreferences) {
        ProvideAppLocale(languagePreferences.selection) {
            StreamDekTvAppContent(repository)
        }
    }
}

@Composable
private fun StreamDekTvAppContent(repository: StreamDekRepository) {
    val navController = rememberNavController()
    val context = LocalContext.current
    // findActivity(), not a cast: ProvideAppLocale hands the composition a ContextWrapper, and a
    // direct `as? Activity` would quietly become null, taking the sleep and back-out paths with it.
    val activity = context.findActivity()
    // The handoff failure is reported from a coroutine, which is not a composition.
    val appResources = context.resources
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
    val navigationFocusScope = rememberCoroutineScope()
    /**
     * Which region owns the D-pad: the page, or the side navigation. Exactly one of them, always.
     *
     * The rail reports what its focus is actually doing and this follows, so "the menu is open" and
     * "the menu holds the highlight" cannot come apart. Everything that wants the menu closed does
     * it by moving focus back into the page rather than by setting this directly — see
     * [openSideNavigation] for the one exception, which is optimistic and self-correcting.
     */
    var navigationFocusRegion by remember { mutableStateOf(NavigationFocusRegion.Content) }
    val sideNavOwnsFocus = navigationFocusRegion == NavigationFocusRegion.SideNav
    /**
     * Bumped every time the rail newly takes focus.
     *
     * Screens hand focus to their content over a short retry window, and "the viewer opened the
     * menu while that was running" has to win — otherwise the menu is torn back open-and-shut one
     * attempt at a time, which is what made opening it from a Home row take two presses. Comparing
     * this against the value a transfer started with distinguishes that from the ordinary case of a
     * transfer that began in the rail because the viewer chose a destination from it.
     */
    var sideNavFocusEpoch by remember { mutableStateOf(0) }
    /** Set once the rail has actually taken focus, as opposed to having merely been claimed. */
    var sideNavFocusConfirmed by remember { mutableStateOf(false) }
    /**
     * Whether anything at all in the app window holds the highlight.
     *
     * On a television there is no pointer and no other way to address the screen, so a moment with
     * no focus owner is a moment the remote does nothing — which is how it is reported, as the page
     * having gone dead. Screens aim focus for themselves and usually land it; this is only the
     * floor under them, and it is deliberately the last thing to act.
     */
    var appHasFocus by remember { mutableStateOf(false) }
    var appUserActivityVersion by remember { mutableIntStateOf(0) }
    val appView = androidx.compose.ui.platform.LocalView.current
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
    var homeResetToTopToken by remember { mutableStateOf(0) }
    /**
     * Asks Home to take the highlight back off the navigation rail, and says which entry it is.
     *
     * Every other destination has one attached requester the shell can aim at. Home's entry card
     * is the leading card of whichever row first has items, which is only attached while the shelf
     * list keeps that row composed — so aiming a requester at it worked only while the viewer
     * happened to be near the top of the page. Home answers this instead, and it is also the only
     * thing that knows where the viewer was: a menu opened from Home and closed again returns them
     * to the card they left, while arriving at Home as a destination starts at the first row.
     */
    var homeEntryRequest by remember { mutableStateOf(HomeEntryRequest()) }
    var pendingDestinationFocus by remember { mutableStateOf<String?>(null) }
    var detailNavigationInProgress by remember { mutableStateOf(false) }
    val openDetail: (String, String) -> Unit = { mediaType, mediaId ->
        detailNavigationInProgress = true
        navController.navigate(detailRoute(mediaType, mediaId))
    }

    fun prepareFreshHomeEntry() {
        lastHomeRowId = null
        lastHomeItemKey = null
        homeResetToTopToken += 1
        startScreenApplied = true
        // Home owns this placement because it alone knows which optional rows actually resolved.
        // The shell must not request its entry requester before that row has been composed.
        pendingDestinationFocus = null
    }

    fun returnHomeAfterProfileSelection() {
        prepareFreshHomeEntry()
        navController.navigate(TopLevelDestination.Home.route) {
            popUpTo(TopLevelDestination.Home.route) { inclusive = false }
            launchSingleTop = true
        }
    }

    var observedProfileId by remember(session?.user?.uid) { mutableStateOf(activeProfile?.id) }
    LaunchedEffect(activeProfile?.id) {
        val next = activeProfile?.id
        val previous = observedProfileId
        observedProfileId = next
        // The startup picker is composed instead of NavHost, so its controller has no graph yet.
        // Once the picker leaves composition, Home is the graph's start destination already.
        if (previous != null && next != null && previous != next && !showStartupProfilePicker) {
            returnHomeAfterProfileSelection()
        }
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
            navController.navigate(TopLevelDestination.Home.route) {
                popUpTo(TopLevelDestination.Home.route) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(currentRoute) {
        // A destination change always starts in content. The rail hands focus to the new page
        // below, and its own state follows that focus; nothing is set here, or the rail could be
        // drawn shut for a frame while it still held the highlight.
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
    /**
     * Hand the D-pad back from the navigation rail to a page. True when something will take it.
     *
     * Home is asked rather than aimed at, for the reason on [homeEntryRequest]; every other
     * destination is one requester, and a false result means this route has nothing attached to
     * receive the highlight — in which case the caller leaves the menu where it is.
     *
     * [mode] only reaches Home, and only Home has anywhere to put it: the other destinations have
     * a single entry target either way.
     */
    fun enterContent(route: String, mode: HomeEntryMode): Boolean =
        if (route == TopLevelDestination.Home.route) {
            homeEntryRequest = HomeEntryRequest(homeEntryRequest.token + 1, mode)
            true
        } else {
            destinationContentRequesters[route]?.requestFocusOrFalse() == true
        }

    LaunchedEffect(currentRoute, pendingDestinationFocus, showStartupProfilePicker) {
        val route = currentRoute
        if (route == null || showStartupProfilePicker) {
            return@LaunchedEffect
        }
        if (pendingDestinationFocus != null && pendingDestinationFocus != route) {
            return@LaunchedEffect
        }
        // Home has a dynamic entry target: its first non-empty row is only knowable after the
        // progressive row load settles. It places ordinary route-entry focus itself. The one
        // exception is an explicit transfer from the open rail, which must be completed here so
        // the rail can hand ownership back to the page.
        if (route == TopLevelDestination.Home.route) {
            if (pendingDestinationFocus != route) return@LaunchedEffect
            // Arriving at Home as a destination, so the first row rather than wherever the viewer
            // last was — that distinction belongs to Home and is why this is a request rather than
            // a focus grab. The retry below stays behind it for the case where Home is only now
            // being composed and so has not seen this request at all.
            enterContent(route, HomeEntryMode.Fresh)
        }
        val requester = destinationContentRequesters[route]
        if (requester == null) {
            return@LaunchedEffect
        }

        // This runs for every route change, including plain popBackStack calls from nested
        // screens. The short bounded retry window covers the spatial-focus fallback that occurs
        // while the returning destination is being attached. It must remain bounded: some entry
        // requesters legitimately reject programmatic focus (or the content is already focused),
        // and that must never leave the navigation rail permanently disabled.
        //
        // It stops at the first request that lands. The success test used to compare the Unit
        // returned by requestFocus against `true` and so was never satisfied, which turned this
        // into half a second of repeated focus grabs: a viewer who opened the menu just after
        // arriving on a page had the highlight dragged back out of it, and the drawer collapsed
        // with them. That is also why moving down the menu appeared to close it.
        val epochAtStart = sideNavFocusEpoch
        // A transfer the rail asked for has to land, because while the rail still holds focus the
        // destination stands down from placing its own — so nothing else would finish the job. An
        // incidental route change has the screen's own placement and the focus floor behind it and
        // does not need to keep trying.
        val explicitTransfer = pendingDestinationFocus == route
        repeat(if (explicitTransfer) 24 else 7) { attempt ->
            delay(if (attempt == 0) 32L else if (explicitTransfer) 120L else 80L)
            // The viewer has reached for the menu since this started. It owns focus now, and
            // dragging the highlight back into the page is precisely the behaviour being fixed.
            if (sideNavFocusEpoch != epochAtStart) {
                pendingDestinationFocus = null
                return@LaunchedEffect
            }
            if (requester.requestFocusOrFalse()) {
                pendingDestinationFocus = null
                return@LaunchedEffect
            }
        }
        pendingDestinationFocus = null
    }

    /**
     * The focus floor.
     *
     * Every screen places its own focus, and every one of those placements can miss — a restored
     * card whose row no longer exists, a requester asked for before its target was attached, a
     * transition that disposed the screen holding the highlight. Individually those are recoverable;
     * together they left the app with nothing focused and no way for the remote to reach anything,
     * which is what going back to Home quickly from the player used to produce.
     *
     * Deliberately slow to act and easy to pre-empt: the first attempt is well after a screen's own
     * placement would have landed, and the effect is cancelled the instant anything takes focus. It
     * stands down entirely while another window — a dialog, the system — owns input, since the
     * highlight in there is not this composition's to take back.
     */
    LaunchedEffect(appHasFocus, currentRoute, showStartupProfilePicker) {
        if (appHasFocus || showStartupProfilePicker) return@LaunchedEffect
        // Home has its own focus floor, tied to the first available row after its initial row set
        // is ready. A shell-level request here can attach to a lower row while an earlier optional
        // row (Continue Watching or New Episodes) is still resolving.
        if (currentRoute == TopLevelDestination.Home.route) return@LaunchedEffect
        val requester = destinationContentRequesters[currentRoute] ?: return@LaunchedEffect
        repeat(10) { attempt ->
            delay(if (attempt == 0) 420L else 160L)
            if (appView.hasWindowFocus()) requester.requestFocusOrFalse()
        }
    }

    /**
     * The one way the side navigation opens, from anywhere in the app.
     *
     * The region is claimed first so the rail is already drawing as a menu on the frame the
     * highlight arrives in it — there is no intermediate state where the viewer has pressed Left
     * and nothing visible has happened. If the rail cannot take focus, because this route does not
     * carry it, the claim is handed straight back rather than left standing.
     */
    fun openSideNavigation() {
        if (navigationFocusRegion == NavigationFocusRegion.SideNav) return
        navigationFocusRegion = NavigationFocusRegion.SideNav
        sideNavFocusEpoch += 1
        navigationFocusScope.launch {
            // Which item the highlight should land on is the rail's own knowledge — the page the
            // viewer is on, or the one they last left the menu from — so the rail answers this
            // claim by taking focus itself. That answer coming back is also how the claim is
            // confirmed. If none arrives, this route carries no rail and the claim is withdrawn.
            delay(NavRailOpenConfirmMs)
            if (navigationFocusRegion == NavigationFocusRegion.SideNav && !sideNavFocusConfirmed) {
                navigationFocusRegion = NavigationFocusRegion.Content
            }
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
                        // Prepare Home before dismissing the startup destination. Do not navigate
                        // here: NavHost is intentionally not composed behind this picker, and its
                        // controller therefore has no graph until the next composition.
                        prepareFreshHomeEntry()
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
                startPositionSec = item.positionSec,
                returnToDetailOnBack = true,
                fromContinueWatching = true,
            ),
        )
        navController.navigate("player")
    }
    BackHandler(enabled = !showUpdatePrompt && currentRoute != "player") {
        val isTopLevelRoute = currentRoute in topLevelDestinations.map { it.route }
        when {
            isTopLevelRoute && currentRoute != TopLevelDestination.Home.route -> {
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

    LaunchedEffect(currentRoute, appUserActivityVersion, showUpdatePrompt) {
        // A playing or paused video has its own lifecycle timer in PlayerScreen. Excluding the
        // route here means a two-hour film is never mistaken for two hours without interaction.
        if (currentRoute == "player" || showUpdatePrompt) return@LaunchedEffect
        val timeout = idleTimeoutMillis(TvIdlePreferences(context).appIdleTimeoutMinutes)
            ?: return@LaunchedEffect
        delay(timeout)
        // Sleep is what this timer is for: a set left on StreamDek for hours with nobody in the
        // room should go dark, not merely go to the launcher. Asking for it outright is only
        // answered where the platform has granted this app DEVICE_POWER, so where it is refused the
        // app stands down instead and leaves the set to its own idle timer — which is the same
        // behaviour as before, now as the fallback rather than as the whole of it.
        if (!TvPowerActions.sleepDevice(context)) activity?.moveTaskToBack(true)
    }

    // Device-local, and read here so that choosing a speed in Settings recomposes the theme and
    // every animation under it on the spot. See AnimationSpeed.kt for why this one setting does not
    // travel with the account.
    val animationPreferences = remember(context) { TvAnimationPreferences(context) }
    val motionSettings = rememberTvMotionSettings(
        preferences = animationPreferences,
        accountReducedMotion = appPrefs?.reducedMotion == true,
    )
    CompositionLocalProvider(LocalTvAnimationPreferences provides animationPreferences) {
    StreamDekTvTheme(appPreferences = appPrefs, homePreferences = homePrefs, motion = motionSettings) {
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
        val shellFocusManager = androidx.compose.ui.platform.LocalFocusManager.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // Covers the pages, the rail and everything drawn over them, so `hasFocus` here is
                // the honest answer to "does the remote currently address anything".
                .onFocusChanged { appHasFocus = it.hasFocus }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) appUserActivityVersion += 1
                    false
                }
                // The content/menu boundary, in one place, for every screen.
                //
                // This is the bubble phase, so it only ever sees a Left press that the focused
                // screen — and every container between it and here — declined to handle. Such a
                // press is exactly the one Compose would otherwise hand to a spatial search, so the
                // search is run here instead and its answer used: something further left inside the
                // page, or, when there is nothing, the menu.
                //
                // Doing it this way is what lets the collapsed rail stop being a focus target at
                // all. Screens used to reach it by aiming a focus redirect at it or by simply
                // letting the search find it, which meant *any* search that ran out of page — most
                // damagingly Compose's own recovery when a screen was disposed mid-transition —
                // landed in the rail and pulled it open. Returning Home from an inner page did it
                // every time.
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    if (event.key != Key.DirectionLeft) return@onKeyEvent false
                    if (!railOnScreen || sideNavOwnsFocus) return@onKeyEvent false
                    if (shellFocusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Left)) {
                        return@onKeyEvent true
                    }
                    openSideNavigation()
                    true
                }
                .focusGroup(),
        ) {
            CompositionLocalProvider(
                LocalImmersiveContent provides { active -> immersiveContent = active },
                LocalSideNavOwnsFocus provides sideNavOwnsFocus,
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
                modifier = Modifier.fillMaxSize(),
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
                        resetToTopToken = homeResetToTopToken,
                        navEntry = homeEntryRequest,
                        onPositionChanged = { rowId, itemKey ->
                            lastHomeRowId = rowId
                            lastHomeItemKey = itemKey
                        },
                        onOpenNavigation = ::openSideNavigation,
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
                        onOpenNavigation = ::openSideNavigation,
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
                            prepareFreshHomeEntry()
                            navController.navigate(TopLevelDestination.Home.route) {
                                popUpTo(TopLevelDestination.Home.route) { inclusive = false }
                                launchSingleTop = true
                            }
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
                            onExitToDetail = {
                                if (!navController.popBackStack(DetailRoutePattern, inclusive = false)) {
                                    navController.navigate("detail/${Uri.encode(request.mediaType)}/${Uri.encode(request.mediaId)}") {
                                        popUpTo(TopLevelDestination.Home.route) { inclusive = false }
                                        launchSingleTop = true
                                    }
                                }
                            },
                            onPlayRecommendation = { item ->
                                if (item.type.equals("tv", ignoreCase = true)) {
                                    navController.navigate("detail/${Uri.encode(item.type)}/${Uri.encode(item.id)}") {
                                        popUpTo("player") { inclusive = true }
                                    }
                                } else {
                                    repository.savePlaybackRequest(
                                        PlaybackRequest(
                                            mediaId = item.id,
                                            mediaType = item.type,
                                            imdbId = item.imdbId,
                                            title = item.title,
                                            returnToDetailOnBack = true,
                                        ),
                                    )
                                    navController.navigate("player") {
                                        popUpTo("player") { inclusive = true }
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
                            onBack = {
                                repository.cancelStreamDiscovery("left picker")
                                navController.popBackStack()
                            },
                            onPlayRequest = { selectedRequest ->
                                // The viewer has chosen. Whatever the remaining providers were
                                // still scraping for cannot change that choice, and on a stick the
                                // decoder needs the CPU and the network more than the picker does.
                                repository.cancelStreamDiscovery("source selected")
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

            // The rail is composed per route. When it goes — into the player, into a title page
            // mid-transition, behind a trailer — it takes its focus with it, and nothing would
            // otherwise report that. Leaving the region claiming SIDE_NAV is what brought a viewer
            // back to Home with the menu drawn open and the highlight sitting on a card.
            LaunchedEffect(railOnScreen) {
                if (!railOnScreen) navigationFocusRegion = NavigationFocusRegion.Content
            }

            if (railOnScreen) {
                TvSideNav(
                    destinations = topLevelDestinations,
                    avatarIndex = activeProfile?.avatarIndex ?: 0,
                    avatarLabel = activeProfile?.name ?: "P",
                    profileFocusRequester = profileNavRequester,
                    currentRoute = currentRoute.orEmpty(),
                    // Right and Back out of the menu are a return, not an arrival: the viewer never
                    // left the page underneath, so they go back to the card they opened it from.
                    onEnterContent = { route -> enterContent(route, HomeEntryMode.Resume) },
                    transparent = appPrefs?.transparentNavigation != false,
                    open = sideNavOwnsFocus,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .graphicsLayer { alpha = chromeAlpha },
                    // Choosing a destination does not collapse the rail directly. It asks the
                    // destination to take focus, and the rail closes because it stopped holding it
                    // — so the drawer is never drawn shut while the highlight is still inside it,
                    // and no open state can leak into the page being opened.
                    onNavigate = { route ->
                        pendingDestinationFocus = route
                        if (route == TopLevelDestination.Home.route && route != currentRoute) {
                            lastHomeRowId = null
                            lastHomeItemKey = null
                            homeResetToTopToken += 1
                        }
                        if (route != currentRoute) {
                            navController.navigate(route) {
                                popUpTo(TopLevelDestination.Home.route) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    },
                    onOpenChanged = { open ->
                        if (open && navigationFocusRegion != NavigationFocusRegion.SideNav) {
                            sideNavFocusEpoch += 1
                        }
                        sideNavFocusConfirmed = open
                        navigationFocusRegion =
                            if (open) NavigationFocusRegion.SideNav else NavigationFocusRegion.Content
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
                                            handoffError = appResources.getString(R.string.handoff_open_failed)
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
                    stringResource(R.string.handoff_continue_on_tv),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    errorMessage ?: stringResource(R.string.handoff_prompt),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (errorMessage == null) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.74f) else Color(0xFFFFB4AB),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(
                        onClick = if (errorMessage == null) onAccept else onDismiss,
                        enabled = !processing,
                        modifier = Modifier.focusRequester(acceptRequester),
                    ) {
                        Text(
                            stringResource(
                                when {
                                    processing -> R.string.state_opening
                                    errorMessage == null -> R.string.continue_watching
                                    else -> R.string.action_close
                                },
                            ),
                        )
                    }
                    if (errorMessage == null) {
                        OutlinedButton(onClick = onDismiss, enabled = !processing) { Text(stringResource(R.string.action_not_now)) }
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
            text = stringResource(R.string.exit_press_back_again),
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
                    text = stringResource(R.string.update_available),
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onBackground,
                )

                release.releaseNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                    Card(
                        onClick = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            // Taller than the single paragraph this used to hold: rendered notes
                            // carry headings and one line per bullet, and 180dp of a television
                            // showed about three of them before the viewer had to scroll.
                            .heightIn(min = 160.dp, max = 260.dp)
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
                                    text = stringResource(R.string.update_whats_new),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                // Authored as Markdown, so shown as Markdown -- see MarkdownText.
                                // Handed to a plain Text the notes arrived as their own
                                // punctuation: "## What's New" hashes and all, every bullet a
                                // hyphen, and no gap between one section and the next.
                                MarkdownText(
                                    markdown = notes,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    bodyAlpha = 0.8f,
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
                                state.blockedByUnknownSources -> stringResource(R.string.update_open_install_settings)
                                state.downloadProgressPercent != null -> stringResource(
                                    R.string.update_downloading_percent,
                                    // Through the percent formatter: the sign sits on the other
                                    // side of the number in several of these languages.
                                    AppFormats.percent(LocalAppLanguage.current, state.downloadProgressPercent / 100.0),
                                )
                                state.isInstalling -> stringResource(R.string.update_preparing)
                                else -> stringResource(R.string.update_install)
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
                        Text(stringResource(R.string.action_later))
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

/**
 * The side navigation.
 *
 * ## Two pieces of state, one invariant
 *
 * Which item is highlighted ([highlightedRoute]) and whether the drawer is a menu ([open]) are
 * separate things, and moving between items changes only the first. They used to influence each
 * other, which is why travelling down the menu collapsed it after every press.
 *
 * What ties them together is a single invariant: **the rail is open exactly while it holds focus**.
 * That is reported, not assumed — focus arriving anywhere inside the rail opens it, focus genuinely
 * leaving closes it, and [onOpenChanged] tells the shell so its authoritative region follows. The
 * consequence is that the two failures the viewer actually notices become unrepresentable: a drawer
 * drawn open while the page still answers the D-pad, and a highlight sitting inside a drawer that
 * has already collapsed.
 *
 * It also means nothing closes this by setting a flag. Everything that wants the menu shut — Back,
 * Right, choosing a destination — does it by handing focus back to the page, and the drawer follows.
 *
 * The close is deferred by [NavRailCloseSettleMs]. Compose can report the container as unfocused for
 * an instant while focus moves between two of its own children, and acting on that instant is what
 * made the drawer flicker under fast repeated presses.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TvSideNav(
    destinations: List<TopLevelDestination>,
    avatarIndex: Int,
    avatarLabel: String,
    profileFocusRequester: FocusRequester,
    currentRoute: String,
    /**
     * Hands the D-pad back to a page. False means that route has nothing able to take it, and the
     * menu stays where it is rather than collapsing with the highlight still inside it.
     */
    onEnterContent: (String) -> Boolean,
    transparent: Boolean,
    /**
     * Whether the rail is drawn as a menu. Mirrors the shell's navigation region, which in turn
     * mirrors what this composable reports through [onOpenChanged] — so it is this rail's own focus,
     * one hop away, rather than an independent flag that can drift from it.
     */
    open: Boolean,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit,
    /**
     * Reports what the rail's focus is actually doing, so the shell's region can never disagree
     * with what is on screen. This is the only thing that opens or closes the rail.
     */
    onOpenChanged: (Boolean) -> Unit,
) {
    var highlightedRoute by remember { mutableStateOf(currentRoute) }
    val itemRequesters = remember(destinations, profileFocusRequester) {
        destinations.associateWith { destination ->
            if (destination == TopLevelDestination.Profile) profileFocusRequester else FocusRequester()
        }
    }
    // True while any descendant holds focus — so travelling between nav items does not register
    // here at all, which is exactly the distinction the drawer needs and did not have.
    val railHasFocus = remember { mutableStateOf(false) }
    // One value for the whole rail, and the same one in both directions.
    //
    // The width, the labels and the surface each had their own animation before, on three
    // durations, and the surface in particular arrived by fading up in place — a hard-edged panel
    // materialising over the artwork rather than coming from anywhere. Driving everything from one
    // 0-to-1 figure means opening and closing are the same movement played each way, and lets the
    // surface be slid in from behind the icons instead of switched on.
    val railOpen by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = TvScroll.spec(TvMotion.duration(TvMotion.Expand)),
        label = "side-nav-open",
    )
    val railWidth = TvNavRailWidth + (196.dp - TvNavRailWidth) * railOpen
    // Trails the panel slightly: the words arrive once there is room for them, not while the space
    // is still opening up.
    val labelAlpha = ((railOpen - 0.35f) / 0.65f).coerceIn(0f, 1f)
    val railSurfaceAlpha = railOpen * if (transparent) NavRailTransparentAlpha else 1f

    // The highlight follows the page the viewer is on, but only while the menu is shut. Re-syncing
    // it under an open menu would drag the highlight off whatever the viewer is currently reading.
    LaunchedEffect(currentRoute, open) {
        if (!open) highlightedRoute = currentRoute
    }

    // Entry focus belongs to the rail, because only the rail knows where the highlight should land
    // — the page the viewer is on, or the item they last left the menu from. Asking the container
    // for focus from outside got the answer wrong: the group's own entry rule was not consulted for
    // a programmatic request, so opening the menu from Library put the highlight on Home.
    //
    // Retried across a few frames: the items only become focusable on the composition that made the
    // rail a menu, and the first attempt can land a frame ahead of it.
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        val target = itemRequesters[destinations.firstOrNull { it.route == highlightedRoute }]
            ?: itemRequesters[destinations.firstOrNull { it.route == currentRoute }]
            ?: itemRequesters[destinations.firstOrNull()]
            ?: return@LaunchedEffect
        repeat(5) {
            if (railHasFocus.value) return@LaunchedEffect
            delay(16L)
            target.requestFocusOrFalse()
        }
    }

    // The invariant: the rail is open exactly while it holds focus.
    //
    // Edge-triggered, so this reports a change and never re-asserts a state the shell has moved on
    // from — that is what lets a destination selection collapse the rail as focus leaves without
    // this effect immediately reopening it. Opening is immediate; the viewer pressed Left and the
    // menu has to be there in the same frame. Closing settles for a moment first, because Compose
    // can report the container as unfocused for an instant while focus crosses between two of its
    // own items, and collapsing on that instant is what made the drawer flicker under fast presses.
    val railFocused = railHasFocus.value
    LaunchedEffect(railFocused) {
        if (railFocused) {
            onOpenChanged(true)
        } else {
            delay(NavRailCloseSettleMs)
            onOpenChanged(false)
        }
    }

    val displayedRoute = if (open) highlightedRoute else currentRoute
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
    BackHandler(enabled = open) {
        // Hand focus back first. The rail closes because it stopped owning focus, which keeps the
        // drawer and the highlight from ever disagreeing about where the viewer is. If the page has
        // nothing to take it, close explicitly rather than force-clearing focus and leaving the
        // screen with no focus owner at all.
        if (!onEnterContent(currentRoute)) {
            onOpenChanged(false)
        }
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
            // One observer for the whole rail. `hasFocus` is true for any descendant, so travelling
            // between nav items does not register here at all — which is the point. Only focus
            // genuinely arriving at or leaving the rail moves the open state.
            .onFocusChanged { state -> railHasFocus.value = state.hasFocus }
            .focusProperties {
                // There is nothing to the left of the menu. Without this a Left press from a nav
                // item ran an ordinary spatial search, which on some layouts found page content
                // underneath the rail and took focus out of an open drawer.
                exit = { direction ->
                    if (direction == androidx.compose.ui.focus.FocusDirection.Left) {
                        FocusRequester.Cancel
                    } else {
                        FocusRequester.Default
                    }
                }
            }
            // A group, so nothing above treats the five items as loose neighbours of page content.
            // Where the highlight lands on the way in is decided explicitly by the effect above,
            // not by a spatial search or by the group's entry rule — which turned out not to be
            // consulted for a programmatic request, and quietly answered "Home" every time.
            .focusGroup()
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
                    // A collapsed rail is not a focus target at all.
                    //
                    // This is the other half of the shell's boundary handler: with nothing here
                    // focusable while the menu is shut, no spatial search — including Compose's own
                    // recovery when a screen is disposed mid-transition — can put the highlight
                    // inside a drawer the viewer cannot see. The one way in is
                    // `openSideNavigation`, which claims the region first and so has already made
                    // these focusable by the time it asks for focus a frame later.
                    .focusProperties { canFocus = open }
                    // Vertical movement inside the rail is named, never searched for.
                    //
                    // Spatial focus search runs against the laid-out tree, and while the rail is
                    // animating its width that tree includes page content sitting under it. On Fire
                    // TV the search picked the page for a frame — the drawer collapsed, and the
                    // press that caused it appeared to do nothing. Every direction is answered here
                    // and every press is consumed, so the search never runs and the drawer never
                    // sees focus leave for a frame it did not mean to.
                    .focusProperties {
                        up = destinations.getOrNull(index - 1)?.let { itemRequesters[it] }
                            ?: FocusRequester.Cancel
                        down = destinations.getOrNull(index + 1)?.let { itemRequesters[it] }
                            ?: FocusRequester.Cancel
                        left = FocusRequester.Cancel
                    }
                    .onPreviewKeyEvent { event ->
                        when (event.key) {
                            // Consumed on both edges. The framework move is driven by the
                            // focusProperties above; letting the release through as well gave
                            // tv-material a chance to fire a click on whatever landed under it.
                            Key.DirectionUp, Key.DirectionDown -> {
                                if (event.type == KeyEventType.KeyDown) {
                                    val target = destinations.getOrNull(
                                        if (event.key == Key.DirectionUp) index - 1 else index + 1,
                                    )
                                    target?.let { itemRequesters[it] }?.requestFocusOrFalse()
                                }
                                true
                            }
                            Key.DirectionLeft -> true
                            Key.DirectionRight -> {
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                                // Back to the page the viewer is actually on, not the one the
                                // highlighted item would open. On a top-level screen those are usually
                                // the same and the difference never showed; on a title page every item
                                // names somewhere else, so this looked up a requester belonging to a
                                // screen that was not composed, failed, and left the viewer in the menu
                                // with no way out.
                                // Closing follows the focus leaving, never the press itself. If the
                                // page has nothing to take it the menu simply stays — a drawer that
                                // collapsed here would leave the highlight inside a hidden rail.
                                if (!onEnterContent(currentRoute)) onEnterContent(destination.route)
                                true
                            }
                            else -> false
                        }
                    }
                    .onFocusChanged {
                        // Which item is highlighted, and nothing else. The drawer's open state is
                        // the container's business — see the observer on the Column above.
                        if (it.isFocused) highlightedRoute = destination.route
                    }
                    .clickable { onNavigate(destination.route) }
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
                                contentDescription = stringResource(destination.labelRes),
                                tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                if (labelAlpha > 0.01f) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(destination.labelRes),
                        color = (if (highlighted) Color.White else MaterialTheme.colorScheme.onBackground)
                            .copy(alpha = labelAlpha),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        softWrap = false,
                        // The expanded rail is a fixed width, and "Einstellungen" is half again as
                        // long as "Settings". Ellipsis rather than a hard clip, so a label that
                        // does not fit still ends somewhere a viewer can read.
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    }
}


























