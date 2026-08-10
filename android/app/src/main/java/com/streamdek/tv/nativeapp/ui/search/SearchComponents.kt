package com.streamdek.tv.nativeapp.ui.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.TvMotion
import com.streamdek.tv.nativeapp.ui.TvSpacing

internal val SearchInset = TvSpacing.ScreenHorizontal

/** Fixed card geometry, matching Home. Cards never resize on focus. */
internal val SearchCardWidth = 132.dp
internal val SearchCardHeight = 198.dp

/**
 * A pill that can be selected. Used for every control on this screen — scope, content type, genre,
 * year, recent queries — so one shape and one focus treatment covers the lot.
 *
 * The screen this replaces put these in a left column that erased its own contents whenever focus
 * moved to the grid, and behind modal dialogs for genre and year. Both meant the viewer could not
 * see what was currently applied while looking at the results it produced.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SearchChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    leading: String? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    val highContrast = LocalTvExperienceSettings.current.highContrast
    Card(
        onClick = onClick,
        modifier = modifier.height(40.dp).onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(AppPillShape),
        colors = CardDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
            } else {
                Color.White.copy(alpha = 0.07f)
            },
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
            pressedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
        ),
        border = CardDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                BorderStroke(if (highContrast) 3.dp else 2.dp, MaterialTheme.colorScheme.primary),
                shape = AppPillShape,
            ),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = TvMotion.focusScale()),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leading?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The options for one filter, revealed in place directly under the chip that opened it.
 *
 * Genre and year used to open centred modal dialogs. A dialog on a TV costs a focus teleport out
 * and back, hides the results the filter is about to change, and needs a dismiss press even after
 * you have chosen. Expanding in place costs none of that.
 */
@Composable
internal fun SearchFilterTray(
    options: List<SearchFilterOption>,
    firstOptionRequester: FocusRequester,
    onDismiss: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = SearchInset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(options, key = { it.label }) { option ->
            SearchChip(
                label = option.label,
                selected = option.selected,
                modifier = if (options.firstOrNull() === option) {
                    Modifier.focusRequester(firstOptionRequester)
                } else {
                    Modifier
                },
                onClick = {
                    option.onSelect()
                    onDismiss()
                },
            )
        }
    }
}

internal data class SearchFilterOption(
    val label: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
)

/**
 * The query field.
 *
 * Kept deliberately loud about its own state. The field it replaces was a read-only text box that
 * silently became editable after an OK press, so there was no way to tell from the screen whether
 * a keypress would type a letter or move the highlight. This one says which mode it is in.
 */
@Composable
internal fun SearchQueryDisplay(
    query: String,
    editing: Boolean,
    focused: Boolean,
    modifier: Modifier = Modifier,
) {
    val hint = when {
        editing -> "Typing — press Back when done"
        focused -> "Press OK to type"
        query.isBlank() -> "Search films, series and channels"
        else -> null
    }
    Box(modifier) {
        androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = query.ifBlank { "Search" },
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                color = if (query.isBlank()) {
                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.onBackground
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (editing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** Row label above the grid: what is being shown, and how much of it. */
@Composable
internal fun SearchResultsHeading(title: String, detail: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = SearchInset),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
        )
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}
