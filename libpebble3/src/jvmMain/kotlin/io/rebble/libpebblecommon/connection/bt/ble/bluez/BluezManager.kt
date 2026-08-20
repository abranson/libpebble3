package io.rebble.libpebblecommon.connection.bt.ble.bluez

import co.touchlab.kermit.Logger
import com.github.hypfvieh.bluetooth.DeviceManager
import com.github.hypfvieh.bluetooth.wrapper.BluetoothAdapter
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice
import io.rebble.libpebblecommon.connection.planJvmBondedWatchRemoval
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import org.bluez.Adapter1
import org.bluez.Device1
import org.bluez.exceptions.BluezDoesNotExistException
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.messages.MethodCall
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.Executors

private const val BLUEZ_ADAPTER_ENV = "LIBPEBBLE3_BLUEZ_ADAPTER"
private const val BLUEZ_SERVICE = "org.bluez"
private const val DEVICE_INTERFACE = "org.bluez.Device1"
private val BLUEZ_ADAPTER_PATH_REGEX = Regex("^/org/bluez/hci[0-9]+$")
private val BLUETOOTH_ADDRESS_REGEX = Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")

internal fun isValidBluezAdapterPath(path: String): Boolean = BLUEZ_ADAPTER_PATH_REGEX.matches(path)

internal fun isObjectOnBluezAdapter(objectPath: String, adapterPath: String): Boolean =
    objectPath.startsWith("$adapterPath/")

/** dbus-java signal rules require a unique sender, not BlueZ's well-known bus name. */
internal fun <T : DBusSignal> DBusConnection.addBluezSigHandler(
    signalClass: Class<T>,
    handler: DBusSigHandler<T>,
): AutoCloseable = addSigHandler(signalClass, getDBusOwnerName(BLUEZ_SERVICE), handler)

internal fun bluezDevicePath(adapterPath: String, address: String): String? {
    if (!isValidBluezAdapterPath(adapterPath) || !BLUETOOTH_ADDRESS_REGEX.matches(address)) {
        return null
    }
    return "$adapterPath/dev_${address.uppercase().replace(':', '_')}"
}

internal fun isBluezDoesNotExist(error: Throwable): Boolean {
    val seen = hashSetOf<Throwable>()
    var current: Throwable? = error
    while (current != null && seen.add(current)) {
        if (current is BluezDoesNotExistException) return true
        current = current.cause
    }
    return false
}

internal fun preparedBondRemovalSucceededAfterError(
    error: Throwable,
    pairedAfterFailure: Boolean?,
): Boolean = isBluezDoesNotExist(error) || pairedAfterFailure == false

data class LinuxBluezAdapter(
    val path: String,
    val address: String,
    val powered: Boolean,
)

internal data class LinuxBluezDevice(
    val path: String,
    val address: String,
    val name: String?,
    val paired: Boolean,
    val uuids: List<String>,
    val bluetoothClass: Int?,
)

internal fun bluezDeviceFromManagedObject(
    path: String,
    adapterPath: String,
    properties: Map<String, Variant<*>>,
): LinuxBluezDevice? {
    if (!isObjectOnBluezAdapter(path, adapterPath)) return null
    val address = properties["Address"]?.value as? String ?: return null
    if (bluezDevicePath(adapterPath, address) != path) return null
    val declaredAdapter = (properties["Adapter"]?.value as? DBusPath)?.path
    if (declaredAdapter != adapterPath) return null
    val name = (properties["Name"]?.value as? String)
        ?: (properties["Alias"]?.value as? String)
    val uuids = when (val value = properties["UUIDs"]?.value) {
        is Array<*> -> value.filterIsInstance<String>()
        is Iterable<*> -> value.filterIsInstance<String>()
        else -> emptyList()
    }
    return LinuxBluezDevice(
        path = path,
        address = address.uppercase(),
        name = name,
        paired = properties["Paired"]?.value as? Boolean ?: false,
        uuids = uuids,
        bluetoothClass = (properties["Class"]?.value as? Number)?.toInt(),
    )
}

/** Optional native-Linux policy for choosing among multiple BlueZ adapters. */
fun interface LinuxBluezAdapterSelector {
    fun select(adapters: List<LinuxBluezAdapter>): String?
}

private object BluezDependencies : LibPebbleKoinComponent

/** A selected-adapter snapshot prepared before portable watch state is retired. */
class BluezBondRemovalPlan internal constructor(
    addresses: Collection<String>,
    internal val adapter: BluetoothAdapter,
) {
    val addresses: Set<String> = addresses.toCollection(linkedSetOf())
    val adapterIndex: Int? = adapter.dbusPath
        .takeIf(::isValidBluezAdapterPath)
        ?.removePrefix("/org/bluez/hci")
        ?.toIntOrNull()

    /** Blocking; call from Dispatchers.IO. */
    fun removeAll(): Boolean = BluezManager.removePreparedBonds(this)
}

/** Generic default: use the first powered adapter, or the first adapter. */
internal fun selectAutomaticBluezAdapter(candidates: List<LinuxBluezAdapter>): String? =
    candidates
        .filter { it.powered }
        .ifEmpty { candidates }
        .firstOrNull()
        ?.path

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
    private val discoveryCoordinator = BluezDiscoveryCoordinator()
    private val cleanupWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "libpebble3-bluez-cleanup").apply { isDaemon = true }
    }

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
            LinuxBluezAdapter(adapter.dbusPath, address, powered)
        }
        val selector = BluezDependencies.getKoin().getOrNull<LinuxBluezAdapterSelector>()
        val chosenPath = if (selector == null) {
            selectAutomaticBluezAdapter(candidates)
        } else {
            runCatching { selector.select(candidates) }
                .onFailure { logger.e("BlueZ adapter selector failed", it) }
                .getOrNull()
        }
        if (chosenPath != null && candidates.none { it.path == chosenPath }) {
            logger.e("BlueZ adapter selector returned unknown path: $chosenPath")
            return null
        }
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
     * Snapshot Device1 objects directly from BlueZ's ObjectManager below the selected adapter.
     * This deliberately bypasses bluez-dbus's adapter-address-keyed cache. Linux systems can
     * expose multiple adapter objects with the same controller address, which would otherwise
     * alias their device trees.
     */
    internal fun deviceSnapshotsOnDefaultAdapter(): List<LinuxBluezDevice>? {
        val dm = deviceManagerOrNull() ?: return null
        val adapter = defaultAdapter() ?: return null
        return deviceSnapshots(adapter, dm)
    }

    private fun deviceSnapshots(
        adapter: BluetoothAdapter,
        dm: DeviceManager,
    ): List<LinuxBluezDevice>? {
        return try {
            val objectManager = dm.dbusConnection.getRemoteObject(
                "org.bluez",
                "/",
                ObjectManager::class.java,
                false,
            )
            objectManager.GetManagedObjects().mapNotNull { (path, interfaces) ->
                interfaces[DEVICE_INTERFACE]?.let { properties ->
                    bluezDeviceFromManagedObject(path.path, adapter.dbusPath, properties)
                }
            }
        } catch (e: Throwable) {
            logger.e("error reading BlueZ devices below ${adapter.dbusPath}", e)
            null
        }
    }

    /**
     * Resolve all currently paired LE/Classic aliases for one logical watch from a single
     * selected-adapter snapshot.  `null` means that the adapter or its object tree is unavailable;
     * callers must retain portable watch state so cleanup can be retried.
     *
     * Blocking; call from Dispatchers.IO.
     */
    fun prepareBondRemoval(address: String, name: String): BluezBondRemovalPlan? {
        val dm = deviceManagerOrNull() ?: return null
        val adapter = defaultAdapter() ?: return null
        val devices = deviceSnapshots(adapter, dm) ?: return null
        val addresses = planJvmBondedWatchRemoval(devices, name, address)
        if (addresses.isEmpty()) {
            logger.e("cannot prepare bond removal for invalid address")
            return null
        }
        return BluezBondRemovalPlan(addresses, adapter)
    }

    internal fun removePreparedBonds(plan: BluezBondRemovalPlan): Boolean {
        var complete = true
        plan.addresses.forEach { address ->
            val devicePath = bluezDevicePath(plan.adapter.dbusPath, address)
            if (devicePath == null) {
                logger.e("cannot remove invalid prepared bond address")
                complete = false
                return@forEach
            }
            try {
                plan.adapter.rawAdapter.RemoveDevice(DBusPath(devicePath))
                logger.i { "removeBond($address): BlueZ bond removed" }
            } catch (e: Throwable) {
                val pairedAfterFailure = preparedBondStillPaired(plan.adapter, devicePath)
                if (preparedBondRemovalSucceededAfterError(e, pairedAfterFailure)) {
                    logger.d { "removeBond($address): already absent" }
                } else {
                    logger.e("removeBond($address) failed", e)
                    complete = false
                }
            }
        }
        return complete
    }

    private fun preparedBondStillPaired(
        adapter: BluetoothAdapter,
        devicePath: String,
    ): Boolean? = try {
        val objectManager = adapter.dbusConnection.getRemoteObject(
            "org.bluez",
            "/",
            ObjectManager::class.java,
            false,
        )
        val device = objectManager.GetManagedObjects().entries
            .firstOrNull { it.key.path == devicePath }
            ?.value
            ?.get(DEVICE_INTERFACE)
        if (device == null) false else device["Paired"]?.value as? Boolean
    } catch (e: Throwable) {
        logger.w(e) { "could not verify BlueZ bond state after RemoveDevice failure" }
        null
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
        return deviceSnapshotsOnDefaultAdapter()
            ?.filter(LinuxBluezDevice::paired)
            ?.mapTo(hashSetOf(), LinuxBluezDevice::address)
            ?: emptySet()
    }

    /**
     * Find the BlueZ device wrapper for the given address on the selected adapter.
     *
     * BlueZ device paths are deterministic. Resolve that exact object instead of using
     * bluez-dbus's device cache: the cache is keyed by adapter MAC, so it aliases a physical
     * controller and a virtual controller which expose the same address.
     */
    fun findDevice(address: String): BluetoothDevice? {
        val dm = deviceManagerOrNull() ?: return null
        val adapter = defaultAdapter() ?: return null
        return findDevice(address, adapter, dm)
    }

    private fun findDevice(
        address: String,
        adapter: BluetoothAdapter,
        dm: DeviceManager,
    ): BluetoothDevice? {
        val devicePath = bluezDevicePath(adapter.dbusPath, address) ?: return null
        return try {
            val properties = dm.dbusConnection.getRemoteObject(
                "org.bluez",
                devicePath,
                Properties::class.java,
                false,
            ).GetAll(DEVICE_INTERFACE)
            val actualAddress = properties["Address"]?.value as? String
            val actualAdapter = (properties["Adapter"]?.value as? DBusPath)?.path
            if (!actualAddress.equals(address, ignoreCase = true) || actualAdapter != adapter.dbusPath) {
                logger.w {
                    "ignoring mismatched BlueZ device $devicePath: " +
                        "address=$actualAddress adapter=$actualAdapter"
                }
                return null
            }
            val rawDevice = dm.dbusConnection.getRemoteObject(
                "org.bluez",
                devicePath,
                Device1::class.java,
                false,
            )
            BluetoothDevice(rawDevice, adapter, devicePath, dm.dbusConnection)
        } catch (e: Throwable) {
            logger.v { "device $address is not present at $devicePath: ${e.message}" }
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
        val dm = deviceManagerOrNull() ?: return false
        val adapter = defaultAdapter() ?: return false
        val device = findDevice(address, adapter, dm) ?: run {
            logger.d { "removeBond($address): not in BlueZ's tree; nothing to remove" }
            return false
        }
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
    internal fun findDeviceRediscovering(
        address: String,
        timeoutMs: Long = 10_000,
        transport: BluezDiscoveryTransport = BluezDiscoveryTransport.Auto,
    ): BluetoothDevice? {
        val dm = deviceManagerOrNull() ?: return null
        val adapter = defaultAdapter() ?: return null
        findDevice(address, adapter, dm)?.let { return it }
        val lease = runCatching {
            logger.i { "$address not in BlueZ tree; discovering to find it" }
            acquireDiscovery(
                adapter = adapter,
                filter = mapOf(
                    "Transport" to Variant(transport.filterValue),
                    "DuplicateData" to Variant(true),
                ),
            )
        }.onFailure {
            logger.e("could not start discovery for $address", it)
        }.getOrNull() ?: return null
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1_000)
                findDevice(address, adapter, dm)?.let { return it }
            }
            // Distinguishes "watch stopped advertising" (others visible) from a wedged
            // controller RX path (tree stays empty even with devices nearby).
            val treeSize = runCatching { devicesOnDefaultAdapter().size }.getOrNull()
            logger.w { "$address not found after ${timeoutMs}ms discovery; BlueZ tree has $treeSize device(s)" }
            return null
        } finally {
            lease.close()
        }
    }

    internal fun acquireDiscovery(
        adapter: BluetoothAdapter,
        filter: Map<String, Variant<*>>,
        cancelled: () -> Boolean = { false },
    ): BluezDiscoveryCoordinator.Lease? = discoveryCoordinator.acquire(
        cancelled = cancelled,
        start = {
            // Clear an untracked session left by an older scanner implementation before
            // establishing the coordinator-owned generation.
            stopDiscoverySession(adapter)
            adapter.setDiscoveryFilter(filter)
            // BluetoothAdapter.startDiscovery() skips the D-Bus call whenever the global
            // Discovering property is already true because another client is scanning. We need
            // an ownership reference for this sender, so call Adapter1 directly.
            adapter.rawAdapter.StartDiscovery()
        },
        stop = { stopDiscoverySession(adapter) },
    )

    /** Stop this process's current discovery owner before a connection attempt. */
    internal fun stopDiscoveryForConnection() {
        val adapter = defaultAdapter()
        discoveryCoordinator.stopCurrent {
            adapter?.let(::stopDiscoverySession)
        }
    }

    /** Run potentially blocking RemoveMatch/StopNotify cleanup away from D-Bus callbacks. */
    internal fun runCleanup(block: () -> Unit) {
        cleanupWorker.execute {
            runCatching(block).onFailure { logger.e("BlueZ cleanup failed", it) }
        }
    }

    internal fun closeSignalRegistrations(registrations: Collection<AutoCloseable>) {
        if (registrations.isEmpty()) return
        val registrationsToClose = registrations.toList()
        runCleanup {
            registrationsToClose.asReversed().forEach { registration ->
                runCatching { registration.close() }
                    .onFailure { logger.e("could not remove BlueZ signal handler", it) }
            }
        }
    }

    private fun stopDiscoverySession(adapter: BluetoothAdapter) {
        runCatching { adapter.rawAdapter.StopDiscovery() }
            .onFailure { logger.v { "StopDiscovery ignored: ${it.message}" } }
        runCatching { adapter.setDiscoveryFilter(emptyMap()) }
            .onFailure { logger.v { "could not reset discovery filter: ${it.message}" } }
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

internal enum class BluezDiscoveryTransport(val filterValue: String) {
    Auto("auto"),
    Bredr("bredr"),
}
