package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import co.touchlab.kermit.Logger
import com.juul.kable.ManufacturerData
import io.rebble.libpebblecommon.connection.BleScanResult
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.bluez.BluezManager
import io.rebble.libpebblecommon.connection.bt.ble.transport.BleScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.handlers.AbstractInterfacesAddedHandler
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.ConcurrentHashMap

private const val DEVICE_INTERFACE = "org.bluez.Device1"
private const val RSSI_UNKNOWN = -100

private val DEVICE_PATH_REGEX = Regex("dev_((?:[0-9A-Fa-f]{2}_){5}[0-9A-Fa-f]{2})$")

internal fun addressFromDevicePath(path: String): String? =
    DEVICE_PATH_REGEX.find(path)?.groupValues?.get(1)?.replace('_', ':')?.uppercase()

/**
 * BLE scanner over BlueZ D-Bus: StartDiscovery + InterfacesAdded/PropertiesChanged signals.
 *
 * BlueZ delivers a device's name / manufacturer data / RSSI incrementally across signals, so
 * per-device state is accumulated and a scan result emitted whenever we have name + mfg data
 * (mirroring what the Kable backends provide in one Advertisement). As a belt-and-braces
 * fallback, the first signal seen for a device also triggers a Properties.GetAll on it, so we
 * don't depend on the signal sequence carrying every property.
 */
internal class BluezBleScanner : BleScanner {
    private val logger = Logger.withTag("BluezBleScanner")

    private class AdvState(val address: String) {
        var name: String? = null
        var rssi: Int? = null
        var manufacturerData: ManufacturerData? = null
    }

    override fun scan(): Flow<BleScanResult> = callbackFlow {
        val dm = BluezManager.deviceManagerOrNull()
        val adapter = BluezManager.defaultAdapter()
        if (dm == null || adapter == null) {
            logger.w { "BLE scan unavailable: BlueZ/D-Bus not reachable" }
            close()
            return@callbackFlow
        }

        logger.d { "scanning on adapter=${adapter.dbusPath}" }

        val advState = ConcurrentHashMap<String, AdvState>()
        val fetchRequests = Channel<String>(capacity = Channel.UNLIMITED)
        val fetchRequested = ConcurrentHashMap.newKeySet<String>()
        val loggedQuietPebbles = ConcurrentHashMap.newKeySet<String>()

        fun update(path: String, props: Map<String, Variant<*>>, source: String) {
            val address = addressFromDevicePath(path) ?: return
            logger.v { "$source $path: ${props.keys}" }
            val state = advState.computeIfAbsent(path) { AdvState(address) }
            (props["Name"]?.value as? String)?.let { state.name = it }
            (props["Alias"]?.value as? String)?.let { if (state.name == null) state.name = it }
            (props["RSSI"]?.value as? Number)?.let { state.rssi = it.toInt() }
            props["ManufacturerData"]?.let { v ->
                unwrapManufacturerData(v.value)?.let { state.manufacturerData = it }
            }

            val name = state.name
            val mfg = state.manufacturerData
            if (name == null || mfg == null) {
                // Signal didn't carry everything we need — fetch the full property set once.
                if (fetchRequested.add(path)) fetchRequests.trySend(path)
                if (name != null && name.startsWith("Pebble") && loggedQuietPebbles.add(path)) {
                    logger.i {
                        "found Pebble '$name' at ${state.address} but no BLE advertisement " +
                            "(cached/bonded device, classic-only watch, or not advertising)"
                    }
                }
                return
            }
            trySend(
                BleScanResult(
                    identifier = PebbleBleIdentifier(address),
                    name = name,
                    rssi = state.rssi ?: RSSI_UNKNOWN,
                    manufacturerData = mfg,
                )
            )
        }

        // GetAll fallback: runs blocking D-Bus calls off the signal-dispatch threads.
        launch(Dispatchers.IO) {
            for (path in fetchRequests) {
                try {
                    val props = dm.dbusConnection
                        .getRemoteObject("org.bluez", path, Properties::class.java)
                        .GetAll(DEVICE_INTERFACE)
                    update(path, props, "GetAll")
                } catch (e: Throwable) {
                    logger.v { "GetAll failed for $path: ${e.message}" }
                }
            }
        }

        val propertiesHandler = object : AbstractPropertiesChangedHandler() {
            override fun handle(signal: Properties.PropertiesChanged) {
                if (signal.interfaceName != DEVICE_INTERFACE) return
                update(signal.path, signal.propertiesChanged, "PropertiesChanged")
            }
        }
        val interfacesAddedHandler = object : AbstractInterfacesAddedHandler() {
            override fun handle(signal: ObjectManager.InterfacesAdded) {
                val deviceProps = signal.interfaces[DEVICE_INTERFACE] ?: return
                // dbus-java field naming is confusing here; accept whichever field holds
                // an object path that looks like a device.
                val path = listOfNotNull(signal.signalSource?.path, signal.objectPath)
                    .firstOrNull { addressFromDevicePath(it) != null }
                logger.v { "InterfacesAdded source=${signal.signalSource?.path} objectPath=${signal.objectPath}" }
                if (path == null) return
                update(path, deviceProps, "InterfacesAdded")
            }
        }

        try {
            dm.registerPropertyHandler(propertiesHandler)
            dm.registerSignalHandler(interfacesAddedHandler)
        } catch (e: Throwable) {
            logger.e("error registering scan signal handlers", e)
            close(e)
            return@callbackFlow
        }

        // Setup runs in a child coroutine so the flow body reaches awaitClose immediately:
        // these are blocking, non-cancellable D-Bus calls, and running them inline meant a
        // cancellation mid-setup skipped awaitClose while startDiscovery still went through —
        // leaking a discovery session nobody would ever stop (which then starves connects).
        val discoveryLock = Any()
        var closed = false
        var discoveryStarted = false
        launch(Dispatchers.IO) {
            // Seed with devices BlueZ already knows about (bonded or recently seen); the
            // GetAll fallback fills in whatever properties are cached. true => re-introspect;
            // getDevices(false) would return bluez-dbus's populate-once cache, stale from the
            // second scan onwards.
            try {
                dm.getDevices(true).forEach { device ->
                    val path = device.dbusPath ?: return@forEach
                    if (fetchRequested.add(path)) fetchRequests.trySend(path)
                }
            } catch (e: Throwable) {
                logger.w("error seeding known devices", e)
            }

            try {
                // Transport=auto so classic-only Pebbles are at least *seen* and logged
                // (connections stay BLE-only). DuplicateData keeps RSSI/mfg-data updates
                // flowing for already-seen devices.
                adapter.setDiscoveryFilter(
                    mapOf(
                        "Transport" to Variant("auto"),
                        "DuplicateData" to Variant(true),
                    )
                )
            } catch (e: Throwable) {
                logger.w("setDiscoveryFilter failed (continuing without filter)", e)
            }
            synchronized(discoveryLock) {
                if (closed) return@launch
                try {
                    val started = adapter.startDiscovery()
                    discoveryStarted = true
                    logger.d { "startDiscovery -> $started" }
                } catch (e: Throwable) {
                    logger.e("startDiscovery failed", e)
                    close(e)
                    return@launch
                }
            }
            delay(1500)
            logger.d {
                "discovering=${runCatching { adapter.isDiscovering }.getOrNull()} " +
                    "powered=${runCatching { adapter.isPowered }.getOrNull()} " +
                    "devicesSeen=${advState.size}"
            }
        }

        awaitClose {
            synchronized(discoveryLock) {
                closed = true
                if (discoveryStarted) runCatching { adapter.stopDiscovery() }
            }
            runCatching { dm.unRegisterPropertyHandler(propertiesHandler) }
            runCatching { dm.unRegisterSignalHandler(interfacesAddedHandler) }
            fetchRequests.close()
        }
    }

    private fun unwrapManufacturerData(raw: Any?): ManufacturerData? {
        val map = raw as? Map<*, *> ?: return null
        val entry = map.entries.firstOrNull() ?: return null
        val code = (entry.key as? Number)?.toInt() ?: return null
        val value = entry.value.let { if (it is Variant<*>) it.value else it }
        val bytes = when (value) {
            is ByteArray -> value
            is List<*> -> ByteArray(value.size) { i -> (value[i] as Number).toByte() }
            else -> return null
        }
        return ManufacturerData(code, bytes)
    }
}
