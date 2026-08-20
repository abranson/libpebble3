package io.rebble.libpebblecommon.web

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.RequestSync
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.locker.Locker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch

/**
 * This will manage scheduling background tasks etc. For now, it just triggers stuff to happen
 * immediately (in a way that might be killed if we're not in the foreground etc).
 */
class WebSyncManager(
    private val webServices: WebServices,
    private val locker: Locker,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
) : RequestSync {
    private val logger = Logger.withTag("WebSyncManager")

    override fun requestLockerSync(): Deferred<Unit> {
        logger.d { "requestLockerSync()" }
        val result = CompletableDeferred<Unit>()
        val job = libPebbleCoroutineScope.launch {
            try {
                // TODO probably don't want the logic in this class
                val session = locker.captureRemoteAccountSession()
                val response = when (val webSession = session.webSession) {
                    null -> if (webServices.usesAccountSessions()) null else webServices.fetchLocker()
                    else -> webServices.fetchLocker(webSession)
                }
                if (response == null) {
                    throw IllegalStateException("Locker response is null")
                }
                locker.update(response, session)
                result.complete(Unit)
            } catch (e: CancellationException) {
                result.cancel(e)
                throw e
            } catch (t: Throwable) {
                result.completeExceptionally(t)
                if (t !is Exception) throw t
            }
        }
        result.invokeOnCompletion { cause ->
            if (cause is CancellationException) job.cancel(cause)
        }
        return result
    }
}
