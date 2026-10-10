package com.streamdek.tv.nativeapp.ui.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.mediaserver.JellyfinQuickConnectCode
import com.streamdek.tv.nativeapp.mediaserver.JellyfinServerCandidate
import com.streamdek.tv.nativeapp.mediaserver.MediaServerLinkStatus
import com.streamdek.tv.nativeapp.mediaserver.MediaServerReachability
import com.streamdek.tv.nativeapp.mediaserver.OfflineReason
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Jellyfin's purple, from its logo: the focus ring and switches, so the page reads as Jellyfin's. */
internal val JellyfinPurple = Color(0xFFAA5CC3)
private val JfPanelBackground = Color(0xFF0E141D)
private val JfRowFocused = Color(0xFF172131)
private val JfPositive = Color(0xFF22C55E)
private val JfWaiting = Color(0xFF60A5FA)
private val JfWarning = Color(0xFFF59E0B)

/**
 * Settings > Jellyfin, on a television.
 *
 * Jellyfin is the viewer's own server at an address only they know, so connecting starts by finding
 * it: servers on this network are looked for at once, and an address can be typed instead. Signing
 * in is then Quick Connect wherever the server allows it - a code approved from a Jellyfin app the
 * viewer is already signed in to, nothing typed with a remote - with a username and password as
 * the fallback. The password goes to the server once and is never kept.
 *
 * Follows the Plex page's focus rules: nothing focused vanishes or disables under the remote, since
 * on a television the focused rail item decides which page is open.
 */
@Composable
internal fun JellyfinSettingsPanel(
    repository: StreamDekRepository,
    signedIn: Boolean,
    leftRequester: FocusRequester,
    onStatus: (String) -> Unit,
) {
    val manager = repository.mediaServers
    val state by manager.jellyfinState.collectAsState()
    val ambient by manager.jellyfinAmbient.collectAsState()
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources

    var adding by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<JellyfinServerCandidate>>(emptyList()) }
    var chosen by remember { mutableStateOf<JellyfinServerCandidate?>(null) }
    var quickCode by remember { mutableStateOf<JellyfinQuickConnectCode?>(null) }
    var quickExpired by remember { mutableStateOf(false) }
    var askAddress by remember { mutableStateOf(false) }
    var askPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf<String?>(null) }

    val anchorRequester = remember { FocusRequester() }
    val primaryRequester = remember { FocusRequester() }
    var focusPrimaryRequest by remember { mutableIntStateOf(0) }

    fun holdFocus() {
        runCatching { anchorRequester.requestFocus() }
    }

    fun focusPrimarySoon() {
        focusPrimaryRequest += 1
    }

    LaunchedEffect(focusPrimaryRequest) {
        if (focusPrimaryRequest == 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { primaryRequester.requestFocus() }
    }

    val connecting = adding || !state.linked

    fun resetFlow() {
        holdFocus()
        chosen = null
        quickCode = null
        quickExpired = false
        adding = false
        focusPrimarySoon()
    }

    fun search() {
        if (searching) return
        searching = true
        scope.launch {
            found = manager.discoverJellyfinServers()
            searching = false
            if (found.isEmpty()) onStatus(resources.getString(R.string.jellyfin_none_found))
        }
    }

    fun startQuickConnect(server: JellyfinServerCandidate) {
        if (busy) return
        busy = true
        scope.launch {
            val code = manager.startJellyfinQuickConnect(server)
            busy = false
            if (code == null) {
                onStatus(resources.getString(R.string.jellyfin_quick_connect_unavailable))
            } else {
                holdFocus()
                quickExpired = false
                quickCode = code
                focusPrimarySoon()
            }
        }
    }

    fun choose(server: JellyfinServerCandidate) {
        holdFocus()
        chosen = server
        quickCode = null
        focusPrimarySoon()
        if (server.quickConnect) startQuickConnect(server)
    }

    fun finished(result: MediaServerLinkStatus) {
        when (result) {
            is MediaServerLinkStatus.Linked -> {
                resetFlow()
                onStatus(result.accountName?.let { resources.getString(R.string.plex_connected_as, it) } ?: resources.getString(R.string.plex_connected))
            }
            MediaServerLinkStatus.Failed -> onStatus(resources.getString(R.string.jellyfin_sign_in_failed))
            else -> Unit
        }
    }

    // Look for servers on the network as soon as the page is about connecting, before anything is typed.
    LaunchedEffect(connecting, signedIn) {
        if (connecting && signedIn && found.isEmpty()) search()
    }

    // Quick Connect: checks every few seconds while the code is on screen.
    LaunchedEffect(quickCode) {
        val code = quickCode ?: return@LaunchedEffect
        while (true) {
            delay(3_000)
            when (val result = manager.pollJellyfinQuickConnect(code)) {
                MediaServerLinkStatus.Pending -> Unit
                MediaServerLinkStatus.Expired -> {
                    quickExpired = true
                    return@LaunchedEffect
                }
                else -> {
                    finished(result)
                    return@LaunchedEffect
                }
            }
        }
    }

    val leftToRail = Modifier.onPreviewKeyEvent {
        it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && runCatching { leftRequester.requestFocus() }.isSuccess
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        JellyfinHeader(
            status = when {
                !signedIn -> stringResource(R.string.plex_not_connected)
                state.linked && state.needsAttention -> stringResource(R.string.jellyfin_needs_attention)
                state.linked -> state.accountName?.let { stringResource(R.string.plex_connected_as, it) } ?: stringResource(R.string.plex_connected)
                else -> stringResource(R.string.plex_not_connected)
            },
            statusColor = when {
                state.linked && state.needsAttention -> JfWarning
                state.linked -> JfPositive
                else -> Color.White.copy(alpha = 0.45f)
            },
            body = stringResource(if (!signedIn) R.string.jellyfin_signed_out_note else R.string.jellyfin_intro_body),
            modifier = Modifier.focusRequester(anchorRequester).then(leftToRail),
        )

        if (signedIn && connecting) {
            val server = chosen
            if (server == null) {
                // Step one: which server.
                PlexSectionHeading(stringResource(R.string.jellyfin_find_server))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { askAddress = true },
                        modifier = Modifier.focusRequester(primaryRequester).then(leftToRail),
                    ) { Text(stringResource(R.string.jellyfin_enter_address)) }
                    OutlinedButton(onClick = ::search) {
                        Text(stringResource(if (searching) R.string.jellyfin_searching else R.string.jellyfin_search_network))
                    }
                    if (adding) OutlinedButton(onClick = ::resetFlow) { Text(stringResource(R.string.action_cancel)) }
                }
                found.forEach { candidate ->
                    PlexSwitchRow(
                        title = candidate.name,
                        detail = listOfNotNull(candidate.url.removePrefix("http://").removePrefix("https://"), candidate.version?.let { "Jellyfin $it" }).joinToString(" · "),
                        detailColor = Color.White.copy(alpha = 0.5f),
                        checked = false,
                        indent = false,
                        leftRequester = leftRequester,
                        accent = JellyfinPurple,
                        onToggle = { choose(candidate) },
                    )
                }
                PlexNote(stringResource(R.string.jellyfin_find_note))
            } else {
                // Step two: sign in to it.
                PlexSectionHeading(stringResource(R.string.jellyfin_sign_in_to, server.name))
                val code = quickCode
                if (code != null) JellyfinQuickConnectPanel(code = code.code, expired = quickExpired)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (server.quickConnect) {
                        Button(
                            onClick = { if (code == null || quickExpired) startQuickConnect(server) else askPassword = true },
                            modifier = Modifier.focusRequester(primaryRequester).then(leftToRail),
                        ) {
                            Text(
                                stringResource(
                                    when {
                                        busy -> R.string.plex_link_getting_code
                                        code == null -> R.string.jellyfin_use_quick_connect
                                        quickExpired -> R.string.plex_link_new_code
                                        else -> R.string.jellyfin_use_password
                                    },
                                ),
                            )
                        }
                        if (code == null || quickExpired) OutlinedButton(onClick = { askPassword = true }) { Text(stringResource(R.string.jellyfin_use_password)) }
                    } else {
                        Button(
                            onClick = { askPassword = true },
                            modifier = Modifier.focusRequester(primaryRequester).then(leftToRail),
                        ) { Text(stringResource(R.string.jellyfin_use_password)) }
                    }
                    OutlinedButton(onClick = {
                        holdFocus()
                        chosen = null
                        quickCode = null
                        focusPrimarySoon()
                    }) { Text(stringResource(R.string.jellyfin_other_server)) }
                }
                if (!server.quickConnect) PlexNote(stringResource(R.string.jellyfin_quick_connect_off))
            }
        }

        if (signedIn && state.linked && !adding) {
            MediaServerGroups(
                provider = com.streamdek.tv.nativeapp.mediaserver.JELLYFIN_PROVIDER_ID,
                manager = manager,
                servers = state.servers,
                accent = JellyfinPurple,
                leftRequester = leftRequester,
                emptyNote = null,
                onToggleServer = { server -> manager.setJellyfinServerEnabled(server.id, !server.enabled) },
                onToggleLibrary = { server, library -> manager.setJellyfinLibraryEnabled(server.id, library.key, !library.enabled) },
                holdFocus = ::holdFocus,
                onStatus = onStatus,
                // Signing out of the last server turns this page back into the connect flow.
                onServerRemoved = ::focusPrimarySoon,
            )

            PlexSectionHeading(stringResource(R.string.jellyfin_page_section))
            PlexSwitchRow(
                title = stringResource(R.string.plex_ambient),
                detail = stringResource(R.string.jellyfin_ambient_detail),
                detailColor = Color.White.copy(alpha = 0.5f),
                checked = ambient,
                indent = false,
                leftRequester = leftRequester,
                accent = JellyfinPurple,
                onToggle = { manager.setJellyfinAmbient(!ambient) },
            )

            MediaServerContinueLocationRow(repository, com.streamdek.tv.nativeapp.mediaserver.JELLYFIN_PROVIDER_ID, stringResource(R.string.media_server_jellyfin), leftRequester, onStatus)

            PlexSectionHeading(stringResource(R.string.plex_manage))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (!state.refreshing) scope.launch { manager.refreshJellyfin(force = true); onStatus(resources.getString(R.string.jellyfin_refreshed)) }
                    },
                    modifier = leftToRail,
                ) { Text(stringResource(if (state.refreshing) R.string.plex_status_connecting else R.string.plex_refresh)) }
                OutlinedButton(onClick = {
                    holdFocus()
                    adding = true
                    chosen = null
                    focusPrimarySoon()
                }) { Text(stringResource(R.string.jellyfin_add_server)) }
                OutlinedButton(onClick = { confirmSignOut = "" }) { Text(stringResource(R.string.jellyfin_sign_out)) }
            }
            PlexNote(stringResource(R.string.jellyfin_sign_out_note))
        }
        PlexNote(stringResource(R.string.jellyfin_logo_credit))
    }

    if (askAddress) {
        JellyfinAddressDialog(
            onDismiss = { askAddress = false },
            onFind = { input, done ->
                scope.launch {
                    val server = manager.findJellyfinServer(input)
                    done(server != null)
                    if (server != null) {
                        askAddress = false
                        choose(server)
                    }
                }
            },
        )
    }

    val server = chosen
    if (askPassword && server != null) {
        JellyfinPasswordDialog(
            serverName = server.name,
            onDismiss = { askPassword = false },
            onSignIn = { user, password, done ->
                scope.launch {
                    val result = manager.signInToJellyfin(server, user, password)
                    done(result is MediaServerLinkStatus.Linked)
                    if (result is MediaServerLinkStatus.Linked) askPassword = false
                    finished(result)
                }
            },
        )
    }

    confirmSignOut?.let {
        PlexConfirmDialog(
            title = stringResource(R.string.jellyfin_sign_out_title),
            body = stringResource(R.string.jellyfin_sign_out_body),
            confirm = stringResource(R.string.jellyfin_sign_out),
            onConfirm = {
                confirmSignOut = null
                scope.launch {
                    holdFocus()
                    manager.disconnectJellyfin()
                    focusPrimarySoon()
                    onStatus(resources.getString(R.string.jellyfin_signed_out))
                }
            },
            onDismiss = { confirmSignOut = null },
        )
    }
}

@Composable
private fun JellyfinHeader(status: String, statusColor: Color, body: String, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth()
            .background(if (focused) JfRowFocused else JfPanelBackground, RoundedCornerShape(18.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) JellyfinPurple.copy(alpha = 0.6f) else Color(0x10FFFFFF), RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painter = painterResource(R.drawable.jellyfin_logo), contentDescription = null, modifier = Modifier.size(52.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.media_server_jellyfin), color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Box(Modifier.size(9.dp).background(statusColor, CircleShape))
                Text(status, color = statusColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            }
            Text(body, color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The Quick Connect code, sized to be read across a room and typed on another device. */
@Composable
private fun JellyfinQuickConnectPanel(code: String, expired: Boolean) {
    Column(
        Modifier.fillMaxWidth()
            .background(JfPanelBackground, RoundedCornerShape(18.dp))
            .border(2.dp, JellyfinPurple.copy(alpha = if (expired) 0.25f else 0.7f), RoundedCornerShape(18.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (expired) {
            Text(stringResource(R.string.plex_link_expired), color = JfWarning, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        } else {
            Text(stringResource(R.string.jellyfin_quick_connect_step), color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodyMedium)
            Text(
                code,
                color = JellyfinPurple,
                letterSpacing = 10.sp,
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(JfWaiting, CircleShape))
                Text(stringResource(R.string.jellyfin_link_waiting), color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun jellyfinFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color(0xFF121722), unfocusedContainerColor = Color(0xFF0E121A),
    focusedIndicatorColor = JellyfinPurple, unfocusedIndicatorColor = Color(0x18FFFFFF),
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
)

@Composable
private fun JellyfinAddressDialog(onDismiss: () -> Unit, onFind: (String, (Boolean) -> Unit) -> Unit) {
    var value by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    fun submit() {
        if (working || value.isBlank()) return
        working = true
        failed = false
        onFind(value) { ok ->
            working = false
            failed = !ok
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(620.dp).background(JfPanelBackground, RoundedCornerShape(22.dp)).border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(22.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.jellyfin_enter_address), color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            Text(stringResource(R.string.jellyfin_address_hint), color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value = value,
                onValueChange = { value = it; failed = false },
                singleLine = true,
                placeholder = { Text("192.168.1.20:8096") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { submit() }),
                colors = jellyfinFieldColors(),
                modifier = Modifier.fillMaxWidth().focusRequester(requester),
            )
            if (failed) Text(stringResource(R.string.jellyfin_address_not_found), color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(onClick = ::submit) { Text(stringResource(if (working) R.string.jellyfin_searching else R.string.jellyfin_connect_address)) }
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
}

@Composable
private fun JellyfinPasswordDialog(serverName: String, onDismiss: () -> Unit, onSignIn: (String, String, (Boolean) -> Unit) -> Unit) {
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    fun submit() {
        if (working || user.isBlank()) return
        working = true
        failed = false
        onSignIn(user, password) { ok ->
            working = false
            failed = !ok
            // The password is only ever held while it is being sent.
            if (ok) password = ""
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(620.dp).background(JfPanelBackground, RoundedCornerShape(22.dp)).border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(22.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.jellyfin_sign_in_to, serverName), color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            OutlinedTextField(
                value = user,
                onValueChange = { user = it; failed = false },
                singleLine = true,
                label = { Text(stringResource(R.string.jellyfin_username)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                colors = jellyfinFieldColors(),
                modifier = Modifier.fillMaxWidth().focusRequester(requester),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; failed = false },
                singleLine = true,
                label = { Text(stringResource(R.string.jellyfin_password)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { submit() }),
                colors = jellyfinFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (failed) Text(stringResource(R.string.jellyfin_sign_in_failed), color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Button(onClick = ::submit) { Text(stringResource(if (working) R.string.plex_status_connecting else R.string.jellyfin_sign_in)) }
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
}

/** A one-line summary for the Sources page row that points here. */
@Composable
internal fun jellyfinSummary(repository: StreamDekRepository): String {
    val state by repository.mediaServers.jellyfinState.collectAsState()
    return when {
        state.linked && state.needsAttention -> stringResource(R.string.jellyfin_needs_attention)
        state.linked -> stringResource(R.string.plex_libraries_on, state.usableLibraries.size)
        else -> stringResource(R.string.plex_not_connected)
    }
}

/** Whether a Jellyfin server refused the sign-in, for the page's attention state. */
internal fun MediaServerReachability.refused(): Boolean = (this as? MediaServerReachability.Offline)?.reason == OfflineReason.Unauthorized
