package io.rebble.libpebblecommon.connection.bt.ble.bluez

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BluezDiscoveryCoordinatorTest {
    @Test
    fun replacementStopsPreviousAndStaleCloseDoesNothing() {
        val events = mutableListOf<String>()
        val coordinator = BluezDiscoveryCoordinator()

        val first = coordinator.acquire(
            cancelled = { false },
            start = { events += "start-first" },
            stop = { events += "stop-first" },
        )!!
        val second = coordinator.acquire(
            cancelled = { false },
            start = { events += "start-second" },
            stop = { events += "stop-second" },
        )!!

        first.close()
        second.close()
        coordinator.stopCurrent()

        assertEquals(
            listOf("start-first", "stop-first", "start-second", "stop-second"),
            events,
        )
    }

    @Test
    fun cancellationDuringStartCleansUpWithoutPublishingLease() {
        val events = mutableListOf<String>()
        var cancelled = false
        val coordinator = BluezDiscoveryCoordinator()

        val lease = coordinator.acquire(
            cancelled = { cancelled },
            start = {
                events += "start"
                cancelled = true
            },
            stop = { events += "stop" },
        )

        assertNull(lease)
        assertEquals(listOf("start", "stop"), events)
    }

    @Test
    fun preCancelledReplacementDoesNotEvictCurrentOwner() {
        val events = mutableListOf<String>()
        val coordinator = BluezDiscoveryCoordinator()
        val first = coordinator.acquire(
            cancelled = { false },
            start = { events += "start-first" },
            stop = { events += "stop-first" },
        )!!

        val cancelled = coordinator.acquire(
            cancelled = { true },
            start = { events += "start-cancelled" },
            stop = { events += "stop-cancelled" },
        )
        first.close()
        coordinator.stopCurrent()

        assertNull(cancelled)
        assertEquals(listOf("start-first", "stop-first"), events)
    }

    @Test
    fun explicitStopInvalidatesLease() {
        var stops = 0
        val coordinator = BluezDiscoveryCoordinator()
        val lease = coordinator.acquire(
            cancelled = { false },
            start = {},
            stop = { stops++ },
        )!!

        coordinator.stopCurrent()
        lease.close()
        coordinator.stopCurrent()

        assertEquals(1, stops)
    }

    @Test
    fun unownedStopRunsFallbackBeforeLaterAcquisition() {
        val events = mutableListOf<String>()
        val coordinator = BluezDiscoveryCoordinator()

        coordinator.stopCurrent { events += "stop-untracked" }
        val lease = coordinator.acquire(
            cancelled = { false },
            start = { events += "start" },
            stop = { events += "stop" },
        )!!
        lease.close()
        coordinator.stopCurrent()

        assertEquals(listOf("stop-untracked", "start", "stop"), events)
    }
}
