package com.streamdek.tv.nativeapp.ui.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.streamdek.tv.BuildConfig
import com.streamdek.tv.nativeapp.data.AccountBootstrap
import com.streamdek.tv.nativeapp.data.AddonManifest
import com.streamdek.tv.nativeapp.data.DeviceInfo
import com.streamdek.tv.nativeapp.data.SessionInfo
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.StreamProfile
import com.streamdek.tv.nativeapp.ui.ProfileAvatarCircle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SettingsSection(val label: String) {
    Profile("Profile"),
    Services("Services"),
    Playback("Playback"),
    Tv("TV Interface"),
    Devices("Devices"),
    About("About"),
}

@Composable
fun AccountScreen(
    repository: StreamDekRepository,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var bootstrap by remember { mutableStateOf<AccountBootstrap?>(repository.bootstrap.value) }
    var addons by remember { mutableStateOf<List<AddonManifest>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var selectedSection by remember { mutableStateOf(SettingsSection.Profile) }
    val firstSectionRequester = remember { FocusRequester() }

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
    val activeProfile = repository.activeStreamProfile()

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06080C))
            .padding(start = 32.dp, end = 32.dp, top = 96.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SettingsSidebar(
            sessionPresent = session != null,
            selectedSection = selectedSection,
            firstSectionRequester = firstSectionRequester,
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
                            CompactCard("Link Your Account") {
                                Text(
                                    text = "Sign in to sync profiles, library, addons, and playback settings.",
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Button(onClick = onSignIn, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) {
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
                        CompactCard("Current Profile") {
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
                        CompactCard("Account") {
                            TextLine("Email", session?.user?.email ?: "Guest")
                            TextLine("Display Name", bootstrap?.profile?.displayName ?: session?.user?.displayName ?: "Not set")
                            TextLine("Subscription", session?.user?.subscriptionStatus ?: "free")
                        }
                    }
                    item {
                        CompactCard("Trakt") {
                            val trakt = bootstrap?.integrations?.trakt
                            TextLine("Status", if (trakt?.connected == true) "Connected" else "Not connected")
                            TextLine("Username", trakt?.username ?: "Unavailable")
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
                        CompactCard("Addons") {
                            if (addons.isEmpty()) {
                                Text(
                                    text = "No synced addons found for this account.",
                                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    addons.forEach { addon ->
                                        AddonRow(
                                            addon = addon,
                                            onToggle = {
                                                scope.launch {
                                                    repository.toggleAddon(addon.id, !addon.enabled)
                                                    addons = repository.fetchAddonManifests(forceRefresh = true)
                                                    bootstrap = repository.bootstrap.value
                                                    status = if (addon.enabled) "${addon.manifest.name} disabled." else "${addon.manifest.name} enabled."
                                                }
                                            },
                                            onRemove = {
                                                scope.launch {
                                                    repository.uninstallAddon(addon.id)
                                                    addons = repository.fetchAddonManifests(forceRefresh = true)
                                                    bootstrap = repository.bootstrap.value
                                                    status = "${addon.manifest.name} removed."
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                SettingsSection.Playback -> {
                    item {
                        CompactCard("Playback Defaults") {
                            PreferenceRow("Autoplay Next Episode", playbackPrefs?.autoplayNextEpisode == true) {
                                scope.launch {
                                    repository.updatePlaybackPreferences(mapOf("autoplayNextEpisode" to !(playbackPrefs?.autoplayNextEpisode == true)))
                                    bootstrap = repository.bootstrap.value
                                }
                            }
                            ChoiceRow("Preferred Quality", playbackPrefs?.preferredQuality ?: "1080p") {
                                scope.launch {
                                    repository.updatePlaybackPreferences(mapOf("preferredQuality" to nextOf(listOf("best", "4k", "1080p", "720p"), playbackPrefs?.preferredQuality ?: "1080p")))
                                    bootstrap = repository.bootstrap.value
                                }
                            }
                            ChoiceRow("Streaming Server", playbackPrefs?.streamingServer ?: "addon") {
                                scope.launch {
                                    repository.updatePlaybackPreferences(mapOf("streamingServer" to nextOf(listOf("addon", "backend"), playbackPrefs?.streamingServer ?: "addon")))
                                    bootstrap = repository.bootstrap.value
                                }
                            }
                            ChoiceRow("Max File Size", formatFileSizeGB(playbackPrefs?.maxFileSizeGB ?: "2")) {
                                scope.launch {
                                    repository.updatePlaybackPreferences(mapOf("maxFileSizeGB" to nextOf(listOf("0", "2", "5", "10", "20"), playbackPrefs?.maxFileSizeGB ?: "2")))
                                    bootstrap = repository.bootstrap.value
                                }
                            }
                            ChoiceRow("Subtitle Language", playbackPrefs?.defaultSubtitleLanguage ?: "en") {
                                scope.launch {
                                    repository.updatePlaybackPreferences(mapOf("defaultSubtitleLanguage" to nextOf(listOf("off", "en", "es", "pt"), playbackPrefs?.defaultSubtitleLanguage ?: "en")))
                                    bootstrap = repository.bootstrap.value
                                }
                            }
                            ChoiceRow("Audio Language", playbackPrefs?.defaultAudioLanguage ?: "en") {
                                scope.launch {
                                    repository.updatePlaybackPreferences(mapOf("defaultAudioLanguage" to nextOf(listOf("en", "es", "pt", "original"), playbackPrefs?.defaultAudioLanguage ?: "en")))
                                    bootstrap = repository.bootstrap.value
                                }
                            }
                        }
                    }
                }

                SettingsSection.Tv -> {
                    item {
                        CompactCard("TV Interface") {
                            ChoiceRow("Theme", appPrefs?.theme ?: "cinema-blue") {
                                scope.launch {
                                    repository.updateAppPreferences(mapOf("theme" to nextOf(listOf("streamdek", "cinema-blue", "carbon-gold", "frost-neon", "ember-red", "aurora-green", "violet-pulse"), appPrefs?.theme ?: "cinema-blue")))
                                    bootstrap = repository.bootstrap.value
                                }
                            }
                            ChoiceRow("Color Mode", appPrefs?.colorMode ?: "night") {
                                scope.launch {
                                    repository.updateAppPreferences(mapOf("colorMode" to nextOf(listOf("night", "day"), appPrefs?.colorMode ?: "night")))
                                    bootstrap = repository.bootstrap.value
                                }
                            }
                            ChoiceRow("Start Screen", appPrefs?.startScreen ?: "home") {
                                scope.launch {
                                    repository.updateAppPreferences(mapOf("startScreen" to nextOf(listOf("home", "library", "continue-watching"), appPrefs?.startScreen ?: "home")))
                                    bootstrap = repository.bootstrap.value
                                }
                            }
                            ChoiceRow("Home Row Style", appPrefs?.homeRowCardStyle ?: "landscape") {
                                scope.launch {
                                    repository.updateAppPreferences(mapOf("homeRowCardStyle" to nextOf(listOf("landscape", "portrait"), appPrefs?.homeRowCardStyle ?: "landscape")))
                                    bootstrap = repository.bootstrap.value
                                }
                            }
                            PreferenceRow("Compact Mode", appPrefs?.compactMode == true) {
                                scope.launch {
                                    repository.updateAppPreferences(mapOf("compactMode" to !(appPrefs?.compactMode == true)))
                                    bootstrap = repository.bootstrap.value
                                }
                            }
                            PreferenceRow("Sync Over Cellular", appPrefs?.syncOverCellular == true) {
                                scope.launch {
                                    repository.updateAppPreferences(mapOf("syncOverCellular" to !(appPrefs?.syncOverCellular == true)))
                                    bootstrap = repository.bootstrap.value
                                }
                            }
                        }
                    }
                }

                SettingsSection.Devices -> {
                    item {
                        CompactCard("Sync Status") {
                            TextLine("Settings Sync", bootstrap?.syncStatus?.lastSettingsSyncAt ?: "Ready")
                            TextLine("Cloud Sync", if (bootstrap?.syncStatus?.cloudSyncEnabled != false) "On" else "Off")
                            TextLine("Playback Sync", if (bootstrap?.syncStatus?.playbackSyncEnabled != false) "On" else "Off")
                            TextLine("Trakt", if (bootstrap?.syncStatus?.traktConnected == true) "Connected" else "Not connected")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                OutlinedButton(
                                    onClick = { scope.launch { bootstrap = repository.refreshBootstrap(); status = "Sync status refreshed." } },
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

                SettingsSection.About -> {
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
}

@Composable
private fun SettingsSidebar(
    sessionPresent: Boolean,
    selectedSection: SettingsSection,
    firstSectionRequester: FocusRequester,
    onSelectSection: (SettingsSection) -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
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

        SettingsSection.entries.forEachIndexed { index, section ->
            SidebarItem(
                title = section.label,
                selected = selectedSection == section,
                requester = if (index == 0) firstSectionRequester else null,
                // onFocused does NOT change the section — only onClick does.
                // This prevents navigation returning from content from jumping to a different section.
                onFocused = {},
                onClick = { onSelectSection(section) },
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (!sessionPresent) {
            SidebarItem(title = "Sign In", selected = false, onFocused = {}, onClick = onSignIn)
        } else {
            SignOutButton(onSignOut = onSignOut)
        }
    }
}

@Composable
private fun SignOutButton(onSignOut: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onSignOut,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
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
                    focused -> Color(0x2CFFFFFF)
                    selected -> Color(0x1AF4EDE2)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(10.dp),
            )
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
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
private fun CompactCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x9411141B), RoundedCornerShape(20.dp))
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
private fun PreferenceRow(label: String, value: Boolean, onToggle: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.76f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onToggle, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) { Text(if (value) "On" else "Off") }
    }
}

private fun formatFileSizeGB(raw: String): String = when (raw) {
    "0" -> "Unlimited"
    else -> "$raw GB"
}

@Composable
private fun ChoiceRow(label: String, value: String, onCycle: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.76f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onCycle, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) { Text(value) }
    }
}

@Composable
private fun TextLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CompactActionRow(title: String, value: String?, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
        OutlinedButton(onClick = onClick, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) {
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
            containerColor = Color(0x5915181D),
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
private fun AddonRow(addon: AddonManifest, onToggle: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0x5915181D), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
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
            OutlinedButton(onClick = onToggle, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) { Text(if (addon.enabled) "Disable" else "Enable") }
            OutlinedButton(onClick = onRemove, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) { Text("Remove") }
        }
    }
}

private fun nextOf(values: List<String>, current: String): String {
    val index = values.indexOf(current)
    return if (index < 0 || index == values.lastIndex) values.first() else values[index + 1]
}
