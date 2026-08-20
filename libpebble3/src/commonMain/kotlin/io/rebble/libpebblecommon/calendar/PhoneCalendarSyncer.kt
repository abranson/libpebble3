package io.rebble.libpebblecommon.calendar

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.SystemAppIDs.CALENDAR_APP_UUID
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.Calendar
import io.rebble.libpebblecommon.connection.CalendarSyncOutcome
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.TimeProvider
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.TimelineWindowProvider
import io.rebble.libpebblecommon.database.dao.CalendarDao
import io.rebble.libpebblecommon.database.dao.TimelinePinRealDao
import io.rebble.libpebblecommon.database.dao.TimelineReminderRealDao
import io.rebble.libpebblecommon.database.dao.VibePatternDao
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import io.rebble.libpebblecommon.database.entity.TimelinePin
import io.rebble.libpebblecommon.database.entity.TimelineReminder
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class PhoneCalendarSyncer(
    private val timelinePinDao: TimelinePinRealDao,
    private val calendarDao: CalendarDao,
    private val timeProvider: TimeProvider,
    private val systemCalendar: SystemCalendar,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val timelineReminderDao: TimelineReminderRealDao,
    private val watchConfig: WatchConfigFlow,
    private val vibePatternDao: VibePatternDao,
    private val timelineWindowProvider: TimelineWindowProvider,
    private val retryDelay: Duration = 1.minutes,
) : Calendar {
    private val logger = Logger.withTag("PhoneCalendarSyncer")
    private val syncTrigger = MutableSharedFlow<Unit>()
    private var changesFlow: Flow<Unit>? = null
    private val changesJobMutex = Mutex()
    private val reconciliationMutex = Mutex()
    private var retryJob: Job? = null

    fun init() {
        logger.v { "init()" }
        libPebbleCoroutineScope.launch {
            libPebbleCoroutineScope.launch {
                syncTrigger.conflate().collect {
                    syncDeviceCalendarsToDb()
                }
            }
            // Make sure the above is collecting already
            delay(1.seconds)
            requestSync()
            tryStartListeningForChanges()
            libPebbleCoroutineScope.launch {
                timelineWindowProvider.sourceWindow.drop(1).collect { requestSync() }
            }
            libPebbleCoroutineScope.launch {
                watchConfig.flow.map {
                    with(it.watchConfig) {
                        listOf(calendarPins, calendarReminders, calendarShowDeclinedEvents, overrideCalendarVibePattern)
                    }
                    }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { requestSync() }
            }
        }
    }

    private suspend fun tryStartListeningForChanges() = changesJobMutex.withLock {
        if (changesFlow != null) {
            return@withLock
        }
        changesFlow = systemCalendar.registerForCalendarChanges()?.sample(5.seconds)
        changesFlow?.let {
            libPebbleCoroutineScope.launch {
                it.collect {
                    requestSync()
                }
            }
        }
    }

    fun handlePermissionsGranted() {
        libPebbleCoroutineScope.launch {
            tryStartListeningForChanges()
            requestSync()
        }
    }

    private suspend fun requestSync() {
        syncTrigger.emit(Unit)
    }

    internal suspend fun syncDeviceCalendarsToDb() {
        try {
            syncCalendars()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (systemCalendar.hasPermission()) {
                logger.w(e) { "Calendar sync failed; retrying without treating it as empty" }
                scheduleRetry()
            } else {
                logger.w(e) { "Calendar source access is unavailable; preserving the projection" }
            }
        }
    }

    override suspend fun syncCalendars(
        beginCommit: () -> Boolean,
    ): CalendarSyncOutcome = reconciliationMutex.withLock {
        val result = reconcileDeviceCalendarsToDb(beginCommit)
        retryJob?.cancel()
        retryJob = null
        result
    }

    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob = libPebbleCoroutineScope.launch {
            delay(retryDelay)
            requestSync()
        }
    }

    private suspend fun reconcileDeviceCalendarsToDb(
        beginCommit: () -> Boolean,
    ): CalendarSyncOutcome {
        val pinsEnabled = watchConfig.value.calendarPins
        val remindersEnabled = watchConfig.value.calendarReminders
        val showDeclinedEvents = watchConfig.value.calendarShowDeclinedEvents
        logger.d("syncDeviceCalendarsToDb remindersEnabled=$remindersEnabled showDeclinedEvents=$showDeclinedEvents")
        val existingCalendars = calendarDao.getAll()
        val sourceCalendars = systemCalendar.getCalendars()
        if (!systemCalendar.hasPermission()) {
            logger.i { "Calendar source is unavailable; preserving the existing projection" }
            return CalendarSyncOutcome.SourceUnavailable
        }

        val removedCalendars = existingCalendars.filter { existing ->
            sourceCalendars.none { it.platformId == existing.platformId }
        }
        val existingByPlatformId = existingCalendars.associateBy { it.platformId }
        val desiredCalendars = sourceCalendars.map { sourceCalendar ->
            existingByPlatformId[sourceCalendar.platformId]?.copy(
                platformId = sourceCalendar.platformId,
                name = sourceCalendar.name,
                ownerName = sourceCalendar.ownerName,
                ownerId = sourceCalendar.ownerId,
                color = sourceCalendar.color,
                syncEvents = sourceCalendar.syncEvents,
                visible = sourceCalendar.visible,
            ) ?: sourceCalendar.inheritEnabledFrom(removedCalendars)
        }
        logger.d(
            "Got ${desiredCalendars.size} calendars from device, staging sync " +
                "(${existingCalendars.size} existing)",
        )

        val calendarsToUpdate = desiredCalendars.filter { desiredCalendar ->
            val existingCalendar = existingByPlatformId[desiredCalendar.platformId]
            existingCalendar != null && existingCalendar != desiredCalendar
        }
        val calendarsToInsert = desiredCalendars.filter { desiredCalendar ->
            desiredCalendar.platformId !in existingByPlatformId
        }
        val calendarsToDelete = existingCalendars.filter { existingCalendar ->
            desiredCalendars.none { it.platformId == existingCalendar.platformId }
        }

        val existingPins = timelinePinDao.getPinsForWatchapp(CALENDAR_APP_UUID)
        val timelineWindow = timelineWindowProvider.sourceWindow.value
        val now = timeProvider.now()
        val startDate = now + timelineWindow.pastDays.days
        val endDate = now + timelineWindow.futureDays.days
        val calendarVibePattern = watchConfig.value.overrideCalendarVibePattern
            ?.let { vibePatternDao.getVibePattern(it)?.pattern }
        val supportsRsvp = pinsEnabled && systemCalendar.supportsPinActions()
        val newPins = desiredCalendars.flatMap { calendar ->
            if (!calendar.enabled || !pinsEnabled) {
                return@flatMap emptyList()
            }
            val events = systemCalendar.getCalendarEvents(calendar, startDate, endDate)
                .filter { event ->
                    if (showDeclinedEvents) return@filter true
                    val currentUserAttendee = event.attendees.find { it.isCurrentUser }
                    currentUserAttendee?.attendanceStatus != EventAttendee.AttendanceStatus.Declined
                }
            events.map { event ->
                EventAndPin(event, event.toTimelinePin(calendar, supportsRsvp))
            }
        }
        val remindersToInsert = mutableListOf<TimelineReminder>()
        val remindersToDelete = mutableListOf<Uuid>()
        var reminderCount = 0
        val toInsert = newPins.mapNotNull { new ->
            val newPin = new.pin
            val existingPin = existingPins.find { it.backingId == newPin.backingId }
            reminderCount += syncReminders(
                remindersEnabled = remindersEnabled,
                event = new.event,
                pinId = existingPin?.itemId ?: newPin.itemId,
                vibePattern = calendarVibePattern,
                remindersToInsert = remindersToInsert,
                remindersToDelete = remindersToDelete,
            )

            if (existingPin?.recordHashCode() == newPin.recordHashCode()) {
                return@mapNotNull null
            }
            val pin = existingPin?.let {
                newPin.copy(itemId = it.itemId)
            } ?: newPin
            logger.d("New Pin: ${newPin.itemId} (existed: ${existingPin != null})")
            return@mapNotNull pin
        }
        val pinsToDelete = existingPins.filter { pin ->
            if (newPins.none { it.pin.backingId == pin.backingId }) {
                logger.d("Deleting pin ${pin.itemId} (backingId: ${pin.backingId}) as no longer exists in calendar")
                true
            } else {
                false
            }
        }

        // Platform reads can fail or be cancelled. Cross the mutation boundary only after the
        // complete source snapshot and every comparison read have succeeded, then finish the
        // durable reconciliation even if the caller is cancelled while Room is applying it.
        currentCoroutineContext().ensureActive()
        if (!beginCommit()) throw CancellationException("calendar sync cancelled before commit")
        withContext(NonCancellable) {
            calendarDao.applyProjection(
                calendarsToUpdate = calendarsToUpdate,
                calendarsToDelete = calendarsToDelete,
                calendarsToInsert = calendarsToInsert,
                pinsToInsert = toInsert,
                pinIdsToDelete = pinsToDelete.map { it.itemId },
                remindersToInsert = remindersToInsert,
                reminderIdsToDelete = remindersToDelete,
                timelinePinDao = timelinePinDao,
                timelineReminderDao = timelineReminderDao,
            )
        }
        logger.d("Synced ${desiredCalendars.size} calendars to DB")
        return CalendarSyncOutcome.Completed(
            calendarCount = desiredCalendars.size,
            eventCount = newPins.size,
            reminderCount = reminderCount,
            calendarEnabled = pinsEnabled,
        )
    }

    private suspend fun syncReminders(
        remindersEnabled: Boolean,
        event: CalendarEvent,
        pinId: Uuid,
        vibePattern: List<UInt>?,
        remindersToInsert: MutableList<TimelineReminder>,
        remindersToDelete: MutableList<Uuid>,
    ): Int {
        val existingReminders = timelineReminderDao.getRemindersForPin(pinId)
        val desiredReminders = if (remindersEnabled) {
            event.reminders.map { reminder ->
                event.toTimelineReminder(
                    event.startTime - reminder.minutesBefore.minutes,
                    pinId,
                    vibePattern,
                )
            }
        } else {
            emptyList()
        }

        remindersToDelete += existingReminders.filter { er ->
            desiredReminders.none { dr -> dr.content.timestamp == er.content.timestamp }
        }.map { it.itemId }

        remindersToInsert += desiredReminders.mapNotNull { dr ->
            val existing = existingReminders.find {
                it.content.timestamp == dr.content.timestamp
            }
            when {
                existing == null -> dr
                existing.recordHashCode() != dr.recordHashCode() -> dr.copy(itemId = existing.itemId)
                else -> null
            }
        }
        return desiredReminders.size
    }

    override fun calendars(): Flow<List<CalendarEntity>> = calendarDao.getFlow()

    override suspend fun createEvent(event: NewCalendarEvent): String? =
        systemCalendar.createEvent(event)

    override fun updateCalendarEnabled(calendarId: Int, enabled: Boolean) {
        libPebbleCoroutineScope.launch {
            reconciliationMutex.withLock {
                calendarDao.setEnabled(calendarId, enabled)
                val calendar = calendarDao.getAll().find { it.id == calendarId }
                // A calendar only actually downloads events to the provider when it's both syncing
                // and visible; repair either flag if missing (enableSyncForCalendar sets both).
                if (calendar != null && (!calendar.syncEvents || !calendar.visible)) {
                    systemCalendar.enableSyncForCalendar(calendar)
                }
            }
            requestSync()
        }
    }
}

data class EventAndPin(val event: CalendarEvent, val pin: TimelinePin)

/**
 * `platformId` is not stable - iOS may hand back a new `calendarIdentifier` for the same calendar
 * (re-subscribe, account resync), which drops the old row and re-adds it enabled. Carry the user's
 * choice over to the replacement.
 */
internal fun CalendarEntity.inheritEnabledFrom(removed: List<CalendarEntity>): CalendarEntity {
    val previous = removed.find { it.ownerId == ownerId && it.name == name } ?: return this
    return copy(enabled = previous.enabled)
}
