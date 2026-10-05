package com.streamdek.tv.nativeapp.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * StreamDek's own icons for the navigation rail: the same drawings the phone's bottom bar uses, so
 * the two apps read as one product.
 *
 * One family, on a 24-unit grid with the artwork inside roughly 3 to 21: a 1.9 round-capped,
 * round-joined outline, a tonal fill inside any closed shape, and one solid detail where the shape
 * has one (a door, a play mark, a hub). Each destination has two drawings. At rest it is the
 * outline; highlighted - focused on the rail, or the page that is open - the silhouette fills and
 * the detail is cut out of it. The outer shape never changes, so nothing shifts as focus moves.
 *
 * No colour is baked in. Every path is one ink, replaced by the rail's own `Icon(tint = ...)`, and
 * the lighter parts are alpha on that ink, which a tint preserves.
 *
 * The media server entry is not here: the Plex and Jellyfin marks are those services' own.
 */
internal object StreamDekNavIcons {
    private val Ink = SolidColor(Color.Black)

    /** Home, at rest: a bold outline with a tonal interior and a solid door. */
    val HomeOutline: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekNavHomeOutline", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(4.5f, 12f)
                quadTo(4.5f, 10.4f, 5.71f, 9.35f)
                lineTo(10.56f, 5.15f)
                quadTo(12f, 3.9f, 13.44f, 5.15f)
                lineTo(18.29f, 9.35f)
                quadTo(19.5f, 10.4f, 19.5f, 12f)
                lineTo(19.5f, 17.4f)
                quadTo(19.5f, 20f, 16.9f, 20f)
                lineTo(7.1f, 20f)
                quadTo(4.5f, 20f, 4.5f, 17.4f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4.5f, 12f)
                quadTo(4.5f, 10.4f, 5.71f, 9.35f)
                lineTo(10.56f, 5.15f)
                quadTo(12f, 3.9f, 13.44f, 5.15f)
                lineTo(18.29f, 9.35f)
                quadTo(19.5f, 10.4f, 19.5f, 12f)
                lineTo(19.5f, 17.4f)
                quadTo(19.5f, 20f, 16.9f, 20f)
                lineTo(7.1f, 20f)
                quadTo(4.5f, 20f, 4.5f, 17.4f)
                close()
            }
            path(fill = Ink) {
                moveTo(9.5f, 20f)
                lineTo(9.5f, 15.6f)
                quadTo(9.5f, 13.9f, 11.2f, 13.9f)
                lineTo(12.8f, 13.9f)
                quadTo(14.5f, 13.9f, 14.5f, 15.6f)
                lineTo(14.5f, 20f)
                close()
            }
        }.build()
    }

    /** Home, highlighted: the same silhouette filled, with the door cut out of it. */
    val HomeFilled: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekNavHomeFilled", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink, pathFillType = PathFillType.EvenOdd) {
                moveTo(4.5f, 12f)
                quadTo(4.5f, 10.4f, 5.71f, 9.35f)
                lineTo(10.56f, 5.15f)
                quadTo(12f, 3.9f, 13.44f, 5.15f)
                lineTo(18.29f, 9.35f)
                quadTo(19.5f, 10.4f, 19.5f, 12f)
                lineTo(19.5f, 17.4f)
                quadTo(19.5f, 20f, 16.9f, 20f)
                lineTo(7.1f, 20f)
                quadTo(4.5f, 20f, 4.5f, 17.4f)
                close()
                moveTo(9.5f, 20f)
                lineTo(9.5f, 15.6f)
                quadTo(9.5f, 13.9f, 11.2f, 13.9f)
                lineTo(12.8f, 13.9f)
                quadTo(14.5f, 13.9f, 14.5f, 15.6f)
                lineTo(14.5f, 20f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4.5f, 12f)
                quadTo(4.5f, 10.4f, 5.71f, 9.35f)
                lineTo(10.56f, 5.15f)
                quadTo(12f, 3.9f, 13.44f, 5.15f)
                lineTo(18.29f, 9.35f)
                quadTo(19.5f, 10.4f, 19.5f, 12f)
                lineTo(19.5f, 17.4f)
                quadTo(19.5f, 20f, 16.9f, 20f)
                lineTo(7.1f, 20f)
                quadTo(4.5f, 20f, 4.5f, 17.4f)
                close()
            }
        }.build()
    }

    /** Search, at rest: a ring lens over a tonal fill, on a round-capped handle. */
    val SearchOutline: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekNavSearchOutline", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(10.6f, 4.5f)
                curveTo(13.97f, 4.5f, 16.7f, 7.23f, 16.7f, 10.6f)
                curveTo(16.7f, 13.97f, 13.97f, 16.7f, 10.6f, 16.7f)
                curveTo(7.23f, 16.7f, 4.5f, 13.97f, 4.5f, 10.6f)
                curveTo(4.5f, 7.23f, 7.23f, 4.5f, 10.6f, 4.5f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(10.6f, 4.5f)
                curveTo(13.97f, 4.5f, 16.7f, 7.23f, 16.7f, 10.6f)
                curveTo(16.7f, 13.97f, 13.97f, 16.7f, 10.6f, 16.7f)
                curveTo(7.23f, 16.7f, 4.5f, 13.97f, 4.5f, 10.6f)
                curveTo(4.5f, 7.23f, 7.23f, 4.5f, 10.6f, 4.5f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 2.3f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15.3f, 15.3f)
                lineTo(19.7f, 19.7f)
            }
        }.build()
    }

    /** Search, highlighted: the lens filled. */
    val SearchFilled: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekNavSearchFilled", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink) {
                moveTo(10.6f, 4.5f)
                curveTo(13.97f, 4.5f, 16.7f, 7.23f, 16.7f, 10.6f)
                curveTo(16.7f, 13.97f, 13.97f, 16.7f, 10.6f, 16.7f)
                curveTo(7.23f, 16.7f, 4.5f, 13.97f, 4.5f, 10.6f)
                curveTo(4.5f, 7.23f, 7.23f, 4.5f, 10.6f, 4.5f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(10.6f, 4.5f)
                curveTo(13.97f, 4.5f, 16.7f, 7.23f, 16.7f, 10.6f)
                curveTo(16.7f, 13.97f, 13.97f, 16.7f, 10.6f, 16.7f)
                curveTo(7.23f, 16.7f, 4.5f, 13.97f, 4.5f, 10.6f)
                curveTo(4.5f, 7.23f, 7.23f, 4.5f, 10.6f, 4.5f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 2.3f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15.3f, 15.3f)
                lineTo(19.7f, 19.7f)
            }
        }.build()
    }

    /** Live TV, at rest: a set with its aerial, in outline over a tonal fill, its play mark solid. */
    val LiveOutline: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekNavLiveOutline", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(6f, 7f)
                lineTo(18f, 7f)
                quadTo(20.5f, 7f, 20.5f, 9.5f)
                lineTo(20.5f, 16.8f)
                quadTo(20.5f, 19.3f, 18f, 19.3f)
                lineTo(6f, 19.3f)
                quadTo(3.5f, 19.3f, 3.5f, 16.8f)
                lineTo(3.5f, 9.5f)
                quadTo(3.5f, 7f, 6f, 7f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6f, 7f)
                lineTo(18f, 7f)
                quadTo(20.5f, 7f, 20.5f, 9.5f)
                lineTo(20.5f, 16.8f)
                quadTo(20.5f, 19.3f, 18f, 19.3f)
                lineTo(6f, 19.3f)
                quadTo(3.5f, 19.3f, 3.5f, 16.8f)
                lineTo(3.5f, 9.5f)
                quadTo(3.5f, 7f, 6f, 7f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8.8f, 3.4f)
                lineTo(12f, 7f)
                lineTo(15.2f, 3.4f)
            }
            path(fill = Ink) {
                moveTo(10.3f, 11.13f)
                quadTo(10.3f, 10.29f, 11.22f, 10.88f)
                lineTo(14.44f, 12.75f)
                quadTo(15.08f, 13.2f, 14.44f, 13.65f)
                lineTo(11.22f, 15.52f)
                quadTo(10.3f, 16.11f, 10.3f, 15.27f)
                close()
            }
        }.build()
    }

    /** Live TV, highlighted: the set filled, with the play mark cut out of it. */
    val LiveFilled: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekNavLiveFilled", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink, pathFillType = PathFillType.EvenOdd) {
                moveTo(6f, 7f)
                lineTo(18f, 7f)
                quadTo(20.5f, 7f, 20.5f, 9.5f)
                lineTo(20.5f, 16.8f)
                quadTo(20.5f, 19.3f, 18f, 19.3f)
                lineTo(6f, 19.3f)
                quadTo(3.5f, 19.3f, 3.5f, 16.8f)
                lineTo(3.5f, 9.5f)
                quadTo(3.5f, 7f, 6f, 7f)
                close()
                moveTo(10.3f, 11.13f)
                quadTo(10.3f, 10.29f, 11.22f, 10.88f)
                lineTo(14.44f, 12.75f)
                quadTo(15.08f, 13.2f, 14.44f, 13.65f)
                lineTo(11.22f, 15.52f)
                quadTo(10.3f, 16.11f, 10.3f, 15.27f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6f, 7f)
                lineTo(18f, 7f)
                quadTo(20.5f, 7f, 20.5f, 9.5f)
                lineTo(20.5f, 16.8f)
                quadTo(20.5f, 19.3f, 18f, 19.3f)
                lineTo(6f, 19.3f)
                quadTo(3.5f, 19.3f, 3.5f, 16.8f)
                lineTo(3.5f, 9.5f)
                quadTo(3.5f, 7f, 6f, 7f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8.8f, 3.4f)
                lineTo(12f, 7f)
                lineTo(15.2f, 3.4f)
            }
        }.build()
    }

    /** Fuse, at rest: three sources joined at a solid hub. */
    val FuseOutline: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekNavFuseOutline", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = Ink, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 9.6f)
                lineTo(12f, 7.6f)
                moveTo(14.51f, 13.95f)
                lineTo(16.24f, 14.95f)
                moveTo(9.49f, 13.95f)
                lineTo(7.76f, 14.95f)
            }
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(12f, 3f)
                curveTo(13.22f, 3f, 14.2f, 3.98f, 14.2f, 5.2f)
                curveTo(14.2f, 6.42f, 13.22f, 7.4f, 12f, 7.4f)
                curveTo(10.78f, 7.4f, 9.8f, 6.42f, 9.8f, 5.2f)
                curveTo(9.8f, 3.98f, 10.78f, 3f, 12f, 3f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3f)
                curveTo(13.22f, 3f, 14.2f, 3.98f, 14.2f, 5.2f)
                curveTo(14.2f, 6.42f, 13.22f, 7.4f, 12f, 7.4f)
                curveTo(10.78f, 7.4f, 9.8f, 6.42f, 9.8f, 5.2f)
                curveTo(9.8f, 3.98f, 10.78f, 3f, 12f, 3f)
                close()
            }
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(18.32f, 13.95f)
                curveTo(19.54f, 13.95f, 20.52f, 14.93f, 20.52f, 16.15f)
                curveTo(20.52f, 17.37f, 19.54f, 18.35f, 18.32f, 18.35f)
                curveTo(17.11f, 18.35f, 16.12f, 17.37f, 16.12f, 16.15f)
                curveTo(16.12f, 14.93f, 17.11f, 13.95f, 18.32f, 13.95f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(18.32f, 13.95f)
                curveTo(19.54f, 13.95f, 20.52f, 14.93f, 20.52f, 16.15f)
                curveTo(20.52f, 17.37f, 19.54f, 18.35f, 18.32f, 18.35f)
                curveTo(17.11f, 18.35f, 16.12f, 17.37f, 16.12f, 16.15f)
                curveTo(16.12f, 14.93f, 17.11f, 13.95f, 18.32f, 13.95f)
                close()
            }
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(5.68f, 13.95f)
                curveTo(6.89f, 13.95f, 7.88f, 14.93f, 7.88f, 16.15f)
                curveTo(7.88f, 17.37f, 6.89f, 18.35f, 5.68f, 18.35f)
                curveTo(4.46f, 18.35f, 3.48f, 17.37f, 3.48f, 16.15f)
                curveTo(3.48f, 14.93f, 4.46f, 13.95f, 5.68f, 13.95f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5.68f, 13.95f)
                curveTo(6.89f, 13.95f, 7.88f, 14.93f, 7.88f, 16.15f)
                curveTo(7.88f, 17.37f, 6.89f, 18.35f, 5.68f, 18.35f)
                curveTo(4.46f, 18.35f, 3.48f, 17.37f, 3.48f, 16.15f)
                curveTo(3.48f, 14.93f, 4.46f, 13.95f, 5.68f, 13.95f)
                close()
            }
            path(fill = Ink) {
                moveTo(12f, 9.8f)
                curveTo(13.49f, 9.8f, 14.7f, 11.01f, 14.7f, 12.5f)
                curveTo(14.7f, 13.99f, 13.49f, 15.2f, 12f, 15.2f)
                curveTo(10.51f, 15.2f, 9.3f, 13.99f, 9.3f, 12.5f)
                curveTo(9.3f, 11.01f, 10.51f, 9.8f, 12f, 9.8f)
                close()
            }
        }.build()
    }

    /** Fuse, highlighted: the sources filled too. */
    val FuseFilled: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekNavFuseFilled", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = Ink, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 9.3f)
                lineTo(12f, 7.6f)
                moveTo(14.77f, 14.1f)
                lineTo(16.24f, 14.95f)
                moveTo(9.23f, 14.1f)
                lineTo(7.76f, 14.95f)
            }
            path(fill = Ink) {
                moveTo(12f, 3f)
                curveTo(13.22f, 3f, 14.2f, 3.98f, 14.2f, 5.2f)
                curveTo(14.2f, 6.42f, 13.22f, 7.4f, 12f, 7.4f)
                curveTo(10.78f, 7.4f, 9.8f, 6.42f, 9.8f, 5.2f)
                curveTo(9.8f, 3.98f, 10.78f, 3f, 12f, 3f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3f)
                curveTo(13.22f, 3f, 14.2f, 3.98f, 14.2f, 5.2f)
                curveTo(14.2f, 6.42f, 13.22f, 7.4f, 12f, 7.4f)
                curveTo(10.78f, 7.4f, 9.8f, 6.42f, 9.8f, 5.2f)
                curveTo(9.8f, 3.98f, 10.78f, 3f, 12f, 3f)
                close()
            }
            path(fill = Ink) {
                moveTo(18.32f, 13.95f)
                curveTo(19.54f, 13.95f, 20.52f, 14.93f, 20.52f, 16.15f)
                curveTo(20.52f, 17.37f, 19.54f, 18.35f, 18.32f, 18.35f)
                curveTo(17.11f, 18.35f, 16.12f, 17.37f, 16.12f, 16.15f)
                curveTo(16.12f, 14.93f, 17.11f, 13.95f, 18.32f, 13.95f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(18.32f, 13.95f)
                curveTo(19.54f, 13.95f, 20.52f, 14.93f, 20.52f, 16.15f)
                curveTo(20.52f, 17.37f, 19.54f, 18.35f, 18.32f, 18.35f)
                curveTo(17.11f, 18.35f, 16.12f, 17.37f, 16.12f, 16.15f)
                curveTo(16.12f, 14.93f, 17.11f, 13.95f, 18.32f, 13.95f)
                close()
            }
            path(fill = Ink) {
                moveTo(5.68f, 13.95f)
                curveTo(6.89f, 13.95f, 7.88f, 14.93f, 7.88f, 16.15f)
                curveTo(7.88f, 17.37f, 6.89f, 18.35f, 5.68f, 18.35f)
                curveTo(4.46f, 18.35f, 3.48f, 17.37f, 3.48f, 16.15f)
                curveTo(3.48f, 14.93f, 4.46f, 13.95f, 5.68f, 13.95f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5.68f, 13.95f)
                curveTo(6.89f, 13.95f, 7.88f, 14.93f, 7.88f, 16.15f)
                curveTo(7.88f, 17.37f, 6.89f, 18.35f, 5.68f, 18.35f)
                curveTo(4.46f, 18.35f, 3.48f, 17.37f, 3.48f, 16.15f)
                curveTo(3.48f, 14.93f, 4.46f, 13.95f, 5.68f, 13.95f)
                close()
            }
            path(fill = Ink) {
                moveTo(12f, 9.4f)
                curveTo(13.71f, 9.4f, 15.1f, 10.79f, 15.1f, 12.5f)
                curveTo(15.1f, 14.21f, 13.71f, 15.6f, 12f, 15.6f)
                curveTo(10.29f, 15.6f, 8.9f, 14.21f, 8.9f, 12.5f)
                curveTo(8.9f, 10.79f, 10.29f, 9.4f, 12f, 9.4f)
                close()
            }
        }.build()
    }

    /** Library, and a series' episodes: the media tray in outline over a tonal fill, its play mark solid. */
    val LibraryOutline: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekNavLibraryOutline", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = Ink, strokeAlpha = 0.45f, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8.9f, 3.5f)
                quadTo(9.1f, 2.95f, 9.9f, 2.95f)
                lineTo(14.1f, 2.95f)
                quadTo(14.9f, 2.95f, 15.1f, 3.5f)
            }
            path(stroke = Ink, strokeAlpha = 0.7f, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.5f, 6.6f)
                quadTo(6.7f, 6f, 7.6f, 6f)
                lineTo(16.4f, 6f)
                quadTo(17.3f, 6f, 17.5f, 6.6f)
            }
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(6.6f, 9.3f)
                lineTo(17.4f, 9.3f)
                quadTo(20.6f, 9.3f, 20.3f, 12.1f)
                lineTo(19.7f, 17.5f)
                quadTo(19.4f, 20f, 16.8f, 20f)
                lineTo(7.2f, 20f)
                quadTo(4.6f, 20f, 4.3f, 17.5f)
                lineTo(3.7f, 12.1f)
                quadTo(3.4f, 9.3f, 6.6f, 9.3f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.6f, 9.3f)
                lineTo(17.4f, 9.3f)
                quadTo(20.6f, 9.3f, 20.3f, 12.1f)
                lineTo(19.7f, 17.5f)
                quadTo(19.4f, 20f, 16.8f, 20f)
                lineTo(7.2f, 20f)
                quadTo(4.6f, 20f, 4.3f, 17.5f)
                lineTo(3.7f, 12.1f)
                quadTo(3.4f, 9.3f, 6.6f, 9.3f)
                close()
            }
            path(fill = Ink) {
                moveTo(10.3f, 12.75f)
                quadTo(10.3f, 11.6f, 11.3f, 12.17f)
                lineTo(14.55f, 14.05f)
                quadTo(15.4f, 14.6f, 14.55f, 15.15f)
                lineTo(11.3f, 17.03f)
                quadTo(10.3f, 17.6f, 10.3f, 16.45f)
                close()
            }
        }.build()
    }

    /** Library, highlighted: the tray filled, with the play mark cut out of it. */
    val LibraryFilled: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekNavLibraryFilled", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = Ink, strokeAlpha = 0.45f, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8.9f, 3.5f)
                quadTo(9.1f, 2.95f, 9.9f, 2.95f)
                lineTo(14.1f, 2.95f)
                quadTo(14.9f, 2.95f, 15.1f, 3.5f)
            }
            path(stroke = Ink, strokeAlpha = 0.7f, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.5f, 6.6f)
                quadTo(6.7f, 6f, 7.6f, 6f)
                lineTo(16.4f, 6f)
                quadTo(17.3f, 6f, 17.5f, 6.6f)
            }
            path(fill = Ink, pathFillType = PathFillType.EvenOdd) {
                moveTo(6.6f, 9.3f)
                lineTo(17.4f, 9.3f)
                quadTo(20.6f, 9.3f, 20.3f, 12.1f)
                lineTo(19.7f, 17.5f)
                quadTo(19.4f, 20f, 16.8f, 20f)
                lineTo(7.2f, 20f)
                quadTo(4.6f, 20f, 4.3f, 17.5f)
                lineTo(3.7f, 12.1f)
                quadTo(3.4f, 9.3f, 6.6f, 9.3f)
                close()
                moveTo(10.3f, 12.75f)
                quadTo(10.3f, 11.6f, 11.3f, 12.17f)
                lineTo(14.55f, 14.05f)
                quadTo(15.4f, 14.6f, 14.55f, 15.15f)
                lineTo(11.3f, 17.03f)
                quadTo(10.3f, 17.6f, 10.3f, 16.45f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.6f, 9.3f)
                lineTo(17.4f, 9.3f)
                quadTo(20.6f, 9.3f, 20.3f, 12.1f)
                lineTo(19.7f, 17.5f)
                quadTo(19.4f, 20f, 16.8f, 20f)
                lineTo(7.2f, 20f)
                quadTo(4.6f, 20f, 4.3f, 17.5f)
                lineTo(3.7f, 12.1f)
                quadTo(3.4f, 9.3f, 6.6f, 9.3f)
                close()
            }
        }.build()
    }

    /** Not on the watchlist: a round-shouldered bookmark in outline over a tonal fill. */
    val WatchlistOutline: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekNavWatchlistOutline", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(6.5f, 6.5f)
                quadTo(6.5f, 3.9f, 9.1f, 3.9f)
                lineTo(14.9f, 3.9f)
                quadTo(17.5f, 3.9f, 17.5f, 6.5f)
                lineTo(17.5f, 18.7f)
                quadTo(17.5f, 20.5f, 16f, 19.5f)
                lineTo(12.8f, 17.35f)
                quadTo(12f, 16.8f, 11.2f, 17.35f)
                lineTo(8f, 19.5f)
                quadTo(6.5f, 20.5f, 6.5f, 18.7f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.5f, 6.5f)
                quadTo(6.5f, 3.9f, 9.1f, 3.9f)
                lineTo(14.9f, 3.9f)
                quadTo(17.5f, 3.9f, 17.5f, 6.5f)
                lineTo(17.5f, 18.7f)
                quadTo(17.5f, 20.5f, 16f, 19.5f)
                lineTo(12.8f, 17.35f)
                quadTo(12f, 16.8f, 11.2f, 17.35f)
                lineTo(8f, 19.5f)
                quadTo(6.5f, 20.5f, 6.5f, 18.7f)
                close()
            }
        }.build()
    }

    /** On the watchlist: the same bookmark, filled. */
    val WatchlistFilled: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekNavWatchlistFilled", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink) {
                moveTo(6.5f, 6.5f)
                quadTo(6.5f, 3.9f, 9.1f, 3.9f)
                lineTo(14.9f, 3.9f)
                quadTo(17.5f, 3.9f, 17.5f, 6.5f)
                lineTo(17.5f, 18.7f)
                quadTo(17.5f, 20.5f, 16f, 19.5f)
                lineTo(12.8f, 17.35f)
                quadTo(12f, 16.8f, 11.2f, 17.35f)
                lineTo(8f, 19.5f)
                quadTo(6.5f, 20.5f, 6.5f, 18.7f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.5f, 6.5f)
                quadTo(6.5f, 3.9f, 9.1f, 3.9f)
                lineTo(14.9f, 3.9f)
                quadTo(17.5f, 3.9f, 17.5f, 6.5f)
                lineTo(17.5f, 18.7f)
                quadTo(17.5f, 20.5f, 16f, 19.5f)
                lineTo(12.8f, 17.35f)
                quadTo(12f, 16.8f, 11.2f, 17.35f)
                lineTo(8f, 19.5f)
                quadTo(6.5f, 20.5f, 6.5f, 18.7f)
                close()
            }
        }.build()
    }
}

/**
 * The player's icons, drawn in the same family as [StreamDekNavIcons] and the phone's player.
 *
 * Pairs that say on and off - captions - keep one silhouette and change inside it: the off drawing
 * is the same shape dimmed, with a stroke through it. Marks that are pure action or direction -
 * play, pause, next, the chevrons - are solid or stroke only, because there is no interior to tone.
 *
 * The Episodes control uses [StreamDekNavIcons.LibraryOutline], the media tray, and the watchlist
 * control the bookmark from the same set: each is the same idea as its destination.
 */
internal object StreamDekPlayerIcons {
    private val Ink = SolidColor(Color.Black)

    /** Play: a soft-cornered triangle, optically centred in the box. */
    val Play: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekPlayerPlay", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink) {
                moveTo(7.2f, 6.23f)
                quadTo(7.2f, 3.89f, 9.72f, 5.53f)
                lineTo(18.54f, 10.75f)
                quadTo(20.3f, 12f, 18.54f, 13.25f)
                lineTo(9.72f, 18.47f)
                quadTo(7.2f, 20.11f, 7.2f, 17.77f)
                close()
            }
        }.build()
    }

    /** Pause: two round-ended bars. */
    val Pause: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekPlayerPause", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink) {
                moveTo(8f, 4.8f)
                lineTo(8.7f, 4.8f)
                quadTo(10.4f, 4.8f, 10.4f, 6.5f)
                lineTo(10.4f, 17.5f)
                quadTo(10.4f, 19.2f, 8.7f, 19.2f)
                lineTo(8f, 19.2f)
                quadTo(6.3f, 19.2f, 6.3f, 17.5f)
                lineTo(6.3f, 6.5f)
                quadTo(6.3f, 4.8f, 8f, 4.8f)
                close()
            }
            path(fill = Ink) {
                moveTo(15.3f, 4.8f)
                lineTo(16f, 4.8f)
                quadTo(17.7f, 4.8f, 17.7f, 6.5f)
                lineTo(17.7f, 17.5f)
                quadTo(17.7f, 19.2f, 16f, 19.2f)
                lineTo(15.3f, 19.2f)
                quadTo(13.6f, 19.2f, 13.6f, 17.5f)
                lineTo(13.6f, 6.5f)
                quadTo(13.6f, 4.8f, 15.3f, 4.8f)
                close()
            }
        }.build()
    }

    /** Next episode: play, against a bar. */
    val Next: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekPlayerNext", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink) {
                moveTo(5f, 7.12f)
                quadTo(5f, 5.14f, 7.12f, 6.52f)
                lineTo(14.54f, 10.94f)
                quadTo(16.02f, 12f, 14.54f, 13.06f)
                lineTo(7.12f, 17.48f)
                quadTo(5f, 18.86f, 5f, 16.88f)
                close()
            }
            path(fill = Ink) {
                moveTo(17.85f, 5.6f)
                lineTo(17.85f, 5.6f)
                quadTo(19.2f, 5.6f, 19.2f, 6.95f)
                lineTo(19.2f, 17.05f)
                quadTo(19.2f, 18.4f, 17.85f, 18.4f)
                lineTo(17.85f, 18.4f)
                quadTo(16.5f, 18.4f, 16.5f, 17.05f)
                lineTo(16.5f, 6.95f)
                quadTo(16.5f, 5.6f, 17.85f, 5.6f)
                close()
            }
        }.build()
    }

    /** Audio tracks, and the volume gesture. */
    val Audio: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekPlayerAudio", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(4.6f, 9.7f)
                lineTo(7.4f, 9.7f)
                lineTo(11.3f, 6.4f)
                quadTo(12.6f, 5.4f, 12.6f, 7f)
                lineTo(12.6f, 17f)
                quadTo(12.6f, 18.6f, 11.3f, 17.6f)
                lineTo(7.4f, 14.3f)
                lineTo(4.6f, 14.3f)
                quadTo(3.6f, 14.3f, 3.6f, 13.3f)
                lineTo(3.6f, 10.7f)
                quadTo(3.6f, 9.7f, 4.6f, 9.7f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4.6f, 9.7f)
                lineTo(7.4f, 9.7f)
                lineTo(11.3f, 6.4f)
                quadTo(12.6f, 5.4f, 12.6f, 7f)
                lineTo(12.6f, 17f)
                quadTo(12.6f, 18.6f, 11.3f, 17.6f)
                lineTo(7.4f, 14.3f)
                lineTo(4.6f, 14.3f)
                quadTo(3.6f, 14.3f, 3.6f, 13.3f)
                lineTo(3.6f, 10.7f)
                quadTo(3.6f, 9.7f, 4.6f, 9.7f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15.37f, 9.32f)
                curveTo(16.74f, 10.84f, 16.74f, 13.16f, 15.37f, 14.68f)
            }
            path(stroke = Ink, strokeAlpha = 0.7f, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(17.99f, 7.31f)
                curveTo(20.27f, 10.02f, 20.27f, 13.98f, 17.99f, 16.69f)
            }
        }.build()
    }

    /** A live channel's captions, on. */
    val Captions: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekPlayerCaptions", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(6.1f, 5.5f)
                lineTo(17.9f, 5.5f)
                quadTo(20.5f, 5.5f, 20.5f, 8.1f)
                lineTo(20.5f, 15.9f)
                quadTo(20.5f, 18.5f, 17.9f, 18.5f)
                lineTo(6.1f, 18.5f)
                quadTo(3.5f, 18.5f, 3.5f, 15.9f)
                lineTo(3.5f, 8.1f)
                quadTo(3.5f, 5.5f, 6.1f, 5.5f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.1f, 5.5f)
                lineTo(17.9f, 5.5f)
                quadTo(20.5f, 5.5f, 20.5f, 8.1f)
                lineTo(20.5f, 15.9f)
                quadTo(20.5f, 18.5f, 17.9f, 18.5f)
                lineTo(6.1f, 18.5f)
                quadTo(3.5f, 18.5f, 3.5f, 15.9f)
                lineTo(3.5f, 8.1f)
                quadTo(3.5f, 5.5f, 6.1f, 5.5f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(10.72f, 13.46f)
                curveTo(9.95f, 14.1f, 8.81f, 14.04f, 8.12f, 13.3f)
                curveTo(7.43f, 12.57f, 7.43f, 11.43f, 8.12f, 10.7f)
                curveTo(8.81f, 9.96f, 9.95f, 9.9f, 10.72f, 10.54f)
            }
            path(stroke = Ink, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(16.12f, 13.46f)
                curveTo(15.35f, 14.1f, 14.21f, 14.04f, 13.52f, 13.3f)
                curveTo(12.83f, 12.57f, 12.83f, 11.43f, 13.52f, 10.7f)
                curveTo(14.21f, 9.96f, 15.35f, 9.9f, 16.12f, 10.54f)
            }
        }.build()
    }

    /** A live channel's captions, off. */
    val CaptionsOff: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekPlayerCaptionsOff", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = Ink, strokeAlpha = 0.55f, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.1f, 5.5f)
                lineTo(17.9f, 5.5f)
                quadTo(20.5f, 5.5f, 20.5f, 8.1f)
                lineTo(20.5f, 15.9f)
                quadTo(20.5f, 18.5f, 17.9f, 18.5f)
                lineTo(6.1f, 18.5f)
                quadTo(3.5f, 18.5f, 3.5f, 15.9f)
                lineTo(3.5f, 8.1f)
                quadTo(3.5f, 5.5f, 6.1f, 5.5f)
                close()
            }
            path(stroke = Ink, strokeAlpha = 0.55f, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(10.72f, 13.46f)
                curveTo(9.95f, 14.1f, 8.81f, 14.04f, 8.12f, 13.3f)
                curveTo(7.43f, 12.57f, 7.43f, 11.43f, 8.12f, 10.7f)
                curveTo(8.81f, 9.96f, 9.95f, 9.9f, 10.72f, 10.54f)
            }
            path(stroke = Ink, strokeAlpha = 0.55f, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(16.12f, 13.46f)
                curveTo(15.35f, 14.1f, 14.21f, 14.04f, 13.52f, 13.3f)
                curveTo(12.83f, 12.57f, 12.83f, 11.43f, 13.52f, 10.7f)
                curveTo(14.21f, 9.96f, 15.35f, 9.9f, 16.12f, 10.54f)
            }
            path(stroke = Ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4.6f, 4.4f)
                lineTo(19.4f, 19.6f)
            }
        }.build()
    }

    /** Sources, and the card view of a list: four tiles, the first solid. */
    val Sources: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekPlayerSources", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink) {
                moveTo(6.2f, 4.3f)
                lineTo(8.7f, 4.3f)
                quadTo(10.6f, 4.3f, 10.6f, 6.2f)
                lineTo(10.6f, 8.7f)
                quadTo(10.6f, 10.6f, 8.7f, 10.6f)
                lineTo(6.2f, 10.6f)
                quadTo(4.3f, 10.6f, 4.3f, 8.7f)
                lineTo(4.3f, 6.2f)
                quadTo(4.3f, 4.3f, 6.2f, 4.3f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.2f, 4.3f)
                lineTo(8.7f, 4.3f)
                quadTo(10.6f, 4.3f, 10.6f, 6.2f)
                lineTo(10.6f, 8.7f)
                quadTo(10.6f, 10.6f, 8.7f, 10.6f)
                lineTo(6.2f, 10.6f)
                quadTo(4.3f, 10.6f, 4.3f, 8.7f)
                lineTo(4.3f, 6.2f)
                quadTo(4.3f, 4.3f, 6.2f, 4.3f)
                close()
            }
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(15.3f, 4.3f)
                lineTo(17.8f, 4.3f)
                quadTo(19.7f, 4.3f, 19.7f, 6.2f)
                lineTo(19.7f, 8.7f)
                quadTo(19.7f, 10.6f, 17.8f, 10.6f)
                lineTo(15.3f, 10.6f)
                quadTo(13.4f, 10.6f, 13.4f, 8.7f)
                lineTo(13.4f, 6.2f)
                quadTo(13.4f, 4.3f, 15.3f, 4.3f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15.3f, 4.3f)
                lineTo(17.8f, 4.3f)
                quadTo(19.7f, 4.3f, 19.7f, 6.2f)
                lineTo(19.7f, 8.7f)
                quadTo(19.7f, 10.6f, 17.8f, 10.6f)
                lineTo(15.3f, 10.6f)
                quadTo(13.4f, 10.6f, 13.4f, 8.7f)
                lineTo(13.4f, 6.2f)
                quadTo(13.4f, 4.3f, 15.3f, 4.3f)
                close()
            }
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(6.2f, 13.4f)
                lineTo(8.7f, 13.4f)
                quadTo(10.6f, 13.4f, 10.6f, 15.3f)
                lineTo(10.6f, 17.8f)
                quadTo(10.6f, 19.7f, 8.7f, 19.7f)
                lineTo(6.2f, 19.7f)
                quadTo(4.3f, 19.7f, 4.3f, 17.8f)
                lineTo(4.3f, 15.3f)
                quadTo(4.3f, 13.4f, 6.2f, 13.4f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.2f, 13.4f)
                lineTo(8.7f, 13.4f)
                quadTo(10.6f, 13.4f, 10.6f, 15.3f)
                lineTo(10.6f, 17.8f)
                quadTo(10.6f, 19.7f, 8.7f, 19.7f)
                lineTo(6.2f, 19.7f)
                quadTo(4.3f, 19.7f, 4.3f, 17.8f)
                lineTo(4.3f, 15.3f)
                quadTo(4.3f, 13.4f, 6.2f, 13.4f)
                close()
            }
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(15.3f, 13.4f)
                lineTo(17.8f, 13.4f)
                quadTo(19.7f, 13.4f, 19.7f, 15.3f)
                lineTo(19.7f, 17.8f)
                quadTo(19.7f, 19.7f, 17.8f, 19.7f)
                lineTo(15.3f, 19.7f)
                quadTo(13.4f, 19.7f, 13.4f, 17.8f)
                lineTo(13.4f, 15.3f)
                quadTo(13.4f, 13.4f, 15.3f, 13.4f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(15.3f, 13.4f)
                lineTo(17.8f, 13.4f)
                quadTo(19.7f, 13.4f, 19.7f, 15.3f)
                lineTo(19.7f, 17.8f)
                quadTo(19.7f, 19.7f, 17.8f, 19.7f)
                lineTo(15.3f, 19.7f)
                quadTo(13.4f, 19.7f, 13.4f, 17.8f)
                lineTo(13.4f, 15.3f)
                quadTo(13.4f, 13.4f, 15.3f, 13.4f)
                close()
            }
        }.build()
    }

    /** The list view of a list. */
    val ListView: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekPlayerListView", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink) {
                moveTo(5.3f, 5.45f)
                curveTo(6.05f, 5.45f, 6.65f, 6.05f, 6.65f, 6.8f)
                curveTo(6.65f, 7.55f, 6.05f, 8.15f, 5.3f, 8.15f)
                curveTo(4.55f, 8.15f, 3.95f, 7.55f, 3.95f, 6.8f)
                curveTo(3.95f, 6.05f, 4.55f, 5.45f, 5.3f, 5.45f)
                close()
            }
            path(fill = Ink) {
                moveTo(5.3f, 10.65f)
                curveTo(6.05f, 10.65f, 6.65f, 11.25f, 6.65f, 12f)
                curveTo(6.65f, 12.75f, 6.05f, 13.35f, 5.3f, 13.35f)
                curveTo(4.55f, 13.35f, 3.95f, 12.75f, 3.95f, 12f)
                curveTo(3.95f, 11.25f, 4.55f, 10.65f, 5.3f, 10.65f)
                close()
            }
            path(fill = Ink) {
                moveTo(5.3f, 15.85f)
                curveTo(6.05f, 15.85f, 6.65f, 16.45f, 6.65f, 17.2f)
                curveTo(6.65f, 17.95f, 6.05f, 18.55f, 5.3f, 18.55f)
                curveTo(4.55f, 18.55f, 3.95f, 17.95f, 3.95f, 17.2f)
                curveTo(3.95f, 16.45f, 4.55f, 15.85f, 5.3f, 15.85f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9.6f, 6.8f)
                lineTo(19.6f, 6.8f)
            }
            path(stroke = Ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9.6f, 12f)
                lineTo(19.6f, 12f)
            }
            path(stroke = Ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9.6f, 17.2f)
                lineTo(19.6f, 17.2f)
            }
        }.build()
    }

    /** Player engine and tuning: three sliders. */
    val Engine: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekPlayerEngine", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = Ink, strokeAlpha = 0.7f, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4.2f, 6.8f)
                lineTo(19.8f, 6.8f)
            }
            path(stroke = Ink, strokeAlpha = 0.7f, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4.2f, 12f)
                lineTo(19.8f, 12f)
            }
            path(stroke = Ink, strokeAlpha = 0.7f, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4.2f, 17.2f)
                lineTo(19.8f, 17.2f)
            }
            path(fill = Ink) {
                moveTo(9f, 4.5f)
                curveTo(10.27f, 4.5f, 11.3f, 5.53f, 11.3f, 6.8f)
                curveTo(11.3f, 8.07f, 10.27f, 9.1f, 9f, 9.1f)
                curveTo(7.73f, 9.1f, 6.7f, 8.07f, 6.7f, 6.8f)
                curveTo(6.7f, 5.53f, 7.73f, 4.5f, 9f, 4.5f)
                close()
            }
            path(fill = Ink) {
                moveTo(15.2f, 9.7f)
                curveTo(16.47f, 9.7f, 17.5f, 10.73f, 17.5f, 12f)
                curveTo(17.5f, 13.27f, 16.47f, 14.3f, 15.2f, 14.3f)
                curveTo(13.93f, 14.3f, 12.9f, 13.27f, 12.9f, 12f)
                curveTo(12.9f, 10.73f, 13.93f, 9.7f, 15.2f, 9.7f)
                close()
            }
            path(fill = Ink) {
                moveTo(8f, 14.9f)
                curveTo(9.27f, 14.9f, 10.3f, 15.93f, 10.3f, 17.2f)
                curveTo(10.3f, 18.47f, 9.27f, 19.5f, 8f, 19.5f)
                curveTo(6.73f, 19.5f, 5.7f, 18.47f, 5.7f, 17.2f)
                curveTo(5.7f, 15.93f, 6.73f, 14.9f, 8f, 14.9f)
                close()
            }
        }.build()
    }

    /** Stream information. */
    val Info: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekPlayerInfo", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(12f, 3.45f)
                curveTo(16.72f, 3.45f, 20.55f, 7.28f, 20.55f, 12f)
                curveTo(20.55f, 16.72f, 16.72f, 20.55f, 12f, 20.55f)
                curveTo(7.28f, 20.55f, 3.45f, 16.72f, 3.45f, 12f)
                curveTo(3.45f, 7.28f, 7.28f, 3.45f, 12f, 3.45f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3.45f)
                curveTo(16.72f, 3.45f, 20.55f, 7.28f, 20.55f, 12f)
                curveTo(20.55f, 16.72f, 16.72f, 20.55f, 12f, 20.55f)
                curveTo(7.28f, 20.55f, 3.45f, 16.72f, 3.45f, 12f)
                curveTo(3.45f, 7.28f, 7.28f, 3.45f, 12f, 3.45f)
                close()
            }
            path(fill = Ink) {
                moveTo(12f, 7.05f)
                curveTo(12.69f, 7.05f, 13.25f, 7.61f, 13.25f, 8.3f)
                curveTo(13.25f, 8.99f, 12.69f, 9.55f, 12f, 9.55f)
                curveTo(11.31f, 9.55f, 10.75f, 8.99f, 10.75f, 8.3f)
                curveTo(10.75f, 7.61f, 11.31f, 7.05f, 12f, 7.05f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 2.1f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 11.7f)
                lineTo(12f, 16.3f)
            }
        }.build()
    }

    /** Playback speed: a gauge. */
    val Speed: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekPlayerSpeed", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4.66f, 16.82f)
                curveTo(3.07f, 13.42f, 4.03f, 9.37f, 6.98f, 7.05f)
                curveTo(9.92f, 4.72f, 14.08f, 4.72f, 17.02f, 7.05f)
                curveTo(19.97f, 9.37f, 20.93f, 13.42f, 19.34f, 16.82f)
            }
            path(stroke = Ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 13.4f)
                lineTo(15.9f, 9.1f)
            }
            path(fill = Ink) {
                moveTo(12f, 11.7f)
                curveTo(12.94f, 11.7f, 13.7f, 12.46f, 13.7f, 13.4f)
                curveTo(13.7f, 14.34f, 12.94f, 15.1f, 12f, 15.1f)
                curveTo(11.06f, 15.1f, 10.3f, 14.34f, 10.3f, 13.4f)
                curveTo(10.3f, 12.46f, 11.06f, 11.7f, 12f, 11.7f)
                close()
            }
        }.build()
    }

    /** A live channel's progress bar, shown. */
    val Progress: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekPlayerProgress", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = Ink, strokeAlpha = 0.45f, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 12f)
                lineTo(20f, 12f)
            }
            path(stroke = Ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 12f)
                lineTo(10.6f, 12f)
            }
            path(fill = Ink) {
                moveTo(11.6f, 9.1f)
                curveTo(13.2f, 9.1f, 14.5f, 10.4f, 14.5f, 12f)
                curveTo(14.5f, 13.6f, 13.2f, 14.9f, 11.6f, 14.9f)
                curveTo(10f, 14.9f, 8.7f, 13.6f, 8.7f, 12f)
                curveTo(8.7f, 10.4f, 10f, 9.1f, 11.6f, 9.1f)
                close()
            }
        }.build()
    }

    /** The Live / VOD badge switch: a television with a play mark. */
    val LiveBadge: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekPlayerLiveBadge", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(6f, 7f)
                lineTo(18f, 7f)
                quadTo(20.5f, 7f, 20.5f, 9.5f)
                lineTo(20.5f, 16.8f)
                quadTo(20.5f, 19.3f, 18f, 19.3f)
                lineTo(6f, 19.3f)
                quadTo(3.5f, 19.3f, 3.5f, 16.8f)
                lineTo(3.5f, 9.5f)
                quadTo(3.5f, 7f, 6f, 7f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6f, 7f)
                lineTo(18f, 7f)
                quadTo(20.5f, 7f, 20.5f, 9.5f)
                lineTo(20.5f, 16.8f)
                quadTo(20.5f, 19.3f, 18f, 19.3f)
                lineTo(6f, 19.3f)
                quadTo(3.5f, 19.3f, 3.5f, 16.8f)
                lineTo(3.5f, 9.5f)
                quadTo(3.5f, 7f, 6f, 7f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8.8f, 3.4f)
                lineTo(12f, 7f)
                lineTo(15.2f, 3.4f)
            }
            path(fill = Ink) {
                moveTo(10.3f, 11.13f)
                quadTo(10.3f, 10.29f, 11.22f, 10.88f)
                lineTo(14.44f, 12.75f)
                quadTo(15.08f, 13.2f, 14.44f, 13.65f)
                lineTo(11.22f, 15.52f)
                quadTo(10.3f, 16.11f, 10.3f, 15.27f)
                close()
            }
        }.build()
    }

    /** A favourite channel. */
    val Star: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekPlayerStar", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink) {
                moveTo(12f, 4.1f)
                lineTo(14.35f, 9.46f)
                lineTo(20.18f, 10.04f)
                lineTo(15.8f, 13.94f)
                lineTo(17.05f, 19.66f)
                lineTo(12f, 16.7f)
                lineTo(6.95f, 19.66f)
                lineTo(8.2f, 13.94f)
                lineTo(3.82f, 10.04f)
                lineTo(9.65f, 9.46f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 4.1f)
                lineTo(14.35f, 9.46f)
                lineTo(20.18f, 10.04f)
                lineTo(15.8f, 13.94f)
                lineTo(17.05f, 19.66f)
                lineTo(12f, 16.7f)
                lineTo(6.95f, 19.66f)
                lineTo(8.2f, 13.94f)
                lineTo(3.82f, 10.04f)
                lineTo(9.65f, 9.46f)
                close()
            }
        }.build()
    }

    /** Not yet a favourite. */
    val StarOutline: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekPlayerStarOutline", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(12f, 4.1f)
                lineTo(14.35f, 9.46f)
                lineTo(20.18f, 10.04f)
                lineTo(15.8f, 13.94f)
                lineTo(17.05f, 19.66f)
                lineTo(12f, 16.7f)
                lineTo(6.95f, 19.66f)
                lineTo(8.2f, 13.94f)
                lineTo(3.82f, 10.04f)
                lineTo(9.65f, 9.46f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 4.1f)
                lineTo(14.35f, 9.46f)
                lineTo(20.18f, 10.04f)
                lineTo(15.8f, 13.94f)
                lineTo(17.05f, 19.66f)
                lineTo(12f, 16.7f)
                lineTo(6.95f, 19.66f)
                lineTo(8.2f, 13.94f)
                lineTo(3.82f, 10.04f)
                lineTo(9.65f, 9.46f)
                close()
            }
        }.build()
    }

    /** A watched episode: a ticked disc. */
    val Watched: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekPlayerWatched", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(12f, 3.45f)
                curveTo(16.72f, 3.45f, 20.55f, 7.28f, 20.55f, 12f)
                curveTo(20.55f, 16.72f, 16.72f, 20.55f, 12f, 20.55f)
                curveTo(7.28f, 20.55f, 3.45f, 16.72f, 3.45f, 12f)
                curveTo(3.45f, 7.28f, 7.28f, 3.45f, 12f, 3.45f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3.45f)
                curveTo(16.72f, 3.45f, 20.55f, 7.28f, 20.55f, 12f)
                curveTo(20.55f, 16.72f, 16.72f, 20.55f, 12f, 20.55f)
                curveTo(7.28f, 20.55f, 3.45f, 16.72f, 3.45f, 12f)
                curveTo(3.45f, 7.28f, 7.28f, 3.45f, 12f, 3.45f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8.1f, 12.3f)
                lineTo(10.9f, 15f)
                lineTo(16f, 9.4f)
            }
        }.build()
    }

    /** Close the drawer on the trailing edge. */
    val ChevronRight: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekPlayerChevronRight", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = Ink, strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9.4f, 5.6f)
                lineTo(15.8f, 12f)
                lineTo(9.4f, 18.4f)
            }
        }.build()
    }

    /** More below: the cue to the channel row under a live picture. */
    val ChevronDown: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekPlayerChevronDown", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = Ink, strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5.8f, 9f)
                lineTo(12f, 15.2f)
                lineTo(18.2f, 9f)
            }
        }.build()
    }
}

/**
 * The Settings screen's icons, in the same family as [StreamDekNavIcons] and [StreamDekPlayerIcons].
 *
 * Only the ones Settings needs that neither of those already has. A settings page that is about
 * something the rail or the player already draws - Home, Live TV, subtitles, audio, the next
 * episode, sources, the player itself - uses that drawing, so one idea has one picture everywhere.
 */
internal object StreamDekSettingsIcons {
    private val Ink = SolidColor(Color.Black)

    /** Account: a head and shoulders, in outline over a tonal fill. */
    val Account: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekSettingsAccount", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(5f, 19.6f)
                curveTo(5f, 16f, 8f, 14.4f, 12f, 14.4f)
                curveTo(16f, 14.4f, 19f, 16f, 19f, 19.6f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5f, 19.6f)
                curveTo(5f, 16f, 8f, 14.4f, 12f, 14.4f)
                curveTo(16f, 14.4f, 19f, 16f, 19f, 19.6f)
            }
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(12f, 4.9f)
                curveTo(13.93f, 4.9f, 15.5f, 6.47f, 15.5f, 8.4f)
                curveTo(15.5f, 10.33f, 13.93f, 11.9f, 12f, 11.9f)
                curveTo(10.07f, 11.9f, 8.5f, 10.33f, 8.5f, 8.4f)
                curveTo(8.5f, 6.47f, 10.07f, 4.9f, 12f, 4.9f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 4.9f)
                curveTo(13.93f, 4.9f, 15.5f, 6.47f, 15.5f, 8.4f)
                curveTo(15.5f, 10.33f, 13.93f, 11.9f, 12f, 11.9f)
                curveTo(10.07f, 11.9f, 8.5f, 10.33f, 8.5f, 8.4f)
                curveTo(8.5f, 6.47f, 10.07f, 4.9f, 12f, 4.9f)
                close()
            }
        }.build()
    }

    /** Appearance: a painter's palette in outline over a tonal fill, its colours solid. */
    val Appearance: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekSettingsAppearance", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(12f, 3.8f)
                curveTo(16.7f, 3.8f, 20.3f, 7f, 20.3f, 11f)
                curveTo(20.3f, 13.6f, 18.4f, 15f, 16.2f, 15f)
                lineTo(14.6f, 15f)
                curveTo(13.4f, 15f, 12.7f, 16f, 13.1f, 17.1f)
                curveTo(13.6f, 18.6f, 13f, 20.2f, 11.4f, 20.2f)
                curveTo(7f, 20.2f, 3.7f, 16.6f, 3.7f, 12f)
                curveTo(3.7f, 7.4f, 7.4f, 3.8f, 12f, 3.8f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3.8f)
                curveTo(16.7f, 3.8f, 20.3f, 7f, 20.3f, 11f)
                curveTo(20.3f, 13.6f, 18.4f, 15f, 16.2f, 15f)
                lineTo(14.6f, 15f)
                curveTo(13.4f, 15f, 12.7f, 16f, 13.1f, 17.1f)
                curveTo(13.6f, 18.6f, 13f, 20.2f, 11.4f, 20.2f)
                curveTo(7f, 20.2f, 3.7f, 16.6f, 3.7f, 12f)
                curveTo(3.7f, 7.4f, 7.4f, 3.8f, 12f, 3.8f)
                close()
            }
            path(fill = Ink) {
                moveTo(8.1f, 10.15f)
                curveTo(8.79f, 10.15f, 9.35f, 10.71f, 9.35f, 11.4f)
                curveTo(9.35f, 12.09f, 8.79f, 12.65f, 8.1f, 12.65f)
                curveTo(7.41f, 12.65f, 6.85f, 12.09f, 6.85f, 11.4f)
                curveTo(6.85f, 10.71f, 7.41f, 10.15f, 8.1f, 10.15f)
                close()
            }
            path(fill = Ink) {
                moveTo(11.2f, 6.65f)
                curveTo(11.89f, 6.65f, 12.45f, 7.21f, 12.45f, 7.9f)
                curveTo(12.45f, 8.59f, 11.89f, 9.15f, 11.2f, 9.15f)
                curveTo(10.51f, 9.15f, 9.95f, 8.59f, 9.95f, 7.9f)
                curveTo(9.95f, 7.21f, 10.51f, 6.65f, 11.2f, 6.65f)
                close()
            }
            path(fill = Ink) {
                moveTo(15.6f, 7.95f)
                curveTo(16.29f, 7.95f, 16.85f, 8.51f, 16.85f, 9.2f)
                curveTo(16.85f, 9.89f, 16.29f, 10.45f, 15.6f, 10.45f)
                curveTo(14.91f, 10.45f, 14.35f, 9.89f, 14.35f, 9.2f)
                curveTo(14.35f, 8.51f, 14.91f, 7.95f, 15.6f, 7.95f)
                close()
            }
        }.build()
    }

    /** Accessibility: a figure with arms out, its head solid. */
    val Accessibility: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekSettingsAccessibility", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink) {
                moveTo(12f, 3.2f)
                curveTo(13.05f, 3.2f, 13.9f, 4.05f, 13.9f, 5.1f)
                curveTo(13.9f, 6.15f, 13.05f, 7f, 12f, 7f)
                curveTo(10.95f, 7f, 10.1f, 6.15f, 10.1f, 5.1f)
                curveTo(10.1f, 4.05f, 10.95f, 3.2f, 12f, 3.2f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5.4f, 9.4f)
                lineTo(18.6f, 9.4f)
                moveTo(12f, 9.4f)
                lineTo(12f, 14.2f)
                moveTo(12f, 14.2f)
                lineTo(8.5f, 20.2f)
                moveTo(12f, 14.2f)
                lineTo(15.5f, 20.2f)
            }
        }.build()
    }

    /** Streams and quality: three sliders, their knobs solid. */
    val Sliders: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekSettingsSliders", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = Ink, strokeAlpha = 0.55f, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4.5f, 7f)
                lineTo(19.5f, 7f)
                moveTo(4.5f, 12f)
                lineTo(19.5f, 12f)
                moveTo(4.5f, 17f)
                lineTo(19.5f, 17f)
            }
            path(fill = Ink) {
                moveTo(9f, 4.8f)
                curveTo(10.22f, 4.8f, 11.2f, 5.78f, 11.2f, 7f)
                curveTo(11.2f, 8.22f, 10.22f, 9.2f, 9f, 9.2f)
                curveTo(7.78f, 9.2f, 6.8f, 8.22f, 6.8f, 7f)
                curveTo(6.8f, 5.78f, 7.78f, 4.8f, 9f, 4.8f)
                close()
            }
            path(fill = Ink) {
                moveTo(15.2f, 9.8f)
                curveTo(16.42f, 9.8f, 17.4f, 10.78f, 17.4f, 12f)
                curveTo(17.4f, 13.22f, 16.42f, 14.2f, 15.2f, 14.2f)
                curveTo(13.98f, 14.2f, 13f, 13.22f, 13f, 12f)
                curveTo(13f, 10.78f, 13.98f, 9.8f, 15.2f, 9.8f)
                close()
            }
            path(fill = Ink) {
                moveTo(8f, 14.8f)
                curveTo(9.22f, 14.8f, 10.2f, 15.78f, 10.2f, 17f)
                curveTo(10.2f, 18.22f, 9.22f, 19.2f, 8f, 19.2f)
                curveTo(6.78f, 19.2f, 5.8f, 18.22f, 5.8f, 17f)
                curveTo(5.8f, 15.78f, 6.78f, 14.8f, 8f, 14.8f)
                close()
            }
        }.build()
    }

    /** Keys for content services: a key, its bow in outline over a tonal fill. */
    val Key: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekSettingsKey", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(8f, 8f)
                curveTo(10.21f, 8f, 12f, 9.79f, 12f, 12f)
                curveTo(12f, 14.21f, 10.21f, 16f, 8f, 16f)
                curveTo(5.79f, 16f, 4f, 14.21f, 4f, 12f)
                curveTo(4f, 9.79f, 5.79f, 8f, 8f, 8f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(8f, 8f)
                curveTo(10.21f, 8f, 12f, 9.79f, 12f, 12f)
                curveTo(12f, 14.21f, 10.21f, 16f, 8f, 16f)
                curveTo(5.79f, 16f, 4f, 14.21f, 4f, 12f)
                curveTo(4f, 9.79f, 5.79f, 8f, 8f, 8f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 12f)
                lineTo(20.2f, 12f)
                moveTo(16.6f, 12f)
                lineTo(16.6f, 15.2f)
                moveTo(20.2f, 12f)
                lineTo(20.2f, 14.6f)
            }
        }.build()
    }

    /** Sync services: two arrows chasing each other round. */
    val Sync: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekSettingsSync", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5f, 11.2f)
                curveTo(5.6f, 7.3f, 9.1f, 5f, 12.4f, 5f)
                curveTo(15f, 5f, 17.2f, 6.3f, 18.6f, 8.4f)
                moveTo(18.9f, 4.5f)
                lineTo(18.9f, 8.6f)
                lineTo(14.8f, 8.6f)
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(19f, 12.8f)
                curveTo(18.4f, 16.7f, 14.9f, 19f, 11.6f, 19f)
                curveTo(9f, 19f, 6.8f, 17.7f, 5.4f, 15.6f)
                moveTo(5.1f, 19.5f)
                lineTo(5.1f, 15.4f)
                lineTo(9.2f, 15.4f)
            }
        }.build()
    }

    /** Network: a globe in outline over a tonal fill. */
    val Network: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekSettingsNetwork", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(12f, 3.8f)
                curveTo(16.53f, 3.8f, 20.2f, 7.47f, 20.2f, 12f)
                curveTo(20.2f, 16.53f, 16.53f, 20.2f, 12f, 20.2f)
                curveTo(7.47f, 20.2f, 3.8f, 16.53f, 3.8f, 12f)
                curveTo(3.8f, 7.47f, 7.47f, 3.8f, 12f, 3.8f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3.8f)
                curveTo(16.53f, 3.8f, 20.2f, 7.47f, 20.2f, 12f)
                curveTo(20.2f, 16.53f, 16.53f, 20.2f, 12f, 20.2f)
                curveTo(7.47f, 20.2f, 3.8f, 16.53f, 3.8f, 12f)
                curveTo(3.8f, 7.47f, 7.47f, 3.8f, 12f, 3.8f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3.8f, 12f)
                lineTo(20.2f, 12f)
                moveTo(12f, 3.8f)
                curveTo(8.2f, 7f, 8.2f, 17f, 12f, 20.2f)
                curveTo(15.8f, 17f, 15.8f, 7f, 12f, 3.8f)
                close()
            }
        }.build()
    }

    /** Shown: an eye in outline over a tonal fill, its pupil solid. */
    val Eye: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekSettingsEye", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink, fillAlpha = 0.2f) {
                moveTo(2.8f, 12f)
                curveTo(5.5f, 7.2f, 9f, 6f, 12f, 6f)
                curveTo(15f, 6f, 18.5f, 7.2f, 21.2f, 12f)
                curveTo(18.5f, 16.8f, 15f, 18f, 12f, 18f)
                curveTo(9f, 18f, 5.5f, 16.8f, 2.8f, 12f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2.8f, 12f)
                curveTo(5.5f, 7.2f, 9f, 6f, 12f, 6f)
                curveTo(15f, 6f, 18.5f, 7.2f, 21.2f, 12f)
                curveTo(18.5f, 16.8f, 15f, 18f, 12f, 18f)
                curveTo(9f, 18f, 5.5f, 16.8f, 2.8f, 12f)
                close()
            }
            path(fill = Ink) {
                moveTo(12f, 9.3f)
                curveTo(13.49f, 9.3f, 14.7f, 10.51f, 14.7f, 12f)
                curveTo(14.7f, 13.49f, 13.49f, 14.7f, 12f, 14.7f)
                curveTo(10.51f, 14.7f, 9.3f, 13.49f, 9.3f, 12f)
                curveTo(9.3f, 10.51f, 10.51f, 9.3f, 12f, 9.3f)
                close()
            }
        }.build()
    }

    /** Hidden: the same eye dimmed, with a stroke through it. */
    val EyeOff: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekSettingsEyeOff", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = Ink, fillAlpha = 0.12f) {
                moveTo(2.8f, 12f)
                curveTo(5.5f, 7.2f, 9f, 6f, 12f, 6f)
                curveTo(15f, 6f, 18.5f, 7.2f, 21.2f, 12f)
                curveTo(18.5f, 16.8f, 15f, 18f, 12f, 18f)
                curveTo(9f, 18f, 5.5f, 16.8f, 2.8f, 12f)
                close()
            }
            path(stroke = Ink, strokeAlpha = 0.55f, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2.8f, 12f)
                curveTo(5.5f, 7.2f, 9f, 6f, 12f, 6f)
                curveTo(15f, 6f, 18.5f, 7.2f, 21.2f, 12f)
                curveTo(18.5f, 16.8f, 15f, 18f, 12f, 18f)
                curveTo(9f, 18f, 5.5f, 16.8f, 2.8f, 12f)
                close()
            }
            path(stroke = Ink, strokeLineWidth = 2.1f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5f, 4.6f)
                lineTo(19f, 19.4f)
            }
        }.build()
    }

    /** Less: fold a section away, or move a row up. */
    val ChevronUp: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekSettingsChevronUp", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = Ink, strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5.8f, 15f)
                lineTo(12f, 8.8f)
                lineTo(18.2f, 15f)
            }
        }.build()
    }

    /** Chosen. */
    val Check: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekSettingsCheck", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = Ink, strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5f, 12.7f)
                lineTo(9.8f, 17.3f)
                lineTo(19f, 7.4f)
            }
        }.build()
    }

    /** Remove from a list. */
    val Close: ImageVector by lazy {
        ImageVector.Builder(name = "StreamDekSettingsClose", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            path(stroke = Ink, strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.6f, 6.6f)
                lineTo(17.4f, 17.4f)
                moveTo(17.4f, 6.6f)
                lineTo(6.6f, 17.4f)
            }
        }.build()
    }
}
