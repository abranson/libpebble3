package io.rebble.libpebblecommon.weather

import io.rebble.libpebblecommon.database.dao.WeatherAppRealDao
import io.rebble.libpebblecommon.database.entity.AppPrefsEntryDao
import io.rebble.libpebblecommon.database.entity.WeatherAppEntry
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class WeatherManagerTest {
    @Test
    fun queuedUpdatesApplyOnlyTheNewestSnapshot() = runTest {
        val first = populated()
        val newest = populated()
        data class Snapshot(val entries: List<WeatherAppEntry>, val keys: List<Uuid>)
        val snapshots = mutableListOf<Snapshot>()
        val weatherDao = proxy<WeatherAppRealDao> { method, args ->
            when (method.name) {
                "getAllFlow" -> kotlinx.coroutines.flow.emptyFlow<List<WeatherAppEntry>>()
                "replaceSnapshot" -> snapshots += Snapshot(
                    entries = args!![0] as List<WeatherAppEntry>,
                    keys = args[1] as List<Uuid>,
                )
                else -> error("unexpected weather DAO call: ${method.name}")
            }
        }
        val prefsDao = proxy<AppPrefsEntryDao> { method, args ->
            error("unexpected preferences DAO call: ${method.name}(${args?.contentToString()})")
        }
        val manager = WeatherManager(
            weatherDao,
            LibPebbleCoroutineScope(backgroundScope.coroutineContext),
            prefsDao,
        )

        manager.updateWeatherData(listOf(first))
        manager.updateWeatherData(listOf(newest))
        runCurrent()

        assertEquals(
            listOf(Snapshot(listOf(newest.toWeatherAppEntry()), listOf(newest.key))),
            snapshots,
        )
    }

    private fun populated(key: Uuid = Uuid.random()) = WeatherLocationData.WeatherLocationDataPopulated(
        key = key,
        currentTemp = 20,
        currentWeatherType = WeatherType.Sun,
        todayHighTemp = 22,
        todayLowTemp = 15,
        tomorrowWeatherType = WeatherType.CloudyDay,
        tomorrowHighTemp = 21,
        tomorrowLowTemp = 14,
        lastUpdateTimeUtcSecs = 1,
        isCurrentLocation = true,
        locationName = "Test location",
        forecastShort = "Sunny",
    )

    private inline fun <reified T : Any> proxy(
        crossinline handler: (Method, Array<out Any?>?) -> Any?,
    ): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "Test${T::class.java.simpleName}"
            else -> handler(method, args)
        }
    } as T
}
