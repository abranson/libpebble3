package io.rebble.libpebblecommon.linux.notifications

import io.rebble.libpebblecommon.NotificationConfig
import io.rebble.libpebblecommon.asFlow
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.CustomTimelineActionHandler
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.dao.TimelineNotificationRealDao
import io.rebble.libpebblecommon.database.entity.BaseAction
import io.rebble.libpebblecommon.database.entity.TimelineNotification
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.linux.LinuxDeviceActivity
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class LinuxNotificationActionHandlerTest {
    @Test
    fun replyExecutesValidatedTitleAndConfirmsSuccess() = runTest {
        val harness = harness(backgroundScope, executeResult = true)
        harness.start()

        val result = harness.handler.invoke(harness.itemId, replyAction(), titleAttribute("On my way"))

        assertTrue(result.success)
        assertEquals(TimelineIcon.GenericConfirmation, result.icon)
        assertEquals("Replied", result.title)
        assertEquals(
            listOf<LinuxNotificationCommand>(LinuxNotificationCommand.Reply("On my way")),
            harness.backend.commands,
        )
    }

    @Test
    fun replyReportsFailureWhenBackendRejectsIt() = runTest {
        val harness = harness(backgroundScope, executeResult = false)
        harness.start()

        val result = harness.handler.invoke(harness.itemId, replyAction(), titleAttribute("No"))

        assertFalse(result.success)
        assertEquals("Failed", result.title)
        assertEquals(
            listOf<LinuxNotificationCommand>(LinuxNotificationCommand.Reply("No")),
            harness.backend.commands,
        )
    }

    @Test
    fun replyRejectsInvalidInputAndCannotBeInvokedThroughAnotherAction() = runTest {
        val harness = harness(backgroundScope, executeResult = true)
        harness.start()

        listOf(
            emptyList(),
            titleAttribute("   "),
            titleAttribute("a\u0000b"),
            titleAttribute("a".repeat(513)),
            listOf(titleAttribute("first").single(), titleAttribute("second").single()),
            listOf(TimelineItem.Attribute(TimelineAttribute.Title.id, ubyteArrayOf(0xc3u, 0x28u))),
        ).forEach { attributes ->
            assertFalse(harness.handler.invoke(harness.itemId, replyAction(), attributes).success)
        }
        assertEquals(emptyList(), harness.backend.commands)

        val muteResult = harness.handler.invoke(harness.itemId, muteAction(), titleAttribute("not a reply"))
        assertTrue(muteResult.success)
        assertEquals("Muted", muteResult.title)
        assertEquals(emptyList(), harness.backend.commands)
    }

    private fun replyAction() = BaseAction(1u, TimelineItem.Action.Type.Response, emptyList())

    private fun muteAction() = BaseAction(2u, TimelineItem.Action.Type.Generic, emptyList())

    private fun titleAttribute(text: String): List<TimelineItem.Attribute> = listOf(
        TimelineItem.Attribute(TimelineAttribute.Title.id, text.encodeToByteArray().toUByteArray()),
    )

    private fun harness(scope: CoroutineScope, executeResult: Boolean): Harness {
        val eventsHandled = CompletableDeferred<Unit>()
        val backend = RecordingBackend(executeResult, eventsHandled)
        val notificationAppDao = proxy<NotificationAppRealDao> { method, _ ->
            when {
                method.name.startsWith("getEntry") -> null
                method.name.startsWith("insertOrReplace") -> Unit
                method.name.startsWith("updateAppMuteState") -> Unit
                else -> error("unexpected notification app DAO call: ${method.name}")
            }
        }
        val timelineDao = proxy<TimelineNotificationRealDao> { method, _ ->
            when {
                method.name.startsWith("getNotificationsForParent") -> emptyList<TimelineNotification>()
                else -> error("unexpected timeline DAO call: ${method.name}")
            }
        }
        val listener = LinuxNotificationListener(
            LibPebbleCoroutineScope(scope.coroutineContext),
            notificationAppDao,
            timelineDao,
            backend,
            NotificationConfig().asFlow(),
            LinuxDeviceActivity { false },
        )
        val libPebble = RecordingLibPebble()
        return Harness(
            listener = listener,
            handler = LinuxNotificationActionHandler(listener, notificationAppDao, backend),
            backend = backend,
            libPebble = libPebble,
            eventsHandled = eventsHandled,
        )
    }

    private class Harness(
        private val listener: LinuxNotificationListener,
        val handler: LinuxNotificationActionHandler,
        val backend: RecordingBackend,
        private val libPebble: RecordingLibPebble,
        private val eventsHandled: CompletableDeferred<Unit>,
    ) {
        val itemId: Uuid
            get() = libPebble.sent.single().itemId

        suspend fun start() {
            listener.init(libPebble)
            eventsHandled.await()
        }
    }

    private class RecordingBackend(
        private val executeResult: Boolean,
        private val eventsHandled: CompletableDeferred<Unit>,
    ) : LinuxNotificationBackend {
        val commands = mutableListOf<LinuxNotificationCommand>()

        override fun events(): Flow<LinuxNotificationEvent> = flow {
            emit(
                LinuxNotificationEvent.Posted(
                    LinuxNotification(
                        id = "42",
                        content = MappedNotification("Sender", "Message", "org.example.mail", "Mail"),
                        hasReplyAction = true,
                    )
                )
            )
            eventsHandled.complete(Unit)
        }

        override suspend fun execute(id: String, command: LinuxNotificationCommand): Boolean {
            commands += command
            return executeResult
        }
    }

    private class RecordingLibPebble : LibPebble by FakeLibPebble() {
        val sent = mutableListOf<TimelineNotification>()

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
