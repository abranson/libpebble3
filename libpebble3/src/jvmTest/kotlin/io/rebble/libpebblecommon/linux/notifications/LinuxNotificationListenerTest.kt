package io.rebble.libpebblecommon.linux.notifications

import io.rebble.libpebblecommon.SystemAppIDs.ANDROID_NOTIFICATIONS_UUID
import io.rebble.libpebblecommon.NotificationConfig
import io.rebble.libpebblecommon.asFlow
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.CustomTimelineActionHandler
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.dao.TimelineNotificationRealDao
import io.rebble.libpebblecommon.database.entity.BaseAttribute
import io.rebble.libpebblecommon.database.entity.TimelineNotification
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.linux.LinuxDeviceActivity
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

class LinuxNotificationListenerTest {
    @Test
    fun imagesAffectReplacementAndRespectGlobalPreference() = runTest {
        for (enabled in listOf(true, false)) {
            val dir = java.nio.file.Files.createTempDirectory("notification-listener-images")
            try {
                val done = CompletableDeferred<Unit>()
                val source = posted("1").notification
                val image = kotlin.test.assertNotNull(LinuxNotificationImage.decode(byteArrayOf(1, 0, 1, 0, -1, 0, 0)))
                val store = LinuxNotificationImageStore(dir)
                val listener = LinuxNotificationListener(
                    LibPebbleCoroutineScope(backgroundScope.coroutineContext), notificationAppDao(), timelineDao(),
                    backend(done) {
                        emit(LinuxNotificationEvent.Posted(source))
                        emit(LinuxNotificationEvent.Posted(source.copy(id = "2", replacesId = "1", image = image)))
                        emit(LinuxNotificationEvent.Posted(source.copy(id = "3", replacesId = "2", image = image)))
                    },
                    NotificationConfig(sendNotificationImages = enabled).asFlow(), LinuxDeviceActivity { false }, store,
                )
                val pebble = RecordingLibPebble()
                listener.init(pebble)
                done.await()
                assertEquals(2, pebble.sent.size)
                assertEquals(listOf(pebble.sent.first().itemId), pebble.read)
                val sent = pebble.sent.last()
                assertEquals(enabled, sent.content.attributes.any { it.attribute == io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute.ImageAspectRatio })
                assertEquals(enabled, store.image(sent.itemId, 20, 10) != null)
                assertEquals("3", listener.activeNotification(sent.itemId)?.notificationId)
            } finally { dir.toFile().deleteRecursively() }
        }
    }

    @Test
    fun startupRetiresUnreadPinsAndResetRetiresLivePin() = runTest {
        val unread = notification()
        val read = notification().let { notification ->
            notification.copy(
                content = notification.content.copy(
                    flags = notification.content.flags + TimelineItem.Flag.STATE_READ,
                )
            )
        }
        val timelineDao = proxy<TimelineNotificationRealDao> { method, _ ->
            when {
                method.name.startsWith("getNotificationsForParent") -> listOf(unread, read)
                else -> error("unexpected timeline DAO call: ${method.name}")
            }
        }
        val notificationAppDao = proxy<NotificationAppRealDao> { method, _ ->
            when {
                method.name.startsWith("getEntry") -> null
                method.name.startsWith("insertOrReplace") -> Unit
                else -> error("unexpected notification app DAO call: ${method.name}")
            }
        }
        val eventsHandled = CompletableDeferred<Unit>()
        val backend = object : LinuxNotificationBackend {
            override fun events(): Flow<LinuxNotificationEvent> = flow {
                emit(LinuxNotificationEvent.Posted(
                    LinuxNotification(
                        id = "42",
                        content = MappedNotification(
                            title = "Sender",
                            body = "Message",
                            packageName = "org.example.mail",
                            appName = "Mail",
                        ),
                    )
                ))
                emit(LinuxNotificationEvent.Reset)
                eventsHandled.complete(Unit)
            }

            override suspend fun execute(
                id: String,
                command: LinuxNotificationCommand,
            ): Boolean = false
        }
        val libPebble = RecordingLibPebble()
        val listener = LinuxNotificationListener(
            LibPebbleCoroutineScope(backgroundScope.coroutineContext),
            notificationAppDao,
            timelineDao,
            backend,
            NotificationConfig().asFlow(),
            LinuxDeviceActivity { false },
        )

        listener.init(libPebble)
        eventsHandled.await()

        assertEquals(1, libPebble.sent.size)
        assertEquals(
            listOf(unread.itemId, libPebble.sent.single().itemId),
            libPebble.read,
        )
        assertNull(listener.activeNotification(libPebble.sent.single().itemId))
    }

    @Test
    fun activeDeviceSuppressesNormalNotificationButNotPebbleTest() = runTest {
        val eventsHandled = CompletableDeferred<Unit>()
        val listener = listener(
            scope = LibPebbleCoroutineScope(backgroundScope.coroutineContext),
            backend = backend(eventsHandled) {
                emit(posted("1"))
                emit(posted("2", packageName = "coredevices.coreapp", title = "Test Notification"))
            },
            config = NotificationConfig(alwaysSendNotifications = false),
            deviceActivity = LinuxDeviceActivity { true },
        )
        val libPebble = RecordingLibPebble()

        listener.init(libPebble)
        eventsHandled.await()

        assertEquals(1, libPebble.sent.size)
    }

    @Test
    fun suppressedReplacementRetiresPreviouslySentNotification() = runTest {
        var active = false
        val eventsHandled = CompletableDeferred<Unit>()
        val listener = listener(
            scope = LibPebbleCoroutineScope(backgroundScope.coroutineContext),
            backend = backend(eventsHandled) {
                emit(posted("1"))
                active = true
                emit(posted("2", replacesId = "1"))
            },
            config = NotificationConfig(alwaysSendNotifications = false),
            deviceActivity = LinuxDeviceActivity { active },
        )
        val libPebble = RecordingLibPebble()

        listener.init(libPebble)
        eventsHandled.await()

        assertEquals(1, libPebble.sent.size)
        assertEquals(listOf(libPebble.sent.single().itemId), libPebble.read)
        assertNull(listener.activeNotification(libPebble.sent.single().itemId))
    }

    @Test
    fun replyActionUsesConfiguredCannedResponsesWithinFirmwareLimit() = runTest {
        val eventsHandled = CompletableDeferred<Unit>()
        val listener = listener(
            scope = LibPebbleCoroutineScope(backgroundScope.coroutineContext),
            backend = backend(eventsHandled) {
                emit(posted("1", hasDefaultAction = true, hasReplyAction = true))
            },
            config = NotificationConfig(
                cannedResponses = listOf("a".repeat(512), "must be omitted"),
            ),
            deviceActivity = LinuxDeviceActivity { false },
        )
        val libPebble = RecordingLibPebble()

        listener.init(libPebble)
        eventsHandled.await()

        val notification = libPebble.sent.single()
        assertEquals(
            listOf(
                TimelineItem.Action.Type.Dismiss,
                TimelineItem.Action.Type.Response,
                TimelineItem.Action.Type.Generic,
                TimelineItem.Action.Type.Generic,
            ),
            notification.content.actions.map { it.type },
        )
        assertEquals(
            listOf("a".repeat(512)),
            notification.content.actions[1].attributes
                .filterIsInstance<BaseAttribute.TextListAttribute>()
                .single()
                .text,
        )
        assertEquals(
            "Reply",
            notification.content.actions[1].attributes
                .filterIsInstance<BaseAttribute.TextAttribute>()
                .single()
                .text,
        )
        assertEquals(
            listOf(WatchAction.Dismiss, WatchAction.Reply, WatchAction.Open, WatchAction.MuteApp),
            listener.activeNotification(notification.itemId)?.actions,
        )
    }

    @Test
    fun replacementWithNewReplyAuthorityIsSentAgain() = runTest {
        val eventsHandled = CompletableDeferred<Unit>()
        val listener = listener(
            scope = LibPebbleCoroutineScope(backgroundScope.coroutineContext),
            backend = backend(eventsHandled) {
                emit(posted("1"))
                emit(posted("2", replacesId = "1", hasReplyAction = true))
            },
            config = NotificationConfig(),
            deviceActivity = LinuxDeviceActivity { false },
        )
        val libPebble = RecordingLibPebble()

        listener.init(libPebble)
        eventsHandled.await()

        assertEquals(2, libPebble.sent.size)
        assertEquals(listOf(libPebble.sent.first().itemId), libPebble.read)
        assertEquals(
            listOf(WatchAction.Dismiss, WatchAction.Reply, WatchAction.MuteApp),
            listener.activeNotification(libPebble.sent.last().itemId)?.actions,
        )
    }

    private fun listener(
        scope: LibPebbleCoroutineScope,
        backend: LinuxNotificationBackend,
        config: NotificationConfig,
        deviceActivity: LinuxDeviceActivity,
    ): LinuxNotificationListener = LinuxNotificationListener(
        scope,
        notificationAppDao(),
        timelineDao(),
        backend,
        config.asFlow(),
        deviceActivity,
    )

    private fun backend(
        eventsHandled: CompletableDeferred<Unit>,
        events: suspend kotlinx.coroutines.flow.FlowCollector<LinuxNotificationEvent>.() -> Unit,
    ): LinuxNotificationBackend = object : LinuxNotificationBackend {
        override fun events(): Flow<LinuxNotificationEvent> = flow {
            events()
            eventsHandled.complete(Unit)
        }

        override suspend fun execute(id: String, command: LinuxNotificationCommand) = false
    }

    private fun posted(
        id: String,
        replacesId: String? = null,
        packageName: String = "org.example.mail",
        title: String = "Sender",
        hasDefaultAction: Boolean = false,
        hasReplyAction: Boolean = false,
    ) = LinuxNotificationEvent.Posted(
        LinuxNotification(
            id = id,
            replacesId = replacesId,
            content = MappedNotification(title, "Message", packageName, "Mail"),
            hasDefaultAction = hasDefaultAction,
            hasReplyAction = hasReplyAction,
        )
    )

    private fun timelineDao(): TimelineNotificationRealDao = proxy { method, _ ->
        when {
            method.name.startsWith("getNotificationsForParent") -> emptyList<TimelineNotification>()
            else -> error("unexpected timeline DAO call: ${method.name}")
        }
    }

    private fun notificationAppDao(): NotificationAppRealDao = proxy { method, _ ->
        when {
            method.name.startsWith("getEntry") -> null
            method.name.startsWith("insertOrReplace") -> Unit
            else -> error("unexpected notification app DAO call: ${method.name}")
        }
    }

    private fun notification(): TimelineNotification = buildTimelineNotification(
        parentId = ANDROID_NOTIFICATIONS_UUID,
        timestamp = Clock.System.now(),
    ) { }

    private class RecordingLibPebble : LibPebble by FakeLibPebble() {
        val read = mutableListOf<Uuid>()
        val sent = mutableListOf<TimelineNotification>()

        override suspend fun markNotificationRead(itemId: Uuid) {
            read += itemId
        }

        override suspend fun sendNotification(
            notification: TimelineNotification,
            actionHandlers: Map<UByte, CustomTimelineActionHandler>?,
        ) {
            sent += notification
        }
    }

    private inline fun <reified T : Any> proxy(
        crossinline handler: (java.lang.reflect.Method, Array<out Any?>?) -> Any?,
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "Test${T::class.java.simpleName}"
            else -> handler(method, args)
        }
    } as T
}
