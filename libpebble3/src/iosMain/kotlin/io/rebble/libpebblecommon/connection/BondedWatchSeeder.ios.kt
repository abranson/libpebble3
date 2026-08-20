package io.rebble.libpebblecommon.connection

// iOS/CoreBluetooth has no equivalent of Android's BluetoothAdapter.getBondedDevices(), and
// a reinstall invalidates per-app CBPeripheral UUIDs, so we can't recover prior pairings here.
internal actual suspend fun bondedWatchCandidates(
    appContext: AppContext,
): List<BondedWatchCandidate>? = emptyList()
