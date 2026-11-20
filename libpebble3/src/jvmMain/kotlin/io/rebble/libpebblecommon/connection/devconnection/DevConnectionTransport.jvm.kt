package io.rebble.libpebblecommon.connection.devconnection

import io.rebble.libpebblecommon.util.JvmPaths
import kotlinx.io.files.Path
import java.util.UUID

internal actual fun getTempPath(extension: String): Path {
    val uuid = UUID.randomUUID().toString()
    return Path(JvmPaths.cacheSubdir("devconnection").resolve("devconn-$uuid.$extension").toString())
}
