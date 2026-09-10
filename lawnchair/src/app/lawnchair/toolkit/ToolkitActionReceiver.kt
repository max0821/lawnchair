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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.android.launcher3.R

/**
 * Runs the toolkit actions that must not leave the home screen — currently only the
 * torch, which is toggled in place.
 */
class ToolkitActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION_ID)?.let(ToolkitAction::fromId) ?: return
        if (action == ToolkitAction.FLASHLIGHT) {
            toggleTorch(context, goAsync())
            return
        }
        // Everything else only reaches the receiver when its component could not be
        // resolved when the widget was drawn; retry in case the app was installed
        // since, and otherwise say so instead of doing nothing.
        val activity = action.activityIntent(context)
        if (activity != null) {
            context.startActivity(activity)
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.toolkit_action_unavailable, context.getString(action.labelRes)),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun toggleTorch(context: Context, result: PendingResult?) {
        val manager = context.getSystemService(CameraManager::class.java)
        if (manager == null) {
            result?.finish()
            return
        }

        val cameraId = findTorchCamera(manager)
        if (cameraId == null) {
            Toast.makeText(context, R.string.toolkit_action_no_torch, Toast.LENGTH_SHORT).show()
            result?.finish()
            return
        }

        // Registering delivers the current torch state right away, which is the only
        // way to read it — CameraManager has no getter — so the toggle stays in sync
        // even when the torch was switched from quick settings.
        val callback = object : CameraManager.TorchCallback() {
            private var done = false

            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (done || id != cameraId) return
                done = true
                try {
                    manager.setTorchMode(cameraId, !enabled)
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to toggle the torch", e)
                }
                manager.unregisterTorchCallback(this)
                result?.finish()
            }

            override fun onTorchModeUnavailable(id: String) {
                if (done || id != cameraId) return
                done = true
                manager.unregisterTorchCallback(this)
                Toast.makeText(context, R.string.toolkit_action_torch_busy, Toast.LENGTH_SHORT).show()
                result?.finish()
            }
        }
        manager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
    }

    private fun findTorchCamera(manager: CameraManager): String? = try {
        manager.cameraIdList.firstOrNull { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
    } catch (e: Exception) {
        Log.e(TAG, "Unable to enumerate cameras", e)
        null
    }

    companion object {
        private const val TAG = "ToolkitActionReceiver"
        const val EXTRA_ACTION_ID = "action_id"
    }
}
