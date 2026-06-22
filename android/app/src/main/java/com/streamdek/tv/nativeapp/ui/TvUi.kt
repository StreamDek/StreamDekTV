package com.streamdek.tv.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

val AppCardShape = RoundedCornerShape(20.dp)
val AppPillShape = RoundedCornerShape(999.dp)

private val CurrentTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

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
fun CurrentTimePill(
    modifier: Modifier = Modifier,
) {
    var currentTime by remember {
        mutableStateOf(LocalTime.now().format(CurrentTimeFormatter))
    }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now().format(CurrentTimeFormatter)
            delay(30_000)
        }
    }

    Text(
        text = currentTime,
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.92f),
    )
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
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp)),
        )
    }
}

