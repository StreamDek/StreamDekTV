package com.streamdek.tv.nativeapp.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Connects a premium service that has no code-on-a-phone sign-in.
 *
 * Real-Debrid and Premiumize hand a television a short code and take the typing somewhere better
 * suited to it; AllDebrid, TorBox, Debrid-Link and Deepbrid do not, and the account holder's key
 * has to be entered here. The key is checked with the provider before it is stored, because a
 * character missed on a remote otherwise leaves a service that looks connected and quietly serves
 * nothing.
 */
@Composable
internal fun DebridApiKeyDialog(
    providerId: String,
    providerLabel: String,
    repository: StreamDekRepository,
    onConnected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val fieldRequester = remember(providerId) { FocusRequester() }
    var apiKey by remember(providerId) { mutableStateOf("") }
    var checking by remember(providerId) { mutableStateOf(false) }
    var error by remember(providerId) { mutableStateOf<String?>(null) }

    // The field is the only thing anyone opened this for, so it is live on arrival rather than
    // behind one more press.
    LaunchedEffect(providerId) {
        delay(80)
        runCatching { fieldRequester.requestFocus() }
        keyboard?.show()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xC7000000)), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.52f)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0xFF0E141D))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(26.dp))
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    stringResource(R.string.debrid_connect_provider, providerLabel),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                )
                Text(
                    stringResource(R.string.debrid_key_explainer, providerLabel),
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        error = null
                    },
                    singleLine = true,
                    enabled = !checking,
                    placeholder = { Text(stringResource(R.string.debrid_api_key)) },
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
                error?.let {
                    Text(it, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(
                        onClick = {
                            if (checking || apiKey.isBlank()) return@Button
                            checking = true
                            error = null
                            scope.launch {
                                val username = repository.connectDebridApiKey(providerId, apiKey)
                                checking = false
                                if (username == null) {
                                    error = "$providerLabel would not accept that key. Check it and try again."
                                } else {
                                    onConnected(username)
                                }
                            }
                        },
                        enabled = !checking && apiKey.isNotBlank(),
                    ) { Text(if (checking) "Checking…" else "Connect") }
                    OutlinedButton(onClick = onDismiss, enabled = !checking) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
    }
}
