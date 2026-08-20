package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.bt.ble.bluez.BluezManager
import io.rebble.libpebblecommon.connection.bt.ble.bluez.LinuxBluezDevice
import io.rebble.libpebblecommon.connection.bt.classic.PEBBLE_NAME_REGEX
import io.rebble.libpebblecommon.database.entity.TransportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val logger = Logger.withTag("BondedWatchSeeder")
private const val CLASSIC_PEBBLE_SERVICE_UUID = "00000000-deca-fade-deca-deafdecacaff"
private const val LE_PEBBLE_SERVICE_UUID = "0000fed9-0000-1000-8000-00805f9b34fb"
private val BLUETOOTH_ADDRESS_REGEX = Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

internal fun classifyJvmBondedPebble(
    name: String?,
    uuids: Collection<String>,
    bluetoothClass: Int?,
): TransportType? {
    if (name == null || !PEBBLE_NAME_REGEX.matches(name)) return null

    val normalizedUuids = uuids.mapTo(hashSetOf()) { it.lowercase() }
    return when {
        CLASSIC_PEBBLE_SERVICE_UUID in normalizedUuids -> TransportType.BluetoothClassic
        LE_PEBBLE_SERVICE_UUID in normalizedUuids -> TransportType.BluetoothLe
        bluetoothClass != null && bluetoothClass != 0 -> TransportType.BluetoothClassic
        name.startsWith("Pebble Time Le ", ignoreCase = true) -> TransportType.BluetoothLe
        else -> null
    }
}

internal fun planJvmBondedWatchCandidates(
    devices: List<LinuxBluezDevice>,
): List<BondedWatchCandidate> {
    val candidates = devices.mapNotNull { device ->
        if (!device.paired) return@mapNotNull null
        val address = device.address.uppercase()
        if (!BLUETOOTH_ADDRESS_REGEX.matches(address)) return@mapNotNull null
        val name = device.name?.takeIf(PEBBLE_NAME_REGEX::matches) ?: return@mapNotNull null
        val normalizedUuids = device.uuids.mapTo(hashSetOf()) { it.lowercase() }
        val hasLe = LE_PEBBLE_SERVICE_UUID in normalizedUuids ||
            name.startsWith("Pebble Time Le ", ignoreCase = true)
        val hasClassic = CLASSIC_PEBBLE_SERVICE_UUID in normalizedUuids ||
            (!hasLe && device.bluetoothClass != null && device.bluetoothClass != 0)
        val addresses = buildMap {
            if (hasLe) put(TransportType.BluetoothLe, address)
            if (hasClassic) put(TransportType.BluetoothClassic, address)
        }
        if (addresses.isEmpty()) return@mapNotNull null
        BondedWatchCandidate(
            identity = bondedWatchIdentity(name, address),
            name = name,
            addresses = addresses,
        )
    }
    return mergeBondedWatchCandidates(candidates)
}

/**
 * Resolve every paired BlueZ object which belongs to one logical watch before Forget removes
 * the portable record.  The requested address is retained as an idempotent removal anchor even
 * when bluetoothd has already dropped that object from its current snapshot.
 */
internal fun planJvmBondedWatchRemoval(
    devices: List<LinuxBluezDevice>,
    knownName: String,
    knownAddress: String,
): List<String> {
    val normalizedAddress = knownAddress.uppercase()
    if (!BLUETOOTH_ADDRESS_REGEX.matches(normalizedAddress)) return emptyList()
    val identity = bondedWatchIdentity(knownName, normalizedAddress)
    return buildSet {
        add(normalizedAddress)
        devices.forEach { device ->
            if (!device.paired) return@forEach
            val address = device.address.uppercase()
            if (!BLUETOOTH_ADDRESS_REGEX.matches(address)) return@forEach
            val sameAddress = address == normalizedAddress
            val sameIdentity = classifyJvmBondedPebble(
                name = device.name,
                uuids = device.uuids,
                bluetoothClass = device.bluetoothClass,
            ) != null && bondedWatchIdentity(device.name.orEmpty(), address) == identity
            if (sameAddress || sameIdentity) add(address)
        }
    }.sorted()
}

internal actual suspend fun bondedWatchCandidates(
    appContext: AppContext,
): List<BondedWatchCandidate>? = withContext(Dispatchers.IO) {
    val devices = BluezManager.deviceSnapshotsOnDefaultAdapter()
    if (devices == null) {
        logger.w { "Selected BlueZ adapter unavailable; bonded watch import will be retried" }
        return@withContext null
    }
    planJvmBondedWatchCandidates(devices)
}
