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

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads today's public holiday from the subscribed holiday calendars, which know
 * about substitute days off and the festivals that follow a solar term — 清明 above
 * all — that a fixed table cannot get right.
 *
 * READ_CALENDAR cannot be granted for one calendar only, so the queries here are
 * deliberately narrowed to calendars owned by Google's holiday accounts. Personal
 * calendars are never read even though the permission would allow it.
 */
object HolidayCalendar {

    private const val TAG = "HolidayCalendar"

    /** Google publishes every country's holidays under an owner of this shape. */
    private const val HOLIDAY_OWNER_SUFFIX = "#holiday@group.v.calendar.google.com"

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Returns today's holiday name, or null when there is none or it cannot be read. */
    fun today(context: Context): String? {
        if (!hasPermission(context)) return null
        val calendarIds = holidayCalendarIds(context)
        if (calendarIds.isEmpty()) return null

        val zone = ZoneId.systemDefault()
        val startOfDay = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val endOfDay = LocalDate.now().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .let { ContentUris.appendId(it, startOfDay); ContentUris.appendId(it, endOfDay); it }
            .build()
        val selection = CalendarContract.Instances.CALENDAR_ID +
            " IN (" + calendarIds.joinToString(",") { "?" } + ")"

        return try {
            context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE),
                selection,
                calendarIds.map { it.toString() }.toTypedArray(),
                CalendarContract.Instances.BEGIN + " ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val title = cursor.getString(0)?.trim()
                    if (!title.isNullOrEmpty()) return@use title
                }
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to read the holiday calendars", e)
            null
        }
    }

    private fun holidayCalendarIds(context: Context): List<Long> = try {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            CalendarContract.Calendars.OWNER_ACCOUNT + " LIKE ?",
            arrayOf("%$HOLIDAY_OWNER_SUFFIX"),
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        } ?: emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Unable to list the holiday calendars", e)
        emptyList()
    }
}
