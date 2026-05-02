package com.streamdek.tv.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val AppCardShape = RoundedCornerShape(24.dp)
val AppPillShape = RoundedCornerShape(999.dp)

fun formatPlaybackClock(seconds: Double?): String {
    val safe = (seconds ?: 0.0).toInt().coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val secs = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%d:%02d".format(minutes, secs)
    }
}

@Composable
fun ProgressMeter(
    progress: Double?,
    modifier: Modifier = Modifier,
) {
    val ratio = ((progress ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
    Box(
        modifier = modifier
            .background(Color(0x40FFFFFF), RoundedCornerShape(999.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(ratio)
                .height(4.dp)
                .background(Color(0xFFF0BA66), RoundedCornerShape(999.dp)),
        )
    }
}
