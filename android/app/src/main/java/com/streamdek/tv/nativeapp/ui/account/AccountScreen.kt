package com.streamdek.tv.nativeapp.ui.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamdek.tv.BuildConfig
import com.streamdek.tv.nativeapp.data.AccountBootstrap
import com.streamdek.tv.nativeapp.data.AddonManifest
import com.streamdek.tv.nativeapp.data.DeviceInfo
import com.streamdek.tv.nativeapp.data.FUSION_BADGE_LANGUAGE_GROUP_ID
import com.streamdek.tv.nativeapp.data.FusionBadgeSource
import com.streamdek.tv.nativeapp.data.MAX_FUSION_BADGE_URLS
import com.streamdek.tv.nativeapp.data.SessionInfo
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.StreamProfile
import com.streamdek.tv.nativeapp.data.StreamsPreferences
import com.streamdek.tv.nativeapp.data.SyncServiceId
import com.streamdek.tv.nativeapp.data.countEnabledFilters
import com.streamdek.tv.nativeapp.data.countGroupsWithFilters
import com.streamdek.tv.nativeapp.data.groupSourceFilters
import com.streamdek.tv.nativeapp.ui.TvChromeSurface
import com.streamdek.tv.nativeapp.ui.TvChromePanel
import com.streamdek.tv.nativeapp.update.AppUpdateManager
import com.streamdek.tv.nativeapp.ui.ProfileAvatarCircle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SettingsSection(val label: String, val searchTerms: String) {
    Profile("Profile", "account household pin switch"),
    Services("Services", "tracking trakt simkl mdblist addons providers"),
    Playback("Playback", "player decoder audio autoplay intro recap credits"),
    Streams("Streams", "sources quality badges filters fusion"),
    Tv("Appearance", "theme density cards navigation grid animation blur home"),
    Accessibility("Accessibility", "contrast large text reduced motion colour screen reader"),
    Devices("Devices", "sync sessions connected televisions"),
    Diagnostics("Diagnostics", "performance storage cache network playback providers health"),
    About("About", "version update application"),
}

private const val SettingsFocusGuardMs = 250L

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun AccountScreen(
    repository: StreamDekRepository,
    appUpdateManager: AppUpdateManager,
    topNavFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var bootstrap by remember { mutableStateOf<AccountBootstrap?>(repository.bootstrap.value) }
    var addons by remember { mutableStateOf<List<AddonManifest>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var selectedSection by remember { mutableStateOf(SettingsSection.Profile) }
    var settingsQuery by remember { mutableStateOf("") }
    val fusionBadgeSourcesByUrl by repository.fusionBadgeSources.collectAsState()
    var badgeUrlDraft by remember { mutableStateOf("") }
    var badgeUrlError by remember { mutableStateOf<String?>(null) }
    var badgeUrlSubmitting by remember { mutableStateOf(false) }
    var loadingBadgeUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var previewBadgeUrl by remember { mutableStateOf<String?>(null) }
    val firstSectionRequester = remember { FocusRequester() }
    val sidebarSearchRequester = remember { FocusRequester() }
    val contentEntryRequester = remember { FocusRequester() }
    val profileContentRequester = remember { FocusRequester() }
    val profileActionRequester = remember { FocusRequester() }
    val servicesContentRequester = remember { FocusRequester() }
    val servicesActionRequester = remember { FocusRequester() }
    val playbackContentRequester = remember { FocusRequester() }
    val playbackActionRequester = remember { FocusRequester() }
    val streamsContentRequester = remember { FocusRequester() }
    val streamsActionRequester = remember { FocusRequester() }
    val tvContentRequester = remember { FocusRequester() }
    val tvActionRequester = remember { FocusRequester() }
    val devicesContentRequester = remember { FocusRequester() }
    val devicesActionRequester = remember { FocusRequester() }
    val aboutContentRequester = remember { FocusRequester() }
    val aboutActionRequester = remember { FocusRequester() }
    val appUpdateState by appUpdateManager.uiState.collectAsState()
    val reachability by repository.reachability.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        bootstrap = repository.refreshBootstrap()
        addons = repository.fetchAddonManifests()
    }

    LaunchedEffect(Unit) {
        delay(200)
        try { firstSectionRequester.requestFocus() } catch (_: Exception) {}
    }

    val session = repository.session.value
    val prefs = bootstrap?.preferences
    val appPrefs = prefs?.app
    val playbackPrefs = prefs?.playback
    val streamsPrefs = prefs?.streams ?: StreamsPreferences()
    val activeProfile = repository.activeStreamProfile()

    LaunchedEffect(selectedSection, streamsPrefs.fusionBadgesEnabled) {
        if (selectedSection == SettingsSection.Streams && streamsPrefs.fusionBadgesEnabled) {
            repository.ensureFusionBadgeSourcesLoaded()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TvChromeSurface)
            .padding(start = 32.dp, end = 32.dp, top = 96.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SettingsSidebar(
            sessionPresent = session != null,
            selectedSection = selectedSection,
            firstSectionRequester = firstSectionRequester,
            sidebarSearchRequester = sidebarSearchRequester,
            contentEntryRequester = contentEntryRequester,
            topNavFocusRequester = topNavFocusRequester,
            query = settingsQuery,
            onQueryChange = { settingsQuery = it },
            onSelectSection = { selectedSection = it },
            onSignIn = onSignIn,
            onSignOut = {
                repository.signOut()
                bootstrap = null
                addons = emptyList()
                status = "Signed out from this TV."
            },
        )

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(Color(0x18FFFFFF)),
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = selectedSection.label,
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .focusRequester(contentEntryRequester)
                            .focusProperties {
                                left = sidebarSearchRequester
                            }
                            .focusable(),
                    )
                    status?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f),
                        )
                    }
                }
            }

            when (selectedSection) {
                SettingsSection.Profile -> {
                    item {
                        if (session == null) {
                            CompactCard("Link Your Account", modifier = Modifier.focusRequester(profileContentRequester)) {
                                Text(
                                    text = "Sign in to sync profiles, library, addons, and playback settings.",
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(
                                        onClick = onSignIn,
                                        modifier = Modifier.focusRequester(profileActionRequester),
                                        shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
                                    ) {
                                        Text("Sign In / Link TV")
                                    }
                                    OutlinedButton(onClick = onSignIn, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) {
                                        Text("Create Account")
                                    }
                                }
                            }
                        }
                    }
                    item {
                        CompactCard(
                            "Current Profile",
                            modifier = if (session != null) Modifier.focusRequester(profileContentRequester) else Modifier,
                        ) {
                            if (activeProfile != null) {
                                ProfileSummary(activeProfile)
                            } else {
                                Text(
                                    text = if (session == null) "Sign in to pick a household profile." else "No active profile is selected yet.",
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                                )
                            }
                        }
                    }
                    if (bootstrap?.streamProfiles?.isNotEmpty() == true) {
                        item {
                            CompactCard("Switch Profile") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    bootstrap?.streamProfiles?.forEach { profile ->
                                        CompactActionRow(
                                            title = profile.name,
                                            value = if (activeProfile?.id == profile.id) "Active" else if (profile.isDefault) "Default" else null,
                                            requester = if (profile == bootstrap?.streamProfiles?.firstOrNull()) profileActionRequester else null,
                                            onClick = {
                                                scope.launch {
                                                    repository.setActiveStreamProfile(profile.id)
                                                    bootstrap = repository.refreshBootstrap()
                                                    status = "Using ${profile.name} on this TV."
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                SettingsSection.Services -> {
                    item {
                        CompactCard("Account", modifier = Modifier.focusRequester(servicesContentRequester)) {
                            TextLine("Email", session?.user?.email ?: "Guest")
                            TextLine("Display Name", bootstrap?.profile?.displayName ?: session?.user?.displayName ?: "Not set")
                            TextLine("Subscription", session?.user?.subscriptionStatus ?: "free")
                        }
                    }
                    item {
                        CompactCard("Tracking") {
                            val integrations = bootstrap?.integrations
                            val primary = SyncServiceId.normalize(prefs?.home?.primarySyncService)
                            TextLine("Primary Service", SyncServiceId.label(primary))
                            TrackingServiceRow(
                                service = SyncServiceId.TRAKT,
                                connected = integrations?.trakt?.connected == true,
                                username = integrations?.trakt?.username,
                                isPrimary = primary == SyncServiceId.TRAKT,
                            )
                            TrackingServiceRow(
                                service = SyncServiceId.SIMKL,
                                connected = integrations?.simkl?.connected == true,
                                username = integrations?.simkl?.username,
                                isPrimary = primary == SyncServiceId.SIMKL,
                            )
                            TrackingServiceRow(
                                service = SyncServiceId.MDBLIST,
                                connected = integrations?.mdblist?.connected == true,
                                username = integrations?.mdblist?.username,
                                isPrimary = primary == SyncServiceId.MDBLIST,
                            )
                            if (integrations?.let { it.trakt.connected || it.simkl.connected || it.mdblist.connected } != true) {
                                Text(
                                    text = "Connect a tracking service in the StreamDek mobile app or web portal " +
                                        "to sync your watchlist and resume points to this TV.",
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    item {
                        CompactCard("Debrid") {
                            val accounts = bootstrap?.integrations?.debrid?.accounts.orEmpty()
                            if (accounts.isEmpty()) {
                                Text(
                                    text = "No debrid accounts are linked to this profile.",
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                                )
                            } else {
                                accounts.forEach { account ->
                                    TextLine(
                                        account.provider,
                                        buildString {
                                            append(account.username ?: "Priority ${account.priority + 1}")
                                            if (!account.enabled) append("  |  disabled")
                                        },
                                    )
                                }
                            }
                        }
                    }
                    item {
                        CompactCard("Cloud Sources") {
                            ChoiceRow("Configuration", "Refresh", requester = servicesActionRequester) {
                                scope.launch {
                                    bootstrap = repository.refreshBootstrap()
                                    addons = repository.fetchAddonManifests(forceRefresh = true)
                                    status = "Cloud configuration refreshed."
                                }
                            }
                            Text(
                                text = "Add-ons and providers are managed in the mobile app. This TV receives their cloud state.",
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                            )
                            if (addons.isEmpty()) {
                                TextLine("Synced add-ons", "None")
                            } else {
                                addons.forEach { addon ->
                                    TextLine(addon.manifest.name, if (addon.enabled) "Enabled" else "Disabled")
                                }
                            }
                        }
                    }
                }

                SettingsSection.Playback -> {
                    item {
                        CompactCard("Cloud Playback Profile", modifier = Modifier.focusRequester(playbackContentRequester)) {
                            ChoiceRow("Configuration", "Refresh", requester = playbackActionRequester) {
                                scope.launch {
                                    bootstrap = repository.refreshBootstrap()
                                    status = "Playback settings refreshed from the cloud."
                                }
                            }
                            Text(
                                text = "Change these defaults in StreamDek Mobile. TV applies them automatically.",
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                            )
                            TextLine("Player", playbackPrefs?.playerEngine ?: "Auto")
                            TextLine("Decoder", formatDecoderMode(playbackPrefs?.decoderMode ?: "auto"))
                            TextLine("Render surface", formatRenderSurface(playbackPrefs?.renderSurface ?: "auto"))
                            TextLine("Preferred quality", playbackPrefs?.preferredQuality ?: "1080p")
                            TextLine("Preferred audio", formatAudioLanguage(playbackPrefs?.defaultAudioLanguage ?: "auto"))
                            TextLine("Autoplay next", if (playbackPrefs?.isAutoPlayNextEpisodeEnabled() == true) "On" else "Off")
                            TextLine("Skip intro", if (playbackPrefs?.isSegmentEnabled("intro") != false) "On" else "Off")
                            TextLine("Skip recap", if (playbackPrefs?.isSegmentEnabled("recap") != false) "On" else "Off")
                            TextLine("Skip ending", if (playbackPrefs?.isSegmentEnabled("outro") != false) "On" else "Off")
                        }
                    }
                }

                SettingsSection.Streams -> {
                    item {
                        CompactCard("Cloud Stream Display", modifier = Modifier.focusRequester(streamsContentRequester)) {
                            ChoiceRow("Configuration", "Refresh", requester = streamsActionRequester) {
                                scope.launch {
                                    bootstrap = repository.refreshBootstrap()
                                    status = "Stream display settings refreshed from the cloud."
                                }
                            }
                            Text(
                                text = "Configure stream badges and source preferences in StreamDek Mobile.",
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                            )
                            TextLine("Fusion badges", if (streamsPrefs.fusionBadgesEnabled) "On" else "Off")
                            TextLine("Size badges", if (streamsPrefs.showSizeBadges) "On" else "Off")
                            TextLine("Badge position", streamsPrefs.badgePosition)
                        }
                    }
                }

                SettingsSection.Tv -> {
                    item {
                        CompactCard("Appearance & Layout", modifier = Modifier.focusRequester(tvContentRequester)) {
                            Text("Changes apply immediately and sync with this profile.", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f), style = MaterialTheme.typography.bodySmall)
                            ChoiceRow("Theme", appPrefs?.theme ?: "cinema-blue", requester = tvActionRequester) { scope.launch { repository.updateAppPreferences(mapOf("theme" to nextOf(listOf("streamdek", "cinema-blue", "carbon-gold", "frost-neon", "ember-red", "aurora-green", "violet-pulse"), appPrefs?.theme ?: "cinema-blue"))); bootstrap = repository.bootstrap.value } }
                            ChoiceRow("Start screen", appPrefs?.startScreen ?: "home") { scope.launch { repository.updateAppPreferences(mapOf("startScreen" to nextOf(listOf("home", "library", "continue-watching"), appPrefs?.startScreen ?: "home"))); bootstrap = repository.bootstrap.value } }
                            ChoiceRow("Card format", appPrefs?.homeRowCardStyle ?: "landscape") { scope.launch { repository.updateAppPreferences(mapOf("homeRowCardStyle" to nextOf(listOf("landscape", "portrait"), appPrefs?.homeRowCardStyle ?: "landscape"))); bootstrap = repository.bootstrap.value } }
                            ChoiceRow("Card density", appPrefs?.cardDensity ?: "comfortable") { scope.launch { repository.updateAppPreferences(mapOf("cardDensity" to nextOf(listOf("comfortable", "compact"), appPrefs?.cardDensity ?: "comfortable"))); bootstrap = repository.bootstrap.value } }
                            ChoiceRow("Grid columns", (appPrefs?.gridSize ?: 5).toString()) { scope.launch { val next = if ((appPrefs?.gridSize ?: 5) >= 7) 4 else (appPrefs?.gridSize ?: 5) + 1; repository.updateAppPreferences(mapOf("gridSize" to next)); bootstrap = repository.bootstrap.value } }
                            ChoiceRow("Animation speed", appPrefs?.animationSpeed ?: "normal") { scope.launch { repository.updateAppPreferences(mapOf("animationSpeed" to nextOf(listOf("normal", "fast", "slow"), appPrefs?.animationSpeed ?: "normal"))); bootstrap = repository.bootstrap.value } }
                            ChoiceRow("Navigation", appPrefs?.navigationStyle ?: "adaptive") { scope.launch { repository.updateAppPreferences(mapOf("navigationStyle" to nextOf(listOf("adaptive", "compact"), appPrefs?.navigationStyle ?: "adaptive"))); bootstrap = repository.bootstrap.value } }
                            PreferenceRow("Background depth", appPrefs?.backgroundBlur != false) { scope.launch { repository.updateAppPreferences(mapOf("backgroundBlur" to (appPrefs?.backgroundBlur == false))); bootstrap = repository.bootstrap.value } }
                            PreferenceRow("Legacy compact mode", appPrefs?.compactMode == true) { scope.launch { repository.updateAppPreferences(mapOf("compactMode" to !(appPrefs?.compactMode == true))); bootstrap = repository.bootstrap.value } }
                        }
                    }
                }

                SettingsSection.Accessibility -> {
                    item {
                        CompactCard("Vision & Motion", modifier = Modifier.focusRequester(tvContentRequester)) {
                            Text("Recommended on smaller televisions and for viewers sensitive to motion.", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f), style = MaterialTheme.typography.bodySmall)
                            PreferenceRow("High contrast", appPrefs?.highContrast == true, requester = tvActionRequester) { scope.launch { repository.updateAppPreferences(mapOf("highContrast" to !(appPrefs?.highContrast == true))); bootstrap = repository.bootstrap.value } }
                            PreferenceRow("Large text", appPrefs?.largeText == true) { scope.launch { repository.updateAppPreferences(mapOf("largeText" to !(appPrefs?.largeText == true))); bootstrap = repository.bootstrap.value } }
                            PreferenceRow("Reduced motion", appPrefs?.reducedMotion == true) { scope.launch { repository.updateAppPreferences(mapOf("reducedMotion" to !(appPrefs?.reducedMotion == true))); bootstrap = repository.bootstrap.value } }
                        }
                    }
                    item {
                        CompactCard("TV Navigation") {
                            TextLine("Focus ring", "Always visible")
                            TextLine("Focused scale", if (appPrefs?.reducedMotion == true) "Off" else "Subtle")
                            TextLine("Screen reader labels", "Enabled")
                            TextLine("Colour use", "Never the only status signal")
                        }
                    }
                }
                SettingsSection.Devices -> {
                    item {
                        CompactCard("Sync Status", modifier = Modifier.focusRequester(devicesContentRequester)) {
                            TextLine("Settings Sync", bootstrap?.syncStatus?.lastSettingsSyncAt ?: "Ready")
                            TextLine("Cloud Sync", if (bootstrap?.syncStatus?.cloudSyncEnabled != false) "On" else "Off")
                            TextLine("Playback Sync", if (bootstrap?.syncStatus?.playbackSyncEnabled != false) "On" else "Off")
                            TextLine(
                                "Tracking",
                                SyncServiceId.label(SyncServiceId.normalize(prefs?.home?.primarySyncService)).let { label ->
                                    val connected = when (SyncServiceId.normalize(prefs?.home?.primarySyncService)) {
                                        SyncServiceId.SIMKL -> bootstrap?.integrations?.simkl?.connected == true
                                        SyncServiceId.MDBLIST -> bootstrap?.integrations?.mdblist?.connected == true
                                        else -> bootstrap?.integrations?.trakt?.connected == true ||
                                            bootstrap?.syncStatus?.traktConnected == true
                                    }
                                    if (connected) "$label  |  connected" else "$label  |  not connected"
                                },
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                OutlinedButton(
                                    onClick = { scope.launch { bootstrap = repository.refreshBootstrap(); status = "Sync status refreshed." } },
                                    modifier = Modifier.focusRequester(devicesActionRequester),
                                    shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
                                ) { Text("Refresh") }
                            }
                        }
                    }
                    item {
                        CompactCard("Devices") {
                            val devices = bootstrap?.devices.orEmpty()
                            if (devices.isEmpty()) {
                                Text("No device records are available yet.", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f))
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    devices.forEach { DeviceRow(it) }
                                }
                            }
                        }
                    }
                    item {
                        CompactCard("Sessions") {
                            val sessions = bootstrap?.sessions.orEmpty()
                            if (sessions.isEmpty()) {
                                Text("No active sessions were returned.", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f))
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    sessions.forEach { SessionRow(it) }
                                }
                            }
                        }
                    }
                }

                SettingsSection.Diagnostics -> {
                    item {
                        CompactCard("Health Check", modifier = Modifier.focusRequester(devicesContentRequester)) {
                            TextLine("Backend", reachability.name.lowercase().replaceFirstChar { it.uppercase() })
                            TextLine("Authentication", if (session != null) "Healthy" else "Guest mode")
                            TextLine("Providers", "${addons.count { it.enabled }} enabled / ${addons.size} installed")
                            TextLine("Image & response cache", formatBytes(directorySize(context.cacheDir)))
                            TextLine("Playback engine", playbackPrefs?.playerEngine ?: "Auto")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                OutlinedButton(onClick = { scope.launch { bootstrap = repository.refreshBootstrap(); addons = repository.fetchAddonManifests(); status = "Health checks refreshed." } }, modifier = Modifier.focusRequester(devicesActionRequester), shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) { Text("Run Again") }
                            }
                        }
                    }
                }
                SettingsSection.About -> {
                    item {
                        CompactCard("App Updates", modifier = Modifier.focusRequester(aboutContentRequester)) {
                            TextLine("Installed", BuildConfig.VERSION_NAME)
                            TextLine("Status", appUpdateState.statusText ?: appUpdateState.errorMessage ?: "Ready")
                            PreferenceRow("Automatic Checks", appUpdateState.autoCheckEnabled, requester = aboutActionRequester) {
                                appUpdateManager.setAutoCheckEnabled(!appUpdateState.autoCheckEnabled)
                            }
                            appUpdateState.availableRelease?.let { release ->
                                TextLine("Available", release.versionName)
                                release.requiredReason?.takeIf { it.isNotBlank() }?.let { reason ->
                                    Text(
                                        text = reason,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                release.releaseNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                                    Text(
                                        text = notes,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                if (appUpdateState.availableRelease != null) {
                                    Button(
                                        onClick = { scope.launch { appUpdateManager.startUpdate() } },
                                        shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
                                    ) {
                                        Text(
                                            when {
                                                appUpdateState.blockedByUnknownSources -> "Open Install Settings"
                                                appUpdateState.downloadProgressPercent != null -> "Downloading ${appUpdateState.downloadProgressPercent}%"
                                                appUpdateState.isInstalling -> "Preparing Update"
                                                else -> "Install Update"
                                            },
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                }
                                OutlinedButton(
                                    onClick = { scope.launch { appUpdateManager.checkForUpdates(showPromptOnAvailable = false, force = true) } },
                                    shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
                                ) { Text("Check Now") }
                            }
                        }
                    }
                    item {
                        CompactCard("About") {
                            TextLine("Version", BuildConfig.VERSION_NAME)
                            TextLine("Client", "Android TV")
                            TextLine("API", if (session != null) "Authenticated" else "Guest")
                            TextLine("Profile", activeProfile?.name ?: "Not selected")
                            TextLine("Theme", appPrefs?.theme ?: "cinema-blue")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                OutlinedButton(
                                    onClick = { scope.launch { bootstrap = repository.refreshBootstrap(); status = "Settings refreshed." } },
                                    shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
                                ) { Text("Refresh") }
                            }
                        }
                    }
                }
            }
        }
    }

    previewBadgeUrl?.let { url ->
        FusionBadgePreviewDialog(
            source = fusionBadgeSourcesByUrl[url],
            onDismiss = { previewBadgeUrl = null },
        )
    }
}

@Composable
private fun SettingsSidebar(
    sessionPresent: Boolean,
    selectedSection: SettingsSection,
    firstSectionRequester: FocusRequester,
    sidebarSearchRequester: FocusRequester,
    contentEntryRequester: FocusRequester,
    topNavFocusRequester: FocusRequester,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelectSection: (SettingsSection) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val searchEditorRequester = remember { FocusRequester() }
    var searchEditing by remember { mutableStateOf(false) }
    var searchFieldWasFocused by remember { mutableStateOf(false) }
    var searchLauncherFocused by remember { mutableStateOf(false) }

    LaunchedEffect(searchEditing) {
        if (searchEditing) {
            delay(60)
            runCatching { searchEditorRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    Column(
        modifier = Modifier.width(210.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        if (searchEditing) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { androidx.compose.material3.Text("Find settings") },
                singleLine = true,
                shape = RoundedCornerShape(999.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF11151C),
                    unfocusedContainerColor = Color(0xFF0D1015),
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = Color(0x20FFFFFF),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .focusRequester(searchEditorRequester)
                    .focusProperties {
                        left = topNavFocusRequester
                        right = contentEntryRequester
                    }
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            searchFieldWasFocused = true
                        } else if (searchFieldWasFocused) {
                            searchFieldWasFocused = false
                            searchEditing = false
                        }
                    }
,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(
                        color = if (searchLauncherFocused) Color(0xFF11151C) else Color(0xFF0D1015),
                        shape = RoundedCornerShape(999.dp),
                    )
                    .border(
                        width = if (searchLauncherFocused) 2.dp else 1.dp,
                        color = if (searchLauncherFocused) MaterialTheme.colorScheme.primary else Color(0x20FFFFFF),
                        shape = RoundedCornerShape(999.dp),
                    )
                    .focusRequester(sidebarSearchRequester)
                    .focusProperties {
                        left = topNavFocusRequester
                        right = contentEntryRequester
                    }
                    .onFocusChanged { searchLauncherFocused = it.isFocused }

                    .clickable { searchEditing = true },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = query.ifBlank { "Find settings" },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = if (query.isBlank()) Color.White.copy(alpha = 0.68f) else Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val visibleSections = SettingsSection.entries.filter {
            query.isBlank() || it.label.contains(query, true) || it.searchTerms.contains(query, true)
        }
        visibleSections.forEachIndexed { index, section ->
            SidebarItem(
                title = section.label,
                selected = selectedSection == section,
                requester = if (index == 0) firstSectionRequester else null,
                leftRequester = topNavFocusRequester,
                rightRequester = contentEntryRequester,
                onFocused = {},
                onClick = { onSelectSection(section) },
            )
        }
        if (visibleSections.isEmpty()) {
            Text("No settings match", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (!sessionPresent) {
            SidebarItem(title = "Sign In", selected = false, leftRequester = topNavFocusRequester, rightRequester = contentEntryRequester, onFocused = {}, onClick = onSignIn)
        } else {
            SignOutButton(leftRequester = topNavFocusRequester, onSignOut = onSignOut)
        }
    }
}

@Composable
private fun SignOutButton(leftRequester: FocusRequester, onSignOut: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onSignOut,
        modifier = Modifier.fillMaxWidth().focusProperties { left = leftRequester }.onFocusChanged { focused = it.isFocused },
        shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ButtonDefaults.colors(
            containerColor = Color(0x28FF3030),
            focusedContainerColor = Color(0x55FF3030),
            contentColor = Color(0xFFFF7070),
            focusedContentColor = Color(0xFFFFAAAA),
        ),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) Color(0xFFFF6060) else Color(0x40FF4040),
                ),
                shape = RoundedCornerShape(999.dp),
            ),
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.02f),
    ) {
        Text(
            text = "Sign Out",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun SidebarItem(
    title: String,
    selected: Boolean,
    requester: FocusRequester? = null,
    leftRequester: FocusRequester? = null,
    rightRequester: FocusRequester? = null,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(
                color = when {
                    focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(10.dp),
            )
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .focusProperties {
                if (leftRequester != null) left = leftRequester
                if (rightRequester != null) right = rightRequester
            }
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable { onClick() },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 10.dp),
            color = when {
                focused -> MaterialTheme.colorScheme.onBackground
                selected -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f)
                else -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f)
            },
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = if (selected || focused) FontWeight.Black else FontWeight.Medium,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CompactCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TvChromePanel, RoundedCornerShape(20.dp))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.66f) else Color(0x10FFFFFF),
                shape = RoundedCornerShape(20.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
        )
        content()
    }
}

@Composable
private fun ProfileSummary(profile: StreamProfile) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProfileAvatar(profile, size = 48.dp)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(profile.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onBackground)
            Text(
                text = buildList {
                    if (profile.isDefault) add("Default")
                    if (profile.hasPinSet) add("PIN locked")
                    if (profile.maturityRating != "all") add(profile.maturityRating.uppercase())
                    profile.subtitleLanguage?.takeIf { it.isNotBlank() }?.let { add("Subs $it") }
                    profile.audioLanguage?.takeIf { it.isNotBlank() }?.let { add("Audio $it") }
                }.joinToString("  |  ").ifBlank { "Ready to watch" },
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ProfileAvatar(profile: StreamProfile, size: Dp = 34.dp) {
    ProfileAvatarCircle(avatarIndex = profile.avatarIndex, fallbackLabel = profile.name, size = size)
}

@Composable
private fun PreferenceRow(label: String, value: Boolean, requester: FocusRequester? = null, onToggle: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.76f), style = MaterialTheme.typography.bodyMedium)
        SettingsActionButton(onClick = onToggle, requester = requester) { Text(if (value) "On" else "Off") }
    }
}

private fun formatFileSizeGB(raw: String): String = when (raw) {
    "0" -> "Unlimited"
    else -> "$raw GB"
}

private fun normalizeRenderSurfacePreference(value: String?): String = when (value?.trim()?.lowercase()) {
    "texture", "textureview" -> "texture"
    "surface", "surfaceview" -> "surface"
    "auto", "standard", null, "" -> "auto"
    else -> "auto"
}

private fun formatRenderSurface(raw: String): String = when (normalizeRenderSurfacePreference(raw)) {
    "surface" -> "SurfaceView"
    "texture" -> "TextureView"
    else -> "Auto"
}

private fun normalizeDecoderModePreference(value: String?): String = when (value?.trim()?.lowercase()) {
    "hardware", "hw", "mediacodec-copy" -> "hardware"
    "hardware+", "hw+", "hardware_plus", "mediacodec" -> "hardware_plus"
    "software", "sw", "none" -> "software"
    else -> "auto"
}

private fun formatDecoderMode(raw: String): String = when (normalizeDecoderModePreference(raw)) {
    "hardware" -> "Hardware Decoder (HW)"
    "hardware_plus" -> "Hardware+ (HW+)"
    "software" -> "Software Decoder (SW)"
    else -> "Auto"
}

private fun preferredAudioLanguageOptions(): List<String> = listOf(
    "auto",
    "en",
    "es",
    "fr",
    "de",
    "it",
    "pt",
    "ar",
    "hi",
    "ja",
    "ko",
    "zh",
    "ru",
    "tr",
)

private fun normalizeAudioLanguagePreference(value: String?): String = when (value?.trim()?.lowercase()) {
    null, "", "auto" -> "auto"
    "eng", "english" -> "en"
    "spa", "spanish", "espanol" -> "es"
    "fra", "fre", "french" -> "fr"
    "deu", "ger", "german" -> "de"
    "ita", "italian" -> "it"
    "por", "portuguese" -> "pt"
    "ara", "arabic" -> "ar"
    "hin", "hindi" -> "hi"
    "jpn", "japanese" -> "ja"
    "kor", "korean" -> "ko"
    "zho", "chi", "chinese", "mandarin", "cantonese" -> "zh"
    "rus", "russian" -> "ru"
    "tur", "turkish" -> "tr"
    else -> value.trim().lowercase()
}

private fun formatAudioLanguage(raw: String): String = when (normalizeAudioLanguagePreference(raw)) {
    "en" -> "English"
    "es" -> "Spanish"
    "fr" -> "French"
    "de" -> "German"
    "it" -> "Italian"
    "pt" -> "Portuguese"
    "ar" -> "Arabic"
    "hi" -> "Hindi"
    "ja" -> "Japanese"
    "ko" -> "Korean"
    "zh" -> "Chinese"
    "ru" -> "Russian"
    "tr" -> "Turkish"
    else -> "Auto"
}

@Composable
private fun ChoiceRow(label: String, value: String, requester: FocusRequester? = null, onCycle: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.76f), style = MaterialTheme.typography.bodyMedium)
        SettingsActionButton(onClick = onCycle, requester = requester) { Text(value) }
    }
}

@Composable
private fun SettingsActionButton(
    onClick: () -> Unit,
    requester: FocusRequester? = null,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var lastFocusedAt by remember { mutableLongStateOf(0L) }
    OutlinedButton(
        onClick = {
            val now = System.currentTimeMillis()
            if (!focused || now - lastFocusedAt >= SettingsFocusGuardMs) {
                onClick()
            }
        },
        shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ButtonDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            focusedContentColor = MaterialTheme.colorScheme.onBackground,
        ),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color(0x30FFFFFF)),
                shape = RoundedCornerShape(999.dp),
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(999.dp),
            ),
        ),
        modifier = Modifier
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    lastFocusedAt = System.currentTimeMillis()
                }
            },
        content = content,
    )
}

/** One tracking service and what it can currently do for this profile. */
@Composable
private fun TrackingServiceRow(service: String, connected: Boolean, username: String?, isPrimary: Boolean) {
    val detail = buildString {
        append(if (connected) "Connected" else "Not connected")
        if (connected && !username.isNullOrBlank()) append("  |  $username")
        if (isPrimary) append("  |  primary")
    }
    TextLine(SyncServiceId.label(service), detail)
}

@Composable
private fun TextLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CompactActionRow(title: String, value: String?, requester: FocusRequester? = null, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
        OutlinedButton(
            onClick = onClick,
            modifier = if (requester != null) Modifier.focusRequester(requester) else Modifier,
            shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
        ) {
            Text(value ?: "Use")
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceInfo) {
    EntityRow(
        title = device.name ?: device.deviceType ?: device.id ?: "Unknown device",
        subtitle = listOfNotNull(device.platform, device.appVersion?.let { "v$it" }, device.lastSeenAt?.let { "Seen $it" }, if (device.isCurrent) "This TV" else null).joinToString("  |  ").ifBlank { "Registered device" },
    )
}

@Composable
private fun SessionRow(session: SessionInfo) {
    EntityRow(
        title = session.clientName ?: session.id ?: "Active session",
        subtitle = listOfNotNull(session.clientPlatform, session.deviceId, session.lastSeenAt?.let { "Seen $it" }, if (session.isCurrent) "Current session" else null).joinToString("  |  ").ifBlank { "Signed in" },
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EntityRow(title: String, subtitle: String) {
    Card(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
        colors = CardDefaults.colors(
            containerColor = TvChromePanel,
            focusedContainerColor = Color(0x7015181D),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(1.dp, Color(0x38FFFFFF)),
                shape = RoundedCornerShape(16.dp),
            ),
        ),
        scale = CardDefaults.scale(focusedScale = 1.0f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
            Text(subtitle, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AddonRow(
    addon: AddonManifest,
    actionRequester: FocusRequester? = null,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(TvChromePanel, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(addon.manifest.name.ifBlank { addon.id }, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
            Text(
                text = "v${addon.manifest.version.ifBlank { "0.0.0" }}  |  ${addon.manifest.description ?: addon.id}",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onToggle,
                modifier = if (actionRequester != null) Modifier.focusRequester(actionRequester) else Modifier,
                shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
            ) { Text(if (addon.enabled) "Disable" else "Enable") }
            OutlinedButton(onClick = onRemove, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) { Text("Remove") }
        }
    }
}

private fun nextOf(values: List<String>, current: String): String {
    val index = values.indexOf(current)
    return if (index < 0 || index == values.lastIndex) values.first() else values[index + 1]
}

@Composable
private fun BadgeUrlRow(
    url: String,
    source: FusionBadgeSource?,
    isLoading: Boolean,
    canRemove: Boolean,
    showActiveSelector: Boolean = false,
    isActive: Boolean = false,
    onSetActive: () -> Unit = {},
    onRefresh: () -> Unit,
    onPreview: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(TvChromePanel, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = url,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    isLoading -> "Refreshing..."
                    source != null -> "${countEnabledFilters(source)} badges across ${countGroupsWithFilters(source)} groups" + (if (isActive) " · Active" else "")
                    else -> "Not loaded yet"
                },
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showActiveSelector) {
                if (isActive) {
                    Button(onClick = onSetActive, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) { Text("Active") }
                } else {
                    OutlinedButton(onClick = onSetActive, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) { Text("Set Active") }
                }
            }
            OutlinedButton(onClick = onPreview, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) { Text("Preview") }
            OutlinedButton(onClick = onRefresh, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) { Text("Refresh") }
            if (canRemove) {
                OutlinedButton(onClick = onRemove, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun BadgeUrlField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { androidx.compose.material3.Text("Add badge source URL") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFF12161F),
            unfocusedContainerColor = Color(0xFF12161F),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
        ),
    )
}

@Composable
private fun FusionBadgePreviewDialog(source: FusionBadgeSource?, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .heightIn(max = 560.dp)
                .background(TvChromePanel, RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Badge Preview",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (source == null) {
                Text("Loading badges...", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f))
            } else {
                val groups = remember(source) { groupSourceFilters(source) }
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    groups.forEach { groupMatch ->
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = groupMatch.group.name.ifBlank { "Special" },
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                )
                                groupMatch.badges.chunked(6).forEach { rowBadges ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        rowBadges.forEach { badge ->
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                                modifier = Modifier.width(64.dp),
                                            ) {
                                                AsyncImage(
                                                    model = badge.imageURL,
                                                    contentDescription = badge.name,
                                                    contentScale = ContentScale.Fit,
                                                    modifier = Modifier
                                                        .height(28.dp)
                                                        .width(if (badge.groupId == FUSION_BADGE_LANGUAGE_GROUP_ID) 28.dp else 56.dp),
                                                )
                                                Text(
                                                    text = badge.name,
                                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = onDismiss, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) { Text("Close") }
            }
        }
    }
}

private fun directorySize(root: java.io.File): Long = runCatching {
    root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}.getOrDefault(0L)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}