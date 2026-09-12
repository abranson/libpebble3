package io.rebble.libpebblecommon.messaging

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.dao.SendTextContactRealDao
import io.rebble.libpebblecommon.database.entity.AppPrefsEntryDao
import io.rebble.libpebblecommon.database.entity.SEND_TEXT_MAX_METHODS
import io.rebble.libpebblecommon.database.entity.SendTextCannedResponses
import io.rebble.libpebblecommon.database.entity.SendTextCannedResponsesDao
import io.rebble.libpebblecommon.database.entity.SendTextContact
import io.rebble.libpebblecommon.database.entity.SendTextMethod
import io.rebble.libpebblecommon.database.entity.SendTextMethodReference
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Buffer
import kotlin.uuid.Uuid

data class SendTextRoute(
    val accountId: String,
    val recipient: String,
    val displayRecipient: String = recipient,
    val methodId: Uuid? = null,
)

data class SendTextFavorite(
    val name: String,
    val routes: List<SendTextRoute>,
    val contactId: Uuid? = null,
)

interface SendTextConfiguration {
    suspend fun replaceSendTextConfiguration(
        favorites: List<SendTextFavorite>,
        cannedResponses: List<String>,
    )
}

enum class SendMessageResult { Sent, Unavailable, Failed }

interface SystemMessaging {
    suspend fun sendMessage(accountId: String, recipient: String, text: String): SendMessageResult
}

class UnavailableSystemMessaging : SystemMessaging {
    override suspend fun sendMessage(
        accountId: String,
        recipient: String,
        text: String,
    ): SendMessageResult = SendMessageResult.Unavailable
}

class SendTextManager(
    private val contactsDao: SendTextContactRealDao,
    private val cannedDao: SendTextCannedResponsesDao,
    private val appPrefsDao: AppPrefsEntryDao,
    private val systemMessaging: SystemMessaging,
) : SendTextConfiguration {
    private val logger = Logger.withTag("SendTextManager")
    private val configurationMutex = Mutex()

    override suspend fun replaceSendTextConfiguration(
        favorites: List<SendTextFavorite>,
        cannedResponses: List<String>,
    ) = configurationMutex.withLock {
        validateConfiguration(favorites, cannedResponses)
        val contacts = favorites.map { favorite ->
            val contactId = favorite.contactId
                ?: stableUuid("${favorite.name}.send-text-contact.libpebble3.rebble.io")
            val methods = favorite.routes.map { route ->
                SendTextMethod(
                    id = route.methodId ?: stableUuid(
                        "${route.accountId}:${route.recipient}.send-text-method.libpebble3.rebble.io"
                    ),
                    accountId = route.accountId,
                    recipient = route.recipient,
                    displayRecipient = route.displayRecipient,
                )
            }
            SendTextContact.create(contactId, favorite.name, methods)
        }
        val references = contacts.flatMap { contact ->
            contact.methods.map { method -> SendTextMethodReference(contact.contactId, method.id) }
        }
        contactsDao.replaceSnapshot(
            contacts = contacts,
            responses = SendTextCannedResponses.create(cannedResponses),
            references = references,
            cannedDao = cannedDao,
            appPrefsDao = appPrefsDao,
        )
    }

    fun handles(itemId: Uuid): Boolean = itemId == SEND_TEXT_ACTION_UUID

    suspend fun invoke(
        actionId: UByte,
        attributes: List<TimelineItem.Attribute>,
    ): TimelineActionResult = configurationMutex.withLock {
        if (actionId != 0.toUByte() || attributes.size != 2) return failedResult()
        val titleAttributes = attributes.filter { it.attributeId.get() == TimelineAttribute.Title.id }
        val senderAttributes = attributes.filter { it.attributeId.get() == TimelineAttribute.Sender.id }
        if (titleAttributes.size != 1 || senderAttributes.size != 1) return failedResult()
        val text = titleAttributes.single().strictText(MAX_TEXT_BYTES) ?: return failedResult()
        val displayedRecipient = senderAttributes.single().strictText(MAX_DISPLAY_BYTES)
            ?: return failedResult()

        val routes = contactsDao.getAll().flatMap { it.methods }
            .filter { it.displayRecipient == displayedRecipient }
        if (routes.size != 1) {
            logger.w { "Rejected Send Text action with ambiguous or unknown recipient" }
            return failedResult()
        }
        val route = routes.single()
        return when (systemMessaging.sendMessage(route.accountId, route.recipient, text)) {
            SendMessageResult.Sent -> TimelineActionResult(
                success = true,
                icon = TimelineIcon.ResultSent,
                title = "Sent!",
            )
            SendMessageResult.Unavailable, SendMessageResult.Failed -> failedResult()
        }
    }

    private fun TimelineItem.Attribute.strictText(maxBytes: Int): String? {
        val bytes = content.get().toByteArray()
        if (bytes.isEmpty() || bytes.size > maxBytes || 0.toByte() in bytes) return null
        return runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }
            .getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun validateConfiguration(
        favorites: List<SendTextFavorite>,
        cannedResponses: List<String>,
    ) {
        require(favorites.sumOf { it.routes.size } <= SEND_TEXT_MAX_METHODS)
        require(favorites.map { it.name }.toSet().size == favorites.size)
        val displays = mutableSetOf<String>()
        favorites.forEach { favorite ->
            requireValidText(favorite.name, MAX_DISPLAY_BYTES)
            require(favorite.routes.isNotEmpty())
            favorite.routes.forEach { route ->
                require(route.accountId.startsWith(TELEPATHY_ACCOUNT_PREFIX))
                requireValidText(route.accountId, MAX_ROUTE_BYTES)
                requireValidText(route.recipient, MAX_ROUTE_BYTES)
                requireValidText(route.displayRecipient, MAX_DISPLAY_BYTES)
                require(displays.add(route.displayRecipient))
            }
        }
        require(cannedResponses.size <= UByte.MAX_VALUE.toInt())
        cannedResponses.forEach { requireValidText(it, MAX_TEXT_BYTES) }
        require(cannedResponses.joinToString("\u0000").encodeToByteArray().size <= MAX_TEXT_BYTES)
    }

    private fun requireValidText(value: String, maxBytes: Int) {
        require(value.isNotBlank() && '\u0000' !in value)
        require(value.encodeToByteArray().size <= maxBytes)
    }

    private fun failedResult() = TimelineActionResult(
        success = false,
        icon = TimelineIcon.ResultFailed,
        title = "Failed",
    )

    companion object {
        val SEND_TEXT_ACTION_UUID: Uuid = Uuid.parse("0f71aaba-5814-4b5c-96e2-c9828c9734cb")
        private val UUID_DNS_NAMESPACE: Uuid =
            Uuid.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8")
        private const val TELEPATHY_ACCOUNT_PREFIX = "/org/freedesktop/Telepathy/Account/"
        private const val MAX_ROUTE_BYTES = 512
        private const val MAX_DISPLAY_BYTES = 64
        private const val MAX_TEXT_BYTES = 512

        internal fun stableUuid(name: String): Uuid {
            val digest = Buffer()
                .write(UUID_DNS_NAMESPACE.toByteArray())
                .write(name.encodeToByteArray())
                .sha1()
                .toByteArray()
            digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
            digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
            return Uuid.fromByteArray(digest.copyOf(16))
        }
    }
}
