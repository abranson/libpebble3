package io.rebble.libpebblecommon.connection

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse

class FakeNotificationAppsTest {
    @Test
    fun forgetNotificationAppRemovesSource() = runTest {
        val libPebble = FakeLibPebble()
        val packageName = libPebble.notificationApps().first().first().app.packageName

        libPebble.forgetNotificationApp(packageName)

        assertFalse(libPebble.notificationApps().first().any { it.app.packageName == packageName })
    }
}
