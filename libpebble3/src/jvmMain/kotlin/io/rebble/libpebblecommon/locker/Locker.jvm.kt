package io.rebble.libpebblecommon.locker

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.util.JvmPaths
import kotlinx.io.files.Path

actual fun getLockerPBWCacheDirectory(context: AppContext): Path {
    return Path(JvmPaths.cacheSubdir("locker").toString())
}

actual fun getLockerPBWCacheLegacyDirectory(context: AppContext): Path? {
    // No legacy location on desktop.
    return null
}