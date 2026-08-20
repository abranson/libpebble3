package io.rebble.libpebblecommon.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NegotiationDisconnectWatcherTest {
    @Test
    fun `watcher remains active until negotiation completes`() = runTest {
        val negotiation = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<ConnectionFailureReason>()
        var disconnects = 0
        val awaiting = async {
            awaitNegotiationOrDisconnect(negotiation, disconnected) {
                disconnects++
            }
        }

        runCurrent()
        assertFalse(negotiation.isCancelled)

        negotiation.complete(Unit)
        awaiting.await()

        disconnected.complete(ConnectionFailureReason.ClassicDisconnected)
        runCurrent()
        assertEquals(0, disconnects)
    }

    @Test
    fun `disconnect cancels an in-flight negotiation`() = runTest {
        val negotiation = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<ConnectionFailureReason>()
        var disconnects = 0
        val awaiting = async {
            awaitNegotiationOrDisconnect(negotiation, disconnected) {
                disconnects++
            }
        }

        runCurrent()
        disconnected.complete(ConnectionFailureReason.ClassicDisconnected)
        runCurrent()

        assertTrue(negotiation.isCancelled)
        assertEquals(1, disconnects)
        assertFailsWith<CancellationException> { awaiting.await() }
    }

    @Test
    fun `already disconnected transport wins over completed negotiation`() = runTest {
        val negotiation = CompletableDeferred<Unit>().apply { complete(Unit) }
        val disconnected = CompletableDeferred<ConnectionFailureReason>().apply {
            complete(ConnectionFailureReason.ClassicDisconnected)
        }
        var disconnects = 0

        assertFailsWith<CancellationException> {
            awaitNegotiationOrDisconnect(negotiation, disconnected) {
                disconnects++
            }
        }

        assertEquals(1, disconnects)
    }
}
