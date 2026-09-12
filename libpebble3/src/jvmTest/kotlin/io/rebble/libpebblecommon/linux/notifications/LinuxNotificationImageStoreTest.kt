package io.rebble.libpebblecommon.linux.notifications

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*
import kotlin.uuid.Uuid

class LinuxNotificationImageStoreTest {
    @Test fun validatesAndEncodesThumbnails() {
        val bytes = byteArrayOf(1, 0, 1, 0, -1, 0, 0)
        val image = assertNotNull(LinuxNotificationImage.decode(bytes))
        bytes[4] = 0
        assertEquals(255, image.encode()[4].toInt() and 255)
        assertEquals(16u.toUByte(), image.aspect)
        val encoded = assertNotNull(image.forWatch(3, 2))
        assertEquals(3, encoded.width)
        assertEquals(2, encoded.height)
        assertEquals(4, encoded.pixels.size) // odd-width rows are padded
        assertNull(image.forWatch(0, 10))
        assertNull(image.forWatch(301, 10))
        assertNull(LinuxNotificationImage.decode(byteArrayOf(1, 0, 1, 0, -1)))
        assertNull(LinuxNotificationImage.decode(byteArrayOf(-1, 127, 1, 0, 0, 0, 0)))
    }

    @Test fun cachesAcrossRestartAndBoundsStorage() = runTest {
        val dir = Files.createTempDirectory("notification-images-test")
        try {
            val image = assertNotNull(LinuxNotificationImage.decode(byteArrayOf(1, 0, 1, 0, -1, 0, 0)))
            val store = LinuxNotificationImageStore(dir)
            val id = Uuid.random()
            assertTrue(store.put(id, image))
            assertNotNull(LinuxNotificationImageStore(dir).image(id, 20, 10))
            assertNull(store.image(Uuid.random(), 20, 10))
            Files.write(dir.resolve("$id.rgb"), byteArrayOf(0, 0))
            assertNull(store.image(id, 20, 10))
            repeat(34) { assertTrue(store.put(Uuid.random(), image)) }
            assertEquals(32L, Files.list(dir).use { it.count() })
        } finally { dir.toFile().deleteRecursively() }
    }
}
