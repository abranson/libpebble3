package io.rebble.libpebblecommon.connection.devconnection

import io.rebble.libpebblecommon.util.JvmPaths
import kotlinx.io.files.Path

internal actual fun getTempPbwPath(): Path {
    return Path(JvmPaths.cacheSubdir("devconnection").resolve("temp.pbw").toString())
}