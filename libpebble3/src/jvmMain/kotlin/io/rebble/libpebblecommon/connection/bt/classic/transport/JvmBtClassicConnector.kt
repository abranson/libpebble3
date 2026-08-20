package io.rebble.libpebblecommon.connection.bt.classic.transport

import co.touchlab.kermit.Logger
import io.ktor.utils.io.writeByteArray
import io.rebble.libpebblecommon.connection.ConnectionFailureReason
import io.rebble.libpebblecommon.connection.PebbleBtClassicIdentifier
import io.rebble.libpebblecommon.connection.PebbleProtocolStreams
import io.rebble.libpebblecommon.connection.bt.classic.pebble.BtClassicConnector
import io.rebble.libpebblecommon.connection.bt.classic.pebble.ClassicConnectionResult
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebblePairing
import io.rebble.libpebblecommon.connection.bt.isBondedClassic
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * An RFCOMM byte stream supplied by the native Linux Bluetooth integration.
 *
 * The generic JVM build deliberately has no direct outbound channel-1 RFCOMM implementation yet,
 * so [UnavailableLinuxRfcommSocketFactory] returns no socket. Platforms can replace the factory
 * through Koin without changing the Classic transport lifecycle.
 */
interface LinuxRfcommSocket {
    /** Connects before [timeoutMillis] expires. False means the connection was refused or timed out. */
    suspend fun connect(timeoutMillis: Long): Boolean

    /** Returns -1 at EOF. */
    suspend fun read(buffer: ByteArray): Int

    /** Writes at most [length] bytes and returns the number accepted. */
    suspend fun write(buffer: ByteArray, offset: Int, length: Int): Int

    suspend fun flush()

    /** Interrupts any pending connect, read, or write without waiting for them to return. */
    fun shutdown()

    /** Releases the underlying descriptor after its reader and writer have stopped. */
    suspend fun close()
}

fun interface LinuxRfcommSocketFactory {
    /** Creates a socket for the legacy Pebble RFCOMM [channel], or null when unsupported. */
    fun create(address: String, channel: Int): LinuxRfcommSocket?
}

/** Generic JVM fallback for platforms that do not supply a direct RFCOMM socket. */
object UnavailableLinuxRfcommSocketFactory : LinuxRfcommSocketFactory {
    override fun create(address: String, channel: Int): LinuxRfcommSocket? = null
}

/**
 * Direct RFCOMM transport for legacy Pebbles on JVM/Linux.
 *
 * The factory is intentionally a narrow seam: direct outbound RFCOMM setup is platform specific,
 * while pairing, connection timing, protocol pumps and shutdown semantics stay shared.
 */
class JvmBtClassicConnector private constructor(
    private val identifier: PebbleBtClassicIdentifier,
    private val pebbleProtocolStreams: PebbleProtocolStreams,
    private val connectionCoroutineScope: ConnectionCoroutineScope,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val socketFactory: LinuxRfcommSocketFactory,
    private val ensureBonded: suspend () -> Boolean,
) : BtClassicConnector {
    constructor(
        identifier: PebbleBtClassicIdentifier,
        pairing: PebblePairing,
        pebbleProtocolStreams: PebbleProtocolStreams,
        connectionCoroutineScope: ConnectionCoroutineScope,
        libPebbleCoroutineScope: LibPebbleCoroutineScope,
        socketFactory: LinuxRfcommSocketFactory,
    ) : this(
        identifier = identifier,
        pebbleProtocolStreams = pebbleProtocolStreams,
        connectionCoroutineScope = connectionCoroutineScope,
        libPebbleCoroutineScope = libPebbleCoroutineScope,
        socketFactory = socketFactory,
        ensureBonded = {
            isBondedClassic(identifier) || pairing.requestClassicPairing(identifier) == null
        },
    )

    private val logger = Logger.withTag("JvmBtClassicConnector/${identifier.asString}")
    private val lifecycleLock = Any()
    private val _disconnected = CompletableDeferred<ConnectionFailureReason>()
    private var socket: LinuxRfcommSocket? = null
    private val pumpJobs = mutableSetOf<Job>()
    private var closed = false
    private var shutdownSocket: LinuxRfcommSocket? = null

    override val disconnected: Deferred<ConnectionFailureReason> = _disconnected

    override suspend fun connect(): ClassicConnectionResult {
        if (isClosed() || !ensureBonded()) {
            shutdown(ConnectionFailureReason.ClassicDisconnected)
            return ClassicConnectionResult.Failure
        }

        val newSocket = try {
            socketFactory.create(identifier.asString, LEGACY_RFCOMM_CHANNEL)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.w(e) { "Could not create RFCOMM socket" }
            null
        } ?: run {
            logger.w { "RFCOMM unavailable for ${identifier.asString}" }
            shutdown(ConnectionFailureReason.ClassicDisconnected)
            return ClassicConnectionResult.Failure
        }

        if (!installSocket(newSocket)) {
            closeSocketImmediately(newSocket)
            return ClassicConnectionResult.Failure
        }

        val connectJob = libPebbleCoroutineScope.async(Dispatchers.IO) {
            newSocket.connect(CONNECT_TIMEOUT.inWholeMilliseconds)
        }
        try {
            val connected = withTimeout(CONNECT_TIMEOUT) {
                connectJob.await()
            }
            if (!connected) {
                shutdown(ConnectionFailureReason.ClassicDisconnected)
                return ClassicConnectionResult.Failure
            }
        } catch (e: TimeoutCancellationException) {
            logger.w { "RFCOMM connection timed out" }
            stopConnecting(newSocket, connectJob)
            shutdown(ConnectionFailureReason.ClassicDisconnected)
            return ClassicConnectionResult.Failure
        } catch (e: CancellationException) {
            stopConnecting(newSocket, connectJob)
            shutdown(ConnectionFailureReason.ClassicDisconnected)
            throw e
        } catch (e: Throwable) {
            logger.w(e) { "RFCOMM connection failed" }
            stopConnecting(newSocket, connectJob)
            shutdown(ConnectionFailureReason.ClassicDisconnected)
            return ClassicConnectionResult.Failure
        }

        if (!isCurrentSocket(newSocket)) {
            // disconnect() may have closed the socket while connect() was blocked.
            return ClassicConnectionResult.Failure
        }

        startPumps(newSocket)
        return if (isCurrentSocket(newSocket)) {
            ClassicConnectionResult.Success
        } else {
            ClassicConnectionResult.Failure
        }
    }

    override suspend fun disconnect() {
        shutdown(ConnectionFailureReason.ClassicDisconnected)
    }

    private fun startPumps(connectedSocket: LinuxRfcommSocket) {
        val reader = connectionCoroutineScope.launch(start = CoroutineStart.LAZY) {
            try {
                readPump(connectedSocket)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.w(e) { "RFCOMM reader stopped" }
            } finally {
                shutdown(ConnectionFailureReason.ClassicDisconnected)
            }
        }
        val writer = connectionCoroutineScope.launch(start = CoroutineStart.LAZY) {
            try {
                pebbleProtocolStreams.outboundPPBytes.consumeAsFlow().collect { bytes ->
                    writeFully(connectedSocket, bytes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.w(e) { "RFCOMM writer stopped" }
            } finally {
                shutdown(ConnectionFailureReason.ClassicDisconnected)
            }
        }

        val start = synchronized(lifecycleLock) {
            if (closed || socket !== connectedSocket) {
                false
            } else {
                pumpJobs += reader
                pumpJobs += writer
                true
            }
        }
        if (start) {
            reader.start()
            writer.start()
        } else {
            reader.cancel()
            writer.cancel()
        }
    }

    private suspend fun readPump(connectedSocket: LinuxRfcommSocket) {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (true) {
            val read = connectedSocket.read(buffer)
            if (read < 0) return
            if (read > buffer.size) {
                throw IOException("RFCOMM socket returned $read bytes for a ${buffer.size}-byte buffer")
            }
            if (read == 0) {
                // A nonblocking native bridge may use zero for "try again". Yield so it cannot
                // turn an empty RFCOMM read into a busy loop.
                yield()
                continue
            }
            if (read > 0) {
                pebbleProtocolStreams.inboundPPBytes.writeByteArray(buffer.copyOf(read))
                pebbleProtocolStreams.inboundPPBytes.flush()
            }
        }
    }

    private suspend fun writeFully(connectedSocket: LinuxRfcommSocket, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val written = connectedSocket.write(bytes, offset, bytes.size - offset)
            if (written <= 0 || written > bytes.size - offset) {
                throw IOException("RFCOMM socket made no forward write progress")
            }
            offset += written
        }
        connectedSocket.flush()
    }

    private fun installSocket(newSocket: LinuxRfcommSocket): Boolean = synchronized(lifecycleLock) {
        if (closed || socket != null) {
            false
        } else {
            socket = newSocket
            shutdownSocket = null
            true
        }
    }

    private fun isCurrentSocket(candidate: LinuxRfcommSocket): Boolean = synchronized(lifecycleLock) {
        !closed && socket === candidate
    }

    private fun isClosed(): Boolean = synchronized(lifecycleLock) { closed }

    private fun shutdown(reason: ConnectionFailureReason) {
        val state = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            val oldSocket = socket
            socket = null
            oldSocket to pumpJobs.toList().also { pumpJobs.clear() }
        }
        _disconnected.complete(reason)
        state.second.forEach { it.cancel() }
        state.first?.let { oldSocket ->
            requestSocketShutdown(oldSocket)
            libPebbleCoroutineScope.launch(Dispatchers.IO) {
                state.second.joinAll()
                closeSocketAfterPumps(oldSocket)
            }
        }
    }

    private suspend fun closeSocketAfterPumps(socket: LinuxRfcommSocket) {
        // The RFCOMM FD is released only after both protocol pumps have returned.
        // The connection scope keeps this cleanup alive for ordinary disconnects.
        try {
            socket.close()
        } catch (e: Throwable) {
            logger.w(e) { "Error closing RFCOMM socket" }
        }
    }

    private fun closeSocketImmediately(socket: LinuxRfcommSocket) {
        requestSocketShutdown(socket)
        libPebbleCoroutineScope.launch(Dispatchers.IO) {
            try {
                socket.close()
            } catch (e: Throwable) {
                logger.w(e) { "Error closing RFCOMM socket" }
            }
        }
    }

    private suspend fun stopConnecting(socket: LinuxRfcommSocket, connectJob: Job) {
        requestSocketShutdown(socket)
        // connectJob is deliberately outside the caller's connection coroutine.  Closing an FD
        // while native connect is still using it races descriptor reuse, so wait for it first.
        withContext(NonCancellable) { connectJob.join() }
    }

    private fun requestSocketShutdown(socket: LinuxRfcommSocket) {
        val shouldShutdown = synchronized(lifecycleLock) {
            if (shutdownSocket === socket) false else {
                shutdownSocket = socket
                true
            }
        }
        if (shouldShutdown) {
            runCatching { socket.shutdown() }
                .onFailure { logger.w(it) { "Error shutting down RFCOMM socket" } }
        }
    }

    internal companion object {
        fun forTest(
            identifier: PebbleBtClassicIdentifier,
            pebbleProtocolStreams: PebbleProtocolStreams,
            connectionCoroutineScope: ConnectionCoroutineScope,
            libPebbleCoroutineScope: LibPebbleCoroutineScope,
            socketFactory: LinuxRfcommSocketFactory,
            ensureBonded: suspend () -> Boolean,
        ) = JvmBtClassicConnector(
            identifier,
            pebbleProtocolStreams,
            connectionCoroutineScope,
            libPebbleCoroutineScope,
            socketFactory,
            ensureBonded,
        )

        val CONNECT_TIMEOUT = 30.seconds
        const val LEGACY_RFCOMM_CHANNEL = 1
        const val READ_BUFFER_SIZE = 500
    }
}
