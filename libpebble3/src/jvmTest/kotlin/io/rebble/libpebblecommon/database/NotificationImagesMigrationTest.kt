package io.rebble.libpebblecommon.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.io.File
import kotlin.test.*

class NotificationImagesMigrationTest {
    @Test fun upgradesShippedSailfish42WithoutLosingSendText() = runTest { migrate(true) }
    @Test fun upgradesUpstream42() = runTest { migrate(false) }

    private suspend fun migrate(sailfish: Boolean) {
        val dir = Files.createTempDirectory("notification-migration")
        val path = dir.resolve("test.db").toString()
        val driver = BundledSQLiteDriver()
        val statements = if (sailfish) {
            javaClass.getResourceAsStream("/sailfish-schema-42.sql")!!.bufferedReader().use { it.readText() }.split(';')
        } else {
            val schema = Json.parseToJsonElement(File("schema/io.rebble.libpebblecommon.database.Database/42.json").readText())
            schema.jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray.flatMap { entry ->
                val table = entry.jsonObject
                (listOf(table.getValue("createSql")) + table["indices"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("createSql") })
                    .map { it.jsonPrimitive.content.replace("\${TABLE_NAME}", table.getValue("tableName").jsonPrimitive.content) }
            }
        }
        driver.open(path).use { connection ->
            statements.filter { it.isNotBlank() }.forEach(connection::execSQL)
            connection.execSQL("INSERT INTO AppPrefsEntryEntity (recordHashcode, deleted, sync, id, value) VALUES (17, 0, 1, 'migration-sentinel', 'preserved')")
            if (sailfish) connection.execSQL("INSERT INTO SendTextContactEntity (recordHashcode, deleted, sync, contactId, name, methodsJson) VALUES (42, 0, 1, '00112233-4455-6677-8899-aabbccddeeff', 'Alice', '[]')")
            connection.execSQL("PRAGMA user_version = 42")
        }
        val database = Room.databaseBuilder<Database>(name = path)
            .addMigrations(MIGRATION_42_46)
            .setDriver(driver).setQueryCoroutineContext(Dispatchers.IO).build()
        try {
            assertEquals("preserved", database.appPrefsDao().getEntry("migration-sentinel")?.value)
            assertEquals(if (sailfish) listOf("Alice") else emptyList(), database.sendTextContactDao().getAll().map { it.name })
            database.notificationAppDao().getEntry("absent") // force generated DAO/schema use
        } finally {
            database.close()
            dir.toFile().deleteRecursively()
        }
    }
}
