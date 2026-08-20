package io.rebble.libpebblecommon.connection.bt.classic.transport

import io.ktor.utils.io.readByteArray
import io.rebble.libpebblecommon.connection.ConnectionFailureReason
import io.rebble.libpebblecommon.connection.PebbleProtocolStreams
import io.rebble.libpebblecommon.connection.asPebbleBtClassicIdentifier
import io.rebble.libpebblecommon.connection.bt.classic.pebble.ClassicConnectionResult
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmBtClassicConnectorTest {
    @Test
    fun bondsBeforeOpeningChannelOneAndPumpsBothDirections() = runBlocking {
        val events = mutableListOf<String>()
        val socket = FakeSocket(maxWriteSize = 2)
        socket.offerRead(byteArrayOf(1, 2, 3))
        val connector = connector(socket, ensureBonded = {
            events += "bond"
            true
        }) { address, channel ->
            events += "create:$address:$channel"
            socket
        }

        val connection = async { connector.connect() }
        socket.connectEntered.await()
        assertEquals(ClassicConnectionResult.Success, connection.await())
        assertEquals(listOf("bond", "create:D0:81:0A:D4:D7:CD:1"), events)
        assertEquals(30_000L, socket.connectTimeoutMillis)
        assertContentEquals(byteArrayOf(1, 2, 3), withTimeout(1_000) {
            streams.inboundPPBytes.readByteArray(3)
        })

        streams.outboundPPBytes.send(byteArrayOf(4, 5, 6, 7, 8))
        withTimeout(1_000) {
            while (socket.writtenBytes().size < 5) delay(1)
        }
        assertContentEquals(byteArrayOf(4, 5, 6, 7, 8), socket.writtenBytes())
        assertEquals(1, socket.flushes.get())

        connector.disconnect()
        connector.disconnect()
        assertEquals(ConnectionFailureReason.ClassicDisconnected, connector.disconnected.await())
        withTimeout(1_000) { while (socket.closeCalls.get() != 1) delay(1) }
        assertEquals(1, socket.shutdownCalls.get())
    }

    @Test
    fun disconnectInterruptsAnInFlightConnectAndClosesOnce() = runTest {
        val socket = FakeSocket(blockConnect = true)
        val connector = connector(socket, ensureBonded = { true }) { _, _ -> socket }

        val result = launch { assertEquals(ClassicConnectionResult.Failure, connector.connect()) }
        socket.connectEntered.await()
        connector.disconnect()
        result.join()

        assertEquals(ConnectionFailureReason.ClassicDisconnected, connector.disconnected.await())
        withTimeout(1_000) { while (socket.closeCalls.get() != 1) delay(1) }
        assertEquals(1, socket.shutdownCalls.get())
    }

    @Test
    fun preservesCancellationFromSocketConnect() = runBlocking {
        val socket = FakeSocket(connectFailure = CancellationException("cancelled"))
        val connector = connector(socket, ensureBonded = { true }) { _, _ -> socket }

        assertFailsWith<CancellationException> { connector.connect() }
        assertEquals(ConnectionFailureReason.ClassicDisconnected, connector.disconnected.await())
    }

    @Test
    fun unavailableGenericFactoryFailsWithoutOpeningAConnection() = runTest {
        val connector = connector(
            socket = null,
            ensureBonded = { true },
            factory = UnavailableLinuxRfcommSocketFactory,
        )

        assertEquals(ClassicConnectionResult.Failure, connector.connect())
        assertEquals(ConnectionFailureReason.ClassicDisconnected, connector.disconnected.await())
    }

    @Test
    fun bondFailureDoesNotCreateASocket() = runTest {
        var createCalls = 0
        val connector = connector(
            socket = null,
            ensureBonded = { false },
            factory = LinuxRfcommSocketFactory { _, _ ->
                createCalls++
                error("factory must not be called after bond failure")
            },
        )

        assertEquals(ClassicConnectionResult.Failure, connector.connect())
        assertEquals(0, createCalls)
    }

    @Test
    fun timeoutInterruptsConnectBeforeClosingTheSocket() = runTest {
        val socket = FakeSocket(blockConnect = true)
        val connector = connector(socket, ensureBonded = { true }) { _, _ -> socket }

        val connection = async { connector.connect() }
        socket.connectEntered.await()
        assertEquals(ClassicConnectionResult.Failure, connection.await())
        assertEquals(30_000L, socket.connectTimeoutMillis)
        assertEquals(1, socket.shutdownCalls.get())
        withTimeout(1_000) { while (socket.closeCalls.get() != 1) delay(1) }
    }

    @Test
    fun rejectedConcurrentSocketDoesNotSuppressShutdownOfTheInstalledSocket() = runTest {
        val installed = FakeSocket(blockConnect = true)
        val rejected = FakeSocket()
        var factoryCall = 0
        val connector = connector(
            socket = installed,
            ensureBonded = { true },
            factory = LinuxRfcommSocketFactory { _, _ ->
                if (factoryCall++ == 0) installed else rejected
            },
        )

        val first = launch { assertEquals(ClassicConnectionResult.Failure, connector.connect()) }
        installed.connectEntered.await()
        assertEquals(ClassicConnectionResult.Failure, connector.connect())
        connector.disconnect()
        first.join()

        assertEquals(1, rejected.shutdownCalls.get())
        assertEquals(1, installed.shutdownCalls.get())
    }

    private lateinit var streams: PebbleProtocolStreams

    private suspend fun connector(
        socket: FakeSocket?,
        ensureBonded: suspend () -> Boolean,
        factory: LinuxRfcommSocketFactory = LinuxRfcommSocketFactory { _, _ -> socket },
    ): JvmBtClassicConnector {
        streams = PebbleProtocolStreams()
        return JvmBtClassicConnector.forTest(
            identifier = "D0:81:0A:D4:D7:CD".asPebbleBtClassicIdentifier(),
            pebbleProtocolStreams = streams,
            connectionCoroutineScope = ConnectionCoroutineScope(coroutineContext),
            libPebbleCoroutineScope = LibPebbleCoroutineScope(coroutineContext),
            socketFactory = factory,
            ensureBonded = ensureBonded,
        )
    }

    private class FakeSocket(
        private val maxWriteSize: Int = Int.MAX_VALUE,
        private val blockConnect: Boolean = false,
        private val connectFailure: Throwable? = null,
    ) : LinuxRfcommSocket {
        private sealed interface ReadResult {
            data class Bytes(val value: ByteArray) : ReadResult
            data object Eof : ReadResult
        }

        private val reads = Channel<ReadResult>(Channel.UNLIMITED)
        val connectEntered = CompletableDeferred<Unit>()
        var connectTimeoutMillis: Long = -1
        val written = ArrayList<Byte>()
        val flushes = AtomicInteger()
        val shutdownCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        private val connectReleased = CompletableDeferred<Unit>()

        fun offerRead(bytes: ByteArray) {
            assertTrue(reads.trySend(ReadResult.Bytes(bytes)).isSuccess)
        }

        fun writtenBytes(): ByteArray = synchronized(written) { written.toByteArray() }

        override suspend fun connect(timeoutMillis: Long): Boolean {
            connectTimeoutMillis = timeoutMillis
            connectEntered.complete(Unit)
            if (blockConnect) connectReleased.await()
            connectFailure?.let { throw it }
            return shutdownCalls.get() == 0
        }

        override suspend fun read(buffer: ByteArray): Int = when (val result = reads.receive()) {
            is ReadResult.Bytes -> {
                result.value.copyInto(buffer)
                result.value.size
            }
            ReadResult.Eof -> -1
        }

        override suspend fun write(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = minOf(length, maxWriteSize)
            synchronized(written) {
                repeat(count) { written += buffer[offset + it] }
            }
            return count
        }

        override suspend fun flush() {
            flushes.incrementAndGet()
        }

        override fun shutdown() {
            if (shutdownCalls.incrementAndGet() == 1) {
                connectReleased.complete(Unit)
                reads.trySend(ReadResult.Eof)
            }
        }

        override suspend fun close() {
            closeCalls.incrementAndGet()
        }
    }
}
