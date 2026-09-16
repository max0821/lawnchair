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

import com.android.launcher3.R

/**
 * Maps the WMO weather codes Open-Meteo reports onto an icon and a label.
 *
 * The full code list is finer grained than a small widget can usefully show, so
 * neighbouring codes share an icon; the label keeps the distinction where it
 * matters (drizzle vs rain, sleet vs snow).
 */
object WeatherCodes {

    fun icon(code: Int): Int = when (code) {
        0 -> R.drawable.ic_weather_clear
        1, 2 -> R.drawable.ic_weather_partly
        3 -> R.drawable.ic_weather_cloudy
        45, 48 -> R.drawable.ic_weather_fog
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.ic_weather_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_weather_snow
        95, 96, 99 -> R.drawable.ic_weather_thunder
        else -> R.drawable.ic_weather_cloudy
    }

    fun label(code: Int): Int = when (code) {
        0 -> R.string.weather_code_clear
        1 -> R.string.weather_code_mostly_clear
        2 -> R.string.weather_code_partly_cloudy
        3 -> R.string.weather_code_overcast
        45, 48 -> R.string.weather_code_fog
        51, 53, 55 -> R.string.weather_code_drizzle
        56, 57 -> R.string.weather_code_freezing_drizzle
        61, 63, 65 -> R.string.weather_code_rain
        66, 67 -> R.string.weather_code_freezing_rain
        71, 73, 75, 77 -> R.string.weather_code_snow
        80, 81, 82 -> R.string.weather_code_showers
        85, 86 -> R.string.weather_code_snow_showers
        95 -> R.string.weather_code_thunderstorm
        96, 99 -> R.string.weather_code_thunderstorm_hail
        else -> R.string.weather_code_unknown
    }
}
