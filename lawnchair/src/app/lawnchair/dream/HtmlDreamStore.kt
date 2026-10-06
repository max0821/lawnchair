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

package app.lawnchair.dream

import android.content.Context

/** Settings for [HtmlDreamService]: which page to show and whether it takes touches. */
object HtmlDreamStore {

    /** Shown until the user picks something of their own. */
    const val DEFAULT_URL = "file:///android_asset/dream/index.html"

    private const val PREFS_NAME = "html_dream"
    private const val KEY_URL = "url"
    private const val KEY_INTERACTIVE = "interactive"
    private const val KEY_DARK_OFF_MINUTES = "dark_off_minutes"

    /** The choices offered for [darkOffMinutes]; 0 keeps the screen on. */
    val DARK_OFF_CHOICES = intArrayOf(0, 5, 10, 15, 30, 60)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun url(context: Context): String =
        prefs(context).getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL

    fun setUrl(context: Context, url: String?) {
        prefs(context).edit().putString(KEY_URL, url?.trim()).apply()
    }

    /**
     * Off by default, so a tap dismisses the screen saver the way the built-in ones
     * do. When on, touches go to the page instead and the dream ends only on the
     * power or home button.
     */
    fun interactive(context: Context): Boolean = prefs(context).getBoolean(KEY_INTERACTIVE, false)

    fun setInteractive(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_INTERACTIVE, value).apply()
    }

    /**
     * How long the room has to stay dark before the screen saver ends and the screen
     * turns off, or 0 to keep it on all night.
     */
    fun darkOffMinutes(context: Context): Int = prefs(context).getInt(KEY_DARK_OFF_MINUTES, 10)

    fun setDarkOffMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_DARK_OFF_MINUTES, minutes).apply()
    }
}
