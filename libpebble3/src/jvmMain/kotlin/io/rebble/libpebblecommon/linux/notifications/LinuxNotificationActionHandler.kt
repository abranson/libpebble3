package io.rebble.libpebblecommon.linux.notifications

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.entity.BaseAction
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.linux.dbus.RawSessionConnection
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.types.UInt32
import kotlin.uuid.Uuid

/**
 * Executes watch-side notification actions on the host: dismiss closes the notification via the
 * standard org.freedesktop.Notifications API, mute updates the app's mute state, and "open on
 * phone" invokes whatever [RemoteAction] the desktop's [NotificationHintMapper] supplied.
 */
class LinuxNotificationActionHandler(
    private val listener: LinuxNotificationListener,
    private val notificationAppDao: NotificationAppRealDao,
) : PlatformNotificationActionHandler {
    private val logger = Logger.withTag("LinuxNotificationActionHandler")

    override suspend fun invoke(
        itemId: Uuid,
        action: BaseAction,
        attributes: List<TimelineItem.Attribute>,
    ): TimelineActionResult {
        val active = listener.activeNotification(itemId) ?: run {
            logger.w { "no tracked notification for $itemId" }
            return failed()
        }
        val watchAction = active.actions.getOrNull(action.actionID.toInt()) ?: run {
            logger.w { "no action ${action.actionID} on $itemId" }
            return failed()
        }
        return when (watchAction) {
            WatchAction.Dismiss -> dismiss(active)
            WatchAction.MuteApp -> muteApp(active)
            is WatchAction.Remote -> invokeRemote(watchAction.action)
        }
    }

    private suspend fun dismiss(active: ActiveNotification): TimelineActionResult {
        val ok = sessionCall(
            NOTIFICATIONS_SERVICE, NOTIFICATIONS_PATH,
            NOTIFICATIONS_IFACE, "CloseNotification",
            "u", UInt32(active.dbusId.toLong()),
        )
        if (ok) listener.remove(active.itemId)
        return if (ok) {
            TimelineActionResult(success = true, icon = TimelineIcon.ResultDismissed, title = "Dismissed")
        } else {
            failed()
        }
    }

    private suspend fun muteApp(active: ActiveNotification): TimelineActionResult {
        notificationAppDao.updateAppMuteState(active.packageName, MuteState.Always)
        return TimelineActionResult(success = true, icon = TimelineIcon.ResultMute, title = "Muted")
    }

    private suspend fun invokeRemote(action: RemoteAction): TimelineActionResult {
        val ok = sessionCall(action.service, action.path, action.iface, action.method)
        return if (ok) {
            TimelineActionResult(success = true, icon = TimelineIcon.GenericConfirmation, title = "Opened")
        } else {
            failed()
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
        RawSessionConnection.connect()?.use { conn ->
            try {
                conn.call(destination, path, iface, member, signature, *args)
                true
            } catch (e: Exception) {
                logger.e("$iface.$member failed", e)
                false
            }
        } ?: false
    }

    private fun failed() =
        TimelineActionResult(success = false, icon = TimelineIcon.ResultFailed, title = "Failed")

    private companion object {
        private const val NOTIFICATIONS_SERVICE = "org.freedesktop.Notifications"
        private const val NOTIFICATIONS_PATH = "/org/freedesktop/Notifications"
        private const val NOTIFICATIONS_IFACE = "org.freedesktop.Notifications"
    }
}
