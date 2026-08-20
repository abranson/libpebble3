package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.connection.WebAccountSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class LockerRemoteAccountSession(
    val generation: Long,
    val webSession: WebAccountSession?,
)

/** Serializes account transitions with the local commit boundary of remote locker operations. */
internal class LockerAccountMutationCoordinator {
    private val mutex = Mutex()
    private var generation = 0L

    suspend fun capture(webSession: () -> WebAccountSession?): LockerRemoteAccountSession =
        mutex.withLock { LockerRemoteAccountSession(generation, webSession()) }

    suspend fun <T> mutate(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun <T> mutateWithSession(
        webSession: () -> WebAccountSession?,
        block: suspend (LockerRemoteAccountSession) -> T,
    ): T = mutex.withLock { block(LockerRemoteAccountSession(generation, webSession())) }

    suspend fun <T> transition(block: suspend () -> T): T = mutex.withLock {
        // Advance even when the subsequent durable operation fails. An attempted account change
        // must retire requests which were already using an older credential snapshot.
        generation += 1
        block()
    }

    suspend fun commitIfCurrent(
        session: LockerRemoteAccountSession,
        isWebSessionCurrent: (WebAccountSession) -> Boolean,
        block: suspend () -> Boolean,
    ): Boolean = mutex.withLock {
        if (session.generation != generation) return@withLock false
        val webSession = session.webSession
        if (webSession != null && !isWebSessionCurrent(webSession)) return@withLock false
        block()
    }

    suspend fun commitIfWebSessionCurrent(
        session: WebAccountSession,
        isWebSessionCurrent: (WebAccountSession) -> Boolean,
        block: suspend () -> Unit,
    ): Boolean = mutex.withLock {
        if (!isWebSessionCurrent(session)) return@withLock false
        block()
        true
    }
}
