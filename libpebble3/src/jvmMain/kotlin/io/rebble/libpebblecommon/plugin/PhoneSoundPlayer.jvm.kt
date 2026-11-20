package io.rebble.libpebblecommon.plugin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

actual open class PhoneSoundPlayer {
    actual open val isRinging: Flow<Boolean> = flowOf(false)

    actual open fun startRinging() {
        throw PluginNativeException(PluginErrors.PLUGIN_UNAVAILABLE, "Phone ringing is unavailable on this platform")
    }

    actual open fun stopRinging() = Unit
}
