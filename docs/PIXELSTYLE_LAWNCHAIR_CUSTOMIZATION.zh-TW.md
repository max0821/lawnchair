# 06 Lawnchair：從自製 Launcher 到 PixelStyle 客製

> 狀態：技術整理草稿。程式客製分支為 `codex/06-lawnchair-pixelstyle`。

## 為什麼改用 Lawnchair

這個專案最初嘗試自行實作 Android Launcher；後來改以 [Lawnchair](https://github.com/LawnchairLauncher/lawnchair) `v15.0.0-beta3.0` 為基底。原因不是要重做既有能力，而是 Launcher3 已經處理好小工具的預覽與選擇、跨頁小工具、拖放、尺寸調整、應用程式抽屜與系統 Home 角色等複雜行為。

這個 fork 保留與上游 Lawnchair 的關聯；客製程式在獨立分支中維護。使用、散布與後續修改仍須遵守上游的 [Apache License 2.0](../LICENSE.txt)。

## 兩個核心亮點

### 1. 主頁左邊的小工具頁

PixelStyle 的主頁預設在第 2 頁，左邊的第 1 頁（`screen 0`）專門作為小工具頁。這個頁面不再依賴 At a Glance 是否啟用，因此使用者可關閉時間資訊，仍保有固定的小工具空間。

Home 鍵與冷啟動會回到使用者指定的預設主頁，而不是一律回到第 1 頁。設定頁提供 1 到 9 頁的「預設主畫面頁」滑桿；預設為第 2 頁，單頁裝置則會自動夾限到可用頁面。

### 2. 自動依原生桌面匯入

匯入入口位於「設定 → 主畫面 → 版面配置 → 匯入原生桌面排列」。流程如下：

```text
啟用 Lawnchair 無障礙服務
        ↓
切換到裝置的原生 Launcher
        ↓
逐頁讀取桌面、Dock 與資料夾內容
        ↓
以圖示座標推算格線，將顯示名稱解析為已安裝 App 的 Component
        ↓
重建 Lawnchair favorites 資料庫、保存格線設定並重啟 Launcher
```

掃描器會以手勢翻頁、開啟資料夾並處理資料夾分頁；匯入時保留現有 `screen 0` 的小工具，將桌面頁寫入 `screen 1..N`，並盡量保留 Dock、資料夾和空格位置。

這項功能使用的是 Android 無障礙服務的讀取視窗內容與手勢能力。使用者必須在系統設定中自行啟用權限，功能不會繞過該授權流程。

## 其他客製

| 功能 | 使用者可見結果 | 主要實作位置 |
| --- | --- | --- |
| 預設主畫面頁 | Home 與冷啟動可回到指定頁 | `Workspace.java`、`Launcher.java`、`QuickstepLauncher.java`、`ModelCallbacks.kt` |
| 固定小工具頁 | `screen 0` 不因關閉 At a Glance 而被清除 | `Workspace.java`、`ModelCallbacks.kt` |
| 原生桌面匯入 | 從原生 Launcher 重建頁面、資料夾、Dock 與格線 | `lawnchair/src/app/lawnchair/nativeimport/` |
| 資料夾視窗大小 | 設定中可調 80% 至 130%，並避免超出螢幕 | `DeviceProfile.java`、`FolderPreferences.kt` |
| 首次安裝版面 | 預設項目放在第 2 頁，讓第 1 頁留給小工具 | `lawnchair/res/xml/default_workspace_*.xml` |

## 實作時學到的事

### 不要輕易自寫 Launcher 的基礎層

小工具看似只是放一個 View，實際上牽涉綁定授權、Provider 尺寸、預覽選擇器、拖放、旋轉、重新啟動與資料遷移。改在成熟的 Launcher3 架構上做小範圍客製，能把時間放在產品行為，而不是反覆補齊系統相容性。

### 「匯入」要寫成可回復的資料操作

Lawnchair 的桌面資料依格線儲存在 `launcher_{rows}_{columns}_{hotseat}.db`。匯入時必須同步更新格線偏好和 migration 來源，否則下一次啟動可能觸發資料遷移、覆蓋剛寫入的配置。直接寫資料庫也意味著匯入前應先備份使用者資料。

### 以可理解的限制取代過度承諾

掃描依賴原生 Launcher 的無障礙節點、圖示內容描述與應用程式顯示名稱。因此它目前應描述為「以 Pixel／相近 Launcher 實測的匯入工具」，不是保證相容所有廠牌與語言的通用遷移器。同名 App、未安裝 App、Widget、客製圖示和原生 Launcher 的 UI 變動都可能影響還原結果。

## 驗證狀態

- **已檢查**：本分支的程式差異通過 `git diff --check`，未發現空白格式問題。
- **專案既有紀錄**：記錄指出原生桌面匯入、小工具與預設主頁曾於 Pixel 實機驗證。
- **本次未重新執行**：完整 Gradle 建置與實機回歸測試；因此不將它宣稱為本次新驗證結果。

## 後續建議

1. 匯入前自動建立使用者資料庫備份，並提供還原入口。
2. 匯入完成後加入預覽／確認畫面，避免一次覆寫桌面。
3. 將掃描器的 Launcher 判斷、節點選擇和語言規則拆成可測試的 adapter。
4. 在實機上分別測試：無障礙未啟用、空桌面、多頁資料夾、同名 App、未安裝 App、不同格線與匯入後冷啟動。

## 建置（不含簽章資訊）

在專案根目錄可使用：

```powershell
.\gradlew.bat assembleLawnWithQuickstepGithubDebug
```

簽章檔與本機設定檔應維持在 Git 忽略清單中，不能提交到 fork 或公開文件。
