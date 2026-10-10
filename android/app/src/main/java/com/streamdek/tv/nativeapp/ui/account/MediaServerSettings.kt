package com.streamdek.tv.nativeapp.ui.account

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.mediaserver.MediaServerLibrary
import com.streamdek.tv.nativeapp.mediaserver.MediaServerLibraryKind
import com.streamdek.tv.nativeapp.mediaserver.MediaServerLinkCode
import com.streamdek.tv.nativeapp.mediaserver.MediaServerLinkStatus
import com.streamdek.tv.nativeapp.mediaserver.MediaServerReachability
import com.streamdek.tv.nativeapp.mediaserver.MediaServerRoute
import com.streamdek.tv.nativeapp.mediaserver.MediaServerView
import com.streamdek.tv.nativeapp.mediaserver.OfflineReason
import com.streamdek.tv.nativeapp.mediaserver.MediaServerManager
import com.streamdek.tv.nativeapp.mediaserver.PLEX_PROVIDER_ID
import com.streamdek.tv.nativeapp.mediaserver.RemovedMediaServerEntry
import com.streamdek.tv.nativeapp.mediaserver.listedMediaServers
import com.streamdek.tv.nativeapp.mediaserver.mediaServerEntryKey
import com.streamdek.tv.nativeapp.mediaserver.removedMediaServerEntries
import com.streamdek.tv.nativeapp.ui.StreamDekPlayerIcons
import com.streamdek.tv.nativeapp.ui.StreamDekSettingsIcons
import com.streamdek.tv.nativeapp.ui.auth.rememberQrImage
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings > Plex, on a television.
 *
 * Built around the sofa. Connecting is one press: a short code appears with plex.tv/link beside it
 * and a QR code that opens the page with the code already filled in, and the screen notices the
 * moment the viewer approves it on their phone - nothing to confirm, nothing to type with a remote.
 *
 * Once connected the page is the integration's management screen: the account, each server with
 * how it is being reached, each library with its switch, and the actions the brief asks for.
 * Choices save to the StreamDek profile, so every device on it follows.
 */

private val PanelBackground = Color(0xFF0E141D)
private val RowIdle = Color(0xB20E141D)
private val RowFocused = Color(0xFF172131)
/** Plex's own gold, used for the focus ring and the code so the page reads as Plex's at a glance. */
internal val PlexGold = Color(0xFFE5A00D)
private val Positive = Color(0xFF22C55E)
private val Waiting = Color(0xFF60A5FA)
private val Warning = Color(0xFFF59E0B)

/** The remote-quality choices, in kbps; null is original quality. */
private val RemoteQualities: List<Int?> = listOf(null, 20_000, 12_000, 8_000, 4_000, 2_000)

@Composable
internal fun MediaServerSettingsPanel(
    repository: StreamDekRepository,
    signedIn: Boolean,
    leftRequester: FocusRequester,
    onStatus: (String) -> Unit,
) {
    val manager = repository.mediaServers
    val state by manager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    var linkCode by remember { mutableStateOf<MediaServerLinkCode?>(null) }
    var linkExpired by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf(repository.mediaServerRemoteQualityKbps()) }
    /**
     * Focus on a television decides what the Settings rail shows: a rail item that receives focus
     * opens its page. So nothing on this page may lose focus by vanishing or being disabled under
     * the viewer - that is how pressing Connect used to land them on another settings page. Two
     * rules keep it put: controls never disable themselves (a second press is ignored instead),
     * and before anything focused is taken off screen, focus is parked on the header, which never
     * leaves, and then handed to the primary button once the new layout is drawn.
     */
    val anchorRequester = remember { FocusRequester() }
    val primaryRequester = remember { FocusRequester() }
    var focusPrimaryRequest by remember { mutableStateOf(0) }

    fun holdFocus() {
        runCatching { anchorRequester.requestFocus() }
    }

    fun focusPrimarySoon() {
        focusPrimaryRequest += 1
    }

    LaunchedEffect(focusPrimaryRequest) {
        if (focusPrimaryRequest == 0) return@LaunchedEffect
        // One frame, so the button that takes over exists before it is asked to take focus. When
        // there is no primary button in the new layout, focus simply stays on the header.
        withFrameNanos { }
        runCatching { primaryRequester.requestFocus() }
    }

    fun startLink() {
        if (starting) return
        starting = true
        linkExpired = false
        scope.launch {
            val code = manager.startLink()
            starting = false
            if (code == null) {
                onStatus(resources.getString(R.string.plex_link_unavailable))
            } else {
                holdFocus()
                linkCode = code
                focusPrimarySoon()
            }
        }
    }

    fun closeCode() {
        holdFocus()
        linkCode = null
        linkExpired = false
        focusPrimarySoon()
    }

    // Polls while a code is on screen, at the pace StreamDek asked for. Leaving the page, or the
    // code expiring, ends it; approval on the phone ends it with Plex connected.
    LaunchedEffect(linkCode) {
        val code = linkCode ?: return@LaunchedEffect
        while (true) {
            delay(code.pollIntervalMs)
            when (val result = manager.pollLink(code)) {
                MediaServerLinkStatus.Pending -> Unit
                MediaServerLinkStatus.Expired -> {
                    linkExpired = true
                    return@LaunchedEffect
                }
                MediaServerLinkStatus.Failed -> {
                    closeCode()
                    onStatus(resources.getString(R.string.plex_link_failed))
                    return@LaunchedEffect
                }
                is MediaServerLinkStatus.Linked -> {
                    closeCode()
                    onStatus(
                        result.accountName?.let { resources.getString(R.string.plex_connected_as, it) }
                            ?: resources.getString(R.string.plex_connected),
                    )
                    return@LaunchedEffect
                }
            }
        }
    }

    fun act(work: suspend () -> Boolean, success: Int? = null, reshapesPage: Boolean = false) {
        if (busy) return
        busy = true
        scope.launch {
            if (reshapesPage) holdFocus()
            val ok = work()
            busy = false
            if (reshapesPage) focusPrimarySoon()
            onStatus(resources.getString(if (ok) success ?: R.string.plex_saved else R.string.plex_saving_failed))
        }
    }

    val leftToRail = Modifier.onPreviewKeyEvent {
        it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && runCatching { leftRequester.requestFocus() }.isSuccess
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PlexHeader(
            title = stringResource(R.string.media_server_plex),
            status = when {
                !signedIn -> stringResource(R.string.plex_not_connected)
                state.linked && state.needsAttention -> stringResource(R.string.plex_needs_attention)
                state.linked -> state.accountName?.let { stringResource(R.string.plex_connected_as, it) } ?: stringResource(R.string.plex_connected)
                else -> stringResource(R.string.plex_not_connected)
            },
            statusColor = when {
                state.linked && state.needsAttention -> Warning
                state.linked -> Positive
                else -> Color.White.copy(alpha = 0.45f)
            },
            body = stringResource(if (!signedIn) R.string.plex_signed_out_note else R.string.plex_intro_body),
            modifier = Modifier.focusRequester(anchorRequester).then(leftToRail),
        )

        val code = linkCode
        if (signedIn && code != null) PlexLinkPanel(code = code, expired = linkExpired)

        // The one primary action, always the same button: Connect, then Cancel while a code is up,
        // then "Get a new code" if it runs out. Changing its words rather than swapping it for
        // another button is what keeps the remote's focus on it.
        if (signedIn && (code != null || !state.linked || state.needsAttention)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        when {
                            code == null || linkExpired -> startLink()
                            else -> closeCode()
                        }
                    },
                    modifier = Modifier.focusRequester(primaryRequester).then(leftToRail),
                ) {
                    Text(
                        when {
                            starting -> stringResource(R.string.plex_link_getting_code)
                            code != null && linkExpired -> stringResource(R.string.plex_link_new_code)
                            code != null -> stringResource(R.string.action_cancel)
                            state.needsAttention -> stringResource(R.string.plex_reconnect)
                            else -> stringResource(R.string.plex_connect)
                        },
                    )
                }
                if (code != null && linkExpired) {
                    OutlinedButton(onClick = ::closeCode) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }

        if (signedIn && state.linked && code == null) {
            MediaServerGroups(
                provider = PLEX_PROVIDER_ID,
                manager = manager,
                servers = state.servers,
                accent = PlexGold,
                leftRequester = leftRequester,
                emptyNote = stringResource(if (state.refreshing) R.string.plex_status_connecting else R.string.plex_no_servers),
                onToggleServer = { server -> act({ manager.setServerEnabled(server.id, !server.enabled) }) },
                onToggleLibrary = { server, library -> act({ manager.setLibraryEnabled(server.id, library.key, !library.enabled) }) },
                holdFocus = ::holdFocus,
                onStatus = onStatus,
            )

            PlexSectionHeading(stringResource(R.string.plex_playback))
            PlexChoiceRow(
                title = stringResource(R.string.plex_remote_quality),
                detail = stringResource(R.string.plex_remote_quality_detail),
                value = quality?.let { stringResource(R.string.plex_quality_mbps, it / 1000) } ?: stringResource(R.string.plex_quality_original),
                leftRequester = leftRequester,
            ) {
                val next = RemoteQualities[(RemoteQualities.indexOf(quality) + 1) % RemoteQualities.size]
                quality = next
                repository.setMediaServerRemoteQualityKbps(next)
            }

            PlexSectionHeading(stringResource(R.string.plex_page_section))
            val ambient by manager.ambient.collectAsState()
            PlexSwitchRow(
                title = stringResource(R.string.plex_ambient),
                detail = stringResource(R.string.plex_ambient_detail),
                detailColor = Color.White.copy(alpha = 0.5f),
                checked = ambient,
                indent = false,
                leftRequester = leftRequester,
                onToggle = { manager.setAmbient(!ambient) },
            )

            MediaServerContinueLocationRow(repository, com.streamdek.tv.nativeapp.mediaserver.PLEX_PROVIDER_ID, stringResource(R.string.media_server_plex), leftRequester, onStatus)

            PlexSectionHeading(stringResource(R.string.plex_manage))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (!state.refreshing) scope.launch { manager.refresh(force = true); onStatus(resources.getString(R.string.plex_refreshed)) }
                    },
                    modifier = leftToRail,
                ) { Text(stringResource(if (state.refreshing) R.string.plex_status_connecting else R.string.plex_refresh)) }
                OutlinedButton(onClick = { if (!busy) startLink() }) {
                    Text(stringResource(if (starting) R.string.plex_link_getting_code else R.string.plex_reconnect))
                }
                OutlinedButton(onClick = { if (!busy) confirmDisconnect = true }) { Text(stringResource(R.string.plex_disconnect)) }
            }
            PlexNote(stringResource(R.string.plex_revoke_note))
        }
    }

    if (confirmDisconnect) {
        PlexConfirmDialog(
            title = stringResource(R.string.plex_disconnect_title),
            body = stringResource(R.string.plex_disconnect_body),
            confirm = stringResource(R.string.plex_disconnect),
            onConfirm = {
                confirmDisconnect = false
                act({ manager.disconnect() }, success = R.string.plex_disconnected, reshapesPage = true)
            },
            onDismiss = { confirmDisconnect = false },
        )
    }
}

@Composable
private fun PlexHeader(title: String, status: String, statusColor: Color, body: String, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth()
            .background(if (focused) RowFocused else PanelBackground, RoundedCornerShape(18.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) PlexGold.copy(alpha = 0.6f) else Color(0x10FFFFFF), RoundedCornerShape(18.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.plex_logo),
            contentDescription = null,
            modifier = Modifier.size(56.dp).clip(CircleShape),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Box(Modifier.size(9.dp).background(statusColor, CircleShape))
                Text(status, color = statusColor, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            }
            Text(body, color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * The code and where to enter it, sized to be read across a room.
 *
 * The QR code opens plex.tv/link with the code already in it, so a viewer with a phone in hand
 * does not type anything at all.
 */
@Composable
private fun PlexLinkPanel(code: MediaServerLinkCode, expired: Boolean) {
    val qr = rememberQrImage(code.directUrl.takeUnless { expired })
    Row(
        Modifier.fillMaxWidth()
            .background(PanelBackground, RoundedCornerShape(18.dp))
            .border(2.dp, PlexGold.copy(alpha = if (expired) 0.25f else 0.7f), RoundedCornerShape(18.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (expired) {
                Text(stringResource(R.string.plex_link_expired), color = Warning, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.plex_link_step_open), color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        code.linkUrl.removePrefix("https://").removePrefix("http://"),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.plex_link_step_code), color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        code.code,
                        color = PlexGold,
                        // Spaced and oversized: read across a room, typed by hand somewhere else.
                        letterSpacing = 10.sp,
                        style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(Waiting, CircleShape))
                    Text(stringResource(R.string.plex_link_waiting), color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (qr != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(
                    painter = BitmapPainter(qr),
                    contentDescription = stringResource(R.string.plex_link_scan),
                    modifier = Modifier.size(168.dp).clip(RoundedCornerShape(12.dp)),
                )
                Text(
                    stringResource(R.string.plex_link_scan),
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(168.dp),
                )
            }
        }
    }
}

@Composable
internal fun PlexSectionHeading(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
internal fun PlexNote(text: String, indent: Boolean = false) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.5f),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = if (indent) 36.dp else 4.dp),
    )
}

@Composable
internal fun reachabilityLabel(server: MediaServerView): Pair<String, Color> = when {
    !server.enabled -> stringResource(R.string.plex_status_off) to Color.White.copy(alpha = 0.4f)
    else -> when (val reach = server.reachability) {
        is MediaServerReachability.Online -> when (reach.route) {
            MediaServerRoute.Local -> stringResource(R.string.plex_status_local) to Positive
            MediaServerRoute.Remote -> stringResource(R.string.plex_status_remote) to Positive
            MediaServerRoute.Relay -> stringResource(R.string.plex_status_relay) to Warning
        }
        MediaServerReachability.Connecting, MediaServerReachability.Unknown -> stringResource(R.string.plex_status_connecting) to Waiting
        is MediaServerReachability.Offline -> when (reach.reason) {
            OfflineReason.Unauthorized -> stringResource(R.string.plex_status_unauthorized) to Warning
            OfflineReason.NoConnections -> stringResource(R.string.plex_status_no_connections) to Warning
            OfflineReason.Unreachable -> stringResource(R.string.plex_status_offline) to Warning
        }
    }
}

/**
 * The servers and their libraries, as groups that fold, with a way to take one library or one
 * server off the list. Shared by the Plex and Jellyfin pages so the two behave alike.
 *
 * Built for a remote. A server's own row folds and unfolds its group; the rows inside it are the
 * server's switch, each library, and "Remove this server". A library row is two stops: the row
 * itself is the switch, and Right reaches its remove button. Nothing is a long-press, because
 * nothing on screen would say so.
 *
 * Removing is not disconnecting: every other server and library stays exactly as it was, and what
 * was removed is kept under "Removed" to be brought back; see MediaServerListTidy.kt. Because the
 * row that had the highlight leaves the page when it is removed or restored, focus is parked first
 * ([holdFocus]) and then handed to the server row it belonged to - the rule this page lives by.
 */
@Composable
internal fun MediaServerGroups(
    provider: String,
    manager: MediaServerManager,
    servers: List<MediaServerView>,
    accent: Color,
    leftRequester: FocusRequester,
    emptyNote: String?,
    onToggleServer: (MediaServerView) -> Unit,
    onToggleLibrary: (MediaServerView, MediaServerLibrary) -> Unit,
    holdFocus: () -> Unit,
    onStatus: (String) -> Unit,
    /** After a whole server has gone, for a page that may have changed shape under the viewer. */
    onServerRemoved: () -> Unit = {},
) {
    val removed by manager.removedEntries.collectAsState()
    val collapsed by manager.collapsedServers.collectAsState()
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    var working by remember { mutableStateOf(false) }
    var removingServer by remember { mutableStateOf<MediaServerView?>(null) }
    var removingLibrary by remember { mutableStateOf<MediaServerLibrary?>(null) }
    val listed = remember(provider, servers, removed) { listedMediaServers(provider, servers, removed) }
    val gone = remember(provider, servers, removed) { removedMediaServerEntries(provider, servers, removed) }
    val headerRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var focusServerId by remember { mutableStateOf<String?>(null) }
    var focusServerRequest by remember { mutableStateOf(0) }

    LaunchedEffect(focusServerRequest) {
        if (focusServerRequest == 0) return@LaunchedEffect
        // One frame, so the server's row is laid out before it is asked to take the highlight.
        withFrameNanos { }
        focusServerId?.let { headerRequesters[it] }?.let { runCatching { it.requestFocus() } }
    }

    fun perform(name: String, doneRes: Int, focusServer: String?, work: suspend () -> Boolean) {
        if (working) return
        working = true
        holdFocus()
        scope.launch {
            val ok = work()
            working = false
            if (focusServer != null) {
                focusServerId = focusServer
                focusServerRequest += 1
            } else {
                onServerRemoved()
            }
            onStatus(if (ok) resources.getString(doneRes, name) else resources.getString(R.string.plex_saving_failed))
        }
    }

    PlexSectionHeading(stringResource(R.string.plex_servers))
    if (listed.isEmpty()) emptyNote?.let { PlexNote(it) }
    listed.forEach { server ->
        val folded = mediaServerEntryKey(provider, server.id) in collapsed
        MediaServerGroupHeader(
            server = server,
            folded = folded,
            accent = accent,
            leftRequester = leftRequester,
            requester = headerRequesters.getOrPut(server.id) { FocusRequester() },
            onFold = { manager.setServerCollapsed(provider, server.id, !folded) },
        )
        if (!folded) {
            PlexSwitchRow(
                title = stringResource(R.string.media_server_use_server),
                detail = null,
                detailColor = Color.White.copy(alpha = 0.5f),
                checked = server.enabled,
                indent = true,
                leftRequester = leftRequester,
                accent = accent,
                onToggle = { onToggleServer(server) },
            )
            if (server.enabled) {
                if (server.libraries.isEmpty()) PlexNote(stringResource(R.string.plex_no_libraries), indent = true)
                server.libraries.forEach { library ->
                    PlexLibraryRow(
                        library = library,
                        leftRequester = leftRequester,
                        accent = accent,
                        onRemove = { removingLibrary = library },
                        onToggle = { onToggleLibrary(server, library) },
                    )
                }
            }
            MediaServerActionRow(
                text = stringResource(R.string.media_server_remove_server),
                value = null,
                detail = null,
                textColor = Color(0xFFF87171),
                accent = accent,
                leftRequester = leftRequester,
                indent = true,
                onClick = { removingServer = server },
            )
        }
    }

    if (gone.isNotEmpty()) {
        PlexSectionHeading(stringResource(R.string.media_server_removed))
        PlexNote(stringResource(R.string.media_server_removed_note))
        gone.forEach { entry ->
            val library = entry.library
            MediaServerActionRow(
                text = library?.title ?: entry.serverName,
                value = stringResource(R.string.media_server_restore),
                detail = if (library == null) stringResource(R.string.media_server_whole_server) else entry.serverName,
                textColor = Color.White,
                accent = accent,
                leftRequester = leftRequester,
                indent = false,
                onClick = {
                    if (library == null) perform(entry.serverName, R.string.media_server_restored_done, entry.serverId) { manager.restoreServer(provider, entry.serverId) }
                    else perform(library.title, R.string.media_server_restored_done, entry.serverId) { manager.restoreLibrary(provider, entry.serverId, library.key) }
                },
            )
        }
    }

    removingLibrary?.let { library ->
        PlexConfirmDialog(
            title = stringResource(R.string.media_server_remove_title, library.title),
            body = stringResource(R.string.media_server_remove_library_body),
            confirm = stringResource(R.string.media_server_remove),
            onConfirm = {
                removingLibrary = null
                perform(library.title, R.string.media_server_removed_done, library.serverId) { manager.removeLibrary(provider, library.serverId, library.key) }
            },
            onDismiss = { removingLibrary = null },
        )
    }
    removingServer?.let { server ->
        PlexConfirmDialog(
            title = stringResource(R.string.media_server_remove_title, server.name),
            body = stringResource(com.streamdek.tv.nativeapp.ui.mediaServerBrand(provider).removeServerBody),
            confirm = stringResource(R.string.media_server_remove),
            onConfirm = {
                removingServer = null
                perform(server.name, R.string.media_server_removed_done, null) { manager.removeServer(provider, server.id) }
            },
            onDismiss = { removingServer = null },
        )
    }
}

/** A server's own row. Pressing it folds or unfolds the group beneath; it never switches anything. */
@Composable
private fun MediaServerGroupHeader(
    server: MediaServerView,
    folded: Boolean,
    accent: Color,
    leftRequester: FocusRequester,
    requester: FocusRequester,
    onFold: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val (status, color) = reachabilityLabel(server)
    val owner = server.ownerName?.takeIf { !server.owned }?.let { stringResource(R.string.plex_server_shared_by, it) }
    // Folded, the row says what is inside it, so nothing has to be opened to find out.
    val count = if (folded && server.enabled && server.libraries.isNotEmpty()) {
        stringResource(R.string.media_server_libraries_on_of, server.libraries.count { it.enabled }, server.libraries.size)
    } else null
    Row(
        Modifier.fillMaxWidth()
            .focusRequester(requester)
            .background(if (focused) RowFocused else RowIdle, RoundedCornerShape(16.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) accent else Color(0x10FFFFFF), RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent {
                it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && runCatching { leftRequester.requestFocus() }.isSuccess
            }
            .clickable(
                onClickLabel = stringResource(if (folded) R.string.media_server_show_libraries else R.string.media_server_hide_libraries),
                onClick = onFold,
            )
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(server.name, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(listOfNotNull(status, owner, count).joinToString(" · "), color = color, style = MaterialTheme.typography.bodySmall)
        }
        Icon(
            if (folded) StreamDekPlayerIcons.ChevronDown else StreamDekSettingsIcons.ChevronUp,
            contentDescription = null,
            tint = if (focused) accent else Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** A row that does one thing when pressed: remove this server, or restore what was removed. */
@Composable
private fun MediaServerActionRow(
    text: String,
    value: String?,
    detail: String?,
    textColor: Color,
    accent: Color,
    leftRequester: FocusRequester,
    indent: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .padding(start = if (indent) 32.dp else 0.dp)
            .background(if (focused) RowFocused else RowIdle, RoundedCornerShape(16.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) accent else Color(0x10FFFFFF), RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent {
                it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && runCatching { leftRequester.requestFocus() }.isSuccess
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(text, color = textColor, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            detail?.let { Text(it, color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall) }
        }
        value?.let { Text(it, color = accent, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)) }
    }
}

/** The button at the end of a library row. Reached with Right; Left goes back to the row. */
@Composable
private fun MediaServerRemoveButton(label: String, accent: Color, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.size(52.dp)
            .background(if (focused) RowFocused else RowIdle, RoundedCornerShape(16.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) accent else Color(0x10FFFFFF), RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            StreamDekSettingsIcons.Close,
            contentDescription = label,
            tint = if (focused) Color.White else Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A focusable row with a switch at its end. The whole row is the switch. */
@Composable
internal fun PlexSwitchRow(
    title: String,
    detail: String?,
    detailColor: Color,
    checked: Boolean,
    indent: Boolean,
    leftRequester: FocusRequester,
    accent: Color = PlexGold,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth()
            .padding(start = if (indent) 32.dp else 0.dp)
            .background(if (focused) RowFocused else RowIdle, RoundedCornerShape(16.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) accent else Color(0x10FFFFFF), RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent {
                it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && runCatching { leftRequester.requestFocus() }.isSuccess
            }
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            detail?.let { Text(it, color = detailColor, style = MaterialTheme.typography.bodySmall) }
        }
        // A pill switch drawn here, so it matches the page rather than a platform control.
        Box(
            Modifier.width(46.dp).padding(vertical = 2.dp)
                .background(if (checked) accent else Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(18.dp).background(Color.White, CircleShape))
        }
    }
}

@Composable
internal fun PlexServerRow(server: MediaServerView, leftRequester: FocusRequester, accent: Color = PlexGold, onToggle: () -> Unit) {
    val (status, color) = reachabilityLabel(server)
    val owner = server.ownerName?.takeIf { !server.owned }?.let { stringResource(R.string.plex_server_shared_by, it) }
    PlexSwitchRow(
        title = server.name,
        detail = listOfNotNull(status, owner).joinToString(" · "),
        detailColor = color,
        checked = server.enabled,
        indent = false,
        leftRequester = leftRequester,
        accent = accent,
        onToggle = onToggle,
    )
}

@Composable
internal fun PlexLibraryRow(
    library: MediaServerLibrary,
    leftRequester: FocusRequester,
    accent: Color = PlexGold,
    onRemove: (() -> Unit)? = null,
    onToggle: () -> Unit,
) {
    val detail = stringResource(
        when (library.kind) {
            MediaServerLibraryKind.Movies -> R.string.plex_library_movies
            MediaServerLibraryKind.Shows -> R.string.plex_library_shows
            MediaServerLibraryKind.Other -> R.string.plex_library_other
        },
    )
    if (onRemove == null) {
        PlexSwitchRow(
            title = library.title,
            detail = detail,
            detailColor = Color.White.copy(alpha = 0.5f),
            checked = library.enabled,
            indent = true,
            leftRequester = leftRequester,
            accent = accent,
            onToggle = onToggle,
        )
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlexSwitchRow(
            title = library.title,
            detail = detail,
            detailColor = Color.White.copy(alpha = 0.5f),
            checked = library.enabled,
            indent = false,
            leftRequester = leftRequester,
            accent = accent,
            modifier = Modifier.weight(1f),
            onToggle = onToggle,
        )
        MediaServerRemoveButton(stringResource(R.string.media_server_remove_named, library.title), accent, onRemove)
    }
}

@Composable
private fun PlexChoiceRow(title: String, detail: String, value: String, leftRequester: FocusRequester, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .background(if (focused) RowFocused else RowIdle, RoundedCornerShape(16.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) PlexGold else Color(0x10FFFFFF), RoundedCornerShape(16.dp))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent {
                it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft && runCatching { leftRequester.requestFocus() }.isSuccess
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(detail, color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
        }
        Text(value, color = PlexGold, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
internal fun PlexConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cancelRequester = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(560.dp)
                .background(PanelBackground, RoundedCornerShape(22.dp))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(22.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            Text(body, color = Color.White.copy(alpha = 0.65f), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Cancel has the focus: a destructive action is never one accidental press away.
                OutlinedButton(onClick = onDismiss, modifier = Modifier.focusRequester(cancelRequester)) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = onConfirm) { Text(confirm) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { cancelRequester.requestFocus() } }
}

/** A one-line summary for the Sources page row that points here. */
@Composable
internal fun mediaServerSummary(repository: StreamDekRepository): String {
    val state by repository.mediaServers.state.collectAsState()
    val libraries = state.usableLibraries.size
    return when {
        state.linked && state.needsAttention -> stringResource(R.string.plex_needs_attention)
        state.linked -> stringResource(R.string.plex_libraries_on, libraries)
        else -> stringResource(R.string.plex_not_connected)
    }
}

/**
 * Where a provider's in-progress titles appear - see
 * [com.streamdek.tv.nativeapp.data.MediaServerContinueLocation]. One row that switches between the
 * two places on select, naming the active one, on the Plex and Jellyfin pages alike. Synced with
 * the phone and the web portal; changing it reconnects nothing.
 */
@Composable
internal fun MediaServerContinueLocationRow(
    repository: StreamDekRepository,
    provider: String,
    providerName: String,
    leftRequester: FocusRequester,
    onStatus: (String) -> Unit,
) {
    val bootstrap by repository.bootstrap.collectAsState()
    val location = com.streamdek.tv.nativeapp.data.MediaServerContinueLocations.from(bootstrap?.preferences?.home).of(provider)
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    val serverLibrary = location == com.streamdek.tv.nativeapp.data.MediaServerContinueLocation.ServerLibrary
    PlexSectionHeading(stringResource(R.string.media_server_continue_location_title))
    PlexNote(stringResource(R.string.media_server_continue_location_detail, providerName))
    PlexChoiceRow(
        title = stringResource(if (serverLibrary) R.string.media_server_continue_location_server else R.string.media_server_continue_location_streamdek),
        detail = stringResource(
            if (serverLibrary) R.string.media_server_continue_location_server_detail else R.string.media_server_continue_location_streamdek_detail,
            providerName,
        ),
        value = stringResource(R.string.media_server_continue_location_switch),
        leftRequester = leftRequester,
    ) {
        val next = if (serverLibrary) com.streamdek.tv.nativeapp.data.MediaServerContinueLocation.StreamDek else com.streamdek.tv.nativeapp.data.MediaServerContinueLocation.ServerLibrary
        scope.launch {
            if (repository.setMediaServerContinueLocation(provider, next) == null) {
                onStatus(resources.getString(R.string.media_server_continue_location_failed))
            }
        }
    }
}
