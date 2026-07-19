package io.rebble.libpebblecommon.connection.bt.ble.transport

import io.rebble.libpebblecommon.connection.ConnectionFailureReason
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.PlatformIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

// Platform GATT central seam: android/ios build a Kable KableGattConnector; jvm/Linux (Sailfish)
// supplies a BlueZ D-Bus connector instead. Kable's desktop backend binds btleplug via UniFFI+JNA,
// which doesn't work in a GraalVM native image — what the Sailfish daemon compiles to.
expect fun libpebbleGattConnector(
    identifier: PebbleBleIdentifier,
    platformIdentifier: PlatformIdentifier,
    scope: ConnectionCoroutineScope,
    blePlatformConfig: BlePlatformConfig,
): GattConnector

sealed class GattConnectionResult {
    data class Success(val client: ConnectedGattClient) : GattConnectionResult()
    data class Failure(val reason: ConnectionFailureReason) : GattConnectionResult()
}

interface GattConnector : AutoCloseable {
    suspend fun connect(): GattConnectionResult
    suspend fun disconnect()
    val disconnected: Deferred<ConnectionFailureReason>
}

enum class GattWriteType {
    WithResponse,
    NoResponse,
}

interface ConnectedGattClient : AutoCloseable {
    suspend fun discoverServices(): Boolean
    fun subscribeToCharacteristic(
        serviceUuid: Uuid,
        characteristicUuid: Uuid,
        onSubscription: (suspend () -> Unit)? = null,
    ): Flow<ByteArray>?

    suspend fun isBonded(): Boolean // TODO doesn't belong in here
    suspend fun writeCharacteristic(
        serviceUuid: Uuid,
        characteristicUuid: Uuid,
        value: ByteArray,
        writeType: GattWriteType
    ): Boolean

    suspend fun readCharacteristic(serviceUuid: Uuid, characteristicUuid: Uuid): ByteArray?
    val services: List<GattService>?
    suspend fun requestMtu(mtu: Int): Int
    suspend fun getMtu(): Int
    suspend fun refreshServicesNative(): Boolean
}
