package io.rebble.libpebblecommon.linux.notifications

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.SystemAppIDs.ANDROID_NOTIFICATIONS_UUID
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.dao.TimelineNotificationRealDao
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.database.entity.TimelineNotification
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.linux.LinuxDeviceActivity
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.notification.trimCannedResponses
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.util.toPebbleColor
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** What to do when the watch invokes action N on a notification (indexed by actionId). */
sealed class WatchAction {
    data object Dismiss : WatchAction()
    data object Reply : WatchAction()
    data object MuteApp : WatchAction()
    data object Open : WatchAction()
}

data class ActiveNotification(
    val itemId: Uuid,
    val notificationId: String,
    val packageName: String,
    val title: String,
    val body: String,
    val actions: List<WatchAction>,
)

internal class ActiveNotificationTracker(
    private val maximumTracked: Int = 200,
) {
    private val lock = Any()
    private val byItemId = LinkedHashMap<Uuid, ActiveNotification>()
    private val byNotificationId = HashMap<String, Uuid>()

    fun active(itemId: Uuid): ActiveNotification? = synchronized(lock) { byItemId[itemId] }

    fun replacement(notificationId: String?): ActiveNotification? = synchronized(lock) {
        notificationId?.let { byNotificationId[it] }?.let { byItemId[it] }
    }

    fun remap(itemId: Uuid, expectedId: String, newId: String) = synchronized(lock) {
        val current = byItemId[itemId]
        if (current != null && current.notificationId == expectedId) {
            byNotificationId.remove(expectedId, itemId)
            byNotificationId.put(newId, itemId)?.takeIf { it != itemId }?.let(byItemId::remove)
            byItemId[itemId] = current.copy(notificationId = newId)
        }
    }

    fun track(active: ActiveNotification) = synchronized(lock) {
        byItemId.put(active.itemId, active)?.let { previous ->
            byNotificationId.remove(previous.notificationId, previous.itemId)
        }
        byNotificationId.put(active.notificationId, active.itemId)
            ?.takeIf { it != active.itemId }
            ?.let(byItemId::remove)
        while (byItemId.size > maximumTracked) {
            val oldest = byItemId.keys.first()
            removeLocked(oldest)
        }
    }

    fun remove(itemId: Uuid): ActiveNotification? = synchronized(lock) { removeLocked(itemId) }

    fun close(notificationId: String): Uuid? = synchronized(lock) {
        val itemId = byNotificationId[notificationId] ?: return@synchronized null
        removeLocked(itemId)
        itemId
    }

    fun clear(): List<Uuid> = synchronized(lock) {
        val ids = byItemId.keys.toList()
        byItemId.clear()
        byNotificationId.clear()
        ids
    }

    fun removePackages(packageNames: Set<String>): List<Uuid> = synchronized(lock) {
        val ids = byItemId.values
            .filter { it.packageName in packageNames }
            .map(ActiveNotification::itemId)
        ids.forEach(::removeLocked)
        ids
    }

    private fun removeLocked(itemId: Uuid): ActiveNotification? =
        byItemId.remove(itemId)?.also { active ->
            byNotificationId.remove(active.notificationId, itemId)
        }
}

/**
 * Feeds native-Linux notifications into libpebble3. The injected backend may
 * use freedesktop D-Bus directly or a distro-specific native provider.
 */
class LinuxNotificationListener(
    private val scope: LibPebbleCoroutineScope,
    private val notificationAppDao: NotificationAppRealDao,
    private val timelineNotificationDao: TimelineNotificationRealDao,
    private val backend: LinuxNotificationBackend,
    private val notificationConfig: NotificationConfigFlow,
    private val deviceActivity: LinuxDeviceActivity,
) : NotificationListenerConnection {
    private val logger = Logger.withTag("LinuxNotificationListener")

    private val tracker = ActiveNotificationTracker()

    override fun init(libPebble: LibPebble) {
        scope.launch {
            try {
                retirePersistedNotifications(libPebble)
            } catch (e: Exception) {
                logger.e("could not retire stale native notifications", e)
            }
            backend.events().collect { event ->
                try {
                    when (event) {
                        is LinuxNotificationEvent.Posted ->
                            onPosted(libPebble, event.notification)
                        is LinuxNotificationEvent.Closed -> onClosed(libPebble, event.id)
                        is LinuxNotificationEvent.SourcesChanged -> try {
                            onSourcesChanged(libPebble, event.packageNames)
                        } finally {
                            event.applied.complete(Unit)
                        }
                        LinuxNotificationEvent.Reset -> onReset(libPebble)
                    }
                } catch (e: Exception) {
                    logger.e("error handling $event", e)
                }
            }
        }
    }

    fun activeNotification(itemId: Uuid): ActiveNotification? =
        tracker.active(itemId)

    fun remove(itemId: Uuid) {
        tracker.remove(itemId)
    }

    private suspend fun retirePersistedNotifications(libPebble: LibPebble) {
        val stale = timelineNotificationDao
            .getNotificationsForParent(ANDROID_NOTIFICATIONS_UUID)
            .filter { TimelineItem.Flag.STATE_READ !in it.content.flags }
        stale.forEach { libPebble.markNotificationRead(it.itemId) }
        if (stale.isNotEmpty()) {
            logger.i { "retired ${stale.size} native notifications after restart" }
        }
    }

    private suspend fun onPosted(libPebble: LibPebble, n: LinuxNotification) {
        val mapped = n.content
        val replaced = tracker.replacement(n.replacesId)
        if (!notificationConfig.value.alwaysSendNotifications &&
            !mapped.isPebbleTestNotification() && deviceActivity.isActiveAndUnlocked()
        ) {
            // Replacing a visible pin with a suppressed native notification must
            // still retire the old pin. The new notification was never tracked,
            // so its later close event is intentionally a no-op.
            replaced?.let {
                libPebble.markNotificationRead(it.itemId)
                remove(it.itemId)
            }
            logger.d { "suppressed notification from ${mapped.packageName}: device active" }
            return
        }
        if (replaced != null && replaced.packageName == mapped.packageName &&
            replaced.title == mapped.title && replaced.body == mapped.body &&
            (WatchAction.Open in replaced.actions) == n.hasDefaultAction &&
            (WatchAction.Reply in replaced.actions) == n.hasReplyAction
        ) {
            // Same content re-posted (e.g. progress/hint update): nothing new for the watch.
            tracker.remap(replaced.itemId, replaced.notificationId, n.id)
            return
        }

        val app = registerApp(mapped)
        if (isMuted(app)) {
            logger.d { "muted: ${mapped.packageName}" }
            return
        }
        val appearance = appearanceFor(app, mapped)

        val watchActions = mutableListOf<WatchAction>()
        val notification = buildTimelineNotification(
            parentId = ANDROID_NOTIFICATIONS_UUID,
            timestamp = n.timestamp ?: Clock.System.now(),
        ) {
            layout = TimelineItem.Layout.GenericNotification
            attributes {
                // Sender and message only, as on Android. No appName (attr 30) — the firmware's
                // list row prefers it over the title, so it would show the app where the sender
                // belongs — and no subtitle either: the icon and background colour already say
                // which app this is, so spending a line on the name only pushes the message down.
                title { mapped.title.take(64) }
                if (mapped.body.isNotEmpty()) body { mapped.body.take(512) }
                tinyIcon { appearance.icon }
                appearance.colorArgb?.let { backgroundColor { it.toPebbleColor() } }
            }
            actions {
                action(TimelineItem.Action.Type.Dismiss) {
                    attributes { title { "Dismiss" } }
                }
                watchActions += WatchAction.Dismiss
                if (n.hasReplyAction) {
                    action(TimelineItem.Action.Type.Response) {
                        attributes {
                            title { "Reply" }
                            cannedResponse { trimCannedResponses(notificationConfig.value.cannedResponses) }
                        }
                    }
                    watchActions += WatchAction.Reply
                }
                if (n.hasDefaultAction) {
                    action(TimelineItem.Action.Type.Generic) {
                        attributes { title { "Open on phone" } }
                    }
                    watchActions += WatchAction.Open
                }
                action(TimelineItem.Action.Type.Generic) {
                    attributes { title { "Mute app" } }
                }
                watchActions += WatchAction.MuteApp
            }
        }

        if (replaced != null) {
            libPebble.markNotificationRead(replaced.itemId)
            remove(replaced.itemId)
        }
        track(notification, n, mapped, watchActions)
        libPebble.sendNotification(notification)
        logger.d { "sent notification from ${mapped.packageName} (native id ${n.id})" }
    }

    private fun track(
        notification: TimelineNotification,
        n: LinuxNotification,
        mapped: MappedNotification,
        actions: List<WatchAction>,
    ) {
        val active = ActiveNotification(
            itemId = notification.itemId,
            notificationId = n.id,
            packageName = mapped.packageName,
            title = mapped.title,
            body = mapped.body,
            actions = actions,
        )
        tracker.track(active)
    }

    private suspend fun onClosed(libPebble: LibPebble, notificationId: String) {
        val itemId = tracker.close(notificationId) ?: return
        libPebble.markNotificationRead(itemId)
    }

    private suspend fun onReset(libPebble: LibPebble) {
        val itemIds = tracker.clear()
        itemIds.forEach { libPebble.markNotificationRead(it) }
    }

    private suspend fun onSourcesChanged(libPebble: LibPebble, packageNames: Set<String>) {
        tracker.removePackages(packageNames).forEach { libPebble.markNotificationRead(it) }
    }

    private suspend fun registerApp(mapped: MappedNotification): NotificationAppItem {
        val now = Clock.System.now().asMillisecond()
        val existing = notificationAppDao.getEntry(mapped.packageName)
        // colorName/iconCode stay null: they are the user's overrides, and appearanceFor() falls
        // back to the package table when they are unset.
        val entry = existing?.copy(lastNotified = now) ?: NotificationAppItem(
            packageName = mapped.packageName,
            name = mapped.appName,
            muteState = MuteState.Never,
            channelGroups = emptyList(),
            stateUpdated = now,
            lastNotified = now,
            vibePatternName = null,
            colorName = null,
            iconCode = null,
            autoAdded = true,
        )
        notificationAppDao.insertOrReplace(entry)
        return entry
    }

    private fun isMuted(app: NotificationAppItem): Boolean {
        app.muteExpiration?.let { expiry ->
            return Clock.System.now() < expiry.instant
        }
        val weekend = LocalDate.now().dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        return when (app.muteState) {
            MuteState.Always -> true
            MuteState.Weekends -> weekend
            MuteState.Weekdays -> !weekend
            MuteState.Never, MuteState.Exempt -> false
        }
    }

}

private fun MappedNotification.isPebbleTestNotification(): Boolean =
    packageName == "coredevices.coreapp" && title == "Test Notification"
