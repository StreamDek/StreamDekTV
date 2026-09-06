package com.streamdek.tv.nativeapp.ui.account

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Dns
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
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnKey
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
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamdek.tv.BuildConfig
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.data.APP_IDLE_CHOICES_MINUTES
import com.streamdek.tv.nativeapp.data.AccountBootstrap
import com.streamdek.tv.nativeapp.data.AddonManifest
import com.streamdek.tv.nativeapp.data.AppLanguage
import com.streamdek.tv.nativeapp.data.DefaultTrailerCacheClearHours
import com.streamdek.tv.nativeapp.data.DefaultTrailerDelaySeconds
import com.streamdek.tv.nativeapp.data.DoHSettings
import com.streamdek.tv.nativeapp.data.Languages
import com.streamdek.tv.nativeapp.data.MaxTrailerDelaySeconds
import com.streamdek.tv.nativeapp.data.PAUSED_SLEEP_CHOICES_MINUTES
import com.streamdek.tv.nativeapp.data.PlaybackCodecOptions
import com.streamdek.tv.nativeapp.data.ProfilePluginProvider
import com.streamdek.tv.nativeapp.data.ProfilePluginRepo
import com.streamdek.tv.nativeapp.data.ProfilePluginState
import com.streamdek.tv.nativeapp.data.RemotePlaylist
import com.streamdek.tv.nativeapp.data.StreamDekDoHProviders
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.data.SyncServiceId
import com.streamdek.tv.nativeapp.data.TrailerCache
import com.streamdek.tv.nativeapp.data.TrailerCacheClearChoices
import com.streamdek.tv.nativeapp.data.TvIdlePreferences
import com.streamdek.tv.nativeapp.data.clearTrailerState
import com.streamdek.tv.nativeapp.data.idleTimeoutLabel
import com.streamdek.tv.nativeapp.data.trailerCacheClearLabel
import com.streamdek.tv.nativeapp.ui.AnimationSpeed
import com.streamdek.tv.nativeapp.ui.AppFormats
import com.streamdek.tv.nativeapp.ui.LocalAppLanguage
import com.streamdek.tv.nativeapp.ui.LocalTvAnimationPreferences
import com.streamdek.tv.nativeapp.ui.LocalTvAppLanguagePreferences
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.MotionSettings
import com.streamdek.tv.nativeapp.ui.ProfileAvatarCircle
import com.streamdek.tv.nativeapp.ui.TvChromePanel
import com.streamdek.tv.nativeapp.ui.TvChromeSurface
import com.streamdek.tv.nativeapp.ui.appLanguageOptionDescriptions
import com.streamdek.tv.nativeapp.ui.appLanguageOptions
import com.streamdek.tv.nativeapp.ui.player.normalizeSubtitleDefaultSource
import com.streamdek.tv.nativeapp.ui.requestFocusOrFalse
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
/**
 * The Animation speed row's second line.
 *
 * When the device or the account has asked for reduced motion it overrules the selection outright,
 * and a row that carried on describing "Cinematic" while the app animated nothing would be lying.
 * The selection itself is left alone and still editable - turning reduced motion back off should
 * restore the choice already made - so the override is stated instead of applied to the value.
 */
@Composable
private fun animationSpeedDescription(motion: MotionSettings): String = stringResource(
    when {
        motion.overriddenBySystem -> R.string.settings_animation_reduced_motion_note
        else -> R.string.settings_animation_duration_note
    },
)

/**
 * The settings rail: one entry per page, grouped under a category heading.
 *
 * Category, name and description are resource ids rather than text. Holding the English here would
 * leave the whole rail in English on a translated television - which is exactly what it did, and
 * what a screenshot of the French build made obvious.
 *
 * [terms] is deliberately still English, and is *additional* to the label and description rather
 * than a replacement for them: the search matches all three, so a French viewer typing "langue"
 * finds this page through its translated description, while the English keywords keep working for
 * anyone who knows them. Translating the keyword lists as well would be a larger piece of work and
 * is not needed for search to work in the interface language.
 */
private enum class SettingsDestination(
    @StringRes val categoryRes: Int,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val terms: String,
    val icon: ImageVector,
) {
    Account(R.string.settings_category_account, R.string.settings_dest_account, R.string.settings_dest_account_description, "accounts profile pin sign in switch household", Icons.Outlined.AccountCircle),

    Appearance(R.string.settings_category_appearance, R.string.settings_appearance, R.string.settings_dest_appearance_description, "accent colour theme animation blur transparent navigation language interface translation", Icons.Outlined.Palette),
    Library(R.string.settings_category_appearance, R.string.settings_dest_home_screen, R.string.settings_dest_home_screen_description, "home catalogs rows poster landscape grid columns density start screen trailer trailers autoplay title page card titles hide titles overlay label", Icons.Outlined.VideoLibrary),
    LiveTv(R.string.settings_category_appearance, R.string.live_tv, R.string.settings_dest_live_tv_description, "live tv channel channels iptv category categories group landscape cards favourite favorite drawer progress bar", Icons.Outlined.LiveTv),
    Accessibility(R.string.settings_category_appearance, R.string.settings_dest_accessibility, R.string.settings_dest_accessibility_description, "vision screen reader high contrast large text compact", Icons.Outlined.Accessibility),

    Playback(R.string.settings_category_playback, R.string.settings_dest_player, R.string.settings_dest_player_description, "engine mpv media3 exoplayer decoder display surface audio language subtitles live progress", Icons.Outlined.PlayArrow),
    SkipAndAutoplay(R.string.settings_category_playback, R.string.settings_dest_skip_autoplay, R.string.settings_dest_skip_autoplay_description, "skip intro recap ending credits autoplay next episode binge threshold", Icons.Outlined.SkipNext),
    Streams(R.string.settings_category_playback, R.string.settings_dest_streams, R.string.settings_dest_streams_description, "quality resolution 4k 1080p file size picker source badges labels", Icons.Outlined.Tune),

    Sources(R.string.settings_category_sources, R.string.settings_dest_sources, R.string.settings_dest_sources_description, "providers addon plugin cloudstream debrid premium install playlist", Icons.Outlined.Extension),

    ContentServices(R.string.settings_category_connections, R.string.settings_dest_content_services, R.string.settings_dest_content_services_description, "content services tmdb mdblist theintrodb api key keys metadata artwork posters ratings timing intro recap credits outro enrichment own key personal key device only save to streamdek account credential", Icons.Outlined.VpnKey),
    Connections(R.string.settings_category_connections, R.string.settings_dest_sync_services, R.string.settings_dest_sync_services_description, "tracking trakt simkl mdblist sync devices television session cloud", Icons.Outlined.Sync),
    Network(R.string.settings_category_connections, R.string.settings_dest_network, R.string.settings_dest_network_description, "network dns doh dns over https privacy resolver", Icons.Outlined.Dns),

    About(R.string.settings_category_about, R.string.settings_dest_about, R.string.settings_dest_about_description, "release update version diagnostics health cache network runtime", Icons.Outlined.Info),
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
    // Account-saved keys arrive on the bootstrap, so this is already right by the time the
    // page is opened -- including on a television signed in a moment ago that has never been
    // told a key on its own.
    val contentServices by repository.contentServices.collectAsState()
    val reachability by repository.reachability.collectAsState()
    val updateState by appUpdateManager.uiState.collectAsState()
    var bootstrap by remember { mutableStateOf<AccountBootstrap?>(repository.bootstrap.value) }
    var addons by remember { mutableStateOf<List<AddonManifest>>(emptyList()) }
    /** The registry's row definitions, for the Home rows editor. Cached in the repository. */
    var catalogDefinitions by remember { mutableStateOf<List<com.streamdek.tv.nativeapp.data.CatalogDefinition>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<RemotePlaylist>>(emptyList()) }
    var selected by remember { mutableStateOf(SettingsDestination.Account) }
    // Focus owns the left-menu highlight immediately. The selected page follows only after the
    // remote settles: rebuilding a large settings panel in the same frame as every Up/Down press
    // made the highlight visibly hitch, especially while travelling upward through the list.
    var menuFocusedDestination by remember { mutableStateOf(SettingsDestination.Account) }
    // Device-local motion, from the app shell. Null only in a preview that has not provided it.
    val animationPreferences = LocalTvAnimationPreferences.current
    val languagePreferences = LocalTvAppLanguagePreferences.current
    val motionSettings = LocalTvExperienceSettings.current.motion
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    // The composition's own resources, which ProvideAppLocale has already wrapped in the chosen
    // language. Held here because the save status is written from a coroutine, which is not a
    // composition and cannot call stringResource.
    val settingsResources = LocalContext.current.resources
    // Sizes and counts on this screen are written with the interface language's own decimal mark and
    // grouping, not the device's.
    val appLanguage = LocalAppLanguage.current
    // What the trailer cache holds, refreshed after a clear so the row reports what just happened
    // rather than what it held when the screen opened. Keyed on the language too: the wording and
    // the number in it both change when the language does.
    var trailerCacheStatus by remember(appLanguage) {
        mutableStateOf(
            trailerCacheStatusLabel(
                settingsResources,
                appLanguage,
                TrailerCache.sizeBytes(context),
                TrailerCache.lastClearedAt(context),
            ),
        )
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
    val dohSettings = remember(context) { DoHSettings(context) }
    val idlePreferences = remember(context) { TvIdlePreferences(context) }
    var dohEnabled by remember { mutableStateOf(dohSettings.enabled) }
    var dohProviderId by remember { mutableStateOf(dohSettings.providerId) }
    var customDohEntry by remember { mutableStateOf(false) }
    var pausedTimeoutMinutes by remember { mutableIntStateOf(idlePreferences.pausedTimeoutMinutes) }
    var appIdleTimeoutMinutes by remember { mutableIntStateOf(idlePreferences.appIdleTimeoutMinutes) }
    val contentEntryRequester = remember { FocusRequester() }
    val destinationRequesters = remember(entryFocusRequester) {
        SettingsDestination.entries.associateWith { if (it == SettingsDestination.Account) entryFocusRequester else FocusRequester() }
    }
    val contentScroll = rememberScrollState()

    LaunchedEffect(Unit) {
        bootstrap = repository.refreshBootstrap()
        repository.applyContentServices(bootstrap)
        addons = repository.fetchAddonManifests()
        catalogDefinitions = runCatching { repository.fetchCatalogManifest() }.getOrDefault(emptyList())
        playlists = repository.fetchPlaylists()
        delay(120)
        runCatching { destinationRequesters.getValue(SettingsDestination.Account).requestFocus() }
    }
    LaunchedEffect(selected) { contentScroll.scrollTo(0) }
    LaunchedEffect(menuFocusedDestination) {
        delay(75)
        selected = menuFocusedDestination
    }
    LaunchedEffect(status) {
        val message = status ?: return@LaunchedEffect
        delay(2_500)
        if (status == message) status = null
    }

    // Matched against the *translated* name and description as well as the English keywords, so
    // somebody using a French television finds a page by typing French - see the note on
    // [SettingsDestination.terms].
    val destinationText = SettingsDestination.entries.associateWith {
        stringResource(it.labelRes) + " " + stringResource(it.descriptionRes) + " " + it.terms
    }
    val visible = SettingsDestination.entries.filter {
        query.isBlank() || destinationText.getValue(it).contains(query, true)
    }
    LaunchedEffect(visible) {
        if (visible.isNotEmpty() && selected !in visible) {
            menuFocusedDestination = visible.first()
            selected = visible.first()
        }
    }
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
        "streamdek" to stringResource(R.string.settings_opt_streamdek),
        "cinema-blue" to stringResource(R.string.settings_opt_cinema_blue),
        "carbon-gold" to stringResource(R.string.settings_opt_carbon_gold),
        "frost-neon" to stringResource(R.string.settings_opt_frost_neon),
        "ember-red" to stringResource(R.string.settings_opt_ember_red),
        "aurora-green" to stringResource(R.string.settings_opt_aurora_green),
        "violet-pulse" to stringResource(R.string.settings_opt_violet_pulse),
    )
    val themeColors = themeOptions.associate { (value, _) -> value to streamDekThemeAccent(value) }

    fun savePreference(
        @StringRes labelRes: Int,
        onComplete: (Boolean) -> Unit = {},
        update: suspend () -> AccountBootstrap?,
    ) {
        // Resolved from the resources the composition is already using, which ProvideAppLocale has
        // wrapped in the chosen language. The three lines are whole sentences with the setting's
        // name as an argument, not a name glued to a fragment.
        val label = settingsResources.getString(labelRes)
        scope.launch {
            status = settingsResources.getString(R.string.settings_saving_named, label)
            val updated = runCatching { update() }.getOrNull()
            if (updated == null) {
                status = settingsResources.getString(R.string.settings_save_failed_named, label)
            } else {
                bootstrap = updated
                status = settingsResources.getString(R.string.settings_saved_named, label)
            }
            onComplete(updated != null)
        }
    }

    val trackingOptions = buildList {
        val integrations = bootstrap?.integrations
        // SyncDek leads and is always offered. It has no account behind it, so unlike the others
        // it cannot be unavailable -- which also makes it the one safe answer on a fresh TV.
        add(SyncServiceId.SYNCDEK to settingsResources.getString(R.string.sync_syncdek_built_in))
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
            Text(stringResource(R.string.nav_settings), color = Color.White, style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black))
            Text(stringResource(R.string.settings_tagline), color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))
            SettingsSearchBox(query, { query = it }, navFocusRequester, contentEntryRequester)
            Column(
                Modifier.weight(1f).padding(top = 12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                visible.forEachIndexed { index, destination ->
                    if (index == 0 || visible[index - 1].categoryRes != destination.categoryRes) {
                        Text(
                            stringResource(destination.categoryRes),
                            color = Color.White.copy(alpha = 0.42f),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(start = 10.dp, top = if (index == 0) 2.dp else 10.dp, bottom = 2.dp),
                        )
                    }
                    SettingsDestinationRow(
                        destination = destination,
                        selected = selected == destination,
                        requester = destinationRequesters.getValue(destination),
                        navRequester = navFocusRequester,
                        contentRequester = contentEntryRequester,
                        onFocused = { menuFocusedDestination = destination },
                        onMoveUp = visible.getOrNull(index - 1)?.let { previous ->
                            {
                                destinationRequesters.getValue(previous).requestFocusOrFalse()
                            }
                        },
                        onMoveDown = visible.getOrNull(index + 1)?.let { next ->
                            {
                                destinationRequesters.getValue(next).requestFocusOrFalse()
                            }
                        },
                    )
                }
                if (visible.isEmpty()) Text(stringResource(R.string.settings_no_match), color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(14.dp))
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0x18FFFFFF)))
        Column(
            // Trailing room inside the scroll, not around it.
            //
            // A panel that ends in something the D-pad cannot land on -- an instruction line, a
            // closing note -- ended flush against the bottom edge. Focus stops at the last row it
            // can reach, the scroll stops with it, and whatever sits below is permanently half off
            // the screen with no way to bring it up. The padding is scrollable space, so the last
            // focusable row can travel clear of the edge and take the text under it into view.
            Modifier.weight(1f).fillMaxHeight().verticalScroll(contentScroll).padding(bottom = 96.dp),
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
                        SettingsHeaderAction(stringResource(R.string.settings_action_refresh_sources), requester, selectedRequester) {
                            scope.launch {
                                status = settingsResources.getString(R.string.settings_status_refreshing_sources)
                                bootstrap = repository.refreshBootstrap()
                                addons = repository.fetchAddonManifests(forceRefresh = true)
                                playlists = repository.fetchPlaylists(forceRefresh = true)
                                status = settingsResources.getString(R.string.settings_status_sources_refreshed)
                            }
                        }
                    }
                } else {
                    null
                },
            )
            when (selected) {
                SettingsDestination.Account -> {
                    SettingsPanel(stringResource(R.string.settings_tv_active_profile)) {
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
                                    Text(stringResource(if (activeProfile.isDefault) R.string.profile_default else R.string.profile_ready_to_watch), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        session?.user?.email.let { email ->
                            if (email.isNullOrBlank()) {
                                InfoLine(stringResource(R.string.info_email), stringResource(R.string.info_not_signed_in))
                            } else {
                                RevealableInfoLine(stringResource(R.string.info_email), email)
                            }
                        }
                        InfoLine(stringResource(R.string.info_subscription), session?.user?.subscriptionStatus ?: stringResource(R.string.subscription_free))
                    }
                    if (session == null) {
                        SettingsActionRow(stringResource(R.string.settings_tv_sign_in_or_link_this_tv), stringResource(R.string.settings_tv_sync_profiles_library_and_providers), stringResource(R.string.action_open), selectedRequester, onClick = onSignIn)
                    } else {
                        SettingsToggleRow(
                            stringResource(R.string.settings_tv_remember_last_profile_at_startup),
                            stringResource(R.string.settings_tv_skip_the_profile_picker_when_this_tv),
                            rememberLastProfileAtStartup,
                            selectedRequester,
                        ) { next, complete ->
                            repository.setRememberLastProfileAtStartup(next)
                            rememberLastProfileAtStartup = next
                            complete(true)
                        }
                        bootstrap?.streamProfiles.orEmpty().forEach { profile ->
                            SettingsActionRow(profile.name, if (profile.id == activeProfile?.id) stringResource(R.string.settings_tv_current_household_profile) else stringResource(R.string.settings_tv_switch_to_this_profile), if (profile.id == activeProfile?.id) stringResource(R.string.state_active) else stringResource(R.string.action_use), selectedRequester) {
                                scope.launch { repository.setActiveStreamProfile(profile.id); bootstrap = repository.refreshBootstrap(); status = settingsResources.getString(R.string.settings_status_using_profile, profile.name) }
                            }
                        }
                        SettingsActionRow(stringResource(R.string.settings_tv_sign_out), stringResource(R.string.settings_tv_remove_this_account_from_the_television), stringResource(R.string.action_sign_out), selectedRequester) {
                            repository.signOut(); bootstrap = null; addons = emptyList(); status = settingsResources.getString(R.string.settings_status_signed_out)
                        }
                    }
                }
                SettingsDestination.Playback -> {
                    SettingsPanel(stringResource(R.string.settings_tv_synced_tv_playback)) {
                        InfoLine(stringResource(R.string.info_cloud_scope), stringResource(R.string.info_changes_apply_on_this_tv_and_sync))
                    }
                    SettingsDropdownRow(stringResource(R.string.settings_tv_default_player), stringResource(R.string.settings_tv_auto_uses_media3_first_with_one_mpv), normalizePlayerEngine(playbackPrefs?.playerEngine), listOf(PlayerEngineValues.AUTO to stringResource(R.string.settings_opt_auto), PlayerEngineValues.EXOPLAYER to stringResource(R.string.settings_opt_media3_exoplayer), PlayerEngineValues.MPV to PlayerEngineValues.MPV)) { value ->
                        savePreference(R.string.settings_tv_default_player) { repository.updatePlaybackPreferences(mapOf("playerEngine" to value)) }
                    }
                    SettingsDropdownRow(stringResource(R.string.settings_tv_default_audio), stringResource(R.string.settings_tv_select_the_first_matching_audio_track), normalizeLanguage(playbackPrefs?.defaultAudioLanguage), languageOptions(includeOff = false)) { value ->
                        savePreference(R.string.settings_tv_default_audio) { repository.updatePlaybackPreferences(mapOf("defaultAudioLanguage" to value)) }
                    }
                    SettingsDropdownRow(stringResource(R.string.settings_tv_default_subtitles), stringResource(R.string.settings_tv_choose_a_preferred_subtitle_language_or_leave), normalizeLanguage(playbackPrefs?.defaultSubtitleLanguage, allowOff = true), languageOptions(includeOff = true)) { value ->
                        savePreference(R.string.settings_tv_default_subtitles) { repository.updatePlaybackPreferences(mapOf("defaultSubtitleLanguage" to value)) }
                    }
                    SettingsDropdownRow(stringResource(R.string.settings_tv_secondary_subtitles), stringResource(R.string.settings_tv_used_only_when_the_preferred_language_is), normalizeLanguage(playbackPrefs?.secondarySubtitleLanguage, allowOff = true), languageOptions(includeOff = true)) { value ->
                        savePreference(R.string.settings_tv_secondary_subtitles) { repository.updatePlaybackPreferences(mapOf("secondarySubtitleLanguage" to value)) }
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_auto_load_subtitles), stringResource(R.string.settings_tv_automatically_select_matching_subtitles_when_playback_starts), playbackPrefs?.autoLoadSubtitles != false, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_auto_load_subtitles, complete) { repository.updatePlaybackPreferences(mapOf("autoLoadSubtitles" to next)) }
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_show_only_preferred_language), stringResource(R.string.settings_tv_hide_embedded_and_add_on_subtitles_in), playbackPrefs?.showOnlyPreferredSubtitleLanguages == true, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_show_only_preferred_language, complete) { repository.updatePlaybackPreferences(mapOf("showOnlyPreferredSubtitleLanguages" to next)) }
                    }
                    SettingsDropdownRow(
                        stringResource(R.string.settings_tv_subtitle_sources),
                        stringResource(R.string.settings_tv_choose_which_subtitle_sources_the_player_searches),
                        normalizeSubtitleDefaultSource(playbackPrefs?.subtitleDefaultSource),
                        listOf(
                            SubtitleSourceValues.ALL to stringResource(R.string.settings_opt_all_sources),
                            SubtitleSourceValues.BUILT_IN to stringResource(R.string.settings_opt_built_in),
                            SubtitleSourceValues.ADDONS to stringResource(R.string.settings_opt_add_ons),
                        ),
                    ) { value ->
                        savePreference(R.string.settings_tv_preferred_subtitle_source) { repository.updatePlaybackPreferences(mapOf("subtitleDefaultSource" to value)) }
                    }
                    SettingsPanel(stringResource(R.string.settings_tv_sleep_idle)) {
                        InfoLine(stringResource(R.string.info_scope), stringResource(R.string.info_stored_on_this_tv_active_video_playback))
                    }
                    SettingsDropdownRow(
                        stringResource(R.string.settings_tv_sleep_when_paused),
                        stringResource(R.string.settings_tv_return_to_the_title_page_and_start),
                        pausedTimeoutMinutes.toString(),
                        PAUSED_SLEEP_CHOICES_MINUTES.map { it.toString() to idleTimeoutLabel(settingsResources, it) },
                    ) { value ->
                        pausedTimeoutMinutes = value.toInt()
                        idlePreferences.pausedTimeoutMinutes = pausedTimeoutMinutes
                        status = settingsResources.getString(R.string.settings_status_paused_sleep_set, idleTimeoutLabel(settingsResources, pausedTimeoutMinutes))
                    }
                    SettingsDropdownRow(
                        stringResource(R.string.settings_tv_app_idle_timeout),
                        stringResource(R.string.settings_tv_put_the_tv_to_sleep_after_remote),
                        appIdleTimeoutMinutes.toString(),
                        APP_IDLE_CHOICES_MINUTES.map { it.toString() to idleTimeoutLabel(settingsResources, it) },
                    ) { value ->
                        appIdleTimeoutMinutes = value.toInt()
                        idlePreferences.appIdleTimeoutMinutes = appIdleTimeoutMinutes
                        status = settingsResources.getString(R.string.settings_status_app_idle_set, idleTimeoutLabel(settingsResources, appIdleTimeoutMinutes))
                    }
                    // Last on the page: only worth opening when something will not play.
                    SettingsPanel(stringResource(R.string.settings_tv_if_a_video_will_not_play)) {
                        InfoLine(stringResource(R.string.info_when_these_apply), stringResource(R.string.info_used_when_mpv_is_selected_or_auto))
                    }
                    SettingsDropdownRow(stringResource(R.string.settings_tv_mpv_video_compatibility), stringResource(R.string.settings_tv_choose_hardware_acceleration_or_safe_software_decoding), normalizeDecoderMode(playbackPrefs?.decoderMode), listOf(DecoderModeValues.RECOMMENDED to stringResource(R.string.settings_opt_recommended_hw), DecoderModeValues.HARDWARE to stringResource(R.string.settings_opt_device_hw), DecoderModeValues.SOFTWARE to stringResource(R.string.settings_opt_safe_sw))) { value ->
                        savePreference(R.string.settings_tv_mpv_video_compatibility) { repository.updatePlaybackPreferences(mapOf("decoderMode" to value)) }
                    }
                    SettingsDropdownRow(stringResource(R.string.settings_tv_mpv_display), stringResource(R.string.settings_tv_compatibility_mode_uses_a_texture_backed_video), normalizeRenderSurface(playbackPrefs?.renderSurface), listOf(RenderSurfaceValues.STANDARD to stringResource(R.string.settings_opt_standard), RenderSurfaceValues.COMPATIBILITY to stringResource(R.string.settings_opt_compatibility))) { value ->
                        savePreference(R.string.settings_tv_mpv_display) { repository.updatePlaybackPreferences(mapOf("renderSurface" to value)) }
                    }
                }
                SettingsDestination.SkipAndAutoplay -> {
                    SettingsPanel(stringResource(R.string.settings_tv_auto_skip)) {
                        InfoLine(stringResource(R.string.info_detection), stringResource(R.string.info_only_reliable_introdb_segments_are_skipped_each))
                    }
                    // Each segment's own pair, together: the switch that offers the control, then
                    // the one that acts on it without being asked. They were in two separate blocks
                    // -- all three automatic ones, then all three manual ones -- so the setting that
                    // governs a row and the row it governs were six apart, and turning off "Skip
                    // intro" left an "Auto Skip Intro" switch further up still reading as on.
                    SettingsToggleRow(stringResource(R.string.settings_tv_skip_intro), stringResource(R.string.settings_tv_show_the_skip_control_when_an_intro), playbackPrefs?.isSegmentEnabled("intro") != false, selectedRequester) { next, complete ->
                        savePreference(R.string.player_skip_intro, complete) { repository.updatePlaybackPreferences(mapOf("skipIntroEnabled" to next)) }
                    }
                    // Only while the control it automates is switched on. An automatic skip of a
                    // segment the viewer has asked not to be offered is a setting that cannot do
                    // anything, and a switch that cannot do anything is worse than no switch.
                    if (playbackPrefs?.isSegmentEnabled("intro") != false) {
                        SettingsToggleRow(stringResource(R.string.settings_tv_auto_skip_intro), stringResource(R.string.settings_tv_skip_a_detected_intro_without_waiting_to), playbackPrefs?.autoSkipIntroEnabled == true, selectedRequester) { next, complete ->
                            savePreference(R.string.settings_tv_auto_skip_intro, complete) { repository.updatePlaybackPreferences(mapOf("autoSkipIntroEnabled" to next)) }
                        }
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_skip_recap), stringResource(R.string.settings_tv_show_the_skip_control_when_a_recap), playbackPrefs?.isSegmentEnabled("recap") != false, selectedRequester) { next, complete ->
                        savePreference(R.string.player_skip_recap, complete) { repository.updatePlaybackPreferences(mapOf("skipRecapEnabled" to next)) }
                    }
                    if (playbackPrefs?.isSegmentEnabled("recap") != false) {
                        SettingsToggleRow(stringResource(R.string.settings_tv_auto_skip_recap), stringResource(R.string.settings_tv_skip_a_detected_recap_without_waiting_to), playbackPrefs?.autoSkipRecapEnabled == true, selectedRequester) { next, complete ->
                            savePreference(R.string.settings_tv_auto_skip_recap, complete) { repository.updatePlaybackPreferences(mapOf("autoSkipRecapEnabled" to next)) }
                        }
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_skip_ending), stringResource(R.string.settings_tv_show_the_skip_control_when_an_ending), playbackPrefs?.isSegmentEnabled("outro") != false, selectedRequester) { next, complete ->
                        savePreference(R.string.player_skip_ending, complete) { repository.updatePlaybackPreferences(mapOf("skipEndingEnabled" to next)) }
                    }
                    if (playbackPrefs?.isSegmentEnabled("outro") != false) {
                        SettingsToggleRow(stringResource(R.string.settings_tv_auto_skip_ending), stringResource(R.string.settings_tv_skip_a_detected_ending_without_waiting_to), playbackPrefs?.autoSkipEndingEnabled == true, selectedRequester) { next, complete ->
                            savePreference(R.string.settings_tv_auto_skip_ending, complete) { repository.updatePlaybackPreferences(mapOf("autoSkipEndingEnabled" to next)) }
                        }
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_auto_play_next_episode), stringResource(R.string.settings_tv_start_the_next_episode_near_the_configured), playbackPrefs?.isAutoPlayNextEpisodeEnabled() != false, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_auto_play_next_episode, complete) { repository.updatePlaybackPreferences(mapOf("autoPlayNextEpisodeEnabled" to next)) }
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_keep_the_same_source), stringResource(R.string.settings_tv_prefer_the_current_provider_and_release_group), playbackPrefs?.preferBingeGroupNextEpisode != false, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_next_episode_source, complete) { repository.updatePlaybackPreferences(mapOf("preferBingeGroupNextEpisode" to next)) }
                    }
                    TimingProviderBrandPanel(
                        playbackPrefs?.timingProvider?.takeIf { it in setOf("introdb", "theintrodb") } ?: "introdb",
                    )
                    SettingsDropdownRow(
                        stringResource(R.string.settings_tv_preferred_timing_provider),
                        stringResource(R.string.settings_tv_choose_which_timing_service_streamdek_asks_first),
                        playbackPrefs?.timingProvider?.takeIf { it in setOf("introdb", "theintrodb") } ?: "introdb",
                        listOf("introdb" to stringResource(R.string.settings_opt_introdb), "theintrodb" to stringResource(R.string.settings_opt_theintrodb)),
                    ) { value ->
                        savePreference(R.string.settings_tv_timing_provider) { repository.updatePlaybackPreferences(mapOf("timingProvider" to value)) }
                    }
                    SettingsToggleRow(
                        stringResource(R.string.settings_tv_automatically_use_the_other_provider_when_needed),
                        stringResource(R.string.settings_tv_silently_improves_coverage_when_the_preferred_service),
                        playbackPrefs?.timingProviderFallbackEnabled != false,
                        selectedRequester,
                    ) { next, complete ->
                        savePreference(R.string.settings_tv_timing_provider_fallback, complete) { repository.updatePlaybackPreferences(mapOf("timingProviderFallbackEnabled" to next)) }
                    }
                    SettingsToggleRow(
                        stringResource(R.string.settings_tv_end_of_playback_recommendations),
                        stringResource(R.string.settings_tv_show_relevant_recommendations_as_you_approach_the),
                        playbackPrefs?.endOfPlaybackRecommendationsEnabled == true,
                        selectedRequester,
                    ) { next, complete ->
                        savePreference(R.string.settings_tv_end_of_playback_recommendations, complete) {
                            repository.updatePlaybackPreferences(mapOf("endOfPlaybackRecommendationsEnabled" to next))
                        }
                    }
                    if (playbackPrefs?.endOfPlaybackRecommendationsEnabled == true) {
                        SettingsDropdownRow(
                            stringResource(R.string.settings_tv_recommendation_timing),
                            stringResource(R.string.settings_tv_choose_how_early_the_adaptive_end_of),
                            playbackPrefs.recommendationTiming,
                            listOf("early" to stringResource(R.string.settings_opt_early), "standard" to stringResource(R.string.settings_opt_standard), "late" to stringResource(R.string.settings_opt_late)),
                        ) { value ->
                            savePreference(R.string.settings_tv_recommendation_timing) { repository.updatePlaybackPreferences(mapOf("recommendationTiming" to value)) }
                        }
                        SettingsDropdownRow(
                            stringResource(R.string.settings_tv_recommendations_shown),
                            stringResource(R.string.settings_tv_choose_how_many_suggestions_appear_in_the),
                            playbackPrefs.recommendationItemCount.coerceIn(1, 2).toString(),
                            listOf("1" to stringResource(R.string.settings_opt_1_recommendation), "2" to stringResource(R.string.settings_opt_2_recommendations)),
                        ) { value ->
                            savePreference(R.string.settings_tv_recommendations_shown) { repository.updatePlaybackPreferences(mapOf("recommendationItemCount" to value.toInt())) }
                        }
                    }
                }
                SettingsDestination.Streams -> {
                    SettingsDropdownRow(stringResource(R.string.settings_tv_preferred_quality), stringResource(R.string.settings_tv_rank_matching_streams_first_across_tv_and), normalizePreferredQuality(playbackPrefs?.preferredQuality), listOf(QualityValues.AUTO to stringResource(R.string.settings_opt_auto), QualityValues.UHD to stringResource(R.string.settings_opt_4k_2160p), QualityValues.FHD to QualityValues.FHD, QualityValues.HD to QualityValues.HD)) { value ->
                        savePreference(R.string.settings_tv_preferred_quality) { repository.updatePlaybackPreferences(mapOf("preferredQuality" to value)) }
                    }
                    SettingsDropdownRow(stringResource(R.string.settings_tv_maximum_file_size), stringResource(R.string.settings_tv_hide_larger_streams_when_size_metadata_is), playbackPrefs?.maxFileSizeGB ?: "0", listOf("0" to stringResource(R.string.settings_opt_unlimited), "2" to stringResource(R.string.settings_opt_2_gb), "5" to stringResource(R.string.settings_opt_5_gb), "10" to stringResource(R.string.settings_opt_10_gb), "20" to stringResource(R.string.settings_opt_20_gb))) { value ->
                        savePreference(R.string.settings_tv_maximum_file_size) { repository.updatePlaybackPreferences(mapOf("maxFileSizeGB" to value)) }
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_show_stream_picker), stringResource(R.string.settings_tv_choose_a_source_before_playback_instead_of), streamsPrefs?.showStreamsList != false, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_stream_picker, complete) { repository.updateStreamsPreferences(mapOf("showStreamsList" to next)) }
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_remember_last_source), stringResource(R.string.settings_tv_prefer_the_source_previously_used_for_the), streamsPrefs?.rememberLastSource != false, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_remember_last_source, complete) { repository.updateStreamsPreferences(mapOf("rememberLastSource" to next)) }
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_stream_detail_badges), stringResource(R.string.settings_tv_show_quality_source_codec_and_hdr_labels), streamsPrefs?.fusionBadgesEnabled != false, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_stream_detail_badges, complete) { repository.updateStreamsPreferences(mapOf("fusionBadgesEnabled" to next)) }
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_size_badges), stringResource(R.string.settings_tv_show_file_sizes_on_stream_choices), streamsPrefs?.showSizeBadges != false, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_size_badges, complete) { repository.updateStreamsPreferences(mapOf("showSizeBadges" to next)) }
                    }
                    SettingsToggleRow(
                        stringResource(R.string.settings_tv_streamdek_formatting),
                        stringResource(R.string.settings_tv_rebuild_add_on_results_into_streamdek_s),
                        streamsPrefs?.streamDekFormattingEnabled == true,
                        selectedRequester,
                    ) { next, complete ->
                        savePreference(R.string.settings_tv_streamdek_formatting, complete) {
                            repository.updateStreamsPreferences(mapOf("streamDekFormattingEnabled" to next))
                        }
                    }
                    // Decoder choices for this box, kept out of the synced profile: whether Dolby
                    // Vision needs mapping down, and whether tunneled output works, is a property
                    // of this hardware and not of the account. See PlaybackCodecOptions.
                    var dv7HevcFallback by remember { mutableStateOf(PlaybackCodecOptions.dv7HevcFallback) }
                    var tunneledPlayback by remember { mutableStateOf(PlaybackCodecOptions.tunneledPlayback) }
                    SettingsToggleRow(
                        stringResource(R.string.settings_tv_dv7_hevc_fallback),
                        stringResource(R.string.settings_tv_dolby_vision_profile_7_files_mostly_disc),
                        dv7HevcFallback,
                        selectedRequester,
                    ) { next, complete ->
                        PlaybackCodecOptions.setDv7HevcFallback(context, next)
                        dv7HevcFallback = next
                        complete(true)
                    }
                    SettingsToggleRow(
                        stringResource(R.string.settings_tv_tunneled_playback),
                        stringResource(R.string.settings_tv_hand_decoding_and_display_to_the_hardware),
                        tunneledPlayback,
                        selectedRequester,
                    ) { next, complete ->
                        PlaybackCodecOptions.setTunneledPlayback(context, next)
                        tunneledPlayback = next
                        complete(true)
                    }
                }
                SettingsDestination.Library -> {
                    SettingsToggleRow(stringResource(R.string.settings_tv_built_in_catalogs), stringResource(R.string.settings_tv_show_streamdek_s_default_movie_and_series), homePrefs?.defaultAppCatalogsEnabled != false, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_built_in_catalogs, complete) { repository.updateHomePreferences(mapOf("defaultAppCatalogsEnabled" to next)) }
                    }
                    // Under the built-in switch it qualifies, and above the presentation settings:
                    // which rows exist is a bigger decision than how their cards are drawn.
                    HomeRowsSettings(
                        definitions = catalogDefinitions,
                        addons = addons,
                        layout = homePrefs?.homeCatalogRows.orEmpty(),
                        streamDekRowsEnabled = homePrefs?.defaultAppCatalogsEnabled != false,
                        leftRequester = selectedRequester,
                    ) { rows, complete ->
                        savePreference(R.string.settings_home_rows, complete) {
                            repository.updateHomePreferences(mapOf("homeCatalogRows" to rows))
                        }
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_hide_home_synopsis), stringResource(R.string.settings_tv_drop_the_description_from_the_home_spotlight), appPrefs?.hideHomeSynopsis != false, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_hide_home_synopsis, complete) { repository.updateAppPreferences(mapOf("hideHomeSynopsis" to next)) }
                    }
                    SettingsDropdownRow(stringResource(R.string.settings_tv_home_row_cards), stringResource(R.string.settings_tv_use_landscape_or_portrait_artwork_on_the), appPrefs?.homeRowCardStyle ?: "landscape", listOf("landscape" to stringResource(R.string.settings_opt_landscape), "portrait" to stringResource(R.string.settings_opt_portrait))) { value ->
                        savePreference(R.string.settings_tv_home_row_cards) { repository.updateAppPreferences(mapOf("homeRowCardStyle" to value)) }
                    }
                    // Directly under the style it belongs to, and only when that style is portrait.
                    // A landscape still is often unidentifiable without its title, so the setting
                    // does nothing there -- and a switch that does nothing is worse than no switch.
                    if ((appPrefs?.homeRowCardStyle ?: "landscape") == "portrait") {
                        SettingsToggleRow(stringResource(R.string.settings_tv_hide_card_titles), stringResource(R.string.settings_tv_most_posters_already_carry_the_title_so), appPrefs?.hideHomeCardTitles == true, selectedRequester) { next, complete ->
                            savePreference(R.string.settings_tv_hide_card_titles, complete) { repository.updateAppPreferences(mapOf("hideHomeCardTitles" to next)) }
                        }
                    }
                    SettingsDropdownRow(
                        stringResource(R.string.settings_tv_streaming_network_cards),
                        stringResource(R.string.settings_tv_draw_the_streaming_networks_row_with_each),
                        if (NetworkCardStyleValues.CLASSIC.equals(homePrefs?.networkCardStyle, ignoreCase = true)) {
                            NetworkCardStyleValues.CLASSIC
                        } else {
                            NetworkCardStyleValues.BRANDED
                        },
                        listOf(
                            NetworkCardStyleValues.BRANDED to stringResource(R.string.settings_opt_branded_artwork),
                            NetworkCardStyleValues.CLASSIC to stringResource(R.string.settings_opt_logo_tile),
                        ),
                        optionDescriptions = mapOf(
                            NetworkCardStyleValues.BRANDED to stringResource(R.string.settings_opt_the_service_s_own_artwork_edge_to),
                            NetworkCardStyleValues.CLASSIC to stringResource(R.string.settings_opt_each_service_s_logo_on_a_white),
                        ),
                        optionPreview = { option -> NetworkCardStylePreview(branded = option != NetworkCardStyleValues.CLASSIC) },
                    ) { value ->
                        savePreference(R.string.settings_tv_streaming_network_cards) { repository.updateHomePreferences(mapOf("networkCardStyle" to value)) }
                    }
                    SettingsDropdownRow(stringResource(R.string.settings_tv_card_density), stringResource(R.string.settings_tv_comfortable_or_compact_browsing), appPrefs?.cardDensity ?: "comfortable", listOf("comfortable" to stringResource(R.string.settings_opt_comfortable), "compact" to stringResource(R.string.settings_opt_compact))) { value ->
                        savePreference(R.string.settings_tv_card_density) { repository.updateAppPreferences(mapOf("cardDensity" to value)) }
                    }
                    SettingsDropdownRow(stringResource(R.string.settings_tv_grid_columns), stringResource(R.string.settings_tv_balance_artwork_size_and_visible_items), (appPrefs?.gridSize ?: 5).toString(), (4..7).map { it.toString() to pluralStringResource(R.plurals.settings_tv_grid_columns_value, it, it) }) { value ->
                        savePreference(R.string.settings_tv_grid_columns) { repository.updateAppPreferences(mapOf("gridSize" to value.toInt())) }
                    }
                    // Sits with Home rather than Appearance: it picks which screen opens, not how it looks.
                    SettingsDropdownRow(stringResource(R.string.settings_tv_start_screen), stringResource(R.string.settings_tv_choose_where_streamdek_opens), appPrefs?.startScreen ?: "home", listOf("home" to stringResource(R.string.settings_opt_home), "library" to stringResource(R.string.settings_opt_library), "continue-watching" to stringResource(R.string.settings_opt_continue_watching))) { value ->
                        savePreference(R.string.settings_tv_start_screen) { repository.updateAppPreferences(mapOf("startScreen" to value)) }
                    }
                    SettingsPanel(stringResource(R.string.settings_tv_title_page)) {
                        SettingsToggleRow(
                            stringResource(R.string.settings_tv_play_trailers_automatically),
                            stringResource(R.string.settings_tv_when_a_title_page_opens_play_its),
                            detailPrefs?.heroTrailerAutoplay != false,
                            selectedRequester,
                        ) { next, complete ->
                            savePreference(R.string.settings_tv_play_trailers_automatically, complete) {
                                repository.updateDetailPreferences(mapOf("heroTrailerAutoplay" to next))
                            }
                        }
                        // How long the page has to itself before the trailer takes the screen.
                        // Nothing here affects the trailer button, which always plays at once:
                        // somebody who pressed it has already decided.
                        SettingsDropdownRow(
                            stringResource(R.string.settings_tv_trailer_start_delay),
                            stringResource(R.string.settings_tv_how_long_a_title_page_stays_put),
                            (detailPrefs?.heroTrailerDelaySeconds ?: DefaultTrailerDelaySeconds)
                                .coerceIn(0, MaxTrailerDelaySeconds).toString(),
                            (0..MaxTrailerDelaySeconds).map { seconds ->
                                seconds.toString() to if (seconds == 0) stringResource(R.string.settings_tv_immediately) else pluralStringResource(R.plurals.settings_tv_delay_seconds, seconds, seconds)
                            },
                        ) { value ->
                            savePreference(R.string.settings_tv_trailer_start_delay) {
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
                            stringResource(R.string.settings_tv_trailer_quality),
                            stringResource(R.string.settings_tv_the_best_picture_a_trailer_may_use),
                            (detailPrefs?.heroTrailerResolution ?: 2160).coerceIn(360, 2160).toString(),
                            listOf("360" to "360p", "720" to "720p", "1080" to "1080p", "2160" to "2160p"),
                        ) { value ->
                            savePreference(R.string.settings_tv_trailer_quality) {
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
                            stringResource(R.string.settings_tv_clear_trailer_cache),
                            stringResource(R.string.settings_tv_trailers_can_stop_playing_when_the_stored),
                            trailerClearHours.toString(),
                            // The synced value is folded in so a choice made on the phone that this
                            // build does not list still reads as an interval rather than a number.
                            (TrailerCacheClearChoices + (trailerClearHours to trailerCacheClearLabel(trailerClearHours)))
                                .distinctBy { it.first }
                                .sortedBy { it.first }
                                .map { (hours, label) -> hours.toString() to label },
                        ) { value ->
                            savePreference(R.string.settings_tv_trailer_cache_schedule) {
                                repository.updateDetailPreferences(
                                    mapOf(
                                        "trailerCacheClearHours" to
                                            (value.toIntOrNull() ?: DefaultTrailerCacheClearHours),
                                    ),
                                )
                            }
                        }
                        SettingsActionRow(
                            stringResource(R.string.settings_tv_clear_trailer_cache_now),
                            trailerCacheStatus,
                            stringResource(R.string.action_clear),
                            selectedRequester,
                        ) {
                            // Synchronous on the main thread by necessity: WebView and
                            // CookieManager, which hold the state that actually matters here,
                            // refuse to be touched from anywhere else.
                            val freed = clearTrailerState(context, "requested from settings")
                            trailerCacheStatus = trailerCacheStatusLabel(
                                settingsResources,
                                appLanguage,
                                TrailerCache.sizeBytes(context),
                                TrailerCache.lastClearedAt(context),
                            )
                            status = if (freed > 0) {
                                settingsResources.getString(
                                    R.string.settings_status_trailer_cache_cleared_freed,
                                    settingsResources.getString(R.string.unit_kilobytes, AppFormats.number(appLanguage, freed / 1024)),
                                )
                            } else {
                                settingsResources.getString(R.string.settings_status_trailer_cache_cleared)
                            }
                        }
                    }
                }
                SettingsDestination.LiveTv -> {
                    SettingsPanel(stringResource(R.string.settings_tv_channel_list)) {
                        InfoLine(stringResource(R.string.info_synced_with_mobile), stringResource(R.string.info_these_match_the_live_tv_page_in))
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_landscape_channel_cards), stringResource(R.string.settings_tv_show_channels_as_wide_cards_off_uses), homePrefs?.liveLandscapeCards != false, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_landscape_channel_cards, complete) { repository.updateHomePreferences(mapOf("liveLandscapeCards" to next)) }
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_group_channels_into_categories), stringResource(R.string.settings_tv_list_each_source_s_categories_in_the), homePrefs?.liveCategoriesEnabled != false, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_channel_categories, complete) { repository.updateHomePreferences(mapOf("liveCategoriesEnabled" to next)) }
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_card_style_favourites), stringResource(R.string.settings_tv_show_channel_artwork_in_the_player_s), homePrefs?.liveFavouriteDrawerCards == true, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_card_style_favourites, complete) { repository.updateHomePreferences(mapOf("liveFavouriteDrawerCards" to next)) }
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_live_progress_bar), stringResource(R.string.settings_tv_show_the_timeline_when_live_tv_or), playbackPrefs?.liveProgressBarEnabled == true, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_live_progress_bar, complete) { repository.updatePlaybackPreferences(mapOf("liveProgressBarEnabled" to next)) }
                    }
                    SettingsPanel(stringResource(R.string.settings_tv_where_channels_come_from)) {
                        InfoLine(
                            stringResource(R.string.info_add_ons),
                            stringResource(
                                R.string.settings_addons_enabled_listed_under_sources,
                                AppFormats.number(appLanguage, addons.count { it.enabled }),
                            ),
                        )
                        InfoLine(
                            stringResource(R.string.info_iptv_playlists),
                            if (playlists.isEmpty()) {
                                stringResource(R.string.settings_playlists_none_yet)
                            } else {
                                stringResource(
                                    R.string.settings_playlists_on_under_sources,
                                    AppFormats.number(appLanguage, playlists.count { it.enabled }),
                                    AppFormats.number(appLanguage, playlists.size),
                                )
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
                    SettingsPanel(stringResource(R.string.settings_tv_debrid_accounts)) {
                        val accounts = bootstrap?.integrations?.debrid?.accounts.orEmpty()
                        val debridProviders = repository.supportedDebridProviders()
                        val chosenLabel = debridProviderLabel(debridProviders, debridProviderChoice)
                        val usesSignIn = repository.debridProviderUsesDeviceSignIn(debridProviderChoice)
                        val alreadyLinked = accounts.any { it.provider.equals(debridProviderChoice, true) }
                        InfoLine(
                            stringResource(R.string.info_linked_services),
                            if (accounts.isEmpty()) {
                                stringResource(R.string.settings_debrid_none_yet)
                            } else {
                                accounts.joinToString(", ") { debridProviderLabel(debridProviders, it.provider) }
                            },
                        )
                        // Every supported service, not just the one with a sign-in button. Only
                        // Real-Debrid was ever offered here, so an account holder with TorBox or
                        // AllDebrid had to link it on another device to use it on this one.
                        SettingsDropdownRow(
                            stringResource(R.string.settings_tv_service),
                            stringResource(R.string.settings_tv_every_premium_service_streamdek_can_talk_to),
                            debridProviderChoice,
                            debridProviders,
                        ) { debridProviderChoice = it }
                        SettingsActionRow(
                            if (alreadyLinked) stringResource(R.string.settings_tv_reconnect_service, chosenLabel) else stringResource(R.string.settings_tv_connect_service, chosenLabel),
                            if (usesSignIn) {
                                stringResource(R.string.settings_tv_approve_short_code)
                            } else {
                                stringResource(R.string.settings_tv_enter_api_key_from, chosenLabel)
                            },
                            if (usesSignIn) stringResource(R.string.action_sign_in) else stringResource(R.string.action_enter_key),
                            selectedRequester,
                        ) {
                            when {
                                debridProviderChoice == "real-debrid" -> scope.launch {
                                    status = settingsResources.getString(R.string.settings_status_asking_for_code, "Real-Debrid")
                                    val started = repository.startRealDebridSignIn()
                                    if (started == null) {
                                        status = settingsResources.getString(R.string.settings_status_provider_unreachable, "Real-Debrid")
                                        return@launch
                                    }
                                    status = null
                                    signInPrompt = DeviceSignIn("Real-Debrid", started.verificationUrl, started.userCode)
                                    val username = repository.completeRealDebridSignIn(started)
                                    signInPrompt = signInPrompt?.copy(
                                        waiting = false,
                                        outcome = if (username != null) {
                                            bootstrap = repository.bootstrap.value
                                            settingsResources.getString(R.string.settings_status_connected_as, username)
                                        } else {
                                            settingsResources.getString(R.string.settings_status_code_expired)
                                        },
                                    )
                                }
                                debridProviderChoice == "premiumize" && usesSignIn -> scope.launch {
                                    status = settingsResources.getString(R.string.settings_status_asking_for_code, "Premiumize")
                                    val started = repository.startPremiumizeSignIn()
                                    if (started == null) {
                                        status = settingsResources.getString(R.string.settings_status_provider_unreachable, "Premiumize")
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
                                            settingsResources.getString(R.string.settings_status_connected_as, username)
                                        } else {
                                            settingsResources.getString(R.string.settings_status_code_expired)
                                        },
                                    )
                                }
                                else -> debridKeyEntry = debridProviderChoice to chosenLabel
                            }
                        }
                        SettingsToggleRow(
                            stringResource(R.string.settings_tv_save_keys_to_your_streamdek_account),
                            stringResource(R.string.settings_tv_keys_are_stored_encrypted_on_your_account),
                            repository.debridCloudSyncEnabled(),
                            selectedRequester,
                        ) { next, complete ->
                            scope.launch {
                                status = settingsResources.getString(
                                    if (next) R.string.settings_status_saving_keys_to_account else R.string.settings_status_moving_keys_to_tv,
                                )
                                val applied = repository.setDebridCloudSync(next)
                                bootstrap = repository.bootstrap.value
                                status = settingsResources.getString(
                                    when {
                                        applied && next -> R.string.settings_status_keys_on_account
                                        applied -> R.string.settings_status_keys_on_tv_only
                                        else -> R.string.settings_status_keys_copy_failed
                                    },
                                )
                                complete(applied)
                            }
                        }
                        accounts.forEach { account ->
                            key(account.provider) {
                                SettingsToggleRow(
                                    debridProviderLabel(debridProviders, account.provider),
                                    account.username ?: stringResource(R.string.settings_tv_linked_debrid_priority, account.priority + 1),
                                    account.enabled,
                                    selectedRequester,
                                ) { next, complete ->
                                    scope.launch {
                                        status = settingsResources.getString(R.string.settings_updating_named, account.provider)
                                        val saved = repository.setDebridAccountEnabled(account.provider, next)
                                        if (saved) {
                                            bootstrap = repository.bootstrap.value
                                            status = settingsResources.getString(R.string.settings_updated_named, account.provider)
                                        } else {
                                            status = settingsResources.getString(R.string.settings_update_failed_named, account.provider)
                                        }
                                        complete(saved)
                                    }
                                }
                            }
                        }
                    }
                    SettingsPanel(stringResource(R.string.settings_tv_synced_providers)) {
                        InfoLine(
                            stringResource(R.string.info_enabled_add_ons),
                            stringResource(
                                R.string.settings_count_of_total,
                                AppFormats.number(appLanguage, addons.count { it.enabled }),
                                AppFormats.number(appLanguage, addons.size),
                            ),
                        )
                        // Past three, this stops being a summary and becomes a list to scroll
                        // through — and it pushes plugins, playlists and everything below it off
                        // the screen for anyone who only came to check one of those. Long lists
                        // arrive closed, the way the plugin collections under this one do.
                        val collapsibleAddons = addons.size > 3
                        if (collapsibleAddons) {
                            SettingsActionRow(
                                stringResource(R.string.settings_tv_installed_add_ons),
                                pluralStringResource(R.plurals.settings_tv_synced_from_account, addons.size, addons.size),
                                if (addonsExpanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                                selectedRequester,
                            ) { addonsExpanded = !addonsExpanded }
                        }
                        if (!collapsibleAddons || addonsExpanded) {
                            addons.sortedWith(compareByDescending<AddonManifest> { it.favourite }.thenBy { it.position }).forEach { addon ->
                                key(addon.id) {
                                    SettingsToggleRow(
                                        addon.manifest.name.ifBlank { addon.id },
                                        stringResource(R.string.settings_tv_enable_or_disable_this_installed_add_on),
                                        addon.enabled,
                                        selectedRequester,
                                    ) { next, complete ->
                                        scope.launch {
                                            addons = addons.map { current ->
                                                if (current.id == addon.id) current.copy(enabled = next) else current
                                            }
                                            status = settingsResources.getString(R.string.settings_updating_named, addon.manifest.name.ifBlank { addon.id })
                                            val saved = repository.toggleAddon(addon.id, next)
                                            if (saved) {
                                                bootstrap = repository.bootstrap.value
                                                status = settingsResources.getString(R.string.settings_updated_named, addon.manifest.name.ifBlank { addon.id })
                                            } else {
                                                addons = addons.map { current ->
                                                    if (current.id == addon.id && current.enabled == next) current.copy(enabled = addon.enabled) else current
                                                }
                                                bootstrap = repository.bootstrap.value
                                                status = settingsResources.getString(
                                                    R.string.settings_update_failed_named,
                                                    settingsResources.getString(R.string.source_origin_addon),
                                                )
                                            }
                                            complete(saved)
                                        }
                                    }
                                    SettingsActionRow(
                                        stringResource(R.string.settings_tv_name_favourite, addon.manifest.name.ifBlank { addon.id }),
                                        stringResource(R.string.settings_tv_favourite_add_ons_are_searched_before_other),
                                        if (addon.favourite) stringResource(R.string.action_unfavourite) else stringResource(R.string.action_favourite),
                                        selectedRequester,
                                    ) {
                                        scope.launch {
                                            val saved = repository.setAddonFavourite(addon.id, !addon.favourite)
                                            if (saved) {
                                                bootstrap = repository.bootstrap.value
                                                addons = repository.fetchAddonManifests(forceRefresh = true)
                                            }
                                            val addonName = addon.manifest.name.ifBlank { settingsResources.getString(R.string.source_origin_addon) }
                                            status = settingsResources.getString(
                                                if (saved) R.string.settings_favourite_updated_named else R.string.settings_favourite_update_failed_named,
                                                addonName,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (addons.isEmpty()) InfoLine(stringResource(R.string.info_providers), stringResource(R.string.info_install_add_ons_from_streamdek_mobile))
                    }
                    SettingsPanel(stringResource(R.string.settings_tv_synced_plugins)) {
                        if (pluginState == null || (pluginState.repos.isEmpty() && pluginState.providers.isEmpty())) {
                            InfoLine(stringResource(R.string.info_plugins), stringResource(R.string.info_install_plugins_from_streamdek_mobile))
                        } else {
                            SettingsToggleRow(
                                stringResource(R.string.settings_tv_plugin_sources),
                                stringResource(R.string.settings_tv_enable_or_disable_every_synced_plugin_source),
                                pluginState.enabled,
                                selectedRequester,
                            ) { next, complete ->
                                scope.launch {
                                    val updated = repository.updateProfilePlugins(pluginState.copy(enabled = next))
                                    if (updated != null) bootstrap = updated
                                    status = settingsResources.getString(
                                        if (updated != null) R.string.settings_updated_named else R.string.settings_update_failed_named,
                                        settingsResources.getString(R.string.settings_tv_plugin_sources),
                                    )
                                    complete(updated != null)
                                }
                            }
                            val repoGroups = pluginState.repos
                                .sortedWith(compareByDescending<ProfilePluginRepo> { it.favourite }.thenBy { it.name.lowercase() })
                                .map { repo ->
                                val providers = pluginState.providers
                                    .filter { it.repoUrl == repo.url }
                                    .sortedBy { it.name.lowercase() }
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
                                    stringResource(R.string.settings_tv_plugin_collections),
                                    pluralStringResource(R.plurals.settings_tv_synced_from_account, collectionCount, collectionCount),
                                    if (pluginsExpanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                                    selectedRequester,
                                ) { pluginsExpanded = !pluginsExpanded }
                            }
                            if (!collapsiblePlugins || pluginsExpanded) {
                                listOf(
                                    PluginSourceSection.Regular to stringResource(R.string.settings_plugin_section_regular),
                                    PluginSourceSection.CloudStream to stringResource(R.string.settings_plugin_section_cloudstream),
                                    PluginSourceSection.SkyStream to stringResource(R.string.settings_plugin_section_skystream),
                                ).forEach { (section, title) ->
                                    val groups = repoGroups.filter { it.first == section }
                                    val knownRepoUrls = pluginState.repos.mapTo(hashSetOf()) { it.url }
                                    val unparentedProviders = pluginState.providers.filter {
                                        it.repoUrl !in knownRepoUrls && pluginSourceSection(ProfilePluginRepo(), listOf(it)) == section
                                    }.sortedBy { it.name.lowercase() }
                                    if (groups.isNotEmpty() || unparentedProviders.isNotEmpty()) {
                                        InfoLine(
                                            title,
                                            stringResource(
                                                R.string.settings_tv_dot_joined,
                                                pluralStringResource(R.plurals.settings_tv_collection_count, groups.size, groups.size),
                                                pluralStringResource(
                                                    R.plurals.settings_tv_source_count,
                                                    groups.sumOf { it.third.size } + unparentedProviders.size,
                                                    groups.sumOf { it.third.size } + unparentedProviders.size,
                                                ),
                                            ),
                                        )
                                        groups.forEach { (_, repo, providers) ->
                                            val parentKey = "repo:${repo.url}"
                                            val expanded = parentKey in expandedPluginParents
                                            key(parentKey) {
                                                SettingsActionRow(
                                                    repo.name.ifBlank { repo.url },
                                                    pluralStringResource(R.plurals.settings_tv_source_count, providers.size, providers.size).let { base -> repo.version.takeIf { it.isNotBlank() }?.let { stringResource(R.string.settings_tv_dot_joined, base, it) } ?: base },
                                                    if (expanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                                                    selectedRequester,
                                                ) {
                                                    expandedPluginParents = if (expanded) expandedPluginParents - parentKey else expandedPluginParents + parentKey
                                                }
                                                SettingsActionRow(
                                                    stringResource(R.string.settings_tv_name_favourite, repo.name.ifBlank { stringResource(R.string.settings_tv_plugin_collection) }),
                                                    stringResource(R.string.settings_tv_favourite_plugin_collections_are_searched_before_other),
                                                    if (repo.favourite) stringResource(R.string.action_unfavourite) else stringResource(R.string.action_favourite),
                                                    selectedRequester,
                                                ) {
                                                    scope.launch {
                                                        val nextState = pluginState.copy(
                                                            repos = pluginState.repos.map { if (it.url == repo.url) it.copy(favourite = !repo.favourite) else it },
                                                        )
                                                        val updated = repository.updateProfilePlugins(nextState)
                                                        if (updated != null) bootstrap = updated
                                                        status = settingsResources.getString(
                                                            if (updated != null) R.string.settings_favourite_updated_named else R.string.settings_favourite_update_failed_named,
                                                            repo.name.ifBlank { settingsResources.getString(R.string.settings_tv_plugin_collection) },
                                                        )
                                                    }
                                                }
                                                if (expanded) {
                                                    SettingsToggleRow(
                                                        stringResource(R.string.settings_tv_collection_enabled),
                                                        stringResource(R.string.settings_tv_enable_or_disable_named, repo.name.ifBlank { stringResource(R.string.settings_tv_this_plugin_collection) }),
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
                                                            status = settingsResources.getString(
                                                                if (updated != null) R.string.settings_updated_named else R.string.settings_update_failed_named,
                                                                repo.name.ifBlank { settingsResources.getString(R.string.settings_tv_plugin_collection) },
                                                            )
                                                            complete(updated != null)
                                                        }
                                                    }
                                                    providers.forEach { provider ->
                                                        key("provider:${provider.repoUrl}:${provider.id}") {
                                                            SettingsToggleRow(
                                                                provider.name.ifBlank { provider.id },
                                                                provider.types.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: stringResource(R.string.settings_tv_plugin_provider),
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
                                                                    status = settingsResources.getString(
                                                                        if (updated != null) R.string.settings_updated_named else R.string.settings_update_failed_named,
                                                                        provider.name.ifBlank { settingsResources.getString(R.string.settings_tv_plugin_provider) },
                                                                    )
                                                                    complete(updated != null)
                                                                }
                                                            }
                                                            if (repository.pluginProviderHasSettings(provider)) {
                                                                SettingsActionRow(
                                                                    stringResource(R.string.settings_tv_name_settings, provider.name.ifBlank { stringResource(R.string.settings_tv_this_source) }),
                                                                    stringResource(R.string.settings_tv_api_keys_and_options_this_source_asks),
                                                                    stringResource(R.string.action_open),
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
                                                stringResource(R.string.settings_tv_other_synced_sources),
                                                pluralStringResource(R.plurals.settings_tv_sources_without_metadata, unparentedProviders.size, unparentedProviders.size),
                                                if (expanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                                                selectedRequester,
                                            ) {
                                                expandedPluginParents = if (expanded) expandedPluginParents - parentKey else expandedPluginParents + parentKey
                                            }
                                            if (expanded) {
                                                unparentedProviders.forEach { provider ->
                                                    key("provider:${provider.repoUrl}:${provider.id}") {
                                                        SettingsToggleRow(
                                                            provider.name.ifBlank { provider.id },
                                                            provider.types.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: stringResource(R.string.settings_tv_plugin_provider),
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
                                                                status = settingsResources.getString(
                                                                    if (updated != null) R.string.settings_updated_named else R.string.settings_update_failed_named,
                                                                    provider.name.ifBlank { settingsResources.getString(R.string.settings_tv_plugin_provider) },
                                                                )
                                                                complete(updated != null)
                                                            }
                                                        }
                                                        if (repository.pluginProviderHasSettings(provider)) {
                                                            SettingsActionRow(
                                                                stringResource(R.string.settings_tv_name_settings, provider.name.ifBlank { stringResource(R.string.settings_tv_this_source) }),
                                                                stringResource(R.string.settings_tv_api_keys_and_options_this_source_asks),
                                                                stringResource(R.string.action_open),
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
                        SettingsPanel(stringResource(R.string.settings_tv_cloudstream_sources)) {
                            InfoLine(
                                stringResource(R.string.info_collections),
                                stringResource(
                                    R.string.settings_cloudstream_summary,
                                    AppFormats.number(appLanguage, cloudStream.repos.size),
                                    AppFormats.number(appLanguage, cloudStream.providers.count { it.enabled }),
                                    AppFormats.number(appLanguage, cloudStream.providers.size),
                                ),
                            )
                            cloudStream.repos.forEach { repo ->
                                val parentKey = "cs:${repo.url}"
                                val expanded = parentKey in expandedPluginParents
                                val sources = cloudStream.providers.filter { it.repoUrl == repo.url }
                                key(parentKey) {
                                    SettingsActionRow(
                                        repo.name.ifBlank { repo.url },
                                        pluralStringResource(R.plurals.settings_tv_sources_with_enabled, sources.size, sources.size, sources.count { it.enabled }),
                                        if (expanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
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
                                                    ).joinToString(" · ").ifBlank { stringResource(R.string.settings_tv_cloudstream_extension) },
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
                                                        val sourceName = source.name.ifBlank { settingsResources.getString(R.string.player_info_source) }
                                                        status = when {
                                                            updated == null ->
                                                                settingsResources.getString(R.string.settings_update_failed_named, sourceName)
                                                            next ->
                                                                settingsResources.getString(R.string.settings_status_source_on_downloads, sourceName)
                                                            else -> settingsResources.getString(R.string.settings_named_off, sourceName)
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
                    SettingsPanel(stringResource(R.string.settings_tv_iptv_playlists)) {
                        if (playlists.isEmpty()) {
                            InfoLine(stringResource(R.string.info_playlists), stringResource(R.string.info_add_one_from_streamdek_mobile_or_the))
                        } else {
                            InfoLine(
                                stringResource(R.string.info_enabled_playlists),
                                stringResource(
                                    R.string.settings_count_of_total,
                                    AppFormats.number(appLanguage, playlists.count { it.enabled }),
                                    AppFormats.number(appLanguage, playlists.size),
                                ),
                            )
                            val collapsiblePlaylists = playlists.size > 2
                            if (collapsiblePlaylists) {
                                SettingsActionRow(
                                    stringResource(R.string.settings_tv_synced_playlists),
                                    pluralStringResource(R.plurals.settings_tv_on_your_account, playlists.size, playlists.size),
                                    if (playlistsExpanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
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
                                                status = settingsResources.getString(R.string.settings_updating_named, playlist.name)
                                                val saved = repository.setPlaylistEnabled(playlist.id, next)
                                                if (saved) {
                                                    playlists = repository.fetchPlaylists(forceRefresh = true)
                                                    status = settingsResources.getString(
                                                        if (next) R.string.settings_named_on else R.string.settings_named_off,
                                                        playlist.name,
                                                    )
                                                } else {
                                                    status = settingsResources.getString(R.string.settings_update_failed_named, playlist.name)
                                                }
                                                complete(saved)
                                            }
                                        }
                                    }
                                }
                                InfoLine(stringResource(R.string.info_adding_and_removing), stringResource(R.string.info_use_streamdek_mobile_or_the_web_portal))
                            }
                        }
                    }
                }
                SettingsDestination.Appearance -> {
                    SettingsDropdownRow(stringResource(R.string.settings_tv_theme), stringResource(R.string.settings_tv_change_the_visual_colour_system), appPrefs?.theme ?: "cinema-blue", themeOptions, themeColors) { value ->
                        savePreference(R.string.settings_theme) { repository.updateAppPreferences(mapOf("theme" to value)) }
                    }
                    // Saved on this television and nowhere else, unlike every other row on this
                    // page: see AnimationSpeed.kt. There is nothing to save to the account and so
                    // nothing that can fail, which is why it does not go through savePreference.
                    SettingsDropdownRow(
                        title = stringResource(R.string.settings_animation_speed),
                        description = animationSpeedDescription(motionSettings),
                        value = motionSettings.speed.key,
                        options = AnimationSpeed.entries.map { it.key to stringResource(it.labelRes) },
                        optionDescriptions = AnimationSpeed.entries.associate { it.key to stringResource(it.descriptionRes) },
                    ) { value ->
                        animationPreferences?.select(AnimationSpeed.fromKey(value))
                    }
                    // Saved on this television and nowhere else, like Animation speed above and
                    // for the same reason: see AppLanguage.kt. There is nothing to save to the
                    // account and so nothing that can fail, which is why it does not go through
                    // savePreference. Choosing a language recomposes the tree in place - the rail
                    // keeps its focus and this row stays under the highlight, now reading in the
                    // language just chosen.
                    SettingsDropdownRow(
                        title = stringResource(R.string.settings_language),
                        description = stringResource(R.string.settings_language_description),
                        value = languagePreferences?.selection ?: AppLanguage.SystemSelection,
                        options = appLanguageOptions(),
                        optionDescriptions = appLanguageOptionDescriptions(),
                    ) { value ->
                        languagePreferences?.select(value)
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_background_depth), stringResource(R.string.settings_tv_subtle_cinematic_depth_behind_content), appPrefs?.backgroundBlur != false, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_background_depth, complete) { repository.updateAppPreferences(mapOf("backgroundBlur" to next)) }
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_transparent_navigation), stringResource(R.string.settings_tv_let_the_backdrop_show_through_the_expanded), appPrefs?.transparentNavigation != false, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_transparent_navigation, complete) { repository.updateAppPreferences(mapOf("transparentNavigation" to next)) }
                    }
                }
                SettingsDestination.Accessibility -> {
                    // Stated here as well as on Appearance: somebody turning this on should be able
                    // to see, from the row they are turning on, what it does to the rest of the app.
                    SettingsToggleRow(stringResource(R.string.settings_tv_reduced_motion), stringResource(R.string.settings_tv_limit_scaling_and_transitions_overrides_the_animation), appPrefs?.reducedMotion == true, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_reduced_motion, complete) { repository.updateAppPreferences(mapOf("reducedMotion" to next)) }
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_high_contrast), stringResource(R.string.settings_tv_increase_separation_between_controls), appPrefs?.highContrast == true, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_high_contrast, complete) { repository.updateAppPreferences(mapOf("highContrast" to next)) }
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_large_text), stringResource(R.string.settings_tv_increase_primary_interface_text), appPrefs?.largeText == true, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_large_text, complete) { repository.updateAppPreferences(mapOf("largeText" to next)) }
                    }
                    // Sat alone under the old Advanced page. It is a comfort setting like the rest here.
                    SettingsToggleRow(stringResource(R.string.settings_tv_legacy_compact_mode), stringResource(R.string.settings_tv_reduce_spacing_on_older_low_memory_devices), appPrefs?.compactMode == true, selectedRequester) { next, complete ->
                        savePreference(R.string.settings_tv_legacy_compact_mode, complete) { repository.updateAppPreferences(mapOf("compactMode" to next)) }
                    }
                    SettingsPanel(stringResource(R.string.settings_tv_tv_navigation)) { InfoLine(stringResource(R.string.info_focus_indicator), stringResource(R.string.info_always_visible)); InfoLine(stringResource(R.string.info_screen_reader_labels), stringResource(R.string.info_enabled)); InfoLine(stringResource(R.string.info_colour_only_status), stringResource(R.string.info_never_used)) }
                }
                SettingsDestination.ContentServices -> {
                    ContentServicesPanel(
                        state = contentServices,
                        repository = repository,
                        signedIn = session != null,
                        leftRequester = selectedRequester,
                        onStatus = { message -> status = message },
                    )
                }
                SettingsDestination.Connections -> {
                    val integrations = bootstrap?.integrations
                    // A pointer rather than a second copy of the page: the keys are enrichment
                    // credentials, not a tracking connection, but this is where someone looking for
                    // "the MDBList thing" will start.
                    SettingsActionRow(
                        stringResource(R.string.settings_tv_content_services),
                        stringResource(R.string.settings_tv_your_own_tmdb_and_mdblist_keys_and),
                        contentServicesSummary(contentServices),
                        selectedRequester,
                    ) { selected = SettingsDestination.ContentServices }
                    SettingsDropdownRow(
                        stringResource(R.string.settings_tv_where_your_sync_lives),
                        stringResource(R.string.settings_tv_one_source_supplies_continue_watching_and_your),
                        SyncServiceId.normalize(homePrefs?.primarySyncService),
                        trackingOptions,
                    ) { value ->
                        savePreference(R.string.settings_tv_primary_library_service) {
                            repository.updateHomePreferences(mapOf("primarySyncService" to value))
                        }
                    }
                    SettingsPanel(stringResource(R.string.settings_tv_cloud_tracking)) {
                        InfoLine(stringResource(R.string.info_in_use), SyncServiceId.label(SyncServiceId.normalize(homePrefs?.primarySyncService)))
                        InfoLine(stringResource(R.string.info_syncdek), stringResource(R.string.info_always_on_keeps_your_place_and_watchlist))
                        InfoLine(stringResource(R.string.info_trakt), serviceStatus(settingsResources, integrations?.trakt?.connected == true, integrations?.trakt?.username))
                        InfoLine(stringResource(R.string.info_simkl), serviceStatus(settingsResources, integrations?.simkl?.connected == true, integrations?.simkl?.username))
                        InfoLine(stringResource(R.string.info_punchplay), serviceStatus(settingsResources, integrations?.punchplay?.connected == true, integrations?.punchplay?.username))
                        InfoLine(stringResource(R.string.info_mdblist), serviceStatus(settingsResources, integrations?.mdblist?.connected == true, integrations?.mdblist?.username))
                        InfoLine(stringResource(R.string.info_connect_services), stringResource(R.string.info_use_streamdek_mobile_for_oauth_sign_in))
                    }
                    SettingsPanel(stringResource(R.string.settings_tv_sync_status)) { InfoLine(stringResource(R.string.info_settings), bootstrap?.syncStatus?.lastSettingsSyncAt ?: stringResource(R.string.state_ready)); InfoLine(stringResource(R.string.info_cloud_sync), onOff(settingsResources, bootstrap?.syncStatus?.cloudSyncEnabled != false)); InfoLine(stringResource(R.string.info_playback_sync), onOff(settingsResources, bootstrap?.syncStatus?.playbackSyncEnabled != false)) }
                    bootstrap?.devices.orEmpty().take(6).forEach { device ->
                        SettingsPanel(device.name ?: stringResource(R.string.device_streamdek_device)) { InfoLine(stringResource(R.string.info_platform), device.platform ?: device.deviceType ?: stringResource(R.string.state_unknown)); InfoLine(stringResource(R.string.info_version), device.appVersion ?: stringResource(R.string.state_unknown)); InfoLine(stringResource(R.string.info_status), if (device.isCurrent) stringResource(R.string.device_this_tv) else device.lastSeenAt ?: stringResource(R.string.settings_connected)) }
                    }
                    bootstrap?.sessions.orEmpty().take(6).forEach { activeSession ->
                        SettingsPanel(activeSession.clientName ?: stringResource(R.string.session_active_session)) { InfoLine(stringResource(R.string.info_platform), activeSession.clientPlatform ?: stringResource(R.string.state_unknown)); InfoLine(stringResource(R.string.info_device), activeSession.deviceId ?: stringResource(R.string.state_not_reported)); InfoLine(stringResource(R.string.info_status), if (activeSession.isCurrent) stringResource(R.string.session_current_session) else activeSession.lastSeenAt ?: stringResource(R.string.state_active)) }
                    }
                    SettingsActionRow(stringResource(R.string.settings_tv_refresh_connections), stringResource(R.string.settings_tv_update_tracking_services_devices_and_sync_status), stringResource(R.string.action_refresh), selectedRequester) { scope.launch { bootstrap = repository.refreshBootstrap(); status = settingsResources.getString(R.string.settings_status_connections_refreshed) } }
                }
                SettingsDestination.Network -> {
                    SettingsPanel(stringResource(R.string.settings_tv_dns_privacy)) {
                        InfoLine(stringResource(R.string.info_scope), stringResource(R.string.info_encrypts_dns_for_streamdek_api_and_app))
                        InfoLine(stringResource(R.string.info_not_a_vpn), stringResource(R.string.info_video_traffic_is_not_tunnelled_through_the))
                    }
                    SettingsToggleRow(stringResource(R.string.settings_tv_dns_over_https), stringResource(R.string.settings_tv_resolve_streamdek_hostnames_through_an_encrypted_https), dohEnabled, selectedRequester) { next, complete ->
                        if (next && dohSettings.endpoint() == null) {
                            status = settingsResources.getString(R.string.settings_status_choose_doh_first)
                            complete(false)
                        } else {
                            dohSettings.enabled = next
                            dohEnabled = next
                            status = settingsResources.getString(
                                if (next) R.string.settings_status_doh_enabled else R.string.settings_status_doh_disabled,
                            )
                            complete(true)
                        }
                    }
                    if (dohEnabled) {
                        SettingsDropdownRow(
                            stringResource(R.string.settings_tv_doh_provider),
                            stringResource(R.string.settings_tv_use_the_provider_s_official_rfc_8484),
                            dohProviderId,
                            StreamDekDoHProviders.map { it.id to (it.labelRes?.let(settingsResources::getString) ?: it.label) },
                            optionDescriptions = StreamDekDoHProviders.associate { provider ->
                                provider.id to (provider.endpoint ?: dohSettings.customEndpoint.ifBlank { stringResource(R.string.settings_tv_not_configured) })
                            },
                        ) { value ->
                            if (value == "custom") {
                                customDohEntry = true
                            } else {
                                dohSettings.providerId = value
                                dohProviderId = value
                                status = settingsResources.getString(
                                    R.string.settings_status_dns_provider_changed,
                                    StreamDekDoHProviders.first { it.id == value }
                                        .let { provider -> provider.labelRes?.let(settingsResources::getString) ?: provider.label },
                                )
                            }
                        }
                        if (dohProviderId == "custom") {
                            SettingsActionRow(stringResource(R.string.settings_tv_custom_doh_endpoint), dohSettings.customEndpoint.ifBlank { stringResource(R.string.settings_tv_enter_https_endpoint) }, stringResource(R.string.action_edit), selectedRequester) {
                                customDohEntry = true
                            }
                        }
                        SettingsPanel(stringResource(R.string.settings_tv_platform_coverage)) {
                            InfoLine(stringResource(R.string.info_protected), stringResource(R.string.info_streamdek_api_calls_made_through_the_shared))
                            InfoLine(stringResource(R.string.info_system_owned), stringResource(R.string.info_media3_mpv_and_third_party_extensions_may))
                            InfoLine(stringResource(R.string.info_failure_policy), stringResource(R.string.info_no_silent_system_dns_fallback_while_doh))
                        }
                    }
                }
                SettingsDestination.About -> {
                    SettingsPanel(stringResource(R.string.settings_tv_streamdek_tv)) { InfoLine(stringResource(R.string.info_version), BuildConfig.VERSION_NAME); InfoLine(stringResource(R.string.info_client), stringResource(R.string.info_android_tv_fire_tv)); InfoLine(stringResource(R.string.info_profile), activeProfile?.name ?: stringResource(R.string.state_not_selected)); InfoLine(stringResource(R.string.info_update), updateState.statusText ?: updateState.errorMessage ?: stringResource(R.string.state_ready)) }
                    SettingsToggleRow(stringResource(R.string.settings_tv_automatic_update_checks), stringResource(R.string.settings_tv_notify_when_a_release_is_ready), updateState.autoCheckEnabled, selectedRequester) { next, complete ->
                        appUpdateManager.setAutoCheckEnabled(next)
                        complete(true)
                    }
                    SettingsActionRow(stringResource(R.string.settings_tv_check_for_updates), stringResource(R.string.settings_tv_query_the_production_tv_update_channel), stringResource(R.string.action_check), selectedRequester) { scope.launch { appUpdateManager.checkForUpdates(showPromptOnAvailable = false, force = true) } }
                    updateState.availableRelease?.let { release -> SettingsActionRow(stringResource(R.string.settings_tv_install_version, release.versionName), release.requiredReason ?: stringResource(R.string.settings_tv_download_available_update), stringResource(R.string.action_install), selectedRequester) { scope.launch { appUpdateManager.startUpdate() } } }
                    // The old Diagnostics page, folded in: two panels and one button did not earn a
                    // rail entry of their own, and this is where someone reporting a problem looks.
                    SettingsPanel(stringResource(R.string.settings_tv_health_check)) { InfoLine(stringResource(R.string.info_backend), reachability.name.lowercase().replaceFirstChar { it.uppercase() }); InfoLine(stringResource(R.string.info_authentication), stringResource(if (session == null) R.string.auth_guest_mode else R.string.health_healthy)); InfoLine(stringResource(R.string.info_sources), stringResource(R.string.settings_addons_enabled_count, AppFormats.number(appLanguage, addons.count { it.enabled }))); InfoLine(stringResource(R.string.info_cache), formatBytes(settingsResources, appLanguage, directorySize(context.cacheDir))); InfoLine(stringResource(R.string.info_playback), playbackPrefs?.playerEngine ?: PlayerEngineValues.AUTO) }
                    SettingsActionRow(stringResource(R.string.settings_tv_run_health_check), stringResource(R.string.settings_tv_refresh_cloud_sources_and_connectivity), stringResource(R.string.action_run), selectedRequester) { scope.launch { bootstrap = repository.refreshBootstrap(); addons = repository.fetchAddonManifests(); status = settingsResources.getString(R.string.settings_status_health_checks_refreshed) } }
                    SettingsPanel(stringResource(R.string.settings_tv_runtime)) { InfoLine(stringResource(R.string.info_hardware_acceleration), stringResource(R.string.info_enabled)); InfoLine(stringResource(R.string.info_progressive_loading), stringResource(R.string.info_enabled)); InfoLine(stringResource(R.string.info_navigation), stringResource(R.string.info_collapsible_left_rail)) }
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
                status = settingsResources.getString(R.string.settings_status_provider_connected_as, providerLabel, username)
            },
            onDismiss = { debridKeyEntry = null },
        )
    }
    if (customDohEntry) {
        CustomDoHEndpointDialog(
            initialValue = dohSettings.customEndpoint,
            onSave = { endpoint ->
                dohSettings.customEndpoint = endpoint
                dohSettings.providerId = "custom"
                dohProviderId = "custom"
                customDohEntry = false
                status = settingsResources.getString(R.string.settings_status_custom_doh_saved)
            },
            onDismiss = { customDohEntry = false },
        )
    }
}

@Composable
private fun TimingProviderBrandPanel(preferred: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.settings_timing_services),
            color = Color.White.copy(alpha = 0.76f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TimingProviderBrandTile(
                modifier = Modifier.weight(1f),
                logo = R.drawable.introdb_logo,
                name = "IntroDB",
                coverage = stringResource(R.string.home_row_kind_series),
                selected = preferred != "theintrodb",
            )
            TimingProviderBrandTile(
                modifier = Modifier.weight(1f),
                logo = R.drawable.theintrodb_logo,
                name = "TheIntroDB",
                coverage = stringResource(R.string.timing_coverage_movies_and_series),
                selected = preferred == "theintrodb",
            )
        }
        Text(
            stringResource(R.string.settings_timing_fallback_note),
            color = Color.White.copy(alpha = 0.60f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TimingProviderBrandTile(
    modifier: Modifier,
    logo: Int,
    name: String,
    coverage: String,
    selected: Boolean,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .background(Color.White.copy(alpha = if (selected) 0.09f else 0.045f), shape)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.52f) else Color.White.copy(alpha = 0.10f),
                shape,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(36.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Image(
                painter = painterResource(logo),
                contentDescription = name,
                modifier = Modifier.width(128.dp).height(30.dp),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
            )
        }
        Text(name, color = Color.White.copy(alpha = 0.94f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            if (selected) stringResource(R.string.timing_coverage_preferred, coverage) else coverage,
            color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.58f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
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
        // Left is left alone: the shell answers a Left that nothing in the page consumed by opening
        // the navigation menu, which is the one place that transition lives now.
        if (event.type != KeyEventType.KeyDown) false else when (event.key) { Key.DirectionRight -> runCatching { contentRequester.requestFocus() }.isSuccess; else -> false }
    }
    if (editing) {
        OutlinedTextField(
            value = query, onValueChange = onQueryChange, singleLine = true,
            label = { androidx.compose.material3.Text(stringResource(R.string.settings_find)) }, shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xFF121722), unfocusedContainerColor = Color(0xFF0E121A), focusedIndicatorColor = MaterialTheme.colorScheme.primary, unfocusedIndicatorColor = Color(0x18FFFFFF), focusedTextColor = Color.White, unfocusedTextColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(50.dp).focusRequester(editorRequester).onFocusChanged { if (it.isFocused) editorWasFocused = true else if (editorWasFocused) { editorWasFocused = false; editing = false } }.onPreviewKeyEvent(keyHandler),
        )
    } else {
        Row(
            Modifier.fillMaxWidth().height(46.dp).background(if (focused) Color(0xFF151C28) else Color(0xFF0D1118), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color(0x18FFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).focusRequester(launcherRequester).onFocusChanged { focused = it.isFocused }.onPreviewKeyEvent(keyHandler).clickable { editing = true }.padding(horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) { Icon(Icons.Outlined.Search, null, tint = Color.White.copy(alpha = 0.62f), modifier = Modifier.size(18.dp)); Text(query.ifBlank { stringResource(R.string.settings_find) }, color = Color.White.copy(alpha = if (query.isBlank()) 0.58f else 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun SettingsDestinationRow(
    destination: SettingsDestination,
    selected: Boolean,
    requester: FocusRequester,
    navRequester: FocusRequester,
    contentRequester: FocusRequester,
    onFocused: () -> Unit,
    onMoveUp: (() -> Boolean)?,
    onMoveDown: (() -> Boolean)?,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(42.dp).background(when { focused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.22f); selected -> Color(0x16FFFFFF); else -> Color.Transparent }, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).border(if (focused) 2.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).focusRequester(requester).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }.onPreviewKeyEvent { e -> if (e.type != KeyEventType.KeyDown) false else when (e.key) { Key.DirectionUp -> onMoveUp?.invoke() ?: false; Key.DirectionDown -> onMoveDown?.invoke() ?: false; Key.DirectionRight -> runCatching { contentRequester.requestFocus() }.isSuccess; else -> false } }.clickable(onClick = onFocused).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) { Icon(destination.icon, null, tint = if (focused || selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.55f), modifier = Modifier.size(19.dp)); Text(stringResource(destination.labelRes), color = Color.White.copy(alpha = if (focused || selected) 0.96f else 0.66f), style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium)) }
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
            Text(stringResource(destination.labelRes), color = Color.White, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black))
            Text(stringResource(destination.descriptionRes), color = Color.White.copy(alpha = 0.64f), style = MaterialTheme.typography.bodyMedium)
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
    SettingsPanel(stringResource(R.string.settings_finish_signing_in_to, providerLabel)) {
        if (waiting) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stringResource(R.string.pairing_step_open_app),
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
                        stringResource(R.string.pairing_step_enter_code),
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
                    stringResource(R.string.pairing_waiting),
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
/**
 * An address with everything but its first character taken out.
 *
 * A television is the one screen someone else is always looking at, and the account email sits on a
 * settings page that gets opened in front of a room. The domain stays — it is what tells the viewer
 * *which* account this is, which is the only reason the line is here — and the local part goes.
 *
 * The run of dots is a fixed-ish length rather than the real one, so the mask does not quietly
 * publish how long the address is. Anything without an `@` is treated as a secret of unknown shape
 * and masked whole; that covers a malformed or partially synced value rather than leaking it.
 */
internal fun maskEmail(email: String): String {
    val trimmed = email.trim()
    val at = trimmed.lastIndexOf('@')
    if (at <= 0) return trimmed.take(1) + "•".repeat(6)
    val local = trimmed.substring(0, at)
    val domain = trimmed.substring(at)
    return local.take(1) + "•".repeat((local.length - 1).coerceIn(4, 8)) + domain
}

/**
 * An info line whose value is hidden until the viewer asks for it.
 *
 * Same shape as [InfoLine] — it sits directly under one — with the eye the mobile app uses, and OK
 * on the row toggles it. The row was already focusable so that Left could get back to the sidebar,
 * so this costs no new stop on the way down the page: the affordance is on the thing the highlight
 * already lands on, rather than being a separate control to travel to.
 */
@Composable
private fun RevealableInfoLine(label: String, value: String) {
    val leftRequester = LocalSettingsLeftRequester.current
    var focused by remember { mutableStateOf(false) }
    // Keyed on the value, so switching account never carries a reveal over to a different address.
    var revealed by remember(value) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .background(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft && leftRequester?.let { runCatching { it.requestFocus() }.isSuccess } == true }
            .clickable { revealed = !revealed }
            .padding(horizontal = 7.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.45f))
        Row(
            modifier = Modifier.weight(0.55f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (revealed) value else maskEmail(value),
                color = if (focused) Color.White else Color.White.copy(alpha = 0.88f),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = if (revealed) {
                    Icons.Outlined.VisibilityOff
                } else {
                    Icons.Outlined.Visibility
                },
                contentDescription = stringResource(if (revealed) R.string.a11y_hide_named else R.string.a11y_show_named, label),
                tint = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp),
            )
        }
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
        // Wraps rather than truncates.
        //
        // This is a label/value line, but several panels use it to carry a sentence of explanation
        // -- and two lines at just over half the width is not enough for one. The instruction under
        // "Auto Skip" ended mid-word at "each segment runs once per playback ses...", which is the
        // half of it that mattered. Short values are unaffected; long ones simply take the lines
        // they need, inside a panel that already scrolls.
        Text(value, color = if (focused) Color.White else Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(0.55f))
    }
}

@Composable
private fun SettingsDropdownRow(
    title: String,
    description: String,
    value: String,
    options: List<Pair<String, String>>,
    optionColors: Map<String, Color> = emptyMap(),
    optionDescriptions: Map<String, String> = emptyMap(),
    /**
     * A thumbnail of what an option looks like, drawn beside its label.
     *
     * For settings whose whole subject is an appearance: a sentence describing artwork is a poor
     * substitute for the artwork. Optional, and every existing row leaves it out.
     */
    optionPreview: (@Composable (String) -> Unit)? = null,
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
                optionPreview?.invoke(value)
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
                            optionPreview?.invoke(optionValue)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                androidx.compose.material3.Text(label, color = if (optionFocused) Color.White else Color.White.copy(alpha = 0.86f), fontWeight = if (optionValue.equals(value, true)) FontWeight.Bold else FontWeight.Medium)
                                optionDescriptions[optionValue]?.let { endpoint ->
                                    androidx.compose.material3.Text(endpoint, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
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

/**
 * A thumbnail of one Streaming Networks card style, for the settings dropdown.
 *
 * Both options show real bundled artwork for the same service, so what differs between the two
 * thumbnails is only the treatment -- which is the thing being chosen.
 */
@Composable
private fun NetworkCardStylePreview(branded: Boolean) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(5.dp)
    Box(
        Modifier.width(40.dp).height(22.dp)
            .clip(shape)
            .background(if (branded) Color(0xFF0E0E0E) else Color.White)
            .border(1.dp, Color.White.copy(alpha = 0.24f), shape),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = if (branded) R.drawable.network_tile_netflix else R.drawable.network_logo_netflix,
            contentDescription = null,
            modifier = if (branded) Modifier.fillMaxSize() else Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 2.dp),
            contentScale = if (branded) {
                androidx.compose.ui.layout.ContentScale.Crop
            } else {
                androidx.compose.ui.layout.ContentScale.Fit
            },
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
/**
 * What the trailer cache currently holds, and when it was last thrown away.
 *
 * Takes the resources rather than reading them itself: this is called from a click handler as well
 * as from the composition, and both need the same wording in the chosen language.
 */
private fun trailerCacheStatusLabel(
    resources: Resources,
    language: AppLanguage,
    sizeBytes: Long,
    lastClearedAt: Long,
): String {
    val size = if (sizeBytes <= 0L) {
        resources.getString(R.string.trailer_cache_nothing_stored)
    } else {
        resources.getString(
            R.string.trailer_cache_stored,
            resources.getString(R.string.unit_kilobytes, AppFormats.number(language, sizeBytes / 1024)),
        )
    }
    if (lastClearedAt <= 0L) {
        return resources.getString(R.string.settings_tv_dot_joined, size, resources.getString(R.string.trailer_cache_never_cleared))
    }
    val hoursAgo = ((System.currentTimeMillis() - lastClearedAt) / 3_600_000L).toInt()
    val cleared = when {
        hoursAgo <= 0 -> resources.getString(R.string.trailer_cache_cleared_recently)
        hoursAgo < 24 -> resources.getQuantityString(R.plurals.trailer_cache_cleared_hours_ago, hoursAgo, hoursAgo)
        hoursAgo < 48 -> resources.getString(R.string.trailer_cache_cleared_yesterday)
        else -> resources.getQuantityString(R.plurals.trailer_cache_cleared_days_ago, hoursAgo / 24, hoursAgo / 24)
    }
    return resources.getString(R.string.settings_tv_dot_joined, size, cleared)
}

private fun onOff(resources: Resources, value: Boolean) =
    resources.getString(if (value) R.string.state_on else R.string.animation_speed_off)

private fun serviceStatus(resources: Resources, connected: Boolean, username: String?) = if (connected) {
    username?.takeIf { it.isNotBlank() } ?: resources.getString(R.string.settings_connected)
} else {
    resources.getString(R.string.state_not_connected)
}
/**
 * The values these four rows store, named rather than spelled out at each of their call sites.
 *
 * Each is written to the account and read back by the phone and the web portal, so it is a protocol
 * word and never translated - the row shows a [stringResource] beside it for that. They are named
 * here because the same word was previously typed in the normaliser, in the option list and in the
 * comparison that decides which option is selected, and one of the three going out of step is a
 * setting that silently resets itself.
 */
private object PlayerEngineValues {
    const val MPV = "MPV"
    const val EXOPLAYER = "ExoPlayer"
    const val AUTO = "Auto"
}

private object DecoderModeValues {
    const val HARDWARE = "HW"
    const val SOFTWARE = "SW"
    const val RECOMMENDED = "HW+"
}

private object RenderSurfaceValues {
    const val COMPATIBILITY = "Compatibility"
    const val STANDARD = "Standard"
}

private object QualityValues {
    const val UHD = "2160p"
    const val FHD = "1080p"
    const val HD = "720p"
    const val AUTO = "Auto"
}

private object NetworkCardStyleValues {
    const val CLASSIC = "Classic"
    const val BRANDED = "Branded"
}

private object SubtitleSourceValues {
    const val ALL = "All"
    const val BUILT_IN = "BuiltIn"
    const val ADDONS = "Addons"
}

private fun normalizePlayerEngine(value: String?): String = when (value?.trim()?.lowercase()) {
    "mpv" -> PlayerEngineValues.MPV
    "media3", "exo", "exoplayer" -> PlayerEngineValues.EXOPLAYER
    else -> PlayerEngineValues.AUTO
}
private fun normalizeDecoderMode(value: String?): String = when (value?.trim()?.lowercase()) {
    "hw", "hardware", "mediacodec-copy" -> DecoderModeValues.HARDWARE
    "sw", "software", "none" -> DecoderModeValues.SOFTWARE
    else -> DecoderModeValues.RECOMMENDED
}
private fun normalizeRenderSurface(value: String?): String = when (value?.trim()?.lowercase()) {
    "compatibility", "texture", "textureview" -> RenderSurfaceValues.COMPATIBILITY
    else -> RenderSurfaceValues.STANDARD
}
private fun normalizePreferredQuality(value: String?): String = when (value?.trim()?.lowercase()) {
    "4k", "2160p", "uhd" -> QualityValues.UHD
    "1080p" -> QualityValues.FHD
    "720p" -> QualityValues.HD
    else -> QualityValues.AUTO
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

@Composable
private fun languageOptions(includeOff: Boolean): List<Pair<String, String>> = buildList {
    if (includeOff) add("off" to stringResource(R.string.settings_opt_off))
    // Every ISO language rather than a typed list of ten. The list is long, but the row is a
    // dropdown a viewer opens knowing what they are looking for, and the alternative is telling
    // most of the world their language is not available.
    addAll(Languages.all.map { it.code to it.label })
}
private fun directorySize(root: java.io.File) = runCatching { root.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
/** A byte count with a locale-correct decimal mark; "1,5 GB" is not "1.5 GB" everywhere. */
private fun formatBytes(resources: Resources, language: AppLanguage, bytes: Long): String = when {
    bytes >= 1_073_741_824L ->
        resources.getString(R.string.unit_gigabytes, AppFormats.number(language, bytes / 1_073_741_824.0, 1))
    bytes >= 1_048_576L ->
        resources.getString(R.string.unit_megabytes, AppFormats.number(language, bytes / 1_048_576.0, 1))
    bytes >= 1024L ->
        resources.getString(R.string.unit_kilobytes, AppFormats.number(language, bytes / 1024.0, 1))
    else -> resources.getString(R.string.unit_bytes, AppFormats.number(language, bytes))
}
