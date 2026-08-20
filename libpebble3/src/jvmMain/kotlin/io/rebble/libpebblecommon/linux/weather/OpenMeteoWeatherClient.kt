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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToInt

/** A bounded two-day weather result suitable for the Pebble weather database. */
data class OpenMeteoForecast(
    val text: String,
    val temperature: Short,
    val timestampEpochSeconds: Long,
    val todayHigh: Short,
    val todayLow: Short,
    val todayIcon: WeatherType,
    val tomorrowHigh: Short,
    val tomorrowLow: Short,
    val tomorrowIcon: WeatherType,
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
    ): OpenMeteoForecast? {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        val response = request(openMeteoForecastUrl(latitude, longitude, imperial))
        if (response.status !in 200..299) return null
        return parseOpenMeteoForecast(response.body)
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
): String = URLBuilder(OPEN_METEO_FORECAST_ENDPOINT).apply {
    parameters.append("latitude", latitude.toString())
    parameters.append("longitude", longitude.toString())
    parameters.append("current", "temperature_2m,weather_code")
    parameters.append(
        "daily",
        "weather_code,temperature_2m_max,temperature_2m_min",
    )
    parameters.append("temperature_unit", if (imperial) "fahrenheit" else "celsius")
    parameters.append("timeformat", "unixtime")
    parameters.append("timezone", "auto")
    parameters.append("forecast_days", "2")
}.buildString()

internal fun parseOpenMeteoForecast(body: String): OpenMeteoForecast {
    val root = OPEN_METEO_JSON.parseToJsonElement(body).jsonObject
    val current = root.requiredObject("current")
    val daily = root.requiredObject("daily")
    val timestamp = current.requiredLong("time")
    require(timestamp in 0..UInt.MAX_VALUE.toLong()) { "Weather timestamp is out of range" }
    val currentCode = current.requiredInt("weather_code")
    val dailyCodes = daily.requiredArray("weather_code").requiredInts()
    val dailyHighs = daily.requiredArray("temperature_2m_max").requiredDoubles()
    val dailyLows = daily.requiredArray("temperature_2m_min").requiredDoubles()
    require(dailyCodes.size >= 2 && dailyHighs.size >= 2 && dailyLows.size >= 2) {
        "Weather forecast does not contain two days"
    }

    return OpenMeteoForecast(
        text = openMeteoWeatherText(currentCode),
        temperature = weatherTemperature(current.requiredDouble("temperature_2m")),
        timestampEpochSeconds = timestamp,
        todayHigh = weatherTemperature(dailyHighs[0]),
        todayLow = weatherTemperature(dailyLows[0]),
        todayIcon = openMeteoWeatherType(dailyCodes[0]),
        tomorrowHigh = weatherTemperature(dailyHighs[1]),
        tomorrowLow = weatherTemperature(dailyLows[1]),
        tomorrowIcon = openMeteoWeatherType(dailyCodes[1]),
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

private fun JsonArray.requiredInts(): List<Int> = map {
    it.jsonPrimitive.intOrNull ?: throw IllegalArgumentException("Weather code is invalid")
}

private fun JsonArray.requiredDoubles(): List<Double> = map {
    it.jsonPrimitive.doubleOrNull?.takeIf(Double::isFinite)
        ?: throw IllegalArgumentException("Weather temperature is invalid")
}

private fun weatherTemperature(value: Double): Short {
    val rounded = value.roundToInt()
    require(rounded in Short.MIN_VALUE..Short.MAX_VALUE) { "Weather temperature is out of range" }
    return rounded.toShort()
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
