package io.rebble.libpebblecommon.database.dao

import androidx.room.Room
import androidx.room.execSQL
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryAppstoreData
import io.rebble.libpebblecommon.database.entity.buildTimelinePin
import io.rebble.libpebblecommon.database.entity.buildTimelineReminder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.Uuid

class LockerSnapshotJvmTest {
    @Test
    fun remoteSnapshotAndTimelineRetirementCommitAtomically() = runTest {
        val directory = Files.createTempDirectory("locker-snapshot-test-")
        val database = Room.databaseBuilder<Database>(
            name = directory.resolve("locker.db").toString(),
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            val lockerDao = database.lockerEntryDao()
            val pinDao = database.timelinePinDao()
            val reminderDao = database.timelineReminderDao()
            val removed = entry("Removed")
            val inserted = entry("Inserted")
            val pin = buildTimelinePin(removed.id, Clock.System.now()) {}
            val reminder = buildTimelineReminder(pin.itemId, Clock.System.now()) {}
            lockerDao.insertOrReplace(removed)
            pinDao.insertOrReplace(pin)
            reminderDao.insertOrReplace(reminder)

            // Force the last part of the cross-DAO transaction to fail. If Room does not keep
            // the whole snapshot in one transaction, the new entry or locker tombstone leaks.
            database.useWriterConnection {
                it.execSQL(
                    """
                    CREATE TRIGGER fail_timeline_retirement
                    BEFORE UPDATE OF deleted ON TimelinePinEntity
                    BEGIN
                        SELECT RAISE(ABORT, 'test failure');
                    END
                    """.trimIndent(),
                )
            }
            assertFails {
                lockerDao.applyRemoteSnapshot(
                    entries = listOf(inserted),
                    removedIds = listOf(removed.id),
                    syncLimit = 50,
                    timelinePinDao = pinDao,
                    timelineReminderDao = reminderDao,
                )
            }
            assertNotNull(lockerDao.getEntry(removed.id))
            assertNull(lockerDao.getEntry(inserted.id))
            assertNotNull(pinDao.getEntry(pin.itemId))
            assertNotNull(reminderDao.getEntry(reminder.itemId))

            database.useWriterConnection {
                it.execSQL("DROP TRIGGER fail_timeline_retirement")
            }
            lockerDao.applyRemoteSnapshot(
                entries = listOf(inserted),
                removedIds = listOf(removed.id),
                syncLimit = 50,
                timelinePinDao = pinDao,
                timelineReminderDao = reminderDao,
            )
            assertNull(lockerDao.getEntry(removed.id))
            assertNotNull(lockerDao.getEntry(inserted.id))
            assertNull(pinDao.getEntry(pin.itemId))
            assertNull(reminderDao.getEntry(reminder.itemId))
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun remoteAccountRetirementHidesRemoteAppsScrubsTokensAndRetiresTimelineChildren() = runTest {
        val directory = Files.createTempDirectory("locker-account-retirement-test-")
        val database = Room.databaseBuilder<Database>(
            name = directory.resolve("locker.db").toString(),
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            val lockerDao = database.lockerEntryDao()
            val pinDao = database.timelinePinDao()
            val reminderDao = database.timelineReminderDao()
            val remote = entry("Remote", userToken = "remote-token")
            val previouslyDeletedRemote = entry("Deleted remote", userToken = "deleted-token")
            val system = entry("System", systemApp = true, userToken = "system-token")
            val sideloaded = entry("Sideloaded", sideloaded = true, userToken = "sideloaded-token")
            lockerDao.insertOrReplace(listOf(remote, previouslyDeletedRemote, system, sideloaded))
            lockerDao.markForDeletion(previouslyDeletedRemote.id)

            val remotePin = buildTimelinePin(remote.id, Clock.System.now()) {}
            val deletedRemotePin = buildTimelinePin(previouslyDeletedRemote.id, Clock.System.now()) {}
            val systemPin = buildTimelinePin(system.id, Clock.System.now()) {}
            val sideloadedPin = buildTimelinePin(sideloaded.id, Clock.System.now()) {}
            val remoteReminder = buildTimelineReminder(remotePin.itemId, Clock.System.now()) {}
            val deletedRemoteReminder = buildTimelineReminder(deletedRemotePin.itemId, Clock.System.now()) {}
            val systemReminder = buildTimelineReminder(systemPin.itemId, Clock.System.now()) {}
            val sideloadedReminder = buildTimelineReminder(sideloadedPin.itemId, Clock.System.now()) {}
            pinDao.insertOrReplace(listOf(remotePin, deletedRemotePin, systemPin, sideloadedPin))
            reminderDao.insertOrReplace(
                listOf(remoteReminder, deletedRemoteReminder, systemReminder, sideloadedReminder),
            )

            lockerDao.retireRemoteAccountData(pinDao, reminderDao)

            assertNull(lockerDao.getEntry(remote.id))
            assertNull(lockerDao.getEntry(previouslyDeletedRemote.id))
            assertNotNull(lockerDao.getEntry(system.id))
            assertNotNull(lockerDao.getEntry(sideloaded.id))
            assertEquals(0, lockerDao.countUserTokensIncludingDeleted())
            assertNull(pinDao.getEntry(remotePin.itemId))
            assertNull(pinDao.getEntry(deletedRemotePin.itemId))
            assertNull(reminderDao.getEntry(remoteReminder.itemId))
            assertNull(reminderDao.getEntry(deletedRemoteReminder.itemId))
            assertNotNull(pinDao.getEntry(systemPin.itemId))
            assertNotNull(reminderDao.getEntry(systemReminder.itemId))
            assertNotNull(pinDao.getEntry(sideloadedPin.itemId))
            assertNotNull(reminderDao.getEntry(sideloadedReminder.itemId))
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun remoteAccountRetirementRollsBackRowsTokensAndTimelineChildrenOnFailure() = runTest {
        val directory = Files.createTempDirectory("locker-account-retirement-test-")
        val database = Room.databaseBuilder<Database>(
            name = directory.resolve("locker.db").toString(),
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            val lockerDao = database.lockerEntryDao()
            val pinDao = database.timelinePinDao()
            val reminderDao = database.timelineReminderDao()
            val remote = entry("Remote", userToken = "remote-token")
            val pin = buildTimelinePin(remote.id, Clock.System.now()) {}
            val reminder = buildTimelineReminder(pin.itemId, Clock.System.now()) {}
            lockerDao.insertOrReplace(remote)
            pinDao.insertOrReplace(pin)
            reminderDao.insertOrReplace(reminder)
            database.useWriterConnection {
                it.execSQL(
                    """
                    CREATE TRIGGER fail_account_token_scrub
                    BEFORE UPDATE OF userToken ON LockerEntryEntity
                    BEGIN
                        SELECT RAISE(ABORT, 'test failure');
                    END
                    """.trimIndent(),
                )
            }

            assertFails {
                lockerDao.retireRemoteAccountData(pinDao, reminderDao)
            }

            assertNotNull(lockerDao.getEntry(remote.id))
            assertEquals(1, lockerDao.countUserTokensIncludingDeleted())
            assertNotNull(pinDao.getEntry(pin.itemId))
            assertNotNull(reminderDao.getEntry(reminder.itemId))
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun entry(
        title: String,
        systemApp: Boolean = false,
        sideloaded: Boolean = false,
        userToken: String? = null,
    ) = LockerEntry(
        id = Uuid.random(),
        version = "1.0",
        title = title,
        type = "watchapp",
        developerName = "Test",
        configurable = false,
        pbwVersionCode = "1",
        platforms = emptyList(),
        systemApp = systemApp,
        sideloaded = sideloaded,
        appstoreData = userToken?.let {
            LockerEntryAppstoreData(
                hearts = 0,
                developerId = "Test",
                timelineEnabled = false,
                removeLink = "",
                shareLink = "",
                pbwLink = "",
                userToken = it,
            )
        },
    )
}
