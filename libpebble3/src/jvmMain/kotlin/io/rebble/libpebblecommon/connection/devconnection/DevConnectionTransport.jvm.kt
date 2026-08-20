package io.rebble.libpebblecommon.connection.devconnection

import io.rebble.libpebblecommon.util.JvmPaths
import kotlinx.io.files.Path
import java.util.UUID

internal actual fun getTempPbwPath(): Path {
    val uuid = UUID.randomUUID().toString()
    return Path(JvmPaths.cacheSubdir("devconnection").resolve("devconn-$uuid.pbw").toString())
}
