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
import android.content.Intent
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import com.android.launcher3.R

/**
 * Built-in system tools that can be placed in a toolkit widget alongside apps.
 *
 * [id] is persisted, so it must stay stable even if the enum is reordered.
 */
enum class ToolkitAction(
    val id: String,
    val labelRes: Int,
    val iconRes: Int,
) {
    /** Toggled in place by [ToolkitActionReceiver] instead of opening an activity. */
    FLASHLIGHT("flashlight", R.string.toolkit_action_flashlight, R.drawable.ic_toolkit_flashlight),
    QR_SCAN("qr_scan", R.string.toolkit_action_qr, R.drawable.ic_toolkit_qr),
    TRANSCRIBE("transcribe", R.string.toolkit_action_transcribe, R.drawable.ic_toolkit_transcribe),
    ALARM("alarm", R.string.toolkit_action_alarm, R.drawable.ic_toolkit_alarm),
    TIMER("timer", R.string.toolkit_action_timer, R.drawable.ic_toolkit_timer),
    CAMERA("camera", R.string.toolkit_action_camera, R.drawable.ic_toolkit_camera),
    WIFI("wifi", R.string.toolkit_action_wifi, R.drawable.ic_toolkit_wifi),
    BLUETOOTH("bluetooth", R.string.toolkit_action_bluetooth, R.drawable.ic_toolkit_bluetooth),
    ;

    /**
     * The activity to start, or null when the action opens nothing (the torch) or
     * when no component on this device can handle it.
     *
     * QR scanning and live transcription have no public intent action, so they are
     * resolved against a list of known components and simply become unavailable on
     * devices that ship neither.
     */
    fun activityIntent(context: Context): Intent? = when (this) {
        FLASHLIGHT -> null
        ALARM -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
        TIMER -> Intent(AlarmClock.ACTION_SHOW_TIMERS)
        CAMERA -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
        BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        QR_SCAN -> firstAvailable(context, QR_COMPONENTS)
        TRANSCRIBE -> firstAvailable(context, TRANSCRIBE_COMPONENTS)
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {

        fun fromId(id: String): ToolkitAction? = entries.firstOrNull { it.id == id }

        /** GMS barcode scanner, the same one behind the system QR code quick tile. */
        private val QR_COMPONENTS = listOf(
            "com.google.android.gms" to
                "com.google.android.gms.mlkit.barcode.ui.PlatformBarcodeScanningActivityProxy",
            "com.google.android.gms" to
                "com.google.android.gms.mlkit.barcode.ui.BarcodeScanningActivityProxy",
        )

        /**
         * Live Transcribe. Its LauncherActivity is disabled on Pixel and the quick
         * settings dispatcher is not exported, so MainActivity is the usable entry.
         */
        private val TRANSCRIBE_COMPONENTS = listOf(
            "com.google.audio.hearing.visualization.accessibility.scribe" to
                "com.google.audio.hearing.visualization.accessibility.scribe.MainActivity",
        )

        private fun firstAvailable(
            context: Context,
            candidates: List<Pair<String, String>>,
        ): Intent? = candidates
            .map { (pkg, cls) -> ComponentName(pkg, cls) }
            .firstOrNull { isLaunchable(context, it) }
            ?.let { Intent().setComponent(it) }

        /**
         * Resolving an explicit intent also rules out components that exist in the
         * manifest but are disabled, which is how Live Transcribe ships its
         * LauncherActivity.
         */
        private fun isLaunchable(context: Context, component: ComponentName): Boolean {
            val info = context.packageManager
                .resolveActivity(Intent().setComponent(component), 0)
            return info?.activityInfo?.exported == true
        }
    }
}
