package io.rebble.libpebblecommon.linux.notifications

import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class NotificationAppearanceTest {
    @Test
    fun platformDefaultTakesPrecedenceOverThemeAndCategoryHints() {
        assertEquals(TimelineIcon.NotificationSignal,
            appearanceFor(app(), notification().copy(defaultIcon = TimelineIcon.NotificationSignal)).icon)
    }

    @Test
    fun userOverridesAndAndroidPackagesTakePrecedenceOverPlatformDefault() {
        val mapped = notification().copy(defaultIcon = TimelineIcon.NotificationSignal)
        assertEquals(TimelineIcon.NotificationDiscord,
            appearanceFor(app().copy(iconCode = TimelineIcon.NotificationDiscord.code), mapped).icon)
        assertEquals(TimelineIcon.NotificationWhatsapp,
            appearanceFor(app(), mapped.copy(androidPackageName = "com.whatsapp")).icon)
    }

    @Test
    fun missingPlatformDefaultPreservesThemeCategoryAndGenericFallbacks() {
        assertEquals(TimelineIcon.GenericEmail, appearanceFor(app(), notification()).icon)
        assertEquals(TimelineIcon.TimelineMissedCall,
            appearanceFor(app(), notification().copy(iconName = null)).icon)
        assertEquals(TimelineIcon.NotificationGeneric,
            appearanceFor(app(), notification().copy(iconName = null, category = null)).icon)
    }

    private fun notification() = MappedNotification(
        title = "Sender", body = "Message", packageName = "org.example.chat", appName = "Chat",
        androidPackageName = "org.example.chat", iconName = "mail-unread", category = "call.missed",
    )

    private fun app(): NotificationAppItem {
        val epoch = Instant.fromEpochSeconds(0).asMillisecond()
        return NotificationAppItem(
            packageName = "org.example.chat", name = "Chat", muteState = MuteState.Never,
            channelGroups = emptyList(), stateUpdated = epoch, lastNotified = epoch,
            vibePatternName = null, colorName = null, iconCode = null,
        )
    }
}
