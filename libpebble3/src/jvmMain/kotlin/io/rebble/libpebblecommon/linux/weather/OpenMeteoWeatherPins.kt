/*
 * SPDX-License-Identifier: Apache-2.0
 */
package io.rebble.libpebblecommon.linux.weather

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import io.rebble.libpebblecommon.SystemAppIDs.WEATHER_APP_UUID
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.entity.buildTimelinePin
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import kotlin.time.Instant
import kotlin.uuid.Uuid

private object WeatherDependencies : LibPebbleKoinComponent

/** Reconcile the six system Weather pin slots used by the Pebble mobile app. */
class OpenMeteoWeatherPins internal constructor(private val database: Database) {
    constructor() : this(WeatherDependencies.getKoin().get<Database>())

    suspend fun replace(location: String, lastUpdated: Long, days: List<OpenMeteoPinDay>) {
        val pins = buildList {
            days.take(sunriseIds.size).forEachIndexed { index, day ->
                day.sunriseEpochSeconds?.let {
                    add(weatherPin(sunriseIds[index], it, day, location, lastUpdated, sunrise = true))
                }
                day.sunsetEpochSeconds?.let {
                    add(weatherPin(sunsetIds[index], it, day, location, lastUpdated, sunrise = false))
                }
            }
        }
        val missing = (sunriseIds + sunsetIds) - pins.map { it.itemId }.toSet()
        val dao = database.timelinePinDao()
        database.useWriterConnection { writer ->
            writer.immediateTransaction {
                dao.markAllForDeletion(missing)
                dao.insertOrReplace(pins)
            }
        }
    }

    private fun weatherPin(
        id: Uuid,
        time: Long,
        day: OpenMeteoPinDay,
        locationName: String,
        updated: Long,
        sunrise: Boolean,
    ) = buildTimelinePin(WEATHER_APP_UUID, Instant.fromEpochSeconds(time)) {
        itemID = id
        layout = TimelineItem.Layout.WeatherPin
        val icon = if (sunrise) TimelineIcon.Sunrise else TimelineIcon.Sunset
        attributes {
            title { if (sunrise) "Sunrise" else "Sunset" }
            uByte(TimelineAttribute.WeatherPinKind) { if (sunrise) 1u else 2u }
            subtitle { "${day.high}°/${day.low}°" }
            body { openMeteoWeatherText(day.weatherCode) }
            location { locationName }
            tinyIcon { icon }
            largeIcon { icon }
            lastUpdated { Instant.fromEpochSeconds(updated) }
        }
        actions {
            action(TimelineItem.Action.Type.OpenWatchapp) { attributes { title { "More" } } }
        }
    }

    private companion object {
        val sunriseIds = listOf(
            "b0beeaa7-a6cf-46c6-8bc4-2700ed3fa952",
            "2834d0c8-85bc-45bd-b2b7-d0f026098d28",
            "3233984e-2dc9-4469-8a9a-46f91d81b7fc",
        ).map(Uuid::parse)
        val sunsetIds = listOf(
            "ef6abfda-5312-4649-a10a-427235059479",
            "2f363ad4-bc5d-455a-a445-4e00ec07417e",
            "fd97c081-9970-436b-aa87-9c55232bce35",
        ).map(Uuid::parse)
    }
}
