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
import com.streamdek.tv.nativeapp.data.DoHSettings
import kotlinx.coroutines.delay

@Composable
internal fun CustomDoHEndpointDialog(initialValue: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { delay(80); runCatching { requester.requestFocus() }; keyboard?.show() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xC7000000)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.fillMaxWidth(0.58f).clip(RoundedCornerShape(26.dp)).background(Color(0xFF0E141D))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(26.dp))
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(stringResource(R.string.doh_custom_title), color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black))
                Text(stringResource(R.string.doh_custom_explainer), color = Color.White.copy(alpha = 0.62f))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    singleLine = true,
                    placeholder = { Text("https://…/dns-query") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF121722), unfocusedContainerColor = Color(0xFF0E121A),
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary, unfocusedIndicatorColor = Color(0x18FFFFFF),
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(requester),
                )
                error?.let { Text(it, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(onClick = {
                        val validation = DoHSettings.validateEndpoint(value)
                        if (validation == null) onSave(value.trim()) else error = validation
                    }, enabled = value.isNotBlank()) { Text(stringResource(R.string.action_save)) }
                    OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
    }
}
