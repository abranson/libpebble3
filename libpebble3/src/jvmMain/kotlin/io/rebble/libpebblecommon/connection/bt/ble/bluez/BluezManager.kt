package io.rebble.libpebblecommon.connection.bt.ble.bluez

import co.touchlab.kermit.Logger
import com.github.hypfvieh.bluetooth.DeviceManager
import com.github.hypfvieh.bluetooth.wrapper.BluetoothAdapter
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice
import org.bluez.Adapter1
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.messages.MethodCall

private const val BLUEZ_ADAPTER_ENV = "LIBPEBBLE3_BLUEZ_ADAPTER"
private val BLUEZ_ADAPTER_PATH_REGEX = Regex("^/org/bluez/hci[0-9]+$")

internal fun isValidBluezAdapterPath(path: String): Boolean = BLUEZ_ADAPTER_PATH_REGEX.matches(path)

internal fun isObjectOnBluezAdapter(objectPath: String, adapterPath: String): Boolean =
    objectPath.startsWith("$adapterPath/")

internal data class BluezAdapterCandidate(
    val path: String,
    val address: String,
    val powered: Boolean,
)

/**
 * Choose one responsive BlueZ adapter without assuming a fixed HCI index.
 *
 * AlienBT presents the physical controller back to Sailfish as a later virtual HCI with the same
 * Bluetooth address.  Deduplicating equal addresses in favour of the later HCI selects that
 * Sailfish-facing adapter, while devices without AlienBT keep their sole hci0 adapter.
 */
internal fun selectAutomaticBluezAdapter(candidates: List<BluezAdapterCandidate>): String? {
    val deduplicated = candidates
        .groupBy { it.address.uppercase() }
        .values
        .map { duplicateAddressAdapters ->
            duplicateAddressAdapters.maxByOrNull { adapterIndex(it.path) }
        }
        .filterNotNull()
    return deduplicated
        .filter { it.powered }
        .ifEmpty { deduplicated }
        .firstOrNull()
        ?.path
}

private fun adapterIndex(path: String): Int =
    path.substringAfterLast("hci", missingDelimiterValue = "").toIntOrNull() ?: -1

/**
 * Shared access to BlueZ over the D-Bus *system* bus for the parts of the stack that Kable's
 * btleplug central backend can't do: pairing/bonding, adapter power state, seeding already-bonded
 * watches, and hosting the PPoGATT GATT *server* (peripheral role).
 *
 * The central role (scan/connect/discover/read/write) goes through Kable → btleplug, which keeps
 * its own connection to the same `bluetoothd`. Both coexist on one adapter because everything is
 * ultimately mediated by BlueZ.
 */
object BluezManager {
    private val logger = Logger.withTag("BluezManager")

    @Volatile
    private var manager: DeviceManager? = null

    /** Lazily connect to the system bus. Returns null if BlueZ/D-Bus is unavailable. */
    @Synchronized
    fun deviceManagerOrNull(): DeviceManager? {
        manager?.let { return it }
        return try {
            // BlueZ replies to Device1.Connect only once the LE connection is up (or its own
            // timeout fires) — that can exceed dbus-java's default reply timeout, surfacing as
            // NoReply while bluetoothd is still happily connecting. Must stay well under the
            // connector's CONNECT_TIMEOUT backstop or the wait-for-Connected fallback that
            // handles exactly that NoReply never gets a chance to run.
            MethodCall.setDefaultTimeout(30_000)
            // false => system bus (BlueZ lives on the system bus).
            val dm = DeviceManager.createInstance(false)
            manager = dm
            dm
        } catch (e: Throwable) {
            logger.e("BlueZ/D-Bus unavailable", e)
            null
        }
    }

    val connection: DBusConnection?
        get() = deviceManagerOrNull()?.dbusConnection

    @Volatile
    private var lastLoggedAdapterChoice: String? = null

    fun defaultAdapter(): BluetoothAdapter? = try {
        val configuredPath = System.getenv(BLUEZ_ADAPTER_ENV)?.trim()?.takeIf { it.isNotEmpty() }
        if (configuredPath != null) {
            if (!isValidBluezAdapterPath(configuredPath)) {
                logger.e("$BLUEZ_ADAPTER_ENV has invalid adapter path: $configuredPath")
                return null
            }
            val dm = deviceManagerOrNull() ?: return null
            val rawAdapter = dm.dbusConnection.getRemoteObject(
                "org.bluez",
                configuredPath,
                Adapter1::class.java,
                false,
            )
            val adapter = BluetoothAdapter(rawAdapter, configuredPath, dm.dbusConnection)
            // Validate the exact object without enumerating or querying any other adapter.
            adapter.isPowered
            val choice = "configured adapter=$configuredPath"
            if (lastLoggedAdapterChoice != choice) {
                lastLoggedAdapterChoice = choice
                logger.i { choice }
            }
            return adapter
        }

        // Don't blindly take the first adapter: phones can expose more than one HCI.
        val adapters = deviceManagerOrNull()?.scanForBluetoothAdapters().orEmpty()
        val candidates = adapters.mapNotNull { adapter ->
            val address = runCatching { adapter.address }.getOrNull() ?: return@mapNotNull null
            val powered = runCatching { adapter.isPowered }.getOrDefault(false)
            BluezAdapterCandidate(adapter.dbusPath, address, powered)
        }
        val chosenPath = selectAutomaticBluezAdapter(candidates)
        val chosen = adapters.firstOrNull { it.dbusPath == chosenPath }
        if (chosen != null && adapters.size > 1) {
            val choice = "adapters=${adapters.map { it.dbusPath }} chose=${chosen.dbusPath}"
            if (lastLoggedAdapterChoice != choice) {
                lastLoggedAdapterChoice = choice
                logger.d { choice }
            }
        }
        chosen
    } catch (e: Throwable) {
        logger.e("error getting default adapter", e)
        null
    }

    /** Re-introspect devices beneath only the selected adapter. */
    fun devicesOnDefaultAdapter(): List<BluetoothDevice> {
        val dm = deviceManagerOrNull() ?: return emptyList()
        val adapter = defaultAdapter() ?: return emptyList()
        return try {
            dm.findBtDevicesByIntrospection(adapter)
            dm.getDevices(adapter.address)
                .filter { device ->
                    device.dbusPath?.let { isObjectOnBluezAdapter(it, adapter.dbusPath) } == true
                }
        } catch (e: Throwable) {
            logger.e("error listing devices on ${adapter.dbusPath}", e)
            emptyList()
        }
    }

    /**
     * Addresses (uppercased) of every device BlueZ currently has bonded (Paired=true).
     *
     * Used to keep already-paired watches out of the "pair a new watch" scan. Dual-mode watches
     * like obelix/getafix misreport BR/EDR and linger as bonded BlueZ device objects with cached
     * advertisement data, so they'd otherwise resurface on every scan even though they're already
     * set up. Blocking (re-introspects the tree); call from Dispatchers.IO.
     */
    fun bondedAddresses(): Set<String> {
        return try {
            devicesOnDefaultAdapter()
                .filter { runCatching { it.isPaired == true }.getOrDefault(false) }
                .map { it.address.uppercase() }
                .toSet()
        } catch (e: Throwable) {
            logger.e("error listing bonded devices", e)
            emptySet()
        }
    }

    /** Find the BlueZ device wrapper for the given address (introspecting the object tree). */
    fun findDevice(address: String): BluetoothDevice? {
        return try {
            devicesOnDefaultAdapter().firstOrNull { it.address.equals(address, ignoreCase = true) }
        } catch (e: Throwable) {
            logger.e("error finding device $address", e)
            null
        }
    }

    /**
     * Drop the BlueZ bond and the device from bluetoothd's object tree.
     *
     * libpebble3's own forget() only clears its database; nothing in the platform pairing contract
     * removes a bond. Leaving one behind means the next pairing attempt runs against a device BlueZ
     * still holds keys for while libpebble3 treats it as new — the watch then rejects the
     * connection, and it looks like pairing is simply broken.
     *
     * Blocking; call from Dispatchers.IO. Removing also disconnects.
     */
    fun removeBond(address: String): Boolean {
        val device = findDevice(address) ?: run {
            logger.d { "removeBond($address): not in BlueZ's tree; nothing to remove" }
            return false
        }
        val adapter = defaultAdapter() ?: return false
        return try {
            adapter.removeDevice(device.rawDevice)
            logger.i { "removeBond($address): BlueZ bond removed" }
            true
        } catch (e: Throwable) {
            logger.e("removeBond($address) failed", e)
            false
        }
    }

    /**
     * [findDevice], re-discovering if needed: bluetoothd purges un-bonded devices from its
     * object tree soon after discovery stops, so connecting to a watch found by an earlier
     * scan (i.e. pairing) has to repopulate it first. No extra cost when already present.
     * Blocking; call from Dispatchers.IO.
     */
    fun findDeviceRediscovering(address: String, timeoutMs: Long = 10_000): BluetoothDevice? {
        findDevice(address)?.let { return it }
        val adapter = defaultAdapter() ?: return null
        val startedDiscovery = runCatching {
            if (adapter.isDiscovering) {
                false
            } else {
                logger.i { "$address not in BlueZ tree; discovering to find it" }
                adapter.startDiscovery()
                true
            }
        }.getOrDefault(false)
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                // findDevice re-introspects the whole tree; 1s keeps that affordable.
                Thread.sleep(1_000)
                findDevice(address)?.let { return it }
            }
            // Distinguishes "watch stopped advertising" (others visible) from a wedged
            // controller RX path (tree stays empty even with devices nearby).
            val treeSize = runCatching { devicesOnDefaultAdapter().size }.getOrNull()
            logger.w { "$address not found after ${timeoutMs}ms discovery; BlueZ tree has $treeSize device(s)" }
            return null
        } finally {
            if (startedDiscovery) runCatching { adapter.stopDiscovery() }
        }
    }

    /**
     * Power-cycle the adapter. The phone's controller sometimes wedges its LE RX path —
     * scans stop seeing anything and connects hang until Bluetooth is turned off and on
     * again (a reboot also cleared it). Last-resort recovery on the connect-failure path;
     * drops every active BT connection on the adapter.
     */
    fun powerCycleAdapter(): Boolean {
        val adapter = defaultAdapter() ?: return false
        return runCatching {
            logger.w { "power-cycling ${adapter.dbusPath} to recover the controller" }
            adapter.isPowered = false
            Thread.sleep(1_000)
            adapter.isPowered = true
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && !adapter.isPowered) {
                Thread.sleep(500)
            }
            adapter.isPowered
        }.getOrDefault(false)
    }
}
