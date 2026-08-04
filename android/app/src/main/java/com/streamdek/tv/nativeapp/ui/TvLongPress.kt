package com.streamdek.tv.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

private const val TV_LONG_PRESS_THRESHOLD_MS = 500L

/**
 * Opens a card's action menu from either the remote Menu key or a held Select/OK key.
 *
 * The long-press callback is only ever invoked from the matching key-up (i.e. once the
 * remote button is released), never while it is still held down. Firing it mid-hold used to
 * let callbacks that remove the focused item (e.g. un-favouriting) race the still-pending
 * key-up dispatch, which Compose's focus system can crash on
 * ("Dispatching intercepted soft keyboard event while focus system is invalidated").
 * Resolving on key-up keeps any resulting structural change inside that same event's dispatch.
 */
fun Modifier.tvCardLongPress(onLongPress: () -> Unit): Modifier = composed {
    var pressStartedAtMs by remember { mutableStateOf(0L) }
    var longPressArmed by remember { mutableStateOf(false) }
    var selectDownCount by remember { mutableStateOf(0) }
    onPreviewKeyEvent { event ->
        val isSelect = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
        when {
            event.key == Key.Menu && event.type == KeyEventType.KeyUp -> {
                onLongPress()
                true
            }
            isSelect && event.type == KeyEventType.KeyDown -> {
                selectDownCount += 1
                if (selectDownCount == 1) {
                    pressStartedAtMs = System.currentTimeMillis()
                    longPressArmed = false
                } else if (!longPressArmed && System.currentTimeMillis() - pressStartedAtMs >= TV_LONG_PRESS_THRESHOLD_MS) {
                    longPressArmed = true
                }
                longPressArmed
            }
            isSelect && event.type == KeyEventType.KeyUp -> {
                selectDownCount = 0
                val wasArmed = longPressArmed
                longPressArmed = false
                if (wasArmed) onLongPress()
                wasArmed
            }
            else -> false
        }
    }
}
