package io.rebble.libpebblecommon.util

import io.rebble.libpebblecommon.connection.AppContext
import kotlinx.io.files.Path

actual fun getTempFilePath(
    appContext: AppContext,
    name: String,
    subdir: String?,
): Path {
    val base = if (subdir != null) JvmPaths.cacheSubdir(subdir) else JvmPaths.cacheHome
    return Path(base.resolve(name).toString())
}