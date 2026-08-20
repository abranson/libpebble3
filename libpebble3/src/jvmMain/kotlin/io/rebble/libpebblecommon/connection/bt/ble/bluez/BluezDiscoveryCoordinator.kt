package io.rebble.libpebblecommon.connection.bt.ble.bluez

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializes discovery ownership for one D-Bus connection.
 *
 * BlueZ discovery filters and StartDiscovery/StopDiscovery ownership belong to the calling
 * D-Bus sender, not to an individual scanner. A generation token makes cleanup from a cancelled
 * scanner harmless after another scanner has replaced it.
 */
internal class BluezDiscoveryCoordinator {
    private data class ActiveDiscovery(
        val generation: Long,
        val stop: () -> Unit,
    )

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "libpebble3-bluez-discovery").apply { isDaemon = true }
    }
    private var generation = 0L
    private var active: ActiveDiscovery? = null

    fun acquire(
        cancelled: () -> Boolean,
        start: () -> Unit,
        stop: () -> Unit,
    ): Lease? = worker.submit<Lease?> {
        // A cancelled setup that was waiting behind another D-Bus operation must not evict the
        // live owner when it finally reaches the front of the queue.
        if (cancelled()) return@submit null

        val nextGeneration = ++generation
        active.also { active = null }?.let { runCatching(it.stop) }
        if (cancelled()) return@submit null

        try {
            start()
        } catch (e: Throwable) {
            runCatching(stop)
            throw e
        }
        if (cancelled()) {
            runCatching(stop)
            return@submit null
        }

        active = ActiveDiscovery(nextGeneration, stop)
        Lease(this, nextGeneration)
    }.await()

    fun stopCurrent(stopWhenUnowned: (() -> Unit)? = null): Boolean = worker.submit<Boolean> {
        val current = active
        if (current == null) {
            stopWhenUnowned?.let { runCatching(it) }
            return@submit false
        }
        ++generation
        active = null
        runCatching(current.stop)
        true
    }.await()

    private fun release(leaseGeneration: Long) {
        worker.execute {
            val current = active ?: return@execute
            if (current.generation != leaseGeneration) return@execute
            ++generation
            active = null
            runCatching(current.stop)
        }
    }

    private fun <T> Future<T>.await(): T = try {
        get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    class Lease internal constructor(
        private val owner: BluezDiscoveryCoordinator,
        private val generation: Long,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) owner.release(generation)
        }
    }
}
