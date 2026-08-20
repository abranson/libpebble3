package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Query
import io.rebble.libpebblecommon.database.entity.TimelineReminder
import io.rebble.libpebblecommon.database.entity.TimelineReminderDao
import io.rebble.libpebblecommon.database.entity.TimelineReminderEntity
import kotlin.uuid.Uuid

@Dao
interface TimelineReminderRealDao : TimelineReminderDao {
    /** See [TimelinePinRealDao.deleteStaleRecords]. */
    @Query("""
        DELETE FROM TimelineReminderEntity AS e
        WHERE (e.deleted = 1 OR e.timestamp < :oldestSupportedTimestampMs)
            AND NOT EXISTS (SELECT 1 FROM TimelineReminderSyncEntity s WHERE s.recordId = e.itemId)
    """)
    override suspend fun deleteStaleRecords(oldestSupportedTimestampMs: Long)

    @Query("""
        SELECT e.* FROM TimelineReminderEntity e
        WHERE e.deleted = 0 AND e.sync = 1
            AND e.timestamp > :windowStartMs AND e.timestamp < :windowEndMs
            AND NOT EXISTS (SELECT 1 FROM TimelineReminderSyncEntity s
                WHERE s.recordId = e.itemId AND s.transport = :transport
                    AND s.watchSynchHashcode = e.recordHashcode)
    """)
    fun dirtyRecordsForWatchInsertInWindow(
        transport: String, windowStartMs: Long, windowEndMs: Long,
    ): kotlinx.coroutines.flow.Flow<List<TimelineReminderEntity>>

    @Query("""
        SELECT e.* FROM TimelineReminderEntity e
        WHERE (e.deleted = 1 OR e.timestamp <= :windowStartMs OR e.timestamp >= :windowEndMs)
            AND EXISTS (SELECT 1 FROM TimelineReminderSyncEntity s
                WHERE s.recordId = e.itemId AND s.transport = :transport)
    """)
    fun dirtyRecordsForWatchDeleteOutsideWindow(
        transport: String, windowStartMs: Long, windowEndMs: Long,
    ): kotlinx.coroutines.flow.Flow<List<TimelineReminderEntity>>

    @Query("SELECT * FROM TimelineReminderEntity WHERE parentId = :parentId AND deleted = 0")
    suspend fun getRemindersForPin(parentId: Uuid): List<TimelineReminder>

    @Query("UPDATE TimelineReminderEntity SET deleted = 1 WHERE parentId = :parentId")
    suspend fun markForDeletionByParentId(parentId: Uuid)

    @Query("UPDATE TimelineReminderEntity SET deleted = 1 WHERE parentId IN (:parentIds)")
    suspend fun markForDeletionByParentIds(parentIds: List<Uuid>)
}
