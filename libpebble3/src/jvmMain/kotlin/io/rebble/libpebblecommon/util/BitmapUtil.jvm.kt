package io.rebble.libpebblecommon.util

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces

/**
 * A pure-JVM [ImageBitmap] that just carries the decoded ARGB_8888 pixels.
 *
 * The headless native-image daemon has no graphics stack: a normal Compose desktop ImageBitmap is
 * skia-backed (skiko's native runtime isn't in the image), and java.awt is absent (touching it is a
 * fatal FindClass crash). This carrier lets takeScreenshot() return a real ImageBitmap whose pixels
 * the daemon reads back via readPixels() and encodes to PNG with pure java.util.zip — no native libs.
 */
private class IntArrayImageBitmap(
    override val width: Int,
    override val height: Int,
    private val pixels: IntArray,
) : ImageBitmap {
    override val config: ImageBitmapConfig get() = ImageBitmapConfig.Argb8888
    override val colorSpace: ColorSpace get() = ColorSpaces.Srgb
    override val hasAlpha: Boolean get() = true

    override fun prepareToDraw() {}

    override fun readPixels(
        buffer: IntArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int,
        bufferOffset: Int,
        stride: Int,
    ) {
        for (row in 0 until height) {
            val src = (startY + row) * this.width + startX
            val dst = bufferOffset + row * stride
            System.arraycopy(pixels, src, buffer, dst, width)
        }
    }
}

actual fun createImageBitmapFromPixelArray(
    pixels: IntArray,
    width: Int,
    height: Int
): ImageBitmap? {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return null
    return IntArrayImageBitmap(width, height, pixels.copyOf(width * height))
}

actual fun isScreenshotFinished(
    buffer: DataBuffer,
    expectedSize: Int
): Boolean {
    return buffer.remaining == 0
}
