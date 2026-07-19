package io.rebble.libpebblecommon.js

import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.PropertiesSettings
import com.russhwolf.settings.Settings
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.util.JvmPaths
import java.util.Properties

@OptIn(ExperimentalSettingsImplementation::class)
internal actual fun createJSSettings(
    appContext: AppContext,
    id: String
): Settings {
    val file = JvmPaths.dataSubdir("jsstorage").resolve("$id.properties").toFile()
    val properties = Properties()
    if (file.exists()) {
        file.inputStream().use { properties.load(it) }
    }
    return PropertiesSettings(properties) { toPersist ->
        file.outputStream().use { toPersist.store(it, null) }
    }
}