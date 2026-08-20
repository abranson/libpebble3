package io.rebble.libpebblecommon

import com.russhwolf.settings.PropertiesSettings
import java.util.Properties
import java.util.prefs.Preferences
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibPebbleConfigHolderJvmTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun absentStorageUsesAndPersistsDefaultAsFreshConfig() {
        val settings = PropertiesSettings(Properties())
        val defaultConfig = LibPebbleConfig(
            watchConfig = WatchConfig(calendarPins = false),
        )

        val holder = LibPebbleConfigHolder(defaultConfig, settings, json)

        assertFalse(holder.configLoadedFromStorage)
        assertTrue(holder.configGeneratedFromDefault)
        assertEquals(defaultConfig, holder.config.value)

        val reloaded = LibPebbleConfigHolder(LibPebbleConfig(), settings, json)
        assertTrue(reloaded.configLoadedFromStorage)
        assertTrue(reloaded.configGeneratedFromDefault)
        assertEquals(defaultConfig, reloaded.config.value)
    }

    @Test
    fun preExistingDefaultTrueIsReportedAsLoaded() {
        val settings = PropertiesSettings(Properties())
        val storedConfig = LibPebbleConfig()
        settings.putString("libpebble.settings", json.encodeToString(storedConfig))

        val holder = LibPebbleConfigHolder(
            defaultValue = storedConfig.copy(
                watchConfig = storedConfig.watchConfig.copy(calendarPins = false),
            ),
            settings = settings,
            json = json,
        )

        assertTrue(holder.configLoadedFromStorage)
        assertFalse(holder.configGeneratedFromDefault)
        assertTrue(holder.config.value.watchConfig.calendarPins)
        assertEquals(storedConfig, holder.config.value)
    }

    @Test
    fun preExistingNonDefaultValueIsReportedAndPreserved() {
        val settings = PropertiesSettings(Properties())
        val storedConfig = LibPebbleConfig(
            watchConfig = WatchConfig(
                calendarPins = false,
                calendarReminders = false,
            ),
        )
        settings.putString("libpebble.settings", json.encodeToString(storedConfig))

        val holder = LibPebbleConfigHolder(LibPebbleConfig(), settings, json)

        assertTrue(holder.configLoadedFromStorage)
        assertFalse(holder.configGeneratedFromDefault)
        assertFalse(holder.config.value.watchConfig.calendarPins)
        assertFalse(holder.config.value.watchConfig.calendarReminders)
        assertEquals(storedConfig, holder.config.value)
    }

    @Test
    fun generatedOriginSurvivesAnInterruptedFirstConfigSave() {
        val settings = PropertiesSettings(Properties())
        settings.putBoolean("libpebble.settings.generated-default", true)

        val holder = LibPebbleConfigHolder(LibPebbleConfig(), settings, json)

        assertFalse(holder.configLoadedFromStorage)
        assertTrue(holder.configGeneratedFromDefault)
        assertEquals(LibPebbleConfig(), holder.config.value)
    }

    @Test
    fun serializedLengthUsesTheExactJvmPreferencesBoundary() {
        val settings = PropertiesSettings(Properties())
        val holder = LibPebbleConfigHolder(LibPebbleConfig(), settings, json)
        val template = LibPebbleConfig().let { config ->
            config.copy(
                notificationConfig = config.notificationConfig.copy(cannedResponses = listOf("")),
            )
        }
        val valueLength = Preferences.MAX_VALUE_LENGTH - holder.serializedLength(template)
        val exact = template.copy(
            notificationConfig = template.notificationConfig.copy(
                cannedResponses = listOf("x".repeat(valueLength)),
            ),
        )
        val oversized = exact.copy(
            notificationConfig = exact.notificationConfig.copy(
                cannedResponses = listOf("x".repeat(valueLength + 1)),
            ),
        )

        assertEquals(Preferences.MAX_VALUE_LENGTH, holder.serializedLength(exact))
        assertEquals(Preferences.MAX_VALUE_LENGTH + 1, holder.serializedLength(oversized))
    }
}
