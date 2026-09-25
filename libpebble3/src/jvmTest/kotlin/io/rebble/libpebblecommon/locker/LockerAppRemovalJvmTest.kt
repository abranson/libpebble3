package io.rebble.libpebblecommon.locker

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.rebble.libpebblecommon.ErrorTracker
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.WebAccountSession
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryAppstoreData
import io.rebble.libpebblecommon.database.entity.buildTimelinePin
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.web.LockerEntryCompanions
import io.rebble.libpebblecommon.web.LockerEntryCompatibility
import io.rebble.libpebblecommon.web.LockerEntryCompatibilityPhonePlatformDetails
import io.rebble.libpebblecommon.web.LockerEntryCompatibilityWatchPlatformDetails
import io.rebble.libpebblecommon.web.LockerEntryDeveloper
import io.rebble.libpebblecommon.web.LockerEntryFirmwareVersion
import io.rebble.libpebblecommon.web.LockerEntryLinks
import io.rebble.libpebblecommon.web.LockerModel
import io.rebble.libpebblecommon.web.LockerModelWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class LockerAppRemovalJvmTest {
    @Test
    fun storeSideloadIsRemovedFromBothLockersInOneRequest() = runTest {
        withFixture {
            val pin = buildTimelinePin(app.id, Clock.System.now()) {}
            database.timelinePinDao().insertOrReplace(pin)
            assertTrue(removal.removeApp(app.id))
            assertEquals(listOf("fetch", "remove", "cache"), events)
            assertNull(database.lockerEntryDao().getEntry(app.id))
            assertNull(database.timelinePinDao().getEntry(pin.itemId))
            assertTrue(web.remoteApps.isEmpty(), "a subsequent sync must not restore the face")
        }
    }

    @Test
    fun associatedSideloadAbsentFromRemoteLockerIsRemovedLocally() = runTest {
        withFixture {
            web.remoteApps.clear()
            assertTrue(removal.removeApp(app.id))
            assertEquals(listOf("fetch", "cache"), events)
            assertNull(database.lockerEntryDao().getEntry(app.id))
        }
    }

    @Test
    fun failedLookupKeepsLocalRowAndPayload() = runTest {
        withFixture {
            web.failFetch = true
            assertFalse(removal.removeApp(app.id))
            assertNotNull(database.lockerEntryDao().getEntry(app.id))
            assertEquals(listOf("fetch"), events)
        }
    }

    @Test
    fun failedRemoteRemovalKeepsLocalRowAndPayload() = runTest {
        withFixture {
            web.failRemove = true
            assertFalse(removal.removeApp(app.id))
            assertNotNull(database.lockerEntryDao().getEntry(app.id))
            assertEquals(listOf("fetch", "remove"), events)
        }
    }

    @Test
    fun partialLockerCannotProveRemoteAbsence() = runTest {
        withFixture {
            web.remoteApps.clear()
            web.nextPage = "https://example.test/next"
            assertFalse(removal.removeApp(app.id))
            assertNotNull(database.lockerEntryDao().getEntry(app.id))
            assertEquals(listOf("fetch"), events)
        }
    }

    @Test
    fun accountSwitchDuringLookupDoesNotDeleteFromReplacementAccount() = runTest {
        withFixture {
            web.afterFetch = { web.invalidateAccountSessions() }
            assertFalse(removal.removeApp(app.id))
            assertNotNull(database.lockerEntryDao().getEntry(app.id))
            assertEquals(listOf("fetch"), events)
        }
    }

    @Test
    fun accountSwitchDuringRemoteDeleteDoesNotCommitLocally() = runTest {
        withFixture {
            web.afterRemove = { coordinator.transition { web.invalidateAccountSessions() } }
            assertFalse(removal.removeApp(app.id))
            assertNotNull(database.lockerEntryDao().getEntry(app.id))
            assertEquals(listOf("fetch", "remove"), events)
        }
    }

    @Test
    fun normalRemoteAppStillDeletesWithoutMembershipLookup() = runTest {
        withFixture {
            database.lockerEntryDao().insertOrReplace(app.copy(sideloaded = false))
            assertTrue(removal.removeApp(app.id))
            assertEquals(listOf("remove", "cache"), events)
            assertNull(database.lockerEntryDao().getEntry(app.id))
        }
    }

    @Test
    fun unassociatedSideloadRetainsLocalRemovalBehaviour() = runTest {
        withFixture {
            database.lockerEntryDao().insertOrReplace(app.copy(appstoreData = null))
            assertTrue(removal.removeApp(app.id))
            assertEquals(listOf("cache", "sync"), events)
            assertNull(database.lockerEntryDao().getEntry(app.id))
        }
    }

    @Test
    fun signedOutSideloadCanStillBeRemovedOffline() = runTest {
        withFixture {
            web.signedIn = false
            assertTrue(removal.removeApp(app.id))
            assertEquals(listOf("cache", "sync"), events)
            assertNull(database.lockerEntryDao().getEntry(app.id))
        }
    }

    @Test
    fun deletionRejectsSnapshotsCapturedBeforeAndDuringRemoteRemoval() = runTest {
        withFixture {
            val before = coordinator.capture(web::currentAccountSession)
            var during: LockerRemoteAccountSession? = null
            web.afterRemove = { during = coordinator.capture(web::currentAccountSession) }
            assertTrue(removal.removeApp(app.id))

            for (snapshot in listOf(before, checkNotNull(during))) {
                val applied = coordinator.commitSnapshotIfCurrent(snapshot, web::isCurrentAccountSession) {
                    database.lockerEntryDao().insertOrReplace(app.copy(sideloaded = false))
                    true
                }
                assertFalse(applied, "a stale sync must not resurrect a removed app")
                assertNull(database.lockerEntryDao().getEntry(app.id))
            }
            val fresh = coordinator.capture(web::currentAccountSession)
            assertTrue(coordinator.commitSnapshotIfCurrent(fresh, web::isCurrentAccountSession) { true })
        }
    }

    private suspend fun withFixture(block: suspend Fixture.() -> Unit) {
        val directory = Files.createTempDirectory("locker-removal-test-")
        val database = Room.databaseBuilder<Database>(directory.resolve("locker.db").toString())
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            val fixture = Fixture(database)
            database.lockerEntryDao().insertOrReplace(fixture.app)
            fixture.block()
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private class Fixture(val database: Database) {
        val app = LockerEntry(
            id = Uuid.random(), version = "1.0", title = "Store face", type = "watchface",
            developerName = "Test", configurable = false, pbwVersionCode = "1",
            sideloaded = true, platforms = emptyList(),
            appstoreData = LockerEntryAppstoreData(
                0, "developer", true, "", "", "", null, storeId = "store-id",
            ),
        )
        val events = mutableListOf<String>()
        val web = RecordingWebServices(app.id, events)
        val coordinator = LockerAccountMutationCoordinator()
        val removal = LockerAppRemoval(
            database, coordinator, web, ErrorTracker(),
            deleteCachedApp = { events += "cache" },
            requestLockerSync = { events += "sync" },
        )
    }

    private class RecordingWebServices(id: Uuid, private val events: MutableList<String>) : WebServices {
        private data class Session(val generation: Int) : WebAccountSession
        private var generation = 0
        var signedIn = true
        var failFetch = false
        var failRemove = false
        var nextPage: String? = null
        var afterFetch: suspend () -> Unit = {}
        var afterRemove: suspend () -> Unit = {}
        val remoteApps = mutableListOf(remoteEntry(id))
        override fun usesAccountSessions() = true
        override fun currentAccountSession(): WebAccountSession? = if (signedIn) Session(generation) else null
        override fun isCurrentAccountSession(session: WebAccountSession) = session == currentAccountSession()
        override fun invalidateAccountSessions() { generation++ }
        override suspend fun fetchLocker(): LockerModelWrapper? = error("must use captured account")
        override suspend fun removeFromLocker(id: Uuid): Boolean = error("must use captured account")
        override suspend fun fetchLocker(session: WebAccountSession): LockerModelWrapper? {
            assertTrue(isCurrentAccountSession(session))
            events += "fetch"
            afterFetch()
            return if (failFetch) null else LockerModelWrapper(LockerModel(remoteApps.toList(), nextPage), emptySet())
        }
        override suspend fun removeFromLocker(id: Uuid, session: WebAccountSession): Boolean {
            assertTrue(isCurrentAccountSession(session))
            events += "remove"
            if (failRemove) return false
            remoteApps.removeAll { it.uuid == id.toString() }
            afterRemove()
            return true
        }
        override suspend fun checkForFirmwareUpdate(watch: WatchInfo, force: Boolean) =
            FirmwareUpdateCheckResult.FoundNoUpdate
        override fun uploadMemfaultChunk(chunk: ByteArray, watchInfo: WatchInfo) {}
        override fun uploadAnalyticsHeartbeat(payload: ByteArray, watchInfo: WatchInfo) {}
    }

    companion object {
        private fun remoteEntry(uuid: Uuid): io.rebble.libpebblecommon.web.LockerEntry {
            val phone = LockerEntryCompatibilityPhonePlatformDetails(false)
            val watch = LockerEntryCompatibilityWatchPlatformDetails(false, LockerEntryFirmwareVersion(4))
            return io.rebble.libpebblecommon.web.LockerEntry(
                id = "store-id", uuid = uuid.toString(), title = "Store face", type = "watchface",
                category = "", hearts = 0, isConfigurable = false, isTimelineEnabled = true,
                links = LockerEntryLinks("", "", ""), developer = LockerEntryDeveloper("", "", ""),
                hardwarePlatforms = emptyList(), companions = LockerEntryCompanions(null, null),
                compatibility = LockerEntryCompatibility(phone, phone, watch, watch, watch, watch, watch),
            )
        }
    }
}
