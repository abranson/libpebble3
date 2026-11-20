package io.rebble.libpebblecommon.plugin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

actual class PhoneSoundPlayer {
    actual val isRinging: Flow<Boolean> = flowOf(false)

    actual fun startRinging() {
        throw PluginNativeException(PluginErrors.PLUGIN_UNAVAILABLE, "Phone ringing is unavailable on this platform")
    }

    actual fun stopRinging() = Unit
}
