package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.rebble.libpebblecommon.database.entity.KnownWatchItem

@Dao
interface KnownWatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(watch: KnownWatchItem)

    /** Bulk Room inserts are transactional. Existing watch metadata must never be replaced. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(watches: List<KnownWatchItem>): List<Long>

    @Query("SELECT * FROM KnownWatchItem")
    suspend fun knownWatches(): List<KnownWatchItem>

    @Query("DELETE FROM KnownWatchItem WHERE transportIdentifier = :identifier")
    suspend fun remove(identifier: String)

    @Query("UPDATE KnownWatchItem SET nickname = :nickname WHERE transportIdentifier = :identifier")
    suspend fun setNickname(identifier: String, nickname: String?)
}
