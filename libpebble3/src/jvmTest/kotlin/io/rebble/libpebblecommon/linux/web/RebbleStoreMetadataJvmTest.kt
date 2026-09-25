package io.rebble.libpebblecommon.linux.web

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.entity.LockerEntryEntity
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.disk.pbw.toLockerEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class RebbleStoreMetadataJvmTest {
    @Test
    fun storeImportKeepsIdentityAndArtworkWithoutChangingPbwVariants() {
        withPbw { pbw ->
            val now = Instant.fromEpochSeconds(0)
            val original = pbw.toLockerEntry(now, 7)
            val metadata = metadata(original.id)
            val imported = pbw.toLockerEntry(now, 7, metadata)
            assertEquals("store-id", imported.appstoreData?.storeId)
            assertEquals("pbw-url", imported.appstoreData?.pbwLink)
            assertTrue(imported.sideloaded)
            assertEquals(original.version, imported.version)
            assertEquals(original.recordHashCode(), imported.recordHashCode())
            assertEquals(original.platforms.map { it.name }, imported.platforms.map { it.name })
            val aplite = imported.platforms.first { it.name == "aplite" }
            assertEquals("preview-url", aplite.screenshotImageUrl)
            assertEquals("list-url", aplite.listImageUrl)
            assertNull(aplite.iconImageUrl)
            assertEquals(original.platforms.first { it.name == "aplite" }.pbwIconResourceId,
                aplite.pbwIconResourceId)
            assertFailsWith<IllegalArgumentException> {
                pbw.toLockerEntry(now, 7, metadata.copy(uuid = Uuid.random()))
            }
        }
    }

    @Test
    fun uuidSearchOnlyAcceptsAnExactUuidMatch() {
        val uuid = Uuid.random()
        assertEquals("matching", findRebbleStoreId("""{"hits":[
            {"id":"wrong","uuid":"${Uuid.random()}"},
            {"id":"invalid","uuid":"bad"},
            {"id":"matching","uuid":"${uuid.toString().uppercase()}"}
        ]}""", uuid))
        assertNull(findRebbleStoreId("""{"hits":[{"id":"wrong","uuid":"${Uuid.random()}"}]}""", uuid))
    }

    @Test
    fun associationPreservesLocalStateAndCannotReviveOrOverwriteApps() = runTest {
        val directory = Files.createTempDirectory("store-association-")
        val database = Room.databaseBuilder<Database>(directory.resolve("locker.db").toString())
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            var original: io.rebble.libpebblecommon.database.entity.LockerEntry? = null
            withPbw { original = it.toLockerEntry(Instant.fromEpochSeconds(0), 7) }
            val entry = assertNotNull(original).copy(active = true, grantedPermissions = listOf("test"))
            val dao = database.lockerEntryDao()
            val stored = LockerEntryEntity(entry.recordHashCode(), false, entry, sync = false)
            dao.insertOrReplace(stored)
            assertTrue(dao.associateStoreMetadata(metadata(entry.id)))
            val updated = assertNotNull(dao.getStoredEntry(entry.id))
            assertFalse(updated.sync)
            assertEquals(stored.recordHashcode, updated.recordHashcode)
            assertEquals(entry, updated.record.copy(
                appstoreData = entry.appstoreData, platforms = entry.platforms,
            ))
            assertEquals("store-id", updated.record.appstoreData?.storeId)
            assertFalse(dao.associateStoreMetadata(metadata(entry.id)))

            dao.markForDeletion(entry.id)
            assertFalse(dao.associateStoreMetadata(metadata(entry.id)))
            assertNull(dao.getEntry(entry.id))
            dao.insertOrReplace(entry.copy(sideloaded = false))
            assertFalse(dao.associateStoreMetadata(metadata(entry.id)))
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun metadata(uuid: Uuid) = Json { ignoreUnknownKeys = true }.decodeFromString<AppRecord>("""
        {
          "uuid":"$uuid", "hearts":5, "developer_id":"author",
          "latest_release":{"pbw_file":"pbw-url","version":"99.0"},
          "hardware_platforms":[{
            "name":"aplite", "sdk_version":"99.0", "pebble_process_info_flags":999,
            "description":"description",
            "images":{"screenshot":"preview-url","list":"list-url","icon":""}
          },{
            "name":"emery", "sdk_version":"99.0", "pebble_process_info_flags":999,
            "description":"not shipped in this PBW",
            "images":{"screenshot":"other-preview","list":"","icon":""}
          }]
        }
    """.trimIndent()).storeMetadata("store-id")

    private fun withPbw(block: (PbwApp) -> Unit) {
        val file = Files.createTempFile("store-metadata-", ".pbw").toFile()
        try {
            javaClass.classLoader.getResourceAsStream("variant_root_and_chalk.pbw")!!.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            block(PbwApp(Path(file.absolutePath)))
        } finally {
            file.delete()
        }
    }
}
