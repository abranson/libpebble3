package io.rebble.libpebblecommon.calendar

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.asFlow
import io.rebble.libpebblecommon.connection.CalendarSyncOutcome
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.DefaultTimelineWindowProvider
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.TimeProvider
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.dao.TimelinePinRealDao
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import io.rebble.libpebblecommon.database.entity.buildTimelinePin
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds

class PhoneCalendarSyncerJvmTest {
    @Test
    fun explicitSyncReportsTheCompleteProjectionAfterCrossingCommit() = runTest {
        withDatabase { database ->
            val sourceCalendar = calendar("calendar", "Test calendar")
            val sourceEvent = event(sourceCalendar.platformId)
            val syncer = syncer(
                database = database,
                calendar = TestSystemCalendar(
                    calendars = listOf(sourceCalendar),
                    events = listOf(sourceEvent),
                ),
                coroutineContext = backgroundScope.coroutineContext,
            )
            var commits = 0

            val outcome = syncer.syncCalendars {
                commits++
                true
            }

            assertEquals(1, commits)
            assertEquals(
                CalendarSyncOutcome.Completed(
                    calendarCount = 1,
                    eventCount = 1,
                    reminderCount = 1,
                    calendarEnabled = true,
                ),
                outcome,
            )
            assertEquals(listOf(sourceCalendar), database.calendarDao().getAll().map { it.withoutId() })
            assertEquals(
                1,
                database.timelinePinDao().getPinsForWatchapp(
                    io.rebble.libpebblecommon.SystemAppIDs.CALENDAR_APP_UUID,
                ).size,
            )
        }
    }

    @Test
    fun explicitSyncReportsUnavailableWithoutCrossingCommitOrMutatingProjection() = runTest {
        withDatabase { database ->
            val seededCalendar = seedCalendar(database)
            var commits = 0

            val outcome = syncer(
                database = database,
                calendar = TestSystemCalendar(permissionGranted = false),
                coroutineContext = backgroundScope.coroutineContext,
            ).syncCalendars {
                commits++
                true
            }

            assertEquals(CalendarSyncOutcome.SourceUnavailable, outcome)
            assertEquals(0, commits)
            assertEquals(listOf(seededCalendar), database.calendarDao().getAll())
        }
    }

    @Test
    fun explicitSyncCancellationBeforeCommitPreservesProjection() = runTest {
        withDatabase { database ->
            val seededCalendar = seedCalendar(database)

            assertFailsWith<CancellationException> {
                syncer(
                    database = database,
                    calendar = TestSystemCalendar(calendars = emptyList()),
                    coroutineContext = backgroundScope.coroutineContext,
                ).syncCalendars { false }
            }

            assertEquals(listOf(seededCalendar), database.calendarDao().getAll())
        }
    }

    @Test
    fun disabledCalendarSettingClearsPinsWithoutReadingEvents() = runTest {
        withDatabase { database ->
            val existingPin = existingPin()
            val sourceCalendar = calendar("calendar", "Test calendar")
            database.timelinePinDao().insertOrReplace(existingPin)
            val calendar = TestSystemCalendar(
                calendars = listOf(sourceCalendar),
                events = listOf(event(sourceCalendar.platformId)),
            )

            val outcome = syncer(
                database = database,
                calendar = calendar,
                coroutineContext = backgroundScope.coroutineContext,
                watchConfig = WatchConfig(calendarPins = false),
            ).syncCalendars()

            assertEquals(
                CalendarSyncOutcome.Completed(
                    calendarCount = 1,
                    eventCount = 0,
                    reminderCount = 0,
                    calendarEnabled = false,
                ),
                outcome,
            )
            assertEquals(0, calendar.eventCalls)
            assertEquals(
                emptyList(),
                database.timelinePinDao().getPinsForWatchapp(existingPin.content.parentId),
            )
        }
    }

    @Test
    fun permissionDenialDoesNotReadEventsOrMutateTheExistingProjection() = runTest {
        withDatabase { database ->
            val pin = existingPin()
            val seededCalendar = seedCalendar(database)
            database.timelinePinDao().insertOrReplace(pin)
            val calendar = TestSystemCalendar(permissionGranted = false)

            syncer(
                database,
                calendar,
                coroutineContext = backgroundScope.coroutineContext,
            ).syncDeviceCalendarsToDb()

            // Permission is checked again after the source-calendar read to close a revocation
            // race, but no event reads or durable changes are allowed after it is denied.
            assertEquals(1, calendar.calendarCalls)
            assertEquals(0, calendar.eventCalls)
            assertEquals(listOf(seededCalendar), database.calendarDao().getAll())
            assertEquals(listOf(pin), database.timelinePinDao().getPinsForWatchapp(pin.content.parentId))
        }
    }

    @Test
    fun eventFailurePreservesProjectionUntilASuccessfulReconciliation() = runTest {
        withDatabase { database ->
            val pin = existingPin()
            val seededCalendar = seedCalendar(database)
            val updatedCalendar = seededCalendar.copy(
                id = 0,
                name = "Updated calendar",
                ownerName = "Updated owner",
                ownerId = "updated-owner",
                color = 42,
                syncEvents = false,
                visible = false,
            )
            val newCalendar = calendar("new-calendar", "New calendar")
            database.timelinePinDao().insertOrReplace(pin)
            val calendar = TestSystemCalendar(
                calendars = listOf(updatedCalendar, newCalendar),
                eventFailure = IllegalStateException("calendar provider unavailable"),
            )
            val syncer = syncer(
                database,
                calendar,
                coroutineContext = backgroundScope.coroutineContext,
            )

            syncer.syncDeviceCalendarsToDb()

            assertEquals(1, calendar.eventCalls)
            assertEquals(listOf(seededCalendar), database.calendarDao().getAll())
            assertEquals(listOf(pin), database.timelinePinDao().getPinsForWatchapp(pin.content.parentId))

            calendar.eventFailure = null
            syncer.syncDeviceCalendarsToDb()

            assertEquals(3, calendar.eventCalls)
            assertEquals(
                setOf(seededCalendar.copy(
                    name = updatedCalendar.name,
                    ownerName = updatedCalendar.ownerName,
                    ownerId = updatedCalendar.ownerId,
                    color = updatedCalendar.color,
                    syncEvents = updatedCalendar.syncEvents,
                    visible = updatedCalendar.visible,
                ).withoutId(), newCalendar.withoutId()),
                database.calendarDao().getAll().map { it.withoutId() }.toSet(),
            )
            assertEquals(emptyList(), database.timelinePinDao().getPinsForWatchapp(pin.content.parentId))
        }
    }

    @Test
    fun eventCancellationIsRethrownWithoutMutatingTheExistingProjection() = runTest {
        withDatabase { database ->
            val pin = existingPin()
            val seededCalendar = seedCalendar(database)
            database.timelinePinDao().insertOrReplace(pin)
            val calendar = TestSystemCalendar(
                calendars = listOf(seededCalendar.copy(id = 0, name = "Updated calendar")),
                eventFailure = CancellationException("test cancellation"),
            )

            assertFailsWith<CancellationException> {
                syncer(
                    database,
                    calendar,
                    coroutineContext = backgroundScope.coroutineContext,
                ).syncDeviceCalendarsToDb()
            }
            assertEquals(listOf(seededCalendar), database.calendarDao().getAll())
            assertEquals(listOf(pin), database.timelinePinDao().getPinsForWatchapp(pin.content.parentId))
        }
    }

    @Test
    fun failedSyncAutomaticallyRetriesAndReconcilesAfterTheBackendRecovers() = runTest {
        withDatabase { database ->
            val pin = existingPin()
            val seededCalendar = seedCalendar(database)
            database.timelinePinDao().insertOrReplace(pin)
            val calendar = TestSystemCalendar(
                calendars = listOf(seededCalendar.copy(id = 0)),
                eventFailure = IllegalStateException("calendar provider unavailable"),
            )
            val syncer = syncer(
                database = database,
                calendar = calendar,
                coroutineContext = backgroundScope.coroutineContext,
                retryDelay = 1.seconds,
            )

            syncer.init()
            testScheduler.advanceTimeBy(1.seconds)
            runCurrent()
            calendar.awaitEventCall(1)

            assertEquals(1, calendar.eventCalls)
            assertEquals(listOf(pin), database.timelinePinDao().getPinsForWatchapp(pin.content.parentId))

            calendar.eventFailure = null
            runCurrent()
            testScheduler.advanceTimeBy(1.seconds)
            runCurrent()
            calendar.awaitEventCall(2)

            assertEquals(2, calendar.eventCalls)
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    while (database.timelinePinDao().getPinsForWatchapp(pin.content.parentId).isNotEmpty()) {
                        kotlinx.coroutines.delay(10.milliseconds)
                    }
                }
            }
        }
    }

    @Test
    fun projectionApplyRollsBackWhenALaterDaoWriteFails() = runTest {
        withDatabase { database ->
            val seededCalendar = seedCalendar(database)
            val updatedCalendar = seededCalendar.copy(name = "Updated calendar")
            @Suppress("UNCHECKED_CAST")
            val failingPinDao = Proxy.newProxyInstance(
                TimelinePinRealDao::class.java.classLoader,
                arrayOf(TimelinePinRealDao::class.java),
            ) { _, method, _ ->
                if (method.name == "insertOrReplace") {
                    throw IllegalStateException("injected pin write failure")
                }
                throw UnsupportedOperationException("Unexpected DAO call: ${method.name}")
            } as TimelinePinRealDao

            assertFailsWith<IllegalStateException> {
                database.calendarDao().applyProjection(
                    calendarsToUpdate = listOf(updatedCalendar),
                    calendarsToDelete = emptyList(),
                    calendarsToInsert = emptyList(),
                    pinsToInsert = listOf(existingPin()),
                    pinIdsToDelete = emptyList(),
                    remindersToInsert = emptyList(),
                    reminderIdsToDelete = emptyList(),
                    timelinePinDao = failingPinDao,
                    timelineReminderDao = database.timelineReminderDao(),
                )
            }
            assertEquals(listOf(seededCalendar), database.calendarDao().getAll())
        }
    }

    private suspend fun withDatabase(block: suspend (Database) -> Unit) {
        val directory = Files.createTempDirectory("phone-calendar-syncer-test-")
        val database = Room.databaseBuilder<Database>(
            name = directory.resolve("timeline.db").toString(),
        )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            block(database)
        } finally {
            database.close()
            directory.toFile().deleteRecursively()
        }
    }

    private fun syncer(
        database: Database,
        calendar: SystemCalendar,
        coroutineContext: CoroutineContext = Dispatchers.Default,
        retryDelay: kotlin.time.Duration = 1.seconds,
        watchConfig: WatchConfig = WatchConfig(),
    ) = PhoneCalendarSyncer(
        timelinePinDao = database.timelinePinDao(),
        calendarDao = database.calendarDao(),
        timeProvider = object : TimeProvider {
            override fun now() = Instant.fromEpochSeconds(1_000)
        },
        systemCalendar = calendar,
        libPebbleCoroutineScope = LibPebbleCoroutineScope(coroutineContext),
        timelineReminderDao = database.timelineReminderDao(),
        watchConfig = watchConfig.asFlow(),
        timelineWindowProvider = DefaultTimelineWindowProvider(),
        retryDelay = retryDelay,
    )

    private suspend fun seedCalendar(database: Database): CalendarEntity {
        database.calendarDao().insertOrReplace(calendar("calendar", "Test calendar"))
        return database.calendarDao().getAll().single()
    }

    private fun calendar(platformId: String, name: String) = CalendarEntity(
        platformId = platformId,
        name = name,
        ownerName = "Test owner",
        ownerId = "owner",
        color = 0,
        enabled = true,
    )

    private fun CalendarEntity.withoutId() = copy(id = 0)

    private fun existingPin() = buildTimelinePin(
        parentId = io.rebble.libpebblecommon.SystemAppIDs.CALENDAR_APP_UUID,
        timestamp = Instant.fromEpochSeconds(1_000),
    ) {
        backingId = "existing-event"
    }

    private fun event(calendarId: String) = CalendarEvent(
        id = "event",
        calendarId = calendarId,
        title = "Calendar event",
        description = "Description",
        location = null,
        startTime = Instant.fromEpochSeconds(2_000),
        endTime = Instant.fromEpochSeconds(2_600),
        allDay = false,
        attendees = emptyList(),
        recurs = false,
        reminders = listOf(EventReminder(minutesBefore = 10)),
        availability = CalendarEvent.Availability.Busy,
        status = CalendarEvent.Status.Confirmed,
        baseEventId = "event",
    )

    private class TestSystemCalendar(
        private val permissionGranted: Boolean = true,
        private val calendars: List<CalendarEntity> = emptyList(),
        private val events: List<CalendarEvent> = emptyList(),
        var eventFailure: Throwable? = null,
    ) : SystemCalendar {
        var calendarCalls = 0
        var eventCalls = 0
        private val eventCallCounts = Channel<Int>(Channel.UNLIMITED)

        override suspend fun getCalendars(): List<CalendarEntity> {
            calendarCalls++
            return calendars
        }

        override suspend fun getCalendarEvents(
            calendar: CalendarEntity,
            startDate: Instant,
            endDate: Instant,
        ): List<CalendarEvent> {
            eventCalls++
            eventCallCounts.trySend(eventCalls)
            eventFailure?.let { throw it }
            return events.filter { it.calendarId == calendar.platformId }
        }

        suspend fun awaitEventCall(expected: Int) {
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    while (eventCallCounts.receive() < expected) {
                        // Consume earlier calls until the requested attempt is observed.
                    }
                }
            }
        }

        override suspend fun enableSyncForCalendar(calendar: CalendarEntity) = Unit

        override fun registerForCalendarChanges(): Flow<Unit>? = null

        override fun hasPermission(): Boolean = permissionGranted

        override suspend fun createEvent(event: NewCalendarEvent): String? = null

        override fun supportsPinActions(): Boolean = false
    }
}
