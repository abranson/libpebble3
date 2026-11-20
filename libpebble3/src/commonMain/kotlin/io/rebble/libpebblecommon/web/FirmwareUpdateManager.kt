package io.rebble.libpebblecommon.web

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckState
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.connection.endpointmanager.PendingFirmwareInstalls
import io.rebble.libpebblecommon.connection.endpointmanager.installsDirectly
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

interface FirmwareUpdateManager {
    fun init(watchInfo: WatchInfo)
    fun isCurrentOffer(update: FirmwareUpdateCheckResult.FoundUpdate): Boolean = true
    fun checkForUpdates(force: Boolean)
    val availableUpdates: Flow<FirmwareUpdateCheckState>
}

class RealFirmwareUpdateManager(
    private val webServices: WebServices,
    private val connectionCoroutineScope: ConnectionCoroutineScope,
    private val identifier: PebbleIdentifier,
    private val pendingInstalls: PendingFirmwareInstalls,
) : FirmwareUpdateManager {
    private val _availableUpdates = MutableStateFlow<FirmwareUpdateCheckState>(
        FirmwareUpdateCheckState(checkingForUpdates = false, result = null)
    )
    private val checkTrigger = Channel<Boolean>(Channel.CONFLATED)
    private var watchInfo: WatchInfo? = null
    private val logger = Logger.withTag("FirmwareUpdateManager")

    companion object {
        private val INITIAL_DELAY_PERIOD = 1.seconds
    }

    override fun init(watchInfo: WatchInfo) {
        this.watchInfo = watchInfo
        connectionCoroutineScope.launch {
            checkTrigger.receiveAsFlow().collect {
                doUpdateCheck(it)
            }
        }
        connectionCoroutineScope.launch {
            val revisions = webServices.firmwareAccountRevision(watchInfo)
            if (revisions == null) {
                delay(INITIAL_DELAY_PERIOD)
                checkForUpdates(false)
            } else {
                revisions.collect { checkForUpdates(false) }
            }
        }
    }

    override fun isCurrentOffer(update: FirmwareUpdateCheckResult.FoundUpdate): Boolean {
        val revision = update.accountRevision ?: return true
        val watch = watchInfo ?: return false
        return revision == webServices.firmwareAccountRevision(watch)?.value &&
            _availableUpdates.value.result == update
    }

    override fun checkForUpdates(force: Boolean) {
        logger.d { "checkForUpdates" }
        checkTrigger.trySend(force)
    }

    private suspend fun doUpdateCheck(force: Boolean) {
        val watch = watchInfo
        if (watch == null) {
            logger.e { "doUpdateCheck: watch is null!" }
            return
        }
        logger.d { "doUpdateCheck" }
        val revisions = webServices.firmwareAccountRevision(watch)
        val revision = revisions?.value
        _availableUpdates.value = FirmwareUpdateCheckState(checkingForUpdates = true, result = null)
        val firmwareUpdateAvailable = when (val result = webServices.checkForFirmwareUpdate(watch, force)) {
            is FirmwareUpdateCheckResult.FoundUpdate -> result.copy(
                accountRevision = revision,
                installsDirectly = installsDirectly(watch.runningFwVersion, result.version, pendingInstalls.get(identifier)),
            )
            else -> result
        }
        if (revision == revisions?.value) {
            _availableUpdates.value = FirmwareUpdateCheckState(checkingForUpdates = false, result = firmwareUpdateAvailable)
        }
    }

    override val availableUpdates: Flow<FirmwareUpdateCheckState> = _availableUpdates.asStateFlow()
}