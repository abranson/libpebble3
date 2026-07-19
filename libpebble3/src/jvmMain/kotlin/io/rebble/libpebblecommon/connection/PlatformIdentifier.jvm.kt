package io.rebble.libpebblecommon.connection

actual fun platformCreatePlatformIdentifier(bleConfig: io.rebble.libpebblecommon.BleConfigFlow, blePlatformConfig: io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig): CreatePlatformIdentifier =
    object : CreatePlatformIdentifier {
        override fun identifier(identifier: PebbleIdentifier, name: String, lastAttemptFailed: Boolean): PlatformIdentifier? =
            when (identifier) {
                is PebbleBleIdentifier ->
                    PlatformIdentifier.BluezBlePlatformIdentifier(identifier)
                is PebbleBtClassicIdentifier ->
                    PlatformIdentifier.BtClassicPlatformIdentifier(identifier)
                else -> error("unknown identifier type: $identifier")
            }
    }
