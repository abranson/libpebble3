package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.storage.SecureStorage
import io.rebble.libpebblecommon.plugin.files.PluginFileSystem
import kotlin.uuid.Uuid

actual class AppContext(
    internal val secureStorageProvider: ((Uuid) -> SecureStorage)? = null,
    internal val pluginFileSystem: PluginFileSystem? = null,
)
