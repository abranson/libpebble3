package io.rebble.libpebblecommon.connection

import java.util.Locale

private val BLUETOOTH_ADDRESS_REGEX = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")

internal fun isCanonicalBluetoothAddress(address: String): Boolean =
    BLUETOOTH_ADDRESS_REGEX.matches(address)

// mac address on android, uuid on ios etc
actual data class PebbleBleIdentifier(val id: String) : PebbleIdentifier {
    actual override val asString: String = id
}

actual fun String.asPebbleBleIdentifier(): PebbleBleIdentifier {
    return PebbleBleIdentifier(this)
}

@ConsistentCopyVisibility
actual data class PebbleBtClassicIdentifier private constructor(
    val macAddress: String,
) : PebbleIdentifier {
    actual override val asString: String = macAddress

    init {
        check(macAddress == macAddress.uppercase(Locale.US))
    }

    companion object {
        operator fun invoke(macAddress: String): PebbleBtClassicIdentifier {
            val canonicalAddress = macAddress.uppercase(Locale.US)
            require(isCanonicalBluetoothAddress(canonicalAddress)) {
                "Invalid Bluetooth address: $macAddress"
            }
            return PebbleBtClassicIdentifier(canonicalAddress)
        }
    }
}

actual fun String.asPebbleBtClassicIdentifier(): PebbleBtClassicIdentifier {
    return PebbleBtClassicIdentifier(this)
}
