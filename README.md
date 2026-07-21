# KIDY TLOCK TV

以 [SkyTube](https://github.com/SkyTubeTeam/SkyTube) 2.999 OSS 為基礎、針對 Android TV 與電視盒遙控器重新設計的 YouTube／Bilibili 雙平台播放器。

![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Version](https://img.shields.io/badge/version-2.999--tv91-blue)
![License](https://img.shields.io/badge/license-GPL--3.0-orange)
![UI](https://img.shields.io/badge/UI-Android%20TV-purple)

> 專案定位：免登入 Google 帳號、以遙控器操作為核心，整合 YouTube、Bilibili、OPML 頻道管理、播放來源切換與家長控制的 Android TV 工具。

## 主要特色

### Android TV 介面與遙控器操作

- 固定式左側訂閱頻道欄，右側顯示影片內容。
- 針對方向鍵、確定鍵、返回鍵與長按操作調整焦點流程。
- 首頁影片網格、頂部分頁、工具列與頻道欄可連續移動焦點。
- 播放頁支援方向鍵快轉／倒退、播放暫停、選集與推薦影片。
- 16:9 橫向介面，適合 1080p Android TV 與電視盒。
- 支援遙控器語音搜尋；裝置攔截實體語音鍵時，可在搜尋框長按確定鍵說話。

### YouTube 播放

- 熱門、最新、訂閱動態、搜尋、頻道與播放清單。
- 本機訂閱，不需要登入 Google／YouTube 帳號。
- 自動續播、觀看進度、推薦影片與自動播放倒數。
- 多重播放後端與失敗備援，包括一般串流、HLS、SABR 與 Web Player fallback。
- 支援播放速度、音訊選擇、字幕、SponsorBlock、書籤及影片封鎖條件。

### Bilibili 整合

- 首頁提供「BILI 熱門」分頁。
- 可訂閱任意 Bilibili UID，瀏覽頻道影片、搜尋結果、播放清單及多集影片。
- 預設以 720p 播放，可在播放器選擇可用解析度。
- 可切換 AVC1 或 HVC1／HEVC；HVC1 通常較省頻寬，AVC1 相容性較佳。
- 可切換 Bilibili 回傳的主要／備援 CDN 來源，方便測試不同線路流暢度。
- 播放停滯時會嘗試備援來源，並保留診斷資訊供問題分析。

### 播放紀錄

- 首頁原「下載」分頁改為「播放紀錄」。
- 影片真正開始播放便立即建立紀錄，不必播放完畢，短暫播放也會顯示。
- 每筆以橫列顯示：影片縮圖、影片標題、相對播放時間。
- 依最後播放時間由新到舊排列。
- 僅保留最近 72 小時，逾期資料會自動清理。

### OPML 頻道管理

- 支援本機 OPML 匯入、匯出，以及以雲端直連網址同步頻道清單。
- 支援合併或取代現有訂閱。
- 匯入 Bilibili 空間網址時會自動辨識 UID，取得真實頻道名稱、ICON 與訂閱數。
- 匯出後會保留標準 Bilibili 空間網址，可再次匯入而不遺失平台資訊。
- YouTube 與 Bilibili 頻道可以放在同一份 OPML 中。

OPML 範例：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<opml version="1.0">
  <head>
    <title>KIDY TLOCK TV subscriptions</title>
  </head>
  <body>
    <outline text="YouTube 頻道"
             type="rss"
             xmlUrl="https://www.youtube.com/feeds/videos.xml?channel_id=UC_CHANNEL_ID"
             htmlUrl="https://www.youtube.com/channel/UC_CHANNEL_ID" />

    <outline text="Bilibili 頻道"
             type="rss"
             xmlUrl="https://space.bilibili.com/5089070"
             htmlUrl="https://space.bilibili.com/5089070" />
  </body>
</opml>
```

`text` 可以先填自訂名稱；Bilibili 頻道匯入成功後，APP 會用線上資料補齊正式名稱與頭像。

### 安全性與家長控制

- 偏好設定可使用 PIN 碼保護。
- 可依頻道、語言、觀看數等條件封鎖影片。
- 專案包含獨立的 `parentalControl` Android TV 模組。
- 訂閱、播放進度、播放紀錄與偏好設定皆儲存在裝置本機。

## 系統需求

| 項目 | 需求 |
| --- | --- |
| Android | Android 10／API 29 以上 |
| 顯示 | 建議 16:9、1920×1080 以上 |
| 操作 | Android TV 遙控器或方向鍵輸入裝置 |
| 網路 | YouTube／Bilibili 可連線環境 |
| 帳號 | 不需要 Google 或 Bilibili 登入 |

APP 套件名稱為 `free.rm.skytube.tv`，可與官方 SkyTube 並存。

## 建置原始碼

### 開發環境

- JDK 21
- Android SDK Platform 36
- Android SDK Build Tools
- Git

專案已包含 Gradle Wrapper。Windows PowerShell：

```powershell
git clone https://github.com/fekilooo/kidytlocktv.git
cd kidytlocktv
.\gradlew.bat assembleOssDebug
```

Linux／macOS：

```bash
git clone https://github.com/fekilooo/kidytlocktv.git
cd kidytlocktv
./gradlew assembleOssDebug
```

建置完成的 APK 位於：

```text
app/build/outputs/apk/oss/debug/SkyTube-audit-Oss-2.999-tv91.apk
```

使用 ADB 安裝：

```bash
adb install -r app/build/outputs/apk/oss/debug/SkyTube-audit-Oss-2.999-tv91.apk
```

## 專案結構

| 路徑 | 說明 |
| --- | --- |
| `app/` | KIDY TLOCK TV 主程式 |
| `app/src/main/java/free/rm/skytube/businessobjects/bilibili/` | Bilibili 頻道、搜尋、清單與串流整合 |
| `app/src/main/java/org/schabi/newpipe/` | 本版使用的播放與 SABR 支援程式 |
| `patchedExtractor/` | 本版相依的 NewPipe Extractor 修正版封裝 |
| `parentalControl/` | Android TV 家長控制輔助模組 |
| `tools/tvbox-launcher-editor/` | 電視盒 Launcher 編輯輔助工具 |
| `fastlane/` | Android 發行資訊與 changelog |

## 資料與隱私

- APP 不要求 Google 帳號登入。
- 訂閱、書籤、觀看進度與播放紀錄預設保存在本機 SQLite 資料庫。
- OPML 只有在使用者主動匯入、匯出或設定雲端直連網址時才會傳輸。
- 診斷 LOG 可能包含影片 ID、播放路徑與錯誤訊息；分享 LOG 前請自行確認內容。

## 已知限制

- YouTube 與 Bilibili 的網頁、API 或串流規則變更時，播放與資料擷取功能可能需要更新。
- HVC1／HEVC 是否能順暢硬體解碼取決於電視盒晶片；遇到卡頓可切回 AVC1。
- 不同地區、ISP、DNS 或 CDN 可能造成速度差異，可在 Bilibili 播放器切換來源測試。
- 這是針對特定 Android TV 使用情境調整的個人維護版本，並非 SkyTube 官方發行版。

## 上游專案與授權

本專案衍生自 [SkyTubeTeam/SkyTube](https://github.com/SkyTubeTeam/SkyTube)，並使用或修改 NewPipe Extractor、AndroidX Media3、ExoPlayer 等開源元件。各第三方元件依其原始授權條款提供。

主程式依 [GNU General Public License v3.0](LICENSE) 發布。修改或散布本專案時，請遵守 GPL-3.0 的原始碼提供與相同授權要求。

本專案與 YouTube、Google、Bilibili 及其關係企業沒有隸屬、授權或背書關係；相關商標屬各自權利人所有。

## 問題回報

請在 [GitHub Issues](https://github.com/fekilooo/kidytlocktv/issues) 提供：

1. Android TV／電視盒型號與 Android 版本。
2. APP 版本（目前為 `2.999-tv91`）。
3. 問題發生的平台（YouTube 或 Bilibili）。
4. 可重現步驟、選擇的解析度／Codec／來源。
5. 必要時附上由 APP 匯出的診斷 LOG；請避免公開個人資料。
