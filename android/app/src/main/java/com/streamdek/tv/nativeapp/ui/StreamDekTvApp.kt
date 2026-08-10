package com.streamdek.tv.nativeapp.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import com.streamdek.tv.nativeapp.data.LiveCatalogSection
import com.streamdek.tv.nativeapp.data.mapAddonCatalogType
import com.streamdek.tv.nativeapp.data.PlaybackHandoff
import com.streamdek.tv.nativeapp.data.PlaybackRequest
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import com.streamdek.tv.nativeapp.data.StreamDekRepository
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
)

private data class LiveBrowseSelection(
    val addonId: String? = null,
    val catalogId: String? = null,
)
private const val ExitBackPressWindowMs = 2500L
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
    val homeContentRequester = remember { FocusRequester() }
    val searchContentRequester = remember { FocusRequester() }
    val liveContentRequester = remember { FocusRequester() }
    val libraryContentRequester = remember { FocusRequester() }
    val profileNavRequester = remember { FocusRequester() }
    val settingsContentRequester = remember { FocusRequester() }
    var liveNavigationState by remember { mutableStateOf(LiveNavigationState()) }
    var loadedLiveCatalogKey by remember { mutableStateOf<String?>(null) }
    var liveBrowseSelection by remember { mutableStateOf(LiveBrowseSelection()) }
    var handledHandoffId by remember(session?.user?.uid) { mutableStateOf<String?>(null) }
    var pendingHandoff by remember(session?.user?.uid) { mutableStateOf<PlaybackHandoff?>(null) }
    var handoffProcessing by remember(session?.user?.uid) { mutableStateOf(false) }
    var handoffError by remember(session?.user?.uid) { mutableStateOf<String?>(null) }
    val handoffScope = rememberCoroutineScope()
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
        if (session != null) {
            runCatching { repository.refreshBootstrap() }
                .onFailure { TvDebugLogger.e("Handoff", "Could not refresh the TV handoff key registration", it) }
        }
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
        liveNavigationState = liveNavigationState.copy(loading = true)
        val sections = runCatching { repository.fetchLiveCatalogSections() }.getOrDefault(emptyList())
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

    LaunchedEffect(session?.user?.uid) {
        if (session != null) {
            runCatching { repository.refreshBootstrap() }
                .onFailure { TvDebugLogger.e("Bootstrap", "Could not refresh account bootstrap", it) }
        }
    }

    LaunchedEffect(Unit) {
        // Defer non-critical OTA work until the shell has painted and the user
        // has had a chance to begin navigating.
        delay(3500)
        appUpdateManager.runAutomaticCheck()
    }

    LaunchedEffect(preferredStartRoute, currentRoute, startScreenApplied) {
        if (!startScreenApplied && currentRoute == TopLevelDestination.Home.route) {
            startScreenApplied = true
            if (preferredStartRoute != TopLevelDestination.Home.route) {
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

    LaunchedEffect(exitHintVisible) {
        if (!exitHintVisible) return@LaunchedEffect
        delay(ExitBackPressWindowMs)
        exitHintVisible = false
        lastExitBackPressAt = 0L
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            NavHost(
                navController = navController,
                startDestination = TopLevelDestination.Home.route,
                modifier = Modifier.padding(
                    start = if (currentRoute in topLevelDestinations.map { it.route }) 68.dp else 0.dp,
                ),
            ) {
                composable(TopLevelDestination.Home.route) {
                    HomeScreen(
                        repository = repository,
                        entryFocusRequester = homeContentRequester,
                        onOpenDetail = { mediaType, mediaId ->
                            navController.navigate("detail/$mediaType/$mediaId")
                        },
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
                composable(TopLevelDestination.Search.route) {
                    SearchScreen(
                        repository = repository,
                        entryFocusRequester = searchContentRequester,
                        onOpenDetail = { mediaType, mediaId ->
                            navController.navigate("detail/$mediaType/$mediaId")
                        },
                        onPlayLive = playLiveItem,
                    )
                }
                composable(TopLevelDestination.Live.route) {
                    LiveScreen(
                        sections = liveNavigationState.sections,
                        isLoading = liveNavigationState.loading,
                        compactMode = appPrefs?.compactMode == true,
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
                composable("live-view-all") {
                    LiveBrowseScreen(
                        sections = liveNavigationState.sections,
                        initialAddonId = liveBrowseSelection.addonId,
                        initialCatalogId = liveBrowseSelection.catalogId,
                        favouriteKeys = favouriteChannelKeys,
                        onToggleFavourite = repository::toggleFavouriteChannel,
                        onPlayLive = playLiveItem,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(TopLevelDestination.Library.route) {
                    LibraryScreen(
                        repository = repository,
                        entryFocusRequester = libraryContentRequester,
                        onOpenDetail = { mediaType, mediaId ->
                            navController.navigate("detail/$mediaType/$mediaId")
                        },
                    )
                }
                composable(TopLevelDestination.Profile.route) {
                    SettingsScreen(
                        repository = repository,
                        appUpdateManager = appUpdateManager,
                        navFocusRequester = profileNavRequester,
                        entryFocusRequester = settingsContentRequester,
                        onSignIn = { navController.navigate("auth") },
                    )
                }
                composable("network/{id}/{name}") { backStackEntryInner ->
                    NetworkBrowseScreen(
                        repository = repository,
                        networkId = backStackEntryInner.arguments?.getString("id").orEmpty(),
                        networkName = Uri.decode(backStackEntryInner.arguments?.getString("name").orEmpty()),
                        onBack = { navController.popBackStack() },
                        onOpenDetail = { mediaType, mediaId ->
                            navController.navigate("detail/$mediaType/$mediaId")
                        },
                    )
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
                    val request = repository.consumePlaybackRequest()
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
                    val request = repository.currentPlaybackRequest()
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
                composable("detail/{type}/{id}") { backStackEntryInner ->
                    DetailScreen(
                        repository = repository,
                        mediaType = backStackEntryInner.arguments?.getString("type").orEmpty(),
                        mediaId = backStackEntryInner.arguments?.getString("id").orEmpty(),
                        onBack = { navController.popBackStack() },
                        onOpenDetail = { mediaType, mediaId ->
                            navController.navigate("detail/$mediaType/$mediaId")
                        },
                        onPlay = { request: PlaybackRequest ->
                            val useAutoSelection = bootstrap?.preferences?.playback?.manualStreamSelectionEnabled == false
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

            CurrentTimePill(
                modifier = Modifier
                    .align(if (currentRoute == "player") Alignment.TopStart else Alignment.TopEnd)
                    .padding(
                        top = 22.dp,
                        start = if (currentRoute == "player") 26.dp else 0.dp,
                        end = if (currentRoute == "player") 0.dp else 26.dp,
                    ),
            )

            if (currentRoute in topLevelDestinations.map { it.route } && !showUpdatePrompt) {
                TvSideNav(
                    destinations = topLevelDestinations,
                    avatarIndex = activeProfile?.avatarIndex ?: 0,
                    avatarLabel = activeProfile?.name ?: "P",
                    profileFocusRequester = profileNavRequester,
                    currentRoute = currentRoute.orEmpty(),
                    contentRequesters = mapOf(
                        TopLevelDestination.Home.route to homeContentRequester,
                        TopLevelDestination.Search.route to searchContentRequester,
                        TopLevelDestination.Live.route to liveContentRequester,
                        TopLevelDestination.Library.route to libraryContentRequester,
                        TopLevelDestination.Profile.route to settingsContentRequester,
                    ),
                    modifier = Modifier.align(Alignment.CenterStart),
                    onNavigate = { route ->
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
                Text("Continue on this TV?", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black))
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

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TvSideNav(
    destinations: List<TopLevelDestination>,
    avatarIndex: Int,
    avatarLabel: String,
    profileFocusRequester: FocusRequester,
    currentRoute: String,
    contentRequesters: Map<String, FocusRequester>,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit,
) {
    var highlightedRoute by remember { mutableStateOf(currentRoute) }
    var navHasFocus by remember { mutableStateOf(false) }
    val itemRequesters = remember(destinations, profileFocusRequester) {
        destinations.associateWith { destination ->
            if (destination == TopLevelDestination.Profile) profileFocusRequester else FocusRequester()
        }
    }
    val railWidth by animateDpAsState(
        targetValue = if (navHasFocus) 196.dp else 64.dp,
        animationSpec = tween(TvMotion.duration(150)),
        label = "side-nav-width",
    )

    LaunchedEffect(currentRoute) {
        highlightedRoute = currentRoute
    }

    val displayedRoute = if (navHasFocus) highlightedRoute else currentRoute
    Column(
        modifier = modifier
            .width(railWidth)
            .fillMaxHeight()
            .focusProperties {
                enter = {
                    itemRequesters[destinations.firstOrNull { it.route == currentRoute }]
                        ?: FocusRequester.Default
                }
            }
            .onFocusChanged { navHasFocus = it.hasFocus }
            .background(TvChromeSurface)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        destinations.forEach { destination ->
            val highlighted = destination.route == displayedRoute
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent,
                    )
                    .focusRequester(itemRequesters.getValue(destination))
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                            contentRequesters[destination.route]
                                ?.let { requester -> runCatching { requester.requestFocus() }.isSuccess }
                                ?: false
                        } else {
                            false
                        }
                    }
                    .onFocusChanged {
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
                                contentDescription = destination.label,
                                tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                if (navHasFocus) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = destination.label,
                        color = if (highlighted) Color.White else MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}


























