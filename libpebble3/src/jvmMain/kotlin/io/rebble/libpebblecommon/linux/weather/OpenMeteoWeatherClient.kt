/*
 * SPDX-License-Identifier: Apache-2.0
 */
package io.rebble.libpebblecommon.linux.weather

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLBuilder
import io.rebble.libpebblecommon.weather.WeatherType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToInt

/** Two-day Pebble weather with optional daily forecasts for timeline pins. */
data class OpenMeteoForecast @JvmOverloads constructor(
    val text: String,
    val temperature: Short,
    val timestampEpochSeconds: Long,
    val todayHigh: Short,
    val todayLow: Short,
    val todayIcon: WeatherType,
    val tomorrowHigh: Short,
    val tomorrowLow: Short,
    val tomorrowIcon: WeatherType,
    val pinDays: List<OpenMeteoPinDay> = emptyList(),
)

@Serializable
data class OpenMeteoPinDay(
    val sunriseEpochSeconds: Long?,
    val sunsetEpochSeconds: Long?,
    val high: Short,
    val low: Short,
    val weatherCode: Int,
)

internal data class OpenMeteoHttpResponse(
    val status: Int,
    val body: String,
)

/** Keyless, bounded Open-Meteo forecast client for native Linux integrations. */
class OpenMeteoWeatherClient private constructor(
    private val request: suspend (String) -> OpenMeteoHttpResponse,
) {
    constructor() : this(::requestOpenMeteo)

    suspend fun fetch(
        latitude: Double,
        longitude: Double,
        imperial: Boolean,
    ): OpenMeteoForecast? = fetch(latitude, longitude, imperial, includePinDays = false)

    suspend fun fetch(
        latitude: Double,
        longitude: Double,
        imperial: Boolean,
        includePinDays: Boolean,
    ): OpenMeteoForecast? {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        val response = request(openMeteoForecastUrl(latitude, longitude, imperial, includePinDays))
        if (response.status !in 200..299) return null
        return parseOpenMeteoForecast(response.body, includePinDays)
    }

    companion object {
        internal fun forTest(
            request: suspend (String) -> OpenMeteoHttpResponse,
        ): OpenMeteoWeatherClient = OpenMeteoWeatherClient(request)
    }
}

internal fun openMeteoForecastUrl(
    latitude: Double,
    longitude: Double,
    imperial: Boolean,
    includePinDays: Boolean = false,
): String = URLBuilder(OPEN_METEO_FORECAST_ENDPOINT).apply {
    parameters.append("latitude", latitude.toString())
    parameters.append("longitude", longitude.toString())
    parameters.append("current", "temperature_2m,weather_code")
    val dailyFields = "weather_code,temperature_2m_max,temperature_2m_min"
    parameters.append("daily", if (includePinDays) "$dailyFields,sunrise,sunset" else dailyFields)
    parameters.append("temperature_unit", if (imperial) "fahrenheit" else "celsius")
    parameters.append("timeformat", "unixtime")
    parameters.append("timezone", "auto")
    parameters.append("forecast_days", if (includePinDays) PIN_FORECAST_DAYS.toString() else "2")
}.buildString()

internal fun parseOpenMeteoForecast(body: String, includePinDays: Boolean = false): OpenMeteoForecast {
    val root = OPEN_METEO_JSON.parseToJsonElement(body).jsonObject
    val current = root.requiredObject("current")
    val daily = root.requiredObject("daily")
    val timestamp = current.requiredLong("time")
    require(timestamp in 0..UInt.MAX_VALUE.toLong()) { "Weather timestamp is out of range" }
    val currentCode = current.requiredInt("weather_code")
    val codes = daily.requiredArray("weather_code")
    val highs = daily.requiredArray("temperature_2m_max")
    val lows = daily.requiredArray("temperature_2m_min")
    val dayStarts = daily["time"] as? JsonArray
    val sunrises = daily["sunrise"] as? JsonArray
    val sunsets = daily["sunset"] as? JsonArray
    val days = buildList {
        val count = if (includePinDays) PIN_FORECAST_DAYS else 2
        for (index in 0 until count) {
            val code = (codes.getOrNull(index) as? JsonPrimitive)?.intOrNull ?: break
            val high = highs.temperatureAt(index) ?: break
            val low = lows.temperatureAt(index) ?: break
            val dayStart = dayStarts.epochSecondsAt(index)
            add(OpenMeteoPinDay(
                sunriseEpochSeconds = sunrises.solarEventAt(index, dayStart, timestamp),
                sunsetEpochSeconds = sunsets.solarEventAt(index, dayStart, timestamp),
                high = high,
                low = low,
                weatherCode = code,
            ))
        }
    }
    require(days.size >= 2) { "Weather forecast does not contain two complete days" }
    return OpenMeteoForecast(
        text = openMeteoWeatherText(currentCode),
        temperature = requireNotNull(weatherTemperature(current.requiredDouble("temperature_2m"))) {
            "Weather temperature is out of range"
        },
        timestampEpochSeconds = timestamp,
        todayHigh = days[0].high,
        todayLow = days[0].low,
        todayIcon = openMeteoWeatherType(days[0].weatherCode),
        tomorrowHigh = days[1].high,
        tomorrowLow = days[1].low,
        tomorrowIcon = openMeteoWeatherType(days[1].weatherCode),
        pinDays = if (includePinDays) days else emptyList(),
    )
}

internal fun openMeteoWeatherType(code: Int): WeatherType = when (code) {
    0 -> WeatherType.Sun
    1, 2 -> WeatherType.PartlyCloudy
    3, 45, 48 -> WeatherType.CloudyDay
    51, 53, 55, 61, 63, 80, 81 -> WeatherType.LightRain
    65, 82, 95, 96, 99 -> WeatherType.HeavyRain
    56, 57, 66, 67 -> WeatherType.RainAndSnow
    71, 73, 77, 85 -> WeatherType.LightSnow
    75, 86 -> WeatherType.HeavySnow
    else -> WeatherType.Generic
}

internal fun openMeteoWeatherText(code: Int): String = when (code) {
    0 -> "Clear"
    1, 2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    51, 53, 55 -> "Drizzle"
    56, 57, 66, 67 -> "Freezing rain"
    61, 63, 80, 81 -> "Rain"
    65, 82 -> "Heavy rain"
    71, 73, 77, 85 -> "Snow"
    75, 86 -> "Heavy snow"
    95, 96, 99 -> "Thunderstorm"
    else -> "Weather"
}

private fun JsonObject.requiredObject(name: String): JsonObject =
    get(name)?.jsonObject ?: throw IllegalArgumentException("Weather $name is missing")

private fun JsonObject.requiredArray(name: String): JsonArray =
    get(name)?.jsonArray ?: throw IllegalArgumentException("Weather $name is missing")

private fun JsonObject.requiredLong(name: String): Long =
    get(name)?.jsonPrimitive?.longOrNull
        ?: throw IllegalArgumentException("Weather $name is invalid")

private fun JsonObject.requiredInt(name: String): Int =
    get(name)?.jsonPrimitive?.intOrNull
        ?: throw IllegalArgumentException("Weather $name is invalid")

private fun JsonObject.requiredDouble(name: String): Double =
    get(name)?.jsonPrimitive?.doubleOrNull?.takeIf(Double::isFinite)
        ?: throw IllegalArgumentException("Weather $name is invalid")

private fun JsonArray.temperatureAt(index: Int): Short? {
    val value = (getOrNull(index) as? JsonPrimitive)?.doubleOrNull ?: return null
    return weatherTemperature(value)
}

private fun JsonArray?.epochSecondsAt(index: Int): Long? =
    (this?.getOrNull(index) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

private fun JsonArray?.solarEventAt(index: Int, dayStart: Long?, forecastTime: Long): Long? {
    val event = epochSecondsAt(index) ?: return null
    val forecastWindow = (forecastTime - SECONDS_PER_DAY)..(forecastTime + PIN_FORECAST_DAYS * SECONDS_PER_DAY)
    if (event !in 1..UInt.MAX_VALUE.toLong() || event !in forecastWindow) return null
    // Open-Meteo uses day boundaries as placeholders during polar day and night.
    return event.takeIf { dayStart == null || it - dayStart in 1 until SECONDS_PER_DAY }
}

private fun weatherTemperature(value: Double): Short? {
    if (!value.isFinite()) return null
    return value.roundToInt().takeIf { it in Short.MIN_VALUE..Short.MAX_VALUE }?.toShort()
}

private suspend fun requestOpenMeteo(url: String): OpenMeteoHttpResponse {
    val response = OPEN_METEO_HTTP_CLIENT.get(url)
    return OpenMeteoHttpResponse(response.status.value, response.bodyAsText())
}

private val OPEN_METEO_HTTP_CLIENT by lazy {
    HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000L
            connectTimeoutMillis = 5_000L
            socketTimeoutMillis = 10_000L
        }
    }
}
private val OPEN_METEO_JSON = Json { ignoreUnknownKeys = true }
private const val OPEN_METEO_FORECAST_ENDPOINT = "https://api.open-meteo.com/v1/forecast"
private const val PIN_FORECAST_DAYS = 3
private const val SECONDS_PER_DAY = 86_400L
