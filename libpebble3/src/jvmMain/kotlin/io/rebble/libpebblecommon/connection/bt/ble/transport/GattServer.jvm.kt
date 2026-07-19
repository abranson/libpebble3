package io.rebble.libpebblecommon.connection.bt.ble.transport

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.bluez.BluezGattServer
import io.rebble.libpebblecommon.connection.bt.ble.bluez.BluezManager
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

private val logger = Logger.withTag("GattServer.jvm")

actual fun openGattServer(
    appContext: AppContext,
    bleConfigFlow: BleConfigFlow,
    libPebbleCoroutineScope: LibPebbleCoroutineScope
): GattServer? {
    if (BluezManager.connection == null) {
        logger.e("can't open GATT server: BlueZ/D-Bus unavailable")
        return null
    }
    return try {
        GattServer(
            BluezGattServer(
                serviceUuid = LEConstants.UUIDs.PPOGATT_DEVICE_SERVICE_UUID_SERVER.toString(),
                metaCharUuid = LEConstants.UUIDs.META_CHARACTERISTIC_SERVER.toString(),
                dataCharUuid = LEConstants.UUIDs.PPOGATT_DEVICE_CHARACTERISTIC_SERVER.toString(),
            )
        )
    } catch (e: Throwable) {
        logger.e("error opening GATT server", e)
        null
    }
}

actual class GattServer internal constructor(private val server: BluezGattServer) {
    actual suspend fun addServices() {
        server.register()
    }

    actual suspend fun removeServices() {
    }

    actual suspend fun closeServer() {
        server.unregister()
    }

    actual val characteristicReadRequest: Flow<ServerCharacteristicReadRequest>
        get() = server.characteristicReadRequest

    actual fun registerDevice(
        identifier: PebbleBleIdentifier,
        sendChannel: SendChannel<ByteArray>
    ) {
        server.registerDevice(identifier, sendChannel)
    }

    actual fun unregisterDevice(identifier: PebbleBleIdentifier) {
        server.unregisterDevice(identifier)
    }

    actual suspend fun sendData(
        identifier: PebbleBleIdentifier,
        serviceUuid: Uuid,
        characteristicUuid: Uuid,
        data: ByteArray
    ): SendResult {
        return server.sendData(data)
    }

    actual fun wasRestoredWithSubscribedCentral(): Boolean = false

    actual fun initServer() {
    }
}
