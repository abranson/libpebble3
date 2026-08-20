package io.rebble.libpebblecommon.connection.devconnection

import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DevConnectionTransportJvmTest {
    @Test
    fun temporaryPbwPathsAreUnique() {
        val paths = List(16) { getTempPbwPath().toString() }

        assertEquals(paths.size, paths.toSet().size)
        assertTrue(paths.all { it.substringAfterLast('/').matches(Regex("devconn-[0-9a-f-]+\\.pbw")) })
    }

    @Test
    fun temporaryPbwIsPrivateToTheRequestAndDeletedAfterSideload() = runTest {
        val payload = "unique developer bundle".encodeToByteArray()
        var observedPath: java.nio.file.Path? = null
        val libPebble = object : LibPebble by FakeLibPebble() {
            override suspend fun sideloadApp(pbwPath: Path): Boolean {
                val file = Paths.get(pbwPath.toString())
                observedPath = file
                assertContentEquals(payload, Files.readAllBytes(file))
                return true
            }
        }

        assertTrue(TestTransport(libPebble).install(payload))

        val file = assertNotNull(observedPath)
        assertFalse(Files.exists(file))
    }

    private class TestTransport(libPebble: LibPebble) : DevConnectionTransport(libPebble) {
        suspend fun install(payload: ByteArray): Boolean = installPBW(payload)

        override suspend fun start(
            identifier: PebbleIdentifier,
            inboundPKJSLogs: Flow<String>,
            inboundDeviceMessages: Flow<ByteArray>,
            outboundDeviceMessages: suspend (ByteArray) -> Unit,
        ) = Unit

        override suspend fun stop() = Unit
    }
}
