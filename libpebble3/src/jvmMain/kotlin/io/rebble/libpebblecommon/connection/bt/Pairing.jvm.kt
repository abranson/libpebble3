package io.rebble.libpebblecommon.connection.bt

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.PebbleBtClassicIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.bluez.BluezAgent
import io.rebble.libpebblecommon.connection.bt.ble.bluez.BluezManager
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ConnectivityWatcher
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

private val logger = Logger.withTag("Pairing.jvm")

actual fun isBonded(identifier: PebbleBleIdentifier): Boolean {
    val device = BluezManager.findDevice(identifier.asString) ?: return false
    return try {
        device.isPaired == true
    } catch (e: Throwable) {
        logger.e("error checking bond state for $identifier", e)
        false
    }
}

actual fun createBond(identifier: PebbleBleIdentifier): Boolean {
    logger.d("createBond($identifier)")
    BluezAgent.ensureRegistered()
    BluezManager.defaultAdapter()?.let {
        try {
            it.setPairable(true)
        } catch (e: Throwable) {
            logger.e("error setting adapter pairable", e)
        }
    }
    val device = BluezManager.findDevice(identifier.asString)
    if (device == null) {
        logger.e("createBond: device not found: $identifier")
        return false
    }
    return try {
        val paired = device.pair()
        if (paired) {
            try {
                device.setTrusted(true)
            } catch (e: Throwable) {
                logger.e("error setting trusted", e)
            }
        }
        paired
    } catch (e: Throwable) {
        logger.e("createBond failed for $identifier", e)
        false
    }
}

actual fun getBluetoothDevicePairEvents(
    context: AppContext,
    identifier: PebbleBleIdentifier,
    connectivityWatcher: ConnectivityWatcher,
    connectionScope: ConnectionCoroutineScope,
): Flow<BluetoothDevicePairEvent> = channelFlow {
    // The Pebble reports its own bond state on the connectivity characteristic — BlueZ's
    // Device1.Paired doesn't reliably flip for this JustWorks bond (the link ends up encrypted but
    // "unpaired"), so the watch's view is authoritative, same as iOS. But on BlueZ the watch is slow
    // to flip `paired` and doesn't reliably *notify* that transition, so actively poll the
    // characteristic while the bond is pending instead of waiting for a notification (a gated
    // re-read is chicken-and-egg: the status won't update until we read it). channelFlow cancels
    // this poll when the bond-wait collector stops — BOND_BONDED seen, or the 60s timeout fires.
    launch {
        while (true) {
            runCatching { connectivityWatcher.readValue() }
            delay(1.5.seconds)
        }
    }
    connectivityWatcher.status.collect { status ->
        send(
            BluetoothDevicePairEvent(
                device = identifier,
                bondState = if (status.paired && status.encrypted) LEConstants.BOND_BONDED else LEConstants.BOND_NONE,
                unbondReason = null,
            )
        )
    }
}

actual fun isBondedClassic(identifier: PebbleBtClassicIdentifier): Boolean {
    throw UnsupportedOperationException("BT Classic not supported on JVM")
}

actual fun createBondClassic(identifier: PebbleBtClassicIdentifier): Boolean {
    throw UnsupportedOperationException("BT Classic not supported on JVM")
}

actual fun getBluetoothClassicDevicePairEvents(
    context: AppContext,
    identifier: PebbleBtClassicIdentifier,
): Flow<BluetoothClassicDevicePairEvent> {
    throw UnsupportedOperationException("BT Classic not supported on JVM")
}
