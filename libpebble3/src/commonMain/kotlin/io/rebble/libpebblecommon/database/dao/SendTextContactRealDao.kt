package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import io.rebble.libpebblecommon.database.entity.AppPrefsEntryDao
import io.rebble.libpebblecommon.database.entity.SendTextCannedResponses
import io.rebble.libpebblecommon.database.entity.SendTextCannedResponsesDao
import io.rebble.libpebblecommon.database.entity.SendTextContact
import io.rebble.libpebblecommon.database.entity.SendTextContactDao
import io.rebble.libpebblecommon.database.entity.SendTextMethodReference
import io.rebble.libpebblecommon.database.entity.SendTextPrefsValue
import io.rebble.libpebblecommon.database.entity.setSendTextSettings
import kotlin.uuid.Uuid

@Dao
interface SendTextContactRealDao : SendTextContactDao {
    @Query("SELECT * FROM SendTextContactEntity WHERE deleted = 0")
    suspend fun getAll(): List<SendTextContact>

    @Transaction
    suspend fun replaceSnapshot(
        contacts: List<SendTextContact>,
        responses: SendTextCannedResponses,
        references: List<SendTextMethodReference>,
        cannedDao: SendTextCannedResponsesDao,
        appPrefsDao: AppPrefsEntryDao,
    ) {
        val retained = contacts.mapTo(mutableSetOf()) { it.contactId }
        val removed = getAll().map { it.contactId }.filterNot(retained::contains)
        if (removed.isNotEmpty()) markAllForDeletion(removed)
        insertOrReplace(contacts)
        cannedDao.insertOrReplace(responses)
        appPrefsDao.setSendTextSettings(SendTextPrefsValue(references))
    }

    @Query("SELECT * FROM SendTextContactEntity WHERE contactId = :contactId AND deleted = 0")
    suspend fun activeContact(contactId: Uuid): SendTextContact?
}
