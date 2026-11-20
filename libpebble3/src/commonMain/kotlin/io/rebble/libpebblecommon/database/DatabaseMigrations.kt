package io.rebble.libpebblecommon.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

// Microphone is bit 0. Rename in place so the locker sync rows survive with their parent.
internal val MIGRATION_53_54 = object : Migration(53, 54) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE LockerEntryEntity RENAME COLUMN microphoneGranted TO watchPermissions")
    }
}

// Linux databases can already have the Send Text tables and oxygen columns.
internal val MIGRATION_54_55 = object : Migration(54, 55) {
    override fun migrate(connection: SQLiteConnection) {
        connection.addColumnIfMissing("health_data", "spo2Percent", "INTEGER NOT NULL DEFAULT 0")
        connection.addColumnIfMissing("health_data", "spo2Quality", "INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `SendTextContactEntity` (
                `recordHashcode` INTEGER NOT NULL,
                `deleted` INTEGER NOT NULL,
                `sync` INTEGER NOT NULL DEFAULT 1,
                `contactId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `methodsJson` TEXT NOT NULL,
                PRIMARY KEY(`contactId`)
            )
        """.trimIndent())
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `SendTextContactSyncEntity` (
                `recordId` TEXT NOT NULL,
                `transport` TEXT NOT NULL,
                `watchSynchHashcode` INTEGER NOT NULL,
                PRIMARY KEY(`recordId`, `transport`),
                FOREIGN KEY(`recordId`)
                    REFERENCES `SendTextContactEntity`(`contactId`)
                    ON UPDATE CASCADE ON DELETE CASCADE
            )
        """.trimIndent())
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `SendTextCannedResponsesEntity` (
                `recordHashcode` INTEGER NOT NULL,
                `deleted` INTEGER NOT NULL,
                `sync` INTEGER NOT NULL DEFAULT 1,
                `source` TEXT NOT NULL,
                `responsesJson` TEXT NOT NULL,
                PRIMARY KEY(`source`)
            )
        """.trimIndent())
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `SendTextCannedResponsesSyncEntity` (
                `recordId` TEXT NOT NULL,
                `transport` TEXT NOT NULL,
                `watchSynchHashcode` INTEGER NOT NULL,
                PRIMARY KEY(`recordId`, `transport`),
                FOREIGN KEY(`recordId`)
                    REFERENCES `SendTextCannedResponsesEntity`(`source`)
                    ON UPDATE CASCADE ON DELETE CASCADE
            )
        """.trimIndent())
    }
}

internal fun SQLiteConnection.addColumnIfMissing(table: String, column: String, type: String) {
    val present = prepare("PRAGMA table_info(`$table`)").use { statement ->
        while (statement.step()) {
            if (statement.getText(1) == column) return@use true
        }
        false
    }
    if (!present) execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $type")
}
