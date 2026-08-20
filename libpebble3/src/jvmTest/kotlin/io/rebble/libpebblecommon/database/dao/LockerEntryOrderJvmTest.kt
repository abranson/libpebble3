package io.rebble.libpebblecommon.database.dao

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.entity.LockerEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class LockerEntryOrderJvmTest {
    @Test
    fun subsetOrderIsAtomicAndRetainsUnselectedSlots() = runTest {
        val directory = Files.createTempDirectory("locker-order-test-")
        val database = Room.databaseBuilder<Database>(
            name = directory.resolve("locker.db").toString(),
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            val dao = database.lockerEntryDao()
            val first = entry("First", 0)
            val retained = entry("Retained", 1)
            val last = entry("Last", 2)
            dao.insertOrReplace(listOf(first, retained, last))

            assertTrue(dao.setOrder(listOf(last.id, first.id), syncLimit = 2))
            assertEquals(
                listOf(last.id, retained.id, first.id),
                dao.getAppOrderFlow("watchapp", 10).first(),
            )

            val beforeRejected = dao.getAllBasicInfo().associate { it.id to it.orderIndex }
            assertFalse(dao.setOrder(listOf(first.id, first.id), syncLimit = 2))
            assertFalse(dao.setOrder(listOf(Uuid.random()), syncLimit = 2))
            assertEquals(
                beforeRejected,
                dao.getAllBasicInfo().associate { it.id to it.orderIndex },
            )
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun entry(title: String, order: Int) = LockerEntry(
        id = Uuid.random(),
        version = "1.0",
        title = title,
        type = "watchapp",
        developerName = "Test",
        configurable = false,
        pbwVersionCode = "1",
        platforms = emptyList(),
        orderIndex = order,
    )
}
