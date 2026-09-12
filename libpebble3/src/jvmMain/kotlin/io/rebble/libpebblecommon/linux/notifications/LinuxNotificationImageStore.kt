package io.rebble.libpebblecommon.linux.notifications

import io.rebble.libpebblecommon.imaging.EncodedImage
import io.rebble.libpebblecommon.imaging.ImageEncoder
import io.rebble.libpebblecommon.imaging.ImagingService
import io.rebble.libpebblecommon.imaging.NotificationImageProvider
import io.rebble.libpebblecommon.imaging.aspectSixteenths
import io.rebble.libpebblecommon.packets.Imaging
import io.rebble.libpebblecommon.util.JvmPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlin.uuid.Uuid

/** Bounded RGB thumbnail: little-endian width/height followed by packed RGB bytes. */
class LinuxNotificationImage private constructor(private val bytes: ByteArray) {
    val width = u16(bytes, 0)
    val height = u16(bytes, 2)
    val aspect: UByte get() = checkNotNull(aspectSixteenths(width, height))
    fun encode(): ByteArray = bytes.copyOf()
    override fun equals(other: Any?): Boolean = other is LinuxNotificationImage && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()

    fun forWatch(width: Int, height: Int): EncodedImage? {
        if (width !in 1..ImagingService.MAX_DIM || height !in 1..ImagingService.MAX_DIM) return null
        val scale = maxOf(width.toDouble() / this.width, height.toDouble() / this.height)
        val cropX = (this.width - width / scale) / 2
        val cropY = (this.height - height / scale) / 2
        val argb = IntArray(width * height) { index ->
            val x = (cropX + (index % width + 0.5) / scale - 0.5).coerceIn(0.0, this.width - 1.0)
            val y = (cropY + (index / width + 0.5) / scale - 0.5).coerceIn(0.0, this.height - 1.0)
            val x0 = x.toInt(); val y0 = y.toInt()
            val x1 = minOf(x0 + 1, this.width - 1); val y1 = minOf(y0 + 1, this.height - 1)
            fun channel(c: Int): Int {
                fun at(px: Int, py: Int) = bytes[4 + (py * this.width + px) * 3 + c].toInt() and 255
                val top = at(x0, y0) * (1 - (x - x0)) + at(x1, y0) * (x - x0)
                val bottom = at(x0, y1) * (1 - (x - x0)) + at(x1, y1) * (x - x0)
                return (top * (1 - (y - y0)) + bottom * (y - y0) + 0.5).toInt()
            }
            (255 shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
        }
        return ImageEncoder.encode(argb, width, height)
    }

    companion object {
        const val MAX_DIMENSION = 128
        const val MAX_BYTES = 4 + MAX_DIMENSION * MAX_DIMENSION * 3
        private fun u16(bytes: ByteArray, offset: Int) =
            (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
        fun decode(bytes: ByteArray): LinuxNotificationImage? {
            if (bytes.size !in 7..MAX_BYTES) return null
            val w = u16(bytes, 0); val h = u16(bytes, 2)
            if (w !in 1..MAX_DIMENSION || h !in 1..MAX_DIMENSION || bytes.size != 4 + w * h * 3) return null
            return LinuxNotificationImage(bytes.copyOf())
        }
    }
}

/** Watch fetches are keyed by timeline UUID, including after the daemon restarts. */
class LinuxNotificationImageStore(
    directory: Path? = null,
) : NotificationImageProvider {
    private val directory: Path by lazy { directory ?: JvmPaths.cacheSubdir("notification-images") }

    suspend fun put(id: Uuid, image: LinuxNotificationImage): Boolean = withContext(Dispatchers.IO) {
        try {
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, ".image-", ".tmp")
            try {
                Files.write(temporary, image.encode())
                Files.move(temporary, file(id), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
            Files.list(directory).use { paths ->
                paths.filter { it.fileName.toString().endsWith(".rgb") && Files.isRegularFile(it, NOFOLLOW_LINKS) }
                    .sorted(compareByDescending { Files.getLastModifiedTime(it, NOFOLLOW_LINKS).toMillis() })
                    .skip(32).forEach { Files.deleteIfExists(it) }
            }
            true
        } catch (_: java.io.IOException) { false }
    }

    internal suspend fun image(id: Uuid, width: Int, height: Int): EncodedImage? {
        val image = withContext(Dispatchers.IO) {
            try {
                Files.newInputStream(file(id), NOFOLLOW_LINKS).use { input ->
                    LinuxNotificationImage.decode(input.readNBytes(LinuxNotificationImage.MAX_BYTES + 1))
                }
            } catch (_: java.io.IOException) { null }
        } ?: return null
        return withContext(Dispatchers.Default) { image.forWatch(width, height) }
    }

    override fun register(imagingService: ImagingService) {
        imagingService.registerHandler(Imaging.ImageType.NotificationImage) { request ->
            (request as? Imaging.NotificationImageRequest)?.let {
                image(it.itemId.get(), it.width.get().toInt(), it.height.get().toInt())
            }
        }
    }

    private fun file(id: Uuid): Path = directory.resolve("$id.rgb")
}
