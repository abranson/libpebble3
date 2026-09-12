package io.rebble.libpebblecommon.di

import io.rebble.libpebblecommon.connection.PhoneCapabilities
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.plugin.PhoneStatePlugin
import io.rebble.libpebblecommon.plugin.PlatformPlugins
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformPluginsJvmTest {
    @Test
    fun advertisesSendTextSoFirmwareEnablesTheSystemApp() {
        val app = koinApplication { modules(platformModule) }
        try {
            assertTrue(ProtocolCapsFlag.SupportsSendTextApp in app.koin.get<PhoneCapabilities>().capabilities)
        } finally {
            app.close()
        }
    }

    @Test
    fun advertisesImageFetchSoFirmwareRequestsNotificationImages() {
        val app = koinApplication { modules(platformModule) }
        try {
            assertTrue(ProtocolCapsFlag.SupportsImageFetch in app.koin.get<PhoneCapabilities>().capabilities)
        } finally {
            app.close()
        }
    }

    @Test
    fun phoneStatePluginResolvesWithJvmPlatformBindings() = runTest {
        val app = koinApplication {
            modules(platformModule, module { singleOf(::PhoneStatePlugin) })
        }
        try {
            val plugin = app.koin.get<PhoneStatePlugin>()
            val state = plugin.observe("phone", "phone_state", null, null).first()
            assertTrue(state.instances.isEmpty())
            assertTrue(app.koin.get<PlatformPlugins>().plugins.isEmpty())
        } finally {
            app.close()
        }
    }
}
