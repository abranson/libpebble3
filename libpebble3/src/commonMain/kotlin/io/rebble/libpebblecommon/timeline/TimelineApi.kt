package io.rebble.libpebblecommon.timeline

import io.rebble.libpebblecommon.connection.Timeline
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.database.dao.TimelinePinRealDao
import io.rebble.libpebblecommon.database.entity.TimelinePin
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.locker.Locker
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class TimelineApi(
    private val timelinePinRealDao: TimelinePinRealDao,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val locker: Locker,
    private val webServices: WebServices,
) : Timeline {
    override fun insertOrReplace(pin: TimelinePin) {
        libPebbleCoroutineScope.launch {
            if (webServices.usesAccountSessions()) {
                locker.mutateForLiveLockerApp(pin.content.parentId) {
                    timelinePinRealDao.insertOrReplace(pin)
                }
            } else {
                timelinePinRealDao.insertOrReplace(pin)
            }
        }
    }

    override fun delete(pinUuid: Uuid) {
        libPebbleCoroutineScope.launch {
            timelinePinRealDao.markForDeletion(pinUuid)
        }
    }
}
