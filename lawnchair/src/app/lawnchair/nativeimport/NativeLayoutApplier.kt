package app.lawnchair.nativeimport

import android.content.ContentValues
import android.content.Context
import android.content.pm.LauncherApps
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.util.Log
import com.android.launcher3.Utilities

/**
 * 把掃描到的原生配置寫入 Lawnchair 的 favorites 資料庫:
 *   screen 0     = 小工具專區(保留既有 widget)
 *   screen 1..N  = 掃描到的原生頁面(App + 資料夾,含空格)
 *   hotseat      = 原生 Dock
 * 同步設定格線與預設主畫面頁,呼叫端負責重啟桌面程序。
 */
object NativeLayoutApplier {

    private const val TAG = "NativeLayoutApplier"
    private const val DB_VERSION = 32
    private const val ITEM_APP = 0
    private const val ITEM_FOLDER = 2
    private const val ITEM_WIDGET = 4
    private const val CONTAINER_DESKTOP = -100
    private const val CONTAINER_HOTSEAT = -101

    private const val FAVORITES_DDL =
        "CREATE TABLE IF NOT EXISTS favorites (" +
            "_id INTEGER PRIMARY KEY, title TEXT, intent TEXT, container INTEGER, " +
            "screen INTEGER, cellX INTEGER, cellY INTEGER, spanX INTEGER, spanY INTEGER, " +
            "itemType INTEGER, appWidgetId INTEGER NOT NULL DEFAULT -1, icon BLOB, " +
            "appWidgetProvider TEXT, modified INTEGER NOT NULL DEFAULT 0, " +
            "restored INTEGER NOT NULL DEFAULT 0, profileId INTEGER DEFAULT 0, " +
            "rank INTEGER NOT NULL DEFAULT 0, options INTEGER NOT NULL DEFAULT 0, " +
            "appWidgetSource INTEGER NOT NULL DEFAULT -1)"

    data class Result(val resolved: Int, val unresolved: List<String>, val folders: Int)

    fun apply(context: Context, layout: ScannedLayout): Result {
        val resolver = LabelResolver(context)
        val sp = Utilities.getPrefs(context)

        val rows = layout.rows
        val cols = layout.columns
        val hotseat = layout.dock.size.coerceIn(3, 7)

        val currentDb = dbName(
            sp.getInt("pref_workspaceRows", 5),
            sp.getInt("pref_workspaceColumns", 4),
            sp.getInt("pref_hotseatColumns", 4),
        )
        val targetDb = dbName(rows, cols, hotseat)

        // 保留目前 screen 0 上的 widget(小工具專區)
        val preservedWidgets = readWidgets(context, currentDb)

        var nextId = 100
        var folderCount = 0
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(targetDb), null)
        try {
            db.execSQL(FAVORITES_DDL)
            db.execSQL("CREATE TABLE IF NOT EXISTS android_metadata (locale TEXT)")
            db.version = DB_VERSION
            db.beginTransaction()
            try {
                db.delete("favorites", null, null)

                preservedWidgets.forEach { w ->
                    db.insert("favorites", null, w.apply { put("_id", nextId++) })
                }

                layout.dock.forEachIndexed { i, label ->
                    val comp = resolver.resolve(label) ?: return@forEachIndexed
                    db.insert("favorites", null, appValues(nextId++, comp, CONTAINER_HOTSEAT, i, i, 0))
                }

                layout.pages.forEachIndexed { pageIdx, page ->
                    val screen = pageIdx + 1
                    page.forEach { item ->
                        when (item) {
                            is ScannedItem.App -> {
                                val comp = resolver.resolve(item.label) ?: return@forEach
                                db.insert(
                                    "favorites", null,
                                    appValues(nextId++, comp, CONTAINER_DESKTOP, screen, item.col, item.row),
                                )
                            }
                            is ScannedItem.Folder -> {
                                val members = item.labels.mapNotNull { resolver.resolve(it) }
                                if (members.isEmpty()) return@forEach
                                folderCount++
                                val folderId = nextId++
                                db.insert("favorites", null, ContentValues().apply {
                                    put("_id", folderId)
                                    put("itemType", ITEM_FOLDER)
                                    put("title", item.name)
                                    put("container", CONTAINER_DESKTOP)
                                    put("screen", screen)
                                    put("cellX", item.col)
                                    put("cellY", item.row)
                                    put("spanX", 1)
                                    put("spanY", 1)
                                })
                                members.forEachIndexed { rank, comp ->
                                    db.insert("favorites", null, appValues(nextId++, comp, folderId, 0, 0, 0).apply {
                                        put("rank", rank)
                                    })
                                }
                            }
                        }
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }

        // 格線與主頁偏好;migration_src_* 同步為目標格線以跳過 grid migration
        sp.edit()
            .putInt("pref_workspaceRows", rows)
            .putInt("pref_workspaceColumns", cols)
            .putInt("pref_hotseatColumns", hotseat)
            .putInt("pref_homeDefaultPage", 2)
            .putString("migration_src_workspace_size", "$cols,$rows")
            .putInt("migration_src_hotseat_count", hotseat)
            .commit()

        Log.i(TAG, "imported: db=$targetDb resolved=${resolver.resolvedCount} unresolved=${resolver.unresolved}")
        return Result(resolver.resolvedCount, resolver.unresolved, folderCount)
    }

    /** 讓桌面以全新程序載入新的格線與資料庫 */
    fun restartLauncher() {
        Process.killProcess(Process.myPid())
    }

    private fun dbName(rows: Int, cols: Int, hotseat: Int) = "launcher_${rows}_${cols}_$hotseat.db"

    private fun readWidgets(context: Context, dbFileName: String): List<ContentValues> {
        val file = context.getDatabasePath(dbFileName)
        if (!file.exists()) return emptyList()
        val result = ArrayList<ContentValues>()
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery(
                "SELECT screen, cellX, cellY, spanX, spanY, appWidgetId, appWidgetProvider " +
                    "FROM favorites WHERE itemType=$ITEM_WIDGET AND container=$CONTAINER_DESKTOP AND screen=0",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    result.add(ContentValues().apply {
                        put("itemType", ITEM_WIDGET)
                        put("container", CONTAINER_DESKTOP)
                        put("screen", 0)
                        put("cellX", c.getInt(1))
                        put("cellY", c.getInt(2))
                        put("spanX", c.getInt(3))
                        put("spanY", c.getInt(4))
                        put("appWidgetId", c.getInt(5))
                        put("appWidgetProvider", c.getString(6))
                        put("restored", 0)
                    })
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "讀取既有 widget 失敗", e)
        } finally {
            db.close()
        }
        return result
    }

    private fun appValues(
        id: Int,
        component: String,
        container: Int,
        screen: Int,
        cellX: Int,
        cellY: Int,
    ) = ContentValues().apply {
        val pkg = component.substringBefore('/')
        put("_id", id)
        put("itemType", ITEM_APP)
        put(
            "intent",
            "#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;" +
                "launchFlags=0x10200000;package=$pkg;component=$component;end",
        )
        put("container", container)
        put("screen", screen)
        put("cellX", cellX)
        put("cellY", cellY)
        put("spanX", 1)
        put("spanY", 1)
    }

    /** 名稱→ComponentName;同名 App 依出現順序輪流對應,同一 App 重複出現則重用 */
    private class LabelResolver(context: Context) {
        private val byLabel: Map<String, List<String>>
        private val occurrence = HashMap<String, Int>()
        val unresolved = ArrayList<String>()
        var resolvedCount = 0
            private set

        init {
            val launcherApps = requireNotNull(context.getSystemService(LauncherApps::class.java))
            byLabel = launcherApps.getActivityList(null, Process.myUserHandle())
                .groupBy({ it.label.toString().trim() }) { it.componentName.flattenToString() }
                .mapValues { (_, v) -> v.sorted() }
        }

        fun resolve(label: String): String? {
            val candidates = byLabel[label] ?: run {
                if (label !in unresolved) unresolved.add(label)
                return null
            }
            val i = occurrence.getOrDefault(label, 0)
            occurrence[label] = i + 1
            resolvedCount++
            return candidates[minOf(i, candidates.size - 1)]
        }
    }
}
