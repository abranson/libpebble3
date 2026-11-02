package io.rebble.libpebblecommon.database.dao

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.hasUsableSpo2
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity

private val logger = Logger.withTag("HealthDaoExt")

/**
 * Inserts health data with priority based on step count. If data already exists for a timestamp,
 * keeps the movement record with more steps while merging SpO2 independently.
 */
suspend fun HealthDao.insertHealthDataWithPriority(data: List<HealthDataEntity>) {
    var inserted = 0
    var skipped = 0
    var replaced = 0

    data.forEach { incoming ->
        val existing = getDataAtTimestamp(incoming.timestamp)
        val newData = if (existing == null) incoming else mergeHealthData(existing, incoming)
        if (existing == null) {
            insertHealthData(listOf(newData))
            inserted++
        } else if (newData.steps > existing.steps) {
            logger.d {
                "Replacing data at timestamp ${newData.timestamp}: ${existing.steps} steps -> ${newData.steps} steps (gained ${newData.steps - existing.steps} steps)"
            }
            insertHealthData(listOf(newData))
            replaced++
        } else if (newData != existing) {
            insertHealthData(listOf(newData))
            replaced++
        } else if (incoming.steps < existing.steps) {
            logger.d {
                "Skipping data at timestamp ${newData.timestamp}: existing ${existing.steps} steps > new ${newData.steps} steps"
            }
            skipped++
        } else {
            logger.d {
                "Skipping duplicate data at timestamp ${newData.timestamp}: both have ${newData.steps} steps"
            }
            skipped++
        }
    }

    val summary = buildString {
        append("Health data insert complete: ")
        if (inserted > 0) append("$inserted new, ")
        if (replaced > 0) append("$replaced replaced (higher steps or SpO2), ")
        if (skipped > 0) append("$skipped skipped (lower/equal steps)")
    }
    logger.d { summary }
}

/**
 * Inserts overlay data (sleep, activities) while preventing duplicates.
 * An overlay entry is considered duplicate if it has the same startTime and type.
 * When duplicates exist, keeps the entry with the longer duration to prevent data loss.
 */
suspend fun HealthDao.insertOverlayDataWithDeduplication(data: List<OverlayDataEntity>) {
    if (data.isEmpty()) return

    var inserted = 0
    var skipped = 0
    var replaced = 0

    data.forEach { newData ->
        val existing = getOverlayAtStartTimeAndType(newData.startTime, newData.type)
        if (existing == null) {
            insertOverlayData(listOf(newData))
            inserted++
        } else if (newData.duration > existing.duration) {
            logger.d {
                "Replacing overlay at startTime ${newData.startTime}, type ${newData.type}: ${existing.duration}s -> ${newData.duration}s (gained ${newData.duration - existing.duration}s)"
            }
            insertOverlayData(listOf(newData))
            replaced++
        } else if (newData.duration < existing.duration) {
            logger.d {
                "Skipping overlay at startTime ${newData.startTime}, type ${newData.type}: existing ${existing.duration}s > new ${newData.duration}s"
            }
            skipped++
        } else {
            // Same duration - skip as duplicate
            skipped++
        }
    }

    val summary = buildString {
        append("Overlay data insert complete: ")
        if (inserted > 0) append("$inserted new, ")
        if (replaced > 0) append("$replaced replaced (longer duration), ")
        if (skipped > 0) append("$skipped skipped (shorter/equal duration)")
    }
    logger.d { summary }
}

internal fun mergeHealthData(existing: HealthDataEntity, incoming: HealthDataEntity): HealthDataEntity {
    val movement = if (incoming.steps > existing.steps) incoming else existing
    // Never erase a stored measurement with an empty extension or a poorer repeat.
    // Retain raw nonzero values even when they are unsuitable for display.
    val oxygen = when {
        incoming.hasUsableSpo2() && !existing.hasUsableSpo2() -> incoming
        existing.hasUsableSpo2() && !incoming.hasUsableSpo2() -> existing
        existing.spo2Percent == 0 && incoming.spo2Percent != 0 -> incoming
        incoming.spo2Percent != 0 && incoming.spo2Quality > existing.spo2Quality -> incoming
        else -> existing
    }
    return movement.copy(spo2Percent = oxygen.spo2Percent, spo2Quality = oxygen.spo2Quality)
}
