package io.rebble.libpebblecommon.web

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.util.JvmPaths
import kotlinx.io.files.Path

actual fun getFirmwareDownloadDirectory(context: AppContext): Path {
    return Path(JvmPaths.cacheSubdir("firmware").toString())
}