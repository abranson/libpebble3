package io.rebble.libpebblecommon.linux.notifications

import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.notification.processor.NotificationProperties
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.timeline.TimelineColor
import io.rebble.libpebblecommon.timeline.argbColor

/** A notification's watch appearance. Null colour means "leave the firmware default". */
internal data class NotificationAppearance(
    val icon: TimelineIcon,
    val colorArgb: Int?,
)

/**
 * Resolves icon and colour, mirroring the precedence Android's BasicNotificationProcessor uses:
 * the user's per-app override first, then the per-package table, then whatever the notification
 * itself told us.
 *
 * The user override ([NotificationAppItem.iconCode] / [NotificationAppItem.colorName]) always wins.
 * Everything below it is only a default for apps the user hasn't customised — the tables are not
 * meant to be exhaustive.
 *
 * [NotificationProperties] is keyed on *Android* package names, so it only matches notifications
 * bridged out of an Android compatibility layer. Platform backends can supply a default icon
 * before falling through to the freedesktop theme icon and category hints.
 */
internal fun appearanceFor(
    app: NotificationAppItem,
    n: MappedNotification,
): NotificationAppearance {
    val props = n.androidPackageName?.let { NotificationProperties.lookup(it) }
    return NotificationAppearance(
        icon = TimelineIcon.fromCode(app.iconCode)
            ?: props?.icon
            ?: n.defaultIcon
            ?: iconForThemeName(n.iconName)
            ?: iconForCategory(n.category),
        colorArgb = TimelineColor.findByName(app.colorName)?.argbColor()
            ?: props?.color?.argbColor()
            ?: n.colorArgb,
    )
}

/**
 * Freedesktop theme icon name -> watch icon.
 */
internal fun iconForThemeName(name: String?): TimelineIcon? {
    val n = name?.lowercase() ?: return null
    return when {
        "missed-call" in n -> TimelineIcon.TimelineMissedCall
        "call" in n -> TimelineIcon.IncomingPhoneCall
        "sms" in n || "message" in n || "chat" in n -> TimelineIcon.GenericSms
        "email" in n || "mail" in n -> TimelineIcon.GenericEmail
        "calendar" in n -> TimelineIcon.TimelineCalendar
        "alarm" in n -> TimelineIcon.AlarmClock
        else -> null
    }
}

/**
 * Notification category -> watch icon. Mirrors Android's `iconForCategory`.
 *
 * Freedesktop categories are `type` or `type.subtype` (`im.received`,
 * `email.arrived`). Specific subtypes are matched before their parent type.
 */
internal fun iconForCategory(category: String?): TimelineIcon {
    val c = category?.lowercase()
        ?: return TimelineIcon.NotificationGeneric
    return when {
        c.startsWith("call.missed") -> TimelineIcon.TimelineMissedCall
        c.startsWith("call") -> TimelineIcon.IncomingPhoneCall
        c.startsWith("email") -> TimelineIcon.GenericEmail
        c.startsWith("im") || c.startsWith("messaging") || c.startsWith("sms") ||
            c.startsWith("chat") -> TimelineIcon.GenericSms
        c.startsWith("social") -> TimelineIcon.NewsEvent
        c.startsWith("network") -> TimelineIcon.CheckInternetConnection
        c.startsWith("device") -> TimelineIcon.Settings
        c.startsWith("transfer") -> TimelineIcon.ArrowDown
        c.startsWith("alarm") -> TimelineIcon.AlarmClock
        c.startsWith("event") || c.startsWith("calendar") -> TimelineIcon.TimelineCalendar
        c.startsWith("reminder") -> TimelineIcon.NotificationReminder
        c.startsWith("error") -> TimelineIcon.GenericWarning
        else -> TimelineIcon.NotificationGeneric
    }
}
