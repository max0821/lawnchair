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

package app.lawnchair.toolkit

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.android.launcher3.R
import kotlin.math.max
import kotlin.math.min

/**
 * A widget that holds a grid of app shortcuts, so that frequently used apps can be
 * grouped into a single compact tray instead of taking up separate cells.
 *
 * The column count is fixed at [COLUMNS]; the number of rows is derived from the height
 * the user resized the widget to, which makes 5x1 and 5x2 behave as 5 and 10 slots.
 */
class ToolkitWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        // The row count depends on the current height, so redraw whenever it is resized.
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { ToolkitStore.remove(context, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Reinstalling the app resets every instance back to the blank initial layout,
        // and nothing else would redraw them because updatePeriodMillis is 0.
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            updateAll(context)
        }
    }

    companion object {

        /** Slots per row. Matches the workspace grid so a 5x1 widget holds exactly one row. */
        private const val COLUMNS = 5

        /** Approximate height one row of icon + label needs, used to derive the row count. */
        private const val ROW_HEIGHT_DP = 74

        private const val MAX_ROWS = 4
        private const val ICON_SIZE_DP = 44

        /** Rebuilds and pushes the RemoteViews for a single widget instance. */
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val components = ToolkitStore.load(context, appWidgetId)
            val views = RemoteViews(context.packageName, R.layout.toolkit_widget)
            views.removeAllViews(R.id.toolkit_rows)

            if (components.isEmpty()) {
                views.setViewVisibility(R.id.toolkit_empty, View.VISIBLE)
                views.setViewVisibility(R.id.toolkit_rows, View.GONE)
                views.setOnClickPendingIntent(R.id.toolkit_empty, configIntent(context, appWidgetId))
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            }

            views.setViewVisibility(R.id.toolkit_empty, View.GONE)
            views.setViewVisibility(R.id.toolkit_rows, View.VISIBLE)

            val rows = resolveRowCount(context, appWidgetManager, appWidgetId)
            val visible = components.take(rows * COLUMNS)
            val iconSizePx = dpToPx(context, ICON_SIZE_DP)

            visible.chunked(COLUMNS).forEach { rowItems ->
                val row = RemoteViews(context.packageName, R.layout.toolkit_widget_row)
                rowItems.forEach { entry ->
                    row.addView(R.id.toolkit_row, buildItem(context, appWidgetId, entry, iconSizePx))
                }
                // Pad the last row so the remaining icons keep their column alignment
                // instead of stretching across the full width.
                repeat(COLUMNS - rowItems.size) {
                    row.addView(
                        R.id.toolkit_row,
                        RemoteViews(context.packageName, R.layout.toolkit_widget_item_empty),
                    )
                }
                views.addView(R.id.toolkit_rows, row)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /** Redraws every instance of this widget, e.g. after the app list was edited. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, ToolkitWidgetProvider::class.java),
            )
            ids.forEach { updateWidget(context, manager, it) }
        }

        private fun buildItem(
            context: Context,
            appWidgetId: Int,
            entry: ToolkitEntry,
            iconSizePx: Int,
        ): RemoteViews {
            val item = RemoteViews(context.packageName, R.layout.toolkit_widget_item)
            when (entry) {
                is ToolkitEntry.App -> buildAppItem(context, appWidgetId, entry.component, iconSizePx, item)
                is ToolkitEntry.Action -> buildActionItem(context, appWidgetId, entry.action, iconSizePx, item)
            }
            return item
        }

        private fun buildAppItem(
            context: Context,
            appWidgetId: Int,
            component: ComponentName,
            iconSizePx: Int,
            item: RemoteViews,
        ) {
            val pm = context.packageManager
            val (label, icon) = try {
                val info = pm.getActivityInfo(component, 0)
                info.loadLabel(pm).toString() to info.loadIcon(pm)
            } catch (e: Exception) {
                // The app was uninstalled since it was picked; leave the slot readable
                // rather than dropping it silently, so the user knows to reconfigure.
                component.packageName to null
            }

            item.setTextViewText(R.id.toolkit_item_label, label)
            item.setViewPadding(R.id.toolkit_item_icon, 0, 0, 0, 0)
            item.setInt(R.id.toolkit_item_icon, "setBackgroundResource", 0)
            if (icon != null) {
                item.setImageViewBitmap(R.id.toolkit_item_icon, toBitmap(icon, iconSizePx))
            }
            item.setOnClickPendingIntent(
                R.id.toolkit_item,
                launchIntent(context, appWidgetId, component),
            )
        }

        private fun buildActionItem(
            context: Context,
            appWidgetId: Int,
            action: ToolkitAction,
            iconSizePx: Int,
            item: RemoteViews,
        ) {
            item.setTextViewText(R.id.toolkit_item_label, context.getString(action.labelRes))
            item.setImageViewResource(R.id.toolkit_item_icon, action.iconRes)
            // A circular backdrop keeps the flat glyphs visually consistent with the
            // adaptive icons sitting next to them.
            item.setInt(R.id.toolkit_item_icon, "setBackgroundResource", R.drawable.toolkit_action_bg)
            val inset = iconSizePx / 5
            item.setViewPadding(R.id.toolkit_item_icon, inset, inset, inset, inset)
            item.setOnClickPendingIntent(R.id.toolkit_item, actionIntent(context, appWidgetId, action))
        }

        private fun actionIntent(
            context: Context,
            appWidgetId: Int,
            action: ToolkitAction,
        ): PendingIntent {
            val requestCode = (appWidgetId * 31 + action.id.hashCode()) and 0x7fffffff
            val activity = action.activityIntent(context)
            // Falling back to the receiver covers both the torch and actions whose
            // component is missing on this device, which then report why.
            return if (activity != null) {
                PendingIntent.getActivity(
                    context,
                    requestCode,
                    activity,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            } else {
                val broadcast = Intent(context, ToolkitActionReceiver::class.java)
                    .putExtra(ToolkitActionReceiver.EXTRA_ACTION_ID, action.id)
                PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    broadcast,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
        }

        private fun resolveRowCount(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
        ): Int {
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId) ?: return 1
            // MIN_HEIGHT is the landscape (shorter) bound and MAX_HEIGHT the portrait
            // one, so the current orientation decides which value describes the height
            // actually on screen.
            val portrait = context.resources.configuration.orientation ==
                Configuration.ORIENTATION_PORTRAIT
            val key = if (portrait) {
                AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT
            } else {
                AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT
            }
            val heightDp = options.getInt(key, 0)
                .takeIf { it > 0 }
                ?: options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            if (heightDp <= 0) return 1
            return min(MAX_ROWS, max(1, heightDp / ROW_HEIGHT_DP))
        }

        private fun launchIntent(
            context: Context,
            appWidgetId: Int,
            component: ComponentName,
        ): PendingIntent {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .setFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                )
            return PendingIntent.getActivity(
                context,
                requestCode(appWidgetId, component),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun configIntent(context: Context, appWidgetId: Int): PendingIntent {
            val intent = Intent(context, ToolkitConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Without a unique data uri the extras of an existing PendingIntent
                // would be reused for every widget instance.
                .setData(android.net.Uri.parse("toolkit://configure/$appWidgetId"))
            return PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun requestCode(appWidgetId: Int, component: ComponentName) =
            (appWidgetId * 31 + component.flattenToString().hashCode()) and 0x7fffffff

        private fun toBitmap(drawable: Drawable, sizePx: Int): Bitmap {
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                return Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
            }
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            return bitmap
        }

        private fun dpToPx(context: Context, dp: Int) = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics,
        ).toInt()
    }
}
