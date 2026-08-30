package app.lawnchair.nativeimport

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import app.lawnchair.lawnchairApp
import com.android.launcher3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「匯入原生桌面排列」流程的進入點:
 * 1. 確認無障礙服務已連線(否則導去系統設定開啟)
 * 2. 啟動原生 Launcher 到前景
 * 3. NativeLayoutScanner 掃描 → NativeLayoutApplier 寫入 → 重啟桌面
 */
object NativeImportController {

    private const val TAG = "NativeImportController"
    private val scope = MainScope()

    @Volatile
    private var scanning = false

    fun start(context: Context) {
        if (scanning) return
        val service = context.lawnchairApp.accessibilityService
        if (service == null) {
            Toast.makeText(context, R.string.native_import_need_a11y, Toast.LENGTH_LONG).show()
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        val target = findNativeLauncher(context)
        if (target == null) {
            Toast.makeText(context, R.string.native_import_no_launcher, Toast.LENGTH_LONG).show()
            return
        }

        scanning = true
        Toast.makeText(context, R.string.native_import_started, Toast.LENGTH_SHORT).show()
        context.startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        scope.launch {
            try {
                val scanner = NativeLayoutScanner(service) { msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
                val layout = scanner.scan(target.packageName)
                val result = withContext(Dispatchers.IO) {
                    NativeLayoutApplier.apply(context, layout)
                }
                val summary = context.getString(
                    R.string.native_import_done,
                    result.resolved,
                    result.folders,
                ) + if (result.unresolved.isEmpty()) {
                    ""
                } else {
                    "\n" + context.getString(
                        R.string.native_import_skipped,
                        result.unresolved.take(5).joinToString("、"),
                    )
                }
                Toast.makeText(context, summary, Toast.LENGTH_LONG).show()
                delay(1500)
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                delay(900)
                NativeLayoutApplier.restartLauncher()
            } catch (e: Exception) {
                Log.e(TAG, "匯入失敗", e)
                Toast.makeText(
                    context,
                    context.getString(R.string.native_import_failed, e.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                scanning = false
            }
        }
    }

    private fun findNativeLauncher(context: Context): ComponentName? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val candidates = pm.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo }
            .filter {
                it.packageName != context.packageName &&
                    (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    !it.packageName.contains("com.android.settings")
            }
        val pick = candidates.firstOrNull { it.packageName.contains("nexuslauncher") }
            ?: candidates.firstOrNull { it.packageName.contains("launcher") }
            ?: candidates.firstOrNull()
        return pick?.let { ComponentName(it.packageName, it.name) }
    }
}
