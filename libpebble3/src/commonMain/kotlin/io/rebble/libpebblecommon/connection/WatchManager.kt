package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import io.rebble.libpebblecommon.LibPebbleAnalytics
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import io.rebble.libpebblecommon.connection.bt.BluetoothStateProvider
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import io.rebble.libpebblecommon.connection.endpointmanager.LanguagePackInstallState
import io.rebble.libpebblecommon.database.BlobDbDatabaseManager
import io.rebble.libpebblecommon.database.MillisecondInstant
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.KnownWatchDao
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import io.rebble.libpebblecommon.database.entity.TransportType
import io.rebble.libpebblecommon.database.entity.identifier
import io.rebble.libpebblecommon.database.entity.type
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.ConnectionScope
import io.rebble.libpebblecommon.di.ConnectionScopeFactory
import io.rebble.libpebblecommon.di.ConnectionScopeProperties
import io.rebble.libpebblecommon.di.HackyProvider
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.di.logWatchEvent
import io.rebble.libpebblecommon.metadata.WatchColor
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Everything that is persisted, not including fields that are duplicated elsewhere (e.g. goal) */
data class KnownWatchProperties(
    val name: String,
    val nickname: String?,
    val runningFwVersion: String,
    val serial: String,
    val lastConnected: MillisecondInstant?,
    val watchType: WatchHardwarePlatform,
    val color: WatchColor?,
    val capabilities: Set<ProtocolCapsFlag>,
)

internal fun WatchInfo.asWatchProperties(lastConnected: MillisecondInstant?, name: String, nickname: String?): KnownWatchProperties =
    KnownWatchProperties(
        name = name,
        nickname = nickname,
        runningFwVersion = runningFwVersion.stringVersion,
        serial = serial,
        lastConnected = lastConnected,
        watchType = platform,
        color = color,
        capabilities = capabilities,
    )

private fun Watch.asKnownWatchItem(): KnownWatchItem? {
    if (knownWatchProps == null) return null
    return KnownWatchItem(
        transportIdentifier = identifier.asString,
        transportType = identifier.type(),
        name = name,
        nickname = nickname,
        runningFwVersion = knownWatchProps.runningFwVersion,
        serial = knownWatchProps.serial,
        connectGoal = connectGoal,
        lastConnected = knownWatchProps.lastConnected,
        watchType = knownWatchProps.watchType.revision,
        color = knownWatchProps.color,
        capabilities = knownWatchProps.capabilities,
    )
}

interface WatchConnector {
    fun addScanResult(scanResult: PebbleScanResult)
    fun requestConnection(identifier: PebbleIdentifier)
    fun requestDisconnection(identifier: PebbleIdentifier)
    fun clearScanResults()
    fun forget(identifier: PebbleIdentifier)
    fun setNickname(identifier: PebbleIdentifier, nickname: String?)
}

private data class Watch(
    val identifier: PebbleIdentifier,
    val name: String,
    val nickname: String?,
    /** Populated (and updated with fresh rssi etc) if recently discovered */
    val scanResult: PebbleScanResult?,
    val connectGoal: Boolean,
    /** Always populated if we have previously connected to this watch */
    val knownWatchProps: KnownWatchProperties?,
    /** Populated if there is an active connection */
    val activeConnection: ConnectionScope?,
    /**
     * What is currently persisted for this watch? Only used to check whether we need to persist
     * changes.
     */
    val asPersisted: KnownWatchItem?,
    val forget: Boolean,
    val firmwareUpdateAvailable: FirmwareUpdateCheckState,
    val lastFirmwareUpdateState: FirmwareUpdateStatus,
    val connectionFailureInfo: ConnectionFailureInfo?,
) {
    init {
        check(scanResult != null || knownWatchProps != null)
    }
}

private fun KnownWatchItem.asProps(): KnownWatchProperties = KnownWatchProperties(
    name = name,
    runningFwVersion = runningFwVersion,
    serial = serial,
    lastConnected = lastConnected,
    watchType = WatchHardwarePlatform.fromHWRevision(watchType),
    color = color,
    nickname = nickname,
    capabilities = capabilities ?: emptySet(),
)

private data class CombinedState(
    val watches: Map<PebbleIdentifier, Watch>,
    val active: Map<PebbleIdentifier, ActivePebbleState>,
    val previousActive: Map<PebbleIdentifier, ActivePebbleState>,
    val btstate: BluetoothState,
)

class WatchManager(
    private val knownWatchDao: KnownWatchDao,
    private val pebbleDeviceFactory: PebbleDeviceFactory,
    private val createPlatformIdentifier: CreatePlatformIdentifier,
    private val connectionScopeFactory: ConnectionScopeFactory,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val bluetoothStateProvider: BluetoothStateProvider,
    private val scanning: HackyProvider<Scanning>,
    private val watchConfig: WatchConfigFlow,
    private val clock: Clock,
    private val blePlatformConfig: BlePlatformConfig,
    private val connectionFailureHandler: ConnectionFailureHandler,
    private val analytics: LibPebbleAnalytics,
    private val blobDbDatabaseManager: BlobDbDatabaseManager,
    private val settings: Settings,
    private val appContext: AppContext,
    private val legacyBtClassicMigrator: LegacyBtClassicMigrator,
) : WatchConnector, Watches {
    private val logger = Logger.withTag("WatchManager")
    private val allWatches: MutableStateFlow<Map<PebbleIdentifier, Watch>> = MutableStateFlow(
        runBlocking {
            legacyBtClassicMigrator.migrateIfNeeded()
            knownWatchDao.knownWatches().associate {
                val identifier = it.identifier()
                identifier to Watch(
                    identifier = identifier,
                    name = it.name,
                    scanResult = null,
                    connectGoal = it.connectGoal,
                    knownWatchProps = it.asProps(),
                    activeConnection = null,
                    asPersisted = it,
                    forget = false,
                    firmwareUpdateAvailable = FirmwareUpdateCheckState(
                        checkingForUpdates = false,
                        result = null,
                    ),
                    lastFirmwareUpdateState = FirmwareUpdateStatus.NotInProgress.Idle(),
                    nickname = it.nickname,
                    connectionFailureInfo = null,
                )
            }
        }
    )
    private val _watches = MutableStateFlow<List<PebbleDevice>>(
        allWatches.value.map {
            it.value.createPebbleDevice(
                batteryLevel = null,
                btState = bluetoothStateProvider.state.value,
                state = null,
                firmwareUpdateState = FirmwareUpdateStatus.NotInProgress.Idle(),
                usingBtClassic = false,
                languagePackInstallState = LanguagePackInstallState.Idle(),
            )
        }
    )
    override val watches: StateFlow<List<PebbleDevice>> = _watches.asStateFlow()
    private val _connectionEvents = MutableSharedFlow<PebbleConnectionEvent>(extraBufferCapacity = 5)
    override val connectionEvents: Flow<PebbleConnectionEvent> = _connectionEvents.asSharedFlow()
    /**
     * Orders daemon connection-goal publication and retirement against [connectTo].
     *
     * A caller which clears a goal while holding this monitor can rely on an older collector
     * snapshot never creating a new connection after the clear returns.  This is deliberately a
     * narrow internal guarantee; the portable [Watches] API keeps its asynchronous semantics.
     */
    private val connectionGoalLock = SynchronizedObject()
    private val activeConnections = mutableSetOf<PebbleIdentifier>()
    private var connectionNum = 0
    private val timeInitialized = clock.now()

    override fun watchesDebugState(): String {
        val active = synchronized(connectionGoalLock) { activeConnections.toSet() }
        return "allWatches=${allWatches.value.entries.joinToString("\n")}\n" +
            "activeConnections=$active\n" +
            "btState=${bluetoothStateProvider.state.value}"
    }

    override fun setNickname(
        identifier: PebbleIdentifier,
        nickname: String?,
    ) {
        libPebbleCoroutineScope.launch {
            updateWatch(identifier) {
                it.copy(nickname = nickname)
            }
        }
    }

    private val seedBondedMutex = Mutex()

    /**
     * Import OS-bonded watches even when automatic fresh-install seeding has already run.
     * [beginCommit] arbitrates cancellation against the first irreversible database write.
     */
    suspend fun importBondedWatches(
        beginCommit: () -> Boolean,
    ): BondedWatchImportOutcome = seedBondedMutex.withLock {
        val candidates = bondedWatchCandidates(appContext)
            ?: return@withLock BondedWatchImportOutcome.Unavailable
        currentCoroutineContext().ensureActive()
        importBondedWatchesLocked(candidates, beginCommit)
    }

    /** Internal deterministic seam for platform-independent import lifecycle tests. */
    internal suspend fun importBondedWatches(
        candidates: List<BondedWatchCandidate>?,
        beginCommit: () -> Boolean,
    ): BondedWatchImportOutcome = seedBondedMutex.withLock {
        if (candidates == null) return@withLock BondedWatchImportOutcome.Unavailable
        importBondedWatchesLocked(candidates, beginCommit)
    }

    private suspend fun importBondedWatchesLocked(
        sourceCandidates: List<BondedWatchCandidate>,
        beginCommit: () -> Boolean,
    ): BondedWatchImportOutcome {
        val candidates = mergeBondedWatchCandidates(sourceCandidates)
        val persisted = try {
            knownWatchDao.knownWatches()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "Could not read known watches for bonded import" }
            return BondedWatchImportOutcome.PersistenceFailed
        }
        val knownAddresses = persisted.flatMapTo(hashSetOf()) { item ->
            buildList {
                add(item.transportIdentifier.uppercase())
                item.btClassicMacAddress?.let { add(it.uppercase()) }
            }
        }
        val knownIdentities = persisted.mapTo(hashSetOf()) {
            bondedWatchIdentity(it.name, it.transportIdentifier)
        }
        allWatches.value.values
            .filter { it.asPersisted != null || it.knownWatchProps != null || it.forget }
            .forEach { watch ->
                knownAddresses += watch.identifier.asString.uppercase()
                watch.asPersisted?.btClassicMacAddress?.let { knownAddresses += it.uppercase() }
                knownIdentities += bondedWatchIdentity(watch.name, watch.identifier.asString)
            }

        var existing = 0
        var unsupported = 0
        val pending = mutableListOf<KnownWatchItem>()
        candidates.forEach { candidate ->
            if (
                candidate.identity in knownIdentities ||
                candidate.addresses.values.any { it.uppercase() in knownAddresses }
            ) {
                existing++
                return@forEach
            }
            val selected = if (blePlatformConfig.supportsBtClassic) {
                candidate.addresses[TransportType.BluetoothClassic]?.let {
                    TransportType.BluetoothClassic to it
                } ?: candidate.addresses[TransportType.BluetoothLe]?.let {
                    TransportType.BluetoothLe to it
                }
            } else {
                candidate.addresses[TransportType.BluetoothLe]?.let {
                    TransportType.BluetoothLe to it
                }
            }
            if (selected == null) {
                unsupported++
                return@forEach
            }
            val (transport, rawAddress) = selected
            val address = rawAddress.uppercase()
            pending += KnownWatchItem(
                transportIdentifier = address,
                transportType = transport,
                name = candidate.name,
                runningFwVersion = UNKNOWN_WATCH_SERIAL_OR_VERSION,
                serial = UNKNOWN_WATCH_SERIAL_OR_VERSION,
                connectGoal = false,
            )
            knownAddresses += address
            knownIdentities += candidate.identity
        }

        currentCoroutineContext().ensureActive()
        if (pending.isEmpty()) {
            return BondedWatchImportOutcome.Completed(
                imported = 0,
                existing = existing,
                aliases = candidates.importAliasCount(),
                unsupported = unsupported,
            )
        }
        if (!beginCommit()) return BondedWatchImportOutcome.Cancelled

        return withContext(NonCancellable) {
            val insertResults = try {
                knownWatchDao.insertIfMissing(pending)
            } catch (e: Exception) {
                logger.w(e) { "Could not save bonded watches" }
                return@withContext BondedWatchImportOutcome.PersistenceFailed
            }
            if (insertResults.size != pending.size) {
                logger.w {
                    "KnownWatchDao returned ${insertResults.size} results for ${pending.size} inserts"
                }
                return@withContext BondedWatchImportOutcome.PersistenceFailed
            }
            val inserted = pending.filterIndexed { index, _ -> insertResults[index] != -1L }
            addImportedWatches(inserted)
            BondedWatchImportOutcome.Completed(
                imported = inserted.size,
                existing = existing + pending.size - inserted.size,
                aliases = candidates.importAliasCount(),
                unsupported = unsupported,
            )
        }
    }

    private fun List<BondedWatchCandidate>.importAliasCount(): Int =
        fold(0L) { count, candidate -> count + candidate.aliases.coerceAtLeast(0) }
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private fun addImportedWatches(inserted: List<KnownWatchItem>) {
        if (inserted.isEmpty()) return
        allWatches.update { current ->
            inserted.fold(current) { watches, item ->
                val id = item.identifier()
                val existing = watches[id]
                val imported = when {
                    existing == null -> Watch(
                        identifier = id,
                        name = item.name,
                        nickname = item.nickname,
                        scanResult = null,
                        connectGoal = item.connectGoal,
                        knownWatchProps = item.asProps(),
                        activeConnection = null,
                        asPersisted = item,
                        forget = false,
                        firmwareUpdateAvailable = FirmwareUpdateCheckState(
                            checkingForUpdates = false,
                            result = null,
                        ),
                        lastFirmwareUpdateState = FirmwareUpdateStatus.NotInProgress.Idle(),
                        connectionFailureInfo = null,
                    )
                    existing.asPersisted == null &&
                        existing.knownWatchProps == null &&
                        !existing.forget -> existing.copy(
                            name = item.name,
                            nickname = item.nickname,
                            connectGoal = item.connectGoal,
                            knownWatchProps = item.asProps(),
                            asPersisted = item,
                        )
                    else -> return@fold watches
                }
                watches + (id to imported)
            }
        }
    }

    fun seedBondedWatchesIfNeeded() {
        if (settings.getBoolean(SEEDED_BONDED_KEY, false)) return
        libPebbleCoroutineScope.launch {
            val shouldWaitForBluetooth = seedBondedMutex.withLock {
                // Re-check inside the lock — another caller may have finished while we waited.
                if (settings.getBoolean(SEEDED_BONDED_KEY, false)) return@withLock false
                // Only seed if there are no known watches yet — approximates "fresh install".
                // If the user already has watches this isn't a reinstall scenario, so just flip
                // the flag and never try again.
                val knownWatchCount = knownWatchCountForBondSeed()
                if (knownWatchCount != 0) {
                    logger.d { "Skipping bonded watch seed: $knownWatchCount watches already known" }
                    settings.putBoolean(SEEDED_BONDED_KEY, true)
                    false
                } else {
                    true
                }
            }
            if (!shouldWaitForBluetooth) return@launch

            // Do not hold the import mutex while Bluetooth is disabled. Explicit user-requested
            // import must still be able to enumerate (or return Unavailable) in that state.
            bluetoothStateProvider.state.first { it == BluetoothState.Enabled }
            seedBondedMutex.withLock {
                // Manual import may have completed while automatic seeding waited for Bluetooth.
                if (settings.getBoolean(SEEDED_BONDED_KEY, false)) return@withLock
                val knownWatchCount = knownWatchCountForBondSeed()
                if (knownWatchCount != 0) {
                    logger.d { "Skipping bonded watch seed: $knownWatchCount watches already known" }
                    settings.putBoolean(SEEDED_BONDED_KEY, true)
                    return@withLock
                }
                val candidates = try {
                    bondedWatchCandidates(appContext)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.e(e) { "Bonded watch seed failed; will retry next launch" }
                    return@withLock
                }
                when (val outcome = seedBondedWatchesLocked(candidates)) {
                    is BondedWatchImportOutcome.Completed -> {
                        settings.putBoolean(SEEDED_BONDED_KEY, true)
                        logger.i { "Seeded ${outcome.imported} bonded watches" }
                    }
                    BondedWatchImportOutcome.Unavailable ->
                        logger.d { "Bonded watch seed unavailable; will retry next launch" }
                    BondedWatchImportOutcome.PersistenceFailed ->
                        logger.w { "Bonded watch seed could not be persisted; will retry next launch" }
                    BondedWatchImportOutcome.Cancelled -> Unit
                }
            }
        }
    }

    private fun knownWatchCountForBondSeed(): Int = allWatches.value.values.count {
        it.asPersisted != null || it.knownWatchProps != null || it.forget
    }

    internal suspend fun seedBondedWatchesIfNeeded(
        candidates: List<BondedWatchCandidate>?,
    ): BondedWatchImportOutcome = seedBondedMutex.withLock {
        seedBondedWatchesLocked(candidates)
    }

    private suspend fun seedBondedWatchesLocked(
        candidates: List<BondedWatchCandidate>?,
    ): BondedWatchImportOutcome {
        if (candidates == null) return BondedWatchImportOutcome.Unavailable
        return importBondedWatchesLocked(candidates) { true }
    }

    private suspend fun persistIfNeeded(
        watch: Watch,
    ) {
        if (watch.forget) {
            logger.d("Deleting $watch from db")
            knownWatchDao.remove(watch.identifier.asString)
        } else {
            val wouldPersist = watch.asKnownWatchItem()
            if (wouldPersist != null && wouldPersist != watch.asPersisted) {
                knownWatchDao.insertOrUpdate(wouldPersist)
                updateWatch(watch.identifier) {
                    logger.d("Persisting changes for $wouldPersist")
                    it.copy(asPersisted = wouldPersist)
                }
            }
        }
    }

    private fun Watch.createPebbleDevice(
        batteryLevel: Int?,
        btState: BluetoothState,
        state: ConnectingPebbleState?,
        firmwareUpdateState: FirmwareUpdateStatus,
        usingBtClassic: Boolean,
        languagePackInstallState: LanguagePackInstallState,
    ): PebbleDevice =
        pebbleDeviceFactory.create(
            identifier = identifier,
            name = name,
            nickname = nickname,
            state = state,
            watchConnector = this@WatchManager,
            scanResult = scanResult,
            knownWatchProperties = knownWatchProps,
            connectGoal = connectGoal,
            firmwareUpdateAvailable = firmwareUpdateAvailable,
            firmwareUpdateState = firmwareUpdateState,
            bluetoothState = btState,
            lastFirmwareUpdateState = lastFirmwareUpdateState,
            batteryLevel = batteryLevel,
            connectionFailureInfo = connectionFailureInfo,
            usingBtClassic = usingBtClassic,
            languagePackInstallState = languagePackInstallState,
            reversePpogVersion = state.reversePpogVersion(),
        )

    fun init() {
        logger.d("watchmanager init()")
        seedBondedWatchesIfNeeded()
        libPebbleCoroutineScope.launch {
            val activeConnectionStates = allWatches.flowOfAllDevices()
            combine(
                allWatches,
                activeConnectionStates,
                bluetoothStateProvider.state,
            ) { watches, active, btState ->
                CombinedState(watches, active, emptyMap(), btState)
            }.runningReduce { previous, current ->
                current.copy(previousActive = previous.active)
            }.mapNotNull { state ->
                // State can be null for the first scan emission
                val (watches, active, previousActive, btState) = state
                if (watchConfig.value.verboseWatchManagerLogging) {
                    val activeConnectionSnapshot = synchronized(connectionGoalLock) {
                        activeConnections.toSet()
                    }
                    logger.d {
                        "combine: watches=$watches / active=$active / btstate=$btState / " +
                            "activeConnections=$activeConnectionSnapshot"
                    }
                }
                // Update for active connection state
                watches.values.mapNotNull { device ->
                    val identifier = device.identifier
                    val states = CurrentAndPreviousState(
                        previousState = previousActive[identifier],
                        currentState = active[identifier],
                    )
                    val hasConnectionAttempt =
                        active.containsKey(device.identifier) || synchronized(connectionGoalLock) {
                            activeConnections.contains(device.identifier)
                        }

                    persistIfNeeded(device)
                    // Removed forgotten device once it is disconnected
                    if (!hasConnectionAttempt && device.forget) {
                        logger.d("removing ${device.identifier} from allWatches")
                        allWatches.update { it.minus(device.identifier) }
                        blobDbDatabaseManager.deleteSyncRecordsForStaleDevices()
                        return@mapNotNull null
                    }

                    // Goals
                    val btUsable = btState.enabled() || !identifier.requiresBluetooth()
                    if (device.connectGoal && !hasConnectionAttempt && btUsable) {
                        if (watchConfig.value.multipleConnectedWatchesSupported) {
                            connectTo(device)
                        } else {
                            if (
                                active.isEmpty() && synchronized(connectionGoalLock) {
                                    activeConnections.isEmpty()
                                }
                            ) {
                                connectTo(device)
                            }
                        }
                    } else if (hasConnectionAttempt && !btUsable) {
                        disconnectFrom(device.identifier)
                        device.activeConnection?.cleanup()
                    } else if (!device.connectGoal && hasConnectionAttempt) {
                        disconnectFrom(device.identifier)
                    }

                    val firmwareUpdateAvailable = active[identifier]?.firmwareUpdateAvailable
                    if (firmwareUpdateAvailable != device.firmwareUpdateAvailable && firmwareUpdateAvailable != null) {
                        updateWatch(identifier) {
                            it.copy(firmwareUpdateAvailable = firmwareUpdateAvailable)
                        }
                    }

                    val pebbleDevice = device.createPebbleDevice(
                        batteryLevel = states.currentState?.batteryLevel,
                        btState = btState,
                        state = states.currentState?.connectingPebbleState,
                        firmwareUpdateState = states.currentState?.firmwareUpdateStatus ?: FirmwareUpdateStatus.NotInProgress.Idle(),
                        usingBtClassic = device.activeConnection?.usingBtClassic == true,
                        languagePackInstallState = states.currentState?.languagePackInstallState ?: LanguagePackInstallState.Idle(),
                    )

                    // Update persisted props after connection
                    if (watchConfig.value.verboseWatchManagerLogging) {
                        logger.d { "states=$states" }
                    }
                    // Watch just connected
                    if (states.currentState?.connectingPebbleState is ConnectingPebbleState.Connected
                        && states.previousState?.connectingPebbleState !is ConnectingPebbleState.Connected) {
                        val newProps = states.currentState.connectingPebbleState.watchInfo.asWatchProperties(
                            lastConnected = clock.now().asMillisecond(),
                            name = device.name,
                            nickname = device.nickname,
                        )
                        if (newProps != device.knownWatchProps) {
                            updateWatch(identifier) {
                                it.copy(
                                    knownWatchProps = newProps,
                                    connectionFailureInfo = null,
                                )
                            }
                        }

                        // Clear scan results after we connected to one of them
                        if (device.scanResult != null) {
                            clearScanResults()
                        }

                        val connectedDevice = pebbleDevice as? CommonConnectedDevice
                        if (connectedDevice == null) {
                            logger.w { "$pebbleDevice isn't a CommonConnectedDevice" }
                        } else {
                            _connectionEvents.emit(PebbleConnectionEvent.PebbleConnectedEvent(connectedDevice))
                        }
                    }
                    // Watch just disconnected
                    if (states.currentState?.connectingPebbleState !is ConnectingPebbleState.Connected
                        && states.previousState?.connectingPebbleState is ConnectingPebbleState.Connected) {
                        val lastFwupState = states.previousState.firmwareUpdateStatus
                        if (device.lastFirmwareUpdateState != lastFwupState) {
                            updateWatch(identifier) {
                                it.copy(lastFirmwareUpdateState = lastFwupState)
                            }
                        }

                        _connectionEvents.emit(PebbleConnectionEvent.PebbleDisconnectedEvent(identifier))
                    }

                    pebbleDevice
                }
            }.collect {
                _watches.value = it.also { logger.d("watches: ${it.joinToString(separator = "\n", prefix = "\n")}") }
            }
        }
    }

    override fun addScanResult(scanResult: PebbleScanResult) {
        logger.d("addScanResult: $scanResult")
        val identifier = scanResult.identifier
        allWatches.update { devices ->
            val mutableDevices = devices.toMutableMap()
            val existing = devices[identifier]
            if (existing == null) {
                mutableDevices.put(
                    identifier, Watch(
                        identifier = identifier,
                        name = scanResult.name,
                        scanResult = scanResult,
                        connectGoal = false,
                        knownWatchProps = null,
                        activeConnection = null,
                        asPersisted = null,
                        forget = false,
                        firmwareUpdateAvailable = FirmwareUpdateCheckState(checkingForUpdates = false, result = null),
                        lastFirmwareUpdateState = FirmwareUpdateStatus.NotInProgress.Idle(),
                        nickname = null,
                        connectionFailureInfo = null,
                    )
                )
            } else {
                mutableDevices.put(identifier, existing.copy(scanResult = scanResult))
            }
            mutableDevices
        }
    }

    /**
     * Update the list of known watches, mutating the specific watch, if it exists, and it the
     * mutation is not null.
     */
    private fun updateWatch(identifier: PebbleIdentifier, mutation: (Watch) -> Watch?) {
        allWatches.update { watches ->
            val device = watches[identifier]
            if (device == null) {
                logger.w { "couldn't mutate device $identifier - not found" }
                return@update watches
            }
            val mutated = mutation(device) ?: return@update watches
            watches.plus(identifier to mutated)
        }
    }

    override fun requestConnection(identifier: PebbleIdentifier) {
        libPebbleCoroutineScope.launch {
            requestConnectionImmediately(identifier)
        }
    }

    /** Applies the connection goal before returning so an ordered caller can retire it safely. */
    internal fun requestConnectionImmediately(identifier: PebbleIdentifier) {
        synchronized(connectionGoalLock) {
            logger.d("requestConnection: $identifier")
            val scanning = scanning.get()
            scanning.stopBleScan()
            scanning.stopClassicScan()
            allWatches.update { watches ->
                watches.mapValues { entry ->
                    if (entry.key == identifier) {
                        entry.value.copy(connectGoal = true)
                    } else {
                        if (watchConfig.value.multipleConnectedWatchesSupported) {
                            entry.value
                        } else {
                            entry.value.copy(connectGoal = false)
                        }
                    }
                }
            }
        }
    }

    override fun requestDisconnection(identifier: PebbleIdentifier) {
        requestDisconnectionImmediately(identifier)
    }

    /** Clears the connection goal synchronously relative to an immediate request above. */
    internal fun requestDisconnectionImmediately(identifier: PebbleIdentifier) {
        synchronized(connectionGoalLock) {
            logger.d("requestDisconnection: $identifier")
            updateWatch(identifier = identifier) { it.copy(connectGoal = false) }
        }
    }

    /** Wait until an immediately retired goal can no longer produce or retain a connection. */
    internal suspend fun awaitConnectionRetired(identifier: PebbleIdentifier) {
        allWatches.first {
            synchronized(connectionGoalLock) {
                val current = allWatches.value[identifier]
                current?.connectGoal != true &&
                    current?.activeConnection == null &&
                    identifier !in activeConnections
            }
        }
    }

    private fun connectTo(snapshot: Watch) = synchronized(connectionGoalLock) {
        val identifier = snapshot.identifier
        val device = allWatches.value[identifier]
        if (device == null || !device.connectGoal) {
            logger.d("ignoring retired connection goal for $identifier")
            return@synchronized
        }
        logger.d("connectTo: $identifier (activeConnections=$activeConnections)")
        if (device.activeConnection != null) {
            logger.w("Already connecting to $identifier")
            return@synchronized
        }
        val platformIdentifier = createPlatformIdentifier.identifier(identifier, device.name, device.connectionFailureInfo != null)
        if (platformIdentifier == null) {
            // Probably because it couldn't create the device (ios throws on an unknown persisted
            // uuid, so we'll need to scan for it using the name/serial?)...
            // ...but TODO revisit this once we have more error modes + handle BT being disabled.
            if (device.knownWatchProps != null) {
                logger.w("removing known device: $identifier")
                updateWatch(identifier) { it.copy(connectGoal = false, forget = true) }
            }
            // Hack: force another connection-state pass.
            updateWatch(identifier) { it.copy() }
            return@synchronized
        }

        if (!activeConnections.add(identifier)) {
            logger.e("Already connecting to $identifier (this is a bug)")
            return@synchronized
        }

        var connectionKoinScope: ConnectionScope? = null
        var connectionAttached = false
        try {
            var caughtException = false
            val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
                logger.e(
                    "watchmanager caught exception for $identifier: $throwable",
                    throwable,
                )
                if (caughtException) {
                    return@CoroutineExceptionHandler
                }
                caughtException = true
                if (throwable is ConnectionException) {
                    device.updateFailureReason(throwable.reason)
                }
                val connection = allWatches.value[identifier]?.activeConnection
                connection?.let {
                    libPebbleCoroutineScope.launch {
                        connection.cleanup()
                    }
                }
            }
            val deviceIdString = identifier.asString
            val thisConnectionNum = connectionNum++
            val coroutineContext =
                SupervisorJob() + exceptionHandler + CoroutineName("con-$deviceIdString-$thisConnectionNum")
            val connectionScope = ConnectionCoroutineScope(coroutineContext)
            logger.v("transport.createConnector")
            val newConnectionKoinScope = connectionScopeFactory.createScope(
                ConnectionScopeProperties(
                    identifier,
                    connectionScope,
                    platformIdentifier,
                    device.color(),
                ),
            )
            connectionKoinScope = newConnectionKoinScope
            if (!attachConnection(identifier, newConnectionKoinScope)) {
                rollbackConnectionSetup(
                    identifier = identifier,
                    connection = newConnectionKoinScope,
                    attached = false,
                )
                return@synchronized
            }
            connectionAttached = true
            val pebbleConnector = newConnectionKoinScope.pebbleConnector

            // This is here for scenarios where we have suspended code waiting for something to
            // happen (e.g. bonding for 60 seconds), which otherwise would wait the entire 60
            // seconds to finish, even after a disconnection.
            val disconnectDuringConnectionJob = connectionScope.launch {
                pebbleConnector.disconnected.disconnected.await()
                logger.d("got disconnection (before connection)")
                newConnectionKoinScope.cleanup()
            }

            connectionScope.launch {
                try {
                    if (blePlatformConfig.delayBleConnectionsAfterAppStart && (clock.now() - timeInitialized) < APP_START_WAIT_TO_CONNECT) {
                        logger.i("Device connecting too soon after init: delaying to make sure we were really disconnected")
                        delay(APP_START_WAIT_TO_CONNECT)
                    }
                    pebbleConnector.connect(
                        knownWatchProperties = device.knownWatchProps,
                        lastError = device.connectionFailureInfo?.reason,
                    )
                    disconnectDuringConnectionJob.cancel()
                    logger.d("watchmanager connected (or failed..); waiting for disconnect: $identifier")
                    pebbleConnector.disconnected.disconnected.await()
                    // TODO if not known (i.e. if only a scan result), then don't reconnect.
                    logger.d("watchmanager got disconnection: $identifier")
                    val connectionFailureReason = (pebbleConnector.state.value as? ConnectingPebbleState.Failed)?.reason
                    device.updateFailureReason(connectionFailureReason)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Because cleanup() runs in finally, the exception handler is not called.
                    logger.e(e) { "connect crashed" }
                    throw e
                } finally {
                    newConnectionKoinScope.cleanup()
                }
            }
        } catch (e: CancellationException) {
            rollbackConnectionSetup(identifier, connectionKoinScope, connectionAttached)
            throw e
        } catch (e: Exception) {
            rollbackConnectionSetup(identifier, connectionKoinScope, connectionAttached)
            logger.e(e) { "Could not create connection for $identifier" }
            val reason = (e as? ConnectionException)?.reason
                ?: ConnectionFailureReason.FailedToConnect
            updateWatch(identifier) { watch ->
                val previous = watch.connectionFailureInfo
                watch.copy(
                    connectGoal = false,
                    connectionFailureInfo = ConnectionFailureInfo(
                        reason = reason,
                        times = if (previous?.reason == reason) previous.times + 1 else 1,
                    ),
                )
            }
        }
    }

    /** Atomically publishes the already-created connection without side effects in a CAS lambda. */
    private fun attachConnection(identifier: PebbleIdentifier, connection: ConnectionScope): Boolean {
        while (true) {
            val watches = allWatches.value
            val watch = watches[identifier] ?: return false
            if (!watch.connectGoal || watch.activeConnection != null) return false
            val updated = watches + (identifier to watch.copy(activeConnection = connection))
            if (allWatches.compareAndSet(watches, updated)) return true
        }
    }

    private fun detachConnection(identifier: PebbleIdentifier, connection: ConnectionScope) {
        while (true) {
            val watches = allWatches.value
            val watch = watches[identifier] ?: return
            if (watch.activeConnection !== connection) return
            val updated = watches + (identifier to watch.copy(activeConnection = null))
            if (allWatches.compareAndSet(watches, updated)) return
        }
    }

    private fun rollbackConnectionSetup(
        identifier: PebbleIdentifier,
        connection: ConnectionScope?,
        attached: Boolean,
    ) {
        try {
            if (attached && connection != null) detachConnection(identifier, connection)
        } finally {
            try {
                connection?.close()
            } catch (e: Exception) {
                logger.w(e) { "Could not close failed connection scope for $identifier" }
            } finally {
                activeConnections.remove(identifier)
            }
        }
    }

    private fun Watch.updateFailureReason(newReason: ConnectionFailureReason?) {
        if (newReason != null) {
            val failureInfo = ConnectionFailureInfo(
                reason = newReason,
                times = if (connectionFailureInfo?.reason == newReason) {
                    connectionFailureInfo.times + 1
                } else {
                    1
                },
            )
            updateWatch(identifier) {
                it.copy(
                    connectionFailureInfo = failureInfo,
                )
            }
        }
    }

    private suspend fun ConnectionScope.cleanup() {
        // Always run in the global scope, so that no cleanup work dies when we kill the connection
        // scope.
        libPebbleCoroutineScope.async {
            if (!closed.compareAndSet(expectedValue = false, newValue = true)) {
                logger.w("$identifier: already done cleanup")
                return@async
            }
            logger.d("$identifier: cleanup")
            try {
                var disconnectRequested = false
                try {
                    pebbleConnector.disconnect()
                    disconnectRequested = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(e) { "$identifier: cleanup: disconnect request failed" }
                }
                if (disconnectRequested) {
                    try {
                        withTimeout(DISCONNECT_TIMEOUT) {
                            logger.d("$identifier: cleanup: waiting for disconnection")
                            pebbleConnector.disconnected.disconnected.await()
                        }
                    } catch (e: TimeoutCancellationException) {
                        logger.w("cleanup: timed out waiting for disconnection from $identifier")
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        logger.d("$identifier: cleanup: cancelling scope")
                        try {
                            close()
                        } catch (e: Exception) {
                            logger.w(e) { "$identifier: cleanup: scope close failed" }
                        }
                        // This is essentially a hack to work around the case where we
                        // disconnect+reconnect so fast that the watch doesn't realize.
                        if (blePlatformConfig.delayBleDisconnections) {
                            logger.d { "delaying before marking as disconnected.." }
                            delay(APP_START_WAIT_TO_CONNECT)
                        }
                    } finally {
                        logger.d("$identifier: cleanup: removing active device")
                        synchronized(connectionGoalLock) {
                            activeConnections.remove(identifier)
                            detachConnection(identifier, this@cleanup)
                        }
                    }
                }
            }
        }.await()
    }

    private fun disconnectFrom(identifier: PebbleIdentifier) {
        logger.d("disconnectFrom: $identifier")
        val activeConnection = allWatches.value[identifier]?.activeConnection
        if (activeConnection == null) {
            Logger.d("disconnectFrom / not an active device")
            return
        }
        try {
            activeConnection.pebbleConnector.disconnect()
        } catch (e: Exception) {
            logger.w(e) { "disconnectFrom: disconnect request failed for $identifier" }
            libPebbleCoroutineScope.launch {
                activeConnection.cleanup()
            }
        }
    }

    private fun Watch.isOnlyScanResult() =
        scanResult != null && activeConnection == null && !connectGoal && knownWatchProps == null

    override fun clearScanResults() {
        logger.d("clearScanResults")
        allWatches.update { aw ->
            aw.filterValues { watch ->
                !watch.isOnlyScanResult()
            }.mapValues {
                if (it.value.knownWatchProps != null) {
                    it.value.copy(scanResult = null)
                } else {
                    // Edge-case where we were disconnecting when this happened - don't leave an
                    // invalid totally empty watch record.
                    it.value
                }
            }
        }
    }

    override fun forget(identifier: PebbleIdentifier) {
        requestDisconnection(identifier)
        updateWatch(identifier) { it.copy(forget = true) }
    }

    private fun Watch.logAnalyticsEvent(name: String, props: Map<String, String>? = null) {
        analytics.logWatchEvent(color(), name, props)
    }

    companion object {
        private val DISCONNECT_TIMEOUT = 3.seconds
        private val APP_START_WAIT_TO_CONNECT = 2.5.seconds
        private const val SEEDED_BONDED_KEY = "seeded_bonded_watches_v1"
    }
}

data class CurrentAndPreviousState(
    val previousState: ActivePebbleState?,
    val currentState: ActivePebbleState?,
)

data class ActivePebbleState(
    val connectingPebbleState: ConnectingPebbleState,
    val firmwareUpdateAvailable: FirmwareUpdateCheckState,
    val firmwareUpdateStatus: FirmwareUpdateStatus,
    val batteryLevel: Int?,
    val languagePackInstallState: LanguagePackInstallState,
)

private fun StateFlow<Map<PebbleIdentifier, Watch>>.flowOfAllDevices(): Flow<Map<PebbleIdentifier, ActivePebbleState>> {
    return flatMapLatest { map ->
        val listOfInnerFlows: List<Flow<ActivePebbleState>> =
            map.values.mapNotNull { watchValue ->
                val connector = watchValue.activeConnection?.pebbleConnector
                val fwUpdateAvailableFlow =
                    watchValue.activeConnection?.firmwareUpdateManager?.availableUpdates ?: flowOf(
                        FirmwareUpdateCheckState(checkingForUpdates = false, result = null))
                val fwUpdateStatusFlow = watchValue.activeConnection?.firmwareUpdater?.firmwareUpdateState ?: flowOf(FirmwareUpdateStatus.NotInProgress.Idle())
                val batteryLevelFlow = watchValue.activeConnection?.batteryWatcher?.batteryLevel ?: flowOf(null)
                val languagePackFlow = watchValue.activeConnection?.languagePackInstaller?.state ?: flowOf(LanguagePackInstallState.Idle())

                if (connector == null) {
                    null
                } else {
                    combine(connector.state, fwUpdateAvailableFlow, fwUpdateStatusFlow, batteryLevelFlow, languagePackFlow) { connectingState, fwUpdateAvailable, fwUpdateStatus, batteryLevel, languagePackState ->
                        ActivePebbleState(connectingState, fwUpdateAvailable, fwUpdateStatus, batteryLevel, languagePackState)
                    }
                }
            }
        if (listOfInnerFlows.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(listOfInnerFlows) { innerValues ->
                innerValues.associateBy { it.connectingPebbleState.identifier }
            }
        }
    }
}

private fun Watch.color(): WatchColor = knownWatchProps?.color
    ?: scanResult?.leScanRecord?.extendedInfo?.color?.let { WatchColor.fromProtocolNumber(it.toInt()) }
    ?: WatchColor.Unknown
