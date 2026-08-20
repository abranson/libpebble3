package io.rebble.libpebblecommon.database.dao

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class NotificationAppForgettingJvmTest {
    @Test
    fun forgottenAppDisappearsAndNormalInsertResurrectsIt() = runTest {
        val directory = Files.createTempDirectory("notification-app-forgetting-test-")
        val database = Room.databaseBuilder<Database>(
            name = directory.resolve("notifications.db").toString(),
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            val dao = database.notificationAppDao()
            val app = notificationApp("Example")
            dao.insertOrReplace(app)

            dao.markForDeletion(app.packageName)

            assertNull(dao.getEntry(app.packageName))
            assertEquals(emptyList(), dao.allAppsFlow().first())

            val registeredAgain = app.copy(name = "Example Again")
            dao.insertOrReplace(registeredAgain)

            assertEquals(registeredAgain, dao.getEntry(app.packageName))
            assertEquals(listOf(registeredAgain), dao.allAppsFlow().first())
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun reconciliationAppliesExplicitMuteAndResetsOmittedApplications() = runTest {
        val directory = Files.createTempDirectory("notification-app-reconciliation-test-")
        val database = Room.databaseBuilder<Database>(
            name = directory.resolve("notifications.db").toString(),
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            val dao = database.notificationAppDao()
            val disabled = notificationApp("Disabled").copy(
                packageName = "org.example.disabled",
                muteState = MuteState.Never,
            )
            val omitted = notificationApp("Omitted").copy(
                packageName = "org.example.omitted",
                muteState = MuteState.Always,
            )
            dao.insertOrReplace(listOf(disabled, omitted))

            dao.reconcileAppMuteStates(
                mapOf(disabled.packageName to MuteState.Always)
            )

            assertEquals(MuteState.Always, dao.getEntry(disabled.packageName)?.muteState)
            assertEquals(MuteState.Never, dao.getEntry(omitted.packageName)?.muteState)
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun notificationApp(name: String) = NotificationAppItem(
        packageName = "org.example.app",
        name = name,
        muteState = MuteState.Never,
        channelGroups = emptyList(),
        stateUpdated = Instant.fromEpochSeconds(1).asMillisecond(),
        lastNotified = Instant.fromEpochSeconds(1).asMillisecond(),
        vibePatternName = null,
        colorName = null,
        iconCode = null,
    )
}
