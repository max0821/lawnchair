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

import android.app.Activity
import android.app.usage.UsageStatsManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.R

/**
 * Configuration screen shown when a toolkit widget is added, and again whenever the
 * user reconfigures it.
 *
 * The top list holds what the widget will show, in the order it will show it, and can
 * be dragged to rearrange; the bottom list adds and removes entries.
 */
class ToolkitConfigActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val selected = mutableListOf<ToolkitEntry>()
    private var entries: List<Row> = emptyList()
    private var rowByEntry: Map<ToolkitEntry, Row> = emptyMap()
    private var usageAvailable = false
    private lateinit var adapter: AppAdapter
    private lateinit var selectedAdapter: SelectedAdapter
    private lateinit var touchHelper: ItemTouchHelper

    private class Row(
        val entry: ToolkitEntry,
        val label: String,
        val icon: Drawable?,
        val iconRes: Int = 0,
    )

    private companion object {
        /** How far back usage records are considered when ordering the list. */
        const val USAGE_WINDOW_MS = 90L * 24 * 60 * 60 * 1000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // Cancelled unless the user explicitly confirms, so backing out of the picker
        // does not leave a half-configured widget on the workspace.
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        setContentView(R.layout.toolkit_config_activity)
        applySystemBarInsets()

        selected.addAll(ToolkitStore.load(this, appWidgetId))

        adapter = AppAdapter()
        findViewById<ListView>(R.id.toolkit_config_list)!!.apply {
            this.adapter = this@ToolkitConfigActivity.adapter
            setOnItemClickListener { _, _, position, _ -> toggle(entries[position].entry) }
        }
        setUpSelectedList()
        findViewById<Button>(R.id.toolkit_config_save)!!.setOnClickListener { save() }

        loadApps()
    }

    private fun setUpSelectedList() {
        selectedAdapter = SelectedAdapter()
        val list = findViewById<RecyclerView>(R.id.toolkit_config_selected)!!
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = selectedAdapter

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0,
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                // Remove and re-insert rather than swap so dragging across several
                // rows at once keeps the intermediate items in their original order.
                selected.add(to, selected.removeAt(from))
                selectedAdapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                // The position numbers and the badges in the lower list are only
                // correct once the drag has settled.
                refreshLists()
            }
        }
        touchHelper = ItemTouchHelper(callback)
        touchHelper.attachToRecyclerView(list)
    }

    private fun refreshLists() {
        selectedAdapter.notifyDataSetChanged()
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.toolkit_config_selected_empty)!!.visibility =
            if (selected.isEmpty()) View.VISIBLE else View.GONE
        updateCounter()
    }

    /**
     * targetSdk 35 makes the window edge-to-edge on Android 15+, so the content would
     * otherwise be drawn underneath the status and navigation bars.
     */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.toolkit_config_root)!!
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun loadApps() {
        val progress = findViewById<ProgressBar>(R.id.toolkit_config_progress)!!
        progress.visibility = View.VISIBLE
        val stored = selected.toList()
        Thread {
            val pm = packageManager
            val usage = loadLastUsed()
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(intent, 0)
                .map {
                    val component = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                    ToolkitEntry.App(component) to it
                }
                // Most recently opened first; apps with no usage record fall to the
                // bottom in alphabetical order so the list stays predictable.
                .sortedWith(
                    compareByDescending<Pair<ToolkitEntry.App, android.content.pm.ResolveInfo>> {
                        usage[it.first.component.packageName] ?: 0L
                    }.thenBy { it.second.loadLabel(pm).toString().lowercase() },
                )
                .map { (entry, info) -> Row(entry, info.loadLabel(pm).toString(), info.loadIcon(pm)) }

            // System tools have no usage history, so they are pinned above the apps
            // instead of sinking to the bottom of the list.
            val tools = ToolkitAction.entries.map {
                Row(ToolkitEntry.Action(it), getString(it.labelRes), null, it.iconRes)
            }

            val allRows = tools + apps
            val byEntry = allRows.associateBy { it.entry }.toMutableMap()

            // An entry saved earlier can point at an activity that is no longer a
            // launcher entry, because apps rename or alias theirs across updates.
            // Resolving it directly keeps its real name and icon in the list instead
            // of degrading to a bare package name.
            stored.filterIsInstance<ToolkitEntry.App>()
                .filterNot { byEntry.containsKey(it) }
                .forEach { entry ->
                    runCatching {
                        val info = pm.getActivityInfo(entry.component, 0)
                        byEntry[entry] = Row(entry, info.loadLabel(pm).toString(), info.loadIcon(pm))
                    }
                }

            runOnUiThread {
                entries = allRows
                rowByEntry = byEntry
                usageAvailable = usage.isNotEmpty()
                progress.visibility = View.GONE
                refreshLists()
            }
        }.start()
    }

    /**
     * Last-used timestamp per package. Requires the "usage access" special permission;
     * without it the query simply returns nothing and the list stays alphabetical.
     */
    private fun loadLastUsed(): Map<String, Long> {
        val manager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyMap()
        val end = System.currentTimeMillis()
        return try {
            manager.queryAndAggregateUsageStats(end - USAGE_WINDOW_MS, end)
                .mapValues { it.value.lastTimeUsed }
                .filterValues { it > 0L }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun toggle(entry: ToolkitEntry) {
        if (!selected.remove(entry)) {
            selected.add(entry)
        }
        refreshLists()
    }

    private fun updateCounter() {
        val counter = getString(R.string.toolkit_config_selected, selected.size)
        findViewById<TextView>(R.id.toolkit_config_hint)!!.text = if (usageAvailable) {
            counter
        } else {
            counter + "\n" + getString(R.string.toolkit_config_no_usage)
        }
    }

    private fun save() {
        ToolkitStore.save(this, appWidgetId, selected)
        ToolkitWidgetProvider.updateWidget(this, AppWidgetManager.getInstance(this), appWidgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }

    /** Resolved once so every list row reuses the same themed colour. */
    private val toolIconTint: Int by lazy {
        val value = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)
        resources.getColor(value.resourceId, theme)
    }

    /** Applies a row's icon, tinting the built-in tool glyphs so they stay visible. */
    private fun bindIcon(view: ImageView, row: Row?) {
        if (row?.icon != null) {
            view.clearColorFilter()
            view.setImageDrawable(row.icon)
        } else if (row != null && row.iconRes != 0) {
            view.setImageResource(row.iconRes)
            // The tool glyphs are authored white for the widget, so they need tinting
            // to stay visible on a light-themed picker.
            view.setColorFilter(toolIconTint)
        } else {
            view.setImageDrawable(null)
        }
    }

    private inner class SelectedAdapter : RecyclerView.Adapter<SelectedAdapter.Holder>() {

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val order: TextView = view.findViewById(R.id.toolkit_selected_order)!!
            val icon: ImageView = view.findViewById(R.id.toolkit_selected_icon)!!
            val label: TextView = view.findViewById(R.id.toolkit_selected_label)!!
            val remove: ImageView = view.findViewById(R.id.toolkit_selected_remove)!!
            val handle: ImageView = view.findViewById(R.id.toolkit_selected_handle)!!
        }

        override fun getItemCount() = selected.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = layoutInflater.inflate(R.layout.toolkit_config_selected_item, parent, false)
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = selected[position]
            val row = rowByEntry[entry]
            holder.order.text = (position + 1).toString()
            holder.label.text = row?.label ?: describeMissing(entry)
            bindIcon(holder.icon, row)
            holder.remove.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) {
                    selected.removeAt(index)
                    refreshLists()
                }
            }
            holder.handle.setColorFilter(toolIconTint)
            holder.handle.setOnTouchListener { view, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    touchHelper.startDrag(holder)
                }
                view.performClick()
                false
            }
        }

        /** Keeps a slot readable when the app behind it was uninstalled. */
        private fun describeMissing(entry: ToolkitEntry) = when (entry) {
            is ToolkitEntry.App -> entry.component.packageName
            is ToolkitEntry.Action -> getString(entry.action.labelRes)
        }
    }

    private inner class AppAdapter : BaseAdapter() {

        override fun getCount() = entries.size

        override fun getItem(position: Int) = entries[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: layoutInflater.inflate(R.layout.toolkit_config_item, parent, false)
            val row = entries[position]
            bindIcon(view.findViewById(R.id.toolkit_config_item_icon)!!, row)
            view.findViewById<TextView>(R.id.toolkit_config_item_label)!!.text = row.label

            val order = selected.indexOf(row.entry)
            view.findViewById<CheckBox>(R.id.toolkit_config_item_check)!!.isChecked = order >= 0
            view.findViewById<TextView>(R.id.toolkit_config_item_order)!!.apply {
                // Showing the pick order makes it obvious how the tray will be arranged.
                text = if (order >= 0) (order + 1).toString() else ""
                visibility = if (order >= 0) View.VISIBLE else View.INVISIBLE
            }
            return view
        }
    }
}
