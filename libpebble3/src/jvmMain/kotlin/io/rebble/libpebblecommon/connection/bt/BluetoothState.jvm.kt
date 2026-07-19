package io.rebble.libpebblecommon.connection.bt

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.bt.ble.bluez.BluezManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler
import org.freedesktop.dbus.interfaces.Properties

private val logger = Logger.withTag("BluetoothState.jvm")

private const val ADAPTER_INTERFACE = "org.bluez.Adapter1"

actual fun nativeBluetoothStateFlow(appContext: AppContext): Flow<BluetoothState>? {
    val dm = BluezManager.deviceManagerOrNull() ?: return null

    fun currentState(): BluetoothState =
        if (BluezManager.defaultAdapter()?.isPowered == true) {
            BluetoothState.Enabled
        } else {
            BluetoothState.Disabled
        }

    return callbackFlow {
        trySend(currentState())

        val handler = object : AbstractPropertiesChangedHandler() {
            override fun handle(signal: Properties.PropertiesChanged) {
                if (signal.interfaceName != ADAPTER_INTERFACE) return
                if (!signal.propertiesChanged.containsKey("Powered")) return
                trySend(currentState())
            }
        }

        try {
            dm.registerPropertyHandler(handler)
        } catch (e: Throwable) {
            logger.e("error registering adapter-state handler", e)
            close(e)
            return@callbackFlow
        }

        awaitClose {
            try {
                dm.unRegisterPropertyHandler(handler)
            } catch (e: Throwable) {
                logger.e("error unregistering adapter-state handler", e)
            }
        }
    }.distinctUntilChanged()
}
