package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.permissions.AppPermissions
import io.rebble.libpebblecommon.permissions.DataUsageRecorder
import io.rebble.libpebblecommon.permissions.NetworkGate
import io.rebble.libpebblecommon.plugin.PluginClient
import io.rebble.libpebblecommon.plugin.PluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.NonCancellable
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
import kotlin.uuid.Uuid

/**
 * PKJS runtime on GraalJS (Truffle), compiled into the native image alongside the rest of
 * the daemon. Mirrors the iOS JavaScriptCore runner: one JS context confined to a single
 * thread, one host function (`__nativeDispatch`) bridging into per-object Kotlin interfaces,
 * pure-JS proxy objects, and shared standard-library scripts loaded before the app's own JS.
 */
class GraalJsRunner(
    private val appContext: AppContext,
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
    private val pluginRegistry: PluginRegistry,
    private val permissions: AppPermissions,
    private val pluginClient: PluginClient,
    private val dataUsage: DataUsageRecorder,
    private val httpClient: HttpClient,
) : JsRunner(appInfo, lockerEntry, jsPath, device, urlOpenRequests) {
    private val logger = Logger.withTag("GraalJsRunner-${appInfo.longName}")

    // GraalJS contexts are single-threaded; every eval goes through this dispatcher.
    @OptIn(DelicateCoroutinesApi::class)
    private val threadContext = newSingleThreadContext("JSRunner-${appInfo.uuid}")

    private val appUuid = Uuid.parse(appInfo.uuid)
    override val networkAccess = NetworkGate(permissions, appInfo.shortName, appUuid)
    private val runtime = GraalJsRuntime(logger)

    private fun evalAsync(js: String) {
        (scope + threadContext).launch { runtime.eval(js) }
    }

    private fun setupContext() {
        val interfacesScope = scope + threadContext
        runtime.start(listOf(
            XMLHTTPRequestManager(interfacesScope, ::evalAsync, httpInterceptorManager, appUuid, httpClient, networkAccess, dataUsage),
            JsTimeout(interfacesScope, ::evalAsync),
            WebSocketManager(interfacesScope, ::evalAsync, httpClient, appUuid, networkAccess, dataUsage),
            JvmPKJSInterface(this, device, remoteTimelineEmulator, jsTokenUtil),
            JvmPrivatePKJSInterface(
                this, device, interfacesScope, _outgoingAppMessages, logMessages, jsTokenUtil,
                remoteTimelineEmulator, httpInterceptorManager, notificationConfigFlow, pluginRegistry,
                permissions, pluginClient,
            ),
            JvmLocalStorageInterface(appInfo.uuid, appContext),
            EngineGeolocation(interfacesScope, ::eval, appUuid, prompt = true),
        ))

        val locale = java.util.Locale.getDefault().toLanguageTag()
        runtime.eval("var navigator = { userAgent: 'PKJS', language: ${Json.encodeToString(locale)}, geolocation: {} };")
    }

    private fun evaluateResourceScript(nameNoExt: String) {
        val resource = "/pkjs/$nameNoExt.js"
        val js = javaClass.getResourceAsStream(resource)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: error("PKJS script missing from classpath: $resource")
        runtime.eval(js, "$nameNoExt.js")
    }

    override suspend fun start() {
        withContext(threadContext) {
            setupContext()
            runtime.eval(BASE64_JS)
            runtime.eval(XML_HTTP_REQUEST_JS)
            runtime.eval(WEBSOCKET_JS)
            evaluateResourceScript("JSTimeout")
            evaluateResourceScript("startup")
            logger.d { "JS context ready" }
        }
        loadAppJs(jsPath.toString())
    }

    override suspend fun stop() {
        _readyState.value = false
        try {
            withContext(NonCancellable + threadContext) { runtime.stop() }
        } finally {
            threadContext.close()
        }
    }

    override suspend fun loadAppJs(jsUrl: String) {
        withContext(threadContext) {
            val js = SystemFileSystem.source(Path(jsUrl)).buffered().use { it.readString() }
            runtime.eval(js, "${appInfo.uuid}.js")
        }
        signalReady()
    }

    override suspend fun signalInterceptResponse(callbackId: String, result: InterceptResponse) {
        // Interception happens inside XMLHTTPRequestManager (iOS model), not via JS signal.
    }

    override suspend fun signalNewAppMessageData(data: String?): Boolean {
        withContext(threadContext) {
            runtime.eval("globalThis.signalNewAppMessageData(${Json.encodeToString(data)})")
        }
        return true
    }

    override suspend fun signalAudioStream(json: String) {
        eval("globalThis.signalAudioStream(${Json.encodeToString(json)})")
    }

    override suspend fun signalTimelineToken(callId: String, token: String) {
        val json = Json.encodeToString(mapOf("userToken" to token, "callId" to callId))
        withContext(threadContext) { runtime.eval("globalThis.signalTimelineTokenSuccess($json)") }
    }

    override suspend fun signalTimelineTokenFail(callId: String) {
        val json = Json.encodeToString(mapOf("userToken" to null, "callId" to callId))
        withContext(threadContext) { runtime.eval("globalThis.signalTimelineTokenFailure($json)") }
    }

    override suspend fun signalReady() {
        withContext(threadContext) { runtime.eval("globalThis.signalReady()") }
    }

    override suspend fun signalConfigMessage(requestId: Int, json: String) {
        withContext(threadContext) { runtime.eval("globalThis.signalConfigMessageEvent($requestId, $json)") }
    }

    override suspend fun signalShowConfiguration() {
        withContext(threadContext) { runtime.eval("globalThis.signalShowConfiguration()") }
    }

    override suspend fun signalWebviewClosed(data: String?) {
        withContext(threadContext) {
            runtime.eval("globalThis.signalWebviewClosedEvent(${Json.encodeToString(data)})")
        }
    }

    override suspend fun eval(js: String) {
        withContext(threadContext) { runtime.eval(js) }
    }

    override suspend fun evalWithResult(js: String): Any? = withContext(threadContext) {
        runtime.eval(js)?.toKotlin()
    }

    override fun debugForceGC() {}
}
