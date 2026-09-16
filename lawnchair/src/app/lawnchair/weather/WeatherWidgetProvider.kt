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

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.view.View
import android.widget.RemoteViews
import com.android.launcher3.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle

/**
 * A clock widget that also shows the current weather for a place the user picks.
 *
 * The clock half needs no upkeep — TextClock ticks by itself — so the provider only
 * runs when the weather has to be refreshed.
 */
class WeatherWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id ->
            // Draw straight away from the cache, then refresh in the background so
            // the widget is never blank while the network request is in flight.
            render(context, appWidgetManager, id)
            refresh(context, id, goAsync())
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WeatherStore.remove(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
                if (id != -1) refresh(context, id, goAsync())
            }
            // Reinstalling resets every instance to the blank initial layout, and
            // updatePeriodMillis alone would not redraw them until the next cycle.
            Intent.ACTION_MY_PACKAGE_REPLACED -> updateAll(context)
        }
    }

    companion object {

        const val ACTION_REFRESH = "app.lawnchair.weather.REFRESH"

        /** Below this age the cached reading is good enough to skip a request. */
        private const val FRESH_FOR_MS = 10 * 60 * 1000L

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))
                .forEach { render(context, manager, it) }
        }

        /** Redraws from whatever is cached; never touches the network. */
        fun render(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.weather_widget)
            val place = WeatherStore.loadPlace(context, appWidgetId)
            val reading = WeatherStore.loadReading(context, appWidgetId)

            if (place == null) {
                views.setViewVisibility(R.id.weather_icon, View.GONE)
                views.setTextViewText(R.id.weather_temp, "")
                views.setTextViewText(R.id.weather_place, context.getString(R.string.weather_pick_place))
                views.setTextViewText(R.id.weather_range, "")
            } else if (reading == null) {
                views.setViewVisibility(R.id.weather_icon, View.GONE)
                views.setTextViewText(R.id.weather_temp, "")
                views.setTextViewText(R.id.weather_place, place.name)
                views.setTextViewText(R.id.weather_range, context.getString(R.string.weather_loading))
            } else {
                views.setViewVisibility(R.id.weather_icon, View.VISIBLE)
                views.setImageViewResource(R.id.weather_icon, WeatherCodes.icon(reading.code))
                views.setTextViewText(R.id.weather_temp, "${reading.temperature}°")
                views.setTextViewText(
                    R.id.weather_place,
                    "${place.name.substringBefore(',')} · ${context.getString(WeatherCodes.label(reading.code))}",
                )
                views.setTextViewText(
                    R.id.weather_range,
                    context.getString(R.string.weather_range, reading.high, reading.low, reading.feelsLike),
                )
            }

            renderWeek(context, views, reading)

            // The clock opens the alarms; the weather side refreshes, or opens the
            // picker when no place has been chosen yet.
            views.setOnClickPendingIntent(R.id.weather_clock, alarmIntent(context))
            views.setOnClickPendingIntent(
                R.id.weather_panel,
                if (place == null) configIntent(context, appWidgetId) else refreshIntent(context, appWidgetId),
            )

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * Fills the bottom row with Monday to Sunday of the current week. The API
         * window reaches backwards too, so days already past keep their recorded
         * high and low instead of leaving gaps in the row.
         */
        private fun renderWeek(context: Context, views: RemoteViews, reading: WeatherReading?) {
            views.removeAllViews(R.id.weather_week)
            val week = reading?.week
            if (week.isNullOrEmpty()) {
                views.setViewVisibility(R.id.weather_divider, View.GONE)
                views.setViewVisibility(R.id.weather_week, View.GONE)
                return
            }
            views.setViewVisibility(R.id.weather_divider, View.VISIBLE)
            views.setViewVisibility(R.id.weather_week, View.VISIBLE)

            val locale = context.resources.configuration.locales[0]
            val today = LocalDate.now()
            val monday = today.with(DayOfWeek.MONDAY)
            val byDate = week.associateBy { it.date }

            for (offset in 0..6) {
                val date = monday.plusDays(offset.toLong())
                val day = byDate[date.toString()]
                val item = RemoteViews(context.packageName, R.layout.weather_day_item)
                item.setTextViewText(
                    R.id.weather_day_label,
                    date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                )
                if (day != null && day.code >= 0) {
                    item.setImageViewResource(R.id.weather_day_icon, WeatherCodes.icon(day.code))
                    item.setTextViewText(R.id.weather_day_high, "${day.high}°")
                    item.setTextViewText(R.id.weather_day_low, "${day.low}°")
                } else {
                    item.setViewVisibility(R.id.weather_day_icon, View.INVISIBLE)
                    item.setTextViewText(R.id.weather_day_high, "—")
                    item.setTextViewText(R.id.weather_day_low, "")
                }
                // Today stays fully opaque so it reads as the anchor of the row.
                if (date != today) {
                    item.setFloat(R.id.weather_day_label, "setAlpha", 0.55f)
                    item.setFloat(R.id.weather_day_high, "setAlpha", 0.9f)
                }
                views.addView(R.id.weather_week, item)
            }
        }

        /**
         * Fetches on a background thread and redraws when it lands. [result] is the
         * broadcast receiver's pending result, finished once the work is done.
         */
        fun refresh(context: Context, appWidgetId: Int, result: PendingResult? = null) {
            val place = WeatherStore.loadPlace(context, appWidgetId)
            if (place == null) {
                result?.finish()
                return
            }
            val cached = WeatherStore.loadReading(context, appWidgetId)
            if (cached != null && System.currentTimeMillis() - cached.fetchedAt < FRESH_FOR_MS) {
                result?.finish()
                return
            }
            val appContext = context.applicationContext
            Thread {
                val reading = WeatherClient.fetch(place)
                if (reading != null) {
                    WeatherStore.saveReading(appContext, appWidgetId, reading)
                    render(appContext, AppWidgetManager.getInstance(appContext), appWidgetId)
                }
                result?.finish()
            }.start()
        }

        private fun alarmIntent(context: Context): PendingIntent {
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun refreshIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, WeatherWidgetProvider::class.java)
                .setAction(ACTION_REFRESH)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            return PendingIntent.getBroadcast(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun configIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, WeatherConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // A distinct uri stops one instance's extras being reused for another.
                .setData(Uri.parse("weather://configure/$appWidgetId"))
            return PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
