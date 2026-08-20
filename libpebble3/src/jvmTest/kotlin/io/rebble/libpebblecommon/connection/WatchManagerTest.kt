package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.LibPebbleAnalytics
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.asFlow
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import io.rebble.libpebblecommon.connection.bt.BluetoothStateProvider
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.bt.ble.pebble.BatteryWatcher
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater
import io.rebble.libpebblecommon.connection.endpointmanager.LanguagePackInstallState
import io.rebble.libpebblecommon.connection.endpointmanager.LanguagePackInstaller
import io.rebble.libpebblecommon.database.BlobDbDatabaseManager
import io.rebble.libpebblecommon.database.MillisecondInstant
import io.rebble.libpebblecommon.database.dao.KnownWatchDao
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import io.rebble.libpebblecommon.database.entity.TransportType
import io.rebble.libpebblecommon.di.ConnectionAnalyticsLogger
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.ConnectionScope
import io.rebble.libpebblecommon.di.ConnectionScopeFactory
import io.rebble.libpebblecommon.di.ConnectionScopeProperties
import io.rebble.libpebblecommon.di.HackyProvider
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.metadata.WatchColor
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.web.FirmwareUpdateManager
import com.russhwolf.settings.PropertiesSettings
import java.util.Properties
import io.rebble.libpebblecommon.web.LockerModel
import io.rebble.libpebblecommon.web.LockerModelWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.io.files.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class WatchManagerTest {
    // Watches present in the DB at WatchManager construction. Set before create().
    private var seededKnownWatches: List<KnownWatchItem> = emptyList()
    private var failBondedWatchInsert = false

    private val knownWatchDao = object : KnownWatchDao {
        override suspend fun insertOrUpdate(watch: KnownWatchItem) {
            seededKnownWatches = seededKnownWatches.filterNot {
                it.transportIdentifier == watch.transportIdentifier
            } + watch
        }

        override suspend fun insertIfMissing(watches: List<KnownWatchItem>): List<Long> {
            if (failBondedWatchInsert) throw IllegalStateException("insert failed")
            var nextId = 1L
            return watches.map { watch ->
                if (seededKnownWatches.any {
                        it.transportIdentifier == watch.transportIdentifier
                    }
                ) {
                    -1L
                } else {
                    seededKnownWatches += watch
                    nextId++
                }
            }
        }

        override suspend fun knownWatches(): List<KnownWatchItem> {
            return seededKnownWatches
        }

        override suspend fun remove(transportIdentifier: String) {
            seededKnownWatches = seededKnownWatches.filterNot {
                it.transportIdentifier == transportIdentifier
            }
        }

        override suspend fun setNickname(identifier: String, nickname: String?) {
        }
    }
    private val pebbleDeviceFactory = PebbleDeviceFactory()
    private val createPlatformIdentifier = object : CreatePlatformIdentifier {
        override fun identifier(
            identifier: PebbleIdentifier,
            name: String,
            lastAttemptFailed: Boolean,
        ): PlatformIdentifier {
            return PlatformIdentifier.SocketPlatformIdentifier("addr")
        }
    }

    class TestConnectionScope(
        override val identifier: PebbleIdentifier,
        override val pebbleConnector: PebbleConnector,
        override val closed: AtomicBoolean = AtomicBoolean(false),
        override val firmwareUpdateManager: FirmwareUpdateManager,
        override val firmwareUpdater: FirmwareUpdater,
        override val batteryWatcher: BatteryWatcher,
        override val analyticsLogger: ConnectionAnalyticsLogger,
        override val usingBtClassic: Boolean,
        override val languagePackInstaller: LanguagePackInstaller,
        private val closeAction: () -> Unit = {},
    ) : ConnectionScope {
        override fun close() {
            closeAction()
        }
    }

    private val identifier = "addr".asPebbleBleIdentifier()
    private val name = "name"

    private var activeConnections = 0
    private var totalConnections = 0
    private var connectionScopeCreations = 0
    private var onConnectionScopeCreated: (() -> Unit)? = null
    private var connectorDisconnectThrows = false
    private var connectionScopeCloseThrows = false
    private var connectionScopeFactoryThrowsOnce = false
    private var connectSuccess = false
    private var exceededMax = false
    private var lastPreviouslyConnected: Boolean? = null

    inner class TestPebbleConnector : PebbleConnector {
        private val _disconnected = CompletableDeferred<ConnectionFailureReason>()
        private val _state =
            MutableStateFlow<ConnectingPebbleState>(ConnectingPebbleState.Inactive(identifier))

        fun onDisconnection() {
            activeConnections--
            _disconnected.complete(ConnectionFailureReason.FailedToConnect)
        }

        override suspend fun connect(knownWatchProperties: KnownWatchProperties?, lastError: ConnectionFailureReason?) {
            lastPreviouslyConnected = knownWatchProperties?.lastConnected != null
            activeConnections++
            totalConnections++
            if (activeConnections > 1) {
                exceededMax = true
                throw IllegalStateException("too many active connections!")
            }
            _state.value = ConnectingPebbleState.Connecting(identifier)
            delay(1.milliseconds)
            _state.value = ConnectingPebbleState.Negotiating(identifier, null)
            delay(1.milliseconds)
            if (connectSuccess) {
                _state.value = ConnectingPebbleState.Connected.ConnectedNotInPrf(
                    identifier = identifier,
                    watchInfo = TODO(),
                    services = TODO(),
                    reversePpogVersion = null,
                )
            } else {
                _state.value = ConnectingPebbleState.Failed(identifier, ConnectionFailureReason.FailedToConnect)
                onDisconnection()
            }
        }

        override fun disconnect() {
            if (connectorDisconnectThrows) throw IllegalStateException("disconnect failed")
            _state.value = ConnectingPebbleState.Inactive(identifier)
            onDisconnection()
        }

        override val disconnected: WasDisconnected = WasDisconnected(_disconnected)
        override val state: StateFlow<ConnectingPebbleState> = _state.asStateFlow()
    }

    private val firmwareUpdateManager = object : FirmwareUpdateManager {
        override fun init(watchInfo: WatchInfo) {
        }

        override fun checkForUpdates(force: Boolean) {
        }

        override val availableUpdates: Flow<FirmwareUpdateCheckState>
            get() = MutableStateFlow(FirmwareUpdateCheckState(false, null))

    }
    private val firmwareUpdater = object : FirmwareUpdater {
        override val firmwareUpdateState: StateFlow<FirmwareUpdater.FirmwareUpdateStatus>
            = MutableStateFlow(FirmwareUpdater.FirmwareUpdateStatus.NotInProgress.Idle())

        override fun init(
            watchPlatform: WatchHardwarePlatform,
            slot: Int?,
            supportsResume: Boolean,
            runningFwVersion: FirmwareVersion,
        ) {
        }

        override fun sideloadFirmware(path: Path) {}

        override fun updateFirmware(update: FirmwareUpdateCheckResult.FoundUpdate) {}

        override fun checkforFirmwareUpdate(force: Boolean) {}
    }
    private val bluetoothState = MutableStateFlow(BluetoothState.Enabled)
    private val bluetoothStateProvider = object : BluetoothStateProvider {
        override fun init() {
        }

        override val state: StateFlow<BluetoothState> = bluetoothState.asStateFlow()

        override val scanningAvailable: StateFlow<Boolean> =
            MutableStateFlow(true).asStateFlow()
    }
    private val scanning = object : Scanning {
        override val bluetoothEnabled: StateFlow<BluetoothState>
            get() = TODO("Not yet implemented")
        override val isScanningBle: StateFlow<Boolean>
            get() = TODO("Not yet implemented")
        override val isScanningClassic: StateFlow<Boolean>
            get() = TODO("Not yet implemented")

        override fun startBleScan() {
        }

        override fun stopBleScan() {
        }

        override fun startClassicScan() {
        }

        override fun stopClassicScan() {
        }

        override fun addQemuWatch(address: String, connect: Boolean) {
        }
    }
    private val watchConfig = WatchConfig(multipleConnectedWatchesSupported = false).asFlow()
    private val webServices = object : WebServices {
        override suspend fun fetchLocker(): LockerModelWrapper? {
            TODO("Not yet implemented")
        }

        override suspend fun removeFromLocker(id: Uuid): Boolean {
            TODO("Not yet implemented")
        }

        override suspend fun checkForFirmwareUpdate(watch: WatchInfo, force: Boolean): FirmwareUpdateCheckResult {
            TODO("Not yet implemented")
        }

        override fun uploadMemfaultChunk(chunk: ByteArray, watchInfo: WatchInfo) {
            TODO("Not yet implemented")
        }

        override fun uploadAnalyticsHeartbeat(payload: ByteArray, watchInfo: WatchInfo) {
            TODO("Not yet implemented")
        }
    }
    private val blePlatformConfig =
        BlePlatformConfig(delayBleConnectionsAfterAppStart = false, delayBleDisconnections = false)

    private fun create(
        scope: CoroutineScope,
        supportsBtClassic: Boolean = false,
        bondedSeeded: Boolean = true,
    ): WatchManager {
        val libPebbleCoroutineScope = LibPebbleCoroutineScope(scope.coroutineContext)
        val connectionCoroutineScope = ConnectionCoroutineScope(scope.coroutineContext)
        val batteryWatcher = BatteryWatcher(connectionCoroutineScope)
        val analyticsLogger = object : ConnectionAnalyticsLogger {
            override fun logEvent(
                name: String,
                props: Map<String, String>?
            ) {
            }
        }
        val langPackInstaller = object : LanguagePackInstaller {
            override val state: StateFlow<LanguagePackInstallState> = MutableStateFlow(
                LanguagePackInstallState.Idle())
        }
        val connectionScopeFactory = object : ConnectionScopeFactory {
            override fun createScope(props: ConnectionScopeProperties): ConnectionScope {
                connectionScopeCreations++
                onConnectionScopeCreated?.invoke()
                if (connectionScopeFactoryThrowsOnce) {
                    connectionScopeFactoryThrowsOnce = false
                    throw IllegalStateException("scope factory failed")
                }
                return TestConnectionScope(
                    identifier = props.identifier,
                    pebbleConnector = TestPebbleConnector(),
                    firmwareUpdateManager = firmwareUpdateManager,
                    firmwareUpdater = firmwareUpdater,
                    batteryWatcher = batteryWatcher,
                    analyticsLogger = analyticsLogger,
                    usingBtClassic = false,
                    languagePackInstaller = langPackInstaller,
                    closeAction = {
                        if (connectionScopeCloseThrows) throw IllegalStateException("scope close failed")
                    },
                )
            }
        }
        val connectionFailureHandler = object : ConnectionFailureHandler {
            override suspend fun handleConnectionFailure(
                identifier: PebbleIdentifier,
                color: WatchColor,
                failure: ConnectionFailureInfo
            ) {
            }
        }
        val analytics = object : LibPebbleAnalytics {
            override fun logEvent(
                name: String,
                parameters: Map<String, String>
            ) {
            }
        }
        val blobDbManager = object : BlobDbDatabaseManager {
            override suspend fun deleteSyncRecordsForStaleDevices() {
            }
        }
        val testSettings = PropertiesSettings(Properties())
        // Bonded-watch seeding is covered by BondedWatchSeederJvmTest. Keep these WatchManager
        // lifecycle tests independent of a real system-bus BlueZ service.
        testSettings.putBoolean("seeded_bonded_watches_v1", bondedSeeded)
        val managerBlePlatformConfig = blePlatformConfig.copy(
            supportsBtClassic = supportsBtClassic,
        )
        return WatchManager(
            knownWatchDao = knownWatchDao,
            pebbleDeviceFactory = pebbleDeviceFactory,
            createPlatformIdentifier = createPlatformIdentifier,
            connectionScopeFactory = connectionScopeFactory,
            libPebbleCoroutineScope = libPebbleCoroutineScope,
            bluetoothStateProvider = bluetoothStateProvider,
            scanning = HackyProvider { scanning },
            watchConfig = watchConfig,
            clock = Clock.System,
            blePlatformConfig = managerBlePlatformConfig,
            connectionFailureHandler = connectionFailureHandler,
            analytics = analytics,
            blobDbDatabaseManager = blobDbManager,
            settings = testSettings,
            appContext = AppContext(),
            legacyBtClassicMigrator = LegacyBtClassicMigrator(
                knownWatchDao = knownWatchDao,
                settings = testSettings,
                blePlatformConfig = managerBlePlatformConfig,
            ),
        )
    }

//    @Test
//    fun happyCase() = runTest(timeout = 5.seconds) {
//        val watchManager = create(backgroundScope)
//        val scanResult = PebbleScanResult(transport, 0, null)
//        watchManager.init()
//        yield()
//        watchManager.addScanResult(scanResult)
//        watchManager.requestConnection(transport)
//        watchManager.watches.first { it.any { it is ConnectingPebbleDevice } }
//        watchManager.watches.first { it.any { it is ConnectedPebbleDevice } }
//        assertFalse(exceededMax)
//    }

    @Test
    fun repeatConnections() = runTest(timeout = 5.seconds) {
        val watchManager = create(backgroundScope)
        val scanResult = PebbleScanResult(identifier, name, 0, null)
        watchManager.init()
        yield()
        watchManager.addScanResult(scanResult)
        watchManager.requestConnection(identifier)
        for (i in 1..20) {
            watchManager.watches.first { totalConnections >= i && it.any { it is ConnectingPebbleDevice } }
        }
        assertFalse(exceededMax)
    }

    @Test
    fun immediateConnectionRequestThenCancellationDoesNotStartConnector() = runTest {
        val watchManager = create(backgroundScope)
        watchManager.init()
        yield()
        watchManager.addScanResult(PebbleScanResult(identifier, name, 0, null))

        watchManager.requestConnectionImmediately(identifier)
        watchManager.requestDisconnectionImmediately(identifier)
        yield()

        assertEquals(0, totalConnections)
    }

    @Test
    fun cancelledImmediateConnectionDoesNotResumeWhenBluetoothIsEnabled() = runTest {
        bluetoothState.value = BluetoothState.Disabled
        val watchManager = create(backgroundScope)
        watchManager.init()
        yield()
        watchManager.addScanResult(PebbleScanResult(identifier, name, 0, null))

        watchManager.requestConnectionImmediately(identifier)
        watchManager.requestDisconnectionImmediately(identifier)
        bluetoothState.value = BluetoothState.Enabled
        yield()

        assertEquals(0, totalConnections)
    }

    @Test
    fun awaitConnectionRetiredPreventsCancelledAttemptFromReappearing() = runTest {
        val watchManager = create(backgroundScope)
        watchManager.init()
        yield()
        watchManager.addScanResult(PebbleScanResult(identifier, name, 0, null))
        watchManager.requestConnectionImmediately(identifier)
        watchManager.watches.first {
            totalConnections == 1 && it.any { device -> device is ConnectingPebbleDevice }
        }

        watchManager.requestDisconnectionImmediately(identifier)
        watchManager.awaitConnectionRetired(identifier)
        watchManager.watches.first { devices ->
            devices.none { device ->
                device is ActiveDevice || device is ConnectingPebbleDevice
            }
        }
        yield()

        assertFalse(
            watchManager.watches.value.any { device ->
                device is ActiveDevice || device is ConnectingPebbleDevice
            },
        )
    }

    @Test
    fun concurrentStateFlowUpdateDoesNotCreateDuplicateConnectionScopes() = runTest {
        lateinit var watchManager: WatchManager
        onConnectionScopeCreated = {
            onConnectionScopeCreated = null
            watchManager.addScanResult(PebbleScanResult(identifier, name, -49, null))
        }
        watchManager = create(backgroundScope)
        watchManager.init()
        yield()
        watchManager.addScanResult(PebbleScanResult(identifier, name, -50, null))

        watchManager.requestConnectionImmediately(identifier)
        watchManager.watches.first {
            totalConnections == 1 && it.any { device -> device is ConnectingPebbleDevice }
        }

        assertEquals(1, connectionScopeCreations)
    }

    @Test
    fun disconnectFailureStillRetiresConnectionBeforeRetry() = runTest(timeout = 5.seconds) {
        val watchManager = create(backgroundScope)
        watchManager.init()
        yield()
        watchManager.addScanResult(PebbleScanResult(identifier, name, 0, null))
        watchManager.requestConnectionImmediately(identifier)
        watchManager.watches.first {
            totalConnections == 1 && it.any { device -> device is ConnectingPebbleDevice }
        }

        connectorDisconnectThrows = true
        watchManager.requestDisconnectionImmediately(identifier)
        watchManager.awaitConnectionRetired(identifier)
        connectorDisconnectThrows = false

        watchManager.requestConnectionImmediately(identifier)
        watchManager.watches.first {
            totalConnections == 2 && it.any { device -> device is ConnectingPebbleDevice }
        }
    }

    @Test
    fun scopeCloseFailureStillRetiresConnectionBeforeRetry() = runTest(timeout = 5.seconds) {
        val watchManager = create(backgroundScope)
        watchManager.init()
        yield()
        watchManager.addScanResult(PebbleScanResult(identifier, name, 0, null))
        watchManager.requestConnectionImmediately(identifier)
        watchManager.watches.first {
            totalConnections == 1 && it.any { device -> device is ConnectingPebbleDevice }
        }

        connectionScopeCloseThrows = true
        watchManager.requestDisconnectionImmediately(identifier)
        watchManager.awaitConnectionRetired(identifier)
        connectionScopeCloseThrows = false

        watchManager.requestConnectionImmediately(identifier)
        watchManager.watches.first {
            totalConnections == 2 && it.any { device -> device is ConnectingPebbleDevice }
        }
    }

    @Test
    fun scopeFactoryFailureRetiresGoalAndAllowsExplicitRetry() = runTest(timeout = 5.seconds) {
        connectionScopeFactoryThrowsOnce = true
        val watchManager = create(backgroundScope)
        watchManager.init()
        yield()
        watchManager.addScanResult(PebbleScanResult(identifier, name, 0, null))

        watchManager.requestConnectionImmediately(identifier)
        watchManager.watches.first { devices ->
            devices.singleOrNull()?.let { device ->
                device !is ActiveDevice &&
                    device !is ConnectingPebbleDevice &&
                    device.connectionFailureInfo?.reason == ConnectionFailureReason.FailedToConnect
            } == true
        }
        assertEquals(1, connectionScopeCreations)
        assertEquals(0, totalConnections)

        watchManager.requestConnectionImmediately(identifier)
        watchManager.watches.first {
            totalConnections == 1 && it.any { device -> device is ConnectingPebbleDevice }
        }
        assertEquals(2, connectionScopeCreations)
    }

    private fun bondedCandidate(
        name: String = "Pebble Time Le D7CD",
        leAddress: String? = "D0:81:0A:D4:D7:CD",
        classicAddress: String? = null,
        aliases: Int = 0,
    ): BondedWatchCandidate {
        val fallback = leAddress ?: classicAddress ?: error("candidate needs an address")
        return BondedWatchCandidate(
            identity = bondedWatchIdentity(name, fallback),
            name = name,
            addresses = buildMap {
                leAddress?.let { put(TransportType.BluetoothLe, it) }
                classicAddress?.let { put(TransportType.BluetoothClassic, it) }
            },
            aliases = aliases,
        )
    }

    @Test
    fun explicitBondImportUpdatesLiveWatchesAndIsIdempotent() = runTest {
        val watchManager = create(backgroundScope)
        watchManager.init()
        yield()
        val candidate = bondedCandidate(aliases = 1)

        assertEquals(
            BondedWatchImportOutcome.Completed(1, 0, 1, 0),
            watchManager.importBondedWatches(listOf(candidate)) { true },
        )
        val importedWatches = watchManager.watches.first {
            it.singleOrNull() is KnownPebbleDevice
        }
        assertEquals("D0:81:0A:D4:D7:CD", importedWatches.single().identifier.asString)
        assertEquals(false, seededKnownWatches.single().connectGoal)

        assertEquals(
            BondedWatchImportOutcome.Completed(0, 1, 1, 0),
            watchManager.importBondedWatches(listOf(candidate)) { false },
        )
        assertEquals(1, seededKnownWatches.size)
    }

    @Test
    fun explicitBondImportCancellationWinsBeforePersistence() = runTest {
        val watchManager = create(backgroundScope)

        assertEquals(
            BondedWatchImportOutcome.Cancelled,
            watchManager.importBondedWatches(listOf(bondedCandidate())) { false },
        )
        assertEquals(emptyList<KnownWatchItem>(), seededKnownWatches)
        assertEquals(0, watchManager.watches.value.size)
    }

    @Test
    fun explicitBondImportIsNotBlockedByAutomaticSeedWaitingForBluetooth() = runTest {
        bluetoothState.value = BluetoothState.Disabled
        val watchManager = create(backgroundScope, bondedSeeded = false)
        watchManager.init()
        yield()

        assertEquals(
            BondedWatchImportOutcome.Completed(1, 0, 0, 0),
            watchManager.importBondedWatches(listOf(bondedCandidate())) { true },
        )
        assertEquals(1, seededKnownWatches.size)
    }

    @Test
    fun explicitBondImportPromotesMatchingScanOnlyWatch() = runTest {
        val watchManager = create(backgroundScope)
        watchManager.init()
        yield()
        watchManager.addScanResult(
            PebbleScanResult(
                identifier = "D0:81:0A:D4:D7:CD".asPebbleBleIdentifier(),
                name = "Pebble Time Le D7CD",
                rssi = -50,
                leScanRecord = null,
            ),
        )
        watchManager.watches.first { it.size == 1 }

        assertEquals(
            BondedWatchImportOutcome.Completed(1, 0, 0, 0),
            watchManager.importBondedWatches(listOf(bondedCandidate())) { true },
        )
        assertEquals(1, seededKnownWatches.size)
        assertEquals(
            "D0:81:0A:D4:D7:CD",
            watchManager.watches.first {
                it.singleOrNull() is KnownPebbleDevice
            }.single().identifier.asString,
        )
    }

    @Test
    fun explicitBondImportDoesNotResurrectForgetInFlight() = runTest {
        seededKnownWatches = listOf(
            KnownWatchItem(
                transportIdentifier = "D0:81:0A:D4:D7:CD",
                transportType = TransportType.BluetoothLe,
                name = "Pebble Time Le D7CD",
                runningFwVersion = "4.4.0",
                serial = "Q1234567890L",
                connectGoal = false,
            ),
        )
        val watchManager = create(backgroundScope)
        watchManager.forget("D0:81:0A:D4:D7:CD".asPebbleBleIdentifier())
        seededKnownWatches = emptyList()

        assertEquals(
            BondedWatchImportOutcome.Completed(0, 1, 0, 0),
            watchManager.importBondedWatches(listOf(bondedCandidate())) { true },
        )
        assertEquals(emptyList<KnownWatchItem>(), seededKnownWatches)
    }

    @Test
    fun explicitBondImportPreservesExistingMetadataAcrossAliasAddress() = runTest {
        seededKnownWatches = listOf(
            KnownWatchItem(
                transportIdentifier = "11:22:33:44:55:66",
                transportType = TransportType.BluetoothLe,
                name = "Pebble Time D7CD",
                runningFwVersion = "4.4.0",
                serial = "Q1234567890L",
                connectGoal = true,
            ),
        )
        val before = seededKnownWatches.single()
        val watchManager = create(backgroundScope)

        assertEquals(
            BondedWatchImportOutcome.Completed(0, 1, 1, 0),
            watchManager.importBondedWatches(listOf(bondedCandidate(aliases = 1))) { true },
        )
        assertEquals(before, seededKnownWatches.single())
    }

    @Test
    fun existingClassicAliasSuppressesImportWhenClassicTransportIsUnavailable() = runTest {
        seededKnownWatches = listOf(
            KnownWatchItem(
                transportIdentifier = "11:22:33:44:55:66",
                transportType = TransportType.BluetoothClassic,
                name = "unknown",
                runningFwVersion = "4.4.0",
                serial = "Q1234567890L",
                connectGoal = true,
            ),
        )
        val watchManager = create(backgroundScope, supportsBtClassic = false)

        assertEquals(
            BondedWatchImportOutcome.Completed(0, 1, 1, 0),
            watchManager.importBondedWatches(
                listOf(
                    bondedCandidate(
                        leAddress = "D0:81:0A:D4:D7:CD",
                        classicAddress = "11:22:33:44:55:66",
                        aliases = 1,
                    ),
                ),
            ) { true },
        )
        assertEquals(1, seededKnownWatches.size)
    }

    @Test
    fun explicitBondImportReportsUnavailableUnsupportedAndPersistenceFailure() = runTest {
        val watchManager = create(backgroundScope)
        assertEquals(
            BondedWatchImportOutcome.Unavailable,
            watchManager.importBondedWatches(null) { true },
        )
        assertEquals(
            BondedWatchImportOutcome.Completed(0, 0, 0, 1),
            watchManager.importBondedWatches(
                listOf(bondedCandidate(leAddress = null, classicAddress = "11:22:33:44:55:66")),
            ) { true },
        )
        failBondedWatchInsert = true
        assertEquals(
            BondedWatchImportOutcome.PersistenceFailed,
            watchManager.importBondedWatches(listOf(bondedCandidate())) { true },
        )
        assertEquals(emptyList<KnownWatchItem>(), seededKnownWatches)
        assertEquals(0, watchManager.watches.value.size)
    }

    @Test
    fun explicitBondImportPrefersClassicOnlyWhenTheRuntimeSupportsIt() = runTest {
        val watchManager = create(backgroundScope, supportsBtClassic = true)

        assertEquals(
            BondedWatchImportOutcome.Completed(1, 0, 0, 0),
            watchManager.importBondedWatches(
                listOf(
                    bondedCandidate(
                        leAddress = "D0:81:0A:D4:D7:CD",
                        classicAddress = "11:22:33:44:55:66",
                    ),
                ),
            ) { true },
        )
        assertEquals(TransportType.BluetoothClassic, seededKnownWatches.single().transportType)
        assertEquals("11:22:33:44:55:66", seededKnownWatches.single().transportIdentifier)
    }

    @Test
    fun automaticBondSeedUsesDualModePlanWithoutCreatingAliases() = runTest {
        val watchManager = create(backgroundScope, supportsBtClassic = true)
        watchManager.init()
        yield()
        val aliases = listOf(
            bondedCandidate(
                name = "Pebble Time Le D7CD",
                leAddress = "D0:81:0A:D4:D7:CD",
                aliases = 0,
            ),
            bondedCandidate(
                name = "Pebble Time D7CD",
                leAddress = null,
                classicAddress = "11:22:33:44:55:66",
                aliases = 0,
            ),
        )

        assertEquals(
            BondedWatchImportOutcome.Completed(1, 0, 1, 0),
            watchManager.seedBondedWatchesIfNeeded(aliases),
        )
        assertEquals(1, seededKnownWatches.size)
        assertEquals(TransportType.BluetoothClassic, seededKnownWatches.single().transportType)
        assertEquals(
            1,
            watchManager.watches.first { it.singleOrNull() is KnownPebbleDevice }.size,
        )
    }

    private fun seededWatch(lastConnected: MillisecondInstant?) = KnownWatchItem(
        transportIdentifier = "addr",
        transportType = TransportType.BluetoothLe,
        name = name,
        // BondedWatchSeeder writes these placeholder values for OS-bonded watches.
        runningFwVersion = UNKNOWN_WATCH_SERIAL_OR_VERSION,
        serial = UNKNOWN_WATCH_SERIAL_OR_VERSION,
        connectGoal = true,
        lastConnected = lastConnected,
    )

    /**
     * A BondedWatchSeeder stub (never connected by this install, so `lastConnected == null`) must
     * be treated as a FIRST connection — otherwise BlobDB.init() skips wiping the watch's blob DBs
     * after a reinstall and stale weather/etc. records linger. Regression test for that.
     */
    @Test
    fun seededWatchIsNotTreatedAsPreviouslyConnected() = runTest(timeout = 5.seconds) {
        seededKnownWatches = listOf(seededWatch(lastConnected = null))
        val watchManager = create(backgroundScope)
        watchManager.init()
        watchManager.watches.first { totalConnections >= 1 && it.any { it is ConnectingPebbleDevice } }
        assertEquals(false, lastPreviouslyConnected)
    }

    @Test
    fun watchWithPriorConnectionIsTreatedAsPreviouslyConnected() = runTest(timeout = 5.seconds) {
        seededKnownWatches = listOf(seededWatch(lastConnected = MillisecondInstant(Instant.fromEpochMilliseconds(1_000))))
        val watchManager = create(backgroundScope)
        watchManager.init()
        watchManager.watches.first { totalConnections >= 1 && it.any { it is ConnectingPebbleDevice } }
        assertEquals(true, lastPreviouslyConnected)
    }
}
