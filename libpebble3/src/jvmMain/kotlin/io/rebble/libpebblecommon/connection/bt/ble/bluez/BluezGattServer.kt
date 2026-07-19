package io.rebble.libpebblecommon.connection.bt.ble.bluez

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants
import io.rebble.libpebblecommon.connection.bt.ble.transport.SendResult
import io.rebble.libpebblecommon.connection.bt.ble.transport.ServerCharacteristicReadRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.bluez.GattCharacteristic1
import org.bluez.GattManager1
import org.bluez.GattService1
import org.bluez.datatypes.TwoTuple
import org.bluez.exceptions.BluezFailedException
import org.bluez.exceptions.BluezNotPermittedException
import org.bluez.exceptions.BluezNotSupportedException
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.interfaces.ObjectManager
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt16
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

private const val GATT_SERVICE_IFACE = "org.bluez.GattService1"
private const val GATT_CHRC_IFACE = "org.bluez.GattCharacteristic1"

private const val APP_PATH = "/io/rebble/libpebble3/gatt"
private const val SERVICE_PATH = "$APP_PATH/service0"
private const val META_CHAR_PATH = "$SERVICE_PATH/char0"
private const val DATA_CHAR_PATH = "$SERVICE_PATH/char1"

private val READ_TIMEOUT = 8.seconds

/** "/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF" -> "AA:BB:CC:DD:EE:FF". */
internal fun deviceAddressFromPath(path: String?): String? {
    if (path == null) return null
    val marker = path.substringAfterLast("dev_", missingDelimiterValue = "")
    if (marker.isEmpty()) return null
    return marker.replace('_', ':').uppercase()
}

/** Base for an object we export on the bus that exposes typed properties to BlueZ. */
internal abstract class ExportedGattObject(private val path: String) : Properties {
    override fun getObjectPath(): String = path
    override fun isRemote(): Boolean = false

    /** Properties for the given functional interface (e.g. org.bluez.GattCharacteristic1). */
    abstract fun propertiesFor(interfaceName: String): Map<String, Variant<*>>

    @Suppress("UNCHECKED_CAST")
    override fun <A> Get(interfaceName: String, propertyName: String): A =
        propertiesFor(interfaceName)[propertyName]?.value as A

    override fun <A> Set(interfaceName: String, propertyName: String, value: A) {}

    override fun GetAll(interfaceName: String): Map<String, Variant<*>> =
        HashMap(propertiesFor(interfaceName))
}

internal class BluezGattService(
    private val uuid: String,
) : ExportedGattObject(SERVICE_PATH), GattService1 {
    override fun propertiesFor(interfaceName: String): Map<String, Variant<*>> =
        if (interfaceName == GATT_SERVICE_IFACE) {
            mapOf(
                "UUID" to Variant(uuid),
                "Primary" to Variant(true),
            )
        } else {
            emptyMap()
        }
}

/**
 * The encrypted META characteristic (read-only). When the watch reads it, we surface a
 * [ServerCharacteristicReadRequest] and block the D-Bus call until the consumer responds (with
 * SERVER_META_RESPONSE).
 */
internal class BluezMetaCharacteristic(
    private val uuid: String,
    private val onRead: (PebbleBleIdentifier) -> ByteArray,
) : ExportedGattObject(META_CHAR_PATH), GattCharacteristic1 {
    private val logger = Logger.withTag("BluezMetaCharacteristic")

    override fun propertiesFor(interfaceName: String): Map<String, Variant<*>> =
        if (interfaceName == GATT_CHRC_IFACE) {
            mapOf(
                "UUID" to Variant(uuid),
                "Service" to Variant(DBusPath(SERVICE_PATH)),
                "Flags" to Variant(arrayOf("encrypt-read"), "as"),
            )
        } else {
            emptyMap()
        }

    override fun ReadValue(options: Map<String, Variant<*>>): ByteArray {
        val address = deviceAddressFromPath((options["device"]?.value as? DBusPath)?.path)
        val identifier = PebbleBleIdentifier(address ?: "")
        val offset = (options["offset"]?.value as? UInt16)?.toInt() ?: 0
        val full = onRead(identifier)
        // The watch reads this to decide the session type (system vs 3rd-party app). Log the
        // exact bytes + any read offset, since a short/offset read makes our system UUID look
        // like a non-system one and drops us to a public session (music/blobdb denied).
        val result = if (offset in 1..full.size) full.copyOfRange(offset, full.size) else full
        logger.d {
            "meta read by ${identifier.asString} offset=$offset -> ${result.size}B: " +
                result.joinToString(" ") { (it.toInt() and 0xff).toString(16) }
        }
        return result
    }

    override fun WriteValue(value: ByteArray, options: Map<String, Variant<*>>) {
        throw BluezNotPermittedException("meta characteristic is read-only")
    }

    override fun AcquireWrite(options: Map<String, Variant<*>>): TwoTuple<FileDescriptor, UInt16> {
        throw BluezNotSupportedException("not supported")
    }

    override fun AcquireNotify(options: Map<String, Variant<*>>): TwoTuple<FileDescriptor, UInt16> {
        throw BluezNotSupportedException("not supported")
    }

    override fun StartNotify() {
        throw BluezNotSupportedException("meta characteristic does not notify")
    }

    override fun StopNotify() {}
    override fun Confirm() {}
}

/**
 * The PPoGATT data characteristic: the watch writes packets to us (forwarded to the registered
 * device's channel), and we notify it via PropertiesChanged on `Value`.
 */
internal class BluezDataCharacteristic(
    private val uuid: String,
    private val onWrite: (PebbleBleIdentifier, ByteArray) -> Unit,
    private val onSubscriptionChanged: (Boolean) -> Unit,
) : ExportedGattObject(DATA_CHAR_PATH), GattCharacteristic1 {

    @Volatile
    var value: ByteArray = ByteArray(0)

    @Volatile
    var notifying: Boolean = false

    override fun propertiesFor(interfaceName: String): Map<String, Variant<*>> =
        if (interfaceName == GATT_CHRC_IFACE) {
            mapOf(
                "UUID" to Variant(uuid),
                "Service" to Variant(DBusPath(SERVICE_PATH)),
                "Flags" to Variant(arrayOf("write-without-response", "notify", "encrypt-write"), "as"),
                "Notifying" to Variant(notifying),
                "Value" to Variant(value),
            )
        } else {
            emptyMap()
        }

    override fun ReadValue(options: Map<String, Variant<*>>): ByteArray {
        throw BluezNotPermittedException("data characteristic is not readable")
    }

    override fun WriteValue(value: ByteArray, options: Map<String, Variant<*>>) {
        val address = deviceAddressFromPath((options["device"]?.value as? DBusPath)?.path)
        onWrite(PebbleBleIdentifier(address ?: ""), value)
    }

    override fun AcquireWrite(options: Map<String, Variant<*>>): TwoTuple<FileDescriptor, UInt16> {
        throw BluezNotSupportedException("use WriteValue")
    }

    override fun AcquireNotify(options: Map<String, Variant<*>>): TwoTuple<FileDescriptor, UInt16> {
        throw BluezNotSupportedException("use PropertiesChanged notifications")
    }

    override fun StartNotify() {
        notifying = true
        onSubscriptionChanged(true)
    }

    override fun StopNotify() {
        notifying = false
        onSubscriptionChanged(false)
    }

    override fun Confirm() {}
}

/** Root object BlueZ introspects to discover our service hierarchy. */
internal class BluezGattApplication(
    private val children: List<ExportedGattObject>,
    private val interfaceOf: (ExportedGattObject) -> String,
) : ObjectManager {
    override fun getObjectPath(): String = APP_PATH
    override fun isRemote(): Boolean = false

    override fun GetManagedObjects(): Map<DBusPath, Map<String, Map<String, Variant<*>>>> =
        children.associate { child ->
            val iface = interfaceOf(child)
            DBusPath(child.objectPath) to mapOf(iface to child.propertiesFor(iface))
        }
}

/**
 * Coordinates the BlueZ GATT *server*: exports the application/service/characteristic objects,
 * registers them with `GattManager1`, routes watch writes to the per-device channel, and notifies
 * the watch via PropertiesChanged.
 */
internal class BluezGattServer(
    private val serviceUuid: String,
    private val metaCharUuid: String,
    private val dataCharUuid: String,
) {
    private val logger = Logger.withTag("BluezGattServer")

    private val connection: DBusConnection? = BluezManager.connection
    private val devices = ConcurrentHashMap<String, SendChannel<ByteArray>>()

    private val _characteristicReadRequest =
        MutableSharedFlow<ServerCharacteristicReadRequest>(extraBufferCapacity = 8)
    val characteristicReadRequest: Flow<ServerCharacteristicReadRequest> = _characteristicReadRequest

    private val metaCharacteristic = BluezMetaCharacteristic(metaCharUuid) { deviceId ->
        val deferred = CompletableDeferred<ByteArray>()
        val emitted = _characteristicReadRequest.tryEmit(
            ServerCharacteristicReadRequest(
                deviceId = deviceId,
                uuid = LEConstants.UUIDs.META_CHARACTERISTIC_SERVER,
                respond = { bytes -> deferred.complete(bytes) },
            )
        )
        if (!emitted) {
            logger.e("no subscriber for meta read request")
            ByteArray(0)
        } else {
            runBlocking { withTimeoutOrNull(READ_TIMEOUT) { deferred.await() } } ?: ByteArray(0)
        }
    }

    private val dataCharacteristic = BluezDataCharacteristic(
        uuid = dataCharUuid,
        onWrite = { deviceId, value ->
            val channel = devices[deviceId.asString.uppercase()] ?: devices.values.firstOrNull()
            if (channel == null) {
                logger.e("data write but no registered device: $deviceId")
            } else {
                val result = channel.trySend(value)
                if (result.isFailure) logger.e("failed forwarding write to channel: $result")
            }
        },
        onSubscriptionChanged = { subscribed ->
            logger.d("data characteristic subscription changed: $subscribed")
        },
    )

    private val service = BluezGattService(serviceUuid)

    private val application = BluezGattApplication(
        children = listOf(
            service,
            metaCharacteristic,
            dataCharacteristic,
        ),
        interfaceOf = { child ->
            if (child is GattService1) GATT_SERVICE_IFACE else GATT_CHRC_IFACE
        },
    )

    /** Export objects and register the application with BlueZ. Returns true on success. */
    fun register(): Boolean {
        val conn = connection ?: return false
        val adapterPath = BluezManager.defaultAdapter()?.dbusPath
        if (adapterPath == null) {
            logger.e("no adapter to register GATT application with")
            return false
        }
        return try {
            conn.exportObject(application)
            conn.exportObject(service)
            conn.exportObject(metaCharacteristic)
            conn.exportObject(dataCharacteristic)
            val gattManager =
                conn.getRemoteObject("org.bluez", adapterPath, GattManager1::class.java, false)
            gattManager.RegisterApplication(DBusPath(APP_PATH), emptyMap())
            logger.d("registered GATT application at $adapterPath")
            true
        } catch (e: Throwable) {
            logger.e("failed to register GATT application", e)
            false
        }
    }

    fun unregister() {
        val conn = connection ?: return
        try {
            BluezManager.defaultAdapter()?.dbusPath?.let { adapterPath ->
                val gattManager =
                    conn.getRemoteObject("org.bluez", adapterPath, GattManager1::class.java, false)
                gattManager.UnregisterApplication(DBusPath(APP_PATH))
            }
        } catch (e: Throwable) {
            logger.e("error unregistering GATT application", e)
        }
        try {
            conn.unExportObject(APP_PATH)
            conn.unExportObject(SERVICE_PATH)
            conn.unExportObject(META_CHAR_PATH)
            conn.unExportObject(DATA_CHAR_PATH)
        } catch (e: Throwable) {
            logger.e("error unexporting GATT objects", e)
        }
    }

    fun registerDevice(identifier: PebbleBleIdentifier, sendChannel: SendChannel<ByteArray>) {
        devices[identifier.asString.uppercase()] = sendChannel
    }

    fun unregisterDevice(identifier: PebbleBleIdentifier) {
        devices.remove(identifier.asString.uppercase())
    }

    fun sendData(data: ByteArray): SendResult {
        val conn = connection ?: return SendResult.Failed
        return try {
            dataCharacteristic.value = data
            val changed = mapOf<String, Variant<*>>("Value" to Variant(data))
            conn.sendMessage(
                Properties.PropertiesChanged(DATA_CHAR_PATH, GATT_CHRC_IFACE, changed, emptyList())
            )
            SendResult.Success
        } catch (e: Throwable) {
            logger.e("error notifying data characteristic", e)
            SendResult.Failed
        }
    }
}
