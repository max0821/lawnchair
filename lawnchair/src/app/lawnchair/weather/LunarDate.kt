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

import android.icu.util.ChineseCalendar
import android.util.Log

/**
 * Formats today in the Chinese lunar calendar, e.g. 八月初七.
 *
 * The conversion comes from ICU, which ships with the platform, so no date tables
 * of our own are needed — only the traditional month and day names, which ICU does
 * not provide in this form. Festivals come from [HolidayCalendar] instead of a
 * table here, so substitute days off and the solar-term ones stay correct.
 */
object LunarDate {

    private const val TAG = "LunarDate"

    private val MONTHS = arrayOf(
        "正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "臘月",
    )

    private val DAYS = arrayOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十",
    )

    /** Returns the lunar date for today, or null if it cannot be determined. */
    fun today(): String? = try {
        val calendar = ChineseCalendar()
        val month = calendar.get(ChineseCalendar.MONTH)
        val day = calendar.get(ChineseCalendar.DAY_OF_MONTH)
        val leap = calendar.get(ChineseCalendar.IS_LEAP_MONTH) == 1
        val name = MONTHS.getOrNull(month)
        val dayName = DAYS.getOrNull(day - 1)
        when {
            name == null || dayName == null -> null
            leap -> "閏$name$dayName"
            else -> "$name$dayName"
        }
    } catch (e: Exception) {
        Log.e(TAG, "Unable to resolve the lunar date", e)
        null
    }
}
