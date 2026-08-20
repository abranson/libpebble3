package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Query
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.entity.TimelineNotificationDao
import io.rebble.libpebblecommon.database.entity.TimelineNotification
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.database.entity.TimelineNotificationEntity
import kotlin.uuid.Uuid

@Dao
interface TimelineNotificationRealDao : TimelineNotificationDao {
    /**
     * A timeline reset discards notifications which already existed on the phone, including
     * records which had not reached this watch yet.  Record their current hashes for this
     * transport so only notifications created or changed after the reset can be delivered.
     */
    @Query("""
        INSERT OR REPLACE INTO TimelineNotificationSyncEntity
            (recordId, transport, watchSynchHashcode)
        SELECT itemId, :transport, recordHashcode
        FROM TimelineNotificationEntity
        WHERE deleted = 0
    """)
    suspend fun suppressCurrentForWatch(transport: String)

    @Query("""
        SELECT NOT EXISTS (
            SELECT 1 FROM TimelineNotificationSyncEntity
            WHERE recordId = :recordId AND transport = :transport
                AND watchSynchHashcode = :recordHashcode
        )
    """)
    suspend fun isPendingForWatch(
        transport: String,
        recordId: Uuid,
        recordHashcode: Int,
    ): Boolean

    /** Notifications never receive deletions, so old entries need no watch marker. */
    @Query("""
        DELETE FROM TimelineNotificationEntity
        WHERE deleted = 1 OR timestamp < :oldestSupportedTimestampMs
    """)
    override suspend fun deleteStaleRecords(oldestSupportedTimestampMs: Long)

    @Query("""
        SELECT e.* FROM TimelineNotificationEntity e
        WHERE e.deleted = 0 AND e.sync = 1
            AND e.timestamp > :windowStartMs AND e.timestamp < :windowEndMs
            AND e.timestamp >= :notificationStartMs
            AND NOT EXISTS (SELECT 1 FROM TimelineNotificationSyncEntity s
                WHERE s.recordId = e.itemId AND s.transport = :transport
                    AND s.watchSynchHashcode = e.recordHashcode)
    """)
    fun dirtyRecordsForWatchInsertInWindow(
        transport: String,
        windowStartMs: Long,
        notificationStartMs: Long,
        windowEndMs: Long,
    ): kotlinx.coroutines.flow.Flow<List<TimelineNotificationEntity>>

    @Query("SELECT * FROM TimelineNotificationEntity WHERE parentId = :parentId AND deleted = 0")
    suspend fun getNotificationsForParent(parentId: Uuid): List<TimelineNotification>

    suspend fun markNotificationRead(itemId: Uuid) {
        val existingNotification = getEntry(itemId)
        if (existingNotification == null) {
            Logger.w { "Couldn't find notification $itemId to mark read" }
            return
        }
        insertOrReplace(
            existingNotification.copy(
                content = existingNotification.content.copy(
                    flags = existingNotification.content.flags.plus(TimelineItem.Flag.STATE_READ)
                )
            )
        )
    }
}
