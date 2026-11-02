package io.rebble.libpebblecommon.health

import io.rebble.libpebblecommon.health.parsers.parseStepsData
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthDataParserTest {
    @Test
    fun version14StoresSpo2FieldsBetweenSamples() {
        val payload = packet(version = 14, sampleSize = 18)
        val records = parseStepsData(payload, payload.size.toUShort())

        assertEquals(listOf(100, 150), records.map { it.steps })
        assertEquals(listOf(1600000000L, 1600000060L), records.map { it.timestamp })
        assertEquals(listOf(60, 65), records.map { it.heartRate })
        assertEquals(listOf(500, 600), records.map { it.intensity })
        assertEquals(listOf(10, 12), records.map { it.restingGramCalories })
        assertEquals(listOf(50, 60), records.map { it.activeGramCalories })
        assertEquals(listOf(7000, 8000), records.map { it.distanceCm })
        assertEquals(listOf(1, 0), records.map { it.activeMinutes })
        assertEquals(listOf(100, 200), records.map { it.heartRateWeight })
        assertEquals(listOf(1, 2), records.map { it.heartRateZone })
        assertEquals(listOf(98, 97), records.map { it.spo2Percent })
        assertEquals(listOf(6, 7), records.map { it.spo2Quality })
    }

    @Test
    fun version13KeepsExistingSampleLayout() {
        val payload = packet(version = 13, sampleSize = 16)
        val records = parseStepsData(payload, payload.size.toUShort())
        assertEquals(listOf(100, 150), records.map { it.steps })
        assertEquals(listOf(60, 65), records.map { it.heartRate })
        assertEquals(listOf(100, 200), records.map { it.heartRateWeight })
        assertTrue(records.all { it.spo2Percent == 0 && it.spo2Quality == 0 })
    }

    @Test
    fun newerVersionsSkipLongerSampleExtensions() {
        val payload = packet(version = 20, sampleSize = 20)
        assertEquals(listOf(100, 150), parseStepsData(payload, payload.size.toUShort()).map { it.steps })
    }

    @Test
    fun partialPacketsSkipPaddingBeforeNextHeader() {
        val first = packet(version = 14, sampleSize = 18, count = 1)
        val second = packet(version = 14, sampleSize = 18, timestamp = 1600000900u)
        val records = parseStepsData(first + second, first.size.toUShort())
        assertEquals(listOf(100, 100, 150), records.map { it.steps })
        assertEquals(listOf(1600000000L, 1600000900L, 1600000960L), records.map { it.timestamp })
    }

    @Test
    fun oversizedSampleCountCannotReadIntoNextPacket() {
        val first = packet(version = 14, sampleSize = 18, count = 3)
        val second = packet(version = 14, sampleSize = 18, timestamp = 1600000900u)
        val records = parseStepsData(first + second, first.size.toUShort())
        assertEquals(listOf(100, 150, 100, 150), records.map { it.steps })
        assertEquals(1600000900L, records[2].timestamp)
    }

    @Test
    fun newerVersionWithShortSampleSkipsOnlyItsPacket() {
        val first = packet(version = 14, sampleSize = 18).also { it[7] = 15 }
        val second = packet(version = 14, sampleSize = 18, timestamp = 1600000900u)
        val records = parseStepsData(first + second, first.size.toUShort())
        assertEquals(listOf(100, 150), records.map { it.steps })
        assertEquals(1600000900L, records.first().timestamp)
    }

    @Test
    fun incompleteHeaderAndSampleDoNotThrow() {
        assertTrue(parseStepsData(ByteArray(8), 8u).isEmpty())
        val payload = packet(version = 14, sampleSize = 18).copyOf(26)
        assertTrue(parseStepsData(payload, payload.size.toUShort()).isEmpty())
    }

    @Test
    fun shortOxygenExtensionDoesNotReadTheNextMinute() {
        for (sampleSize in 16..17) {
            val payload = packet(version = 14, sampleSize = sampleSize)
            val records = parseStepsData(payload, payload.size.toUShort())
            assertEquals(listOf(100, 150), records.map { it.steps })
            assertTrue(records.all { it.spo2Percent == 0 && it.spo2Quality == 0 })
        }
    }

    @Test
    fun rawMissingAndUnknownOxygenValuesArePreserved() {
        val payload = packet(version = 14, sampleSize = 18).also {
            it[9 + 16] = 0
            it[9 + 17] = 0
            it[9 + 18 + 16] = 255.toByte()
            it[9 + 18 + 17] = 254.toByte()
        }
        val records = parseStepsData(payload, payload.size.toUShort())
        assertEquals(listOf(0, 255), records.map { it.spo2Percent })
        assertEquals(listOf(0, 254), records.map { it.spo2Quality })
    }

    private fun packet(
        version: Int,
        sampleSize: Int,
        timestamp: UInt = 1600000000u,
        count: Int = 2,
    ): ByteArray {
        val buffer = DataBuffer(9 + 2 * sampleSize)
        buffer.setEndian(Endian.Little)
        buffer.putUShort(version.toUShort())
        buffer.putUInt(timestamp)
        buffer.putUByte(0u)
        buffer.putUByte(sampleSize.toUByte())
        buffer.putUByte(count.toUByte())
        repeat(2) { index ->
            buffer.putUByte(if (index == 0) 100u else 150u)
            buffer.putUByte((index + 1).toUByte())
            buffer.putUShort(if (index == 0) 500u else 600u)
            buffer.putUByte(if (index == 0) 10u else 20u)
            buffer.putUByte(if (index == 0) 2u else 0u)
            buffer.putUShort(if (index == 0) 10u else 12u)
            buffer.putUShort(if (index == 0) 50u else 60u)
            buffer.putUShort(if (index == 0) 7000u else 8000u)
            buffer.putUByte(if (index == 0) 60u else 65u)
            buffer.putUShort(if (index == 0) 100u else 200u)
            buffer.putUByte((index + 1).toUByte())
            if (sampleSize >= 18) {
                buffer.putUByte((98 - index).toUByte())
                buffer.putUByte((6 + index).toUByte())
                repeat(sampleSize - 18) { buffer.putUByte(255u) }
            } else {
                repeat(sampleSize - 16) { buffer.putUByte(98u) }
            }
        }
        return buffer.array().toByteArray()
    }
}
