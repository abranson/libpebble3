package io.rebble.libpebblecommon.linux.notifications

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.linux.dbus.RawSessionConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.types.UInt32
import kotlin.time.Instant

/** A narrow action which a native-Linux notification backend may execute. */
sealed interface LinuxNotificationCommand {
    data object Dismiss : LinuxNotificationCommand
    data object Open : LinuxNotificationCommand
    data class Reply(val text: String) : LinuxNotificationCommand
}

/** Desktop-independent notification data consumed by the native-Linux pipeline. */
data class LinuxNotification(
    val id: String,
    val replacesId: String? = null,
    val timestamp: Instant? = null,
    val content: MappedNotification,
    val hasDefaultAction: Boolean = false,
    val hasReplyAction: Boolean = false,
)

sealed interface LinuxNotificationEvent {
    data class Posted(val notification: LinuxNotification) : LinuxNotificationEvent
    data class Closed(val id: String, val reason: UInt = 0u) : LinuxNotificationEvent
    data class SourcesChanged(
        val packageNames: Set<String>,
        val applied: CompletableDeferred<Unit>,
    ) : LinuxNotificationEvent
    data object Reset : LinuxNotificationEvent
}

/**
 * Native-Linux notification transport boundary.
 *
 * Generic desktops use [FreedesktopNotificationBackend]. A distro may replace
 * this binding with a native provider while keeping libpebble3's notification
 * processing, database, appearance, replacement, and watch-action logic.
 */
interface LinuxNotificationBackend {
    fun events(): Flow<LinuxNotificationEvent>

    suspend fun execute(id: String, command: LinuxNotificationCommand): Boolean
}

/** Direct unprivileged freedesktop implementation used by generic Linux. */
class FreedesktopNotificationBackend(
    private val hintMapper: NotificationHintMapper,
) : LinuxNotificationBackend {
    private val logger = Logger.withTag("FreedesktopNotificationBackend")
    private val monitor = NotificationMonitor()
    private val lock = Any()
    private val defaultActions = LinkedHashMap<String, RemoteAction>()

    override fun events(): Flow<LinuxNotificationEvent> = monitor.events().transform { event ->
        when (event) {
            is NotificationEvent.Posted -> {
                val raw = event.notification
                val mapped = hintMapper.map(raw) ?: return@transform
                val id = raw.id.toString()
                val replacesId = raw.replacesId.takeIf { it != 0u }?.toString()
                val defaultAction = mapped.remoteActions["default"]
                synchronized(lock) {
                    replacesId?.let(defaultActions::remove)
                    if (defaultAction == null) {
                        defaultActions.remove(id)
                    } else {
                        defaultActions[id] = defaultAction
                    }
                    while (defaultActions.size > MAX_TRACKED_ACTIONS) {
                        defaultActions.remove(defaultActions.keys.first())
                    }
                }
                emit(
                    LinuxNotificationEvent.Posted(
                        LinuxNotification(
                            id = id,
                            replacesId = replacesId,
                            content = mapped.copy(remoteActions = emptyMap()),
                            hasDefaultAction = defaultAction != null,
                        )
                    )
                )
            }

            is NotificationEvent.Closed -> {
                val id = event.id.toString()
                synchronized(lock) { defaultActions.remove(id) }
                emit(LinuxNotificationEvent.Closed(id, event.reason))
            }
        }
    }

    override suspend fun execute(id: String, command: LinuxNotificationCommand): Boolean {
        return when (command) {
            LinuxNotificationCommand.Dismiss -> {
                val numericId = id.toLongOrNull()
                    ?.takeIf { it in 1..UInt.MAX_VALUE.toLong() }
                    ?: return false
                val ok = sessionCall(
                    NOTIFICATIONS_SERVICE,
                    NOTIFICATIONS_PATH,
                    NOTIFICATIONS_IFACE,
                    "CloseNotification",
                    "u",
                    UInt32(numericId),
                )
                if (ok) synchronized(lock) { defaultActions.remove(id) }
                ok
            }

            LinuxNotificationCommand.Open -> {
                val action = synchronized(lock) { defaultActions[id] } ?: return false
                sessionCall(action.service, action.path, action.iface, action.method)
            }

            is LinuxNotificationCommand.Reply -> false
        }
    }

    private suspend fun sessionCall(
        destination: String,
        path: String,
        iface: String,
        member: String,
        signature: String? = null,
        vararg args: Any,
    ): Boolean = withContext(Dispatchers.IO) {
        RawSessionConnection.connect()?.use { connection ->
            try {
                connection.call(destination, path, iface, member, signature, *args)
                true
            } catch (e: Exception) {
                logger.e("$iface.$member failed", e)
                false
            }
        } ?: false
    }

    private companion object {
        private const val MAX_TRACKED_ACTIONS = 200
        private const val NOTIFICATIONS_SERVICE = "org.freedesktop.Notifications"
        private const val NOTIFICATIONS_PATH = "/org/freedesktop/Notifications"
        private const val NOTIFICATIONS_IFACE = "org.freedesktop.Notifications"
    }
}
