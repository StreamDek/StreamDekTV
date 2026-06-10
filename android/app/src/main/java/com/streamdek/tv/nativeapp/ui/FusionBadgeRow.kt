package com.streamdek.tv.nativeapp.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.streamdek.tv.nativeapp.data.FUSION_BADGE_LANGUAGE_GROUP_ID
import com.streamdek.tv.nativeapp.data.FusionBadgeFilter

@Composable
fun FusionBadgeRow(
    badges: List<FusionBadgeFilter>,
    modifier: Modifier = Modifier,
    badgeHeight: Dp = 28.dp,
) {
    if (badges.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        badges.forEach { badge ->
            val isFlag = badge.groupId == FUSION_BADGE_LANGUAGE_GROUP_ID
            AsyncImage(
                model = badge.imageURL,
                contentDescription = badge.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .height(badgeHeight)
                    .width(if (isFlag) badgeHeight else badgeHeight * 2.2f),
            )
        }
    }
}
