package com.streamdek.tv.nativeapp.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The Plex chevron, redrawn for StreamDek's navigation.
 *
 * A single-colour outline-weight glyph, so it sits in the rail beside Home and Search and takes the
 * same tint and focus treatment rather than arriving as a gold badge among white line icons. The
 * full-colour badge (`R.drawable.plex_logo`) is for places that name the service - Settings, the
 * Plex page's header - where the brand is the point.
 *
 * Rounded at the joins to match the soft, rounded language of the rest of the interface. Same
 * geometry as `res/drawable/ic_plex_chevron.xml`.
 */
internal object PlexIcons {
    val Chevron: ImageVector by lazy {
        ImageVector.Builder(
            name = "PlexChevron",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(fill = SolidColor(Color.White)) {
            moveTo(6.2f, 3.2f)
            lineTo(11.9f, 3.2f)
            quadTo(12.4f, 3.2f, 12.7f, 3.6f)
            lineTo(18.6f, 11.4f)
            quadTo(19.05f, 12f, 18.6f, 12.6f)
            lineTo(12.7f, 20.4f)
            quadTo(12.4f, 20.8f, 11.9f, 20.8f)
            lineTo(6.2f, 20.8f)
            quadTo(5.6f, 20.8f, 6f, 20.3f)
            lineTo(12.1f, 12f)
            lineTo(6f, 3.7f)
            quadTo(5.6f, 3.2f, 6.2f, 3.2f)
            close()
        }.build()
    }
}
