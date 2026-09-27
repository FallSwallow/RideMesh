# RideMesh 離線車隊語音原型

本目錄是 Android／iOS 雙平台原生原型。每支手機同時發布與搜尋鄰近裝置，透過 Google Nearby Connections 的 `P2P_CLUSTER`／`.cluster` 建立離線連線。群組內沒有固定 Host；每支手機都能發話、收話與轉送收到的語音封包。安全帽藍牙耳機只負責手機音訊輸入與輸出。

## 下載與文件

- [Android 測試版 APK](artifacts/RideMesh-Android-debug.apk)
- [原始碼壓縮包](artifacts/RideMesh-prototype.zip)
- [機車車隊離線語音對講 App 企畫書](docs/機車車隊離線語音對講App企畫書.md)

Android 0.3 APK SHA-256：`D3F1B6E2771D9C3E31637D597FB1D6285F5BD3191AE465C15C0FB07EDB8337D0`。

## 原型已實作的範圍

- 由一支手機產生 128 位元群組金鑰，其他手機在出發前輸入同一組 32 字元十六進位金鑰。
- 僅搜尋同群組裝置。雙方在連線後以共享金鑰驗證連線；音訊以 AES-256-GCM 加密。
- 所有手機均發布與搜尋，可連線的成員自動連接。語音封包具備來源、序號與剩餘跳數；中間手機會轉送，並去除重複封包。
- 車隊分成兩段時，各自仍可與可達成員通話；重新靠近後再次發現並連接。
- Android 使用麥克風前景服務；iOS 使用 `playAndRecord`、`voiceChat` 與背景音訊模式。
- 音訊先採用 8 kHz、20 ms、G.711 μ-law，以維持跨平台原型無額外音訊編碼相依。正式版仍須依企畫書改測 Opus、音質與耗電。
- 金鑰欄位下方提供「複製」與「清空」；顯示名稱首次使用時預設為裝置名稱，無法取得可用名稱時預設為 `USER` 加四位數亂碼。仍可在加入前修改名稱，連線後顯示本機及經轉送仍可通訊的頻道成員。

## 專案開啟

### Android

在 Android Studio 開啟 `android/`。需要 Android SDK 35、JDK 17 與 Gradle 8.9；此原始碼包未附 Gradle wrapper，若 Android Studio 要求 wrapper，請在已安裝 Gradle 8.9 的開發機於 `android/` 執行 `gradle wrapper --gradle-version 8.9`。同步後於具 Google Play 服務的實機執行。此原型設定 `minSdk 31`、`compileSdk 35`，Nearby Android 函式庫版本見 `android/app/build.gradle.kts`。按「建立群組」或輸入另一人的金鑰後按「開始」。啟動時需准許麥克風與鄰近裝置權限。

本次另提供 `RideMesh-Android-debug.apk`，可傳送到 Android 12 以上且有 Google Play 服務的手機，點選 APK 安裝。若系統要求，請允許該檔案來源安裝應用程式。這是測試用 debug 簽章；同一 APK 可裝在車隊的 Android 手機，未來若改用另一把簽章金鑰，更新前須先移除這個測試版。首次啟動時請在停車狀態下授予所需權限。

### iOS

在 macOS 安裝 Xcode 與 [XcodeGen](https://github.com/yonaskolb/XcodeGen)，於 `ios/` 執行 `xcodegen generate`，再開啟產生的 `RideMesh.xcodeproj`。Xcode 會取得 [NearbyConnections Swift 套件](https://github.com/google/nearby)。請以實機執行，核准麥克風、藍牙與本機網路權限。iOS 專案檔使用 `com.example.ridemesh` 作為示範 Bundle ID；正式簽署前需改成自己的 ID，並同步更新兩平台的服務 ID 和 iOS `NSBonjourServices` 值。

## 使用

1. 停車時，先配對各自手機與安全帽耳機。
2. 一人建立群組，將畫面顯示的 32 字元群組金鑰交給其他人；每人可沿用預設的顯示名稱或自行修改，其餘手機貼上或輸入相同金鑰後開始。
3. 所有人開始通話並鎖屏，先在安全的封閉場地驗證音訊路由、雙機 20／50／100 公尺、三機 A↔B↔C 轉送、斷鏈與重連，再測五人。
4. 通話畫面會列出目前可達的成員；失去聯繫約 15 秒後移除，重新連上後再次顯示。所有手機需更新至 0.2 版以上，才會互相傳送名稱。

## 原型限制與驗收前提

- **尚未在實機安裝或驗證通話。** Android debug APK 已在 Windows 建置；目前沒有連接 Android 實機，也沒有 Xcode 或騎乘測試裝置。Nearby iOS 套件與系統版本仍需在 Xcode 實際編譯確認。
- 此原型供五人以下車隊試用，但尚未實作全群組人數上限；目前僅限制每支手機最多四個直接連線鄰居。正式版需在跨分段重連時檢查全群組成員數。
- 成員名稱每約 5 秒透過群組金鑰加密的封包同步；離線約 15 秒才會從清單移除，因此清單可能短暫保留已斷線成員。iOS 鎖屏後定時傳送是否持續仍須實機測試。
- iOS 16 以上若未取得 Apple「使用者指定裝置名稱」授權，系統只提供通用裝置名稱；此時預設為 `USER` 加四位數亂碼。首次產生的名稱會保存在本機，之後手動修改並開始通話的名稱也會保存。
- Nearby Connections 可離線運作，但可能選擇藍牙、Wi-Fi 或其他本地媒介。**100 公尺、鎖屏後持續探索／重連及五人同時傳音，均未獲保證。** 尤其 iOS App 被暫停後，附近封包不能保證將它喚醒。
- iOS 傳輸設定已排除 WebRTC 媒介；Android App 也沒有要求 `INTERNET` 權限，但 Nearby 的公開選項不能逐一指定所有媒介。兩平台原型都不呼叫外部伺服器，實機驗收仍須關閉行動數據並在沒有可用路由器的場地測試，確認 SDK 沒有依賴外部連線。
- iOS 端的 Google Nearby 開源 Swift 套件仍需做目標機型上的編譯與互通驗證；若它無法滿足鎖屏與距離要求，下一步應將傳輸層替換為 iOS 26／Android 的原生 Wi‑Fi Aware，而非宣稱原型已達標。
- 目前以共享群組金鑰做自動連線驗證。金鑰必須在出發前經可信途徑交換；不要傳給群組外的人。Nearby 連線在共享金鑰驗證前不可傳送語音。
- G.711 及簡單音量門檻僅用於可行性驗證。風噪、多人同時說話、導航／音樂混音、耳機品牌相容性與長時間耗電，尚待實機調整。
- 此原型不應作為緊急或安全關鍵通訊工具。

## 已完成的檢查

- 以本機 Kotlin 編譯器編譯 `Wire.kt`、`G711.kt`、`MeshRouter.kt`：通過。
- 執行三節點模擬：A↔B↔C 語音轉送、B↔C 斷線時 A↔B 保持通話、重連後恢復跨段通話：通過。
- 固定 AES-GCM 測試封包的 SHA-256：Kotlin 與獨立 Node.js 加密實作均為 `27EB906E0B6203FED9EF4B9C4CB25FDE6012167F3FCDB6A699FD15D95645513E`。
- Android Manifest、iOS Info.plist 與 Entitlements：XML 格式解析通過。
- Android `assembleDebug`：**成功**，產出 0.3 版 debug APK；`testDebugUnitTest`：3 個測試通過，0 失敗，涵蓋加密名稱封包、三機成員轉送、分段逾時與重連。
- Android APK 簽章驗證：v2 簽章有效；最低 Android API 31。APK 未宣告 `INTERNET` 權限。
- iOS Xcode 建置、實機安裝、跨平台連線、鎖屏及騎乘實測：**尚未執行**，需在具 Xcode 與實機的環境完成。

## 協定

`protocol/README.md` 記錄兩平台共用的封包格式與安全邊界。Android 和 iOS 以相同服務 ID `com.example.ridemesh` 互相尋找。iOS Bonjour 類型是此服務 ID 的 SHA-256 前 6 位元組（12 個十六進位字元）：`_D63418003B44._tcp`。

## 依據

- [Google Nearby Connections：跨平台與離線連線](https://developers.google.com/nearby)
- [Google Nearby Connections：群集拓樸](https://developers.google.com/nearby/connections/strategies)
- [Google Nearby Connections：Swift 設定](https://developers.google.com/nearby/connections/swift/get-started)
- [Android 麥克風前景服務](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Apple 背景雙向音訊](https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/playandrecord)
