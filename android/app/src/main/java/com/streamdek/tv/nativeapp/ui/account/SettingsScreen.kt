package com.streamdek.tv.nativeapp.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamdek.tv.BuildConfig
import com.streamdek.tv.nativeapp.data.AccountBootstrap
import com.streamdek.tv.nativeapp.data.AddonManifest
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.SyncServiceId
import com.streamdek.tv.nativeapp.data.TvDebugLogger
import com.streamdek.tv.nativeapp.ui.ProfileAvatarCircle
import com.streamdek.tv.nativeapp.update.AppUpdateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val LocalSettingsLeftRequester = staticCompositionLocalOf<FocusRequester?> { null }

private enum class SettingsDestination(val label: String, val description: String, val terms: String, val icon: ImageVector) {
    Accounts("Accounts", "Profiles and household access", "profile pin sign in", Icons.Outlined.AccountCircle),
    Tracking("Tracking", "Watch history and cloud services", "trakt simkl mdblist sync", Icons.Outlined.Sync),
    Playback("Playback", "Player, quality, audio and episodes", "decoder subtitles autoplay intro", Icons.Outlined.PlayArrow),
    Library("Library", "Cards, density and browsing layout", "poster landscape grid collections", Icons.Outlined.VideoLibrary),
    Providers("Providers", "Add-ons, streams and debrid status", "sources fusion badges", Icons.Outlined.Extension),
    Appearance("Appearance", "Theme, motion and presentation", "accent animation blur start", Icons.Outlined.Palette),
    Accessibility("Accessibility", "Contrast, text and reduced motion", "vision screen reader", Icons.Outlined.Accessibility),
    Devices("Devices", "Connected televisions and sessions", "cloud sync", Icons.Outlined.Devices),
    Advanced("Advanced", "Navigation and TV behaviour", "developer experimental compact", Icons.Outlined.Settings),
    Diagnostics("Diagnostics", "Health, cache and recent events", "performance network logs", Icons.Outlined.BugReport),
    About("About", "Version information and updates", "release", Icons.Outlined.Info),
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    repository: StreamDekRepository,
    appUpdateManager: AppUpdateManager,
    navFocusRequester: FocusRequester,
    entryFocusRequester: FocusRequester,
    onSignIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val session by repository.session.collectAsState()
    val reachability by repository.reachability.collectAsState()
    val updateState by appUpdateManager.uiState.collectAsState()
    var bootstrap by remember { mutableStateOf<AccountBootstrap?>(repository.bootstrap.value) }
    var addons by remember { mutableStateOf<List<AddonManifest>>(emptyList()) }
    var selected by remember { mutableStateOf(SettingsDestination.Accounts) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val contentEntryRequester = remember { FocusRequester() }
    val destinationRequesters = remember(entryFocusRequester) {
        SettingsDestination.entries.associateWith { if (it == SettingsDestination.Accounts) entryFocusRequester else FocusRequester() }
    }
    val contentScroll = rememberScrollState()

    LaunchedEffect(Unit) {
        bootstrap = repository.refreshBootstrap()
        addons = repository.fetchAddonManifests()
        delay(120)
        runCatching { destinationRequesters.getValue(SettingsDestination.Accounts).requestFocus() }
    }
    LaunchedEffect(selected) { contentScroll.scrollTo(0) }

    val visible = SettingsDestination.entries.filter {
        query.isBlank() || it.label.contains(query, true) || it.description.contains(query, true) || it.terms.contains(query, true)
    }
    LaunchedEffect(visible) { if (visible.isNotEmpty() && selected !in visible) selected = visible.first() }
    val selectedRequester = destinationRequesters.getValue(selected)
    val activeProfile = repository.activeStreamProfile(bootstrap)
    val prefs = bootstrap?.preferences
    val appPrefs = prefs?.app
    val playbackPrefs = prefs?.playback
    val streamsPrefs = prefs?.streams

    CompositionLocalProvider(LocalSettingsLeftRequester provides selectedRequester) {
    Row(
        Modifier.fillMaxSize().background(Color(0xFF07090D)).padding(start = 18.dp, end = 92.dp, top = 18.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(Modifier.width(238.dp).fillMaxHeight()) {
            Text("Settings", color = Color.White, style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black))
            Text("Fast, focused, TV-first controls", color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
            SettingsSearchBox(query, { query = it }, navFocusRequester, contentEntryRequester)
            Column(
                Modifier.weight(1f).padding(top = 12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                visible.forEach { destination ->
                    SettingsDestinationRow(
                        destination = destination,
                        selected = selected == destination,
                        requester = destinationRequesters.getValue(destination),
                        navRequester = navFocusRequester,
                        contentRequester = contentEntryRequester,
                        onFocused = { selected = destination },
                    )
                }
                if (visible.isEmpty()) Text("No settings match", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(14.dp))
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0x18FFFFFF)))
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(contentScroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsOverviewCard(selected, status, contentEntryRequester, selectedRequester)
            when (selected) {
                SettingsDestination.Accounts -> {
                    SettingsPanel("Active profile") {
                        if (activeProfile != null) {
                            var profileSummaryFocused by remember(activeProfile.id) { mutableStateOf(false) }
                            Row(
                                modifier = Modifier.fillMaxWidth().background(if (profileSummaryFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).onFocusChanged { profileSummaryFocused = it.isFocused }.onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && runCatching { selectedRequester.requestFocus() }.isSuccess }.focusable().padding(7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ProfileAvatarCircle(activeProfile.avatarIndex, activeProfile.name, 48.dp)
                                Column {
                                    Text(activeProfile.name, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                    Text(if (activeProfile.isDefault) "Default profile" else "Ready to watch", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        InfoLine("Email", session?.user?.email ?: "Not signed in")
                        InfoLine("Subscription", session?.user?.subscriptionStatus ?: "Free")
                    }
                    if (session == null) {
                        SettingsActionRow("Sign in or link this TV", "Sync profiles, library and providers", "Open", selectedRequester, onClick = onSignIn)
                    } else {
                        bootstrap?.streamProfiles.orEmpty().forEach { profile ->
                            SettingsActionRow(profile.name, if (profile.id == activeProfile?.id) "Current household profile" else "Switch to this profile", if (profile.id == activeProfile?.id) "Active" else "Use", selectedRequester) {
                                scope.launch { repository.setActiveStreamProfile(profile.id); bootstrap = repository.refreshBootstrap(); status = "Using ${profile.name}." }
                            }
                        }
                        SettingsActionRow("Sign out", "Remove this account from the television", "Sign out", selectedRequester) {
                            repository.signOut(); bootstrap = null; addons = emptyList(); status = "Signed out from this TV."
                        }
                    }
                }
                SettingsDestination.Tracking -> {
                    val integrations = bootstrap?.integrations
                    SettingsPanel("Cloud tracking") {
                        InfoLine("Primary", SyncServiceId.label(SyncServiceId.normalize(prefs?.home?.primarySyncService)))
                        InfoLine("Trakt", serviceStatus(integrations?.trakt?.connected == true, integrations?.trakt?.username))
                        InfoLine("Simkl", serviceStatus(integrations?.simkl?.connected == true, integrations?.simkl?.username))
                        InfoLine("MDBList", serviceStatus(integrations?.mdblist?.connected == true, integrations?.mdblist?.username))
                    }
                    SettingsActionRow("Refresh tracking", "Pull the latest cloud connections", "Refresh", selectedRequester) {
                        scope.launch { bootstrap = repository.refreshBootstrap(); status = "Tracking refreshed." }
                    }
                }
                SettingsDestination.Playback -> {
                    SettingsPanel("Cloud playback profile") {
                        InfoLine("Player", playbackPrefs?.playerEngine ?: "Auto")
                        InfoLine("Decoder", playbackPrefs?.decoderMode ?: "Auto")
                        InfoLine("Quality", playbackPrefs?.preferredQuality ?: "1080p")
                        InfoLine("Audio", playbackPrefs?.defaultAudioLanguage ?: "Auto")
                        InfoLine("Autoplay next", onOff(playbackPrefs?.isAutoPlayNextEpisodeEnabled() == true))
                        InfoLine("Skip intro", onOff(playbackPrefs?.isSegmentEnabled("intro") != false))
                        InfoLine("Skip recap", onOff(playbackPrefs?.isSegmentEnabled("recap") != false))
                        InfoLine("Skip credits", onOff(playbackPrefs?.isSegmentEnabled("outro") != false))
                    }
                    SettingsActionRow("Refresh playback", "Defaults are managed on mobile and synced here", "Refresh", selectedRequester) {
                        scope.launch { bootstrap = repository.refreshBootstrap(); status = "Playback refreshed." }
                    }
                }
                SettingsDestination.Library -> {
                    SettingsDropdownRow("Card format", "Landscape or portrait library artwork", appPrefs?.homeRowCardStyle ?: "landscape", listOf("landscape" to "Landscape", "portrait" to "Portrait")) { value ->
                        scope.launch { repository.updateAppPreferences(mapOf("homeRowCardStyle" to value)); bootstrap = repository.bootstrap.value }
                    }
                    SettingsDropdownRow("Card density", "Comfortable or compact browsing", appPrefs?.cardDensity ?: "comfortable", listOf("comfortable" to "Comfortable", "compact" to "Compact")) { value ->
                        scope.launch { repository.updateAppPreferences(mapOf("cardDensity" to value)); bootstrap = repository.bootstrap.value }
                    }
                    SettingsDropdownRow("Grid columns", "Balance artwork size and visible items", (appPrefs?.gridSize ?: 5).toString(), (4..7).map { it.toString() to "$it columns" }) { value ->
                        scope.launch { repository.updateAppPreferences(mapOf("gridSize" to value.toInt())); bootstrap = repository.bootstrap.value }
                    }
                }
                SettingsDestination.Providers -> {
                    SettingsPanel("Synced providers") {
                        InfoLine("Enabled add-ons", "${addons.count { it.enabled }} of ${addons.size}")
                        addons.take(8).forEach { InfoLine(it.manifest.name, if (it.enabled) "Enabled" else "Disabled") }
                        if (addons.isEmpty()) InfoLine("Providers", "None synced")
                    }
                    SettingsPanel("Debrid accounts") {
                        val accounts = bootstrap?.integrations?.debrid?.accounts.orEmpty()
                        if (accounts.isEmpty()) InfoLine("Accounts", "None linked")
                        accounts.forEach { account -> InfoLine(account.provider, if (account.enabled) account.username ?: "Priority ${account.priority + 1}" else "Disabled") }
                    }
                    SettingsPanel("Stream display") {
                        InfoLine("Fusion badges", onOff(streamsPrefs?.fusionBadgesEnabled != false))
                        InfoLine("Size badges", onOff(streamsPrefs?.showSizeBadges != false))
                        InfoLine("Badge position", streamsPrefs?.badgePosition ?: "Bottom")
                    }
                    SettingsActionRow("Refresh providers", "Pull add-ons and stream settings from the cloud", "Refresh", selectedRequester) {
                        scope.launch { bootstrap = repository.refreshBootstrap(); addons = repository.fetchAddonManifests(forceRefresh = true); status = "Providers refreshed." }
                    }
                }
                SettingsDestination.Appearance -> {
                    SettingsDropdownRow("Theme", "Change the visual colour system", appPrefs?.theme ?: "cinema-blue", listOf("streamdek" to "StreamDek", "cinema-blue" to "Cinema blue", "carbon-gold" to "Carbon gold", "frost-neon" to "Frost neon", "ember-red" to "Ember red", "aurora-green" to "Aurora green", "violet-pulse" to "Violet pulse")) { value ->
                        scope.launch { repository.updateAppPreferences(mapOf("theme" to value)); bootstrap = repository.bootstrap.value }
                    }
                    SettingsDropdownRow("Start screen", "Choose where StreamDek opens", appPrefs?.startScreen ?: "home", listOf("home" to "Home", "library" to "Library", "continue-watching" to "Continue watching")) { value ->
                        scope.launch { repository.updateAppPreferences(mapOf("startScreen" to value)); bootstrap = repository.bootstrap.value }
                    }
                    SettingsDropdownRow("Animation speed", "GPU-friendly transitions for this device", appPrefs?.animationSpeed ?: "normal", listOf("normal" to "Normal", "fast" to "Fast", "slow" to "Slow")) { value ->
                        scope.launch { repository.updateAppPreferences(mapOf("animationSpeed" to value)); bootstrap = repository.bootstrap.value }
                    }
                    SettingsActionRow("Background depth", "Subtle cinematic depth behind content", onOff(appPrefs?.backgroundBlur != false), selectedRequester) {
                        scope.launch { repository.updateAppPreferences(mapOf("backgroundBlur" to (appPrefs?.backgroundBlur == false))); bootstrap = repository.bootstrap.value }
                    }
                }
                SettingsDestination.Accessibility -> {
                    SettingsActionRow("High contrast", "Increase separation between controls", onOff(appPrefs?.highContrast == true), selectedRequester) {
                        scope.launch { repository.updateAppPreferences(mapOf("highContrast" to !(appPrefs?.highContrast == true))); bootstrap = repository.bootstrap.value }
                    }
                    SettingsActionRow("Large text", "Increase primary interface text", onOff(appPrefs?.largeText == true), selectedRequester) {
                        scope.launch { repository.updateAppPreferences(mapOf("largeText" to !(appPrefs?.largeText == true))); bootstrap = repository.bootstrap.value }
                    }
                    SettingsActionRow("Reduced motion", "Limit scaling and transitions", onOff(appPrefs?.reducedMotion == true), selectedRequester) {
                        scope.launch { repository.updateAppPreferences(mapOf("reducedMotion" to !(appPrefs?.reducedMotion == true))); bootstrap = repository.bootstrap.value }
                    }
                    SettingsPanel("TV navigation") { InfoLine("Focus indicator", "Always visible"); InfoLine("Screen reader labels", "Enabled"); InfoLine("Colour-only status", "Never used") }
                }
                SettingsDestination.Devices -> {
                    SettingsPanel("Sync status") { InfoLine("Settings", bootstrap?.syncStatus?.lastSettingsSyncAt ?: "Ready"); InfoLine("Cloud sync", onOff(bootstrap?.syncStatus?.cloudSyncEnabled != false)); InfoLine("Playback sync", onOff(bootstrap?.syncStatus?.playbackSyncEnabled != false)) }
                    bootstrap?.devices.orEmpty().take(6).forEach { device ->
                        SettingsPanel(device.name ?: "StreamDek device") { InfoLine("Platform", device.platform ?: device.deviceType ?: "Unknown"); InfoLine("Version", device.appVersion ?: "Unknown"); InfoLine("Status", if (device.isCurrent) "This TV" else device.lastSeenAt ?: "Connected") }
                    }
                    bootstrap?.sessions.orEmpty().take(6).forEach { activeSession ->
                        SettingsPanel(activeSession.clientName ?: "Active session") { InfoLine("Platform", activeSession.clientPlatform ?: "Unknown"); InfoLine("Device", activeSession.deviceId ?: "Not reported"); InfoLine("Status", if (activeSession.isCurrent) "Current session" else activeSession.lastSeenAt ?: "Active") }
                    }
                    SettingsActionRow("Refresh devices", "Update devices, sessions and sync status", "Refresh", selectedRequester) { scope.launch { bootstrap = repository.refreshBootstrap(); status = "Devices refreshed." } }
                }
                SettingsDestination.Advanced -> {
                    SettingsPanel("Navigation") { InfoLine("Style", "Collapsible left rail"); InfoLine("Behaviour", "Expands on focus • collapses on exit"); InfoLine("Focus memory", "Current destination") }
                    SettingsActionRow("Legacy compact mode", "Reduce spacing on older low-memory devices", onOff(appPrefs?.compactMode == true), selectedRequester) { scope.launch { repository.updateAppPreferences(mapOf("compactMode" to !(appPrefs?.compactMode == true))); bootstrap = repository.bootstrap.value } }
                    SettingsPanel("Experimental") { InfoLine("Hardware acceleration", "Enabled"); InfoLine("Progressive loading", "Enabled"); InfoLine("Developer controls", "Managed remotely") }
                }
                SettingsDestination.Diagnostics -> {
                    SettingsPanel("Health check") { InfoLine("Backend", reachability.name.lowercase().replaceFirstChar { it.uppercase() }); InfoLine("Authentication", if (session == null) "Guest mode" else "Healthy"); InfoLine("Providers", "${addons.count { it.enabled }} enabled"); InfoLine("Cache", formatBytes(directorySize(context.cacheDir))); InfoLine("Playback", playbackPrefs?.playerEngine ?: "Auto") }
                    SettingsActionRow("Run health check", "Refresh cloud, providers and connectivity", "Run", selectedRequester) { scope.launch { bootstrap = repository.refreshBootstrap(); addons = repository.fetchAddonManifests(); status = "Health checks refreshed." } }
                    SettingsPanel("Recent events") { val events = TvDebugLogger.snapshot(8); if (events.isEmpty()) InfoLine("Log", "No events in this session"); events.asReversed().forEach { InfoLine("${it.level} • ${it.tag}", it.message) } }
                    SettingsActionRow("Clear diagnostic log", "Remove recent in-memory events", "Clear", selectedRequester) { TvDebugLogger.clear(); status = "Diagnostic log cleared." }
                }
                SettingsDestination.About -> {
                    SettingsPanel("StreamDek TV") { InfoLine("Version", BuildConfig.VERSION_NAME); InfoLine("Client", "Android TV / Fire TV"); InfoLine("Profile", activeProfile?.name ?: "Not selected"); InfoLine("Update", updateState.statusText ?: updateState.errorMessage ?: "Ready") }
                    SettingsActionRow("Automatic update checks", "Notify when a release is ready", onOff(updateState.autoCheckEnabled), selectedRequester) { appUpdateManager.setAutoCheckEnabled(!updateState.autoCheckEnabled) }
                    SettingsActionRow("Check for updates", "Query the production TV update channel", "Check", selectedRequester) { scope.launch { appUpdateManager.checkForUpdates(showPromptOnAvailable = false, force = true) } }
                    updateState.availableRelease?.let { release -> SettingsActionRow("Install ${release.versionName}", release.requiredReason ?: "Download the available update", "Install", selectedRequester) { scope.launch { appUpdateManager.startUpdate() } } }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SettingsSearchBox(query: String, onQueryChange: (String) -> Unit, navRequester: FocusRequester, contentRequester: FocusRequester) {
    val keyboard = LocalSoftwareKeyboardController.current
    val launcherRequester = remember { FocusRequester() }
    val editorRequester = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var editorWasFocused by remember { mutableStateOf(false) }
    LaunchedEffect(editing) { if (editing) { delay(60); runCatching { editorRequester.requestFocus() }; keyboard?.show() } }
    val keyHandler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        if (event.type != KeyEventType.KeyDown) false else when (event.key) { Key.DirectionLeft -> runCatching { navRequester.requestFocus() }.isSuccess; Key.DirectionRight -> runCatching { contentRequester.requestFocus() }.isSuccess; else -> false }
    }
    if (editing) {
        OutlinedTextField(
            value = query, onValueChange = onQueryChange, singleLine = true,
            label = { androidx.compose.material3.Text("Find settings") }, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF121722), unfocusedContainerColor = Color(0xFF0E121A), focusedIndicatorColor = MaterialTheme.colorScheme.primary, unfocusedIndicatorColor = Color(0x18FFFFFF), focusedTextColor = Color.White, unfocusedTextColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(50.dp).focusRequester(editorRequester).onFocusChanged { if (it.isFocused) editorWasFocused = true else if (editorWasFocused) { editorWasFocused = false; editing = false } }.onPreviewKeyEvent(keyHandler),
        )
    } else {
        Row(
            Modifier.fillMaxWidth().height(46.dp).background(if (focused) Color(0xFF151C28) else Color(0xFF0D1118), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color(0x18FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).focusRequester(launcherRequester).onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent(keyHandler).clickable { editing = true }.padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) { Icon(Icons.Outlined.Search, null, tint = Color.White.copy(alpha = 0.62f), modifier = Modifier.size(18.dp)); Text(query.ifBlank { "Find settings" }, color = Color.White.copy(alpha = if (query.isBlank()) 0.58f else 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun SettingsDestinationRow(destination: SettingsDestination, selected: Boolean, requester: FocusRequester, navRequester: FocusRequester, contentRequester: FocusRequester, onFocused: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(42.dp).background(when { focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f); selected -> Color(0x16FFFFFF); else -> Color.Transparent }, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).border(if (focused) 2.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).focusRequester(requester).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }.onPreviewKeyEvent { e -> if (e.type != KeyEventType.KeyDown) false else when (e.key) { Key.DirectionLeft -> runCatching { navRequester.requestFocus() }.isSuccess; Key.DirectionRight -> runCatching { contentRequester.requestFocus() }.isSuccess; else -> false } }.clickable(onClick = onFocused).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) { Icon(destination.icon, null, tint = if (focused || selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.55f), modifier = Modifier.size(19.dp)); Text(destination.label, color = Color.White.copy(alpha = if (focused || selected) 0.96f else 0.66f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium)) }
}

@Composable
private fun SettingsOverviewCard(destination: SettingsDestination, status: String?, requester: FocusRequester, leftRequester: FocusRequester) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(Color(0xD4111720), androidx.compose.foundation.shape.RoundedCornerShape(22.dp)).border(1.dp, Color(0x14FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(22.dp)).focusRequester(requester).onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && runCatching { leftRequester.requestFocus() }.isSuccess }.focusable().padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(destination.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(destination.label, color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black)); Text(destination.description, color = Color.White.copy(alpha = 0.64f), style = MaterialTheme.typography.bodyMedium); status?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) } }
        Text("Recommended defaults in context", color = Color.White.copy(alpha = 0.42f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xA80E131B), androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .border(1.dp, Color(0x10FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            title,
            color = Color.White.copy(alpha = 0.88f),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp),
        )
        content()
    }
}
@Composable
private fun InfoLine(label: String, value: String) {
    val leftRequester = LocalSettingsLeftRequester.current
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && leftRequester?.let { runCatching { it.requestFocus() }.isSuccess } == true }
            .focusable()
            .padding(horizontal = 7.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.45f))
        Text(value, color = if (focused) Color.White else Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(0.55f), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsDropdownRow(title: String, description: String, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    val leftRequester = LocalSettingsLeftRequester.current
    var focused by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val displayValue = options.firstOrNull { it.first.equals(value, true) }?.second ?: value
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (focused) Color(0xFF172131) else Color(0xB20E141D), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color(0x10FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && !expanded && leftRequester?.let { runCatching { it.requestFocus() }.isSuccess } == true }
                .clickable { expanded = true }
                .padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Text(description, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("$displayValue  ▾", color = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(300.dp).background(Color(0xFF151B25)),
        ) {
            options.forEach { (optionValue, label) ->
                DropdownMenuItem(
                    text = { androidx.compose.material3.Text(if (optionValue.equals(value, true)) "$label  ✓" else label, color = Color.White) },
                    onClick = { expanded = false; onSelect(optionValue) },
                )
            }
        }
    }
}

@Composable
private fun SettingsActionRow(title: String, description: String, value: String, leftRequester: FocusRequester, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(if (focused) Color(0xFF172131) else Color(0xB20E141D), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color(0x10FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && runCatching { leftRequester.requestFocus() }.isSuccess }.clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) { Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)); Text(description, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }; Text(value, color = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)) }
}
private fun onOff(value: Boolean) = if (value) "On" else "Off"
private fun serviceStatus(connected: Boolean, username: String?) = if (connected) username?.takeIf { it.isNotBlank() } ?: "Connected" else "Not connected"
private fun directorySize(root: java.io.File) = runCatching { root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
private fun formatBytes(bytes: Long): String = when { bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0); bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0); bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0); else -> "$bytes B" }