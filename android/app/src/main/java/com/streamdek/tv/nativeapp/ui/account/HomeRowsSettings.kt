package com.streamdek.tv.nativeapp.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.data.AddonManifest
import com.streamdek.tv.nativeapp.data.CatalogDefinition
import com.streamdek.tv.nativeapp.data.HomeCatalogRowPreference
import com.streamdek.tv.nativeapp.data.HomeRowOption
import com.streamdek.tv.nativeapp.data.buildHomeRowGroups
import com.streamdek.tv.nativeapp.data.homeRowLayoutOf
import com.streamdek.tv.nativeapp.data.homeRowOptions

/**
 * Which rows Home shows, arranged from the television.
 *
 * This existed on the phone and nowhere else, which is a poor place for it to live: a viewer
 * running a catalogue add-on with seventy catalogues had seventy rows on the biggest screen in the
 * house and had to fetch their phone to turn any of them off. The layout is the same synced list
 * either device writes, so a row switched off here is off there and the other way round.
 *
 * Switching is immediate and optimistic. The list is long — that is the whole reason the screen
 * exists — and a viewer working down it should not wait for a round trip between each press; a
 * failed save puts the switch back and says so rather than leaving the screen disagreeing with the
 * account.
 */
@Composable
internal fun HomeRowsSettings(
    definitions: List<CatalogDefinition>,
    addons: List<AddonManifest>,
    layout: List<HomeCatalogRowPreference>,
    streamDekRowsEnabled: Boolean,
    leftRequester: FocusRequester,
    onSave: (List<HomeCatalogRowPreference>, (Boolean) -> Unit) -> Unit,
) {
    // Seeded from the layout once, and deliberately not keyed on it.
    //
    // Saving a switch writes the layout, which comes back through this parameter — so keying the
    // list on it rebuilt and re-sorted the list on every press. Every row then had a position where
    // before only some did, the order changed underneath the viewer, the focused row was disposed
    // mid-press and the sidebar took the highlight back. Pressing one switch threw you out of the
    // screen. What rows exist depends on the registry and the add-ons; only those rebuild it.
    val available = remember(definitions, addons) { homeRowOptions(definitions, addons, layout) }
    var rows by remember(available) { mutableStateOf(available) }

    if (rows.isEmpty()) {
        HomeRowsHeading(
            title = stringResource(R.string.settings_home_rows),
            body = if (addons.isEmpty() && definitions.isEmpty()) {
                "Still loading the rows this profile can show."
            } else {
                "There are no rows to arrange yet. Install a catalogue add-on, or turn the " +
                    "built-in catalogues back on above."
            },
        )
        return
    }

    val enabledCount = rows.count { it.enabled }
    // Folded away by default.
    //
    // Thirty rows is a long list and seventy is a much longer one, and it sat between the two
    // settings above it and everything below. Anyone travelling to the card-density controls had to
    // press Down through every row to get there. Collapsed, it is one stop; open, it is what the
    // viewer came for.
    var expanded by remember { mutableStateOf(false) }
    HomeRowsDisclosure(
        title = stringResource(R.string.settings_home_rows),
        summary = "$enabledCount of ${rows.size} on",
        expanded = expanded,
        leftRequester = leftRequester,
        onToggle = { expanded = !expanded },
    )
    if (!expanded) return

    HomeRowsHeading(
        title = null,
        body = "Switch off the rows you do not want on Home. This is the same list the phone and " +
            "the web portal use, so a change here reaches every device on this profile.",
    )

    // One fold per source, the shape the phone shows. A viewer running several catalogue add-ons
    // has one list of seventy rows otherwise, and no way to tell whose row is whose.
    val fallbackAddonName = stringResource(R.string.home_row_group_unknown_addon)
    val groups = remember(rows, addons, streamDekRowsEnabled, fallbackAddonName) {
        buildHomeRowGroups(rows, addons, streamDekRowsEnabled, fallbackAddonName)
    }
    var expandedGroups by remember { mutableStateOf(emptySet<String>()) }

    groups.forEach { group ->
        key(group.key) {
            val open = group.gatedNoteRes == null && group.key in expandedGroups
            HomeRowsDisclosure(
                title = group.title,
                summary = if (group.gatedNoteRes != null) {
                    pluralStringResource(R.plurals.home_row_group_kept, group.rows.size, group.rows.size)
                } else {
                    stringResource(R.string.home_row_group_on_of, group.rows.count { it.enabled }, group.rows.size)
                },
                detail = group.gatedNoteRes?.let { stringResource(it) }
                    ?: if (open) "Press OK to close this source" else "Press OK to choose its rows",
                expanded = open,
                gated = group.gatedNoteRes != null,
                leftRequester = leftRequester,
                onToggle = {
                    expandedGroups = if (group.key in expandedGroups) expandedGroups - group.key else expandedGroups + group.key
                },
            )
            if (open) {
                group.rows.forEach { option ->
                    // Keyed, so a row keeps its identity across a save and the highlight stays on it.
                    key(option.id) {
                        HomeRowToggle(
                            option = option,
                            leftRequester = leftRequester,
                            onToggle = { next, complete ->
                                val previous = rows
                                // By id rather than by index: the groups are a view of this list,
                                // not a copy of its order.
                                rows = rows.map { if (it.id == option.id) it.copy(enabled = next) else it }
                                onSave(homeRowLayoutOf(rows)) { saved ->
                                    if (!saved) rows = previous
                                    complete(saved)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** The editor's own heading, so this file does not reach into the settings screen's private parts. */
@Composable
private fun HomeRowsHeading(title: String?, body: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (title != null) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
        Text(
            text = body,
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The one row that opens and closes the list.
 *
 * Shaped like the settings rows around it so it reads as one of them, with the count on the right
 * where those rows put their value -- a viewer can see how many rows are on without opening it.
 */
@Composable
private fun HomeRowsDisclosure(
    title: String,
    summary: String,
    expanded: Boolean,
    leftRequester: FocusRequester,
    onToggle: () -> Unit,
    detail: String? = null,
    /** Switched off from elsewhere: greyed, and it does not open. */
    gated: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val alpha = if (gated) 0.4f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (focused) Color(0xFF172131) else Color(0xB20E141D),
                RoundedCornerShape(16.dp),
            )
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) MaterialTheme.colorScheme.primary else Color(0x10FFFFFF),
                RoundedCornerShape(16.dp),
            )
            .onFocusChanged { focused = it.isFocused && !gated }
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionLeft &&
                    runCatching { leftRequester.requestFocus() }.isSuccess
            }
            .clickable(enabled = !gated, onClick = onToggle)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(Modifier.width(0.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                color = Color.White.copy(alpha = alpha),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            )
            Text(
                text = detail
                    ?: if (expanded) "Press OK to close the list" else "Choose which rows appear on Home",
                color = Color.White.copy(alpha = alpha * 0.55f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = summary,
            color = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = alpha * 0.8f),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )
        Icon(
            imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            contentDescription = null,
            tint = if (focused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = alpha * 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * One row's switch.
 *
 * Deliberately not [SettingsToggleRow]: that one is built for a handful of named settings and gives
 * each a full description line. Seventy of those is a page nobody can scan, so this is the compact
 * form — the row's name, where it came from, and a tick.
 */
@Composable
private fun HomeRowToggle(
    option: HomeRowOption,
    leftRequester: FocusRequester,
    onToggle: (Boolean, (Boolean) -> Unit) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var visualEnabled by remember(option.id) { mutableStateOf(option.enabled) }
    var saving by remember { mutableStateOf(false) }
    // The account is the authority once a save has settled; while one is in flight the switch keeps
    // showing what the viewer pressed rather than flicking back and forth under them.
    LaunchedEffect(option.enabled) { if (!saving) visualEnabled = option.enabled }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (focused) Color(0xFF172131) else Color(0xB20E141D),
                RoundedCornerShape(12.dp),
            )
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) MaterialTheme.colorScheme.primary else Color(0x10FFFFFF),
                RoundedCornerShape(12.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                event.type == KeyEventType.KeyDown &&
                    event.key == Key.DirectionLeft &&
                    runCatching { leftRequester.requestFocus() }.isSuccess
            }
            // Always clickable, never `enabled = !saving`.
            //
            // Disabling a clickable also makes the node unfocusable, so the row under the highlight
            // stopped being a focus target the instant it was pressed. Focus had nowhere to go, the
            // shell's focus floor put it back on the settings sidebar, and pressing one switch threw
            // the viewer out of the screen and back to Account. A save in flight is guarded inside
            // the handler instead, where it costs no focus.
            .clickable {
                if (saving) return@clickable
                val next = !visualEnabled
                visualEnabled = next
                saving = true
                onToggle(next) { ok ->
                    saving = false
                    if (!ok) visualEnabled = !next
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // A filled tick for on, an empty well for off: readable from a sofa, and it does not rely
        // on colour alone.
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    if (visualEnabled) MaterialTheme.colorScheme.primary else Color(0x14FFFFFF),
                    RoundedCornerShape(6.dp),
                )
                .border(
                    1.dp,
                    if (visualEnabled) MaterialTheme.colorScheme.primary else Color(0x33FFFFFF),
                    RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (visualEnabled) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = Color(0xFF0B0B0B),
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        Column(Modifier.width(0.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = option.title,
                color = if (visualEnabled) Color.White else Color.White.copy(alpha = 0.62f),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = option.subtitleArg
                    ?.let { stringResource(option.subtitleRes, it) }
                    ?: stringResource(option.subtitleRes),
                color = Color.White.copy(alpha = 0.45f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = if (saving) "Saving" else if (visualEnabled) "On" else "Off",
            color = when {
                saving -> Color.White.copy(alpha = 0.5f)
                visualEnabled -> MaterialTheme.colorScheme.primary
                else -> Color.White.copy(alpha = 0.5f)
            },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        )
    }
}
