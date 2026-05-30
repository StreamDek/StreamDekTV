package com.streamdek.tv.nativeapp.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.streamdek.tv.nativeapp.AppGraph
import com.streamdek.tv.nativeapp.data.PlaybackRequest
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.ui.account.AccountScreen
import com.streamdek.tv.nativeapp.ui.auth.AuthScreen
import com.streamdek.tv.nativeapp.ui.detail.DetailScreen
import com.streamdek.tv.nativeapp.ui.detail.PlaybackStreamsScreen
import com.streamdek.tv.nativeapp.ui.home.HomeScreen
import com.streamdek.tv.nativeapp.ui.library.LibraryScreen
import com.streamdek.tv.nativeapp.ui.network.NetworkBrowseScreen
import com.streamdek.tv.nativeapp.ui.player.PlayerScreen
import com.streamdek.tv.nativeapp.ui.search.SearchScreen
import com.streamdek.tv.nativeapp.update.AppUpdateManager
import com.streamdek.tv.nativeapp.update.AppUpdateUiState
import kotlinx.coroutines.launch
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text

private enum class TopLevelDestination(val route: String, val label: String, val icon: String, val width: androidx.compose.ui.unit.Dp) {
    Home("home", "Home", "⌂", 106.dp),
    Search("search", "Search", "⌕", 112.dp),
    Library("library", "Library", "▦", 114.dp),
    Profile("profile", "", "", 52.dp),
}

@Composable
fun StreamDekTvApp(repository: StreamDekRepository = remember { AppGraph.repository }) {
    val navController = rememberNavController()
    val appUpdateManager = remember { AppGraph.appUpdateManager }
    val session by repository.session.collectAsState()
    val bootstrap by repository.bootstrap.collectAsState()
    val appUpdateState by appUpdateManager.uiState.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val activeProfile = repository.activeStreamProfile(bootstrap)

    LaunchedEffect(session?.user?.uid) {
        if (session != null) {
            repository.refreshBootstrap()
        }
    }

    LaunchedEffect(Unit) {
        appUpdateManager.runAutomaticCheck()
    }

    StreamDekTvTheme {
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
                        onOpenDetail = { mediaType, mediaId ->
                            navController.navigate("detail/$mediaType/$mediaId")
                        },
                        onOpenNetwork = { networkId, networkName ->
                            navController.navigate("network/$networkId/${Uri.encode(networkName)}")
                        },
                        onOpenAccount = {
                            navController.navigate(TopLevelDestination.Profile.route)
                        },
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
                composable(TopLevelDestination.Library.route) {
                    LibraryScreen(
                        repository = repository,
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
                            repository.savePlaybackRequest(request)
                            navController.navigate("streams")
                        },
                        onRequireAuth = {
                            navController.navigate("auth")
                        },
                    )
                }
            }

            if (
                currentRoute != "player" &&
                currentRoute != "streams" &&
                appUpdateState.showPrompt &&
                appUpdateState.availableRelease != null
            ) {
                AppUpdatePrompt(
                    state = appUpdateState,
                    updateManager = appUpdateManager,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 42.dp),
                )
            }

            if (currentRoute in TopLevelDestination.entries.map { it.route }) {
                TvFloatingNav(
                    avatarIndex = activeProfile?.avatarIndex ?: 0,
                    avatarLabel = activeProfile?.name ?: "P",
                    currentRoute = currentRoute.orEmpty(),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 34.dp, end = 42.dp),
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
        }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB8000000)),
    )

    Column(
        modifier = modifier
            .fillMaxWidth(0.54f)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF10141B))
            .border(2.dp, Color(0x66F0BA66), RoundedCornerShape(28.dp))
            .padding(horizontal = 24.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Update Available",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "StreamDek TV ${release.versionName} is ready to install.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.86f),
        )
        release.releaseNotes?.takeIf { it.isNotBlank() }?.let { notes ->
            Text(
                text = notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.74f),
            )
        }
        state.statusText?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFF0BA66),
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
                enabled = !release.required,
                shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
            ) {
                Text("Later")
            }
        }
    }
}

@Composable
private fun TvFloatingNav(
    avatarIndex: Int,
    avatarLabel: String,
    currentRoute: String,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit,
) {
    val slotWidths = TopLevelDestination.entries.map { it.width }
    var highlightedRoute by remember(currentRoute) { mutableStateOf(currentRoute) }
    val activeIndex = TopLevelDestination.entries.indexOfFirst { it.route == highlightedRoute }.coerceAtLeast(0)
    val activeOffset = slotWidths.take(activeIndex).fold(0.dp) { acc, width -> acc + width + 6.dp }
    val animatedOffset by animateDpAsState(activeOffset, label = "nav-pill-offset")
    var navHasFocus by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .onFocusChanged { navHasFocus = it.hasFocus }
            .border(
                width = if (navHasFocus) 2.dp else 0.dp,
                color = if (navHasFocus) Color(0x90F0BA66) else Color.Transparent,
                shape = RoundedCornerShape(999.dp),
            )
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xE611141B))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .offset(x = animatedOffset)
                .height(40.dp)
                .width(slotWidths[activeIndex])
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xFFF4EDE2)),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TopLevelDestination.entries.forEach { destination ->
                val active = destination.route == highlightedRoute
                if (destination == TopLevelDestination.Profile) {
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .width(slotWidths[3])
                            .clip(RoundedCornerShape(999.dp))
                            .onFocusChanged { if (it.isFocused) highlightedRoute = destination.route }
                            .clickable { onNavigate(destination.route) },
                        contentAlignment = Alignment.Center,
                    ) {
                        ProfileAvatarCircle(
                            avatarIndex = avatarIndex,
                            fallbackLabel = avatarLabel,
                            size = 28.dp,
                        )
                    }
                } else {
                    NavTextItem(
                        destination = destination,
                        active = active,
                        modifier = Modifier
                            .height(40.dp)
                            .width(destination.width)
                            .clip(RoundedCornerShape(999.dp))
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
            text = destination.icon,
            color = if (active) Color(0xFF18120A) else MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            destination.label,
            color = if (active) Color(0xFF18120A) else MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        )
    }
}
