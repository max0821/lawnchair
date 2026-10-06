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

## 新增的小工具與待機模式（r2）

### 常用工具包小工具

一個小工具裡放多個 App 與系統工具（手電筒、QR 掃描、即時轉錄、鬧鐘、計時器、相機、Wi-Fi、藍牙），支援 5x1、5x2 等尺寸。挑選畫面依「最近開啟」排序（需要使用情況存取權，未授權時改為字母排序），可拖曳調整順序。手電筒用 `CameraManager.setTorchMode` 切換，不需要相機權限。

### 時鐘與天氣小工具

5x2 的時鐘、日期、今日天氣與整週（週一到週日）預報，正中顯示日期，下方顯示農曆。天氣來自免金鑰的 [Open-Meteo](https://open-meteo.com/)，以城市搜尋取得座標，**不使用定位權限**。農曆用平台內建的 `android.icu.util.ChineseCalendar`；節日讀取系統的節日日曆，查詢範圍限縮在 Google 節日日曆帳戶，不會讀到個人日曆。

### HTML 螢幕保護程式（待機模式）

在「設定 → 顯示 → 螢幕保護程式」選擇「HTML 螢幕保護程式」，充電或放上座架時顯示：

- **內建頁**：仿 iPhone StandBy 的數字時鐘、本週天氣與月曆（含農曆），背景為 21 組暗色夜景，直式與橫式各一版、每天輪換；房間變暗時切換為黑底紅字的夜間模式。
- **自訂頁面**：可填網址或選擇手機裡的 HTML 檔；網址連不上時自動改用內建頁，恢復連線後切回。網頁只會單向收到光線、電量與天氣資料，無法呼叫 App。
- **通知**：系統不會在螢幕保護上跳出通知，因此由 App 在網頁上方自行繪製未讀通知的 App 圖示列與新通知橫幅。通知內容不會交給網頁，並遵守鎖定畫面的隱私設定（鎖定時隱藏敏感內容）。
- **暗了自動關螢幕**：房間持續變暗一段時間（預設 10 分鐘，可調整或關閉）後關閉螢幕。需啟用 Lawnchair 無障礙服務；未啟用時只會把螢幕調到最暗。

## 其他客製

| 功能 | 使用者可見結果 | 主要實作位置 |
| --- | --- | --- |
| 預設主畫面頁 | Home 與冷啟動可回到指定頁 | `Workspace.java`、`Launcher.java`、`QuickstepLauncher.java`、`ModelCallbacks.kt` |
| 固定小工具頁 | `screen 0` 不因關閉 At a Glance 而被清除 | `Workspace.java`、`ModelCallbacks.kt` |
| 原生桌面匯入 | 從原生 Launcher 重建頁面、資料夾、Dock 與格線 | `lawnchair/src/app/lawnchair/nativeimport/` |
| 資料夾視窗大小 | 設定中可調 80% 至 130%，並避免超出螢幕 | `DeviceProfile.java`、`FolderPreferences.kt` |
| 首次安裝版面 | 預設項目放在第 2 頁，讓第 1 頁留給小工具 | `lawnchair/res/xml/default_workspace_*.xml` |
| 常用工具包小工具 | 一個小工具收納多個 App 與系統工具 | `lawnchair/src/app/lawnchair/toolkit/` |
| 時鐘與天氣小工具 | 時鐘、日期、農曆、節日與整週天氣 | `lawnchair/src/app/lawnchair/weather/` |
| HTML 螢幕保護程式 | 待機畫面、通知顯示、暗了自動關螢幕 | `lawnchair/src/app/lawnchair/dream/`、`lawnchair/assets/dream/` |

## 實作時學到的事

### 不要輕易自寫 Launcher 的基礎層

小工具看似只是放一個 View，實際上牽涉綁定授權、Provider 尺寸、預覽選擇器、拖放、旋轉、重新啟動與資料遷移。改在成熟的 Launcher3 架構上做小範圍客製，能把時間放在產品行為，而不是反覆補齊系統相容性。

### 「匯入」要寫成可回復的資料操作

Lawnchair 的桌面資料依格線儲存在 `launcher_{rows}_{columns}_{hotseat}.db`。匯入時必須同步更新格線偏好和 migration 來源，否則下一次啟動可能觸發資料遷移、覆蓋剛寫入的配置。直接寫資料庫也意味著匯入前應先備份使用者資料。

### 以可理解的限制取代過度承諾

掃描依賴原生 Launcher 的無障礙節點、圖示內容描述與應用程式顯示名稱。因此它目前應描述為「以 Pixel／相近 Launcher 實測的匯入工具」，不是保證相容所有廠牌與語言的通用遷移器。同名 App、未安裝 App、Widget、客製圖示和原生 Launcher 的 UI 變動都可能影響還原結果。

### 小工具的 RemoteViews 限制

小工具版面只能使用 RemoteViews 支援的 View。只要版面中任何位置出現一個 `<View>`（例如當分隔線），整個小工具就會顯示「無法載入小工具」；分隔線與佔位一律改用 `FrameLayout`。

### 螢幕保護程式要「靜止」才省電

內建頁曾用一個 60 秒的 CSS 轉場讓背景緩慢漂移，結果畫面永遠在動，120Hz 螢幕每一格都要重算卡片的模糊效果，充電時明顯發熱。改為背景靜止、防烙印位移每分鐘瞬間跳動後，Lawnchair 的 CPU 使用率從約 29% 降到約 1.4%。

另外，螢幕保護自行結束（`finish()`）並不會關閉螢幕：系統會喚醒回鎖定畫面，充電中又會重新進入螢幕保護。要真正關螢幕，必須像電源鍵一樣鎖定裝置（無障礙服務的 `GLOBAL_ACTION_LOCK_SCREEN`）。

## 驗證狀態

- **r1**：原生桌面匯入、小工具頁與預設主頁曾於 Pixel 實機驗證。
- **r2**：常用工具包、時鐘與天氣小工具、HTML 螢幕保護程式（含通知顯示、鎖定時的隱私處理、暗了自動關螢幕與省電修正）皆在 Pixel 實機（Android 17）上建置並實測。

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
