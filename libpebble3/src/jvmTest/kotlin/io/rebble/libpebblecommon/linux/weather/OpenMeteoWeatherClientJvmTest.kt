/*
 * SPDX-License-Identifier: Apache-2.0
 */
package io.rebble.libpebblecommon.linux.weather

import io.ktor.http.Url
import io.rebble.libpebblecommon.weather.WeatherType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenMeteoWeatherClientJvmTest {
    @Test
    fun `forecast URL carries coordinates units unix time and two days`() {
        val url = Url(openMeteoForecastUrl(51.5074, -0.1278, imperial = true))

        assertEquals("https", url.protocol.name)
        assertEquals("api.open-meteo.com", url.host)
        assertEquals("/v1/forecast", url.encodedPath)
        assertEquals("51.5074", url.parameters["latitude"])
        assertEquals("-0.1278", url.parameters["longitude"])
        assertEquals("fahrenheit", url.parameters["temperature_unit"])
        assertEquals("unixtime", url.parameters["timeformat"])
        assertEquals("auto", url.parameters["timezone"])
        assertEquals("2", url.parameters["forecast_days"])
        assertEquals("temperature_2m,weather_code", url.parameters["current"])
        assertEquals(
            "weather_code,temperature_2m_max,temperature_2m_min",
            url.parameters["daily"],
        )
    }

    @Test
    fun `successful response maps weather and rounds temperatures`() = runTest {
        var requestedUrl = ""
        val client = OpenMeteoWeatherClient.forTest { url ->
            requestedUrl = url
            OpenMeteoHttpResponse(200, response())
        }

        val forecast = client.fetch(48.8566, 2.3522, imperial = false)

        assertEquals("celsius", Url(requestedUrl).parameters["temperature_unit"])
        assertEquals(
            OpenMeteoForecast(
                text = "Rain",
                temperature = 13,
                timestampEpochSeconds = 1_710_000_000L,
                todayHigh = 21,
                todayLow = -2,
                todayIcon = WeatherType.LightRain,
                tomorrowHigh = 19,
                tomorrowLow = 4,
                tomorrowIcon = WeatherType.HeavySnow,
            ),
            forecast,
        )
    }

    @Test
    fun `all Open Meteo WMO groups have Pebble mappings`() {
        val expected = mapOf(
            0 to WeatherType.Sun,
            1 to WeatherType.PartlyCloudy,
            3 to WeatherType.CloudyDay,
            45 to WeatherType.CloudyDay,
            51 to WeatherType.LightRain,
            56 to WeatherType.RainAndSnow,
            61 to WeatherType.LightRain,
            65 to WeatherType.HeavyRain,
            71 to WeatherType.LightSnow,
            75 to WeatherType.HeavySnow,
            80 to WeatherType.LightRain,
            82 to WeatherType.HeavyRain,
            85 to WeatherType.LightSnow,
            86 to WeatherType.HeavySnow,
            95 to WeatherType.HeavyRain,
        )

        expected.forEach { (code, type) -> assertEquals(type, openMeteoWeatherType(code), "$code") }
        assertEquals(WeatherType.Generic, openMeteoWeatherType(999))
    }

    @Test
    fun `HTTP failure does not parse a forecast`() = runTest {
        val client = OpenMeteoWeatherClient.forTest {
            OpenMeteoHttpResponse(503, "not JSON")
        }

        assertNull(client.fetch(0.0, 0.0, imperial = false))
    }

    @Test
    fun `parser rejects malformed nonfinite and incomplete responses`() {
        assertFailsWith<IllegalArgumentException> { parseOpenMeteoForecast("{") }
        assertFailsWith<IllegalArgumentException> {
            parseOpenMeteoForecast(response(currentTemperature = "\"NaN\""))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOpenMeteoForecast(response(dailyCodes = "[61]"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOpenMeteoForecast(response(timestamp = "-1"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOpenMeteoForecast(response(timestamp = "4294967296"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOpenMeteoForecast(response(currentTemperature = "32767.6"))
        }
        assertFailsWith<IllegalArgumentException> {
            parseOpenMeteoForecast(response(dailyLows = "[-32768.6, 4.1]"))
        }
    }

    @Test
    fun `client rejects invalid coordinates before requesting`() {
        var requested = false
        val client = OpenMeteoWeatherClient.forTest {
            requested = true
            OpenMeteoHttpResponse(200, response())
        }

        listOf(
            Double.NaN to 0.0,
            Double.POSITIVE_INFINITY to 0.0,
            -90.1 to 0.0,
            90.1 to 0.0,
            0.0 to Double.NEGATIVE_INFINITY,
            0.0 to -180.1,
            0.0 to 180.1,
        ).forEach { (latitude, longitude) ->
            assertFailsWith<IllegalArgumentException> {
                runTest { client.fetch(latitude, longitude, imperial = false) }
            }
        }
        assertTrue(!requested)
    }

    private fun response(
        timestamp: String = "1710000000",
        currentTemperature: String = "12.6",
        dailyCodes: String = "[61, 86]",
        dailyHighs: String = "[20.6, 19.4]",
        dailyLows: String = "[-1.6, 4.1]",
    ): String = """
        {
          "current": {
            "time": $timestamp,
            "temperature_2m": $currentTemperature,
            "weather_code": 61
          },
          "daily": {
            "weather_code": $dailyCodes,
            "temperature_2m_max": $dailyHighs,
            "temperature_2m_min": $dailyLows
          }
        }
    """.trimIndent()
}
