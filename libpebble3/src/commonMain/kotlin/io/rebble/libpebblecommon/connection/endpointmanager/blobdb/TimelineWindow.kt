package io.rebble.libpebblecommon.connection.endpointmanager.blobdb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/** The subset of timeline records which should currently be present on a watch. */
data class TimelineWindow(
    val pastDays: Int = DEFAULT_PAST_DAYS,
    val notificationFadeSeconds: Int = DEFAULT_NOTIFICATION_FADE_SECONDS,
    val futureDays: Int = DEFAULT_FUTURE_DAYS,
) {
    companion object {
        const val DEFAULT_PAST_DAYS = -2
        const val DEFAULT_NOTIFICATION_FADE_SECONDS = -60 * 60
        const val DEFAULT_FUTURE_DAYS = 7
        const val MAX_DAYS = 365
        const val MAX_NOTIFICATION_FADE_SECONDS = 30 * 24 * 60 * 60
    }
}

/** Returns a canonical window, or null when the requested range is unsafe. */
fun normalizedTimelineWindow(
    pastDays: Int,
    notificationFadeSeconds: Int,
    futureDays: Int,
): TimelineWindow? {
    val normalizedFade = if (notificationFadeSeconds > 0) -notificationFadeSeconds else notificationFadeSeconds
    if (pastDays !in -TimelineWindow.MAX_DAYS..-1 ||
        futureDays !in -TimelineWindow.MAX_DAYS..TimelineWindow.MAX_DAYS ||
        pastDays > futureDays ||
        abs(normalizedFade.toLong()) > TimelineWindow.MAX_NOTIFICATION_FADE_SECONDS
    ) {
        return null
    }
    return TimelineWindow(pastDays, normalizedFade, futureDays)
}

interface TimelineWindowProvider {
    /** Union window used to bound data produced independently of a watch. */
    val sourceWindow: StateFlow<TimelineWindow>

    /** The window currently configured for this specific watch transport. */
    fun windowFor(transport: String): StateFlow<TimelineWindow>
}

class DefaultTimelineWindowProvider : TimelineWindowProvider {
    private val defaultWindow = TimelineWindow()

    override val sourceWindow: StateFlow<TimelineWindow> = MutableStateFlow(defaultWindow)

    override fun windowFor(transport: String): StateFlow<TimelineWindow> = sourceWindow
}
