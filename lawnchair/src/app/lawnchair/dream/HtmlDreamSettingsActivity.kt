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

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.android.launcher3.R

/**
 * Settings for the HTML screen saver, opened from the gear next to it in the
 * system's screen saver list.
 */
class HtmlDreamSettingsActivity : Activity() {

    private lateinit var urlInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.html_dream_settings)
        applySystemBarInsets()

        urlInput = findViewById(R.id.html_dream_url)!!
        val current = HtmlDreamStore.url(this)
        urlInput.setText(if (current == HtmlDreamStore.DEFAULT_URL) "" else current)

        val interactive = findViewById<CheckBox>(R.id.html_dream_interactive)!!
        interactive.isChecked = HtmlDreamStore.interactive(this)

        findViewById<Button>(R.id.html_dream_pick)!!.setOnClickListener { pickFile() }
        findViewById<Button>(R.id.html_dream_reset)!!.setOnClickListener {
            urlInput.setText("")
            updateCurrent("")
        }
        findViewById<Button>(R.id.html_dream_save)!!.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isNotEmpty() && !isSupported(url)) {
                Toast.makeText(this, R.string.html_dream_bad_url, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            HtmlDreamStore.setUrl(this, url)
            HtmlDreamStore.setInteractive(this, interactive.isChecked)
            Toast.makeText(this, R.string.html_dream_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
        updateCurrent(urlInput.text.toString())
    }

    private fun isSupported(url: String) =
        url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("content://") || url.startsWith("file:///android_asset/")

    /** Lets the user point at an HTML file on the device, e.g. one in Downloads. */
    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("text/html", "application/xhtml+xml", "text/plain"),
            )
        runCatching { startActivityForResult(intent, REQUEST_PICK) }
    }

    @Deprecated("Activity result API is not used to keep this a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        // The dream runs long after this screen is gone, so the read grant has to
        // survive it — and a reboot.
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        urlInput.setText(uri.toString())
        updateCurrent(uri.toString())
    }

    private fun updateCurrent(url: String) {
        findViewById<TextView>(R.id.html_dream_current)!!.text = getString(
            R.string.html_dream_current,
            url.ifEmpty { getString(R.string.html_dream_builtin) },
        )
    }

    /** targetSdk 35 draws the window edge to edge on Android 15+. */
    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.html_dream_root)!!
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private companion object {
        const val REQUEST_PICK = 1
    }
}
