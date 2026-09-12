package io.rebble.libpebblecommon.messaging

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.entity.SEND_TEXT_APP_CONFIG_KEY
import io.rebble.libpebblecommon.database.entity.SEND_TEXT_CANNED_SOURCE
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import io.rebble.libpebblecommon.util.TimelineAttributeFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SendTextManagerJvmTest {
    @Test
    fun snapshotProjectsFavoritesAndAuthenticatesExactRecipient() = runTest {
        val directory = Files.createTempDirectory("send-text-test-")
        val database = Room.databaseBuilder<Database>(
            name = directory.resolve("send-text.db").toString(),
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            val messaging = RecordingMessaging()
            val manager = SendTextManager(
                database.sendTextContactDao(),
                database.sendTextCannedResponsesDao(),
                database.appPrefsDao(),
                messaging,
            )
            val account = "/org/freedesktop/Telepathy/Account/ring/tel/ril_0"
            val contactId = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff")
            val methodId = Uuid.parse("10213243-5465-7687-98a9-bacbdcedfe0f")
            manager.replaceSendTextConfiguration(
                favorites = listOf(
                    SendTextFavorite(
                        "Alice",
                        listOf(SendTextRoute(account, "+358123", methodId = methodId)),
                        contactId,
                    )
                ),
                cannedResponses = listOf("Yes", "No"),
            )

            val contact = database.sendTextContactDao().getAll().single()
            assertEquals("Alice", contact.name)
            assertEquals(contactId, contact.contactId)
            assertEquals(methodId, contact.methods.single().id)
            assertEquals(account, contact.methods.single().accountId)
            assertEquals("+358123", contact.methods.single().recipient)
            assertContentEquals(contactId.toByteArray().toUByteArray(), contact.key())
            assertContentEquals(
                contactId.toByteArray().toUByteArray(),
                contact.value(valueParams()).copyOfRange(0, 16),
            )
            val canned = assertNotNull(
                database.sendTextCannedResponsesDao().getEntry(SEND_TEXT_CANNED_SOURCE)
            )
            assertEquals(SEND_TEXT_CANNED_SOURCE, canned.source)
            assertContentEquals(
                ubyteArrayOf(0u, 0u, 0u, 0u, 0u, 1u),
                canned.value(valueParams()).copyOfRange(0, 6),
            )
            val prefs = assertNotNull(database.appPrefsDao().getEntry(SEND_TEXT_APP_CONFIG_KEY))
            val prefsBytes = assertNotNull(prefs.value(valueParams()))
            assertEquals(331, prefsBytes.size)
            assertEquals(1u, prefsBytes.first())
            assertContentEquals(
                contactId.toByteArray().toUByteArray(), prefsBytes.copyOfRange(1, 17)
            )
            assertContentEquals(
                methodId.toByteArray().toUByteArray(), prefsBytes.copyOfRange(17, 33)
            )
            assertEquals(1u, prefsBytes[33])
            assertTrue(prefsBytes.copyOfRange(34, prefsBytes.size).all { it == 0.toUByte() })

            assertTrue(manager.handles(SendTextManager.SEND_TEXT_ACTION_UUID))
            val success = manager.invoke(
                0u,
                listOf(
                    TimelineAttributeFactory.createTextAttribute(
                        TimelineAttribute.Title, "On my way"
                    ),
                    TimelineAttributeFactory.createTextAttribute(
                        TimelineAttribute.Sender, "+358123"
                    ),
                ),
            )
            assertTrue(success.success)
            assertEquals(Triple(account, "+358123", "On my way"), messaging.last)

            messaging.last = null
            val rejected = manager.invoke(
                0u,
                listOf(
                    TimelineAttributeFactory.createTextAttribute(TimelineAttribute.Title, "Hi"),
                    TimelineAttributeFactory.createTextAttribute(
                        TimelineAttribute.Sender, "+358999"
                    ),
                ),
            )
            assertFalse(rejected.success)
            assertEquals(null, messaging.last)
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsAmbiguousOrOversizedConfiguration() = runTest {
        val directory = Files.createTempDirectory("send-text-limits-")
        val database = Room.databaseBuilder<Database>(
            name = directory.resolve("send-text.db").toString(),
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            val manager = SendTextManager(
                database.sendTextContactDao(),
                database.sendTextCannedResponsesDao(),
                database.appPrefsDao(),
                RecordingMessaging(),
            )
            val account = "/org/freedesktop/Telepathy/Account/ring/tel/ril_0"
            val ambiguous = listOf(
                SendTextFavorite("A", listOf(SendTextRoute(account, "same"))),
                SendTextFavorite("B", listOf(SendTextRoute(account, "same"))),
            )
            assertTrue(runCatching {
                manager.replaceSendTextConfiguration(ambiguous, emptyList())
            }.isFailure)
            assertTrue(runCatching {
                manager.replaceSendTextConfiguration(emptyList(), listOf("x".repeat(513)))
            }.isFailure)
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun valueParams() = io.rebble.libpebblecommon.database.dao.ValueParams(
        platform = io.rebble.libpebblecommon.metadata.WatchType.APLITE,
        capabilities = emptySet(),
        firmwareVersion = io.rebble.libpebblecommon.services.FirmwareVersion(
            stringVersion = "v4.4.0",
            timestamp = kotlin.time.Instant.DISTANT_PAST,
            major = 4,
            minor = 4,
            patch = 0,
            suffix = null,
            gitHash = "",
            isRecovery = false,
            isDualSlot = false,
            isSlot0 = false,
        ),
        libPebbleConfigFlow = LibPebbleConfigFlow(MutableStateFlow(LibPebbleConfig())),
    )

    private class RecordingMessaging : SystemMessaging {
        var last: Triple<String, String, String>? = null

        override suspend fun sendMessage(
            accountId: String,
            recipient: String,
            text: String,
        ): SendMessageResult {
            last = Triple(accountId, recipient, text)
            return SendMessageResult.Sent
        }
    }
}
