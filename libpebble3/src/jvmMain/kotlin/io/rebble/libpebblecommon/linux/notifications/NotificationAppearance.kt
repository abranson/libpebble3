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
 * bridged out of an Android compatibility layer. Native apps fall through to the icon name, which
 * matters more here than on Android: Sailfish's own apps send no category at all, and their
 * app_icon ("icon-lock-sms") is the only hint to go on.
 */
internal fun appearanceFor(
    app: NotificationAppItem,
    n: MappedNotification,
): NotificationAppearance {
    val props = n.androidPackageName?.let { NotificationProperties.lookup(it) }
    return NotificationAppearance(
        icon = TimelineIcon.fromCode(app.iconCode)
            ?: props?.icon
            ?: iconForThemeName(n.iconName)
            ?: iconForCategory(n.category),
        colorArgb = TimelineColor.findByName(app.colorName)?.argbColor()
            ?: props?.color?.argbColor()
            ?: n.colorArgb,
    )
}

/**
 * Sailfish/Nemo theme icon name -> watch icon. These come from Notify's app_icon and are the only
 * usable signal for native apps, which send no category.
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
 * freedesktop categories are `type` or `type.subtype` (`im.received`, `email.arrived`); Sailfish
 * adds its own under an `x-nemo.` prefix (`x-nemo.call.missed`), and AppSupport passes Android's
 * through untranslated (`chat`). Stripping the prefix lets one table serve all three, and the
 * specific subtypes are matched before their parent type.
 */
internal fun iconForCategory(category: String?): TimelineIcon {
    val c = category?.lowercase()?.removePrefix("x-nemo.")
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
