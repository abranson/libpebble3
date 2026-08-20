package io.rebble.libpebblecommon.connection.bt

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.PebbleBtClassicIdentifier
import io.rebble.libpebblecommon.connection.isCanonicalBluetoothAddress
import io.rebble.libpebblecommon.connection.bt.ble.bluez.BluezAgent
import io.rebble.libpebblecommon.connection.bt.ble.bluez.BluezDiscoveryTransport
import io.rebble.libpebblecommon.connection.bt.ble.bluez.BluezManager
import io.rebble.libpebblecommon.connection.bt.ble.bluez.addBluezSigHandler
import io.rebble.libpebblecommon.connection.bt.ble.bluez.bluezDevicePath
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ConnectivityWatcher
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler
import org.freedesktop.dbus.interfaces.Properties
import kotlin.time.Duration.Companion.seconds

private val logger = Logger.withTag("Pairing.jvm")
private object PairingDependencies : LibPebbleKoinComponent

/** Optional native-Linux pairing route used before direct BlueZ pairing. */
fun interface LinuxPairingRequester {
    fun requestPairing(address: String): Boolean
}

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
    if (requestNativePairing(identifier.asString)) {
        return true
    }
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

/**
 * A native-Linux host may provide a pairing requester when direct BlueZ
 * pairing is not available. Failure falls back to the generic BlueZ path.
 */
private fun requestNativePairing(address: String): Boolean =
    if (!isCanonicalBluetoothAddress(address)) {
        logger.e { "refusing native pairing request for invalid Bluetooth address: $address" }
        false
    } else
    PairingDependencies.getKoin()
        .getOrNull<LinuxPairingRequester>()
        ?.let { requester ->
            runCatching { requester.requestPairing(address) }
                .onFailure {
                    logger.e("native pairing requester failed for $address", it)
                }
                .getOrDefault(false)
        }
        ?: false

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
    val device = BluezManager.findDevice(identifier.asString) ?: return false
    return try {
        device.isPaired == true
    } catch (e: Throwable) {
        logger.e("error checking classic bond state for $identifier", e)
        false
    }
}

actual fun createBondClassic(identifier: PebbleBtClassicIdentifier): Boolean {
    logger.d("createBondClassic($identifier)")
    if (requestNativePairing(identifier.asString)) {
        return true
    }
    BluezAgent.ensureRegistered()
    BluezManager.defaultAdapter()?.let {
        try {
            it.setPairable(true)
        } catch (e: Throwable) {
            logger.e("error setting adapter pairable for classic pairing", e)
        }
    }
    val device = BluezManager.findDeviceRediscovering(
        identifier.asString,
        transport = BluezDiscoveryTransport.Bredr,
    )
    if (device == null) {
        logger.e("createBondClassic: device not found: $identifier")
        return false
    }
    return try {
        val paired = device.pair()
        if (paired) {
            try {
                device.setTrusted(true)
            } catch (e: Throwable) {
                logger.e("error setting classic device trusted", e)
            }
        }
        paired
    } catch (e: Throwable) {
        logger.e("createBondClassic failed for $identifier", e)
        false
    }
}

actual fun getBluetoothClassicDevicePairEvents(
    context: AppContext,
    identifier: PebbleBtClassicIdentifier,
): Flow<BluetoothClassicDevicePairEvent> = callbackFlow {
    val dm = BluezManager.deviceManagerOrNull()
    val adapter = BluezManager.defaultAdapter()
    val devicePath = adapter?.let { bluezDevicePath(it.dbusPath, identifier.asString) }
    if (dm == null || devicePath == null) {
        logger.w { "classic pairing events unavailable for $identifier: BlueZ/D-Bus not reachable" }
        close()
        return@callbackFlow
    }

    fun sendBondState(paired: Boolean?) {
        trySend(
            BluetoothClassicDevicePairEvent(
                device = identifier,
                bondState = classicBondState(paired),
                unbondReason = null,
            )
        )
    }

    val handler = object : AbstractPropertiesChangedHandler() {
        override fun handle(signal: Properties.PropertiesChanged) {
            if (signal.interfaceName != CLASSIC_DEVICE_INTERFACE || signal.path != devicePath) return
            val paired = signal.propertiesChanged["Paired"]?.value as? Boolean ?: return
            sendBondState(paired)
        }
    }

    var registration: AutoCloseable? = null
    try {
        // Resolve BlueZ's current unique sender rather than accepting any process that happens
        // to emit a PropertiesChanged signal for the same object path.
        registration = dm.dbusConnection.addBluezSigHandler(
            Properties.PropertiesChanged::class.java,
            handler,
        )
        // Pairing may have completed before this cold flow is collected. A snapshot after
        // handler registration closes that race. Snapshot failure is non-fatal: the registered
        // signal still reports a later transition.
        val paired = runCatching {
            BluezManager.findDevice(identifier.asString)?.isPaired
        }.onFailure {
            logger.w { "could not read initial classic pairing state: ${it.message}" }
        }.getOrNull()
        sendBondState(paired)
    } catch (e: Throwable) {
        registration?.let { BluezManager.closeSignalRegistrations(listOf(it)) }
        logger.e("error registering classic pairing state handler", e)
        close(e)
        return@callbackFlow
    }

    awaitClose {
        registration?.let { BluezManager.closeSignalRegistrations(listOf(it)) }
    }
}

private const val CLASSIC_DEVICE_INTERFACE = "org.bluez.Device1"

internal fun classicBondState(paired: Boolean?): Int =
    if (paired == true) LEConstants.BOND_BONDED else LEConstants.BOND_NONE
