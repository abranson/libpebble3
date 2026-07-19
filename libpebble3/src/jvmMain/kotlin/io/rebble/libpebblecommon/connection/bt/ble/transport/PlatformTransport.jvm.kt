package io.rebble.libpebblecommon.connection.bt.ble.transport

import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.PlatformIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.BluezBleScanner
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.BluezGattConnector
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope

// The BLE central role goes through BlueZ D-Bus rather than Kable/btleplug. Kable's desktop
// backend binds btleplug through UniFFI + JNA, whose runtime FFI dispatch (libjnidispatch, dynamic
// proxies, Structure reflection) doesn't survive GraalVM native-image's closed world — which is
// what this daemon compiles to. bluez-dbus is pure JVM (no JNI), so it just works in the image.
// (The prebuilt libbtleplug_ffi.so also wants glibc >= 2.38, but Sailfish 5.0 ships 2.41, so glibc
// isn't the blocker any more — native-image/JNA is.)

actual fun libpebbleBleScanner(bleConfigFlow: io.rebble.libpebblecommon.BleConfigFlow): BleScanner = BluezBleScanner()

actual fun libpebbleGattConnector(
    identifier: PebbleBleIdentifier,
    platformIdentifier: PlatformIdentifier,
    scope: ConnectionCoroutineScope,
    blePlatformConfig: BlePlatformConfig,
): GattConnector = BluezGattConnector(identifier, scope)
