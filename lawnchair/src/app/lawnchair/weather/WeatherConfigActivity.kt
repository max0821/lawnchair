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

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import com.android.launcher3.R

/** Lets the user choose which place the widget reports the weather for. */
class WeatherConfigActivity : Activity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var results: List<WeatherPlace> = emptyList()
    private lateinit var adapter: ArrayAdapter<String>

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

        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        setContentView(R.layout.weather_config_activity)
        applySystemBarInsets()

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        findViewById<ListView>(R.id.weather_config_list)!!.apply {
            adapter = this@WeatherConfigActivity.adapter
            setOnItemClickListener { _, _, position, _ -> choose(results[position]) }
        }

        val input = findViewById<EditText>(R.id.weather_config_query)!!
        WeatherStore.loadPlace(this, appWidgetId)?.let { input.setText(it.name.substringBefore(',')) }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                search(input.text.toString())
                true
            } else {
                false
            }
        }
        findViewById<Button>(R.id.weather_config_search)!!.setOnClickListener {
            search(input.text.toString())
        }
    }

    /** targetSdk 35 draws the window edge to edge on Android 15+. */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.weather_config_root)!!
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return

        val progress = findViewById<ProgressBar>(R.id.weather_config_progress)!!
        val status = findViewById<TextView>(R.id.weather_config_status)!!
        progress.visibility = View.VISIBLE
        status.text = ""

        // Open-Meteo matches romanised names but returns localised labels, so the
        // device language decides how the results read.
        val language = resources.configuration.locales[0].language.ifEmpty { "en" }
        Thread {
            val found = WeatherClient.search(trimmed, language)
            runOnUiThread {
                progress.visibility = View.GONE
                results = found
                adapter.clear()
                adapter.addAll(found.map { it.name })
                adapter.notifyDataSetChanged()
                status.text = if (found.isEmpty()) getString(R.string.weather_no_results) else ""
            }
        }.start()
    }

    private fun choose(place: WeatherPlace) {
        WeatherStore.savePlace(this, appWidgetId, place)
        val manager = AppWidgetManager.getInstance(this)
        WeatherWidgetProvider.render(this, manager, appWidgetId)
        WeatherWidgetProvider.refresh(this, appWidgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        finish()
    }
}
