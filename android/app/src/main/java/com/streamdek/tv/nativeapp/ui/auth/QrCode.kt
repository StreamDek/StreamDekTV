package com.streamdek.tv.nativeapp.ui.auth

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A QR code drawn on the television itself.
 *
 * The sign-in screen used to fetch its QR image from a public QR web service, which put every
 * pairing link on the wire to a third party and left a blank square whenever that service was slow
 * or unreachable. Encoding it here costs a few milliseconds, off the main thread, and nothing leaves
 * the box. Dark modules on white with a quiet zone, which is what every phone camera reads best.
 */
internal fun encodeQrBitmap(content: String, sizePx: Int): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(
            EncodeHintType.MARGIN to 2,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            pixels[row + x] = if (matrix.get(x, y)) 0xFF0B0D12.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}.getOrNull()

/** [content] as a QR image, encoded off the main thread; null until it is ready or when empty. */
@Composable
internal fun rememberQrImage(content: String?, sizePx: Int = 720): ImageBitmap? {
    val image by produceState<ImageBitmap?>(initialValue = null, content, sizePx) {
        value = content?.takeIf { it.isNotBlank() }?.let { text ->
            withContext(Dispatchers.Default) { encodeQrBitmap(text, sizePx)?.asImageBitmap() }
        }
    }
    return image
}
