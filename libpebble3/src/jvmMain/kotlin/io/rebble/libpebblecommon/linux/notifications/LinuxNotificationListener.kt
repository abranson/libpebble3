package io.rebble.libpebblecommon.linux.notifications

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.SystemAppIDs.ANDROID_NOTIFICATIONS_UUID
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.database.entity.TimelineNotification
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
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
    data object MuteApp : WatchAction()
    data class Remote(val action: RemoteAction) : WatchAction()
}

data class ActiveNotification(
    val itemId: Uuid,
    val dbusId: UInt,
    val packageName: String,
    val title: String,
    val body: String,
    val actions: List<WatchAction>,
)

/**
 * Feeds freedesktop notifications into libpebble3, via [NotificationMonitor] for the raw D-Bus
 * traffic and a [NotificationHintMapper] for the desktop-specific parts.
 */
class LinuxNotificationListener(
    private val scope: LibPebbleCoroutineScope,
    private val notificationAppDao: NotificationAppRealDao,
    private val hintMapper: NotificationHintMapper,
) : NotificationListenerConnection {
    private val logger = Logger.withTag("LinuxNotificationListener")
    private val monitor = NotificationMonitor()

    private val lock = Any()
    private val byItemId = LinkedHashMap<Uuid, ActiveNotification>()
    private val byDbusId = HashMap<UInt, Uuid>()

    override fun init(libPebble: LibPebble) {
        scope.launch {
            monitor.events().collect { event ->
                try {
                    when (event) {
                        is NotificationEvent.Posted -> onPosted(libPebble, event.notification)
                        is NotificationEvent.Closed -> onClosed(libPebble, event.id)
                    }
                } catch (e: Exception) {
                    logger.e("error handling $event", e)
                }
            }
        }
    }

    fun activeNotification(itemId: Uuid): ActiveNotification? =
        synchronized(lock) { byItemId[itemId] }

    fun remove(itemId: Uuid) {
        synchronized(lock) {
            byItemId.remove(itemId)?.let { byDbusId.remove(it.dbusId) }
        }
    }

    private suspend fun onPosted(libPebble: LibPebble, n: DBusNotification) {
        val mapped = hintMapper.map(n) ?: return

        val replaced = n.replacesId.takeIf { it != 0u }
            ?.let { synchronized(lock) { byDbusId[it]?.let { id -> byItemId[id] } } }
        if (replaced != null && replaced.title == mapped.title && replaced.body == mapped.body) {
            // Same content re-posted (e.g. progress/hint update): nothing new for the watch.
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
            timestamp = Clock.System.now(),
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
                mapped.remoteActions["default"]?.let { remote ->
                    action(TimelineItem.Action.Type.Generic) {
                        attributes { title { "Open on phone" } }
                    }
                    watchActions += WatchAction.Remote(remote)
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
        logger.d { "sent notification from ${mapped.packageName} (dbus id ${n.id})" }
    }

    private fun track(
        notification: TimelineNotification,
        n: DBusNotification,
        mapped: MappedNotification,
        actions: List<WatchAction>,
    ) {
        val active = ActiveNotification(
            itemId = notification.itemId,
            dbusId = n.id,
            packageName = mapped.packageName,
            title = mapped.title,
            body = mapped.body,
            actions = actions,
        )
        synchronized(lock) {
            byItemId[active.itemId] = active
            byDbusId[active.dbusId] = active.itemId
            while (byItemId.size > MAX_TRACKED) {
                val oldest = byItemId.keys.first()
                byItemId.remove(oldest)?.let { byDbusId.remove(it.dbusId) }
            }
        }
    }

    private suspend fun onClosed(libPebble: LibPebble, dbusId: UInt) {
        val itemId = synchronized(lock) { byDbusId[dbusId] } ?: return
        libPebble.markNotificationRead(itemId)
        remove(itemId)
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

    companion object {
        private const val MAX_TRACKED = 200
    }
}
