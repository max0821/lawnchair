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

import android.content.ComponentName
import android.content.Context

/** One slot of a toolkit widget: either an installed app or a built-in system tool. */
sealed interface ToolkitEntry {

    data class App(val component: ComponentName) : ToolkitEntry

    data class Action(val action: ToolkitAction) : ToolkitEntry
}

/**
 * Persists the contents of each toolkit widget instance, keyed by appWidgetId.
 *
 * Stored as a single delimited string so that no database is needed; the list is
 * short and is only read when the widget is redrawn.
 */
object ToolkitStore {

    private const val PREFS_NAME = "toolkit_widget"
    private const val SEPARATOR = ";"
    private const val APP_PREFIX = "a:"
    private const val ACTION_PREFIX = "t:"

    private fun key(appWidgetId: Int) = "widget_$appWidgetId"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the entries configured for [appWidgetId], in display order. */
    fun load(context: Context, appWidgetId: Int): List<ToolkitEntry> {
        val raw = prefs(context).getString(key(appWidgetId), null) ?: return emptyList()
        return raw.split(SEPARATOR)
            .filter { it.isNotEmpty() }
            .mapNotNull(::decode)
    }

    /** Stores [entries] as the content of [appWidgetId], replacing any previous list. */
    fun save(context: Context, appWidgetId: Int, entries: List<ToolkitEntry>) {
        val raw = entries.joinToString(SEPARATOR, transform = ::encode)
        prefs(context).edit().putString(key(appWidgetId), raw).apply()
    }

    /** Drops the stored list, called when the widget is removed from the workspace. */
    fun remove(context: Context, appWidgetId: Int) {
        prefs(context).edit().remove(key(appWidgetId)).apply()
    }

    private fun encode(entry: ToolkitEntry): String = when (entry) {
        is ToolkitEntry.App -> APP_PREFIX + entry.component.flattenToString()
        is ToolkitEntry.Action -> ACTION_PREFIX + entry.action.id
    }

    private fun decode(value: String): ToolkitEntry? = when {
        value.startsWith(ACTION_PREFIX) ->
            ToolkitAction.fromId(value.removePrefix(ACTION_PREFIX))?.let(ToolkitEntry::Action)
        value.startsWith(APP_PREFIX) ->
            ComponentName.unflattenFromString(value.removePrefix(APP_PREFIX))
                ?.let(ToolkitEntry::App)
        // Entries written before tools existed were bare component names.
        else -> ComponentName.unflattenFromString(value)?.let(ToolkitEntry::App)
    }
}
