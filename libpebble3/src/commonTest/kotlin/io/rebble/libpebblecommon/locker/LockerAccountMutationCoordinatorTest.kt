package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.connection.WebAccountSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockerAccountMutationCoordinatorTest {
    private data class TestSession(val value: String) : WebAccountSession

    @Test
    fun staleSessionQueuedBehindTransitionIsRejected() = runTest {
        val coordinator = LockerAccountMutationCoordinator()
        val accountA = TestSession("account-a")
        val staleSession = coordinator.capture { accountA }
        val transitionEntered = CompletableDeferred<Unit>()
        val allowTransition = CompletableDeferred<Unit>()
        var staleCommitRan = false

        val transition = async {
            coordinator.transition {
                transitionEntered.complete(Unit)
                allowTransition.await()
            }
        }
        transitionEntered.await()
        val staleCommit = async {
            coordinator.commitIfCurrent(staleSession, { true }) {
                staleCommitRan = true
                true
            }
        }
        runCurrent()
        assertFalse(staleCommit.isCompleted)

        allowTransition.complete(Unit)
        transition.await()

        assertFalse(staleCommit.await())
        assertFalse(staleCommitRan)
    }

    @Test
    fun oldCommitFinishesBeforeQueuedTransitionClearsItsState() = runTest {
        val coordinator = LockerAccountMutationCoordinator()
        val accountA = TestSession("account-a")
        val session = coordinator.capture { accountA }
        val commitEntered = CompletableDeferred<Unit>()
        val allowCommit = CompletableDeferred<Unit>()
        val transitionEntered = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val oldCommit = async {
            coordinator.commitIfCurrent(session, { it == accountA }) {
                commitEntered.complete(Unit)
                allowCommit.await()
                events += "old-commit"
                true
            }
        }
        commitEntered.await()
        val transition = async {
            coordinator.transition {
                events += "transition-clear"
                transitionEntered.complete(Unit)
            }
        }
        runCurrent()
        assertFalse(transitionEntered.isCompleted)

        allowCommit.complete(Unit)
        assertTrue(oldCommit.await())
        transition.await()

        assertEquals(listOf("old-commit", "transition-clear"), events)
    }

    @Test
    fun localGenerationRejectsStaleSessionAfterAccountAba() = runTest {
        val coordinator = LockerAccountMutationCoordinator()
        val accountA = TestSession("account-a")
        val staleAccountA = coordinator.capture { accountA }
        val transitions = mutableListOf<String>()

        coordinator.transition { transitions += "account-b" }
        coordinator.transition { transitions += "account-a" }

        var staleCommitRan = false
        val committed = coordinator.commitIfCurrent(
            session = staleAccountA,
            // The external credential comparison alone sees account A again. The coordinator's
            // local generation must still reject work captured before the A -> B -> A cycle.
            isWebSessionCurrent = { it == accountA },
        ) {
            staleCommitRan = true
            true
        }

        assertEquals(listOf("account-b", "account-a"), transitions)
        assertFalse(committed)
        assertFalse(staleCommitRan)
    }

    @Test
    fun sessionSupplierIsEvaluatedAfterQueuedTransition() = runTest {
        val coordinator = LockerAccountMutationCoordinator()
        val accountA = TestSession("account-a")
        val accountB = TestSession("account-b")
        var current = accountA
        val transitionEntered = CompletableDeferred<Unit>()
        val releaseTransition = CompletableDeferred<Unit>()

        val transition = async {
            coordinator.transition {
                transitionEntered.complete(Unit)
                releaseTransition.await()
            }
        }
        transitionEntered.await()
        val capture = async { coordinator.capture { current } }
        runCurrent()
        assertFalse(capture.isCompleted)

        current = accountB
        releaseTransition.complete(Unit)
        transition.await()

        assertEquals(accountB, capture.await().webSession)
    }

    @Test
    fun staleWebSessionCannotCommitAccountBackedInsertion() = runTest {
        val coordinator = LockerAccountMutationCoordinator()
        val accountA = TestSession("account-a")
        var current = accountA
        var inserted = false

        current = TestSession("account-b")
        val committed = coordinator.commitIfWebSessionCurrent(
            session = accountA,
            isWebSessionCurrent = { it == current },
        ) {
            inserted = true
        }

        assertFalse(committed)
        assertFalse(inserted)
    }
}
