package io.rebble.libpebblecommon.disk.pbw

import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryAppstoreData
import io.rebble.libpebblecommon.web.LockerEntryPlatform
import kotlin.uuid.Uuid

/** Store identity and artwork for a downloaded PBW, independent of cloud locker membership. */
data class StorePbwMetadata(
    val uuid: Uuid,
    val appstoreData: LockerEntryAppstoreData,
    val platforms: List<LockerEntryPlatform>,
) {
    internal fun applyTo(entry: LockerEntry): LockerEntry {
        require(entry.id == uuid) { "Store metadata does not match the PBW UUID" }
        require(!appstoreData.storeId.isNullOrBlank()) { "Store metadata has no store ID" }
        return entry.copy(
            appstoreData = appstoreData,
            // Keep the PBW's actual variants, flags and embedded icon resource IDs.
            platforms = entry.platforms.map { platform ->
                val store = platforms.firstOrNull { it.name == platform.name }
                    ?: return@map platform
                platform.copy(
                    screenshotImageUrl = store.images.screenshot.takeIf { it.isNotBlank() },
                    listImageUrl = store.images.list.takeIf { it.isNotBlank() },
                    iconImageUrl = store.images.icon.takeIf { it.isNotBlank() },
                    description = store.description,
                )
            },
        )
    }
}
