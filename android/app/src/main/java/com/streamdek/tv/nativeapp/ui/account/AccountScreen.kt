package com.streamdek.tv.nativeapp.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.streamdek.tv.nativeapp.data.AccountBootstrap
import com.streamdek.tv.nativeapp.data.AddonManifest
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.StreamProfile
import com.streamdek.tv.nativeapp.ui.ProfileAvatarCircle
import kotlinx.coroutines.launch

private enum class SettingsSection(val label: String) {
    Profile("Profile"),
    Accounts("Accounts and Services"),
    General("General"),
    Playback("Playback"),
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

    LaunchedEffect(Unit) {
        bootstrap = repository.refreshBootstrap()
        addons = repository.fetchAddonManifests()
    }

    val session = repository.session.value
    val prefs = bootstrap?.preferences?.playback
    val activeProfile = repository.activeStreamProfile()

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06080C))
            .padding(start = 36.dp, end = 36.dp, top = 118.dp, bottom = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        val menuOffset by animateDpAsState((SettingsSection.entries.indexOf(selectedSection) * 68).dp, label = "settings-menu-pill")

        Box(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                status?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                    )
                }
                Box {
                    Box(
                        modifier = Modifier
                            .padding(top = menuOffset)
                            .fillMaxWidth()
                            .height(54.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0x18FFFFFF))
                            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(999.dp)),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SettingsSection.entries.forEach { section ->
                            SettingsMenuItem(
                                title = section.label,
                                selected = selectedSection == section,
                                onFocused = { selectedSection = section },
                                onClick = { selectedSection = section },
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .background(Color(0x22FFFFFF))
                        .height(1.dp),
                )
                Button(
                    onClick = onBack,
                    shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
                ) {
                    Text("Home")
                }
                if (session == null) {
                    Button(
                        onClick = onSignIn,
                        shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
                    ) {
                        Text("Sign In")
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            repository.signOut()
                            status = "Signed out"
                        },
                        shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
                    ) {
                        Text("Sign Out")
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(Color(0x22FFFFFF)),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(30.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (selectedSection) {
                SettingsSection.Profile -> {
                    if (session == null) {
                        SettingsCard("Link Your Account") {
                            Text(
                                text = "Sign in to sync your addons, continue watching, watchlist, debrid accounts, and playback settings across all your devices.",
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.82f),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = onSignIn,
                                    shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
                                    colors = ButtonDefaults.colors(
                                        containerColor = Color(0xFFF4EDE2),
                                        contentColor = Color(0xFF18120A),
                                    ),
                                ) {
                                    Text("Sign In / Link TV")
                                }
                                OutlinedButton(
                                    onClick = onSignIn,
                                    shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
                                ) {
                                    Text("Create Account")
                                }
                            }
                        }
                    }
                    SettingsCard("Current Profile") {
                        if (activeProfile != null) {
                            ProfileSummary(activeProfile)
                        } else {
                            Text(
                                text = if (session == null) "Sign in above to select a profile." else "No profile active.",
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                            )
                        }
                    }
                    if (bootstrap?.streamProfiles?.isNotEmpty() == true) {
                    SettingsCard("Switch Profile") {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            bootstrap?.streamProfiles?.forEach { profile ->
                                Button(
                                        onClick = {
                                            scope.launch {
                                                repository.setActiveStreamProfile(profile.id)
                                                bootstrap = repository.refreshBootstrap()
                                                status = "Using ${profile.name}"
                                            }
                                        },
                                        shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
                                        colors = ButtonDefaults.colors(
                                            containerColor = if (activeProfile?.id == profile.id) Color(0xFFF4EDE2) else Color(0xFF15181D),
                                            contentColor = if (activeProfile?.id == profile.id) Color(0xFF18120A) else MaterialTheme.colorScheme.onBackground,
                                        ),
                                    ) {
                                        ProfileAvatar(profile)
                                        Text(" ${profile.name}")
                                    }
                                }
                            }
                        }
                    }
                }
                SettingsSection.Accounts -> {
                    SettingsCard("Account") {
                        TextLine("Email", session?.user?.email ?: "Guest")
                        TextLine("Display Name", bootstrap?.profile?.displayName ?: session?.user?.displayName ?: "Not set")
                        TextLine("Devices", bootstrap?.devices?.size?.toString() ?: "0")
                        TextLine("Sessions", bootstrap?.sessions?.size?.toString() ?: "0")
                    }
                    SettingsCard("Debrid") {
                        val accounts = bootstrap?.integrations?.debrid?.accounts.orEmpty()
                        if (accounts.isEmpty()) {
                            Text(
                                text = "No debrid accounts are linked to this StreamDek profile.",
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                            )
                        } else {
                            accounts.forEach { account ->
                                TextLine(account.provider, account.username ?: "Priority ${account.priority + 1}")
                            }
                        }
                    }
                    SettingsCard("Addons") {
                        if (addons.isEmpty()) {
                            Text(
                                text = "No synced addons found.",
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                            )
                        } else {
                            addons.forEach { addon ->
                                AddonRow(
                                    addon = addon,
                                    onToggle = {
                                        scope.launch {
                                            repository.toggleAddon(addon.id, !addon.enabled)
                                            addons = repository.fetchAddonManifests(forceRefresh = true)
                                            bootstrap = repository.bootstrap.value
                                        }
                                    },
                                    onRemove = {
                                        scope.launch {
                                            repository.uninstallAddon(addon.id)
                                            addons = repository.fetchAddonManifests(forceRefresh = true)
                                            bootstrap = repository.bootstrap.value
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                SettingsSection.General -> {
                    SettingsCard("General") {
                        TextLine("App", "StreamDek TV")
                        TextLine("Theme", "Cinema")
                        TextLine("Sync", bootstrap?.syncStatus?.lastSettingsSyncAt ?: "Ready")
                    }
                }
                SettingsSection.Playback -> {
                    SettingsCard("Playback") {
                        PreferenceRow("Autoplay Next Episode", prefs?.autoplayNextEpisode == true) {
                            scope.launch {
                                repository.updatePlaybackPreferences(mapOf("autoplayNextEpisode" to !(prefs?.autoplayNextEpisode == true)))
                                bootstrap = repository.bootstrap.value
                            }
                        }
                        ChoiceRow("Preferred Quality", prefs?.preferredQuality ?: "best") {
                            scope.launch {
                                repository.updatePlaybackPreferences(
                                    mapOf("preferredQuality" to nextOf(listOf("best", "4k", "1080p", "720p"), prefs?.preferredQuality ?: "best")),
                                )
                                bootstrap = repository.bootstrap.value
                            }
                        }
                        ChoiceRow("Subtitle Language", prefs?.defaultSubtitleLanguage ?: "off") {
                            scope.launch {
                                repository.updatePlaybackPreferences(
                                    mapOf("defaultSubtitleLanguage" to nextOf(listOf("off", "en", "es", "pt"), prefs?.defaultSubtitleLanguage ?: "off")),
                                )
                                bootstrap = repository.bootstrap.value
                            }
                        }
                        ChoiceRow("Audio Language", prefs?.defaultAudioLanguage ?: "en") {
                            scope.launch {
                                repository.updatePlaybackPreferences(
                                    mapOf("defaultAudioLanguage" to nextOf(listOf("en", "es", "pt", "original"), prefs?.defaultAudioLanguage ?: "en")),
                                )
                                bootstrap = repository.bootstrap.value
                            }
                        }
                        ChoiceRow("Resolved URL Cache", prefs?.resolvedPlaybackUrlCacheTtl ?: "1h") {
                            scope.launch {
                                repository.updatePlaybackPreferences(
                                    mapOf("resolvedPlaybackUrlCacheTtl" to nextOf(listOf("15m", "1h", "12h", "24h"), prefs?.resolvedPlaybackUrlCacheTtl ?: "1h")),
                                )
                                bootstrap = repository.bootstrap.value
                            }
                        }
                    }
                }
                SettingsSection.About -> {
                    SettingsCard("About") {
                        TextLine("Version", "1.0")
                        TextLine("Client", "Android TV")
                        TextLine("API", "Connected")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsMenuItem(
    title: String,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(999.dp))
            .onFocusChanged { if (it.isFocused) onFocused() }
            .clickable { onClick() },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = title,
            color = if (selected) Color(0xFFF4EDE2) else MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 18.dp),
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xAA11141B), RoundedCornerShape(28.dp))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(28.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
        )
        content()
    }
}

@Composable
private fun ProfileSummary(profile: StreamProfile) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ProfileAvatar(profile, size = 54.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = buildList {
                    if (profile.isDefault) add("Default")
                    if (profile.hasPinSet) add("PIN locked")
                    if (profile.maturityRating != "all") add(profile.maturityRating.uppercase())
                }.joinToString("  •  ").ifBlank { "Ready to watch" },
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            )
        }
    }
}

@Composable
private fun ProfileAvatar(profile: StreamProfile, size: androidx.compose.ui.unit.Dp = 34.dp) {
    ProfileAvatarCircle(
        avatarIndex = profile.avatarIndex,
        fallbackLabel = profile.name,
        size = size,
    )
}

@Composable
private fun PreferenceRow(label: String, value: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f))
        OutlinedButton(onClick = onToggle, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) {
            Text(if (value) "On" else "Off")
        }
    }
}

@Composable
private fun ChoiceRow(label: String, value: String, onCycle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f))
        OutlinedButton(onClick = onCycle, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) {
            Text(value)
        }
    }
}

@Composable
private fun TextLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f))
        Text(value, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun AddonRow(
    addon: AddonManifest,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF15181D), RoundedCornerShape(22.dp))
            .border(1.dp, Color(0x18FFFFFF), RoundedCornerShape(22.dp))
            .padding(18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(addon.manifest.name, color = MaterialTheme.colorScheme.onBackground)
            Text(
                text = "v${addon.manifest.version}  •  ${addon.manifest.description ?: addon.id}",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onToggle, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) {
                Text(if (addon.enabled) "Disable" else "Enable")
            }
            OutlinedButton(onClick = onRemove, shape = ButtonDefaults.shape(RoundedCornerShape(999.dp))) {
                Text("Remove")
            }
        }
    }
}

private fun nextOf(values: List<String>, current: String): String {
    val index = values.indexOf(current)
    return if (index < 0 || index == values.lastIndex) values.first() else values[index + 1]
}
