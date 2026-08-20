package io.rebble.libpebblecommon.linux.web

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlin.coroutines.cancellation.CancellationException

/** One bounded JVM client policy for the daemon's Rebble/app-store requests. */
internal fun newRebbleHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000L
        connectTimeoutMillis = 5_000L
        socketTimeoutMillis = 10_000L
    }
}

internal fun Throwable.rethrowCancellation() {
    if (this is CancellationException) throw this
}
