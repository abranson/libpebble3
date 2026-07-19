package io.rebble.libpebblecommon.connection

actual fun platformCreatePlatformIdentifier(bleConfig: io.rebble.libpebblecommon.BleConfigFlow, blePlatformConfig: io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig): CreatePlatformIdentifier =
    RealCreatePlatformIdentifier(bleConfig, blePlatformConfig)
