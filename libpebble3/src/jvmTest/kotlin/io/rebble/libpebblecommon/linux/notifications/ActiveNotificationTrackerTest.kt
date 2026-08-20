package io.rebble.libpebblecommon.linux.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class ActiveNotificationTrackerTest {
    @Test
    fun remapMovesCloseAndActionLookupToReplacementId() {
        val tracker = ActiveNotificationTracker()
        val active = notification("41")
        tracker.track(active)

        tracker.remap(active.itemId, "41", "42")

        assertNull(tracker.replacement("41"))
        assertEquals("42", tracker.active(active.itemId)?.notificationId)
        assertEquals(active.itemId, tracker.close("42"))
        assertNull(tracker.active(active.itemId))
    }

    @Test
    fun reusedNativeIdEvictsStaleItem() {
        val tracker = ActiveNotificationTracker()
        val first = notification("42")
        val second = notification("42")

        tracker.track(first)
        tracker.track(second)

        assertNull(tracker.active(first.itemId))
        assertEquals(second, tracker.active(second.itemId))
        assertEquals(second.itemId, tracker.close("42"))
    }

    @Test
    fun resetReturnsAndForgetsEveryTrackedPin() {
        val tracker = ActiveNotificationTracker()
        val first = notification("1")
        val second = notification("2")
        tracker.track(first)
        tracker.track(second)

        assertEquals(listOf(first.itemId, second.itemId), tracker.clear())
        assertNull(tracker.replacement("1"))
        assertNull(tracker.replacement("2"))
    }

    @Test
    fun boundEvictsOldestTrackedPin() {
        val tracker = ActiveNotificationTracker(maximumTracked = 2)
        val first = notification("1")
        val second = notification("2")
        val third = notification("3")
        tracker.track(first)
        tracker.track(second)
        tracker.track(third)

        assertNull(tracker.active(first.itemId))
        assertEquals(second, tracker.active(second.itemId))
        assertEquals(third, tracker.active(third.itemId))
    }

    @Test
    fun disablingSourceRemovesOnlyItsTrackedPins() {
        val tracker = ActiveNotificationTracker()
        val mail = notification("1", "org.example.mail")
        val chat = notification("2", "org.example.chat")
        tracker.track(mail)
        tracker.track(chat)

        assertEquals(listOf(mail.itemId), tracker.removePackages(setOf("org.example.mail")))
        assertNull(tracker.active(mail.itemId))
        assertEquals(chat, tracker.active(chat.itemId))
    }

    private fun notification(id: String, packageName: String = "org.example.app") = ActiveNotification(
        itemId = Uuid.random(),
        notificationId = id,
        packageName = packageName,
        title = "Title",
        body = "Body",
        actions = listOf(WatchAction.Dismiss, WatchAction.MuteApp),
    )
}
