package io.rebble.libpebblecommon.calendar

import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val PARIS = TimeZone.of("Europe/Paris")
private val NEW_YORK = TimeZone.of("America/New_York")

/** What PhoneCalendarSyncer writes as the reminder's timestamp. */
private fun reminderTime(startTime: Instant, reminder: EventReminder) =
    startTime - reminder.minutesBefore.minutes

class CalendarReminderTimingTest {
    @Test
    fun reminderHasDismissActionSoWatchCanOfferDismissAndSnooze() {
        val start = Instant.parse("2026-09-23T14:00:00Z")
        val event = CalendarEvent(
            id = "event", calendarId = "calendar", title = "Appointment",
            description = "", location = null, startTime = start,
            endTime = start + 30.minutes, allDay = false, attendees = emptyList(),
            recurs = false, reminders = emptyList(),
            availability = CalendarEvent.Availability.Busy,
            status = CalendarEvent.Status.Confirmed, baseEventId = "event",
        )
        val reminder = event.toTimelineReminder(start - 15.minutes, Uuid.random())
        assertEquals(TimelineItem.Action.Type.Dismiss, reminder.content.actions.single().type)
    }

    @Test
    fun alarmBeforeStartFiresBeforeStart() {
        val start = Instant.parse("2026-08-13T14:00:00Z")
        val reminder = EventReminder.fromStartOffset(-900.0)
        assertEquals(15, reminder.minutesBefore)
        assertEquals(Instant.parse("2026-08-13T13:45:00Z"), reminderTime(start, reminder))
    }

    @Test
    fun alarmAfterStartFiresAfterStart() {
        val start = Instant.parse("2026-08-13T00:00:00Z")
        val reminder = EventReminder.fromStartOffset(32400.0)
        assertEquals(-540, reminder.minutesBefore)
        assertEquals(Instant.parse("2026-08-13T09:00:00Z"), reminderTime(start, reminder))
    }

    @Test
    fun birthdayAlertLandsOnTheDayItself() {
        // EventKit reports the all-day event at local midnight, with a +9h "9am on the day" alarm.
        val start = Instant.parse("2026-08-12T22:00:00Z").anchorAllDayToUtc(PARIS)
        val reminderAt = reminderTime(start, EventReminder.fromStartOffset(32400.0))
        // Wall-clock 9am on the birthday; the watch applies the +2h offset itself.
        assertEquals(Instant.parse("2026-08-13T09:00:00Z"), reminderAt)
    }

    @Test
    fun allDayAnchoringUsesLocalDateAheadOfUtc() {
        assertEquals(
            Instant.parse("2026-08-13T00:00:00Z"),
            Instant.parse("2026-08-12T22:00:00Z").anchorAllDayToUtc(PARIS),
        )
    }

    @Test
    fun allDayAnchoringUsesLocalDateBehindUtc() {
        assertEquals(
            Instant.parse("2026-08-13T00:00:00Z"),
            Instant.parse("2026-08-13T04:00:00Z").anchorAllDayToUtc(NEW_YORK),
        )
    }

    @Test
    fun anchoringIsANoOpInUtc() {
        val midnight = Instant.parse("2026-08-13T00:00:00Z")
        assertEquals(midnight, midnight.anchorAllDayToUtc(TimeZone.UTC))
    }
}
