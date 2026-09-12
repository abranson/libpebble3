package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.SystemAppIDs
import io.rebble.libpebblecommon.metadata.WatchType
import kotlin.uuid.Uuid

enum class SystemApps(
    val uuid: Uuid,
    val displayName: String,
    val type: AppType,
    val compatiblePlatforms: List<WatchType>,
    val defaultOrder: Int,
) {
    // Apps
    Settings(SystemAppIDs.SETTINGS_APP_UUID, "Settings", AppType.Watchapp, WatchType.entries, defaultOrder = -10),
    Music(SystemAppIDs.MUSIC_APP_UUID, "Music", AppType.Watchapp, WatchType.entries, defaultOrder = -9),
    Notifications(SystemAppIDs.NOTIFICATIONS_APP_UUID, "Notifications", AppType.Watchapp, WatchType.entries, defaultOrder = -8),
    SendText(SystemAppIDs.SMS_APP_UUID, "Send Text", AppType.Watchapp, ALL_EXCEPT_APLITE, defaultOrder = -7),
    Alarms(SystemAppIDs.ALARMS_APP_UUID, "Alarms", AppType.Watchapp, WatchType.entries, defaultOrder = -6),
    Weather(SystemAppIDs.WEATHER_APP_UUID, "Weather", AppType.Watchapp, ALL_EXCEPT_APLITE, defaultOrder = -5),
    Health(SystemAppIDs.HEALTH_APP_UUID, "Health", AppType.Watchapp, ALL_EXCEPT_APLITE, defaultOrder = -4),
    Workout(SystemAppIDs.WORKOUT_APP_UUID, "Workout", AppType.Watchapp, ALL_EXCEPT_APLITE, defaultOrder = -3),
    Watchfaces(SystemAppIDs.WATCHFACES_APP_UUID, "Watchfaces", AppType.Watchapp, WatchType.entries, defaultOrder = -2),
    Timeline(SystemAppIDs.TIMELINE_MENU_ENTRY_UUID, "Timeline", AppType.Watchapp, WatchType.entries, defaultOrder = 100),
    // Faces
    Tictoc(SystemAppIDs.TICTOC_APP_UUID, "Tictoc", AppType.Watchface, WatchType.entries, defaultOrder = -2),
    Kickstart(SystemAppIDs.KICKSTART_APP_UUID, "Kickstart", AppType.Watchface, ALL_EXCEPT_APLITE, defaultOrder = -1),
}

private val ALL_EXCEPT_APLITE = WatchType.entries.filter {
    it != WatchType.APLITE
}
