package io.rebble.libpebblecommon.connection.endpointmanager.blobdb

import co.touchlab.kermit.Logger
import com.russhwolf.settings.ExperimentalSettingsApi
import com.russhwolf.settings.Settings
import com.russhwolf.settings.serialization.decodeValue
import com.russhwolf.settings.serialization.encodeValue
import coredev.BlobDatabase
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.database.dao.BlobDbDao
import io.rebble.libpebblecommon.database.dao.BlobDbRecord
import io.rebble.libpebblecommon.database.dao.LockerEntryRealDao
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.dao.NotificationRuleDao
import io.rebble.libpebblecommon.database.dao.TimelineNotificationRealDao
import io.rebble.libpebblecommon.database.dao.TimelinePinRealDao
import io.rebble.libpebblecommon.database.dao.TimelineReminderRealDao
import io.rebble.libpebblecommon.database.dao.ValueParams
import io.rebble.libpebblecommon.database.dao.VibePatternDao
import io.rebble.libpebblecommon.database.dao.WatchPrefRealDao
import io.rebble.libpebblecommon.database.dao.WeatherAppRealDao
import io.rebble.libpebblecommon.database.entity.AppPrefsEntryDao
import io.rebble.libpebblecommon.database.entity.DbTimelineItem
import io.rebble.libpebblecommon.database.entity.TimelineNotificationEntity
import io.rebble.libpebblecommon.database.dao.HealthSettingsEntryRealDao
import io.rebble.libpebblecommon.database.entity.HealthStatDao
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.PlatformConfig
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import io.rebble.libpebblecommon.packets.blobdb.BlobDB2Command
import io.rebble.libpebblecommon.packets.blobdb.BlobDB2Response
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import io.rebble.libpebblecommon.services.blobdb.DbWrite
import io.rebble.libpebblecommon.services.blobdb.WriteType
import io.rebble.libpebblecommon.web.withTimeoutOr
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class BlobDbDaos(
    private val lockerEntryDao: LockerEntryRealDao,
    private val notificationsDao: TimelineNotificationRealDao,
    private val timelinePinDao: TimelinePinRealDao,
    private val timelineReminderDao: TimelineReminderRealDao,
    private val notificationAppRealDao: NotificationAppRealDao,
    private val healthSettingsDao: HealthSettingsEntryRealDao,
    private val healthStatDao: HealthStatDao,
    private val vibePatternDao: VibePatternDao,
    private val platformConfig: PlatformConfig,
    private val watchPrefDao: WatchPrefRealDao,
    private val weatherAppDao: WeatherAppRealDao,
    private val appPrefsEntryDao: AppPrefsEntryDao,
    private val notificationRuleDao: NotificationRuleDao,
) {
    fun get(): Set<BlobDbDao<BlobDbRecord>> = buildSet {
        add(lockerEntryDao)
        add(notificationsDao)
        add(timelinePinDao)
        add(timelineReminderDao)
        add(healthSettingsDao)
        add(healthStatDao)
        if (platformConfig.syncNotificationApps) {
            add(notificationAppRealDao)
        }
        add(watchPrefDao)
        add(weatherAppDao)
        add(appPrefsEntryDao)
        // because typing
    } as Set<BlobDbDao<BlobDbRecord>>
    
    fun getVibePatternDao(): VibePatternDao = vibePatternDao
    fun getNotificationRuleDao(): io.rebble.libpebblecommon.database.dao.NotificationRuleDao = notificationRuleDao

    suspend fun reconcileTimelineDatabaseAfterClear(
        database: BlobDatabase,
        transport: String,
    ) {
        when (database) {
            BlobDatabase.Pin -> timelinePinDao.markAllDeletedFromWatch(transport)
            BlobDatabase.Reminder -> timelineReminderDao.markAllDeletedFromWatch(transport)
            BlobDatabase.Notification -> notificationsDao.suppressCurrentForWatch(transport)
            else -> error("Not a timeline database: $database")
        }
    }
}

interface TimeProvider {
    fun now(): Instant
}

class RealTimeProvider : TimeProvider {
    override fun now(): Instant = Clock.System.now()
}

@Serializable
data class DevicesWhichHaveSyncedSettings(val identifiers: Set<String>)

@OptIn(ExperimentalSettingsApi::class)
class BlobDB(
    private val watchScope: ConnectionCoroutineScope,
    private val blobDBService: BlobDBService,
    private val identifier: PebbleIdentifier,
    private val blobDatabases: BlobDbDaos,
    private val timeProvider: TimeProvider,
    private val notificationConfigFlow: NotificationConfigFlow,
    private val settings: Settings,
    private val libPebbleConfigFlow: LibPebbleConfigFlow,
    private val timelineWindowProvider: TimelineWindowProvider,
) : ConnectedPebble.TimelineControl {
    protected val watchIdentifier: String = identifier.asString

    companion object {
        private val BLOBDB_RESPONSE_TIMEOUT = 10.seconds
        private val QUERY_REFRESH_PERIOD = 1.hours
        private val TIMELINE_QUERY_REFRESH_PERIOD = 3.minutes
        private val PREF_KEY_DEVICES_HAVE_SYNCED_SETTINGS = "devicesHaveSyncedSettings"
        private const val NUL_CHAR: Char = '\u0000'
    }

    private val logger = Logger.withTag("BlobDB-$watchIdentifier")
    private val random = Random
    // To prevent overlapping insert/delete operations
    private val operationLock = Mutex()
    private val databasesWhichWillSync = MutableStateFlow(setOf(BlobDatabase.WatchPrefs))

    /**
     * Run [query] continually, updating the query timestamp (so that it does not become stale).
     */
    private fun dynamicQuery(
        dao: BlobDbDao<BlobDbRecord>,
        insert: Boolean,
        collector: suspend (items: List<BlobDbRecord>) -> Unit,
    ) {
        val initialTimestamp = timeProvider.now()
        watchScope.launch {
            val refreshPeriod = when (dao) {
                is TimelinePinRealDao,
                is TimelineReminderRealDao,
                is TimelineNotificationRealDao -> TIMELINE_QUERY_REFRESH_PERIOD
                else -> QUERY_REFRESH_PERIOD
            }
            val tickerFlow = flow {
                while (true) {
                    emit(Unit)
                    delay(refreshPeriod)
                }
            }
            timelineWindowProvider.windowFor(identifier.asString)
                .flatMapLatest { window ->
                    tickerFlow.flatMapLatest {
                        logger.d { "dynamicQuery: refreshing (${dao.databaseId()}" }
                        val now = timeProvider.now().toEpochMilliseconds()
                        val windowStart = now + window.pastDays.days.inWholeMilliseconds
                        val windowEnd = now + window.futureDays.days.inWholeMilliseconds
                        when (dao) {
                            is TimelinePinRealDao -> if (insert) {
                                dao.dirtyRecordsForWatchInsertInWindow(identifier.asString, windowStart, windowEnd)
                            } else {
                                dao.dirtyRecordsForWatchDeleteOutsideWindow(identifier.asString, windowStart, windowEnd)
                            }
                            is TimelineReminderRealDao -> if (insert) {
                                dao.dirtyRecordsForWatchInsertInWindow(identifier.asString, windowStart, windowEnd)
                            } else {
                                dao.dirtyRecordsForWatchDeleteOutsideWindow(identifier.asString, windowStart, windowEnd)
                            }
                            is TimelineNotificationRealDao -> if (insert) {
                                dao.dirtyRecordsForWatchInsertInWindow(
                                    identifier.asString,
                                    windowStart,
                                    now + window.notificationFadeSeconds.seconds.inWholeMilliseconds,
                                    windowEnd,
                                )
                            } else {
                                dao.dirtyRecordsForWatchDelete(identifier.asString, now)
                            }
                            else -> if (insert) {
                                dao.dirtyRecordsForWatchInsert(
                                    transport = identifier.asString,
                                    timestampMs = now,
                                    insertOnlyAfterMs = initialTimestamp.toEpochMilliseconds(),
                                )
                            } else {
                                dao.dirtyRecordsForWatchDelete(identifier.asString, now)
                            }
                        }
                    }
                }
                .conflate()
                .distinctUntilChanged()
                .collect { items ->
                    collector(items)
                    // debounce
                    delay(1.seconds)
                }
        }
    }

    /**
     * A window update can race with a Room flow result already waiting behind
     * [operationLock]. Check the latest per-watch window immediately before
     * writing so an old result cannot repopulate an excluded record.
     */
    private suspend fun isCurrentTimelineEligibility(
        dao: BlobDbDao<BlobDbRecord>,
        item: BlobDbRecord,
        insert: Boolean,
    ): Boolean {
        if (dao !is TimelinePinRealDao && dao !is TimelineReminderRealDao && dao !is TimelineNotificationRealDao) {
            return true
        }
        val timestamp = (item.record as? DbTimelineItem)?.content?.timestamp?.instant
            ?.toEpochMilliseconds() ?: return true
        val now = timeProvider.now().toEpochMilliseconds()
        val window = timelineWindowProvider.windowFor(identifier.asString).value
        val windowStart = now + window.pastDays.days.inWholeMilliseconds
        val windowEnd = now + window.futureDays.days.inWholeMilliseconds
        val isWithinCurrentWindow = if (insert) {
            timestamp > windowStart && timestamp < windowEnd &&
                (dao !is TimelineNotificationRealDao ||
                    timestamp >= now + window.notificationFadeSeconds.seconds.inWholeMilliseconds)
        } else {
            dao !is TimelineNotificationRealDao &&
                (item.deleted || timestamp <= windowStart || timestamp >= windowEnd)
        }
        if (!isWithinCurrentWindow) return false
        if (insert && dao is TimelineNotificationRealDao) {
            val notification = item as? TimelineNotificationEntity ?: return false
            // A Room flow can already be queued behind operationLock when Reset Timeline writes
            // suppression markers. Recheck the marker in the same critical section as the send so
            // that pre-reset pending notifications cannot leak through after the clear.
            return dao.isPendingForWatch(
                transport = identifier.asString,
                recordId = notification.record.itemId,
                recordHashcode = notification.recordHashcode,
            )
        }
        return true
    }

    fun init(
        watchType: WatchType,
        unfaithful: Boolean,
        previouslyConnected: Boolean,
        capabilities: Set<ProtocolCapsFlag>,
        firmwareVersion: FirmwareVersion,
    ) {
        val params = ValueParams(
            platform = watchType,
            capabilities = capabilities,
            firmwareVersion = firmwareVersion,
            vibePatternDao = blobDatabases.getVibePatternDao(),
            notificationRuleDao = blobDatabases.getNotificationRuleDao(),
            libPebbleConfigFlow = libPebbleConfigFlow,
        )
        val deviceHasPreviouslySyncedSettings =
            loadDevicePreviousSettingsSyncState().identifiers.contains(identifier.asString)
        watchScope.launch {
            // Watch isn't sending a version response
            val blobDbVersion = if (capabilities.contains(ProtocolCapsFlag.SupportsBlobDbVersion)) {
                val response = blobDBService.send(BlobDB2Command.Version(generateToken()))
                logger.v { "got version response: $response" }
                if (response is BlobDB2Response.VersionResponseCmd) {
                    response.version.get().toInt()
                } else {
                    0
                }
            } else {
                0
            }
            logger.d { "BlobDb version: $blobDbVersion" }

            if (unfaithful || !previouslyConnected) {
                // Mark all of our local DBs not synched (before handling any writes from the watch)
                blobDatabases.get().forEach { db ->
                    db.markAllDeletedFromWatch(identifier.asString)
                }
            }

            if (unfaithful || !deviceHasPreviouslySyncedSettings) {
                if (blobDbVersion >= 1) {
                    // Request a full sync of watch prefs
                    val dirtyResponse = blobDBService.send(
                        BlobDB2Command.MarkAllDirty(
                            generateToken(),
                            BlobDatabase.WatchPrefs,
                        )
                    )
                    logger.v { "Marked all dirty for watch prefs: $dirtyResponse" }
                }
            }

            watchScope.launch {
                blobDBService.writes.collect { message ->
                    logger.v { "write: $message" }
                    if (message.database == BlobDatabase.WatchPrefs && !deviceHasPreviouslySyncedSettings) {
                        markDeviceHasSyncedSettings()
                    }
                    val effectiveDatabase = effectiveDatabaseFor(message)
                    val dao = blobDatabases.get().find { it.databaseId() == effectiveDatabase }
                    val result = dao?.handleWrite(
                        write = message,
                        transport = identifier.asString,
                        params,
                    ) ?: run {
                        logger.v { "unhandled write: $message (key=${message.key.joinToString()} value=${message.value.joinToString()}" }
                        BlobResponse.BlobStatus.Success
                    }
                    val response = when (message.writeType) {
                        // TODO only one of these during initial sync?
                        WriteType.Write -> BlobDB2Response.WriteResponse(message.token, result)
                        WriteType.WriteBack -> BlobDB2Response.WriteBackResponse(
                            message.token,
                            result
                        )
                    }
                    blobDBService.sendResponse(response)
                }
            }

            if (blobDbVersion >= 1) {
                watchScope.launch {
                    blobDBService.syncCompletes.collect {
                        databasesWhichWillSync.value -= it
                    }
                }

                withTimeoutOr(timeout = 30.seconds, block = {
                    databasesWhichWillSync.first { it.isEmpty() }
                }, onTimeout = {
                    logger.w { "Timed out waiting for sync to complete" }
                })
            }

            if (unfaithful || !previouslyConnected) {
                logger.d("unfaithful: wiping DBs on watch")
                // Clear all DBs on watch (whether we have local DBs for them or not)
                operationLock.withLock {
                    BlobDatabase.entries.forEach { db ->
                        if (db.sendClear) {
                            sendWithTimeout(
                                BlobCommand.ClearCommand(
                                    token = generateToken(),
                                    database = db,
                                )
                            )
                        }
                    }
                }
            }

            blobDatabases.get().forEach { db ->
                val now = timeProvider.now().toEpochMilliseconds()
                when (db) {
                    is TimelinePinRealDao -> db.deleteStaleRecords(
                        now - TimelineWindow.MAX_DAYS.days.inWholeMilliseconds,
                    )
                    is TimelineReminderRealDao -> db.deleteStaleRecords(
                        now - TimelineWindow.MAX_DAYS.days.inWholeMilliseconds,
                    )
                    is TimelineNotificationRealDao -> db.deleteStaleRecords(
                        now - TimelineWindow.MAX_NOTIFICATION_FADE_SECONDS.seconds.inWholeMilliseconds,
                    )
                    else -> db.deleteStaleRecords(now)
                }
                dynamicQuery(dao = db, insert = true) { dirty ->
                    dirty.forEach { item ->
                        operationLock.withLock {
                            if (isCurrentTimelineEligibility(db, item, insert = true)) {
                                handleInsert(db, item, params, blobDbVersion)
                            }
                        }
                    }
                }
                dynamicQuery(dao = db, insert = false) { dirty ->
                    dirty.forEach { item ->
                        operationLock.withLock {
                            if (isCurrentTimelineEligibility(db, item, insert = false)) {
                                handleDelete(db, item)
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun resetTimeline(): Boolean = operationLock.withLock {
        resetTimelineDatabases(
            clear = { database ->
                sendWithTimeout(
                    BlobCommand.ClearCommand(
                        token = generateToken(),
                        database = database,
                    ),
                )
            },
            reconcileCleared = { database ->
                blobDatabases.reconcileTimelineDatabaseAfterClear(database, watchIdentifier)
            },
            onFailure = { database, cause ->
                if (cause == null) {
                    logger.w { "watch rejected or timed out clearing timeline database $database" }
                } else {
                    logger.w(cause) { "failed clearing timeline database $database" }
                }
            },
        )
    }

    // The watch firmware sends health settings (activityPreferences, unitsDistance,
    // hrmPreferences, heartRatePreferences) via the WatchPrefs BlobDB, but the phone stores
    // them in HealthParams. Route those keys to the health settings DAO.
    private fun effectiveDatabaseFor(message: DbWrite): BlobDatabase {
        if (message.database != BlobDatabase.WatchPrefs) return message.database
        val key = message.key.toByteArray().decodeToString().trimEnd(NUL_CHAR)
        return when (key) {
            "activityPreferences",
            "unitsDistance",
            "hrmPreferences",
            "heartRatePreferences" -> BlobDatabase.HealthParams
            else -> message.database
        }
    }

    private fun loadDevicePreviousSettingsSyncState() =
        settings.decodeValue(PREF_KEY_DEVICES_HAVE_SYNCED_SETTINGS, DevicesWhichHaveSyncedSettings(emptySet()))

    private fun markDeviceHasSyncedSettings() {
        logger.d { "markDeviceHasSyncedSettings: ${identifier.asString}" }
        val devices = loadDevicePreviousSettingsSyncState()
        settings.encodeValue(
            PREF_KEY_DEVICES_HAVE_SYNCED_SETTINGS,
            devices.copy(identifiers = devices.identifiers + identifier.asString),
        )
    }

    private suspend fun handleInsert(
        db: BlobDbDao<BlobDbRecord>,
        item: BlobDbRecord,
        params: ValueParams,
        blobDbVersion: Int,
    ) {
        val value = item.record.value(params)
        val key = item.record.key()
        val keyString = key.keyAsString(db.databaseId())
        if (value == null) {
            logger.d { "handleInsert: value is null: ${db.databaseId()} $keyString" }
            return
        }
        if (notificationConfigFlow.value.obfuscateContent) {
            logger.d("insert: ${db.databaseId()} $key ($keyString) hashcode: ${item.recordHashcode}")
        } else {
            logger.d("insert: ${db.databaseId()} $keyString - $item")
        }
        val timestamp = item.record.timestamp()
        val command = when {
            timestamp != null && blobDbVersion >= 1 -> {
                logger.v { "inserting with timestamp: $timestamp" }
                BlobCommand.InsertWithTimestampCommand(
                    token = generateToken(),
                    database = db.databaseId(),
                    key = key,
                    value = value,
                    timestamp = timestamp,
                )
            }
            timestamp == null || db.databaseId().supportsLegacyInsert -> BlobCommand.InsertCommand(
                token = generateToken(),
                database = db.databaseId(),
                key = key,
                value = value,
            )
            else -> null
        }
        if (command == null) {
            logger.i { "Not processing insert for ${db.databaseId()}: $keyString because not supported by watch" }
            return
        }
        val bytes = command.serialize().asByteArray()
        val result = sendWithTimeout(command)
        logger.d("insert: result = ${result?.responseValue}")
        when (result?.responseValue) {
            BlobResponse.BlobStatus.Success,
            // Stale = mark as synced (watch will never accept this record)
            BlobResponse.BlobStatus.DataStale-> db.markSyncedToWatch(
                transport = identifier.asString,
                item = item,
                hashcode = item.recordHashcode,
            )

            else -> Unit
        }
    }

    private suspend fun handleDelete(
        db: BlobDbDao<BlobDbRecord>,
        item: BlobDbRecord,
    ) {
        val key = item.record.key()
        val keyString = key.keyAsString(db.databaseId())
        if (notificationConfigFlow.value.obfuscateContent) {
            logger.d("delete: ${db.databaseId()} $key ($keyString) hashcode: ${item.recordHashcode}")
        } else {
            logger.d("delete: ${db.databaseId()} $keyString - $item")
        }
        val result = sendWithTimeout(
            BlobCommand.DeleteCommand(
                token = generateToken(),
                database = db.databaseId(),
                key = key,
            )
        )
        logger.d("delete: result = ${result?.responseValue}")
        if (result?.responseValue == BlobResponse.BlobStatus.Success) {
            db.markDeletedFromWatch(
                transport = identifier.asString,
                item = item,
                hashcode = item.recordHashcode,
            )
        }
    }

    private fun generateToken(): UShort {
        return random.nextInt(0, UShort.MAX_VALUE.toInt()).toUShort()
    }

    private suspend fun sendWithTimeout(command: BlobCommand): BlobResponse? =
        withTimeoutOrNull(BLOBDB_RESPONSE_TIMEOUT) {
            blobDBService.send(command)
        }
}

internal suspend fun resetTimelineDatabases(
    clear: suspend (BlobDatabase) -> BlobResponse?,
    reconcileCleared: suspend (BlobDatabase) -> Unit,
    onFailure: (BlobDatabase, Throwable?) -> Unit = { _, _ -> },
): Boolean = withContext(NonCancellable) {
    var complete = true
    TIMELINE_RESET_DATABASES.forEach { database ->
        val response = try {
            clear(database)
        } catch (e: Exception) {
            onFailure(database, e)
            complete = false
            return@forEach
        }
        if (response?.responseValue == BlobResponse.BlobStatus.Success) {
            // Pins and reminders invalidate their markers so durable content is restored.
            // Notifications instead mark every current phone-side record as suppressed, matching
            // the legacy reset which discarded both delivered and still-pending notifications.
            // Persist that reconciliation before moving on, but keep attempting later databases
            // if the local marker write fails after the watch has committed its clear.
            try {
                reconcileCleared(database)
            } catch (e: Exception) {
                onFailure(database, e)
                complete = false
            }
        } else {
            onFailure(database, null)
            complete = false
        }
    }
    complete
}

private val TIMELINE_RESET_DATABASES = listOf(
    BlobDatabase.Pin,
    BlobDatabase.Reminder,
    BlobDatabase.Notification,
)

private fun UByteArray.keyAsString(db: BlobDatabase): String = when {
    db.keyIsUuid() -> try {
        Uuid.fromUByteArray(this).toString()
    } catch (_: Exception) {
        ""
    }
    else -> ""
}

private fun BlobDatabase.keyIsUuid(): Boolean = when (this) {
    BlobDatabase.Pin, BlobDatabase.App, BlobDatabase.Reminder, BlobDatabase.Notification -> true
    else -> false
}
