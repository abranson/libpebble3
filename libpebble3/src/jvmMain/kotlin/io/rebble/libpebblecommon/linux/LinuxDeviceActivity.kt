package io.rebble.libpebblecommon.linux

/**
 * Reports whether the phone is currently active and unlocked.
 *
 * Generic Linux has no portable way to determine this, so its default is
 * deliberately inactive. A distro may replace this binding with a local
 * implementation without extending the notification transport.
 */
fun interface LinuxDeviceActivity {
    fun isActiveAndUnlocked(): Boolean
}

object InactiveLinuxDeviceActivity : LinuxDeviceActivity {
    override fun isActiveAndUnlocked() = false
}
