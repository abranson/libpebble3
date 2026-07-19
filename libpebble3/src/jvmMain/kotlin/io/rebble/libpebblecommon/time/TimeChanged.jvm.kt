package io.rebble.libpebblecommon.time

import io.rebble.libpebblecommon.connection.AppContext

/**
 * Desktop has no system broadcast for time/timezone changes (the Android impl listens for
 * `ACTION_TIME_CHANGED`). We register a no-op; explicit time sync still happens on connect.
 */
private class JvmTimeChanged : TimeChanged {
    override fun registerForTimeChanges(onChanged: () -> Unit) {
        // no-op
    }
}

actual fun createTimeChanged(appContext: AppContext): TimeChanged = JvmTimeChanged()