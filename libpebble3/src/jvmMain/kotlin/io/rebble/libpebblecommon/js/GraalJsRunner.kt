package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyExecutable

/**
 * PKJS runtime on GraalJS (Truffle), compiled into the native image alongside the rest of
 * the daemon. Mirrors the iOS JavaScriptCore runner: one JS context confined to a single
 * thread, one host function (`__nativeDispatch`) bridging into per-object Kotlin interfaces,
 * pure-JS proxy objects, and the shared standard-lib scripts (XMLHttpRequest / timers /
 * WebSocket / startup) loaded from classpath resources before the app's own JS.
 */
class GraalJsRunner(
    private val appContext: AppContext,
    private val libPebble: LibPebble,
    private val jsTokenUtil: JsTokenUtil,
    device: CompanionAppDevice,
    private val scope: CoroutineScope,
    appInfo: PbwAppInfo,
    lockerEntry: LockerEntry,
    jsPath: Path,
    urlOpenRequests: Channel<String>,
    private val logMessages: Channel<String>,
    private val remoteTimelineEmulator: RemoteTimelineEmulator,
    private val httpInterceptorManager: HttpInterceptorManager,
    private val notificationConfigFlow: NotificationConfigFlow,
) : JsRunner(appInfo, lockerEntry, jsPath, device, urlOpenRequests) {
    private val logger = Logger.withTag("GraalJsRunner-${appInfo.longName}")

    // GraalJS contexts are single-threaded; every eval goes through this dispatcher.
    @OptIn(DelicateCoroutinesApi::class)
    private val threadContext = newSingleThreadContext("JSRunner-${appInfo.uuid}")

    private var jsContext: Context? = null
    private val interfaces = mutableMapOf<String, JvmJsInterface>()

    private fun evalNow(js: String): Value? = try {
        jsContext?.eval(Source.newBuilder("js", js, "<native>").buildLiteral())
    } catch (e: Exception) {
        logger.e { "JS exception: ${e.message}" }
        null
    }

    /** Fire-and-forget eval hopping to the JS thread; safe from any coroutine. */
    private fun evalAsync(js: String) {
        (scope + threadContext).launch { evalNow(js) }
    }

    private fun setupContext() {
        val context = Context.newBuilder("js")
            .allowExperimentalOptions(true)
            .option("js.ecmascript-version", "2022")
            .out(LoggingOutputStream(logger, false))
            .err(LoggingOutputStream(logger, true))
            .build()
        jsContext = context

        val interfacesScope = scope + threadContext
        val instances = listOf(
            XMLHTTPRequestManager(interfacesScope, ::evalAsync, httpInterceptorManager, appInfo),
            JsTimeout(interfacesScope, ::evalAsync),
            WebSocketManager(interfacesScope, ::evalAsync),
            JvmPKJSInterface(this, device, libPebble, jsTokenUtil),
            JvmPrivatePKJSInterface(
                this, device, interfacesScope, _outgoingAppMessages, logMessages, jsTokenUtil,
                remoteTimelineEmulator, httpInterceptorManager, notificationConfigFlow,
            ),
            JvmLocalStorageInterface(appInfo.uuid, appContext),
            JvmGeolocationInterface(interfacesScope, this),
        )
        instances.forEach { interfaces[it.name] = it }

        val bindings = context.getBindings("js")
        bindings.putMember(
            "__nativeDispatch",
            ProxyExecutable { args ->
                val objectName = args.getOrNull(0)?.asString() ?: return@ProxyExecutable null
                val methodName = args.getOrNull(1)?.asString() ?: return@ProxyExecutable null
                val jsArgs = args.getOrNull(2)?.toKotlinList() ?: emptyList()
                try {
                    interfaces[objectName]?.dispatch(methodName, jsArgs)
                } catch (e: Exception) {
                    logger.e("dispatch $objectName.$methodName failed", e)
                    null
                }
            },
        )

        // navigator must exist before startup.js runs (it wires geolocation onto it).
        val language = System.getProperty("user.language") ?: "en"
        val country = System.getProperty("user.country") ?: ""
        val locale = if (country.isEmpty()) language else "$language-$country"
        evalNow("var navigator = { userAgent: 'PKJS', language: '$locale', geolocation: {} };")

        instances.forEach { iface ->
            val methods = iface.methods.joinToString(",") { "'$it'" }
            evalNow(
                """
                var ${iface.name} = {};
                [$methods].forEach(function(m) {
                    ${iface.name}[m] = function() {
                        return __nativeDispatch('${iface.name}', m, Array.from(arguments));
                    };
                });
                """.trimIndent()
            )
            iface.onRegister { js -> evalNow(js) }
        }
    }

    private fun evaluateResourceScript(nameNoExt: String) {
        val resource = "/pkjs/$nameNoExt.js"
        val js = javaClass.getResourceAsStream(resource)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("PKJS script missing from classpath: $resource")
        jsContext?.eval(Source.newBuilder("js", js, "$nameNoExt.js").buildLiteral())
    }

    override suspend fun start() {
        withContext(threadContext) {
            setupContext()
            evaluateResourceScript("XMLHTTPRequest")
            evaluateResourceScript("JSTimeout")
            evaluateResourceScript("WebSocket")
            evaluateResourceScript("startup")
            logger.d { "JS context ready" }
        }
        loadAppJs(jsPath.toString())
    }

    override suspend fun stop() {
        _readyState.value = false
        withContext(threadContext) {
            interfaces.values.forEach { runCatching { it.close() } }
            interfaces.clear()
            runCatching { jsContext?.close(true) }
            jsContext = null
        }
        threadContext.close()
    }

    override suspend fun loadAppJs(jsUrl: String) {
        withContext(threadContext) {
            val js = SystemFileSystem.source(Path(jsUrl)).buffered().use { it.readString() }
            try {
                jsContext?.eval(Source.newBuilder("js", js, "${appInfo.uuid}.js").buildLiteral())
            } catch (e: Exception) {
                logger.e { "app JS threw: ${e.message}" }
            }
        }
        signalReady()
    }

    override suspend fun signalInterceptResponse(callbackId: String, result: InterceptResponse) {
        // Interception happens inside XMLHTTPRequestManager (iOS model), not via JS signal.
    }

    override suspend fun signalNewAppMessageData(data: String?): Boolean {
        withContext(threadContext) {
            evalNow("globalThis.signalNewAppMessageData(${Json.encodeToString(data)})")
        }
        return true
    }

    override suspend fun signalTimelineToken(callId: String, token: String) {
        val json = Json.encodeToString(mapOf("userToken" to token, "callId" to callId))
        withContext(threadContext) { evalNow("globalThis.signalTimelineTokenSuccess($json)") }
    }

    override suspend fun signalTimelineTokenFail(callId: String) {
        val json = Json.encodeToString(mapOf("userToken" to null, "callId" to callId))
        withContext(threadContext) { evalNow("globalThis.signalTimelineTokenFailure($json)") }
    }

    override suspend fun signalReady() {
        withContext(threadContext) { evalNow("globalThis.signalReady()") }
    }

    override suspend fun signalShowConfiguration() {
        withContext(threadContext) { evalNow("globalThis.signalShowConfiguration()") }
    }

    override suspend fun signalWebviewClosed(data: String?) {
        withContext(threadContext) {
            evalNow("globalThis.signalWebviewClosedEvent(${Json.encodeToString(data)})")
        }
    }

    override suspend fun eval(js: String) {
        withContext(threadContext) { evalNow(js) }
    }

    override suspend fun evalWithResult(js: String): Any? = withContext(threadContext) {
        evalNow(js)?.toKotlin()
    }

    override fun debugForceGC() {}
}

/** Convert a polyglot Value to the host types the dispatch tables expect (JSC-compatible). */
private fun Value.toKotlin(): Any? = when {
    isNull -> null
    isBoolean -> asBoolean()
    isNumber -> asDouble()
    isString -> asString()
    hasArrayElements() -> toKotlinList()
    else -> this
}

private fun Value.toKotlinList(): List<Any?> =
    (0 until arraySize).map { getArrayElement(it).toKotlin() }

private class LoggingOutputStream(
    private val logger: Logger,
    private val isError: Boolean,
) : java.io.OutputStream() {
    private val buffer = StringBuilder()

    override fun write(b: Int) {
        if (b == '\n'.code) {
            flushLine()
        } else {
            buffer.append(b.toChar())
        }
    }

    private fun flushLine() {
        if (buffer.isEmpty()) return
        val line = buffer.toString()
        buffer.clear()
        if (isError) logger.e { "js: $line" } else logger.d { "js: $line" }
    }
}
