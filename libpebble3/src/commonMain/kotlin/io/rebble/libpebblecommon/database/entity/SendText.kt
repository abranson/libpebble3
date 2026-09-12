package io.rebble.libpebblecommon.database.entity

import coredev.BlobDatabase
import coredev.GenerateRoomEntity
import io.rebble.libpebblecommon.database.dao.BlobDbItem
import io.rebble.libpebblecommon.database.dao.ValueParams
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.structmapper.SUUID
import io.rebble.libpebblecommon.structmapper.StructMapper
import io.rebble.libpebblecommon.util.TimelineAttributeFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

private val sendTextJson = Json { ignoreUnknownKeys = true }

@Serializable
data class SendTextMethod(
    val id: Uuid,
    val accountId: String,
    val recipient: String,
    val displayRecipient: String,
)

@GenerateRoomEntity(
    primaryKey = "contactId",
    databaseId = BlobDatabase.Contacts,
    windowBeforeSecs = -1,
    windowAfterSecs = -1,
    onlyInsertAfter = false,
    sendDeletions = true,
)
data class SendTextContact(
    val contactId: Uuid,
    val name: String,
    val methodsJson: String,
) : BlobDbItem {
    val methods: List<SendTextMethod>
        get() = sendTextJson.decodeFromString(methodsJson)

    override fun key(): UByteArray = uuidBytes(contactId)

    override fun value(params: ValueParams): UByteArray {
        val title = TimelineAttributeFactory.createTextAttribute(TimelineAttribute.Title, name)
        val methodBytes = methods.flatMap { method ->
            val recipient = TimelineItem.Attribute(
                SEND_TEXT_RECIPIENT_ATTRIBUTE_ID,
                method.displayRecipient.encodeToByteArray().toUByteArray(),
            )
            (uuidBytes(method.id) + ubyteArrayOf(SEND_TEXT_METHOD_TYPE, 1u) + recipient.toBytes())
                .asIterable()
        }.toUByteArray()
        return uuidBytes(contactId) + ubyteArrayOf(0u, 0u, 0u, 0u, 1u, methods.size.toUByte()) +
            title.toBytes() + methodBytes
    }

    override fun recordHashCode(): Int = hashCode()

    companion object {
        fun create(contactId: Uuid, name: String, methods: List<SendTextMethod>) =
            SendTextContact(contactId, name, sendTextJson.encodeToString(methods))
    }
}

@GenerateRoomEntity(
    primaryKey = "source",
    databaseId = BlobDatabase.CannedResponses,
    windowBeforeSecs = -1,
    windowAfterSecs = -1,
    onlyInsertAfter = false,
    sendDeletions = true,
)
data class SendTextCannedResponses(
    val source: String,
    val responsesJson: String,
) : BlobDbItem {
    val responses: List<String>
        get() = sendTextJson.decodeFromString(responsesJson)

    override fun key(): UByteArray = source.encodeToByteArray().toUByteArray()

    override fun value(params: ValueParams): UByteArray =
        ubyteArrayOf(0u, 0u, 0u, 0u, 0u, 1u) + TimelineItem.Action(
            actionID = 0u,
            type = TimelineItem.Action.Type.Response,
            attributes = listOf(
                TimelineAttributeFactory.createStringListAttribute(
                    TimelineAttribute.CannedResponse,
                    responses,
                )
            ),
        ).toBytes()

    override fun recordHashCode(): Int = hashCode()

    companion object {
        fun create(responses: List<String>) = SendTextCannedResponses(
            source = SEND_TEXT_CANNED_SOURCE,
            responsesJson = sendTextJson.encodeToString(responses),
        )
    }
}

@Serializable
data class SendTextMethodReference(
    val contactId: Uuid,
    val methodId: Uuid,
)

@Serializable
data class SendTextPrefsValue(val methods: List<SendTextMethodReference>) {
    fun asBytes(): UByteArray {
        val configured = methods.flatMap { reference ->
            (uuidBytes(reference.contactId) + uuidBytes(reference.methodId) + SEND_TEXT_METHOD_TYPE)
                .asIterable()
        }.toUByteArray()
        val padding = UByteArray((SEND_TEXT_MAX_METHODS - methods.size) * SEND_TEXT_METHOD_RECORD_SIZE)
        return ubyteArrayOf(methods.size.toUByte()) + configured + padding
    }

    fun encode(): String = sendTextJson.encodeToString(this)

    companion object {
        fun decode(value: String?): SendTextPrefsValue? = value?.let {
            runCatching { sendTextJson.decodeFromString<SendTextPrefsValue>(it) }.getOrNull()
        }
    }
}

internal fun uuidBytes(uuid: Uuid): UByteArray = SUUID(StructMapper(), uuid).toBytes()

const val SEND_TEXT_CANNED_SOURCE = "com.pebble.sendText"
const val SEND_TEXT_APP_CONFIG_KEY = "sendTextApp"
const val SEND_TEXT_MAX_METHODS = 10
private const val SEND_TEXT_METHOD_RECORD_SIZE = 33
private const val SEND_TEXT_METHOD_TYPE: UByte = 1u
private const val SEND_TEXT_RECIPIENT_ATTRIBUTE_ID: UByte = 0x27u
