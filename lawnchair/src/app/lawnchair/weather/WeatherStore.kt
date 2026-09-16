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

import android.content.Context

/** The place a clock widget reports the weather for. */
data class WeatherPlace(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

/** One day of the week row. [date] is ISO, e.g. 2026-09-16. */
data class WeatherDay(
    val date: String,
    val code: Int,
    val high: Int,
    val low: Int,
)

/** The last successfully fetched conditions, kept so the widget can redraw offline. */
data class WeatherReading(
    val temperature: Int,
    val feelsLike: Int,
    val high: Int,
    val low: Int,
    val code: Int,
    val fetchedAt: Long,
    val week: List<WeatherDay> = emptyList(),
)

/**
 * Per-widget settings and the cached reading, keyed by appWidgetId.
 *
 * The reading is cached so a redraw — on resize, reboot or app update — can show
 * the last known weather immediately instead of an empty widget while the network
 * request is in flight.
 */
object WeatherStore {

    private const val PREFS_NAME = "weather_widget"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadPlace(context: Context, appWidgetId: Int): WeatherPlace? {
        val p = prefs(context)
        val name = p.getString("place_name_$appWidgetId", null) ?: return null
        val lat = p.getFloat("place_lat_$appWidgetId", Float.NaN)
        val lon = p.getFloat("place_lon_$appWidgetId", Float.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        return WeatherPlace(name, lat.toDouble(), lon.toDouble())
    }

    fun savePlace(context: Context, appWidgetId: Int, place: WeatherPlace) {
        prefs(context).edit()
            .putString("place_name_$appWidgetId", place.name)
            .putFloat("place_lat_$appWidgetId", place.latitude.toFloat())
            .putFloat("place_lon_$appWidgetId", place.longitude.toFloat())
            .apply()
    }

    fun loadReading(context: Context, appWidgetId: Int): WeatherReading? {
        val p = prefs(context)
        if (!p.contains("temp_$appWidgetId")) return null
        return WeatherReading(
            temperature = p.getInt("temp_$appWidgetId", 0),
            feelsLike = p.getInt("feels_$appWidgetId", 0),
            high = p.getInt("high_$appWidgetId", 0),
            low = p.getInt("low_$appWidgetId", 0),
            code = p.getInt("code_$appWidgetId", 0),
            fetchedAt = p.getLong("at_$appWidgetId", 0L),
            week = decodeWeek(p.getString("week_$appWidgetId", null)),
        )
    }

    fun saveReading(context: Context, appWidgetId: Int, reading: WeatherReading) {
        prefs(context).edit()
            .putInt("temp_$appWidgetId", reading.temperature)
            .putInt("feels_$appWidgetId", reading.feelsLike)
            .putInt("high_$appWidgetId", reading.high)
            .putInt("low_$appWidgetId", reading.low)
            .putInt("code_$appWidgetId", reading.code)
            .putLong("at_$appWidgetId", reading.fetchedAt)
            .putString("week_$appWidgetId", encodeWeek(reading.week))
            .apply()
    }

    private fun encodeWeek(week: List<WeatherDay>) =
        week.joinToString(";") { "${it.date},${it.code},${it.high},${it.low}" }

    private fun decodeWeek(raw: String?): List<WeatherDay> {
        if (raw.isNullOrEmpty()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split(",")
            if (parts.size != 4) return@mapNotNull null
            WeatherDay(
                date = parts[0],
                code = parts[1].toIntOrNull() ?: -1,
                high = parts[2].toIntOrNull() ?: 0,
                low = parts[3].toIntOrNull() ?: 0,
            )
        }
    }

    fun remove(context: Context, appWidgetId: Int) {
        val editor = prefs(context).edit()
        listOf("place_name_", "place_lat_", "place_lon_", "temp_", "feels_", "high_", "low_", "code_", "at_", "week_")
            .forEach { editor.remove("$it$appWidgetId") }
        editor.apply()
    }
}
