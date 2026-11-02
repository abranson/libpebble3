package io.rebble.libpebblecommon.health.parsers

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.health.OverlayType
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian

private val logger = Logger.withTag("HealthDataParser")

/**
 * Parses step/movement data from the watch's health payload.
 *
 * Supports multiple firmware versions:
 * - Firmware 3.10 and below (version 5)
 * - Firmware 3.11 (version 6) - adds calorie and distance data
 * - Firmware 4.0 (version 7) - adds heart rate data
 * - Firmware 4.1 (version 8) - adds heart rate weight
 * - Firmware 4.3 (version 13) - adds heart rate zone
 * - Version 14 - adds SpO2 percent and quality; later versions may append more fields
 *
 * @param payload Raw byte array from the watch
 * @param itemSize Size of each data item in bytes
 * @return List of parsed health data entities ready for database insertion
 */
fun parseStepsData(payload: ByteArray, itemSize: UShort): List<HealthDataEntity> {
    if (payload.isEmpty() || itemSize.toInt() < 9) {
        logger.w { "Cannot parse steps data: empty payload or incomplete item header" }
        return emptyList()
    }

    val buffer = DataBuffer(payload.toUByteArray())
    val records = mutableListOf<HealthDataEntity>()

    if (payload.size % itemSize.toInt() != 0) {
        logger.w {
            "Steps payload size (${payload.size}) is not a multiple of item size ($itemSize); parsing what we can"
        }
    }

    val packetCount = payload.size / itemSize.toInt()

    for (i in 0 until packetCount) {
        val itemStart = buffer.readPosition
        buffer.setEndian(Endian.Little)

        val version = buffer.getUShort()
        val timestamp = buffer.getUInt()
        buffer.getByte() // unused
        val sampleSize = buffer.getUByte().toInt()
        val recordNum = buffer.getUByte()

        val isNewerVersion = version > VERSION_FW_4_3 && sampleSize >= VERSION_FW_4_3_SAMPLE_SIZE
        if (!SUPPORTED_STEP_VERSIONS.contains(version) && !isNewerVersion) {
            logger.w {
                "Unsupported health steps record version=$version, skipping packet $i of $packetCount"
            }
            // Skip to next packet instead of aborting entire payload
            val consumed = buffer.readPosition - itemStart
            val remaining = itemSize.toInt() - consumed
            if (remaining > 0) buffer.getBytes(remaining)
            continue
        }

        var currentTimestamp = timestamp
        val minimumSampleSize = when {
            version >= VERSION_FW_4_3 -> VERSION_FW_4_3_SAMPLE_SIZE
            version >= VERSION_FW_4_1 -> 15
            version >= VERSION_FW_4_0 -> 13
            version >= VERSION_FW_3_11 -> 12
            else -> 6
        }

        for (j in 0 until recordNum.toInt()) {
            val sampleStart = buffer.readPosition
            val itemRemaining = itemStart + itemSize.toInt() - sampleStart
            if (itemRemaining < maxOf(sampleSize, minimumSampleSize)) {
                logger.w { "Buffer exhausted during steps parsing at record $j/$recordNum in packet $i" }
                break
            }
            val steps = buffer.getUByte().toInt()
            val orientation = buffer.getUByte().toInt()
            val intensity = buffer.getUShort().toInt()
            val lightIntensity = buffer.getUByte().toInt()

            val flags = if (version >= VERSION_FW_3_10_AND_BELOW) {
                buffer.getUByte().toInt()
            } else {
                0
            }

            var restingGramCalories = 0
            var activeGramCalories = 0
            var distanceCm = 0
            var heartRate = 0
            var heartRateWeight = 0
            var heartRateZone = 0

            if (version >= VERSION_FW_3_11) {
                restingGramCalories = buffer.getUShort().toInt()
                activeGramCalories = buffer.getUShort().toInt()
                distanceCm = buffer.getUShort().toInt()
            }

            if (version >= VERSION_FW_4_0) {
                heartRate = buffer.getUByte().toInt()
            }

            if (version >= VERSION_FW_4_1) {
                heartRateWeight = buffer.getUShort().toInt()
            }

            if (version >= VERSION_FW_4_3) {
                heartRateZone = buffer.getUByte().toInt()
            }

            // A truncated extension must never consume the following minute's fields.
            val hasSpo2 = version >= 14u && sampleSize >= 18
            val spo2Percent = if (hasSpo2) buffer.getUByte().toInt() else 0
            val spo2Quality = if (hasSpo2) buffer.getUByte().toInt() else 0

            // Preserve the known prefix of future append-only formats.
            val unread = sampleSize - (buffer.readPosition - sampleStart)
            if (unread > 0) buffer.getBytes(unread)

            records.add(
                HealthDataEntity(
                    timestamp = currentTimestamp.toLong(),
                    steps = steps,
                    orientation = orientation,
                    intensity = intensity,
                    lightIntensity = lightIntensity,
                    activeMinutes = if ((flags and 2) > 0) 1 else 0,
                    restingGramCalories = restingGramCalories,
                    activeGramCalories = activeGramCalories,
                    distanceCm = distanceCm,
                    heartRate = heartRate,
                    heartRateZone = heartRateZone,
                    heartRateWeight = heartRateWeight,
                    spo2Percent = spo2Percent,
                    spo2Quality = spo2Quality
                )
            )

            currentTimestamp += 60u
        }

        val consumed = buffer.readPosition - itemStart
        val expected = itemSize.toInt()
        if (consumed < expected) {
            val skipAmount = expected - consumed
            if (buffer.remaining >= skipAmount) {
                buffer.getBytes(skipAmount)
            } else {
                logger.w { "Buffer exhausted skipping steps padding: consumed=$consumed, expected=$expected, remaining=${buffer.remaining}" }
                break
            }
        } else if (consumed > expected) {
            logger.w { "Health steps item over-read: consumed=$consumed, expected=$expected" }
        }
    }

    return records
}

/**
 * Parses overlay data (sleep, activities) from the watch's health payload.
 *
 * Overlay types include:
 * - Sleep and nap sessions (deep sleep, light sleep, naps)
 * - Walk and run activities with step counts and distance
 *
 * @param payload Raw byte array from the watch
 * @param itemSize Size of each data item in bytes
 * @return List of parsed overlay data entities ready for database insertion
 */
fun parseOverlayData(payload: ByteArray, itemSize: UShort): List<OverlayDataEntity> {
    if (payload.isEmpty() || itemSize.toInt() == 0) {
        logger.w { "Cannot parse overlay data: empty payload or zero item size" }
        return emptyList()
    }

    val buffer = DataBuffer(payload.toUByteArray())
    buffer.setEndian(Endian.Little)
    val records = mutableListOf<OverlayDataEntity>()

    if (payload.size % itemSize.toInt() != 0) {
        logger.w {
            "Overlay payload size (${payload.size}) is not a multiple of item size ($itemSize); parsing what we can"
        }
    }

    val packetCount = payload.size / itemSize.toInt()
    logger.d { "Parsing $packetCount overlay packets" }

    for (i in 0 until packetCount) {
        val itemStart = buffer.readPosition

        val version = buffer.getUShort()
        buffer.getUShort() // unused
        val rawType = buffer.getUShort().toInt()
        val type = OverlayType.fromValue(rawType)

        if (type == null) {
            val remaining = itemSize.toInt() - 6 // already consumed 6 bytes
            if (remaining > 0) buffer.getBytes(remaining)
            logger.w { "Unknown overlay type: $rawType, skipping packet $i" }
            continue
        }

        val offsetUTC = buffer.getUInt()
        val startTime = buffer.getUInt()
        val duration = buffer.getUInt()

        var steps = 0
        var restingKiloCalories = 0
        var activeKiloCalories = 0
        var distanceCm = 0

        if (version < 3.toUShort() || (type != OverlayType.Walk && type != OverlayType.Run)) {
            if (version == 3.toUShort()) {
                // Firmware 3.x includes calorie/distance data even for non-walk/run types
                buffer.getBytes(8)
            }
        } else {
            steps = buffer.getUShort().toInt()
            restingKiloCalories = buffer.getUShort().toInt()
            activeKiloCalories = buffer.getUShort().toInt()
            distanceCm = buffer.getUShort().toInt()
        }

        records.add(
            OverlayDataEntity(
                startTime = startTime.toLong(),
                duration = duration.toLong(),
                type = type.value,
                steps = steps,
                restingKiloCalories = restingKiloCalories,
                activeKiloCalories = activeKiloCalories,
                distanceCm = distanceCm,
                offsetUTC = offsetUTC.toInt()
            )
        )

        val consumed = buffer.readPosition - itemStart
        val expected = itemSize.toInt()
        if (consumed < expected) {
            val skipAmount = expected - consumed
            if (buffer.remaining >= skipAmount) {
                buffer.getBytes(skipAmount)
            } else {
                logger.w { "Buffer exhausted skipping overlay padding: consumed=$consumed, expected=$expected, remaining=${buffer.remaining}" }
                break
            }
        } else if (consumed > expected) {
            logger.w { "Health overlay item over-read: consumed=$consumed, expected=$expected" }
        }
    }

    return records
}

// Firmware version constants for health data parsing
private val VERSION_FW_3_10_AND_BELOW: UShort = 5u
private val VERSION_FW_3_11: UShort = 6u
private val VERSION_FW_4_0: UShort = 7u
private val VERSION_FW_4_1: UShort = 8u
private val VERSION_FW_4_3: UShort = 13u
private const val VERSION_FW_4_3_SAMPLE_SIZE = 16
private val SUPPORTED_STEP_VERSIONS = setOf(
    VERSION_FW_3_10_AND_BELOW,
    VERSION_FW_3_11,
    VERSION_FW_4_0,
    VERSION_FW_4_1,
    VERSION_FW_4_3
)
