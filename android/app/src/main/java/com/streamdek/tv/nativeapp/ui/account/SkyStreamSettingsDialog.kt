package com.streamdek.tv.nativeapp.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.data.PluginSettingField
import com.streamdek.tv.nativeapp.data.PluginSettingOption
import com.streamdek.tv.nativeapp.data.SkyProfileSource
import com.streamdek.tv.nativeapp.data.SkyProvider
import com.streamdek.tv.nativeapp.data.SkySettingsSchema
import com.streamdek.tv.nativeapp.data.SkyStreamPluginManager
import com.streamdek.tv.nativeapp.data.SkyStreamPlugins
import com.streamdek.tv.nativeapp.data.settingIsOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * A SkyStream source's details and settings, on the television.
 *
 * The fields are the ones the plugin declares, read by running it here. Below them come the two
 * things SkyStream's own settings screen adds - the site address, with any mirrors, and a switch per
 * source for a plugin that splits into several. Values are text throughout ("true"/"false" for
 * switches), as SkyStream stores them. Saving hands the whole set to [onSave], which writes it into
 * the profile's synced document, so the phone and the portal get the same settings.
 */
@Composable
internal fun SkyStreamSettingsDialog(
    source: SkyProfileSource,
    onSave: suspend (Map<String, String>) -> Boolean,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var schema by remember(source.packageName) { mutableStateOf<SkySettingsSchema?>(null) }
    var failed by remember(source.packageName) { mutableStateOf(false) }
    var values by remember(source.packageName) { mutableStateOf(source.settings) }
    var editingKey by remember(source.packageName) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val saveRequester = remember { FocusRequester() }
    val onLabel = stringResource(R.string.state_on)
    val offLabel = stringResource(R.string.animation_speed_off)
    val onOff = listOf(PluginSettingOption(onLabel, "true"), PluginSettingOption(offLabel, "false"))

    LaunchedEffect(source.packageName) {
        val manager = SkyStreamPlugins.manager
        // The bundle this television holds, or the record from the document when it has not been
        // fetched yet - settingsSchema downloads it in that case.
        val provider = manager.state.providers.firstOrNull { it.repoUrl == source.repoUrl && it.packageName == source.packageName }
            ?: SkyProvider(
                repoUrl = source.repoUrl,
                packageName = source.packageName,
                name = source.name,
                version = source.version,
                downloadUrl = source.downloadUrl,
                description = source.description,
                categories = source.categories,
            )
        manager.settingsSchema(provider)
            .onSuccess { loaded ->
                // Seed each field with the plugin's own default so saving untouched keeps its behaviour.
                val seeded = values.toMutableMap()
                loaded.fields.forEach { field -> field.key?.let { key -> if (key !in seeded) field.defaultValue?.let { seeded[key] = it } } }
                values = seeded
                schema = loaded
            }
            .onFailure { failed = true }
    }
    LaunchedEffect(schema, failed) {
        if (schema != null || failed) {
            delay(80)
            runCatching { saveRequester.requestFocus() }
        }
    }

    fun set(key: String, value: String?) {
        values = if (value.isNullOrEmpty()) values - key else values + (key to value)
    }

    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xC7000000)), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .heightIn(max = 680.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0xFF0E141D))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(26.dp))
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(source.name, color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black))
                Text(
                    listOfNotNull(
                        stringResource(R.string.sky_detail_version_value, source.version),
                        source.categories.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                        source.languages.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.uppercase() },
                    ).joinToString("  ·  "),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
                source.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodyMedium, maxLines = 3)
                }
                Text(source.downloadUrl, color = Color.White.copy(alpha = 0.45f), style = MaterialTheme.typography.bodySmall, maxLines = 2)

                val loaded = schema
                when {
                    loaded == null && !failed -> Text(stringResource(R.string.plugin_reading_settings), color = Color.White.copy(alpha = 0.7f))
                    loaded == null -> Text(stringResource(R.string.plugin_settings_undescribed), color = Color(0xFFFFB4AB))
                    else -> Column(
                        modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        val hasAddress = loaded.addressField != null || loaded.domains.isNotEmpty()
                        if (loaded.fields.isEmpty() && !hasAddress && loaded.subProviders.isEmpty()) {
                            Text(stringResource(R.string.plugin_no_settings), color = Color.White.copy(alpha = 0.7f))
                        }
                        loaded.fields.forEach { field ->
                            val fieldKey = field.key
                            if (fieldKey.isNullOrBlank()) {
                                Text(field.label, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.titleSmall)
                            } else key(fieldKey) {
                                SkySettingField(
                                    field = field,
                                    value = values[fieldKey],
                                    onOff = onOff,
                                    editing = editingKey == fieldKey,
                                    onEdit = { editing -> editingKey = if (editing) fieldKey else null },
                                    onValueChange = { set(fieldKey, it) },
                                )
                            }
                        }
                        if (hasAddress) {
                            val defaultChoice = PluginSettingOption(stringResource(R.string.sky_settings_address_plugin_default), "")
                            val current = values[SkyStreamPluginManager.ADDRESS_KEY].orEmpty()
                            PluginSettingChoiceRow(
                                field = PluginSettingField(
                                    type = "select",
                                    key = SkyStreamPluginManager.ADDRESS_KEY,
                                    label = loaded.addressField?.label ?: stringResource(R.string.sky_settings_address),
                                    description = loaded.addressField?.description
                                        ?: loaded.defaultAddress.takeIf { it.isNotBlank() }?.let { stringResource(R.string.sky_settings_address_default_named, it) },
                                    options = listOf(defaultChoice) + loaded.domains.map { PluginSettingOption(it.name, it.url) },
                                ),
                                value = current,
                                onValueChange = { set(SkyStreamPluginManager.ADDRESS_KEY, it) },
                            )
                            key(SkyStreamPluginManager.ADDRESS_KEY) {
                                PluginSettingTextRow(
                                    field = PluginSettingField(
                                        type = "text",
                                        key = SkyStreamPluginManager.ADDRESS_KEY,
                                        label = stringResource(R.string.sky_settings_address_custom),
                                        placeholder = loaded.defaultAddress.takeIf { it.isNotBlank() },
                                    ),
                                    value = current,
                                    editing = editingKey == SkyStreamPluginManager.ADDRESS_KEY,
                                    onEdit = { editing -> editingKey = if (editing) SkyStreamPluginManager.ADDRESS_KEY else null },
                                    onValueChange = { set(SkyStreamPluginManager.ADDRESS_KEY, it.trim()) },
                                )
                            }
                        }
                        if (loaded.subProviders.isNotEmpty()) {
                            val subKey = { id: String -> SkyStreamPluginManager.SUB_PROVIDER_ENABLED_PREFIX + id }
                            val on = loaded.subProviders.count { (id, _, default) -> settingIsOn(values[subKey(id)], default) }
                            Text(stringResource(R.string.sky_settings_sub_providers), color = Color.White, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                            Text(stringResource(R.string.sky_settings_sub_providers_hint, on, loaded.subProviders.size), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { values = values + loaded.subProviders.associate { (id, _, _) -> subKey(id) to "true" } }) {
                                    Text(stringResource(R.string.sky_settings_all_on))
                                }
                                OutlinedButton(onClick = { values = values + loaded.subProviders.associate { (id, _, _) -> subKey(id) to "false" } }) {
                                    Text(stringResource(R.string.sky_settings_all_off))
                                }
                            }
                            loaded.subProviders.forEach { (id, name, default) ->
                                key("sub:$id") {
                                    PluginSettingChoiceRow(
                                        field = PluginSettingField(type = "toggle", key = subKey(id), label = name, options = onOff),
                                        value = if (settingIsOn(values[subKey(id)], default)) "true" else "false",
                                        onValueChange = { set(subKey(id), it) },
                                    )
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Button(
                        onClick = {
                            if (saving) return@Button
                            saving = true
                            scope.launch {
                                val saved = onSave(values)
                                saving = false
                                if (saved) onDismiss()
                            }
                        },
                        enabled = schema != null && !saving,
                        modifier = Modifier.focusRequester(saveRequester),
                    ) { Text(stringResource(R.string.action_save)) }
                    OutlinedButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
    }
}

/** One declared field, drawn with the television's choice and text rows. */
@Composable
private fun SkySettingField(
    field: PluginSettingField,
    value: String?,
    onOff: List<PluginSettingOption>,
    editing: Boolean,
    onEdit: (Boolean) -> Unit,
    onValueChange: (String) -> Unit,
) {
    when (field.type) {
        // Stored as text; some plugins declare the default as "true" rather than true.
        "toggle" -> PluginSettingChoiceRow(
            field = field.copy(options = onOff),
            value = if (settingIsOn(value, settingIsOn(field.defaultValue))) "true" else "false",
            onValueChange = onValueChange,
        )
        "select" -> PluginSettingChoiceRow(field = field, value = value.orEmpty(), onValueChange = onValueChange)
        // Several switches stored as one JSON object of option -> on, as SkyStream stores them.
        "toggleGroup" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val current = runCatching { JSONObject(value ?: field.defaultValue ?: "{}") }.getOrDefault(JSONObject())
            Text(field.label, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            field.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.bodySmall) }
            field.options.forEach { option ->
                key(option.value) {
                    PluginSettingChoiceRow(
                        field = PluginSettingField(type = "toggle", key = option.value, label = option.label, options = onOff),
                        value = if (settingIsOn(current.opt(option.value), option.defaultOn)) "true" else "false",
                        onValueChange = { picked ->
                            val next = JSONObject()
                            field.options.forEach { other ->
                                next.put(other.value, if (other.value == option.value) picked == "true" else settingIsOn(current.opt(other.value), other.defaultOn))
                            }
                            onValueChange(next.toString())
                        },
                    )
                }
            }
        }
        else -> PluginSettingTextRow(field = field, value = value.orEmpty(), editing = editing, onEdit = onEdit, onValueChange = onValueChange)
    }
}
