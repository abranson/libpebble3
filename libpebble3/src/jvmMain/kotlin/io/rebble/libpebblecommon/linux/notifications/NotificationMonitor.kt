package io.rebble.libpebblecommon.linux.notifications

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.linux.dbus.RawSessionConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.messages.MethodCall
import org.freedesktop.dbus.messages.MethodReturn
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import kotlin.time.Duration.Companion.seconds

data class DBusNotification(
    val id: UInt,
    val replacesId: UInt,
    val appName: String,
    /**
     * Notify's app_icon: a theme icon name ("icon-lock-sms"), an "image://theme/..." URI, or a path
     * to a bitmap. Only the names are useful — the watch takes icon IDs, not images — but they are
     * often the only signal there is, since native Sailfish apps send no category.
     */
    val appIcon: String,
    val summary: String,
    val body: String,
    /** Flat [key, label, key, label, ...] pairs as sent by the app. */
    val actions: List<String>,
    /** Hints with Variants unwrapped. */
    val hints: Map<String, Any?>,
) {
    fun hintString(key: String): String? = hints[key] as? String
    fun hintBool(key: String): Boolean = when (val v = hints[key]) {
        is Boolean -> v
        is String -> v == "true"
        is Number -> v.toInt() != 0
        else -> false
    }
}

sealed class NotificationEvent {
    data class Posted(val notification: DBusNotification) : NotificationEvent()
    data class Closed(val id: UInt, val reason: UInt) : NotificationEvent()
}

/**
 * Watches the session bus for notifications the same way rockpoold did: sniff the Notify method
 * calls apps send to lipstick's org.freedesktop.Notifications server, correlate each with its
 * method return to learn the server-assigned id, and track NotificationClosed signals.
 */
class NotificationMonitor {
    private val logger = Logger.withTag("NotificationMonitor")

    fun events(): Flow<NotificationEvent> = channelFlow {
        while (isActive) {
            val conn = RawSessionConnection.connect()
            if (conn == null || !conn.startMonitoring(MATCH_RULES)) {
                conn?.close()
                delay(RETRY_DELAY)
                continue
            }
            logger.i { "monitoring org.freedesktop.Notifications" }
            // sender unique name + Notify serial -> parsed call awaiting its assigned id
            val pending = LinkedHashMap<String, DBusNotification>()
            try {
                runInterruptible(Dispatchers.IO) {
                    while (true) {
                        val msg = conn.read() ?: continue
                        when {
                            msg is MethodCall &&
                                    msg.`interface` == NOTIFICATIONS_IFACE && msg.name == "Notify" -> {
                                parseNotify(msg)?.let { parsed ->
                                    pending["${msg.source}#${msg.serial}"] = parsed
                                    while (pending.size > MAX_PENDING) {
                                        pending.remove(pending.keys.first())
                                    }
                                }
                            }

                            msg is MethodReturn -> {
                                val parsed =
                                    pending.remove("${msg.destination}#${msg.replySerial}")
                                if (parsed != null) {
                                    val id = (msg.parameters.firstOrNull() as? UInt32)
                                        ?.toLong()?.toUInt()
                                    if (id != null) {
                                        trySend(NotificationEvent.Posted(parsed.copy(id = id)))
                                    }
                                }
                            }

                            msg is DBusSignal &&
                                    msg.`interface` == NOTIFICATIONS_IFACE &&
                                    msg.name == "NotificationClosed" -> {
                                val params = msg.parameters
                                val id = (params.getOrNull(0) as? UInt32)?.toLong()?.toUInt()
                                val reason =
                                    (params.getOrNull(1) as? UInt32)?.toLong()?.toUInt() ?: 0u
                                if (id != null) {
                                    trySend(NotificationEvent.Closed(id, reason))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logger.w { "monitor connection lost (${e.message}); reconnecting" }
            } finally {
                conn.close()
            }
            delay(RETRY_DELAY)
        }
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    private fun parseNotify(msg: MethodCall): DBusNotification? {
        val p = try {
            msg.parameters
        } catch (e: Exception) {
            logger.w { "failed to demarshal Notify: ${e.message}" }
            return null
        }
        if (p.size < 7) return null
        return DBusNotification(
            id = 0u,
            replacesId = ((p[1] as? UInt32)?.toLong() ?: 0L).toUInt(),
            appName = p[0] as? String ?: "",
            appIcon = p[2] as? String ?: "",
            summary = p[3] as? String ?: "",
            body = p[4] as? String ?: "",
            actions = toStringList(p[5]),
            hints = toHints(p[6]),
        )
    }

    private fun toStringList(v: Any?): List<String> = when (v) {
        is Array<*> -> v.mapNotNull { it?.toString() }
        is Collection<*> -> v.mapNotNull { it?.toString() }
        else -> emptyList()
    }

    private fun toHints(v: Any?): Map<String, Any?> {
        val map = v as? Map<*, *> ?: return emptyMap()
        return map.entries.associate { (k, value) ->
            k.toString() to unwrap(value)
        }
    }

    private fun unwrap(v: Any?): Any? = when (v) {
        is Variant<*> -> unwrap(v.value)
        is UInt32 -> v.toLong()
        else -> v
    }

    companion object {
        private const val NOTIFICATIONS_IFACE = "org.freedesktop.Notifications"
        private val RETRY_DELAY = 30.seconds
        private const val MAX_PENDING = 64
        private val MATCH_RULES = listOf(
            "type='method_call',interface='$NOTIFICATIONS_IFACE',member='Notify'",
            "type='method_return',sender='$NOTIFICATIONS_IFACE'",
            "type='signal',interface='$NOTIFICATIONS_IFACE',member='NotificationClosed'",
        )
    }
}
