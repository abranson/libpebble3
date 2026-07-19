package io.rebble.libpebblecommon.connection.bt.ble.transport.impl

import co.touchlab.kermit.Logger
import com.github.hypfvieh.bluetooth.DeviceManager
import com.github.hypfvieh.bluetooth.wrapper.BluetoothDevice
import com.github.hypfvieh.bluetooth.wrapper.BluetoothGattCharacteristic
import com.github.hypfvieh.bluetooth.wrapper.BluetoothGattService
import io.rebble.libpebblecommon.connection.ConnectionFailureReason
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.bluez.BluezManager
import io.rebble.libpebblecommon.connection.bt.ble.transport.ConnectedGattClient
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattCharacteristic
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattConnectionResult
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattConnector
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattDescriptor
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattService
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattWriteType
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.bluez.Adapter1
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.errors.NoReply
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private const val DEVICE_INTERFACE = "org.bluez.Device1"
private const val GATT_CHARACTERISTIC_INTERFACE = "org.bluez.GattCharacteristic1"

/** ATT default when BlueZ doesn't expose the negotiated MTU (property exists since 5.62). */
private const val DEFAULT_ATT_MTU = 23

/**
 * GATT central over BlueZ D-Bus, replacing Kable/btleplug on jvm/Linux (whose prebuilt
 * native library needs a newer glibc than Sailfish OS ships). Blocking D-Bus calls are
 * confined to Dispatchers.IO.
 */
internal class BluezGattConnector(
    private val identifier: PebbleBleIdentifier,
    private val scope: ConnectionCoroutineScope,
) : GattConnector {
    private val logger = Logger.withTag("BluezGattConnector/${identifier.asString}")

    private val _disconnected = CompletableDeferred<ConnectionFailureReason>()
    override val disconnected: Deferred<ConnectionFailureReason> = _disconnected

    private var device: BluetoothDevice? = null
    private var deviceManager: DeviceManager? = null
    private var disconnectionHandler: AbstractPropertiesChangedHandler? = null
    @Volatile
    private var attemptedConnection = false

    override suspend fun connect(): GattConnectionResult = withContext(Dispatchers.IO) {
        val dm = BluezManager.deviceManagerOrNull()
            ?: return@withContext failure(ConnectionFailureReason.FailedToConnect)
        deviceManager = dm
        // A watch that was advertising seconds ago and now can't be found usually means the
        // controller's RX path wedged, not that the watch went away. Power-cycle the adapter
        // once and look again before giving up.
        val dev = BluezManager.findDeviceRediscovering(identifier.asString)
            ?: (if (BluezManager.powerCycleAdapter()) {
                BluezManager.findDeviceRediscovering(identifier.asString)
            } else {
                null
            })
            ?: run {
                logger.w { "device not known to BlueZ and not found by discovery (not advertising?)" }
                return@withContext failure(ConnectionFailureReason.FailedToConnect)
            }
        device = dev
        val devicePath = dev.dbusPath

        // Pebble advertisements lack the "BR/EDR Not Supported" flag, so BlueZ marks the
        // watch dual-mode and Connect() pages the (nonexistent) classic radio until it
        // times out — LE is never attempted (btmon: Create Connection → Page Timeout).
        // PreferredBearer (BlueZ >= 5.79) forces the LE path; on older BlueZ (Sailfish
        // ships 5.66) the only bypass is the experimental Adapter1.ConnectDevice, used
        // below for unbonded watches. Once bonded, select_conn_bearer prefers the bonded
        // LE bearer and plain Connect() works everywhere.
        val leBearerForced = runCatching {
            dm.dbusConnection.getRemoteObject("org.bluez", devicePath, Properties::class.java)
                .Set(DEVICE_INTERFACE, "PreferredBearer", "le")
        }.onFailure { logger.d { "couldn't set PreferredBearer=le: ${it.message}" } }.isSuccess

        val handler = object : AbstractPropertiesChangedHandler() {
            override fun handle(signal: Properties.PropertiesChanged) {
                if (signal.path != devicePath) return
                if (signal.interfaceName != DEVICE_INTERFACE) return
                val connected = signal.propertiesChanged["Connected"]?.value as? Boolean ?: return
                if (!connected) {
                    logger.i { "BlueZ reports device disconnected" }
                    _disconnected.complete(ConnectionFailureReason.FailedToConnect)
                }
            }
        }
        try {
            dm.registerPropertyHandler(handler)
            disconnectionHandler = handler
        } catch (e: Throwable) {
            logger.e("error registering disconnection handler", e)
            return@withContext failure(ConnectionFailureReason.FailedToConnect)
        }

        // Active discovery starves LE connection attempts on this controller; make sure
        // it's off no matter who started it.
        runCatching {
            BluezManager.defaultAdapter()?.takeIf { it.isDiscovering }?.let {
                logger.d { "stopping discovery before connect" }
                it.stopDiscovery()
            }
        }

        attemptedConnection = true
        val bonded = runCatching { dev.isPaired }.getOrNull() == true
        val connectResult = withTimeoutOrNull(CONNECT_TIMEOUT) {
            if (leBearerForced || bonded) {
                // dev.connect() is a blocking D-Bus call with BlueZ's own internal timeout;
                // the outer timeout is a backstop for a wedged bluetoothd.
                runCatching { dev.connect() }.getOrElse { e ->
                    if (e is NoReply) {
                        // bluetoothd keeps establishing the connection after the D-Bus reply
                        // timeout; wait for the Connected property instead of giving up.
                        logger.w { "Connect() reply timed out; waiting for Connected property" }
                        waitUntilConnected(dev)
                        true
                    } else {
                        logger.e("Connect() failed", e)
                        false
                    }
                }
            } else {
                connectDeviceLe(dm, dev)
            }
        }
        when (connectResult) {
            null -> {
                logger.w { "connect timed out" }
                runCatching { dev.disconnect() }
                return@withContext failure(ConnectionFailureReason.ConnectTimeout)
            }
            false -> return@withContext failure(ConnectionFailureReason.FailedToConnect)
            else -> {}
        }

        val resolved = withTimeoutOrNull(SERVICES_RESOLVED_TIMEOUT) {
            while (runCatching { dev.isServicesResolved }.getOrNull() != true) {
                delay(200)
            }
            true
        } ?: false
        if (!resolved) {
            logger.w { "services never resolved" }
            runCatching { dev.disconnect() }
            return@withContext failure(ConnectionFailureReason.ConnectTimeout)
        }

        GattConnectionResult.Success(BluezConnectedGattClient(identifier, dev, dm, logger))
    }

    private fun failure(reason: ConnectionFailureReason): GattConnectionResult.Failure {
        _disconnected.complete(reason)
        return GattConnectionResult.Failure(reason)
    }

    private suspend fun waitUntilConnected(dev: BluetoothDevice) {
        var waitedMs = 0
        while (runCatching { dev.isConnected }.getOrNull() != true) {
            delay(500)
            waitedMs += 500
            if (waitedMs % 10_000 == 0) {
                logger.d { "still waiting for Connected (${waitedMs / 1000}s)" }
            }
        }
    }

    /**
     * LE connect for BlueZ without PreferredBearer: Adapter1.ConnectDevice connects with an
     * explicit LE address type, bypassing the BR/EDR-biased bearer selection. It refuses to
     * touch an existing device object, so the (unbonded, temporary) one is removed first —
     * BlueZ recreates it at the same object path, keeping [dev]'s path-based proxy valid.
     * ConnectDevice is an experimental method: hidden unless bluetoothd runs with -E /
     * Experimental=true (the libpebble3d RPM ships a bluetooth.service drop-in for this).
     */
    private suspend fun connectDeviceLe(dm: DeviceManager, dev: BluetoothDevice): Boolean {
        val adapter = BluezManager.defaultAdapter() ?: return false
        logger.i { "no PreferredBearer (BlueZ < 5.79); LE connect via Adapter1.ConnectDevice" }
        val rawAdapter = dm.dbusConnection.getRemoteObject(
            "org.bluez", adapter.dbusPath, Adapter1::class.java,
        )
        purgeStaleDevice(rawAdapter, dev)
        return try {
            rawAdapter.ConnectDevice(
                mapOf(
                    "Address" to Variant(identifier.asString),
                    "AddressType" to Variant("public"),
                )
            )
            true
        } catch (e: NoReply) {
            logger.w { "ConnectDevice reply timed out; waiting for Connected property" }
            waitUntilConnected(dev)
            true
        } catch (e: Exception) {
            logger.e {
                "ConnectDevice failed: ${e.message} — if the method is missing, bluetoothd " +
                    "needs experimental APIs enabled (libpebble3d installs a " +
                    "bluetooth.service drop-in; check TRACING=-E is in effect and " +
                    "bluetooth.service was restarted)"
            }
            false
        }
    }

    /**
     * Fully drop a stale device object before a fresh [connectDeviceLe] pair, the way a manual
     * `bluetoothctl remove` does.
     *
     * After a forget+removeBond, BlueZ often still holds a cached device object with a partial
     * GATT database. A plain RemoveDevice-then-ConnectDevice reuses it: services resolve, but the
     * pairing characteristics (e.g. 00000005-…) are missing and the bond fails with Authentication
     * Failed, looping forever. Three things the fire-and-forget path skipped make it stick:
     * disconnect first (BlueZ won't fully clear a connected object — and the watch auto-reconnects
     * here), confirm the removal actually took, and let bluetoothd finish purging its GATT cache
     * before reconnecting.
     *
     * Only reached on the unbonded branch (caller gated on `!bonded`), so a normal bonded
     * reconnect — which must keep its bond — never lands here.
     */
    private suspend fun purgeStaleDevice(rawAdapter: Adapter1, dev: BluetoothDevice) {
        val address = identifier.asString
        if (runCatching { dev.isConnected }.getOrNull() == true) {
            runCatching { dev.disconnect() }
            withTimeoutOrNull(DISCONNECT_BEFORE_REMOVE) {
                while (runCatching { dev.isConnected }.getOrNull() == true) delay(100)
            }
        }
        val removed = runCatching { rawAdapter.RemoveDevice(DBusPath(dev.dbusPath)) }
            .onFailure { logger.w { "RemoveDevice failed: ${it.message}" } }
            .isSuccess
        // The watch keeps advertising, so BlueZ may re-add a *fresh* object almost at once; what
        // matters is that the old one was actually torn down, which its brief disappearance
        // confirms. Best-effort — proceed regardless, but the settle gives the cache purge time.
        if (removed) {
            withTimeoutOrNull(REMOVE_SETTLE) {
                while (BluezManager.findDevice(address) != null) delay(100)
            }
        }
        delay(REMOVE_SETTLE_MIN)
    }

    override suspend fun disconnect() {
        logger.d { "disconnect()" }
        withContext(Dispatchers.IO) {
            runCatching { device?.disconnect() }
        }
        if (!attemptedConnection) {
            _disconnected.complete(ConnectionFailureReason.NotAnError_NeverAttmpedConnection)
        }
    }

    override fun close() {
        val dm = deviceManager
        val handler = disconnectionHandler
        if (dm != null && handler != null) {
            // Unregistering synchronously can deadlock when called from a signal-dispatch
            // thread; hop to the connection scope.
            scope.launch(Dispatchers.IO) {
                runCatching { dm.unRegisterPropertyHandler(handler) }
            }
            disconnectionHandler = null
        }
    }

    companion object {
        // Must comfortably exceed the D-Bus reply timeout (30s, set in BluezManager) so the
        // NoReply → wait-for-Connected fallback gets a real window before the backstop fires.
        private val CONNECT_TIMEOUT = 120.seconds
        private val SERVICES_RESOLVED_TIMEOUT = 30.seconds

        // Purge-before-repair pacing (see purgeStaleDevice).
        private val DISCONNECT_BEFORE_REMOVE = 3.seconds
        private val REMOVE_SETTLE = 2.seconds
        // A floor even when RemoveDevice reports the object already gone, so bluetoothd still gets
        // a beat to drop its cached GATT database before ConnectDevice rediscovers.
        private val REMOVE_SETTLE_MIN = 500.milliseconds
    }
}

internal class BluezConnectedGattClient(
    private val identifier: PebbleBleIdentifier,
    private val device: BluetoothDevice,
    private val dm: DeviceManager,
    private val logger: Logger,
) : ConnectedGattClient {

    private var _services: List<GattService>? = null

    override suspend fun discoverServices(): Boolean = withContext(Dispatchers.IO) {
        try {
            device.refreshGattServices()
            _services = device.gattServices.map { it.asGattService() }
            true
        } catch (e: Throwable) {
            logger.e("error discovering services", e)
            false
        }
    }

    override val services: List<GattService>?
        get() = _services

    // BlueZ has no separate "drop the GATT cache" call beyond re-reading the database, so this is
    // the same as a fresh discovery.
    override suspend fun refreshServicesNative(): Boolean = discoverServices()

    override fun subscribeToCharacteristic(
        serviceUuid: Uuid,
        characteristicUuid: Uuid,
        onSubscription: (suspend () -> Unit)?,
    ): Flow<ByteArray>? {
        val characteristic = findCharacteristic(serviceUuid, characteristicUuid) ?: run {
            logger.e { "couldn't find characteristic to subscribe: $characteristicUuid" }
            return null
        }
        val path = characteristic.dbusPath
        return callbackFlow {
            val handler = object : AbstractPropertiesChangedHandler() {
                override fun handle(signal: Properties.PropertiesChanged) {
                    if (signal.path != path) return
                    if (signal.interfaceName != GATT_CHARACTERISTIC_INTERFACE) return
                    val value = signal.propertiesChanged["Value"]?.value ?: return
                    valueAsBytes(value)?.let { trySend(it) }
                }
            }
            try {
                dm.registerPropertyHandler(handler)
                withContext(Dispatchers.IO) { characteristic.startNotify() }
                onSubscription?.invoke()
            } catch (e: Throwable) {
                logger.e("error subscribing to $characteristicUuid", e)
                close(e)
            }
            awaitClose {
                runCatching { characteristic.stopNotify() }
                runCatching { dm.unRegisterPropertyHandler(handler) }
            }
        }
    }

    override suspend fun isBonded(): Boolean =
        io.rebble.libpebblecommon.connection.bt.isBonded(identifier)

    override suspend fun writeCharacteristic(
        serviceUuid: Uuid,
        characteristicUuid: Uuid,
        value: ByteArray,
        writeType: GattWriteType,
    ): Boolean = withContext(Dispatchers.IO) {
        val characteristic = findCharacteristic(serviceUuid, characteristicUuid) ?: run {
            logger.e { "couldn't find characteristic to write: $characteristicUuid" }
            return@withContext false
        }
        try {
            characteristic.writeValue(value, mapOf("type" to writeType.asBluezWriteType()))
            true
        } catch (e: Throwable) {
            logger.v("error writing characteristic", e)
            false
        }
    }

    override suspend fun readCharacteristic(
        serviceUuid: Uuid,
        characteristicUuid: Uuid,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val characteristic = findCharacteristic(serviceUuid, characteristicUuid) ?: run {
            logger.e { "couldn't find characteristic to read: $characteristicUuid" }
            return@withContext null
        }
        try {
            characteristic.readValue(emptyMap())
        } catch (e: Throwable) {
            logger.v("error reading characteristic", e)
            null
        }
    }

    override suspend fun requestMtu(mtu: Int): Int = getMtu() // BlueZ negotiates MTU itself.

    override suspend fun getMtu(): Int = withContext(Dispatchers.IO) {
        // GattCharacteristic1 exposes the negotiated ATT MTU as an "MTU" property (BlueZ 5.62+).
        val characteristic = _services
            ?.firstOrNull { it.characteristics.isNotEmpty() }
            ?.let { findCharacteristic(it.uuid, it.characteristics.first().uuid) }
            ?: return@withContext DEFAULT_ATT_MTU
        try {
            val props = dm.dbusConnection.getRemoteObject(
                "org.bluez",
                characteristic.dbusPath,
                Properties::class.java,
            )
            (props.Get<Any>(GATT_CHARACTERISTIC_INTERFACE, "MTU") as? Number)?.toInt()
                ?: DEFAULT_ATT_MTU
        } catch (e: Throwable) {
            logger.w { "couldn't read MTU property (BlueZ < 5.62?): ${e.message}" }
            DEFAULT_ATT_MTU
        }
    }

    override fun close() {
        runCatching { device.disconnect() }
    }

    private fun findCharacteristic(
        serviceUuid: Uuid,
        characteristicUuid: Uuid,
    ): BluetoothGattCharacteristic? =
        device.getGattServiceByUuid(serviceUuid.toString())
            ?.getGattCharacteristicByUuid(characteristicUuid.toString())

    private fun valueAsBytes(value: Any?): ByteArray? {
        val unwrapped = if (value is Variant<*>) value.value else value
        return when (unwrapped) {
            is ByteArray -> unwrapped
            is List<*> -> ByteArray(unwrapped.size) { i -> (unwrapped[i] as Number).toByte() }
            else -> null
        }
    }
}

private fun GattWriteType.asBluezWriteType(): String = when (this) {
    GattWriteType.WithResponse -> "request"
    GattWriteType.NoResponse -> "command"
}

private fun BluetoothGattService.asGattService(): GattService = GattService(
    uuid = Uuid.parse(uuid),
    characteristics = gattCharacteristics.map { c ->
        GattCharacteristic(
            uuid = Uuid.parse(c.uuid),
            properties = c.flags.asCharacteristicProperties(),
            permissions = 0,
            descriptors = c.gattDescriptors.map { d ->
                GattDescriptor(uuid = Uuid.parse(d.uuid), permissions = 0)
            },
        )
    },
)

/** Map BlueZ characteristic flag strings onto standard GATT property bits. */
private fun List<String>?.asCharacteristicProperties(): Int {
    var bits = 0
    this?.forEach {
        bits = bits or when (it) {
            "broadcast" -> 0x01
            "read" -> 0x02
            "write-without-response" -> 0x04
            "write" -> 0x08
            "notify" -> 0x10
            "indicate" -> 0x20
            "authenticated-signed-writes" -> 0x40
            "extended-properties" -> 0x80
            else -> 0
        }
    }
    return bits
}
