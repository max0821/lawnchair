package app.lawnchair.nativeimport

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/** 掃描結果:以「App 顯示名稱」描述的原生桌面配置 */
sealed class ScannedItem {
    abstract val col: Int
    abstract val row: Int

    data class App(val label: String, override val col: Int, override val row: Int) : ScannedItem()
    data class Folder(
        val name: String,
        val labels: List<String>,
        override val col: Int,
        override val row: Int,
    ) : ScannedItem()
}

data class ScannedLayout(
    val columns: Int,
    val rows: Int,
    val dock: List<String>,
    val pages: List<List<ScannedItem>>,
)

/**
 * 方案 C:透過無障礙服務直接讀原生 Launcher 的畫面結構,
 * 自動翻頁、開資料夾,重建「順序 + 資料夾」的桌面配置。
 * 等效於開發期用 uiautomator dump 的流程,但完全在裝置上完成。
 */
class NativeLayoutScanner(
    private val service: AccessibilityService,
    private val onProgress: (String) -> Unit = {},
) {

    companion object {
        private const val TAG = "NativeLayoutScanner"
        private const val MAX_PAGES = 10
        private const val MAX_FOLDER_PAGES = 6
        private const val SETTLE_MS = 900L

        private val FOLDER_REGEX = Regex("^(?:資料夾|文件夹|Folder)[::]\\s*(.+)$")
        private val BADGE_REGEX = Regex("^「(.+)」應用程式.*$")
        private val IGNORE_DESC = Regex("^(分頁靠左|分頁靠右|主畫面|Home screen).*$")
        private val SKIP_ID = Regex(".*(qsb|search|smartspace|page_indicator|scrim).*")
    }

    private data class RawIcon(
        val desc: String,
        val bounds: Rect,
        val inWorkspace: Boolean,
        val inHotseat: Boolean,
    )

    private val metrics = service.resources.displayMetrics
    private val width get() = metrics.widthPixels
    private val height get() = metrics.heightPixels

    suspend fun scan(nativePackage: String): ScannedLayout {
        check(waitForPackage(nativePackage, 8000)) { "原生桌面未出現在前景" }
        delay(SETTLE_MS)

        val pages = ArrayList<List<ScannedItem>>()
        var dock: List<String> = emptyList()
        val allBoundsPerPage = ArrayList<List<Pair<Rect, Int>>>() // (bounds, pageIdx) 供格線推算
        var previousFingerprint: String? = null

        for (pageIdx in 0 until MAX_PAGES) {
            val icons = collectIcons()
            val workspaceIcons = icons.filter { it.inWorkspace }
            val fingerprint = workspaceIcons.joinToString("|") { it.desc }
            if (fingerprint == previousFingerprint) break
            previousFingerprint = fingerprint
            onProgress("掃描第 ${pageIdx + 1} 頁(${workspaceIcons.size} 個項目)")

            if (pageIdx == 0 && dock.isEmpty()) {
                dock = icons.filter { it.inHotseat }
                    .sortedBy { it.bounds.left }
                    .map { cleanLabel(it.desc) }
            }

            // 逐一打開本頁的資料夾記錄內容
            val items = ArrayList<Pair<RawIcon, ScannedItem>>()
            for (icon in workspaceIcons) {
                val folderTitle = folderName(icon.desc)
                if (folderTitle == null) {
                    items.add(icon to ScannedItem.App(cleanLabel(icon.desc), 0, 0))
                } else {
                    onProgress("讀取資料夾「$folderTitle」")
                    val labels = readFolder(icon.bounds)
                    items.add(icon to ScannedItem.Folder(folderTitle, labels, 0, 0))
                }
            }
            pages.add(items.map { it.second })
            allBoundsPerPage.add(items.map { it.first.bounds to pageIdx })

            swipe(width * 0.82f, height * 0.5f, width * 0.16f, height * 0.5f, 300)
            delay(SETTLE_MS)
        }

        check(pages.isNotEmpty() && pages.any { it.isNotEmpty() }) { "掃描不到任何桌面項目" }

        // 以全部頁面的座標推算格線(欄/列)並回填 col/row
        val allBounds = allBoundsPerPage.flatten().map { it.first }
        val colCenters = cluster(allBounds.map { it.centerX() })
        val rowCenters = cluster(allBounds.map { it.centerY() })

        val positionedPages = ArrayList<List<ScannedItem>>()
        var boundsIdx = 0
        val flatBounds = allBoundsPerPage.flatten()
        for (page in pages) {
            val positioned = page.map { item ->
                val b = flatBounds[boundsIdx++].first
                val col = nearestIndex(colCenters, b.centerX())
                val row = nearestIndex(rowCenters, b.centerY())
                when (item) {
                    is ScannedItem.App -> item.copy(col = col, row = row)
                    is ScannedItem.Folder -> item.copy(col = col, row = row)
                }
            }
            positionedPages.add(positioned)
        }

        return ScannedLayout(
            columns = colCenters.size.coerceIn(3, 8),
            rows = rowCenters.size.coerceIn(3, 10),
            dock = dock,
            pages = positionedPages,
        )
    }

    /** 打開資料夾、跨資料夾分頁讀取全部項目後返回 */
    private suspend fun readFolder(iconBounds: Rect): List<String> {
        tap(iconBounds.exactCenterX(), iconBounds.exactCenterY())
        delay(SETTLE_MS)

        val labels = ArrayList<String>()
        var previous: String? = null
        for (i in 0 until MAX_FOLDER_PAGES) {
            val icons = collectIcons().filter { !it.inWorkspace && !it.inHotseat }
            val fingerprint = icons.joinToString("|") { it.desc }
            if (fingerprint == previous || icons.isEmpty()) break
            previous = fingerprint
            labels.addAll(icons.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
                .map { cleanLabel(it.desc) })

            // 資料夾內容可能分頁:在內容區域內左滑
            val area = icons.fold(Rect(icons[0].bounds)) { acc, ic -> acc.apply { union(ic.bounds) } }
            swipe(area.right - 20f, area.exactCenterY(), area.left + 20f, area.exactCenterY(), 300)
            delay(700)
        }

        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        delay(800)
        return labels.distinct()
    }

    // ---------- 節點收集 ----------

    private fun collectIcons(): List<RawIcon> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val result = ArrayList<RawIcon>()
        walk(root, inWorkspace = false, inHotseat = false, result)
        return result
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        inWorkspace: Boolean,
        inHotseat: Boolean,
        out: MutableList<RawIcon>,
    ) {
        val id = node.viewIdResourceName ?: ""
        val cls = node.className?.toString() ?: ""
        if (SKIP_ID.matches(id) || cls == "android.appwidget.AppWidgetHostView") return

        var ws = inWorkspace
        var hs = inHotseat
        if (id.endsWith("/workspace")) ws = true
        if (id.endsWith("/hotseat")) hs = true

        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank() && !IGNORE_DESC.matches(desc)) {
            val isFolder = FOLDER_REGEX.matches(desc)
            val looksLikeIcon = cls.endsWith("TextView") || isFolder
            if (looksLikeIcon) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                // 圖示尺寸應為一格;排除超寬節點(頁面容器、指示器等)
                if (bounds.width() in 40..(width / 2) && bounds.height() in 40..(height / 2)) {
                    out.add(RawIcon(desc, bounds, ws, hs))
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, ws, hs, out)
        }
    }

    // ---------- 標籤處理 ----------

    private fun cleanLabel(desc: String): String {
        BADGE_REGEX.matchEntire(desc)?.let { return it.groupValues[1].trim() }
        return desc.trim()
    }

    private fun folderName(desc: String): String? =
        FOLDER_REGEX.matchEntire(desc)?.groupValues?.get(1)
            ?.substringBefore(",")?.substringBefore(",")?.trim()

    // ---------- 格線推算 ----------

    private fun cluster(values: List<Int>): List<Int> {
        if (values.isEmpty()) return emptyList()
        val sorted = values.sorted()
        val gap = (sorted.last() - sorted.first()).coerceAtLeast(1) / 24 + 24
        val centers = ArrayList<MutableList<Int>>()
        for (v in sorted) {
            val last = centers.lastOrNull()
            if (last != null && v - last.last() <= gap) last.add(v)
            else centers.add(mutableListOf(v))
        }
        return centers.map { it.sum() / it.size }
    }

    private fun nearestIndex(centers: List<Int>, value: Int): Int {
        var best = 0
        var bestDist = Int.MAX_VALUE
        centers.forEachIndexed { i, c ->
            val d = kotlin.math.abs(c - value)
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        return best
    }

    // ---------- 手勢 ----------

    private suspend fun waitForPackage(pkg: String, timeoutMs: Long): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (service.rootInActiveWindow?.packageName == pkg) return true
            delay(300)
            waited += 300
        }
        return false
    }

    private suspend fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, 60)
    }

    private suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        dispatch(path, durationMs)
    }

    private suspend fun dispatch(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val accepted = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(g: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null,
            )
            if (!accepted && cont.isActive) {
                Log.w(TAG, "dispatchGesture rejected")
                cont.resume(false)
            }
        }
}
