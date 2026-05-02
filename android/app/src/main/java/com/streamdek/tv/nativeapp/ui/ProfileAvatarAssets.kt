package com.streamdek.tv.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage

private val avatarAssetPaths = listOf(
    "file:///android_asset/avatars/av1.png",
    "file:///android_asset/avatars/av2.png",
    "file:///android_asset/avatars/av3.png",
    "file:///android_asset/avatars/av4.png",
    "file:///android_asset/avatars/av5.png",
    "file:///android_asset/avatars/av6.png",
    "file:///android_asset/avatars/av7.png",
    "file:///android_asset/avatars/av8.png",
    "file:///android_asset/avatars/av9.png",
    "file:///android_asset/avatars/av10.png",
    "file:///android_asset/avatars/av11.png",
    "file:///android_asset/avatars/av12.png",
)

fun profileAvatarAssetPath(avatarIndex: Int): String = avatarAssetPaths[avatarIndex.mod(avatarAssetPaths.size)]

@Composable
fun ProfileAvatarCircle(
    avatarIndex: Int,
    fallbackLabel: String,
    size: Dp = 34.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF15181D)),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = profileAvatarAssetPath(avatarIndex),
            contentDescription = fallbackLabel,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Crop,
        )
        if (fallbackLabel.isNotBlank()) {
            Text(
                text = fallbackLabel.take(1).uppercase(),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
                color = Color.White.copy(alpha = 0.0f),
            )
        }
    }
}
