package io.rebble.libpebblecommon.linux.notifications

/** A D-Bus method to invoke when the user picks an action ("service path interface method"). */
data class RemoteAction(
    val service: String,
    val path: String,
    val iface: String,
    val method: String,
) {
    companion object {
        fun parse(value: String?): RemoteAction? {
            val tokens = value?.trim()?.split(Regex("\\s+")) ?: return null
            if (tokens.size < 4) return null
            return RemoteAction(tokens[0], tokens[1], tokens[2], tokens[3])
        }
    }
}

/** What a [NotificationHintMapper] pulled out of a raw [DBusNotification]. */
data class MappedNotification(
    /**
     * Pin title. The *sender* where the desktop distinguishes one — matching Android, which puts
     * EXTRA_TITLE (the person, for messaging apps) here. Falls back to the app's summary.
     */
    val title: String,
    val body: String,
    /** Stable key for mute state and the icon/colour table. */
    val packageName: String,
    val appName: String,
    /**
     * The Android package, when this came from an Android compatibility layer (Sailfish's
     * AppSupport). Lets [io.rebble.libpebblecommon.notification.processor.NotificationProperties]
     * resolve an icon/colour, since that table is keyed on Android package names.
     */
    val androidPackageName: String? = null,
    /** freedesktop category (`im.received`, `email.arrived`, …), for icon fallback. */
    val category: String? = null,
    /** Theme icon name from [DBusNotification.appIcon], if it was a name rather than a path. */
    val iconName: String? = null,
    /** The app's own accent colour as ARGB, where the desktop reports one. */
    val colorArgb: Int? = null,
    /** Action key -> method to invoke. Keys match [DBusNotification.actions]. */
    val remoteActions: Map<String, RemoteAction> = emptyMap(),
)

/**
 * Extracts watch-facing fields from a freedesktop notification.
 *
 * The Notify arguments are standardised but what apps put in them is not, and every desktop adds
 * its own hints — so the parts we actually care about (who sent this, which app owns it, what to
 * invoke when the user taps an action) are desktop-specific. [FreedesktopHintMapper] is the
 * lowest common denominator; a desktop with richer hints should bind its own.
 *
 * Returning null drops the notification.
 */
interface NotificationHintMapper {
    fun map(n: DBusNotification): MappedNotification?
}

/**
 * Plain freedesktop mapping, for desktops with no known extensions: summary/body verbatim, app
 * name as the package key, no remote actions.
 */
class FreedesktopHintMapper : NotificationHintMapper {
    override fun map(n: DBusNotification): MappedNotification? {
        if (n.summary.isEmpty() && n.body.isEmpty()) return null
        if (n.hintBool("transient")) return null
        val packageName = n.hintString("desktop-entry")
            ?: n.appName.ifEmpty { "unknown" }.lowercase().replace(' ', '-')
        return MappedNotification(
            title = n.summary,
            body = n.body,
            packageName = packageName,
            appName = n.appName.ifEmpty { packageName },
            category = n.hintString("category"),
            iconName = themeIconName(n.appIcon),
        )
    }
}

/**
 * The icon name from an app_icon value, or null if it was a bitmap path. Accepts both a bare name
 * ("icon-lock-sms") and the "image://theme/..." URI form.
 */
fun themeIconName(appIcon: String): String? = when {
    appIcon.isEmpty() -> null
    appIcon.startsWith("image://theme/") -> appIcon.removePrefix("image://theme/")
    // Anything path-like is a bitmap (AppSupport writes PNGs to /tmp/apkd/notif/).
    appIcon.startsWith("/") || appIcon.startsWith("file:") -> null
    else -> appIcon
}
