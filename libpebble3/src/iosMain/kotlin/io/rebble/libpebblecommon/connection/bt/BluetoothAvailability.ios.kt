package io.rebble.libpebblecommon.connection.bt

import com.juul.kable.Bluetooth
import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal actual fun bluetoothAvailabilityFlow(appContext: AppContext): Flow<Boolean> =
    Bluetooth.availability.map { it is Bluetooth.Availability.Available }
