package realm.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedOrigin
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import kotlin.math.max
import kotlin.math.min

/** Decodes [bytes] upright, scaled down to about [maxSize] pixels on its longer side; null if they aren't an image. */
fun decodeImage(bytes: ByteArray, maxSize: Int): ImageBitmap? = runCatching {
    val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
    val img = Image.makeFromEncoded(bytes)
    val quarterTurns = when (codec.encodedOrigin) {
        EncodedOrigin.RIGHT_TOP -> 1
        EncodedOrigin.BOTTOM_RIGHT -> 2
        EncodedOrigin.LEFT_BOTTOM -> 3
        else -> 0
    }
    val scale = min(1f, maxSize * 1.5f / max(img.width, img.height))
    if (quarterTurns == 0 && scale == 1f) return@runCatching img.toComposeImageBitmap()
    val w = (img.width * scale).toInt()
    val h = (img.height * scale).toInt()
    val (ow, oh) = if (quarterTurns % 2 == 1) h to w else w to h
    val surface = Surface.makeRasterN32Premul(ow, oh)
    surface.canvas.apply {
        translate(ow / 2f, oh / 2f)
        rotate(90f * quarterTurns)
        scale(scale, scale)
        drawImage(img, -img.width / 2f, -img.height / 2f)
    }
    surface.makeImageSnapshot().toComposeImageBitmap()
}.getOrNull()
