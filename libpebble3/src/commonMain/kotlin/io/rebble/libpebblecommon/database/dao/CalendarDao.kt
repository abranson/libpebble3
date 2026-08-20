package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import io.rebble.libpebblecommon.database.entity.TimelinePin
import io.rebble.libpebblecommon.database.entity.TimelineReminder
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface CalendarDao {
//    @Insert(onConflict = OnConflictStrategy.REPLACE)
//    suspend fun insertOrReplaceCalendars(calendars: List<CalendarEntity>)
//
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(calendar: CalendarEntity)
//
    @Query("SELECT * FROM CalendarEntity")
    suspend fun getAll(): List<CalendarEntity>
//
//    @Query("DELETE FROM CalendarEntity")
//    suspend fun deleteAll()
//
    @Update
    suspend fun update(calendar: CalendarEntity)
//
    @Delete
    suspend fun delete(calendar: CalendarEntity)

    @Query("UPDATE CalendarEntity SET enabled = :enabled WHERE id = :calendarId")
    suspend fun setEnabled(calendarId: Int, enabled: Boolean)

    @Query("SELECT * FROM CalendarEntity")
    fun getFlow(): Flow<List<CalendarEntity>>

    /** Apply one complete phone-calendar projection without exposing a partially updated DB. */
    @Transaction
    suspend fun applyProjection(
        calendarsToUpdate: List<CalendarEntity>,
        calendarsToDelete: List<CalendarEntity>,
        calendarsToInsert: List<CalendarEntity>,
        pinsToInsert: List<TimelinePin>,
        pinIdsToDelete: List<Uuid>,
        remindersToInsert: List<TimelineReminder>,
        reminderIdsToDelete: List<Uuid>,
        timelinePinDao: TimelinePinRealDao,
        timelineReminderDao: TimelineReminderRealDao,
    ) {
        calendarsToUpdate.forEach { update(it) }
        calendarsToDelete.forEach { delete(it) }
        calendarsToInsert.forEach { insertOrReplace(it) }
        if (pinsToInsert.isNotEmpty()) {
            timelinePinDao.insertOrReplace(pinsToInsert)
        }
        if (pinIdsToDelete.isNotEmpty()) {
            timelinePinDao.markAllForDeletionWithReminders(
                pinIdsToDelete,
                timelineReminderDao,
            )
        }
        if (remindersToInsert.isNotEmpty()) {
            timelineReminderDao.insertOrReplace(remindersToInsert)
        }
        if (reminderIdsToDelete.isNotEmpty()) {
            timelineReminderDao.markAllForDeletion(reminderIdsToDelete)
        }
    }
//
//    @Query("SELECT * FROM CalendarEntity WHERE id = :calendarId")
//    suspend fun get(calendarId: Long): CalendarEntity?
}
