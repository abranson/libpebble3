package io.rebble.libpebblecommon.storage

import io.rebble.libpebblecommon.connection.AppContext
import java.io.IOException
import kotlin.uuid.Uuid

internal actual fun pluginSecureStorage(appContext: AppContext, pluginUuid: Uuid): SecureStorage =
    appContext.secureStorageProvider?.invoke(pluginUuid) ?: UnavailableSecureStorage

private object UnavailableSecureStorage : SecureStorage {
    override suspend fun get(key: String): String? = unavailable()
    override suspend fun set(key: String, value: String) = unavailable()
    override suspend fun remove(key: String) = unavailable()
    override suspend fun destroy() = Unit

    private fun unavailable(): Nothing = throw IOException("Platform secure storage is unavailable")
}
