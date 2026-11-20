package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext

actual class JsEngine actual constructor(
    appContext: AppContext,
    scope: CoroutineScope,
    name: String,
    private val interfaces: List<JsEngineInterface>,
) {
    private val runtime = GraalJsRuntime(Logger.withTag("JsEngine-$name"))
    @OptIn(DelicateCoroutinesApi::class)
    private val threadContext = newSingleThreadContext("JsEngine-$name")

    actual suspend fun start() = withContext(threadContext) {
        runtime.start(interfaces)
    }

    actual suspend fun eval(js: String) {
        withContext(threadContext) { runtime.eval(js) }
    }

    actual suspend fun stop() {
        try {
            withContext(NonCancellable + threadContext) { runtime.stop() }
        } finally {
            threadContext.close()
        }
    }
}
