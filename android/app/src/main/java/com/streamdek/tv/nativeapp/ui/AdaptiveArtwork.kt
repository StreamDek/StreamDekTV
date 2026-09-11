package com.streamdek.tv.nativeapp.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import coil.transform.Transformation
import kotlin.math.max
import kotlin.math.min

/** How much of an image's area may be cropped away to fill a frame before it is fitted instead. */
object ArtworkCropTolerance {
    /** Backdrops are made to be cropped. */
    const val BACKDROP = 0.66f

    /** Posters and cards carry their subject edge to edge, text and logos included. */
    const val POSTER = 0.30f
}

internal enum class ArtworkFit { Crop, FitOverBlur }

/**
 * Crop to fill, or fit over a blurred copy of itself, for an image of one shape in a frame of another.
 * Cropping keeps `min(a, b) / max(a, b)` of the image's area; beyond [cropTolerance] of it lost, the
 * image is fitted instead. Unknown sizes crop, which is what the artwork did before this existed.
 */
internal fun chooseArtworkFit(
    imageWidth: Float,
    imageHeight: Float,
    frameWidth: Float,
    frameHeight: Float,
    cropTolerance: Float,
): ArtworkFit {
    if (imageWidth <= 0f || imageHeight <= 0f || frameWidth <= 0f || frameHeight <= 0f) return ArtworkFit.Crop
    val imageAspect = imageWidth / imageHeight
    val frameAspect = frameWidth / frameHeight
    val croppedAway = 1f - min(imageAspect, frameAspect) / max(imageAspect, frameAspect)
    return if (croppedAway <= cropTolerance) ArtworkFit.Crop else ArtworkFit.FitOverBlur
}

/**
 * Artwork that adapts to the frame it is given: cropped to fill when it suits the frame, otherwise
 * shown whole and sharp over a full-bleed, blurred and dimmed copy of itself.
 *
 * The blurred copy is a small decode blurred once and cached, then scaled up, so no blur is computed
 * per frame. It is drawn oversized so its edges never reach the frame, and the mode is chosen once
 * the image has loaded and before it is first drawn, so nothing shifts or flashes.
 */
@Composable
fun AdaptiveArtwork(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    cropTolerance: Float = ArtworkCropTolerance.POSTER,
    cropAlignment: Alignment = Alignment.Center,
    fitAlignment: Alignment = Alignment.Center,
    backgroundDim: Float = 0.30f,
    /**
     * How much of its fitted size the sharp image takes when it is fitted: 1 fills the frame's short
     * side, less leaves the blurred copy showing all round it so it reads as a card on a backdrop.
     */
    fitScale: Float = 1f,
    /** The fitted image's own corners, which only show once [fitScale] leaves room around it. */
    fitShape: Shape = RectangleShape,
) {
    val context = LocalContext.current
    val painter = rememberAsyncImagePainter(model = model, contentScale = ContentScale.Crop)
    val blurRequest = remember(model) {
        ImageRequest.Builder(context)
            .data(model)
            .size(BLUR_SOURCE_PX)
            .allowHardware(false)
            .transformations(SoftBlurTransformation)
            .crossfade(false)
            .build()
    }
    LaunchedEffect(blurRequest) { if (model != null) context.imageLoader.enqueue(blurRequest) }
    BoxWithConstraints(modifier = modifier.clipToBounds()) {
        val loaded = (painter.state as? AsyncImagePainter.State.Success)?.painter?.intrinsicSize
        val fit = if (loaded == null) {
            ArtworkFit.Crop
        } else {
            chooseArtworkFit(loaded.width, loaded.height, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), cropTolerance)
        }
        if (fit == ArtworkFit.FitOverBlur) {
            AsyncImage(
                model = blurRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = BLUR_OVERSCAN
                    scaleY = BLUR_OVERSCAN
                },
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = backgroundDim)))
        }
        Image(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = if (fit == ArtworkFit.Crop) ContentScale.Crop else ContentScale.Fit,
            alignment = if (fit == ArtworkFit.Crop) cropAlignment else fitAlignment,
        )
    }
}

private const val BLUR_SOURCE_PX = 96
private const val BLUR_RADIUS_PX = 6
private const val BLUR_OVERSCAN = 1.2f

/** Three box passes over a small decode: close to a gaussian, and cheap enough to be irrelevant. */
private object SoftBlurTransformation : Transformation {
    override val cacheKey: String = "streamdek.softblur.$BLUR_SOURCE_PX.$BLUR_RADIUS_PX"

    override suspend fun transform(input: Bitmap, size: coil.size.Size): Bitmap {
        val bitmap = if (input.config == Bitmap.Config.ARGB_8888 && input.isMutable) input else input.copy(Bitmap.Config.ARGB_8888, true)
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val scratch = IntArray(pixels.size)
        repeat(3) {
            boxBlurPass(pixels, scratch, width, height, BLUR_RADIUS_PX, horizontal = true)
            boxBlurPass(scratch, pixels, width, height, BLUR_RADIUS_PX, horizontal = false)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }
}

private fun boxBlurPass(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
    val lines = if (horizontal) height else width
    val length = if (horizontal) width else height
    val window = 2 * radius + 1
    for (line in 0 until lines) {
        fun at(position: Int): Int {
            val clamped = position.coerceIn(0, length - 1)
            return if (horizontal) line * width + clamped else clamped * width + line
        }
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (i in -radius..radius) {
            val p = src[at(i)]
            a += p ushr 24
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        for (i in 0 until length) {
            dst[at(i)] = ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
            val leaving = src[at(i - radius)]
            val entering = src[at(i + radius + 1)]
            a += (entering ushr 24) - (leaving ushr 24)
            r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
            g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
            b += (entering and 0xFF) - (leaving and 0xFF)
        }
    }
}
