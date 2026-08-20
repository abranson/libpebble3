package io.rebble.libpebblecommon.connection.bt.classic.transport

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.PebbleScanResult
import io.rebble.libpebblecommon.connection.asPebbleBtClassicIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.bluez.BluezManager
import io.rebble.libpebblecommon.connection.bt.ble.bluez.addBluezSigHandler
import io.rebble.libpebblecommon.connection.bt.ble.bluez.isObjectOnBluezAdapter
import io.rebble.libpebblecommon.connection.bt.classic.PEBBLE_NAME_REGEX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.freedesktop.dbus.handlers.AbstractInterfacesAddedHandler
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val DEVICE_INTERFACE = "org.bluez.Device1"
private const val RSSI_UNKNOWN = -100
private const val CLASSIC_PEBBLE_SERVICE_UUID = "00000000-deca-fade-deca-deafdecacaff"
private val CLASSIC_DEVICE_PATH_REGEX = Regex("dev_((?:[0-9A-Fa-f]{2}_){5}[0-9A-Fa-f]{2})$")

/** BlueZ reports zero for [Device1.Class] when a device has no BR/EDR class. */
internal data class BluezClassicDeviceState(
    var address: String? = null,
    var name: String? = null,
    var alias: String? = null,
    var rssi: Int? = null,
    var deviceClass: Int? = null,
    var uuids: Set<String> = emptySet(),
)

internal fun addressFromClassicDevicePath(path: String): String? =
    CLASSIC_DEVICE_PATH_REGEX.find(path)?.groupValues?.get(1)?.replace('_', ':')?.uppercase()

/** Merge partial Device1 updates: BlueZ commonly reports name, class and RSSI separately. */
internal fun BluezClassicDeviceState.merge(properties: Map<String, Variant<*>>) {
    (properties["Address"]?.value as? String)?.let { address = it.uppercase() }
    (properties["Name"]?.value as? String)?.let { name = it }
    (properties["Alias"]?.value as? String)?.let { alias = it }
    (properties["RSSI"]?.value as? Number)?.let { rssi = it.toInt() }
    (properties["Class"]?.value as? Number)?.let { deviceClass = it.toInt() }
    properties["UUIDs"]?.value?.let { value ->
        uuids = when (value) {
            is Array<*> -> value.filterIsInstance<String>()
            is Iterable<*> -> value.filterIsInstance<String>()
            else -> emptyList()
        }.mapTo(hashSetOf()) { it.lowercase() }
    }
}

internal fun BluezClassicDeviceState.invalidate(properties: Collection<String>) {
    if ("Address" in properties) address = null
    if ("Name" in properties) name = null
    if ("Alias" in properties) alias = null
    if ("RSSI" in properties) rssi = null
    if ("Class" in properties) deviceClass = null
    if ("UUIDs" in properties) uuids = emptySet()
}

/** Prefer Pebble's exact SDP service, retaining Class as a discovery-time fallback. */
internal fun BluezClassicDeviceState.isClassicPebble(): Boolean =
    PEBBLE_NAME_REGEX.matches(name ?: alias.orEmpty()) &&
        (CLASSIC_PEBBLE_SERVICE_UUID in uuids || deviceClass != null && deviceClass != 0)

/**
 * BR/EDR scanner over BlueZ D-Bus. Discovery and all signal processing are constrained to the
 * adapter selected by [BluezManager], so a physical and virtual controller cannot cross-feed
 * scan results.
 */
class JvmClassicScanner : ClassicScanner {
    private val logger = Logger.withTag("JvmClassicScanner")

    override fun scan(): Flow<PebbleScanResult> = callbackFlow {
        val deviceManager = BluezManager.deviceManagerOrNull()
        val adapter = BluezManager.defaultAdapter()
        if (deviceManager == null || adapter == null) {
            logger.w { "Classic scan unavailable: BlueZ/D-Bus not reachable" }
            close()
            return@callbackFlow
        }

        val devices = ConcurrentHashMap<String, BluezClassicDeviceState>()
        val fetchRequests = Channel<String>(Channel.UNLIMITED)
        val fetchRequested = ConcurrentHashMap.newKeySet<String>()

        fun update(path: String, properties: Map<String, Variant<*>>, source: String) {
            if (!isObjectOnBluezAdapter(path, adapter.dbusPath)) return
            val pathAddress = addressFromClassicDevicePath(path) ?: return
            val state = devices.computeIfAbsent(path) {
                BluezClassicDeviceState(address = pathAddress)
            }
            val result = synchronized(state) {
                state.merge(properties)
                // The address is part of BlueZ's deterministic object path. Do not let a
                // malformed or stale property update turn this object into another device.
                if (!state.address.equals(pathAddress, ignoreCase = true)) {
                    logger.w { "$source reported mismatched address for $path: ${state.address}" }
                    return@synchronized null
                }
                if (!state.isClassicPebble()) return@synchronized null
                (state.name ?: state.alias ?: return@synchronized null) to state.rssi
            }
            if (result == null) {
                if (fetchRequested.add(path)) fetchRequests.trySend(path)
                return
            }
            val (name, rssi) = result
            trySend(
                PebbleScanResult(
                    identifier = pathAddress.asPebbleBtClassicIdentifier(),
                    name = name,
                    rssi = rssi ?: RSSI_UNKNOWN,
                    leScanRecord = null,
                )
            )
        }

        // Read complete state off D-Bus signal-dispatch threads whenever a partial update is
        // insufficient to classify the device.
        launch(Dispatchers.IO) {
            for (path in fetchRequests) {
                try {
                    val properties = deviceManager.dbusConnection
                        .getRemoteObject("org.bluez", path, Properties::class.java)
                        .GetAll(DEVICE_INTERFACE)
                    update(path, properties, "GetAll")
                } catch (e: Throwable) {
                    logger.v { "GetAll failed for $path: ${e.message}" }
                } finally {
                    // A later signal may invalidate or complete this snapshot. Permit one fresh
                    // GetAll for that signal without recursively refetching an incomplete reply.
                    fetchRequested.remove(path)
                }
            }
        }

        val propertiesHandler = object : AbstractPropertiesChangedHandler() {
            override fun handle(signal: Properties.PropertiesChanged) {
                if (signal.interfaceName == DEVICE_INTERFACE) {
                    devices[signal.path]?.let { state ->
                        synchronized(state) { state.invalidate(signal.propertiesRemoved) }
                    }
                    update(signal.path, signal.propertiesChanged, "PropertiesChanged")
                }
            }
        }
        val interfacesAddedHandler = object : AbstractInterfacesAddedHandler() {
            override fun handle(signal: ObjectManager.InterfacesAdded) {
                val properties = signal.interfaces[DEVICE_INTERFACE] ?: return
                val path = listOfNotNull(signal.signalSource?.path, signal.objectPath)
                    .firstOrNull { addressFromClassicDevicePath(it) != null }
                    ?: return
                update(path, properties, "InterfacesAdded")
            }
        }

        val signalRegistrations = mutableListOf<AutoCloseable>()
        try {
            signalRegistrations += deviceManager.dbusConnection.addBluezSigHandler(
                Properties.PropertiesChanged::class.java,
                propertiesHandler,
            )
            signalRegistrations += deviceManager.dbusConnection.addBluezSigHandler(
                ObjectManager.InterfacesAdded::class.java,
                interfacesAddedHandler,
            )
        } catch (e: Throwable) {
            BluezManager.closeSignalRegistrations(signalRegistrations)
            logger.e("error registering classic scan signal handlers", e)
            close(e)
            return@callbackFlow
        }

        val leaseLock = Any()
        val closed = AtomicBoolean(false)
        var discoveryLease: AutoCloseable? = null
        launch(Dispatchers.IO) {
            try {
                // Do not seed bluez-dbus's address-keyed device cache: two controllers can
                // expose the same address, and this scan must see only fresh objects below the
                // adapter captured above.
                val acquired = BluezManager.acquireDiscovery(
                    adapter = adapter,
                    filter = mapOf(
                        "Transport" to Variant("bredr"),
                        "DuplicateData" to Variant(true),
                    ),
                    cancelled = closed::get,
                )
                if (acquired == null) return@launch
                val closeImmediately = synchronized(leaseLock) {
                    if (closed.get()) {
                        true
                    } else {
                        discoveryLease = acquired
                        false
                    }
                }
                if (closeImmediately) acquired.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.e("startDiscovery failed", e)
                if (!closed.get()) close(e)
            }
        }

        awaitClose {
            closed.set(true)
            synchronized(leaseLock) {
                discoveryLease.also { discoveryLease = null }
            }?.let { runCatching { it.close() } }
            BluezManager.closeSignalRegistrations(signalRegistrations)
            fetchRequests.close()
        }
    }
}
