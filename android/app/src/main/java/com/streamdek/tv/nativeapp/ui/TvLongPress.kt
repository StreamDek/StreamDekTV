package com.streamdek.tv.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Opens a card's action menu from either the remote Menu key or a held Select/OK key.
 * The matching key-up is consumed after a long press so it cannot also open the card.
 */
fun Modifier.tvCardLongPress(onLongPress: () -> Unit): Modifier = composed {
    val scope = rememberCoroutineScope()
    var pressJob by remember { mutableStateOf<Job?>(null) }
    var longPressHandled by remember { mutableStateOf(false) }
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
                if (selectDownCount > 1 && !longPressHandled) {
                    pressJob?.cancel()
                    pressJob = null
                    longPressHandled = true
                    onLongPress()
                    true
                } else {
                    if (pressJob == null && !longPressHandled) {
                        pressJob = scope.launch {
                            delay(500L)
                            longPressHandled = true
                            onLongPress()
                        }
                    }
                    false
                }
            }
            isSelect && event.type == KeyEventType.KeyUp -> {
                pressJob?.cancel()
                pressJob = null
                selectDownCount = 0
                val consume = longPressHandled
                longPressHandled = false
                consume
            }
            else -> false
        }
    }
}