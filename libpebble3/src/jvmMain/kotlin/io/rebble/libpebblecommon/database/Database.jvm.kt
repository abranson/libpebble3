package io.rebble.libpebblecommon.database

import androidx.room.Room
import androidx.room.RoomDatabase
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.util.JvmPaths

internal actual fun getDatabaseBuilder(ctx: AppContext): RoomDatabase.Builder<Database> {
    // XDG data dir, not tmpdir: the database holds known watches and connect
    // goals and must survive reboots for automatic reconnection.
    val dbFile = JvmPaths.dataSubdir("db").resolve(DATABASE_FILENAME)
    return Room.databaseBuilder<Database>(
        name = dbFile.toAbsolutePath().toString(),
    )
}
