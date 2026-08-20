package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import io.rebble.libpebblecommon.database.entity.AppPrefsEntryDao
import io.rebble.libpebblecommon.database.entity.WeatherAppEntry
import io.rebble.libpebblecommon.database.entity.WeatherAppEntryDao
import kotlinx.coroutines.flow.Flow
import io.rebble.libpebblecommon.database.entity.WeatherPrefsValue
import io.rebble.libpebblecommon.database.entity.setWeatherSettings
import kotlin.uuid.Uuid

@Dao
interface WeatherAppRealDao : WeatherAppEntryDao {
    @Query("SELECT * FROM WeatherAppEntryEntity WHERE deleted = 0")
    suspend fun getAll(): List<WeatherAppEntry>

    @Query("SELECT * FROM WeatherAppEntryEntity WHERE deleted = 0")
    fun getAllFlow(): Flow<List<WeatherAppEntry>>

    @Transaction
    suspend fun replaceSnapshot(
        entries: List<WeatherAppEntry>,
        orderedKeys: List<Uuid>,
        appPrefsEntryDao: AppPrefsEntryDao,
    ) {
        val retained = orderedKeys.toHashSet()
        val removed = getAll().map { it.key }.filterNot(retained::contains)
        if (removed.isNotEmpty()) {
            markAllForDeletion(removed)
        }
        insertOrReplace(entries)
        appPrefsEntryDao.setWeatherSettings(WeatherPrefsValue(orderedKeys))
    }
}
