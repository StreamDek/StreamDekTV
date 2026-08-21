package com.streamdek.tv.nativeapp.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamdek.tv.BuildConfig
import com.streamdek.tv.nativeapp.data.AccountBootstrap
import com.streamdek.tv.nativeapp.data.AddonManifest
import com.streamdek.tv.nativeapp.data.DefaultTrailerCacheClearHours
import com.streamdek.tv.nativeapp.data.DefaultTrailerDelaySeconds
import com.streamdek.tv.nativeapp.data.Languages
import com.streamdek.tv.nativeapp.data.MaxTrailerDelaySeconds
import com.streamdek.tv.nativeapp.data.ProfilePluginProvider
import com.streamdek.tv.nativeapp.data.ProfilePluginRepo
import com.streamdek.tv.nativeapp.data.ProfilePluginState
import com.streamdek.tv.nativeapp.data.RemotePlaylist
import com.streamdek.tv.nativeapp.data.PlaybackCodecOptions
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.SyncServiceId
import com.streamdek.tv.nativeapp.data.TrailerCache
import com.streamdek.tv.nativeapp.data.TrailerCacheClearChoices
import com.streamdek.tv.nativeapp.data.clearTrailerState
import com.streamdek.tv.nativeapp.data.trailerCacheClearLabel
import com.streamdek.tv.nativeapp.ui.ProfileAvatarCircle
import com.streamdek.tv.nativeapp.ui.TvChromeSurface
import com.streamdek.tv.nativeapp.ui.TvChromePanel
import com.streamdek.tv.nativeapp.ui.streamDekThemeAccent
import com.streamdek.tv.nativeapp.update.AppUpdateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val LocalSettingsLeftRequester = staticCompositionLocalOf<FocusRequester?> { null }

private enum class PluginSourceSection { Regular, CloudStream, SkyStream }

private fun pluginSourceSection(
    repo: ProfilePluginRepo,
    providers: List<ProfilePluginProvider>,
): PluginSourceSection {
    val identity = buildString {
        append(repo.name).append(' ').append(repo.url).append(' ').append(repo.description.orEmpty())
        providers.forEach { append(' ').append(it.name).append(' ').append(it.id) }
    }.lowercase()
    return when {
        "cloudstream" in identity || "cloud stream" in identity || "cloud-stream" in identity -> PluginSourceSection.CloudStream
        "skystream" in identity || "sky stream" in identity || "sky-stream" in identity -> PluginSourceSection.SkyStream
        else -> PluginSourceSection.Regular
    }
}

/**
 * The rail, grouped and ordered to match the mobile app's settings home: the account, then what
 * plays, then how it looks, then where content comes from, what the TV is connected to, and the
 * app itself.
 *
 * Kept to ten entries. Every extra destination is another press away from the one being looked
 * for, so pages that held a single control were folded into the page that owns the subject:
 * subtitles and the live progress bar sit in Playback, legacy compact mode in Accessibility, and
 * the old Diagnostics health check in About.
 */
private enum class SettingsDestination(val label: String, val description: String, val terms: String, val icon: ImageVector) {
    Account("Account", "Profiles, sign-in and household access", "accounts profile pin sign in switch household", Icons.Outlined.AccountCircle),
    Playback("Playback", "Player, audio, subtitles and compatibility", "engine mpv media3 exoplayer decoder display surface audio language subtitles live progress", Icons.Outlined.PlayArrow),
    SkipAndAutoplay("Skip & Autoplay", "Intros, recaps and the next episode", "skip intro recap ending credits autoplay next episode binge threshold", Icons.Outlined.SkipNext),
    Streams("Streams & Quality", "Preferred quality, limits and result labels", "quality resolution 4k 1080p file size picker source badges labels", Icons.Outlined.Tune),
    Library("Home & Layout", "Rows, cards and browsing layout", "home catalogs rows poster landscape grid columns density start screen trailer trailers autoplay title page", Icons.Outlined.VideoLibrary),
    LiveTv("Live TV", "Channel lists, cards and the live player", "live tv channel channels iptv category categories group landscape cards favourite favorite drawer progress bar", Icons.Outlined.LiveTv),
    Appearance("Appearance", "Theme, motion and presentation", "accent colour theme animation blur transparent navigation", Icons.Outlined.Palette),
    Accessibility("Accessibility", "Contrast, text and reduced motion", "vision screen reader high contrast large text compact", Icons.Outlined.Accessibility),
    Sources("Sources", "Add-ons, plugins and premium services", "providers addon plugin cloudstream debrid premium install", Icons.Outlined.Extension),
    Connections("Connections", "Tracking services, devices and sessions", "tracking trakt simkl mdblist sync devices television session cloud", Icons.Outlined.Sync),
    About("About", "Version, updates and health", "release update version diagnostics health cache network runtime", Icons.Outlined.Info),
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
    var playlists by remember { mutableStateOf<List<RemotePlaylist>>(emptyList()) }
    var selected by remember { mutableStateOf(SettingsDestination.Account) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    // What the trailer cache holds, refreshed after a clear so the row reports what just happened
    // rather than what it held when the screen opened.
    var trailerCacheStatus by remember {
        mutableStateOf(trailerCacheStatusLabel(TrailerCache.sizeBytes(context), TrailerCache.lastClearedAt(context)))
    }
    // Held rather than folded into `status`: the code has to stay on screen while the viewer walks
    // to their phone and types it, and a status line is written over by the next thing that happens.
    var signInPrompt by remember { mutableStateOf<DeviceSignIn?>(null) }
    var expandedPluginParents by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** Plugin source whose settings cog is open, if any. */
    var editingPluginProvider by remember { mutableStateOf<ProfilePluginProvider?>(null) }
    /** Which premium service the connect row acts on. */
    var debridProviderChoice by remember { mutableStateOf(repository.supportedDebridProviders().first().first) }
    /** Set while a service that wants a typed key is being connected. */
    var debridKeyEntry by remember { mutableStateOf<Pair<String, String>?>(null) }
    /** Long synced lists arrive collapsed; these track the ones opened by hand. */
    var addonsExpanded by remember { mutableStateOf(false) }
    var pluginsExpanded by remember { mutableStateOf(false) }
    var playlistsExpanded by remember { mutableStateOf(false) }
    var rememberLastProfileAtStartup by remember {
        mutableStateOf(repository.rememberLastProfileAtStartup())
    }
    val contentEntryRequester = remember { FocusRequester() }
    val destinationRequesters = remember(entryFocusRequester) {
        SettingsDestination.entries.associateWith { if (it == SettingsDestination.Account) entryFocusRequester else FocusRequester() }
    }
    val contentScroll = rememberScrollState()

    LaunchedEffect(Unit) {
        bootstrap = repository.refreshBootstrap()
        addons = repository.fetchAddonManifests()
        playlists = repository.fetchPlaylists()
        delay(120)
        runCatching { destinationRequesters.getValue(SettingsDestination.Account).requestFocus() }
    }
    LaunchedEffect(selected) { contentScroll.scrollTo(0) }
    LaunchedEffect(status) {
        val message = status ?: return@LaunchedEffect
        delay(2_500)
        if (status == message) status = null
    }

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
    val homePrefs = prefs?.home
    val detailPrefs = prefs?.detail
    val pluginState = bootstrap?.profilePlugins
    val themeOptions = listOf(
        "streamdek" to "StreamDek",
        "cinema-blue" to "Cinema blue",
        "carbon-gold" to "Carbon gold",
        "frost-neon" to "Frost neon",
        "ember-red" to "Ember red",
        "aurora-green" to "Aurora green",
        "violet-pulse" to "Violet pulse",
    )
    val themeColors = themeOptions.associate { (value, _) -> value to streamDekThemeAccent(value) }

    fun savePreference(
        label: String,
        onComplete: (Boolean) -> Unit = {},
        update: suspend () -> AccountBootstrap?,
    ) {
        scope.launch {
            status = "Saving ${label}..."
            val updated = runCatching { update() }.getOrNull()
            if (updated == null) {
                status = "$label could not be saved. Check the connection and try again."
            } else {
                bootstrap = updated
                status = "$label saved and synced."
            }
            onComplete(updated != null)
        }
    }

    val trackingOptions = buildList {
        val integrations = bootstrap?.integrations
        if (integrations?.trakt?.connected == true) add(SyncServiceId.TRAKT to "Trakt")
        if (integrations?.simkl?.connected == true) add(SyncServiceId.SIMKL to "Simkl")
        if (integrations?.mdblist?.connected == true) add(SyncServiceId.MDBLIST to "MDBList")
        if (integrations?.punchplay?.connected == true) add(SyncServiceId.PUNCHPLAY to "PunchPlay")
        val current = SyncServiceId.normalize(homePrefs?.primarySyncService)
        if (none { it.first == current }) add(current to SyncServiceId.label(current))
    }.distinctBy { it.first }

    CompositionLocalProvider(LocalSettingsLeftRequester provides selectedRequester) {
    Row(
        // Clears the clock at the top right, then uses the rest of the screen. The old 92dp right
        // inset was reserving space nothing occupied.
        Modifier.fillMaxSize().background(TvChromeSurface).padding(start = 18.dp, end = 28.dp, top = 56.dp, bottom = 20.dp),
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
            SettingsOverviewCard(
                destination = selected,
                status = status,
                requester = contentEntryRequester,
                leftRequester = selectedRequester,
                // Refreshing belongs beside the heading it refreshes. At the foot of the page it
                // was behind every add-on, plugin collection and playlist on the account — the
                // furthest thing from the top on the one page whose lists come from elsewhere and
                // are the reason to reach for it.
                action = if (selected == SettingsDestination.Sources) {
                    { requester ->
                        SettingsHeaderAction("Refresh sources", requester, selectedRequester) {
                            scope.launch {
                                status = "Refreshing sources..."
                                bootstrap = repository.refreshBootstrap()
                                addons = repository.fetchAddonManifests(forceRefresh = true)
                                playlists = repository.fetchPlaylists(forceRefresh = true)
                                status = "Sources refreshed."
                            }
                        }
                    }
                } else {
                    null
                },
            )
            when (selected) {
                SettingsDestination.Account -> {
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
                        SettingsToggleRow(
                            "Remember Last profile at startup",
                            "Skip the profile picker when this TV opens and continue with the last profile used",
                            rememberLastProfileAtStartup,
                            selectedRequester,
                        ) { next, complete ->
                            repository.setRememberLastProfileAtStartup(next)
                            rememberLastProfileAtStartup = next
                            complete(true)
                        }
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
                SettingsDestination.Playback -> {
                    SettingsPanel("Synced TV playback") {
                        InfoLine("Cloud scope", "Changes apply on this TV and sync to mobile")
                    }
                    SettingsDropdownRow("Default player", "Auto uses Media3 first with one MPV fallback", normalizePlayerEngine(playbackPrefs?.playerEngine), listOf("Auto" to "Auto", "ExoPlayer" to "Media3 / ExoPlayer", "MPV" to "MPV")) { value ->
                        savePreference("Default player") { repository.updatePlaybackPreferences(mapOf("playerEngine" to value)) }
                    }
                    SettingsDropdownRow("Default audio", "Select the first matching audio track", normalizeLanguage(playbackPrefs?.defaultAudioLanguage), languageOptions(includeOff = false)) { value ->
                        savePreference("Default audio") { repository.updatePlaybackPreferences(mapOf("defaultAudioLanguage" to value)) }
                    }
                    SettingsDropdownRow("Default subtitles", "Choose a preferred subtitle language or leave subtitles off", normalizeLanguage(playbackPrefs?.defaultSubtitleLanguage, allowOff = true), languageOptions(includeOff = true)) { value ->
                        savePreference("Default subtitles") { repository.updatePlaybackPreferences(mapOf("defaultSubtitleLanguage" to value)) }
                    }
                    SettingsToggleRow("Auto-load subtitles", "Automatically select matching subtitles when playback starts", playbackPrefs?.autoLoadSubtitles != false, selectedRequester) { next, complete ->
                        savePreference("Auto-load subtitles", complete) { repository.updatePlaybackPreferences(mapOf("autoLoadSubtitles" to next)) }
                    }
                    // Last on the page: only worth opening when something will not play.
                    SettingsPanel("If a video will not play") {
                        InfoLine("When these apply", "Used when MPV is selected, or Auto falls back to it")
                    }
                    SettingsDropdownRow("MPV video compatibility", "Choose hardware acceleration or safe software decoding", normalizeDecoderMode(playbackPrefs?.decoderMode), listOf("HW+" to "Recommended (HW+)", "HW" to "Device (HW)", "SW" to "Safe (SW)")) { value ->
                        savePreference("MPV video compatibility") { repository.updatePlaybackPreferences(mapOf("decoderMode" to value)) }
                    }
                    SettingsDropdownRow("MPV display", "Compatibility mode uses a texture-backed video surface", normalizeRenderSurface(playbackPrefs?.renderSurface), listOf("Standard" to "Standard", "Compatibility" to "Compatibility")) { value ->
                        savePreference("MPV display") { repository.updatePlaybackPreferences(mapOf("renderSurface" to value)) }
                    }
                }
                SettingsDestination.SkipAndAutoplay -> {
                    SettingsToggleRow("Skip intro", "Show the skip control when an intro is detected", playbackPrefs?.isSegmentEnabled("intro") != false, selectedRequester) { next, complete ->
                        savePreference("Skip intro", complete) { repository.updatePlaybackPreferences(mapOf("skipIntroEnabled" to next)) }
                    }
                    SettingsToggleRow("Skip recap", "Show the skip control when a recap is detected", playbackPrefs?.isSegmentEnabled("recap") != false, selectedRequester) { next, complete ->
                        savePreference("Skip recap", complete) { repository.updatePlaybackPreferences(mapOf("skipRecapEnabled" to next)) }
                    }
                    SettingsToggleRow("Skip ending", "Show the skip control when an ending is detected", playbackPrefs?.isSegmentEnabled("outro") != false, selectedRequester) { next, complete ->
                        savePreference("Skip ending", complete) { repository.updatePlaybackPreferences(mapOf("skipEndingEnabled" to next)) }
                    }
                    SettingsToggleRow("Auto-play next episode", "Start the next episode near the configured threshold", playbackPrefs?.isAutoPlayNextEpisodeEnabled() != false, selectedRequester) { next, complete ->
                        savePreference("Auto-play next episode", complete) { repository.updatePlaybackPreferences(mapOf("autoPlayNextEpisodeEnabled" to next)) }
                    }
                    SettingsToggleRow("Keep the same source", "Prefer the current provider and release group for the next episode", playbackPrefs?.preferBingeGroupNextEpisode != false, selectedRequester) { next, complete ->
                        savePreference("Next-episode source", complete) { repository.updatePlaybackPreferences(mapOf("preferBingeGroupNextEpisode" to next)) }
                    }
                    SettingsDropdownRow("Next episode trigger", "Choose whether autoplay uses remaining time or watched percentage", playbackPrefs?.nextEpisodeThresholdMode ?: "minutes", listOf("minutes" to "Minutes remaining", "percent" to "Watched percentage")) { value ->
                        savePreference("Next episode trigger") { repository.updatePlaybackPreferences(mapOf("nextEpisodeThresholdMode" to value)) }
                    }
                    if (playbackPrefs?.nextEpisodeThresholdMode.equals("percent", true)) {
                        SettingsDropdownRow("Watched percentage", "Percentage watched before the next episode starts", (playbackPrefs?.nextEpisodeThresholdPercent ?: 95).toString(), listOf(80, 85, 90, 95, 98).map { it.toString() to "$it%" }) { value ->
                            savePreference("Watched percentage") { repository.updatePlaybackPreferences(mapOf("nextEpisodeThresholdPercent" to value.toInt())) }
                        }
                    } else {
                        SettingsDropdownRow("Minutes remaining", "Remaining time before the next episode starts", (playbackPrefs?.nextEpisodeThresholdMinutes ?: 2).toString(), listOf(1, 2, 3, 5, 10, 15).map { it.toString() to "$it minutes" }) { value ->
                            savePreference("Minutes remaining") { repository.updatePlaybackPreferences(mapOf("nextEpisodeThresholdMinutes" to value.toInt())) }
                        }
                    }
                }
                SettingsDestination.Streams -> {
                    SettingsDropdownRow("Preferred quality", "Rank matching streams first across TV and mobile", normalizePreferredQuality(playbackPrefs?.preferredQuality), listOf("Auto" to "Auto", "2160p" to "4K / 2160p", "1080p" to "1080p", "720p" to "720p")) { value ->
                        savePreference("Preferred quality") { repository.updatePlaybackPreferences(mapOf("preferredQuality" to value)) }
                    }
                    SettingsDropdownRow("Maximum file size", "Hide larger streams when size metadata is available", playbackPrefs?.maxFileSizeGB ?: "0", listOf("0" to "Unlimited", "2" to "2 GB", "5" to "5 GB", "10" to "10 GB", "20" to "20 GB")) { value ->
                        savePreference("Maximum file size") { repository.updatePlaybackPreferences(mapOf("maxFileSizeGB" to value)) }
                    }
                    SettingsToggleRow("Show stream picker", "Choose a source before playback instead of selecting automatically", streamsPrefs?.showStreamsList != false, selectedRequester) { next, complete ->
                        savePreference("Stream picker", complete) { repository.updateStreamsPreferences(mapOf("showStreamsList" to next)) }
                    }
                    SettingsToggleRow("Remember last source", "Prefer the source previously used for the same title", streamsPrefs?.rememberLastSource != false, selectedRequester) { next, complete ->
                        savePreference("Remember last source", complete) { repository.updateStreamsPreferences(mapOf("rememberLastSource" to next)) }
                    }
                    SettingsToggleRow("Stream detail badges", "Show quality, source, codec and HDR labels", streamsPrefs?.fusionBadgesEnabled != false, selectedRequester) { next, complete ->
                        savePreference("Stream detail badges", complete) { repository.updateStreamsPreferences(mapOf("fusionBadgesEnabled" to next)) }
                    }
                    SettingsToggleRow("Size badges", "Show file sizes on stream choices", streamsPrefs?.showSizeBadges != false, selectedRequester) { next, complete ->
                        savePreference("Size badges", complete) { repository.updateStreamsPreferences(mapOf("showSizeBadges" to next)) }
                    }
                    SettingsToggleRow(
                        "StreamDek formatting",
                        "Rebuild add-on results into StreamDek's comparison layout. Off shows the add-on's original text and line breaks.",
                        streamsPrefs?.streamDekFormattingEnabled == true,
                        selectedRequester,
                    ) { next, complete ->
                        savePreference("StreamDek formatting", complete) {
                            repository.updateStreamsPreferences(mapOf("streamDekFormattingEnabled" to next))
                        }
                    }
                    // Decoder choices for this box, kept out of the synced profile: whether Dolby
                    // Vision needs mapping down, and whether tunneled output works, is a property
                    // of this hardware and not of the account. See PlaybackCodecOptions.
                    var dv7HevcFallback by remember { mutableStateOf(PlaybackCodecOptions.dv7HevcFallback) }
                    var tunneledPlayback by remember { mutableStateOf(PlaybackCodecOptions.tunneledPlayback) }
                    SettingsToggleRow(
                        "DV7 - HEVC Fallback",
                        "Map Dolby Vision Profile 7 to standard HEVC for devices without Dolby Vision hardware support. Turn this on if those files play as a black screen or refuse to start. The picture loses the Dolby Vision grade but plays at full resolution.",
                        dv7HevcFallback,
                        selectedRequester,
                    ) { next, complete ->
                        PlaybackCodecOptions.setDv7HevcFallback(context, next)
                        dv7HevcFallback = next
                        complete(true)
                    }
                    SettingsToggleRow(
                        "Tunneled playback",
                        "Hand decoding and display to the hardware as one pipeline, which can hold audio and video in step on devices that support it. Turn it off again if the picture goes black while the sound keeps playing.",
                        tunneledPlayback,
                        selectedRequester,
                    ) { next, complete ->
                        PlaybackCodecOptions.setTunneledPlayback(context, next)
                        tunneledPlayback = next
                        complete(true)
                    }
                }
                SettingsDestination.Library -> {
                    SettingsToggleRow("Built-in catalogs", "Show StreamDek's default movie and series rows alongside add-ons", homePrefs?.defaultAppCatalogsEnabled != false, selectedRequester) { next, complete ->
                        savePreference("Built-in catalogs", complete) { repository.updateHomePreferences(mapOf("defaultAppCatalogsEnabled" to next)) }
                    }
                    SettingsToggleRow("Hide home synopsis", "Drop the description from the Home spotlight and centre the title and its details", appPrefs?.hideHomeSynopsis != false, selectedRequester) { next, complete ->
                        savePreference("Hide home synopsis", complete) { repository.updateAppPreferences(mapOf("hideHomeSynopsis" to next)) }
                    }
                    SettingsDropdownRow("Home row cards", "Use landscape or portrait artwork on the Home screen", appPrefs?.homeRowCardStyle ?: "landscape", listOf("landscape" to "Landscape", "portrait" to "Portrait")) { value ->
                        savePreference("Home row cards") { repository.updateAppPreferences(mapOf("homeRowCardStyle" to value)) }
                    }
                    SettingsDropdownRow("Card density", "Comfortable or compact browsing", appPrefs?.cardDensity ?: "comfortable", listOf("comfortable" to "Comfortable", "compact" to "Compact")) { value ->
                        savePreference("Card density") { repository.updateAppPreferences(mapOf("cardDensity" to value)) }
                    }
                    SettingsDropdownRow("Grid columns", "Balance artwork size and visible items", (appPrefs?.gridSize ?: 5).toString(), (4..7).map { it.toString() to "$it columns" }) { value ->
                        savePreference("Grid columns") { repository.updateAppPreferences(mapOf("gridSize" to value.toInt())) }
                    }
                    // Sits with Home rather than Appearance: it picks which screen opens, not how it looks.
                    SettingsDropdownRow("Start screen", "Choose where StreamDek opens", appPrefs?.startScreen ?: "home", listOf("home" to "Home", "library" to "Library", "continue-watching" to "Continue watching")) { value ->
                        savePreference("Start screen") { repository.updateAppPreferences(mapOf("startScreen" to value)) }
                    }
                    SettingsPanel("Title page") {
                        SettingsToggleRow(
                            "Play trailers automatically",
                            "When a title page opens, play its trailer full screen as soon as one is ready. Back returns to the page, and the replay button on the page plays it again.",
                            detailPrefs?.heroTrailerAutoplay != false,
                            selectedRequester,
                        ) { next, complete ->
                            savePreference("Play trailers automatically", complete) {
                                repository.updateDetailPreferences(mapOf("heroTrailerAutoplay" to next))
                            }
                        }
                        // How long the page has to itself before the trailer takes the screen.
                        // Nothing here affects the trailer button, which always plays at once:
                        // somebody who pressed it has already decided.
                        SettingsDropdownRow(
                            "Trailer start delay",
                            "How long a title page stays put before its trailer begins. The trailer button ignores this and plays straight away.",
                            (detailPrefs?.heroTrailerDelaySeconds ?: DefaultTrailerDelaySeconds)
                                .coerceIn(0, MaxTrailerDelaySeconds).toString(),
                            (0..MaxTrailerDelaySeconds).map { seconds ->
                                seconds.toString() to if (seconds == 0) "Immediately" else "$seconds second${if (seconds == 1) "" else "s"}"
                            },
                        ) { value ->
                            savePreference("Trailer start delay") {
                                repository.updateDetailPreferences(
                                    mapOf(
                                        "heroTrailerDelaySeconds" to
                                            (value.toIntOrNull() ?: DefaultTrailerDelaySeconds).coerceIn(0, MaxTrailerDelaySeconds),
                                    ),
                                )
                            }
                        }
                        // The same four steps the phone offers, against the same synced value, so a
                        // household that has already chosen one does not find the television
                        // quietly ignoring it — the TV was reading this setting and honouring it
                        // with no way to change it here.
                        SettingsDropdownRow(
                            "Trailer quality",
                            "The best picture a trailer may use. YouTube is asked for this and serves the closest it can.",
                            (detailPrefs?.heroTrailerResolution ?: 2160).coerceIn(360, 2160).toString(),
                            listOf("360" to "360p", "720" to "720p", "1080" to "1080p", "2160" to "2160p"),
                        ) { value ->
                            savePreference("Trailer quality") {
                                repository.updateDetailPreferences(
                                    mapOf("heroTrailerResolution" to (value.toIntOrNull() ?: 2160)),
                                )
                            }
                        }
                        // Trailers come from a source that decides for itself whether the caller
                        // looks like a browser, and it keeps that judgement in cookies and site
                        // storage the embed leaves behind. Once that state sours, trailers stop
                        // playing until it is thrown away — and on a television there is no
                        // "clear app data" a viewer can reasonably be asked to find.
                        val trailerClearHours = detailPrefs?.trailerCacheClearHours ?: DefaultTrailerCacheClearHours
                        SettingsDropdownRow(
                            "Clear trailer cache",
                            "Trailers can stop playing when the stored playback state goes stale. StreamDek clears it on this schedule, at 9am.",
                            trailerClearHours.toString(),
                            // The synced value is folded in so a choice made on the phone that this
                            // build does not list still reads as an interval rather than a number.
                            (TrailerCacheClearChoices + (trailerClearHours to trailerCacheClearLabel(trailerClearHours)))
                                .distinctBy { it.first }
                                .sortedBy { it.first }
                                .map { (hours, label) -> hours.toString() to label },
                        ) { value ->
                            savePreference("Trailer cache schedule") {
                                repository.updateDetailPreferences(
                                    mapOf(
                                        "trailerCacheClearHours" to
                                            (value.toIntOrNull() ?: DefaultTrailerCacheClearHours),
                                    ),
                                )
                            }
                        }
                        SettingsActionRow(
                            "Clear trailer cache now",
                            trailerCacheStatus,
                            "Clear",
                            selectedRequester,
                        ) {
                            // Synchronous on the main thread by necessity: WebView and
                            // CookieManager, which hold the state that actually matters here,
                            // refuse to be touched from anywhere else.
                            val freed = clearTrailerState(context, "requested from settings")
                            trailerCacheStatus = trailerCacheStatusLabel(
                                TrailerCache.sizeBytes(context),
                                TrailerCache.lastClearedAt(context),
                            )
                            status = if (freed > 0) {
                                "Trailer cache cleared, ${freed / 1024}KB freed."
                            } else {
                                "Trailer cache cleared."
                            }
                        }
                    }
                }
                SettingsDestination.LiveTv -> {
                    SettingsPanel("Channel list") {
                        InfoLine("Synced with mobile", "These match the Live TV page in StreamDek Mobile")
                    }
                    SettingsToggleRow("Landscape channel cards", "Show channels as wide cards. Off uses portrait artwork.", homePrefs?.liveLandscapeCards != false, selectedRequester) { next, complete ->
                        savePreference("Landscape channel cards", complete) { repository.updateHomePreferences(mapOf("liveLandscapeCards" to next)) }
                    }
                    SettingsToggleRow("Group channels into categories", "List each source's categories in the sidebar. Off lists one entry per source.", homePrefs?.liveCategoriesEnabled != false, selectedRequester) { next, complete ->
                        savePreference("Channel categories", complete) { repository.updateHomePreferences(mapOf("liveCategoriesEnabled" to next)) }
                    }
                    SettingsToggleRow("Card-style favourites", "Show channel artwork in the player's favourites drawer. Off uses the compact text list.", homePrefs?.liveFavouriteDrawerCards == true, selectedRequester) { next, complete ->
                        savePreference("Card-style favourites", complete) { repository.updateHomePreferences(mapOf("liveFavouriteDrawerCards" to next)) }
                    }
                    SettingsToggleRow("Live progress bar", "Show the timeline when Live TV or playlist VOD playback opens", playbackPrefs?.liveProgressBarEnabled == true, selectedRequester) { next, complete ->
                        savePreference("Live progress bar", complete) { repository.updatePlaybackPreferences(mapOf("liveProgressBarEnabled" to next)) }
                    }
                    SettingsPanel("Where channels come from") {
                        InfoLine("Add-ons", "${addons.count { it.enabled }} enabled · listed under Sources")
                        InfoLine(
                            "IPTV playlists",
                            if (playlists.isEmpty()) {
                                "None yet · add one from StreamDek Mobile or the web portal"
                            } else {
                                "${playlists.count { it.enabled }} of ${playlists.size} on · turn them on and off under Sources"
                            },
                        )
                    }
                }
                SettingsDestination.Sources -> {
                    // A sign-in in progress owns the top of the page: the code on it is being read
                    // off the television and typed on a phone, and it must not be somewhere the
                    // viewer has to scroll back to.
                    signInPrompt?.let { prompt ->
                        DeviceSignInPanel(
                            providerLabel = prompt.providerLabel,
                            verificationUrl = prompt.verificationUrl,
                            userCode = prompt.userCode,
                            waiting = prompt.waiting,
                            outcome = prompt.outcome,
                        )
                    }
                    // Premium services first. They are the thing on this page a viewer comes to set
                    // up, and they used to sit under three panels of synced lists that nothing on
                    // this television can change.
                    SettingsPanel("Debrid accounts") {
                        val accounts = bootstrap?.integrations?.debrid?.accounts.orEmpty()
                        val debridProviders = repository.supportedDebridProviders()
                        val chosenLabel = debridProviderLabel(debridProviders, debridProviderChoice)
                        val usesSignIn = repository.debridProviderUsesDeviceSignIn(debridProviderChoice)
                        val alreadyLinked = accounts.any { it.provider.equals(debridProviderChoice, true) }
                        InfoLine(
                            "Linked services",
                            if (accounts.isEmpty()) {
                                "None yet — pick one below"
                            } else {
                                accounts.joinToString(", ") { debridProviderLabel(debridProviders, it.provider) }
                            },
                        )
                        // Every supported service, not just the one with a sign-in button. Only
                        // Real-Debrid was ever offered here, so an account holder with TorBox or
                        // AllDebrid had to link it on another device to use it on this one.
                        SettingsDropdownRow(
                            "Service",
                            "Every premium service StreamDek can talk to. Choose one, then connect it below.",
                            debridProviderChoice,
                            debridProviders,
                        ) { debridProviderChoice = it }
                        SettingsActionRow(
                            if (alreadyLinked) "Reconnect $chosenLabel" else "Connect $chosenLabel",
                            if (usesSignIn) {
                                "Approve a short code on your phone — nothing to type on the remote"
                            } else {
                                "Enter the API key from your $chosenLabel account"
                            },
                            if (usesSignIn) "Sign in" else "Enter key",
                            selectedRequester,
                        ) {
                            when {
                                debridProviderChoice == "real-debrid" -> scope.launch {
                                    status = "Asking Real-Debrid for a code..."
                                    val started = repository.startRealDebridSignIn()
                                    if (started == null) {
                                        status = "Real-Debrid could not be reached. Try again in a moment."
                                        return@launch
                                    }
                                    status = null
                                    signInPrompt = DeviceSignIn("Real-Debrid", started.verificationUrl, started.userCode)
                                    val username = repository.completeRealDebridSignIn(started)
                                    signInPrompt = signInPrompt?.copy(
                                        waiting = false,
                                        outcome = if (username != null) {
                                            bootstrap = repository.bootstrap.value
                                            "Connected as $username."
                                        } else {
                                            "That code expired before it was approved. Start again for a new one."
                                        },
                                    )
                                }
                                debridProviderChoice == "premiumize" && usesSignIn -> scope.launch {
                                    status = "Asking Premiumize for a code..."
                                    val started = repository.startPremiumizeSignIn()
                                    if (started == null) {
                                        status = "Premiumize could not be reached. Try again in a moment."
                                        return@launch
                                    }
                                    // The code and where to enter it stay on screen for the whole
                                    // wait: a viewer who looks away should not have to start over
                                    // to read it again.
                                    status = null
                                    signInPrompt = DeviceSignIn("Premiumize", started.verificationUri, started.userCode)
                                    val username = repository.completePremiumizeSignIn(started)
                                    signInPrompt = signInPrompt?.copy(
                                        waiting = false,
                                        outcome = if (username != null) {
                                            bootstrap = repository.bootstrap.value
                                            "Connected as $username."
                                        } else {
                                            "That code expired before it was approved. Start again for a new one."
                                        },
                                    )
                                }
                                else -> debridKeyEntry = debridProviderChoice to chosenLabel
                            }
                        }
                        SettingsToggleRow(
                            "Save keys to your StreamDek account",
                            "Keys are stored encrypted on your account so every device shares them. Turn this off to keep them on this television only.",
                            repository.debridCloudSyncEnabled(),
                            selectedRequester,
                        ) { next, complete ->
                            scope.launch {
                                status = if (next) "Saving keys to your account..." else "Moving keys to this television..."
                                val applied = repository.setDebridCloudSync(next)
                                bootstrap = repository.bootstrap.value
                                status = when {
                                    applied && next -> "Keys are saved to your account."
                                    applied -> "Keys are kept on this television only."
                                    else -> "Your keys could not be copied here, so they were left on your account."
                                }
                                complete(applied)
                            }
                        }
                        accounts.forEach { account ->
                            key(account.provider) {
                                SettingsToggleRow(
                                    debridProviderLabel(debridProviders, account.provider),
                                    account.username ?: "Linked debrid account - priority ${account.priority + 1}",
                                    account.enabled,
                                    selectedRequester,
                                ) { next, complete ->
                                    scope.launch {
                                        status = "Updating ${account.provider}..."
                                        val saved = repository.setDebridAccountEnabled(account.provider, next)
                                        if (saved) {
                                            bootstrap = repository.bootstrap.value
                                            status = "${account.provider} updated."
                                        } else {
                                            status = "${account.provider} could not be updated."
                                        }
                                        complete(saved)
                                    }
                                }
                            }
                        }
                    }
                    SettingsPanel("Synced providers") {
                        InfoLine("Enabled add-ons", "${addons.count { it.enabled }} of ${addons.size}")
                        // Past three, this stops being a summary and becomes a list to scroll
                        // through — and it pushes plugins, playlists and everything below it off
                        // the screen for anyone who only came to check one of those. Long lists
                        // arrive closed, the way the plugin collections under this one do.
                        val collapsibleAddons = addons.size > 3
                        if (collapsibleAddons) {
                            SettingsActionRow(
                                "Installed add-ons",
                                "${addons.size} synced from your account",
                                if (addonsExpanded) "Collapse" else "Expand",
                                selectedRequester,
                            ) { addonsExpanded = !addonsExpanded }
                        }
                        if (!collapsibleAddons || addonsExpanded) {
                            addons.forEach { addon ->
                                key(addon.id) {
                                    SettingsToggleRow(
                                        addon.manifest.name.ifBlank { addon.id },
                                        "Enable or disable this installed add-on for the active profile",
                                        addon.enabled,
                                        selectedRequester,
                                    ) { next, complete ->
                                        scope.launch {
                                            status = "Updating ${addon.manifest.name.ifBlank { addon.id }}..."
                                            val saved = repository.toggleAddon(addon.id, next)
                                            if (saved) {
                                                addons = addons.map { current ->
                                                    if (current.id == addon.id) current.copy(enabled = next) else current
                                                }
                                                bootstrap = repository.bootstrap.value
                                                addons = repository.fetchAddonManifests(forceRefresh = true)
                                                status = "${addon.manifest.name.ifBlank { addon.id }} updated."
                                            } else {
                                                status = "Add-on could not be updated."
                                            }
                                            complete(saved)
                                        }
                                    }
                                }
                            }
                        }
                        if (addons.isEmpty()) InfoLine("Providers", "Install add-ons from StreamDek Mobile")
                    }
                    SettingsPanel("Synced plugins") {
                        if (pluginState == null || (pluginState.repos.isEmpty() && pluginState.providers.isEmpty())) {
                            InfoLine("Plugins", "Install plugins from StreamDek Mobile")
                        } else {
                            SettingsToggleRow(
                                "Plugin sources",
                                "Enable or disable every synced plugin source for this profile",
                                pluginState.enabled,
                                selectedRequester,
                            ) { next, complete ->
                                scope.launch {
                                    val updated = repository.updateProfilePlugins(pluginState.copy(enabled = next))
                                    if (updated != null) bootstrap = updated
                                    status = if (updated != null) "Plugin sources updated." else "Plugin sources could not be updated."
                                    complete(updated != null)
                                }
                            }
                            val repoGroups = pluginState.repos.map { repo ->
                                val providers = pluginState.providers.filter { it.repoUrl == repo.url }
                                Triple(pluginSourceSection(repo, providers), repo, providers)
                            }
                            // Same reasoning as the add-ons above, and it bites harder here: every
                            // collection is itself an expandable row, so a handful of them turns
                            // this panel into a page of its own and pushes playlists off the screen
                            // for anyone who came to look at those.
                            // Counted as rows that would actually appear, not as providers: a
                            // collection is one row however many scrapers are inside it, and the
                            // orphans fold into one "Other synced sources" row per section.
                            val orphanSections = pluginState.providers
                                .filter { provider -> pluginState.repos.none { it.url == provider.repoUrl } }
                                .map { pluginSourceSection(ProfilePluginRepo(), listOf(it)) }
                                .distinct()
                            val collectionCount = repoGroups.size + orphanSections.size
                            val collapsiblePlugins = collectionCount > 2
                            if (collapsiblePlugins) {
                                SettingsActionRow(
                                    "Plugin collections",
                                    "$collectionCount synced from your account",
                                    if (pluginsExpanded) "Collapse" else "Expand",
                                    selectedRequester,
                                ) { pluginsExpanded = !pluginsExpanded }
                            }
                            if (!collapsiblePlugins || pluginsExpanded) {
                                listOf(
                                    PluginSourceSection.Regular to "Regular plugin sources",
                                    PluginSourceSection.CloudStream to "CloudStream sources",
                                    PluginSourceSection.SkyStream to "SkyStream sources",
                                ).forEach { (section, title) ->
                                    val groups = repoGroups.filter { it.first == section }
                                    val knownRepoUrls = pluginState.repos.mapTo(hashSetOf()) { it.url }
                                    val unparentedProviders = pluginState.providers.filter {
                                        it.repoUrl !in knownRepoUrls && pluginSourceSection(ProfilePluginRepo(), listOf(it)) == section
                                    }
                                    if (groups.isNotEmpty() || unparentedProviders.isNotEmpty()) {
                                        InfoLine(
                                            title,
                                            "${groups.size} collection${if (groups.size == 1) "" else "s"} · ${groups.sumOf { it.third.size } + unparentedProviders.size} sources",
                                        )
                                        groups.forEach { (_, repo, providers) ->
                                            val parentKey = "repo:${repo.url}"
                                            val expanded = parentKey in expandedPluginParents
                                            key(parentKey) {
                                                SettingsActionRow(
                                                    repo.name.ifBlank { repo.url },
                                                    "${providers.size} source${if (providers.size == 1) "" else "s"}" + repo.version.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
                                                    if (expanded) "Collapse" else "Expand",
                                                    selectedRequester,
                                                ) {
                                                    expandedPluginParents = if (expanded) expandedPluginParents - parentKey else expandedPluginParents + parentKey
                                                }
                                                if (expanded) {
                                                    SettingsToggleRow(
                                                        "Collection enabled",
                                                        "Enable or disable ${repo.name.ifBlank { "this plugin collection" }} and its sources",
                                                        repo.enabled,
                                                        selectedRequester,
                                                    ) { next, complete ->
                                                        scope.launch {
                                                            val nextState = pluginState.copy(
                                                                repos = pluginState.repos.map { if (it.url == repo.url) it.copy(enabled = next) else it },
                                                                providers = if (next) pluginState.providers else pluginState.providers.map {
                                                                    if (it.repoUrl == repo.url) it.copy(enabled = false) else it
                                                                },
                                                            )
                                                            val updated = repository.updateProfilePlugins(nextState)
                                                            if (updated != null) bootstrap = updated
                                                            status = if (updated != null) "${repo.name.ifBlank { "Plugin collection" }} updated." else "Plugin collection could not be updated."
                                                            complete(updated != null)
                                                        }
                                                    }
                                                    providers.forEach { provider ->
                                                        key("provider:${provider.repoUrl}:${provider.id}") {
                                                            SettingsToggleRow(
                                                                provider.name.ifBlank { provider.id },
                                                                provider.types.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "Plugin provider",
                                                                provider.enabled,
                                                                selectedRequester,
                                                            ) { next, complete ->
                                                                scope.launch {
                                                                    val nextState = pluginState.copy(
                                                                        providers = pluginState.providers.map {
                                                                            if (it.id == provider.id && it.repoUrl == provider.repoUrl) it.copy(enabled = next) else it
                                                                        },
                                                                    )
                                                                    val updated = repository.updateProfilePlugins(nextState)
                                                                    if (updated != null) bootstrap = updated
                                                                    status = if (updated != null) "${provider.name.ifBlank { "Plugin provider" }} updated." else "Plugin provider could not be updated."
                                                                    complete(updated != null)
                                                                }
                                                            }
                                                            if (repository.pluginProviderHasSettings(provider)) {
                                                                SettingsActionRow(
                                                                    "${provider.name.ifBlank { "This source" }} settings",
                                                                    "API keys and options this source asks for. Stored on this TV.",
                                                                    "Open",
                                                                    selectedRequester,
                                                                ) { editingPluginProvider = provider }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        if (unparentedProviders.isNotEmpty()) {
                                            val parentKey = "unparented:${section.name}"
                                            val expanded = parentKey in expandedPluginParents
                                            SettingsActionRow(
                                                "Other synced sources",
                                                "${unparentedProviders.size} source${if (unparentedProviders.size == 1) "" else "s"} without repository metadata",
                                                if (expanded) "Collapse" else "Expand",
                                                selectedRequester,
                                            ) {
                                                expandedPluginParents = if (expanded) expandedPluginParents - parentKey else expandedPluginParents + parentKey
                                            }
                                            if (expanded) {
                                                unparentedProviders.forEach { provider ->
                                                    key("provider:${provider.repoUrl}:${provider.id}") {
                                                        SettingsToggleRow(
                                                            provider.name.ifBlank { provider.id },
                                                            provider.types.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "Plugin provider",
                                                            provider.enabled,
                                                            selectedRequester,
                                                        ) { next, complete ->
                                                            scope.launch {
                                                                val nextState = pluginState.copy(
                                                                    providers = pluginState.providers.map {
                                                                        if (it.id == provider.id && it.repoUrl == provider.repoUrl) it.copy(enabled = next) else it
                                                                    },
                                                                )
                                                                val updated = repository.updateProfilePlugins(nextState)
                                                                if (updated != null) bootstrap = updated
                                                                status = if (updated != null) "${provider.name.ifBlank { "Plugin provider" }} updated." else "Plugin provider could not be updated."
                                                                complete(updated != null)
                                                            }
                                                        }
                                                        if (repository.pluginProviderHasSettings(provider)) {
                                                            SettingsActionRow(
                                                                "${provider.name.ifBlank { "This source" }} settings",
                                                                "API keys and options this source asks for. Stored on this TV.",
                                                                "Open",
                                                                selectedRequester,
                                                            ) { editingPluginProvider = provider }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // CloudStream (.cs3) collections, in their own panel rather than mixed in
                    // with the JavaScript ones above. They are a different runtime -- compiled
                    // Kotlin this box downloads and loads itself -- and switching one on is what
                    // fetches a multi-megabyte extension, so it is worth being clear which is which.
                    val cloudStream = pluginState?.cloudstream
                    if (cloudStream != null && cloudStream.repos.isNotEmpty()) {
                        SettingsPanel("CloudStream sources") {
                            InfoLine(
                                "Collections",
                                "${cloudStream.repos.size} · ${cloudStream.providers.count { it.enabled }} of ${cloudStream.providers.size} sources on",
                            )
                            cloudStream.repos.forEach { repo ->
                                val parentKey = "cs:${repo.url}"
                                val expanded = parentKey in expandedPluginParents
                                val sources = cloudStream.providers.filter { it.repoUrl == repo.url }
                                key(parentKey) {
                                    SettingsActionRow(
                                        repo.name.ifBlank { repo.url },
                                        "${sources.size} source${if (sources.size == 1) "" else "s"} · ${sources.count { it.enabled }} on",
                                        if (expanded) "Collapse" else "Expand",
                                        selectedRequester,
                                    ) {
                                        expandedPluginParents = if (expanded) expandedPluginParents - parentKey else expandedPluginParents + parentKey
                                    }
                                    if (expanded) {
                                        sources.forEach { source ->
                                            key("cs:${source.repoUrl}:${source.internalName}") {
                                                SettingsToggleRow(
                                                    source.name.ifBlank { source.internalName },
                                                    listOfNotNull(
                                                        source.tvTypes.takeIf { it.isNotEmpty() }?.joinToString(", "),
                                                        source.language,
                                                    ).joinToString(" · ").ifBlank { "CloudStream extension" },
                                                    source.enabled,
                                                    selectedRequester,
                                                ) { next, complete ->
                                                    scope.launch {
                                                        // Written back through the same document the
                                                        // phone and the portal read, so switching a
                                                        // source on here switches it on everywhere.
                                                        val nextState = (pluginState ?: ProfilePluginState()).copy(
                                                            cloudstream = cloudStream.copy(
                                                                providers = cloudStream.providers.map {
                                                                    if (it.repoUrl == source.repoUrl && it.internalName == source.internalName) {
                                                                        it.copy(enabled = next)
                                                                    } else {
                                                                        it
                                                                    }
                                                                },
                                                                updatedAt = System.currentTimeMillis(),
                                                            ),
                                                        )
                                                        val updated = repository.updateProfilePlugins(nextState)
                                                        if (updated != null) bootstrap = updated
                                                        status = when {
                                                            updated == null -> "That source could not be updated."
                                                            next -> "${source.name.ifBlank { "Source" }} on. It downloads the first time it is used."
                                                            else -> "${source.name.ifBlank { "Source" }} off."
                                                        }
                                                        complete(updated != null)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    SettingsPanel("IPTV playlists") {
                        if (playlists.isEmpty()) {
                            InfoLine("Playlists", "Add one from StreamDek Mobile or the web portal")
                        } else {
                            InfoLine("Enabled playlists", "${playlists.count { it.enabled }} of ${playlists.size}")
                            val collapsiblePlaylists = playlists.size > 2
                            if (collapsiblePlaylists) {
                                SettingsActionRow(
                                    "Synced playlists",
                                    "${playlists.size} on your account",
                                    if (playlistsExpanded) "Collapse" else "Expand",
                                    selectedRequester,
                                ) { playlistsExpanded = !playlistsExpanded }
                            }
                            if (!collapsiblePlaylists || playlistsExpanded) {
                                playlists.forEach { playlist ->
                                    key(playlist.id) {
                                        SettingsToggleRow(
                                            playlist.name,
                                            playlist.url.substringAfter("://").substringBefore('/'),
                                            playlist.enabled,
                                            selectedRequester,
                                        ) { next, complete ->
                                            scope.launch {
                                                status = "Updating ${playlist.name}..."
                                                val saved = repository.setPlaylistEnabled(playlist.id, next)
                                                if (saved) {
                                                    playlists = repository.fetchPlaylists(forceRefresh = true)
                                                    status = "${playlist.name} ${if (next) "on" else "off"}."
                                                } else {
                                                    status = "${playlist.name} could not be updated."
                                                }
                                                complete(saved)
                                            }
                                        }
                                    }
                                }
                                InfoLine("Adding and removing", "Use StreamDek Mobile or the web portal")
                            }
                        }
                    }
                }
                SettingsDestination.Appearance -> {
                    SettingsDropdownRow("Theme", "Change the visual colour system", appPrefs?.theme ?: "cinema-blue", themeOptions, themeColors) { value ->
                        savePreference("Theme") { repository.updateAppPreferences(mapOf("theme" to value)) }
                    }
                    SettingsDropdownRow("Animation speed", "GPU-friendly transitions for this device", appPrefs?.animationSpeed ?: "normal", listOf("normal" to "Normal", "fast" to "Fast", "slow" to "Slow")) { value ->
                        savePreference("Animation speed") { repository.updateAppPreferences(mapOf("animationSpeed" to value)) }
                    }
                    SettingsToggleRow("Background depth", "Subtle cinematic depth behind content", appPrefs?.backgroundBlur != false, selectedRequester) { next, complete ->
                        savePreference("Background depth", complete) { repository.updateAppPreferences(mapOf("backgroundBlur" to next)) }
                    }
                    SettingsToggleRow("Transparent navigation", "Let the backdrop show through the expanded navigation rail, up to 15%. Collapsed it is always transparent.", appPrefs?.transparentNavigation != false, selectedRequester) { next, complete ->
                        savePreference("Transparent navigation", complete) { repository.updateAppPreferences(mapOf("transparentNavigation" to next)) }
                    }
                }
                SettingsDestination.Accessibility -> {
                    SettingsToggleRow("High contrast", "Increase separation between controls", appPrefs?.highContrast == true, selectedRequester) { next, complete ->
                        savePreference("High contrast", complete) { repository.updateAppPreferences(mapOf("highContrast" to next)) }
                    }
                    SettingsToggleRow("Large text", "Increase primary interface text", appPrefs?.largeText == true, selectedRequester) { next, complete ->
                        savePreference("Large text", complete) { repository.updateAppPreferences(mapOf("largeText" to next)) }
                    }
                    SettingsToggleRow("Reduced motion", "Limit scaling and transitions", appPrefs?.reducedMotion == true, selectedRequester) { next, complete ->
                        savePreference("Reduced motion", complete) { repository.updateAppPreferences(mapOf("reducedMotion" to next)) }
                    }
                    // Sat alone under the old Advanced page. It is a comfort setting like the rest here.
                    SettingsToggleRow("Legacy compact mode", "Reduce spacing on older low-memory devices", appPrefs?.compactMode == true, selectedRequester) { next, complete ->
                        savePreference("Legacy compact mode", complete) { repository.updateAppPreferences(mapOf("compactMode" to next)) }
                    }
                    SettingsPanel("TV navigation") { InfoLine("Focus indicator", "Always visible"); InfoLine("Screen reader labels", "Enabled"); InfoLine("Colour-only status", "Never used") }
                }
                SettingsDestination.Connections -> {
                    val integrations = bootstrap?.integrations
                    SettingsDropdownRow(
                        "Primary library service",
                        "Choose which connected service supplies watchlist and continue watching",
                        SyncServiceId.normalize(homePrefs?.primarySyncService),
                        trackingOptions,
                    ) { value ->
                        savePreference("Primary library service") {
                            repository.updateHomePreferences(mapOf("primarySyncService" to value))
                        }
                    }
                    SettingsPanel("Cloud tracking") {
                        InfoLine("Primary", SyncServiceId.label(SyncServiceId.normalize(homePrefs?.primarySyncService)))
                        InfoLine("Trakt", serviceStatus(integrations?.trakt?.connected == true, integrations?.trakt?.username))
                        InfoLine("Simkl", serviceStatus(integrations?.simkl?.connected == true, integrations?.simkl?.username))
                        InfoLine("PunchPlay", serviceStatus(integrations?.punchplay?.connected == true, integrations?.punchplay?.username))
                        InfoLine("MDBList", serviceStatus(integrations?.mdblist?.connected == true, integrations?.mdblist?.username))
                        InfoLine("Connect services", "Use StreamDek Mobile for OAuth sign-in")
                    }
                    SettingsPanel("Sync status") { InfoLine("Settings", bootstrap?.syncStatus?.lastSettingsSyncAt ?: "Ready"); InfoLine("Cloud sync", onOff(bootstrap?.syncStatus?.cloudSyncEnabled != false)); InfoLine("Playback sync", onOff(bootstrap?.syncStatus?.playbackSyncEnabled != false)) }
                    bootstrap?.devices.orEmpty().take(6).forEach { device ->
                        SettingsPanel(device.name ?: "StreamDek device") { InfoLine("Platform", device.platform ?: device.deviceType ?: "Unknown"); InfoLine("Version", device.appVersion ?: "Unknown"); InfoLine("Status", if (device.isCurrent) "This TV" else device.lastSeenAt ?: "Connected") }
                    }
                    bootstrap?.sessions.orEmpty().take(6).forEach { activeSession ->
                        SettingsPanel(activeSession.clientName ?: "Active session") { InfoLine("Platform", activeSession.clientPlatform ?: "Unknown"); InfoLine("Device", activeSession.deviceId ?: "Not reported"); InfoLine("Status", if (activeSession.isCurrent) "Current session" else activeSession.lastSeenAt ?: "Active") }
                    }
                    SettingsActionRow("Refresh connections", "Update tracking services, devices and sync status", "Refresh", selectedRequester) { scope.launch { bootstrap = repository.refreshBootstrap(); status = "Connections refreshed." } }
                }
                SettingsDestination.About -> {
                    SettingsPanel("StreamDek TV") { InfoLine("Version", BuildConfig.VERSION_NAME); InfoLine("Client", "Android TV / Fire TV"); InfoLine("Profile", activeProfile?.name ?: "Not selected"); InfoLine("Update", updateState.statusText ?: updateState.errorMessage ?: "Ready") }
                    SettingsToggleRow("Automatic update checks", "Notify when a release is ready", updateState.autoCheckEnabled, selectedRequester) { next, complete ->
                        appUpdateManager.setAutoCheckEnabled(next)
                        complete(true)
                    }
                    SettingsActionRow("Check for updates", "Query the production TV update channel", "Check", selectedRequester) { scope.launch { appUpdateManager.checkForUpdates(showPromptOnAvailable = false, force = true) } }
                    updateState.availableRelease?.let { release -> SettingsActionRow("Install ${release.versionName}", release.requiredReason ?: "Download the available update", "Install", selectedRequester) { scope.launch { appUpdateManager.startUpdate() } } }
                    // The old Diagnostics page, folded in: two panels and one button did not earn a
                    // rail entry of their own, and this is where someone reporting a problem looks.
                    SettingsPanel("Health check") { InfoLine("Backend", reachability.name.lowercase().replaceFirstChar { it.uppercase() }); InfoLine("Authentication", if (session == null) "Guest mode" else "Healthy"); InfoLine("Sources", "${addons.count { it.enabled }} enabled"); InfoLine("Cache", formatBytes(directorySize(context.cacheDir))); InfoLine("Playback", playbackPrefs?.playerEngine ?: "Auto") }
                    SettingsActionRow("Run health check", "Refresh cloud, sources and connectivity", "Run", selectedRequester) { scope.launch { bootstrap = repository.refreshBootstrap(); addons = repository.fetchAddonManifests(); status = "Health checks refreshed." } }
                    SettingsPanel("Runtime") { InfoLine("Hardware acceleration", "Enabled"); InfoLine("Progressive loading", "Enabled"); InfoLine("Navigation", "Collapsible left rail") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    }

    editingPluginProvider?.let { provider ->
        PluginProviderSettingsDialog(
            provider = provider,
            repository = repository,
            onDismiss = { editingPluginProvider = null },
        )
    }

    debridKeyEntry?.let { (providerId, providerLabel) ->
        DebridApiKeyDialog(
            providerId = providerId,
            providerLabel = providerLabel,
            repository = repository,
            onConnected = { username ->
                debridKeyEntry = null
                bootstrap = repository.bootstrap.value
                status = "$providerLabel connected as $username."
            },
            onDismiss = { debridKeyEntry = null },
        )
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
private fun SettingsOverviewCard(
    destination: SettingsDestination,
    status: String?,
    requester: FocusRequester,
    leftRequester: FocusRequester,
    /**
     * A page-level action sitting at the right of the title.
     *
     * When one is given it takes over as the page's entry point: it is the topmost focusable thing
     * in the content column, so arriving from the destination list lands on it and one press down
     * reaches the settings. Without that the heading itself would take focus and bounce straight
     * past, leaving the action with nothing above it to be reached from.
     */
    action: (@Composable (FocusRequester) -> Unit)? = null,
) {
    val focusManager = LocalFocusManager.current
    var redirectFocus by remember { mutableStateOf(false) }
    LaunchedEffect(redirectFocus) {
        if (redirectFocus) {
            delay(1)
            focusManager.moveFocus(FocusDirection.Down)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            Modifier.weight(1f)
                .then(
                    if (action == null) {
                        Modifier.focusRequester(requester)
                            .onFocusChanged { redirectFocus = it.isFocused }
                            .onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && runCatching { leftRequester.requestFocus() }.isSuccess }
                            .focusable()
                    } else {
                        Modifier
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(destination.label, color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black))
            Text(destination.description, color = Color.White.copy(alpha = 0.64f), style = MaterialTheme.typography.bodyMedium)
            status?.let { SettingsStatusRow(it) }
        }
        action?.invoke(requester)
    }
}

/** The title-row form of an action: a pill, sized to itself rather than to the page. */
@Composable
private fun SettingsHeaderAction(
    label: String,
    requester: FocusRequester,
    leftRequester: FocusRequester,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .background(if (focused) Color(0xFF172131) else Color(0xB20E141D), androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) MaterialTheme.colorScheme.primary else Color(0x18FFFFFF),
                androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
            )
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && runCatching { leftRequester.requestFocus() }.isSuccess }
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.86f),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}

/** A provider id as viewers know it, falling back to the id when the build does not list it. */
private fun debridProviderLabel(providers: List<Pair<String, String>>, provider: String): String =
    providers.firstOrNull { it.first.equals(provider, true) }?.second ?: provider

@Composable
private fun SettingsStatusRow(message: String) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}

/** A device sign-in the viewer is part-way through, as this screen shows it. */
private data class DeviceSignIn(
    val providerLabel: String,
    val verificationUrl: String,
    val userCode: String,
    val waiting: Boolean = true,
    val outcome: String? = null,
)

/**
 * A device sign-in in progress, sized for the far side of a room.
 *
 * The code is read off this screen and typed on a phone, so it is the largest thing on it — no
 * copy button, because there is nothing on a television to paste into. Nothing here takes focus:
 * the viewer's hands are on another device, and a card that stole focus from the row they were on
 * would leave the remote pointing at something they cannot use.
 */
@Composable
private fun DeviceSignInPanel(
    providerLabel: String,
    verificationUrl: String,
    userCode: String,
    waiting: Boolean,
    outcome: String?,
) {
    SettingsPanel("Finish signing in to $providerLabel") {
        if (waiting) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "1. On your phone, open",
                        color = Color.White.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        verificationUrl.removePrefix("https://").removePrefix("http://"),
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "2. Enter this code",
                        color = Color.White.copy(alpha = 0.62f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        userCode,
                        color = MaterialTheme.colorScheme.primary,
                        // Spaced and oversized on purpose: this is read across a room and typed by
                        // hand somewhere else, where one mistaken character costs the whole attempt.
                        letterSpacing = 6.sp,
                        style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Black),
                    )
                }
                Text(
                    "Waiting for you to approve it…",
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            Text(
                outcome.orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun SettingsPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(TvChromePanel, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
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
private fun SettingsDropdownRow(
    title: String,
    description: String,
    value: String,
    options: List<Pair<String, String>>,
    optionColors: Map<String, Color> = emptyMap(),
    onSelect: (String) -> Unit,
) {
    val leftRequester = LocalSettingsLeftRequester.current
    val density = LocalDensity.current
    var focused by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var fieldWidthPx by remember { mutableIntStateOf(0) }
    val displayValue = options.firstOrNull { it.first.equals(value, true) }?.second ?: value
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (focused) Color(0xFF172131) else Color(0xB20E141D), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color(0x10FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .onFocusChanged { focused = it.isFocused }
                .onGloballyPositioned { fieldWidthPx = it.size.width }
                .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && !expanded && leftRequester?.let { runCatching { it.requestFocus() }.isSuccess } == true }
                .clickable { expanded = true }
                .padding(horizontal = 18.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                // Two lines while the row is passed over, all of them while it is the row being
                // read. Exactly one row holds focus on a television and it is the one the viewer is
                // looking at, so that is where the rest of the explanation belongs -- and it costs
                // no extra focus stop, which on a remote is the difference between a help affordance
                // and an obstacle.
                Text(description, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall, maxLines = if (focused) Int.MAX_VALUE else 2, overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                optionColors[value]?.let { color -> ThemeColorSwatch(color) }
                Text(displayValue, color = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Icon(Icons.Outlined.ArrowDropDown, null, tint = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.72f), modifier = Modifier.size(22.dp))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(0.dp, 6.dp),
            modifier = Modifier.width(with(density) { fieldWidthPx.toDp() }),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            containerColor = Color(0xFF111923),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
        ) {
            options.forEach { (optionValue, label) ->
                var optionFocused by remember(optionValue) { mutableStateOf(false) }
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            optionColors[optionValue]?.let { color -> ThemeColorSwatch(color) }
                            androidx.compose.material3.Text(label, color = if (optionFocused) Color.White else Color.White.copy(alpha = 0.86f), fontWeight = if (optionValue.equals(value, true)) FontWeight.Bold else FontWeight.Medium)
                        }
                    },
                    trailingIcon = {
                        if (optionValue.equals(value, true)) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    },
                    onClick = { expanded = false; onSelect(optionValue) },
                    modifier = Modifier.fillMaxWidth()
                        .onFocusChanged { optionFocused = it.isFocused }
                        .background(if (optionFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f) else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                )
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    leftRequester: FocusRequester,
    onToggle: (Boolean, (Boolean) -> Unit) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var visualChecked by remember { mutableStateOf(checked) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(checked) { if (!saving) visualChecked = checked }

    fun toggle() {
        if (saving) return
        val next = !visualChecked
        visualChecked = next
        saving = true
        onToggle(next) { succeeded ->
            if (!succeeded) visualChecked = checked
            saving = false
        }
    }

    Row(
        Modifier.fillMaxWidth()
            .background(if (focused) Color(0xFF172131) else Color(0xB20E141D), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color(0x10FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && runCatching { leftRequester.requestFocus() }.isSuccess }
            .clickable(onClick = ::toggle)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            Text(description, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall, maxLines = if (focused) Int.MAX_VALUE else 2, overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis)
        }
        Switch(
            checked = visualChecked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = Color.White.copy(alpha = 0.72f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.16f),
                uncheckedBorderColor = Color.White.copy(alpha = 0.22f),
            ),
        )
    }
}

@Composable
private fun ThemeColorSwatch(color: Color) {
    Box(
        Modifier.size(14.dp)
            .background(color, androidx.compose.foundation.shape.CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.24f), androidx.compose.foundation.shape.CircleShape),
    )
}

@Composable
private fun SettingsActionRow(title: String, description: String, value: String, leftRequester: FocusRequester, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().background(if (focused) Color(0xFF172131) else Color(0xB20E141D), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color(0x10FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent { it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && runCatching { leftRequester.requestFocus() }.isSuccess }.clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) { Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)); Text(description, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall, maxLines = if (focused) Int.MAX_VALUE else 2, overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis) }; Text(value, color = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)) }
}
/** What the trailer cache currently holds, and when it was last thrown away. */
private fun trailerCacheStatusLabel(sizeBytes: Long, lastClearedAt: Long): String {
    val size = if (sizeBytes <= 0L) "Nothing stored" else "${sizeBytes / 1024}KB stored"
    if (lastClearedAt <= 0L) return "$size · never cleared"
    val hoursAgo = ((System.currentTimeMillis() - lastClearedAt) / 3_600_000L).toInt()
    val cleared = when {
        hoursAgo <= 0 -> "cleared less than an hour ago"
        hoursAgo == 1 -> "cleared an hour ago"
        hoursAgo < 24 -> "cleared $hoursAgo hours ago"
        hoursAgo < 48 -> "cleared yesterday"
        else -> "cleared ${hoursAgo / 24} days ago"
    }
    return "$size · $cleared"
}

private fun onOff(value: Boolean) = if (value) "On" else "Off"
private fun serviceStatus(connected: Boolean, username: String?) = if (connected) username?.takeIf { it.isNotBlank() } ?: "Connected" else "Not connected"
private fun normalizePlayerEngine(value: String?): String = when (value?.trim()?.lowercase()) {
    "mpv" -> "MPV"
    "media3", "exo", "exoplayer" -> "ExoPlayer"
    else -> "Auto"
}
private fun normalizeDecoderMode(value: String?): String = when (value?.trim()?.lowercase()) {
    "hw", "hardware", "mediacodec-copy" -> "HW"
    "sw", "software", "none" -> "SW"
    else -> "HW+"
}
private fun normalizeRenderSurface(value: String?): String = when (value?.trim()?.lowercase()) {
    "compatibility", "texture", "textureview" -> "Compatibility"
    else -> "Standard"
}
private fun normalizePreferredQuality(value: String?): String = when (value?.trim()?.lowercase()) {
    "4k", "2160p", "uhd" -> "2160p"
    "1080p" -> "1080p"
    "720p" -> "720p"
    else -> "Auto"
}
/**
 * The stored language as one of the offered values.
 *
 * Used to fall back to English for anything outside a list of ten, which did not merely display
 * wrongly — the row wrote its displayed value back on the next save, so a viewer who chose Polish
 * on their phone had it silently replaced with English by opening this screen on the television.
 * Any language the ISO tables know is now kept as itself.
 */
private fun normalizeLanguage(value: String?, allowOff: Boolean = false): String {
    val raw = value?.trim()?.lowercase().orEmpty()
    if (allowOff && (raw == "off" || raw == Languages.NONE)) return "off"
    return Languages.normalize(raw).ifEmpty { "en" }
}

private fun languageOptions(includeOff: Boolean): List<Pair<String, String>> = buildList {
    if (includeOff) add("off" to "Off")
    // Every ISO language rather than a typed list of ten. The list is long, but the row is a
    // dropdown a viewer opens knowing what they are looking for, and the alternative is telling
    // most of the world their language is not available.
    addAll(Languages.all.map { it.code to it.label })
}
private fun directorySize(root: java.io.File) = runCatching { root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
private fun formatBytes(bytes: Long): String = when { bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0); bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0); bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0); else -> "$bytes B" }
