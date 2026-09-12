package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.SystemAppIDs
import io.rebble.libpebblecommon.metadata.WatchType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemAppsTest {
    @Test
    fun sendTextIsAvailableOnTimeEraWatches() {
        val sendText = SystemApps.entries.single { it.uuid == SystemAppIDs.SMS_APP_UUID }

        assertEquals("Send Text", sendText.displayName)
        assertEquals(AppType.Watchapp, sendText.type)
        assertFalse(WatchType.APLITE in sendText.compatiblePlatforms)
        assertTrue(WatchType.BASALT in sendText.compatiblePlatforms)
        assertTrue(WatchType.CHALK in sendText.compatiblePlatforms)
    }

    @Test
    fun systemAppDefaultOrderIsUnambiguousWithinEachType() {
        AppType.entries.forEach { type ->
            val apps = SystemApps.entries.filter { it.type == type }

            assertEquals(apps.size, apps.map { it.defaultOrder }.distinct().size)
        }
    }
}
