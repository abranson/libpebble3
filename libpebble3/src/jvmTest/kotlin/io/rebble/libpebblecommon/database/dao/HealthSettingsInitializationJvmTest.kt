package io.rebble.libpebblecommon.database.dao

import coredev.BlobDatabase
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.database.entity.KEY_BLOOD_OXYGEN_PREFERENCES
import io.rebble.libpebblecommon.database.entity.KEY_SPO2_PREFERENCES
import io.rebble.libpebblecommon.database.entity.decodeOxygenPreference
import io.rebble.libpebblecommon.database.entity.oxygenPreferenceBytes
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.blobdb.DbWrite
import io.rebble.libpebblecommon.services.blobdb.WriteType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Instant
import kotlin.test.assertContentEquals
import androidx.room.Room
import androidx.room.execSQL
import androidx.room.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.entity.HRMonitoringInterval
import io.rebble.libpebblecommon.database.entity.HealthGender
import io.rebble.libpebblecommon.database.entity.HealthSettingsEntry
import io.rebble.libpebblecommon.database.entity.getWatchSettings
import io.rebble.libpebblecommon.database.entity.setWatchSettings
import io.rebble.libpebblecommon.health.HealthSettings
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthSettingsInitializationJvmTest {
    @Test
    fun oxygenSettingsRoundTripWithoutOverwritingUnrelatedSettings() = runTest {
        withHealthSettingsDao { dao ->
            val original = healthSettings()
            dao.setWatchSettingsTransactionally(original)
            assertNull(dao.getEntry(KEY_BLOOD_OXYGEN_PREFERENCES))
            assertNull(dao.getEntry(KEY_SPO2_PREFERENCES))
            val selected = original.copy(bloodOxygenEnabled = true,
                spo2MeasurementInterval = HRMonitoringInterval.ThirtyMin)
            dao.setWatchSettingsTransactionally(selected)
            assertEquals(selected, dao.getWatchSettings().first())
            val enabledEntry = assertNotNull(dao.getEntry(KEY_BLOOD_OXYGEN_PREFERENCES))
            val intervalEntry = assertNotNull(dao.getEntry(KEY_SPO2_PREFERENCES))
            assertContentEquals(ubyteArrayOf(1u), enabledEntry.value(oxygenParams()))
            assertContentEquals(ubyteArrayOf(1u), intervalEntry.value(oxygenParams()))
            assertNull(enabledEntry.value(oxygenParams().copy(
                firmwareVersion = oxygenParams().firmwareVersion.copy(minor = 3))))
            // Saving an unrelated legacy snapshot must not erase known oxygen values.
            dao.setWatchSettingsTransactionally(original.copy(ageYears = 48))
            assertEquals(selected.copy(ageYears = 48), dao.getWatchSettings().first())
            assertEquals(enabledEntry.timestamp, dao.getEntry(KEY_BLOOD_OXYGEN_PREFERENCES)?.timestamp)
            val write = DbWrite(1u, BlobDatabase.HealthParams, 1800000000u,
                KEY_BLOOD_OXYGEN_PREFERENCES.encodeToByteArray().toUByteArray(),
                ubyteArrayOf(0u), WriteType.Write)
            assertEquals(BlobResponse.BlobStatus.Success, dao.handleWrite(write, "test-watch", oxygenParams()))
            assertEquals(false, dao.getWatchSettings().first().bloodOxygenEnabled)
            assertEquals(HRMonitoringInterval.ThirtyMin, dao.getWatchSettings().first().spo2MeasurementInterval)
            assertEquals(BlobResponse.BlobStatus.InvalidData,
                dao.handleWrite(write.copy(timestamp = 1800000001u, value = ubyteArrayOf(2u)), "test-watch", oxygenParams()))
            assertEquals(false, dao.getWatchSettings().first().bloodOxygenEnabled)
        }
    }

    @Test
    fun oxygenWireValuesAreStrictAndMatchFirmware() {
        for (enabled in listOf(false, true)) {
            val encoded = enabled.toString()
            assertEquals(encoded, decodeOxygenPreference(KEY_BLOOD_OXYGEN_PREFERENCES,
                oxygenPreferenceBytes(KEY_BLOOD_OXYGEN_PREFERENCES, encoded)))
        }
        for (interval in 0..3) {
            assertContentEquals(ubyteArrayOf(interval.toUByte()),
                oxygenPreferenceBytes(KEY_SPO2_PREFERENCES, interval.toString()))
            assertEquals(interval.toString(), decodeOxygenPreference(KEY_SPO2_PREFERENCES,
                ubyteArrayOf(interval.toUByte())))
        }
        for (invalid in listOf(ubyteArrayOf(), ubyteArrayOf(0u, 1u), ubyteArrayOf(255u))) {
            assertFails { decodeOxygenPreference(KEY_SPO2_PREFERENCES, invalid) }
            assertFails { decodeOxygenPreference(KEY_BLOOD_OXYGEN_PREFERENCES, invalid) }
        }
    }

    @Test
    fun absentSettingsAreInitializedAsOneCompleteRecord() = runTest {
        withHealthSettingsDao { dao ->
            val settings = healthSettings().copy(
                heightMm = 1830,
                weightDag = 7200,
                trackingEnabled = true,
                imperialUnits = true,
            )

            assertEquals(settings, dao.initializeIfAbsent { settings })
            assertEquals(settings, dao.getWatchSettings().first())
            HEALTH_SETTING_KEYS.forEach { key -> assertNotNull(dao.getEntry(key)) }
        }
    }

    @Test
    fun deliberatelyStoredDefaultsAreNotTreatedAsAbsent() = runTest {
        withHealthSettingsDao { dao ->
            val defaults = healthSettings()
            dao.setWatchSettings(defaults)

            assertNull(
                dao.initializeIfAbsent {
                    defaults.copy(trackingEnabled = true, ageYears = 42, imperialUnits = true)
                },
            )
            assertEquals(defaults, dao.getWatchSettings().first())
            HEALTH_SETTING_KEYS.forEach { key -> assertNotNull(dao.getEntry(key)) }
        }
    }

    @Test
    fun anyPartialPersistedRecordBlocksInitialization() = runTest {
        withHealthSettingsDao { dao ->
            dao.insertOrReplace(
                HealthSettingsEntry(
                    id = ACTIVITY_PREFERENCES,
                    value = "persisted-partial-record",
                ),
            )

            var transformed = false
            assertNull(
                dao.initializeIfAbsent {
                    transformed = true
                    healthSettings()
                },
            )
            assertFalse(transformed)
            assertNotNull(dao.getEntry(ACTIVITY_PREFERENCES))
            HEALTH_SETTING_KEYS.minus(ACTIVITY_PREFERENCES).forEach { key ->
                assertNull(dao.getEntry(key))
            }
        }
    }

    @Test
    fun deletedPersistedRecordStillBlocksInitialization() = runTest {
        withHealthSettingsDao { dao ->
            dao.insertOrReplace(
                HealthSettingsEntry(
                    id = ACTIVITY_PREFERENCES,
                    value = "persisted-deleted-record",
                ),
            )
            dao.markForDeletion(ACTIVITY_PREFERENCES)
            assertNull(dao.getEntry(ACTIVITY_PREFERENCES))

            assertNull(dao.initializeIfAbsent { healthSettings() })
            HEALTH_SETTING_KEYS.forEach { key -> assertNull(dao.getEntry(key)) }
        }
    }

    @Test
    fun completeSettingsWriteRollsBackAllRowsWhenTheLastInsertFails() = runTest {
        val directory = Files.createTempDirectory("health-settings-transaction-test-")
        val database = Room.databaseBuilder<Database>(
            name = directory.resolve("health.db").toString(),
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            val dao = database.healthSettingsDao()
            val original = healthSettings()
            dao.setWatchSettingsTransactionally(original)
            database.useWriterConnection {
                it.execSQL(
                    """
                    CREATE TRIGGER fail_final_health_setting
                    BEFORE INSERT ON HealthSettingsEntryEntity
                    WHEN NEW.id = 'heartRatePreferences'
                    BEGIN
                        SELECT RAISE(ABORT, 'test failure');
                    END
                    """.trimIndent(),
                )
            }

            assertFails {
                dao.setWatchSettingsTransactionally(
                    original.copy(
                        ageYears = 58,
                        imperialUnits = true,
                        maxHr = 177,
                        bloodOxygenEnabled = true,
                        spo2MeasurementInterval = HRMonitoringInterval.OneHour,
                    ),
                )
            }
            assertEquals(original, dao.getWatchSettings().first())
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private suspend fun withHealthSettingsDao(
        test: suspend (HealthSettingsEntryRealDao) -> Unit,
    ) {
        val directory = Files.createTempDirectory("health-settings-initialization-test-")
        val database = Room.databaseBuilder<Database>(
            name = directory.resolve("health.db").toString(),
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            test(database.healthSettingsDao())
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun healthSettings() = HealthSettings(
        heightMm = 1700,
        weightDag = 7000,
        trackingEnabled = false,
        activityInsightsEnabled = false,
        sleepInsightsEnabled = false,
        ageYears = 35,
        gender = HealthGender.Female,
        imperialUnits = false,
        hrmEnabled = true,
        hrmMeasurementInterval = HRMonitoringInterval.TenMin,
        hrmActivityTrackingEnabled = false,
        restingHr = 70,
        elevatedHr = 100,
        maxHr = 190,
        hrZone1Threshold = 130,
        hrZone2Threshold = 154,
        hrZone3Threshold = 172,
    )

    private companion object {
        const val ACTIVITY_PREFERENCES = "activityPreferences"
        val HEALTH_SETTING_KEYS = setOf(
            ACTIVITY_PREFERENCES,
            "unitsDistance",
            "hrmPreferences",
            "heartRatePreferences",
        )
    }
}

private fun oxygenParams() = ValueParams(
    platform = WatchType.BASALT,
    capabilities = setOf(ProtocolCapsFlag.SupportsHealthInsights),
    firmwareVersion = FirmwareVersion("v4.41.2", Instant.DISTANT_PAST, 4, 41, 2, null, "", false, false, false),
    libPebbleConfigFlow = LibPebbleConfigFlow(MutableStateFlow(LibPebbleConfig())),
)
