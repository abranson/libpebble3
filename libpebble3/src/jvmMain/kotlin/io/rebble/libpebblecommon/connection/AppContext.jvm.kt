package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.storage.SecureStorage
import kotlin.uuid.Uuid

actual class AppContext(
    internal val secureStorageProvider: ((Uuid) -> SecureStorage)? = null,
)
