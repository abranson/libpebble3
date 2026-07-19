package io.rebble.libpebblecommon.connection.bt.ble.transport

import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.PlatformIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.KableGattConnector
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.kableBleScanner
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope

actual fun libpebbleBleScanner(bleConfigFlow: io.rebble.libpebblecommon.BleConfigFlow): BleScanner = kableBleScanner(bleConfigFlow)

actual fun libpebbleGattConnector(
    identifier: PebbleBleIdentifier,
    platformIdentifier: PlatformIdentifier,
    scope: ConnectionCoroutineScope,
    blePlatformConfig: BlePlatformConfig,
): GattConnector = KableGattConnector(
    identifier = identifier,
    peripheral = (platformIdentifier as PlatformIdentifier.BlePlatformIdentifier).peripheral,
    scope = scope,
    blePlatformConfig = blePlatformConfig,
)
