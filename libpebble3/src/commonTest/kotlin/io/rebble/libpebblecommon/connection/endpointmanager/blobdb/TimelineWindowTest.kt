package io.rebble.libpebblecommon.connection.endpointmanager.blobdb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimelineWindowTest {
    @Test
    fun defaultsMatchLegacyTimelineWindow() {
        assertEquals(TimelineWindow(-2, -3600, 7), TimelineWindow())
    }

    @Test
    fun positiveFadeoutIsNormalizedToPast() {
        assertEquals(
            TimelineWindow(-2, -3600, 7),
            normalizedTimelineWindow(-2, 3600, 7),
        )
    }

    @Test
    fun acceptsNegativeFutureWhenItIsAfterThePastBoundary() {
        assertEquals(
            TimelineWindow(-10, 0, -2),
            normalizedTimelineWindow(-10, 0, -2),
        )
    }

    @Test
    fun rejectsInvalidOrUnboundedRanges() {
        assertNull(normalizedTimelineWindow(0, -3600, 7))
        assertNull(normalizedTimelineWindow(-2, -3600, -3))
        assertNull(normalizedTimelineWindow(-366, -3600, 7))
        assertNull(normalizedTimelineWindow(-2, -(31 * 24 * 60 * 60), 7))
    }
}
