package io.rebble.libpebblecommon.js

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class PKJSAppConfigurationTest {
    @Test
    fun `cancelled configuration request cannot consume the next URL`() = runTest {
        val urls = Channel<String>()
        val mutex = Mutex()

        val timedOut = withTimeoutOrNull(1.seconds) {
            requestPKJSConfigurationUrl(mutex, urls) {}
        }
        assertNull(timedOut)

        val next = async {
            requestPKJSConfigurationUrl(mutex, urls) {
                urls.send("https://example.test/config")
            }
        }
        assertEquals("https://example.test/config", next.await())
    }

    @Test
    fun `overlapping configuration requests keep their URLs separate`() = runTest {
        val urls = Channel<String>(Channel.UNLIMITED)
        val mutex = Mutex()
        val firstSignalled = CompletableDeferred<Unit>()
        val allowFirstSignalToFinish = CompletableDeferred<Unit>()
        var secondSignalled = false

        val first = async {
            requestPKJSConfigurationUrl(mutex, urls) {
                firstSignalled.complete(Unit)
                allowFirstSignalToFinish.await()
            }
        }
        firstSignalled.await()

        val second = async {
            requestPKJSConfigurationUrl(mutex, urls) {
                secondSignalled = true
                urls.send("second")
            }
        }
        runCurrent()
        assertEquals(false, secondSignalled)

        allowFirstSignalToFinish.complete(Unit)
        urls.send("first")
        assertEquals("first", first.await())
        assertEquals("second", second.await())
    }
}
