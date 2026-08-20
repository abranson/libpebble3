package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import com.juul.kable.Peripheral
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier

actual fun peripheralFromIdentifier(
    identifier: PebbleBleIdentifier,
    name: String,
    autoConnect: Boolean,
): Peripheral? {
    TODO("Not yet implemented")
}

// btleplug auto-negotiates the MTU; the common client re-adds the 3-byte ATT overhead in
// getMtu(), so there is nothing platform-specific to request here. Native
// Linux normally uses the BlueZ connector, but the JVM actual must still exist.
actual suspend fun Peripheral.requestMtuNative(mtu: Int): Int = mtu

actual suspend fun Peripheral.refreshServicesNative(): Boolean = false