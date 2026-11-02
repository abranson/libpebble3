package io.rebble.libpebblecommon.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.rebble.libpebblecommon.database.dao.insertHealthDataWithPriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class HealthOxygenMigrationTest {
    @Test
    fun preservesOldHealthAndMergesOxygenIndependentlyOfSteps() = runTest {
        val dir = Files.createTempDirectory("health-oxygen-migration")
        val path = dir.resolve("test.db").toString()
        val driver = BundledSQLiteDriver()
        val schema = Json.parseToJsonElement(File("schema/io.rebble.libpebblecommon.database.Database/46.json").readText())
        driver.open(path).use { connection ->
            schema.jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray.forEach { entry ->
                val table = entry.jsonObject
                (listOf(table.getValue("createSql")) + table["indices"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("createSql") })
                    .forEach { connection.execSQL(it.jsonPrimitive.content.replace("\${TABLE_NAME}", table.getValue("tableName").jsonPrimitive.content)) }
            }
            connection.execSQL("INSERT INTO health_data VALUES (1000, 20, 1, 2, 3, 1, 4, 5, 6, 70, 1, 100)")
            connection.execSQL("INSERT INTO AppPrefsEntryEntity (recordHashcode, deleted, sync, id, value) VALUES (17, 0, 1, 'health-migration-sentinel', 'preserved')")
            connection.execSQL("PRAGMA user_version = 46")
        }
        val database = Room.databaseBuilder<Database>(name = path)
            .addMigrations(MIGRATION_46_47)
            .setDriver(driver).setQueryCoroutineContext(Dispatchers.IO).build()
        try {
            val dao = database.healthDao()
            val old = assertNotNull(dao.getDataAtTimestamp(1000))
            assertEquals(20, old.steps)
            assertEquals(70, old.heartRate)
            assertEquals(0, old.spo2Percent)
            assertEquals(0, old.spo2Quality)
            assertEquals("preserved", database.appPrefsDao().getEntry("health-migration-sentinel")?.value)
            assertNull(dao.getLatestSpo2Reading(1000))

            dao.insertHealthDataWithPriority(listOf(old.copy(spo2Percent = 98, spo2Quality = 5)))
            assertEquals(98, dao.getLatestSpo2Reading(1000)?.spo2Percent)
            assertNull(dao.getLatestSpo2Reading(999))
            // Lower step counts can improve oxygen without replacing movement/heart rate.
            dao.insertHealthDataWithPriority(listOf(old.copy(steps = 0, heartRate = 0, spo2Percent = 97, spo2Quality = 7)))
            val merged = assertNotNull(dao.getDataAtTimestamp(1000))
            assertEquals(old.copy(spo2Percent = 97, spo2Quality = 7), merged)
            // More steps without oxygen must retain the stored measurement.
            dao.insertHealthDataWithPriority(listOf(old.copy(steps = 25)))
            assertEquals(merged.copy(steps = 25), dao.getDataAtTimestamp(1000))
            dao.insertHealthDataWithPriority(listOf(old.copy(steps = 30, spo2Percent = 80, spo2Quality = 4)))
            assertEquals(merged.copy(steps = 30), dao.getDataAtTimestamp(1000))
            // Raw invalid values are stored, but cannot become the displayed latest.
            dao.insertHealthDataWithPriority(listOf(old.copy(timestamp = 2000, spo2Percent = 255, spo2Quality = 255)))
            assertEquals(255, dao.getDataAtTimestamp(2000)?.spo2Percent)
            assertEquals(1000L, dao.getLatestSpo2Reading(2000)?.timestamp)
        } finally {
            database.close()
            dir.toFile().deleteRecursively()
        }
    }
}
