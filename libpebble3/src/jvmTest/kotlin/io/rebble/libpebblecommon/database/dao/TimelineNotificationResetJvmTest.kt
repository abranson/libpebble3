package io.rebble.libpebblecommon.database.dao

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class TimelineNotificationResetJvmTest {
    @Test
    fun resetSuppressesExistingPendingNotificationsButNotNewOnes() = runTest {
        val directory = Files.createTempDirectory("timeline-notification-reset-test-")
        val database = Room.databaseBuilder<Database>(
            name = directory.resolve("timeline.db").toString(),
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            val dao = database.timelineNotificationDao()
            val existing = notification(100)
            dao.insertOrReplace(existing)

            assertEquals(
                listOf(existing.itemId),
                dao.pendingFor(TEST_TRANSPORT).map { it.record.itemId },
            )
            assertEquals(
                true,
                dao.isPendingForWatch(TEST_TRANSPORT, existing.itemId, existing.recordHashCode()),
            )

            dao.suppressCurrentForWatch(TEST_TRANSPORT)

            assertEquals(emptyList(), dao.pendingFor(TEST_TRANSPORT))
            assertEquals(
                false,
                dao.isPendingForWatch(TEST_TRANSPORT, existing.itemId, existing.recordHashCode()),
            )

            val postedAfterReset = notification(200)
            dao.insertOrReplace(postedAfterReset)
            assertEquals(
                listOf(postedAfterReset.itemId),
                dao.pendingFor(TEST_TRANSPORT).map { it.record.itemId },
            )
            assertEquals(
                true,
                dao.isPendingForWatch(
                    TEST_TRANSPORT,
                    postedAfterReset.itemId,
                    postedAfterReset.recordHashCode(),
                ),
            )
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private suspend fun TimelineNotificationRealDao.pendingFor(transport: String) =
        dirtyRecordsForWatchInsertInWindow(
            transport = transport,
            windowStartMs = 0,
            notificationStartMs = 0,
            windowEndMs = 1_000_000,
        ).first()

    private fun notification(epochSeconds: Long) = buildTimelineNotification(
        parentId = Uuid.random(),
        timestamp = Instant.fromEpochSeconds(epochSeconds),
    ) { }

    private companion object {
        const val TEST_TRANSPORT = "AA:BB:CC:DD:EE:FF"
    }
}
