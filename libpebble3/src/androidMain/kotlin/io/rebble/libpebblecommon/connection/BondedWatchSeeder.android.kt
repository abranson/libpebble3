package io.rebble.libpebblecommon.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.bt.classic.PEBBLE_NAME_REGEX
import io.rebble.libpebblecommon.database.entity.TransportType

private val logger = Logger.withTag("BondedWatchSeeder")

@SuppressLint("MissingPermission")
internal actual suspend fun bondedWatchCandidates(
    appContext: AppContext,
): List<BondedWatchCandidate>? {
    val btManager = appContext.context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter = btManager?.adapter ?: return emptyList()
    val bonded = try {
        adapter.bondedDevices ?: emptySet()
    } catch (e: SecurityException) {
        logger.w(e) { "Missing BLUETOOTH_CONNECT; bonded watch import unavailable" }
        return null
    }
    return try {
        mergeBondedWatchCandidates(
            bonded.mapNotNull { device ->
                val name = device.name?.takeIf(PEBBLE_NAME_REGEX::matches)
                    ?: return@mapNotNull null
                val address = device.address.uppercase()
                val addresses = when (device.type) {
                    BluetoothDevice.DEVICE_TYPE_LE -> mapOf(TransportType.BluetoothLe to address)
                    BluetoothDevice.DEVICE_TYPE_CLASSIC ->
                        mapOf(TransportType.BluetoothClassic to address)
                    BluetoothDevice.DEVICE_TYPE_DUAL -> mapOf(
                        TransportType.BluetoothLe to address,
                        TransportType.BluetoothClassic to address,
                    )
                    else -> return@mapNotNull null
                }
                BondedWatchCandidate(
                    identity = bondedWatchIdentity(name, address),
                    name = name,
                    addresses = addresses,
                )
            },
        )
    } catch (e: SecurityException) {
        logger.w(e) { "Missing BLUETOOTH_CONNECT; bonded watch import unavailable" }
        null
    }
}
