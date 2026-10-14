package io.rebble.libpebblecommon.services

import io.rebble.libpebblecommon.packets.ScreenshotResponseCode
import io.rebble.libpebblecommon.packets.ScreenshotVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScreenshotServiceTest {
    @Test
    fun validPebbleScreenshotsHaveExpectedWireSizes() {
        assertEquals(3_024, header(ScreenshotVersion.BLACK_WHITE_1_BIT).dataSizeOrNull())
        assertEquals(24_192, header(ScreenshotVersion.COLOR_8_BIT).dataSizeOrNull())
    }

    @Test
    fun rejectsDimensionsThatWouldOverflowOrRequireOversizedAllocations() {
        assertNull(header(ScreenshotVersion.COLOR_8_BIT, width = 65_536, height = 65_536).dataSizeOrNull())
        assertNull(header(ScreenshotVersion.COLOR_8_BIT, width = 1_025, height = 1_024).dataSizeOrNull())
        assertNull(header(ScreenshotVersion.COLOR_8_BIT, width = -1).dataSizeOrNull())
        assertNull(header(ScreenshotVersion.COLOR_8_BIT, width = 0).dataSizeOrNull())
        assertNull(header(ScreenshotVersion.COLOR_8_BIT, height = 0).dataSizeOrNull())
        assertEquals(1_048_576, header(ScreenshotVersion.COLOR_8_BIT, 1_024, 1_024).dataSizeOrNull())
    }

    @Test
    fun rejectsOneBitRowsThatCannotBeDecodedFromTheWireFormat() {
        assertNull(header(ScreenshotVersion.BLACK_WHITE_1_BIT, width = 145).dataSizeOrNull())
    }

    private fun header(
        version: ScreenshotVersion,
        width: Int = 144,
        height: Int = 168,
    ) = ParsedScreenshotHeader(
        responseCode = ScreenshotResponseCode.OK,
        version = version,
        width = width,
        height = height,
    )
}
