/*
 * Copyright 2026, Lawnchair
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.weather

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Talks to Open-Meteo, which needs no API key and no account.
 *
 * Only the coordinates of the place the user picked are sent; nothing about the
 * device or the user leaves here.
 */
object WeatherClient {

    private const val TAG = "WeatherClient"
    private const val GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"
    private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    private const val TIMEOUT_MS = 15_000

    /**
     * Looks up places by name. Open-Meteo matches on the romanised name, but with
     * language=zh it returns localised labels, so searching "Taipei" yields 台北市.
     */
    fun search(query: String, language: String): List<WeatherPlace> {
        val url = "$GEOCODE_URL?name=${encode(query)}&count=8&language=$language&format=json"
        val body = get(url) ?: return emptyList()
        return try {
            val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
            (0 until results.length()).mapNotNull { i ->
                val item = results.optJSONObject(i) ?: return@mapNotNull null
                val name = item.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                // The admin area disambiguates the many same-named towns. Some
                // entries carry alternative spellings as "A or B"; only the first
                // is worth showing.
                val admin = item.optString("admin1").substringBefore(" or ").trim()
                val region = listOfNotNull(
                    admin.takeIf { it.isNotEmpty() && it != name },
                    item.optString("country").takeIf { it.isNotEmpty() },
                ).distinct().joinToString(" · ")
                WeatherPlace(
                    name = if (region.isEmpty()) name else "$name, $region",
                    latitude = item.optDouble("latitude"),
                    longitude = item.optDouble("longitude"),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to parse the geocoding response", e)
            emptyList()
        }
    }

    /**
     * Asks for a window wide enough to cover Monday to Sunday of the current week
     * whichever day it is run on: up to six days back, plus the rest of the week.
     */
    fun fetch(place: WeatherPlace): WeatherReading? {
        val url = FORECAST_URL +
            "?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=temperature_2m,apparent_temperature,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&timezone=auto&past_days=7&forecast_days=7"
        val body = get(url) ?: return null
        return try {
            val root = JSONObject(body)
            val current = root.getJSONObject("current")
            val daily = root.getJSONObject("daily")
            val days = daily.getJSONArray("time")
            val codes = daily.getJSONArray("weather_code")
            val highs = daily.getJSONArray("temperature_2m_max")
            val lows = daily.getJSONArray("temperature_2m_min")

            val week = (0 until days.length()).map { i ->
                WeatherDay(
                    date = days.getString(i),
                    code = codes.optInt(i, -1),
                    high = Math.round(highs.optDouble(i, 0.0)).toInt(),
                    low = Math.round(lows.optDouble(i, 0.0)).toInt(),
                )
            }

            // Today's own entry is the authoritative high/low; the current block
            // only carries the instantaneous reading.
            val todayIso = root.optString("timezone").let { _ ->
                java.time.LocalDate.now().toString()
            }
            val today = week.firstOrNull { it.date == todayIso }

            WeatherReading(
                temperature = Math.round(current.getDouble("temperature_2m")).toInt(),
                feelsLike = Math.round(current.getDouble("apparent_temperature")).toInt(),
                high = today?.high ?: 0,
                low = today?.low ?: 0,
                code = current.getInt("weather_code"),
                fetchedAt = System.currentTimeMillis(),
                week = week,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unable to parse the forecast response", e)
            null
        }
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun get(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
            }
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "Request failed with HTTP ${connection.responseCode}")
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed", e)
            null
        } finally {
            connection?.disconnect()
        }
    }
}
