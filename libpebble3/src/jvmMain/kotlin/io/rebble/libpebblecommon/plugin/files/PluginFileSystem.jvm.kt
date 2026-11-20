package io.rebble.libpebblecommon.plugin.files

import com.russhwolf.settings.Settings
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.plugin.PluginErrors
import io.rebble.libpebblecommon.plugin.PluginNativeException

internal actual fun platformPluginFileSystem(appContext: AppContext, settings: Settings): PluginFileSystem =
    UnavailablePluginFileSystem

/** The JVM target has no platform file picker that can grant access to a scope. */
private object UnavailablePluginFileSystem : PluginFileSystem {
    override suspend fun retain(scope: String, handle: String) = unavailable()
    override suspend fun release(scope: String) = Unit
    override suspend fun hasAccess(scope: String) = false
    override suspend fun stat(scope: String, path: List<String>): FileEntryInfo? = null
    override suspend fun list(scope: String, path: List<String>): List<FileEntryInfo> = unavailable()
    override suspend fun read(scope: String, path: List<String>): ByteArray = unavailable()
    override suspend fun write(scope: String, path: List<String>, data: ByteArray, create: Boolean) = unavailable()
    override suspend fun createDirectory(scope: String, path: List<String>) = unavailable()
    override suspend fun remove(scope: String, path: List<String>, recursive: Boolean) = unavailable()

    private fun unavailable(): Nothing =
        throw PluginNativeException(PluginErrors.PERMISSION_DENIED, "File access is unavailable on this platform")
}
