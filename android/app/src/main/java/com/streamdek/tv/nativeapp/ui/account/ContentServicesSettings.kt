package com.streamdek.tv.nativeapp.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamdek.tv.nativeapp.data.ContentService
import com.streamdek.tv.nativeapp.data.ContentServiceState
import com.streamdek.tv.nativeapp.data.ContentServicesState
import com.streamdek.tv.nativeapp.data.CredentialRemoval
import com.streamdek.tv.nativeapp.data.CredentialStatus
import com.streamdek.tv.nativeapp.data.CredentialStorage
import com.streamdek.tv.nativeapp.data.StorageChoice
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Content Services, on a television.
 *
 * The whole screen is arranged around one fact: nobody wants to type a forty-character API key
 * with a remote control. So the routes that avoid it come first and are described in full — a key
 * saved to the StreamDek account from a phone or the web portal is simply already here — and
 * typing one on the television is offered last, as the fallback it should be.
 *
 * A key already inherited from the account is shown as exactly that, with the option to replace
 * it, so a viewer is never left wondering whether the television has one or is about to ask.
 */

private val PanelBackground = Color(0xFF0E141D)
private val RowIdle = Color(0xB20E141D)
private val RowFocused = Color(0xFF172131)

private fun serviceAccent(service: ContentService): Color = when (service) {
    ContentService.Tmdb -> Color(0xFF01B4E4)
    ContentService.Mdblist -> Color(0xFFF5A524)
}

private fun statusColor(status: CredentialStatus): Color = when (status) {
    CredentialStatus.Connected -> Color(0xFF22C55E)
    CredentialStatus.Checking -> Color(0xFF60A5FA)
    CredentialStatus.NeedsAttention -> Color(0xFFF59E0B)
    CredentialStatus.NotConfigured -> Color.White.copy(alpha = 0.35f)
}

/** The whole page, as the Settings shell renders it under its own destination. */
@Composable
internal fun ContentServicesPanel(
    state: ContentServicesState,
    repository: StreamDekRepository,
    signedIn: Boolean,
    leftRequester: FocusRequester,
    onStatus: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var entry by remember { mutableStateOf<ContentService?>(null) }
    var removing by remember { mutableStateOf<ContentServiceState?>(null) }
    var busy by remember { mutableStateOf<ContentService?>(null) }

    fun run(service: ContentService, work: suspend () -> Result<String>) {
        if (busy != null) return
        busy = service
        scope.launch {
            val result = work()
            busy = null
            onStatus(
                result.getOrElse { it.message ?: "That didn't work. Try again in a moment." },
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ContentServicesIntro(state, signedIn)

        ContentService.all.forEach { service ->
            ContentServiceRow(
                state = state.of(service),
                busy = busy == service,
                signedIn = signedIn,
                leftRequester = leftRequester,
                onEnterKey = { entry = service },
                onSaveToAccount = {
                    run(service) { repository.copyContentServiceKeyToAccount(service) }
                },
                onRemove = { removing = state.of(service) },
            )
        }

        ContentServicesRoutes(leftRequester)
    }

    entry?.let { service ->
        ContentServiceKeyDialog(
            service = service,
            existing = state.of(service),
            signedIn = signedIn,
            onSubmit = { key, choice ->
                entry = null
                run(service) { repository.submitContentServiceKey(service, key, choice) }
            },
            onDismiss = { entry = null },
        )
    }

    removing?.let { target ->
        ContentServiceRemoveDialog(
            state = target,
            onRemove = { scope_ ->
                removing = null
                run(target.service) { repository.removeContentServiceKey(target.service, scope_) }
            },
            onDismiss = { removing = null },
        )
    }
}

/**
 * What this page is for, said before any of the mechanics.
 *
 * The line changes depending on whether StreamDek's shared key is still answering, because
 * "this could be better" and "this is why your rows are empty" are different messages and only
 * one of them is ever true at a time.
 */
@Composable
private fun ContentServicesIntro(state: ContentServicesState, signedIn: Boolean) {
    Column(
        Modifier.fillMaxWidth()
            .background(PanelBackground, RoundedCornerShape(18.dp))
            .border(1.dp, Color(0x10FFFFFF), RoundedCornerShape(18.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            "Your own content services",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        Text(
            when {
                !signedIn ->
                    "Sign in to StreamDek and any keys saved to your account appear here on their own. " +
                        "Until then, a key entered on this television stays on this television."
                !state.anyConfigured && state.sharedFallbackAvailable ->
                    "StreamDek is using its own shared TMDB key for now, which works but is shared with " +
                        "everyone. Add your own for faster, fuller results — and you almost certainly " +
                        "don't need to type it here."
                !state.anyConfigured ->
                    "A TMDB key is needed for artwork and details on this television. The easiest way to " +
                        "add one is on your phone or the StreamDek web portal — it then appears here by itself."
                else ->
                    "Keys saved to your StreamDek account are used here automatically. You can replace or " +
                        "remove them from any of your devices."
            },
            color = Color.White.copy(alpha = 0.62f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** One service, as a focusable row a remote can land on. */
@Composable
private fun ContentServiceRow(
    state: ContentServiceState,
    busy: Boolean,
    signedIn: Boolean,
    leftRequester: FocusRequester,
    onEnterKey: () -> Unit,
    onSaveToAccount: () -> Unit,
    onRemove: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val service = state.service
    val accent = serviceAccent(service)

    Column(
        Modifier.fillMaxWidth()
            .background(if (focused) RowFocused else RowIdle, RoundedCornerShape(16.dp))
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) accent else Color(0x10FFFFFF),
                RoundedCornerShape(16.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent {
                it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft &&
                    runCatching { leftRequester.requestFocus() }.isSuccess
            }
            .clickable(enabled = !busy, onClick = onEnterKey)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    service.label,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    service.tagline,
                    color = Color.White.copy(alpha = 0.55f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(10.dp)
                        .background(statusColor(if (busy) CredentialStatus.Checking else state.status), CircleShape),
                )
                Text(
                    if (busy) "Checking…" else state.summary,
                    color = statusColor(if (busy) CredentialStatus.Checking else state.status),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                )
            }
        }

        // Only when there is something to say. A row that repeats its own title in smaller text
        // is noise on a screen being read from three metres away.
        if (!state.configured) {
            Text(
                service.blurb,
                color = Color.White.copy(alpha = 0.52f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (focused) Int.MAX_VALUE else 2,
                overflow = if (focused) TextOverflow.Clip else TextOverflow.Ellipsis,
            )
        }

        state.storage?.let { storage ->
            Text(
                "Storage: ${storage.label}${state.maskedKey?.let { "  $it" }.orEmpty()}  ·  ${storage.detail}",
                color = Color.White.copy(alpha = 0.48f),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (state.status == CredentialStatus.NeedsAttention) {
            Text(
                "${service.label} is no longer accepting this key. Replace it to bring " +
                    "${if (service == ContentService.Tmdb) "artwork and details" else "ratings"} back.",
                color = Color(0xFFF59E0B),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (state.storage == CredentialStorage.Device && state.accountKeyAlsoAvailable) {
            Text(
                "Your StreamDek account also has a ${service.label} key. This television is using its own; " +
                    "remove the one here to fall back to the account key.",
                color = Color.White.copy(alpha = 0.48f),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onEnterKey, enabled = !busy) {
                Text(
                    when {
                        state.status == CredentialStatus.NeedsAttention -> "Update Key"
                        state.configured -> "Replace Key"
                        else -> "Enter Key on TV"
                    },
                )
            }
            // Offered rather than done: a key the viewer chose to keep on this television is never
            // uploaded without them asking for it, here or anywhere else.
            if (state.storage == CredentialStorage.Device && !state.accountKeyAlsoAvailable && signedIn) {
                OutlinedButton(onClick = onSaveToAccount, enabled = !busy) { Text("Save to StreamDek") }
            }
            if (state.configured) {
                OutlinedButton(onClick = onRemove, enabled = !busy) { Text("Remove") }
            }
        }
    }
}

/**
 * The three setup routes, in the order that costs the viewer the least effort.
 *
 * Typing comes last deliberately. A television that leads with "enter your key here" teaches
 * people that this feature is painful, when for most of them it requires nothing on this device
 * at all.
 */
@Composable
private fun ContentServicesRoutes(leftRequester: FocusRequester) {
    Column(
        Modifier.fillMaxWidth()
            .background(PanelBackground, RoundedCornerShape(18.dp))
            .border(1.dp, Color(0x10FFFFFF), RoundedCornerShape(18.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Text(
            "Three ways to set these up",
            color = Color.White.copy(alpha = 0.88f),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )
        RouteLine(
            "1 - On the StreamDek web portal",
            "Sign in on a computer, open Account, then Content Services, and paste your keys with a " +
                "real keyboard. They save to your account and this television picks them up on its own.",
            leftRequester,
        )
        RouteLine(
            "2 - On StreamDek Mobile",
            "Enter a key on your phone and choose Save to StreamDek. This television will already have " +
                "it the next time it refreshes.",
            leftRequester,
        )
        RouteLine(
            "3 - On this television",
            "Use Enter Key on TV above if you would rather not use another device, or if you want this " +
                "television to have a key of its own.",
            leftRequester,
        )
    }
}

@Composable
private fun RouteLine(title: String, detail: String, leftRequester: FocusRequester) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (focused) RowFocused else Color.Transparent, RoundedCornerShape(12.dp))
            .border(
                if (focused) 2.dp else 0.dp,
                if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent {
                it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft &&
                    runCatching { leftRequester.requestFocus() }.isSuccess
            }
            .focusable()
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            title,
            color = if (focused) Color.White else Color.White.copy(alpha = 0.82f),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            detail,
            color = Color.White.copy(alpha = if (focused) 0.78f else 0.52f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ── Key entry ─────────────────────────────────────────────────────────────────────────────────

/**
 * Entering a key on the television, with the storage choice made in the same place.
 *
 * The field takes focus on arrival and the keyboard is raised, because the viewer opened this to
 * type and one more press to reach the field is one press too many with a remote in hand. Where
 * to get a key is spelled out on screen rather than behind a link, since a television cannot
 * usefully open one.
 */
@Composable
private fun ContentServiceKeyDialog(
    service: ContentService,
    existing: ContentServiceState,
    signedIn: Boolean,
    onSubmit: (String, StorageChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldRequester = remember(service) { FocusRequester() }
    var apiKey by remember(service) { mutableStateOf("") }
    var choice by remember(service) {
        mutableStateOf(if (signedIn) StorageChoice.SaveToStreamDek else StorageChoice.ThisDeviceOnly)
    }

    LaunchedEffect(service) {
        delay(80)
        runCatching { fieldRequester.requestFocus() }
        keyboard?.show()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xC7000000)), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.60f)
                    .clip(RoundedCornerShape(26.dp))
                    .background(PanelBackground)
                    .border(1.dp, serviceAccent(service).copy(alpha = 0.45f), RoundedCornerShape(26.dp))
                    .padding(horizontal = 30.dp, vertical = 26.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    if (existing.configured) "Update your ${service.label} key" else "Enter your ${service.label} key",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                )
                Text(
                    service.blurb,
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodyMedium,
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it.trim() },
                    singleLine = true,
                    placeholder = { Text(service.keyHint) },
                    shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF121722),
                        unfocusedContainerColor = Color(0xFF0E121A),
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = Color(0x18FFFFFF),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldRequester),
                )

                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "Don't have a ${service.label} key?",
                        color = Color.White.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    service.howToGet.forEachIndexed { index, step ->
                        Text(
                            "${index + 1}. $step",
                            color = Color.White.copy(alpha = 0.52f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "Get one at ${service.keyUrl}",
                        color = serviceAccent(service),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                }

                StorageChoiceRows(choice = choice, signedIn = signedIn, onChoice = { choice = it })

                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(
                        onClick = { onSubmit(apiKey.trim(), choice) },
                        enabled = apiKey.trim().length >= 8,
                    ) { Text("Check & Connect") }
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

/**
 * The storage choice as two focusable rows.
 *
 * Both consequences are written out rather than implied by a switch position — on a television
 * the viewer cannot hover anything for an explanation, so anything not on screen is not said.
 */
@Composable
private fun StorageChoiceRows(
    choice: StorageChoice,
    signedIn: Boolean,
    onChoice: (StorageChoice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(
            "Where should this key be kept?",
            color = Color.White.copy(alpha = 0.86f),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )
        StorageChoiceRow(
            title = "Save to StreamDek",
            detail = if (signedIn) {
                "Stored encrypted in your StreamDek account. Every device you sign in on uses it, " +
                    "so you never type it again."
            } else {
                "Sign in to StreamDek to use this option."
            },
            selected = choice == StorageChoice.SaveToStreamDek,
            enabled = signedIn,
            onSelect = { onChoice(StorageChoice.SaveToStreamDek) },
        )
        StorageChoiceRow(
            title = "This TV only",
            detail = "Stored encrypted on this television, and StreamDek keeps no copy. It is sent with " +
                "this TV's own requests so they can be made, and never saved. Your other devices will " +
                "each need their own key.",
            selected = choice == StorageChoice.ThisDeviceOnly,
            enabled = true,
            onSelect = { onChoice(StorageChoice.ThisDeviceOnly) },
        )
    }
}

@Composable
private fun StorageChoiceRow(
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) accent.copy(alpha = 0.14f) else Color(0x140E121A), RoundedCornerShape(14.dp))
            .border(
                if (focused) 2.dp else 1.dp,
                when {
                    focused -> accent
                    selected -> accent.copy(alpha = 0.55f)
                    else -> Color(0x18FFFFFF)
                },
                RoundedCornerShape(14.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(12.dp).padding(top = 4.dp)
                .background(if (selected) accent else Color.White.copy(alpha = 0.22f), CircleShape),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = Color.White.copy(alpha = if (enabled) 1f else 0.45f),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                detail,
                color = Color.White.copy(alpha = if (enabled) 0.55f else 0.32f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// ── Removal ───────────────────────────────────────────────────────────────────────────────────

/**
 * Removing a key, with the reach of the action stated before the button that does it.
 *
 * On a television this matters more than anywhere: the person holding the remote may not be the
 * person who set the account up, and "Remove" without a sentence explaining that it also takes
 * the key off everyone else's phone is a trap.
 */
@Composable
private fun ContentServiceRemoveDialog(
    state: ContentServiceState,
    onRemove: (CredentialRemoval) -> Unit,
    onDismiss: () -> Unit,
) {
    val service = state.service
    val onAccount = state.storage == CredentialStorage.Account
    val bothPlaces = state.storage == CredentialStorage.Device && state.accountKeyAlsoAvailable

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xC7000000)), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.52f)
                    .clip(RoundedCornerShape(26.dp))
                    .background(PanelBackground)
                    .border(1.dp, Color(0x33FF5449), RoundedCornerShape(26.dp))
                    .padding(horizontal = 30.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "Remove your ${service.label} key?",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                )
                Text(
                    when {
                        onAccount ->
                            "This key is saved to your StreamDek account and is in use on every device you " +
                                "are signed in on. Removing it takes it away from your phone and any other " +
                                "television as well as this one."
                        bothPlaces ->
                            "This television has its own ${service.label} key, and your StreamDek account has " +
                                "one too. Choose which to remove — removing the account key affects your " +
                                "other devices."
                        else ->
                            "This key is stored on this television only, so removing it affects nothing else."
                    },
                    color = Color.White.copy(alpha = 0.66f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (bothPlaces) {
                        Button(onClick = { onRemove(CredentialRemoval.Device) }) { Text("Remove from this TV") }
                        OutlinedButton(onClick = { onRemove(CredentialRemoval.Account) }) {
                            Text("Remove from StreamDek")
                        }
                    } else {
                        Button(
                            onClick = { onRemove(if (onAccount) CredentialRemoval.Account else CredentialRemoval.Device) },
                        ) {
                            Text(if (onAccount) "Remove from StreamDek" else "Remove from this TV")
                        }
                    }
                    OutlinedButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

/** The one-line summary the Connections list shows without opening the page. */
internal fun contentServicesSummary(state: ContentServicesState): String {
    val attention = state.needsAttention
    val connected = listOf(state.tmdb, state.mdblist).filter { it.configured }
    return when {
        attention.isNotEmpty() -> "${attention.joinToString(", ") { it.service.label }} needs attention"
        connected.size == 2 -> "TMDB and MDBList connected"
        connected.size == 1 -> "${connected.first().service.label} connected"
        else -> "Not configured"
    }
}
