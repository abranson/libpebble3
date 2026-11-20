package io.rebble.libpebblecommon.database

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.util.JvmPaths

internal actual fun getDatabaseBuilder(ctx: AppContext): RoomDatabase.Builder<Database> {
    // XDG data dir, not tmpdir: the database holds known watches and connect
    // goals and must survive reboots for automatic reconnection.
    val dbFile = JvmPaths.dataSubdir("db").resolve(DATABASE_FILENAME)
    return Room.databaseBuilder<Database>(
        name = dbFile.toAbsolutePath().toString(),
    ).addMigrations(MIGRATION_42_43, MIGRATION_47_48)
}

// Linux v42 shipped Send Text while upstream v42 shipped the first weather extension.
private val MIGRATION_42_43 = object : Migration(42, 43) {
    override fun migrate(connection: SQLiteConnection) {
        connection.addColumnIfMissing("WeatherAppEntryEntity", "todayFeelsLikeTemp", "INTEGER")
        connection.addColumnIfMissing("WeatherAppEntryEntity", "latitude", "REAL")
        connection.addColumnIfMissing("WeatherAppEntryEntity", "longitude", "REAL")
        connection.addColumnIfMissing("WeatherAppEntryEntity", "dailyForecast", "TEXT")
        connection.addColumnIfMissing("WeatherAppEntryEntity", "todayUvIndexX10", "INTEGER")
        connection.addColumnIfMissing("WeatherAppEntryEntity", "todayPrecipProbability", "INTEGER")
        connection.addColumnIfMissing("WeatherAppEntryEntity", "todayWindSpeed", "INTEGER")
        connection.addColumnIfMissing("WeatherAppEntryEntity", "todayWindDirection", "INTEGER")
        connection.addColumnIfMissing("WeatherAppEntryEntity", "todayHourly", "TEXT")
        connection.addColumnIfMissing("WeatherAppEntryEntity", "locationUtcOffsetMin", "INTEGER")
    }
}

// Linux v47 shipped oxygen support before the upstream writable-calendar and plugin fields.
private val MIGRATION_47_48 = object : Migration(47, 48) {
    override fun migrate(connection: SQLiteConnection) {
        connection.addColumnIfMissing("CalendarEntity", "writable", "INTEGER NOT NULL DEFAULT 1")
        connection.addColumnIfMissing("LockerEntryEntity", "pluginManifest", "TEXT DEFAULT NULL")
        connection.addColumnIfMissing("LockerEntryEntity", "configPage", "TEXT DEFAULT NULL")
        connection.execSQL("ALTER TABLE LockerEntryEntity ADD COLUMN manifestVersion INTEGER NOT NULL DEFAULT 1")
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS AppPermission (
                appUuid TEXT NOT NULL,
                permission TEXT NOT NULL,
                granted INTEGER,
                requestedAtMs INTEGER NOT NULL,
                decidedAtMs INTEGER,
                lastUsedAtMs INTEGER,
                PRIMARY KEY(appUuid, permission)
            )
        """.trimIndent())
        // Legacy Location used the same grant key and stored no timestamps.
        connection.execSQL("""
            INSERT INTO AppPermission (appUuid, permission, granted, requestedAtMs)
            SELECT appUuid, permission, granted, 0 FROM LockerAppPermission
        """.trimIndent())
        connection.execSQL("DROP TABLE LockerAppPermission")
    }
}
