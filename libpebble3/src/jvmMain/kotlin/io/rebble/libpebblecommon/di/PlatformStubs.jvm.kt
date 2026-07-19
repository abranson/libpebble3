package io.rebble.libpebblecommon.di

import androidx.compose.ui.graphics.ImageBitmap
import io.rebble.libpebblecommon.calendar.CalendarEvent
import io.rebble.libpebblecommon.calendar.NewCalendarEvent
import io.rebble.libpebblecommon.calendar.PlatformCalendarActionHandler
import io.rebble.libpebblecommon.calendar.SystemCalendar
import io.rebble.libpebblecommon.calls.Call
import io.rebble.libpebblecommon.calls.LegacyPhoneReceiver
import io.rebble.libpebblecommon.calls.MissedCall
import io.rebble.libpebblecommon.calls.SystemCallLog
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.OtherPebbleApp
import io.rebble.libpebblecommon.connection.OtherPebbleApps
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.contacts.SystemContact
import io.rebble.libpebblecommon.contacts.SystemContacts
import io.rebble.libpebblecommon.database.entity.BaseAction
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import io.rebble.libpebblecommon.database.entity.TimelinePin
import io.rebble.libpebblecommon.music.PlaybackStatus
import io.rebble.libpebblecommon.music.SystemMusicControl
import io.rebble.libpebblecommon.notification.NotificationAppsSync
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import io.rebble.libpebblecommon.util.GeolocationPositionResult
import io.rebble.libpebblecommon.util.SystemGeolocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * No-op platform integrations for the Linux desktop (BLE-only) build. These satisfy the Koin
 * graph's contract for OS-integration interfaces that are out of scope for the initial port
 * (calendar/calls/music/geolocation/contacts/notifications). They are wired up in
 * [platformModule]; replacing any of them with a real implementation is purely additive.
 */

internal class JvmSystemCalendar : SystemCalendar {
    override suspend fun getCalendars(): List<CalendarEntity> = emptyList()
    override suspend fun getCalendarEvents(
        calendar: CalendarEntity,
        startDate: Instant,
        endDate: Instant
    ): List<CalendarEvent> = emptyList()

    override suspend fun enableSyncForCalendar(calendar: CalendarEntity) {}
    override fun registerForCalendarChanges(): Flow<Unit>? = null
    override fun hasPermission(): Boolean = false
    override suspend fun createEvent(event: NewCalendarEvent): String? = null
    override fun supportsPinActions(): Boolean = false
}

internal class JvmPlatformCalendarActionHandler : PlatformCalendarActionHandler {
    override suspend fun invoke(pin: TimelinePin, action: BaseAction): TimelineActionResult =
        TimelineActionResult(success = false, icon = TimelineIcon.NotificationFlag, title = "")
}

internal class JvmSystemCallLog : SystemCallLog {
    override suspend fun getMissedCalls(start: Instant): List<MissedCall> = emptyList()
    override fun registerForMissedCallChanges(): Flow<Unit> = emptyFlow()
    override fun hasPermission(): Boolean = false
}

internal class JvmLegacyPhoneReceiver : LegacyPhoneReceiver {
    override fun init(currentCall: kotlinx.coroutines.flow.MutableStateFlow<Call?>) {}
}

internal class JvmSystemMusicControl : SystemMusicControl {
    override fun play() {}
    override fun pause() {}
    override fun playPause() {}
    override fun nextTrack() {}
    override fun previousTrack() {}
    override fun volumeDown() {}
    override fun volumeUp() {}
    override val playbackState: StateFlow<PlaybackStatus?> = MutableStateFlow<PlaybackStatus?>(null)
}

internal class JvmSystemGeolocation : SystemGeolocation {
    override suspend fun getCurrentPosition(
        maximumAge: Duration?,
        timeout: Duration?,
        highAccuracy: Boolean
    ): GeolocationPositionResult =
        GeolocationPositionResult.Error("Geolocation not supported on desktop")

    override suspend fun watchPosition(
        interval: Duration,
        highAccuracy: Boolean
    ): Flow<GeolocationPositionResult> = emptyFlow()
}

internal class JvmSystemContacts : SystemContacts {
    override fun registerForContactsChanges(): Flow<Unit> = emptyFlow()
    override suspend fun getContacts(): List<SystemContact> = emptyList()
    override fun hasPermission(): Boolean = false
    override suspend fun getContactImage(lookupKey: String): ImageBitmap? = null
}

internal class JvmNotificationListenerConnection : NotificationListenerConnection {
    override fun init(libPebble: LibPebble) {}
}

internal class JvmNotificationAppsSync : NotificationAppsSync {
    override fun init() {}
}

internal class JvmPlatformNotificationActionHandler : PlatformNotificationActionHandler {
    override suspend fun invoke(
        itemId: Uuid,
        action: BaseAction,
        attributes: List<TimelineItem.Attribute>
    ): TimelineActionResult =
        TimelineActionResult(success = false, icon = TimelineIcon.NotificationFlag, title = "")
}

internal class JvmOtherPebbleApps : OtherPebbleApps {
    override fun otherPebbleCompanionAppsInstalled(): StateFlow<List<OtherPebbleApp>> =
        MutableStateFlow<List<OtherPebbleApp>>(emptyList())
}
