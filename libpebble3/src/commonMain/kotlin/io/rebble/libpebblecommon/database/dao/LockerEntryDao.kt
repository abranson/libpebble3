package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryDao
import io.rebble.libpebblecommon.locker.AppType
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface LockerEntryRealDao : LockerEntryDao {
    @Query("""
        SELECT * FROM LockerEntryEntity
        WHERE deleted = 0
        AND type = :type
        AND (:searchQuery IS NULL OR title LIKE '%' || :searchQuery || '%' OR developerName LIKE '%' || :searchQuery || '%')
        ORDER BY orderIndex ASC, title ASC
        LIMIT :limit
    """)
    fun getAllFlow(type: String, searchQuery: String?, limit: Int): Flow<List<LockerEntry>>

    @Query("""
        SELECT id FROM LockerEntryEntity
        WHERE deleted = 0
    """)
    fun getAllUuidsFlow(): Flow<List<Uuid>>

    @Query("""
        SELECT id FROM LockerEntryEntity
        WHERE deleted = 0
        AND type = :type
        ORDER BY orderIndex ASC
        LIMIT :limit
    """)
    fun getAppOrderFlow(type: String, limit: Int): Flow<List<Uuid>>

    data class DbAppBasicProperties(
        val id: Uuid,
        val title: String,
        val type: String,
        val developerName: String,
        val orderIndex: Int,
        val active: Boolean,
        val grantedPermissions: List<String>?,
        val sideloaded: Boolean,
        val systemApp: Boolean,
    )

    @Query("""
        SELECT * FROM LockerEntryEntity WHERE deleted = 0
    """)
    @RewriteQueriesToDropUnusedColumns
    fun getAllBasicInfoFlow(): Flow<List<DbAppBasicProperties>>

    @Query("""
        SELECT * FROM LockerEntryEntity WHERE deleted = 0
    """)
    @RewriteQueriesToDropUnusedColumns
    suspend fun getAllBasicInfo(): List<DbAppBasicProperties>

    @Query("""
        SELECT * FROM LockerEntryEntity WHERE deleted = 0
    """)
    suspend fun getAll(): List<LockerEntry>

    @Transaction
    suspend fun insertOrReplaceAndOrder(entry: LockerEntry, syncLimit: Int) {
        insertOrReplace(entry)
        updateOrder(entry.type)
        updateSync(syncLimit)
    }

    @Transaction
    suspend fun insertOrReplaceAndOrder(entries: List<LockerEntry>, syncLimit: Int) {
        insertOrReplace(entries)
        AppType.entries.forEach {
            updateOrder(it.code)
        }
        updateSync(syncLimit)
    }

    /**
     * Apply one remote-locker snapshot as a single database commit.  The locker rows and any
     * timeline children owned by removed applications must never describe different snapshots,
     * including when the caller is cancelled or a DAO operation fails partway through.
     */
    @Transaction
    suspend fun applyRemoteSnapshot(
        entries: List<LockerEntry>,
        removedIds: List<Uuid>,
        syncLimit: Int,
        timelinePinDao: TimelinePinRealDao,
        timelineReminderDao: TimelineReminderRealDao,
    ) {
        insertOrReplaceAndOrder(entries, syncLimit)
        markAllForDeletion(removedIds)
        timelinePinDao.markAllForDeletionByParentIdsWithReminders(
            removedIds,
            timelineReminderDao,
        )
    }

    /**
     * Retire data that belongs to the signed-out account without touching local applications.
     *
     * Include already tombstoned rows when finding account data: a prior locker update may have
     * hidden the application while leaving its per-app timeline token behind.
     */
    @Transaction
    suspend fun retireRemoteAccountData(
        timelinePinDao: TimelinePinRealDao,
        timelineReminderDao: TimelineReminderRealDao,
    ) {
        val remoteIds = getRemoteAccountEntryIds()
        if (remoteIds.isNotEmpty()) {
            markAllForDeletion(remoteIds)
            timelinePinDao.markAllForDeletionByParentIdsWithReminders(
                remoteIds,
                timelineReminderDao,
            )
        }
        clearAllUserTokens()
    }

    @Query("""
        SELECT id FROM LockerEntryEntity
        WHERE systemApp = 0
        AND sideloaded = 0
    """)
    suspend fun getRemoteAccountEntryIds(): List<Uuid>

    @Query("UPDATE LockerEntryEntity SET userToken = NULL")
    suspend fun clearAllUserTokens()

    /** Visible to database regression tests so they can verify tokens are scrubbed from tombstones. */
    @Query("SELECT COUNT(*) FROM LockerEntryEntity WHERE userToken IS NOT NULL")
    suspend fun countUserTokensIncludingDeleted(): Int

    /** Mark one application and its timeline children for deletion atomically. */
    @Transaction
    suspend fun markForDeletionWithTimeline(
        id: Uuid,
        timelinePinDao: TimelinePinRealDao,
        timelineReminderDao: TimelineReminderRealDao,
    ) {
        markForDeletion(id)
        timelinePinDao.markAllForDeletionByParentIdsWithReminders(
            listOf(id),
            timelineReminderDao,
        )
    }

    @Transaction
    suspend fun setOrder(id: Uuid, orderIndex: Int, syncLimit: Int) {
        val entry = getEntry(id) ?: return
        val oldOrder = entry.orderIndex
        if (oldOrder > orderIndex) {
            incrementOrderIndexes(orderIndex, oldOrder, entry.type)
        } else {
            decrementOrderIndexes(oldOrder, orderIndex, entry.type)
        }
        updateOrder(id, orderIndex)
        updateOrder(entry.type)
        updateSync(syncLimit)
    }

    /**
     * Atomically reorder a validated installed subset while leaving account-only and
     * watch-incompatible entries in their existing slots.
     */
    @Transaction
    suspend fun setOrder(orderedIds: List<Uuid>, syncLimit: Int): Boolean {
        if (orderedIds.distinct().size != orderedIds.size) return false
        val initial = getAll().associateBy { it.id }
        if (orderedIds.any { it !in initial }) return false
        val affectedTypes = orderedIds.mapTo(linkedSetOf()) { initial.getValue(it).type }

        // Repair any pre-existing duplicate/gapped ranks before retaining the selected slots.
        affectedTypes.forEach { updateOrder(it) }
        val normalized = getAll().associateBy { it.id }
        affectedTypes.forEach { type ->
            val requested = orderedIds.filter { normalized.getValue(it).type == type }
            val slots = requested.map { normalized.getValue(it).orderIndex }.sorted()
            requested.zip(slots).forEach { (id, orderIndex) -> updateOrder(id, orderIndex) }
        }
        updateSync(syncLimit)
        return true
    }

    @Query("""
        UPDATE LockerEntryEntity
        SET orderIndex = :orderIndex
        WHERE id = :id
    """)
    suspend fun updateOrder(id: Uuid, orderIndex: Int)

    @Query("""
        UPDATE LockerEntryEntity
        SET orderIndex = orderIndex + 1
        WHERE orderIndex BETWEEN :fromIndex AND :toIndex AND type = :type;
    """)
    suspend fun incrementOrderIndexes(fromIndex: Int, toIndex: Int, type: String)

    @Query("""
        UPDATE LockerEntryEntity
        SET orderIndex = orderIndex - 1
        WHERE orderIndex BETWEEN :fromIndex AND :toIndex AND type = :type;
    """)
    suspend fun decrementOrderIndexes(fromIndex: Int, toIndex: Int, type: String)


    @Query("""
        WITH Ordered AS (
            SELECT
                id,
                ROW_NUMBER() OVER (ORDER BY orderIndex ASC, title ASC) - 1 AS new_order
            FROM LockerEntryEntity
            WHERE deleted = 0
            AND type = :type
        )
        UPDATE LockerEntryEntity
        SET orderIndex = (
            SELECT new_order
            FROM Ordered
            WHERE Ordered.id = LockerEntryEntity.id
        )
        WHERE LockerEntryEntity.id IN (SELECT id FROM Ordered)
        AND orderIndex IS NOT (
            SELECT new_order
            FROM Ordered
            WHERE Ordered.id = LockerEntryEntity.id
        )
    """)
    suspend fun updateOrder(type: String)

    @Query("""
        UPDATE LockerEntryEntity
        SET sync = CASE
            WHEN systemApp = 1 THEN 0
            WHEN orderIndex < :syncLimit THEN 1
            ELSE 0
        END
    """)
    suspend fun updateSync(syncLimit: Int)

    @Query("""
        UPDATE LockerEntryEntity
        SET active = 1
        WHERE id = :uuid
        AND active != 1
    """)
    suspend fun _setActiveForUuid(uuid: String)

    @Query("""
        UPDATE LockerEntryEntity
        SET active = 0
        WHERE id != :uuid
        AND active = 1
    """)
    suspend fun _setNonActiveExceptUuid(uuid: String)

    @Transaction
    suspend fun setActive(uuid: Uuid) {
        _setNonActiveExceptUuid(uuid.toString())
        _setActiveForUuid(uuid.toString())
    }

    @Query("""
        SELECT * FROM LockerEntryEntity
        WHERE active = 1
        AND type = 'watchface'
        LIMIT 1
    """)
    fun getActiveWatchface(): Flow<LockerEntry?>
}
