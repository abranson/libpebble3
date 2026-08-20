package io.rebble.libpebblecommon.linux.notifications

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.entity.BaseAction
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import kotlin.uuid.Uuid

/**
 * Executes watch-side notification actions through the same native-Linux
 * backend that supplied the notification. Mute remains local database policy.
 */
class LinuxNotificationActionHandler(
    private val listener: LinuxNotificationListener,
    private val notificationAppDao: NotificationAppRealDao,
    private val backend: LinuxNotificationBackend,
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
            WatchAction.Open -> execute(active, LinuxNotificationCommand.Open, "Opened")
            WatchAction.Reply -> reply(active, attributes)
        }
    }

    private suspend fun dismiss(active: ActiveNotification): TimelineActionResult {
        val ok = backend.execute(active.notificationId, LinuxNotificationCommand.Dismiss)
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

    private suspend fun execute(
        active: ActiveNotification,
        command: LinuxNotificationCommand,
        title: String,
    ): TimelineActionResult {
        val ok = backend.execute(active.notificationId, command)
        return if (ok) {
            TimelineActionResult(success = true, icon = TimelineIcon.GenericConfirmation, title = title)
        } else {
            failed()
        }
    }

    private suspend fun reply(
        active: ActiveNotification,
        attributes: List<TimelineItem.Attribute>,
    ): TimelineActionResult {
        val title = attributes.filter { it.attributeId.get() == TimelineAttribute.Title.id }
            .singleOrNull()
            ?.content?.get()
            ?.asByteArray()
            ?: return failed()
        val text = try {
            title.decodeToString(throwOnInvalidSequence = true)
        } catch (_: java.nio.charset.CharacterCodingException) {
            return failed()
        }
        if (text.isBlank() || '\u0000' in text || title.size > MAX_REPLY_BYTES) return failed()
        return execute(active, LinuxNotificationCommand.Reply(text), "Replied")
    }

    private fun failed() =
        TimelineActionResult(success = false, icon = TimelineIcon.ResultFailed, title = "Failed")

    private companion object {
        const val MAX_REPLY_BYTES = 512
    }
}
