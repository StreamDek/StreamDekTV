package com.streamdek.tv.nativeapp.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.data.PluginSettingField
import com.streamdek.tv.nativeapp.data.PluginSettingOption
import com.streamdek.tv.nativeapp.data.ProfilePluginProvider
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The settings a plugin source asks for, filled in on the TV itself.
 *
 * The schema comes from the provider: StreamDek runs its own `onSettings` export and renders
 * whatever it declares, so a source that wants an API key, a region and a toggle gets exactly
 * those three fields. Values stay on this device — they are credentials, and syncing them would
 * mean holding somebody's API key on the server in the clear.
 */
@Composable
internal fun PluginProviderSettingsDialog(
    provider: ProfilePluginProvider,
    repository: StreamDekRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var fields by remember(provider.id) { mutableStateOf<List<PluginSettingField>?>(null) }
    var values by remember(provider.id) { mutableStateOf(repository.pluginProviderSettings(provider.id)) }
    var error by remember(provider.id) { mutableStateOf<String?>(null) }
    var editingKey by remember(provider.id) { mutableStateOf<String?>(null) }
    val saveRequester = remember { FocusRequester() }

    LaunchedEffect(provider.id) {
        repository.pluginSettingsSchema(provider)
            .onSuccess { loaded ->
                // Seed anything not set yet with the provider's own default, so saving without
                // touching a field keeps the behaviour the provider expects.
                val seeded = values.toMutableMap()
                loaded.forEach { field ->
                    val key = field.key ?: return@forEach
                    if (!seeded.containsKey(key)) field.defaultValue?.let { seeded[key] = it }
                }
                values = seeded
                fields = loaded
            }
            .onFailure {
                fields = emptyList()
                error = it.message ?: "This source could not describe its settings."
            }
    }
    LaunchedEffect(fields) {
        if (fields != null) {
            delay(80)
            runCatching { saveRequester.requestFocus() }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xC7000000)), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.56f)
                    .heightIn(max = 660.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0xFF0E141D))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(26.dp))
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    provider.name.ifBlank { "Plugin source" },
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black),
                )
                val loaded = fields
                when {
                    loaded == null -> Text(stringResource(R.string.plugin_reading_settings), color = Color.White.copy(alpha = 0.7f))
                    error != null -> Text(error.orEmpty(), color = Color(0xFFFFB4AB))
                    loaded.isEmpty() -> Text(stringResource(R.string.plugin_no_settings), color = Color.White.copy(alpha = 0.7f))
                    else -> Column(
                        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        loaded.forEach { field ->
                            val fieldKey = field.key
                            if (fieldKey.isNullOrBlank()) {
                                // A heading or note the provider wanted shown between its fields.
                                Text(field.label, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodyMedium)
                            } else {
                                key(fieldKey) {
                                    when {
                                        field.options.isNotEmpty() -> PluginSettingChoiceRow(
                                            field = field,
                                            value = values[fieldKey].orEmpty(),
                                            onValueChange = { values = values + (fieldKey to it) },
                                        )
                                        field.type == "boolean" || field.type == "switch" -> PluginSettingChoiceRow(
                                            field = field.copy(
                                                options = listOf(
                                                    PluginSettingOption("On", "true"),
                                                    PluginSettingOption("Off", "false"),
                                                ),
                                            ),
                                            value = values[fieldKey].orEmpty().ifBlank { "false" },
                                            onValueChange = { values = values + (fieldKey to it) },
                                        )
                                        else -> PluginSettingTextRow(
                                            field = field,
                                            value = values[fieldKey].orEmpty(),
                                            editing = editingKey == fieldKey,
                                            onEdit = { editing -> editingKey = if (editing) fieldKey else null },
                                            onValueChange = { values = values + (fieldKey to it) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                repository.savePluginProviderSettings(provider.id, values)
                                onDismiss()
                            }
                        },
                        modifier = Modifier.focusRequester(saveRequester),
                    ) { Text(stringResource(R.string.action_save)) }
                    OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
    }
}

/**
 * One free-text field. The value is shown as a row until it is opened, because a TextField that
 * is always live steals D-pad focus from everything around it and pops the keyboard on the way past.
 */
@Composable
private fun PluginSettingTextRow(
    field: PluginSettingField,
    value: String,
    editing: Boolean,
    onEdit: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val editorRequester = remember { FocusRequester() }
    var editorWasFocused by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (editing) {
            delay(60)
            runCatching { editorRequester.requestFocus() }
            keyboard?.show()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(field.label, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        field.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall)
        }
        if (editing) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                placeholder = field.placeholder?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                visualTransformation = if (field.isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF121722),
                    unfocusedContainerColor = Color(0xFF0E121A),
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = Color(0x18FFFFFF),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(editorRequester)
                    .onFocusChanged {
                        if (it.isFocused) {
                            editorWasFocused = true
                        } else if (editorWasFocused) {
                            editorWasFocused = false
                            onEdit(false)
                        }
                    },
            )
        } else {
            val shown = when {
                value.isBlank() -> field.placeholder?.takeIf { it.isNotBlank() } ?: "Not set"
                // Never put a key back on screen in full once it has been entered.
                field.isPassword -> "•".repeat(value.length.coerceAtMost(12))
                else -> value
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (focused) Color(0xFF172131) else Color(0xB20E141D), RoundedCornerShape(12.dp))
                    .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else Color(0x10FFFFFF), RoundedCornerShape(12.dp))
                    .onFocusChanged { focused = it.isFocused }
                    .clickable { onEdit(true) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    shown,
                    color = if (value.isBlank()) Color.White.copy(alpha = 0.45f) else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A field with a fixed set of answers — the provider's own options, or a boolean's on/off. */
@Composable
private fun PluginSettingChoiceRow(
    field: PluginSettingField,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(field.label, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        field.description?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            field.options.forEach { option ->
                key(option.value) {
                    val selected = value == option.value
                    var focused by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f) else Color(0xB20E141D),
                                RoundedCornerShape(999.dp),
                            )
                            .border(
                                if (focused) 2.dp else 1.dp,
                                if (focused) MaterialTheme.colorScheme.primary else Color(0x18FFFFFF),
                                RoundedCornerShape(999.dp),
                            )
                            .onFocusChanged { focused = it.isFocused }
                            .clickable { onValueChange(option.value) }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    ) {
                        Text(option.label.ifBlank { option.value }, color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
