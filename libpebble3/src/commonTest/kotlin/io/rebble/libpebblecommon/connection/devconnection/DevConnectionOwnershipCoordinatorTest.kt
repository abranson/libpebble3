package io.rebble.libpebblecommon.connection.devconnection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DevConnectionOwnershipCoordinatorTest {
    @Test
    fun stopCannotMissStartBetweenLeaseAcquisitionAndRunPublication() = runTest {
        val gate = DevConnectionTransitionGate()
        val leaseAcquired = CompletableDeferred<Unit>()
        val allowPublication = CompletableDeferred<Unit>()
        var runPublished = false

        val start = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run {
                leaseAcquired.complete(Unit)
                allowPublication.await()
                runPublished = true
            }
        }
        leaseAcquired.await()

        val stop = launch {
            gate.run {
                assertTrue(runPublished)
            }
        }
        runCurrent()
        assertFalse(stop.isCompleted)

        allowPublication.complete(Unit)
        start.join()
        stop.join()
    }

    @Test
    fun startCannotPassAStopWhoseCleanupIsStillRunning() = runTest {
        val gate = DevConnectionTransitionGate()
        val stopEntered = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        var stopped = false

        val stop = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run {
                stopEntered.complete(Unit)
                allowCleanup.await()
                stopped = true
            }
        }
        stopEntered.await()

        val start = launch {
            gate.run {
                assertTrue(stopped)
            }
        }
        runCurrent()
        assertFalse(start.isCompleted)

        allowCleanup.complete(Unit)
        stop.join()
        start.join()
    }

    @Test
    fun firstOwnerWinsAndRepeatedStartIsIdempotent() {
        val coordinator = DevConnectionOwnershipCoordinator()
        val firstOwner = Any()
        val otherOwner = Any()

        val first = assertNotNull(coordinator.tryAcquire(firstOwner))
        val repeated = assertNotNull(coordinator.tryAcquire(firstOwner))

        assertTrue(first.acquired)
        assertFalse(repeated.acquired)
        assertSame(first.lease, repeated.lease)
        assertNull(coordinator.tryAcquire(otherOwner))
    }

    @Test
    fun nonOwnerCannotReleaseCurrentOwner() {
        val coordinator = DevConnectionOwnershipCoordinator()
        val owner = Any()
        val nonOwner = Any()
        val lease = assertNotNull(coordinator.tryAcquire(owner)).lease

        assertFalse(coordinator.release(nonOwner, lease))
        assertNull(coordinator.tryAcquire(nonOwner))
        assertFalse(assertNotNull(coordinator.tryAcquire(owner)).acquired)
    }

    @Test
    fun deniedRequestIsNotQueuedAfterRelease() {
        val coordinator = DevConnectionOwnershipCoordinator()
        val owner = Any()
        val deniedOwner = Any()
        val laterOwner = Any()
        val lease = assertNotNull(coordinator.tryAcquire(owner)).lease

        assertNull(coordinator.tryAcquire(deniedOwner))
        assertTrue(coordinator.release(owner, lease))

        assertTrue(assertNotNull(coordinator.tryAcquire(laterOwner)).acquired)
        assertNull(coordinator.tryAcquire(deniedOwner))
    }

    @Test
    fun staleReleaseCannotAffectNewOwner() {
        val coordinator = DevConnectionOwnershipCoordinator()
        val oldOwner = Any()
        val newOwner = Any()
        val thirdOwner = Any()
        val oldLease = assertNotNull(coordinator.tryAcquire(oldOwner)).lease

        assertTrue(coordinator.release(oldOwner, oldLease))
        val newLease = assertNotNull(coordinator.tryAcquire(newOwner)).lease

        assertFalse(coordinator.release(oldOwner, oldLease))
        assertNull(coordinator.tryAcquire(thirdOwner))
        assertFalse(assertNotNull(coordinator.tryAcquire(newOwner)).acquired)
        assertTrue(coordinator.release(newOwner, newLease))
    }
}
