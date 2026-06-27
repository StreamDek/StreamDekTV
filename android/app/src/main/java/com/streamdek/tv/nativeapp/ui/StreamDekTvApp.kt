package com.streamdek.tv.nativeapp.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.streamdek.tv.nativeapp.data.PlaybackRequest
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.ui.account.AccountScreen
import com.streamdek.tv.nativeapp.ui.auth.AuthScreen
import com.streamdek.tv.nativeapp.ui.detail.DetailScreen
import com.streamdek.tv.nativeapp.ui.detail.PlaybackStreamsScreen
import com.streamdek.tv.nativeapp.ui.home.HomeScreen
import com.streamdek.tv.nativeapp.ui.library.LibraryScreen
import com.streamdek.tv.nativeapp.ui.live.LiveScreen
import com.streamdek.tv.nativeapp.ui.network.NetworkBrowseScreen
import com.streamdek.tv.nativeapp.ui.player.PlayerScreen
import com.streamdek.tv.nativeapp.ui.search.SearchScreen
import com.streamdek.tv.nativeapp.update.AppUpdateManager
import com.streamdek.tv.nativeapp.update.AppUpdateUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class TopLevelDestination(val route: String, val label: String, val width: androidx.compose.ui.unit.Dp) {
    Home("home", "Home", 84.dp),
    Search("search", "Search", 90.dp),
    Live("live", "Live", 74.dp),
    Library("library", "Library", 92.dp),
    Profile("profile", "", 42.dp),
}

private data class LiveNavigationState(
    val loading: Boolean = true,
    val sections: List<LiveCatalogSection> = emptyList(),
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
    val appUpdateState by appUpdateManager.uiState.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val activeProfile = repository.activeStreamProfile(bootstrap)
    val appPrefs = bootstrap?.preferences?.app
    val homeContentRequester = remember { FocusRequester() }
    val liveContentRequester = remember { FocusRequester() }
    val libraryContentRequester = remember { FocusRequester() }
    var liveNavigationState by remember { mutableStateOf(LiveNavigationState()) }
    val liveAddonKey = remember(bootstrap) {
        bootstrap?.integrations?.addons?.items.orEmpty().joinToString("|") {
            "${it.id}:${it.enabled}:${it.position}:${it.manifest.catalogs.size}"
        }
    }

    LaunchedEffect(session?.user?.uid, activeProfile?.id, liveAddonKey) {
        liveNavigationState = liveNavigationState.copy(loading = true)
        val sections = runCatching { repository.fetchLiveCatalogSections() }.getOrDefault(emptyList())
        liveNavigationState = LiveNavigationState(
            loading = false,
            sections = sections,
        )
    }

    val showLiveDestination = liveNavigationState.sections.any { section ->
        section.rails.any { rail -> rail.items.isNotEmpty() }
    }
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

    LaunchedEffect(session?.user?.uid) {
        if (session != null) {
            repository.refreshBootstrap()
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

    LaunchedEffect(showLiveDestination, currentRoute) {
        if (!showLiveDestination && currentRoute == TopLevelDestination.Live.route) {
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

    StreamDekTvTheme(themeKey = appPrefs?.theme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            NavHost(
                navController = navController,
                startDestination = TopLevelDestination.Home.route,
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

                    )
                }
                composable(TopLevelDestination.Search.route) {
                    SearchScreen(
                        repository = repository,
                        onOpenDetail = { mediaType, mediaId ->
                            navController.navigate("detail/$mediaType/$mediaId")
                        },
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
                        onItemFocused = { key ->
                            lastLiveFocusedItemKey = key
                        },
                        onPlayLive = playLiveItem,
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
                    AccountScreen(
                        repository = repository,
                        appUpdateManager = appUpdateManager,
                        onBack = {
                            navController.navigate(TopLevelDestination.Home.route) {
                                popUpTo(TopLevelDestination.Home.route) { inclusive = false }
                                launchSingleTop = true
                            }
                        },
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
                TvFloatingNav(
                    destinations = topLevelDestinations,
                    avatarIndex = activeProfile?.avatarIndex ?: 0,
                    avatarLabel = activeProfile?.name ?: "P",
                    currentRoute = currentRoute.orEmpty(),
                    downRequesters = mapOf(
                        TopLevelDestination.Home.route to homeContentRequester,
                        TopLevelDestination.Live.route to liveContentRequester,
                        TopLevelDestination.Library.route to libraryContentRequester,
                    ),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
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
private fun ExitBackHint(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xEE10141B))
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
                    .background(Color(0xFF10141B))
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

@Composable
private fun TvFloatingNav(
    destinations: List<TopLevelDestination>,
    avatarIndex: Int,
    avatarLabel: String,
    currentRoute: String,
    downRequesters: Map<String, FocusRequester>,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit,
) {
    val slotWidths = destinations.map { it.width }
    var highlightedRoute by remember { mutableStateOf(currentRoute) }

    LaunchedEffect(currentRoute) {
        highlightedRoute = currentRoute
    }
    val activeIndex = destinations.indexOfFirst { it.route == highlightedRoute }.let { if (it >= 0) it else 0 }
    val activeOffset = slotWidths.take(activeIndex).fold(0.dp) { acc, width -> acc + width + 4.dp }
    val animatedOffset by animateDpAsState(activeOffset, label = "nav-pill-offset")
    var navHasFocus by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .onFocusChanged { navHasFocus = it.hasFocus }
            .border(
                width = if (navHasFocus) 2.dp else 0.dp,
                color = if (navHasFocus) MaterialTheme.colorScheme.primary.copy(alpha = 0.56f) else Color.Transparent,
                shape = RoundedCornerShape(999.dp),
            )
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xE611141B))
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Box(
            modifier = Modifier
                .offset(x = animatedOffset)
                .height(34.dp)
                .width(slotWidths[activeIndex])
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            destinations.forEach { destination ->
                val active = destination.route == highlightedRoute
                if (destination == TopLevelDestination.Profile) {
                    Box(
                        modifier = Modifier
                            .height(34.dp)
                            .width(destination.width)
                            .clip(RoundedCornerShape(999.dp))
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                    runCatching { downRequesters[destination.route]?.requestFocus() }.isSuccess
                                } else {
                                    false
                                }
                            }
                            .onFocusChanged { if (it.isFocused) highlightedRoute = destination.route }
                            .clickable { onNavigate(destination.route) },
                        contentAlignment = Alignment.Center,
                    ) {
                        ProfileAvatarCircle(
                            avatarIndex = avatarIndex,
                            fallbackLabel = avatarLabel,
                            size = 24.dp,
                        )
                    }
                } else {
                    NavTextItem(
                        destination = destination,
                        active = active,
                        modifier = Modifier
                            .height(34.dp)
                            .width(destination.width)
                            .clip(RoundedCornerShape(999.dp))
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                    runCatching { downRequesters[destination.route]?.requestFocus() }.isSuccess
                                } else {
                                    false
                                }
                            }
                            .onFocusChanged { if (it.isFocused) highlightedRoute = destination.route }
                            .clickable { onNavigate(destination.route) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NavTextItem(
    destination: TopLevelDestination,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            destination.label,
            color = if (active) Color(0xFF18120A) else MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        )
    }
}


























