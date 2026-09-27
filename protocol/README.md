# RideMesh v1 封包

所有整數均以網路位元組順序（big endian）表示。

## 發現資訊

Nearby 服務 ID 固定為 `com.example.ridemesh`。每支手機的廣告資訊為 UTF-8 `roomId|PnodeId`：`roomId` 是群組金鑰 SHA-256 的前 8 個十六進位字元；`P` 是平台字元（`A` Android、`I` iOS）；`nodeId` 是每次開始通話新產生的 8 位元組隨機值，以 16 個十六進位字元表示。只有 `roomId` 相同的端點會嘗試連線。兩方都同時發布與搜尋；同平台連線由字典序較小的 `nodeId` 提出請求，跨平台連線由 iOS 端提出請求，以免兩方同時提出相反方向的連線。

## 金鑰與連線驗證

使用者輸入 16 位元組隨機群組金鑰，以 32 字元大寫十六進位呈現。`roomId = first4bytes(SHA256(groupKey))`。`mediaKey = SHA256(groupKey || UTF8("RideMesh-media-v1"))`。Nearby 完成連線協商後，兩端取得相同的四位數驗證碼，計算 `HMAC-SHA256(mediaKey, UTF8("RideMesh-link-v1:" || verificationCode))`。驗證封包格式是 `RM`、版本 `01`、型別 `01`、32 位元組 HMAC。只在收到正確驗證封包後把鄰居列為已授權；未授權的連線不能收發語音。應在配對／重連時使用新的 Nearby 驗證碼。

## 語音封包

| 位移 | 長度 | 欄位 |
| --- | ---: | --- |
| 0 | 2 | ASCII `RM` |
| 2 | 1 | 版本 `01` |
| 3 | 1 | 型別 `02`（語音） |
| 4 | 8 | 來源 nodeId 原始位元組 |
| 12 | 4 | 來源序號，UInt32 |
| 16 | 1 | 剩餘轉送跳數，初始值 4 |
| 17 | 2 | 後續密文與 16 位元組 GCM tag 的總長度，UInt16 |
| 19 | 變長 | AES-GCM 密文與 tag |

AES-256-GCM nonce 是 `nodeId(8) || sequence(4)`，AAD 是位移 0–15 的 16 位元組不變標頭。跳數不在 AAD 中，使中繼節點能遞減後轉送原始密文；接收節點仍須先成功驗證 GCM tag 才能播放或轉送。相同來源與序號只處理一次。語音明文是 160 位元組 G.711 μ-law 單聲道資料，即 8 kHz 的 20 ms。每次通話開始都必須產生新 nodeId，避免相同群組金鑰下 nonce 重用。

離線無線網路若裂成兩個完全不連通的區段，兩段各自通話，無法互傳即時語音。重新相遇並完成連線驗證後，後續語音封包會再次跨段轉送。
