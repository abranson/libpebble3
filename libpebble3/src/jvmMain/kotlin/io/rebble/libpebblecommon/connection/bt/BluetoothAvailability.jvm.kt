package io.rebble.libpebblecommon.connection.bt

import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal actual fun bluetoothAvailabilityFlow(appContext: AppContext): Flow<Boolean> =
    nativeBluetoothStateFlow(appContext)?.map { it.enabled() } ?: flowOf(false)
