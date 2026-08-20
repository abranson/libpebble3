package io.rebble.libpebblecommon.connection.devconnection

import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.connection.endpointmanager.CompanionAppLifecycleManager
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal class DevConnectionOwnershipCoordinator {
    internal class Lease internal constructor(internal val owner: Any)
    internal class Acquisition internal constructor(
        internal val lease: Lease,
        internal val acquired: Boolean,
    )

    private val current = atomic<Lease?>(null)

    fun tryAcquire(owner: Any): Acquisition? {
        while (true) {
            val existing = current.value
            if (existing != null) {
                return if (existing.owner === owner) {
                    Acquisition(existing, acquired = false)
                } else {
                    null
                }
            }

            val lease = Lease(owner)
            if (current.compareAndSet(null, lease)) {
                return Acquisition(lease, acquired = true)
            }
        }
    }

    fun release(owner: Any, lease: Lease): Boolean =
        lease.owner === owner && current.compareAndSet(lease, null)
}

private val processDevConnectionOwnership = DevConnectionOwnershipCoordinator()

internal class DevConnectionTransitionGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T {
        mutex.lock()
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}

class DevConnectionManager(
    private val transport: Flow<DevConnectionTransport>,
    private val lanServer: DevConnectionServer,
    private val identifier: PebbleIdentifier,
    private val protocolHandler: PebbleProtocolHandler,
    private val companionAppLifecycleManager: CompanionAppLifecycleManager,
    private val scope: ConnectionCoroutineScope
): ConnectedPebble.DevConnection {
    private class ActiveRun(
        val lease: DevConnectionOwnershipCoordinator.Lease,
        val job: Job,
    )

    private val run = atomic<ActiveRun?>(null)
    private val active = MutableStateFlow(false)
    private val transitionGate = DevConnectionTransitionGate()
    override val devConnectionActive: StateFlow<Boolean> = active.asStateFlow()

    override suspend fun startDevConnection(forceLan: Boolean) {
        val transport = if (forceLan) MutableStateFlow(lanServer) else transport
        transitionGate.run {
            val acquisition = processDevConnectionOwnership.tryAcquire(this) ?: return@run
            if (!acquisition.acquired) return@run

            var publishedRun: ActiveRun? = null
            try {
                val inboundPKJSLogs = companionAppLifecycleManager.currentPKJSSession.flatMapLatest {
                    it?.logMessages?.receiveAsFlow() ?: emptyFlow()
                }
                val started = scope.launch(start = CoroutineStart.LAZY) {
                    var last: DevConnectionTransport? = null
                    try {
                        transport.collectLatest {
                            last?.stop()
                            // Assign before start(): start() suspends until cancelled, so the
                            // finally below would otherwise always see `last` as null.
                            last = it
                            it.start(
                                identifier,
                                inboundPKJSLogs,
                                protocolHandler.rawInboundMessages,
                            ) { message ->
                                protocolHandler.send(message)
                            }
                        }
                    } finally {
                        // Cancellation must not interrupt socket/websocket shutdown: global
                        // ownership remains held until this finally block has completed.
                        withContext(NonCancellable) {
                            last?.stop()
                        }
                    }
                }
                val activeRun = ActiveRun(acquisition.lease, started)
                publishedRun = activeRun
                run.value = activeRun
                active.value = true
                started.invokeOnCompletion {
                    if (run.compareAndSet(activeRun, null)) {
                        active.value = false
                        processDevConnectionOwnership.release(this, acquisition.lease)
                    }
                }
                started.start()
            } catch (e: Throwable) {
                publishedRun?.let { failedRun ->
                    withContext(NonCancellable) {
                        failedRun.job.cancelAndJoin()
                    }
                    if (run.compareAndSet(failedRun, null)) {
                        active.value = false
                    }
                }
                processDevConnectionOwnership.release(this, acquisition.lease)
                throw e
            }
        }
    }

    override suspend fun stopDevConnection() {
        // Hold the per-manager transition through cleanup. A later start must not observe this
        // same-owner lease as an idempotent active run and then get lost while stop finishes.
        // Completion callbacks do not acquire this gate, so joining here cannot deadlock them.
        transitionGate.run {
            val stopping = run.value ?: return@run
            withContext(NonCancellable) {
                stopping.job.cancelAndJoin()
            }
            if (run.compareAndSet(stopping, null)) {
                active.value = false
                processDevConnectionOwnership.release(this, stopping.lease)
            }
        }
    }
}
