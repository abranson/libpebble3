package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** JS global `Pebble` (startup.js attaches the higher-level API onto the same object). */
internal class JvmPKJSInterface(
    jsRunner: JsRunner,
    device: CompanionAppDevice,
    libPebble: LibPebble,
    jsTokenUtil: JsTokenUtil,
) : PKJSInterface(jsRunner, device, libPebble, jsTokenUtil), JvmJsInterface {
    private val logger = Logger.withTag("JvmPKJSInterface")
    override val name = "Pebble"
    override val methods = setOf(
        "showSimpleNotificationOnPebble", "getAccountToken", "getWatchToken", "showToast",
        "openURL",
    )

    override fun showToast(toast: String) {
        logger.w { "showToast not implemented: $toast" }
    }

    override fun dispatch(method: String, args: List<Any?>): Any? = when (method) {
        "showSimpleNotificationOnPebble" ->
            showSimpleNotificationOnPebble(args[0].toString(), args[1].toString())
        "getAccountToken" -> getAccountToken()
        "getWatchToken" -> getWatchToken()
        "showToast" -> showToast(args[0].toString())
        "openURL" -> openURL(args[0].toString())
        else -> null
    }
}

/** JS global `_Pebble` — the private side startup.js talks to. */
internal class JvmPrivatePKJSInterface(
    jsRunner: JsRunner,
    device: CompanionAppDevice,
    scope: CoroutineScope,
    outgoingAppMessages: MutableSharedFlow<AppMessageRequest>,
    logMessages: Channel<String>,
    jsTokenUtil: JsTokenUtil,
    remoteTimelineEmulator: RemoteTimelineEmulator,
    httpInterceptorManager: HttpInterceptorManager,
    notificationConfigFlow: NotificationConfigFlow,
) : PrivatePKJSInterface(
    jsRunner, device, scope, outgoingAppMessages, logMessages, jsTokenUtil,
    remoteTimelineEmulator, httpInterceptorManager, notificationConfigFlow,
), JvmJsInterface {
    private val logger = Logger.withTag("JvmPrivatePKJSInterface")
    override val name = "_Pebble"
    override val methods = setOf(
        "sendAppMessageString", "privateLog", "onConsoleLog", "onError",
        "onUnhandledRejection", "logInterceptedSend", "getVersionCode",
        "getTimelineTokenAsync", "privateFnConfirmReadySignal", "getActivePebbleWatchInfo",
        "insertTimelinePin", "deleteTimelinePin",
    )

    override fun getVersionCode(): Int = 0

    override fun dispatch(method: String, args: List<Any?>): Any? = when (method) {
        "sendAppMessageString" -> sendAppMessageString(args[0].toString())
        "privateLog" -> privateLog(args[0].toString())
        "onConsoleLog" -> {
            val level = args.getOrNull(0)?.toString()
            val message = args.getOrNull(1)?.toString()
            val trace = args.getOrNull(2)?.toString()
            if (level == null || message == null) {
                logger.w { "onConsoleLog missing level/message" }
            } else {
                // Same source-line extraction as the iOS runner.
                val sourceLine = trace?.split("\n")?.getOrNull(2)?.trim()?.substringAfter("code@")
                onConsoleLog(level, message, sourceLine)
            }
            null
        }
        "onError" -> {
            onError(
                args.getOrNull(0)?.toString(),
                args.getOrNull(1)?.toString(),
                (args.getOrNull(2) as? Number)?.toDouble(),
                (args.getOrNull(3) as? Number)?.toDouble(),
            )
            null
        }
        "onUnhandledRejection" -> onUnhandledRejection(args[0].toString())
        "logInterceptedSend" -> logInterceptedSend()
        "getVersionCode" -> getVersionCode()
        "getTimelineTokenAsync" -> getTimelineTokenAsync()
        "privateFnConfirmReadySignal" -> {
            val success = when (val a = args.getOrNull(0)) {
                is Boolean -> a
                is Number -> a.toInt() != 0
                else -> false
            }
            privateFnConfirmReadySignal(success)
            null
        }
        "getActivePebbleWatchInfo" -> getActivePebbleWatchInfo()
        "insertTimelinePin" -> insertTimelinePin(args[0].toString())
        "deleteTimelinePin" -> deleteTimelinePin(args[0].toString())
        else -> null
    }
}

/** JS global `localStorage`, persisted per-app under the daemon's data dir. */
internal class JvmLocalStorageInterface(
    scopedSettingsUuid: String,
    appContext: AppContext,
) : JSLocalStorageInterface(scopedSettingsUuid, appContext), JvmJsInterface {
    override val name = "localStorage"
    override val methods = setOf("getItem", "setItem", "removeItem", "clear", "key")

    private var evalNow: ((String) -> Unit)? = null

    override fun setLength(value: Int) {
        evalNow?.invoke("localStorage.length = $value;")
    }

    override fun onRegister(evalNow: (String) -> Unit) {
        this.evalNow = evalNow
        evalNow("localStorage.__override__ = true;")
        setLength(getLength())
    }

    override fun dispatch(method: String, args: List<Any?>): Any? = when (method) {
        "getItem" -> getItem(args.getOrNull(0))
        "setItem" -> {
            setItem(args.getOrNull(0), args.getOrNull(1))
            setLength(getLength())
            null
        }
        "removeItem" -> {
            removeItem(args.getOrNull(0))
            setLength(getLength())
            null
        }
        "clear" -> {
            clear()
            setLength(getLength())
            null
        }
        "key" -> key((args.getOrNull(0) as? Number)?.toDouble() ?: 0.0)
        else -> null
    }
}

/** JS global `_PebbleGeo`; startup.js builds navigator.geolocation on top of it. */
internal class JvmGeolocationInterface(
    scope: CoroutineScope,
    jsRunner: JsRunner,
) : GeolocationInterface(scope, jsRunner), JvmJsInterface {
    override val name = "_PebbleGeo"
    override val methods = setOf(
        "getCurrentPosition", "watchPosition", "clearWatch",
        "getRequestCallbackID", "getWatchCallbackID",
    )

    override fun dispatch(method: String, args: List<Any?>): Any? {
        fun num(i: Int) = args.getOrNull(i) as? Number
        return when (method) {
            "getCurrentPosition" -> {
                val id = num(0)?.toDouble() ?: return null
                getCurrentPosition(
                    id,
                    num(1)?.toDouble() ?: -1.0,
                    num(2)?.toDouble() ?: -1.0,
                    num(3)?.toDouble() ?: 0.0,
                )
            }
            "watchPosition" -> {
                val id = num(0)?.toDouble() ?: return null
                watchPosition(id, num(1)?.toDouble() ?: 500.0, num(2)?.toDouble() ?: 0.0)
            }
            "clearWatch" -> {
                num(0)?.toInt()?.let { clearWatch(it) }
                null
            }
            "getRequestCallbackID" -> getRequestCallbackID()
            "getWatchCallbackID" -> getWatchCallbackID()
            else -> null
        }
    }
}

/** JS global `_Timeout` — JSTimeout.js owns the callback maps; Kotlin owns the clocks. */
internal class JsTimeout(
    private val scope: CoroutineScope,
    private val eval: (String) -> Unit,
) : JvmJsInterface {
    override val name = "_Timeout"
    override val methods = setOf("setTimeout", "setInterval", "clearTimeout", "clearInterval")

    private val jobs = mutableMapOf<Int, kotlinx.coroutines.Job>()
    private var lastId = 0

    @Synchronized
    private fun nextId(): Int {
        lastId = if (lastId == Int.MAX_VALUE) 1 else lastId + 1
        return lastId
    }

    override fun dispatch(method: String, args: List<Any?>): Any? = when (method) {
        "setTimeout" -> {
            val delayMs = (args[0] as Number).toDouble()
            val id = nextId()
            jobs[id] = scope.launch {
                delay(delayMs.toLong())
                if (isActive) eval("_LibPebbleTriggerTimeout(${id.toDouble()})")
            }
            id.toDouble()
        }
        "setInterval" -> {
            val delayMs = (args[0] as Number).toDouble()
            val id = nextId()
            jobs[id] = scope.launch {
                while (isActive) {
                    delay(delayMs.toLong())
                    eval("_LibPebbleTriggerInterval(${id.toDouble()})")
                }
            }
            id.toDouble()
        }
        "clearTimeout", "clearInterval" -> {
            val id = (args[0] as? Number)?.toInt()
            jobs.remove(id)?.cancel(CancellationException("cleared"))
            null
        }
        else -> null
    }

    override fun close() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }
}
