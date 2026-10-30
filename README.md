# LegacyMapFix

把 HuskSync 3.8 以前存在物品上的**舊格式上鎖地圖**，轉換成本分流的真正地圖，讓 HuskSync 3.8+ 能再次正常跨分流同步。

## 問題

HuskSync 3.8 重寫了上鎖地圖的同步方式，新格式在物品的 `custom_data` 裡記錄
`husksync:persisted_locked_map: {origin, id}`。
更早期的物品是另一種格式：`husksync:persisted_locked_map` 裡只有 `canvas_data`（壓縮的像素），另外用 `id_mappings` 以世界 UUID 記錄編號。

HuskSync 3.8+ 遇到這種物品時：

1. **讀取**：找不到 `origin`／`id`，也不認得這個位置的 `canvas_data`，於是略過，物品維持原本的 `map_id`。在非原分流就會顯示當地同編號的地圖：空白、別張圖，或在展示框裡顯示成圖標。
2. **存檔**：物品上已經有 `husksync:persisted_locked_map`，HuskSync 認為已經存過而跳過，所以**永遠不會升級成新格式**。

## 做法

對每張舊格式地圖：

1. 解壓縮 `canvas_data`（容許缺少 gzip 結尾），並把像素**轉置**成原版順序。HuskSync 使用的 MapDataAPI 以 `colors[x * 128 + y]` 存放，原版地圖檔是 `colors[x + y * 128]`。
2. 本分流已經有像素完全相同的**上鎖**地圖時，直接沿用它的編號（`EXISTING`）。
3. 否則建立一張真正的地圖，把像素寫進地圖檔（`CREATED`）。同一張圖的複本共用同一個編號（`REUSED`）。
4. 清除物品上舊的 HuskSync 資料，之後交給 HuskSync 3.8+ 用新格式處理。

轉換後的地圖像素存在地圖檔裡，重開服後原版就能直接顯示，不依賴 HuskSync 的執行期繪製。

## 烘焙 HuskSync 地圖（1.1.0+）

HuskSync 新格式的地圖在**非原分流**上是虛擬地圖：本地地圖檔是空白的，畫面靠 HuskSync 在特定時機（例如玩家同步背包）才繪製。所以放在展示框裡時，重開服後常常空白或顯示成物品圖標，要過一段時間或拿到手上才會出現。

`bake-husksync-maps: true`（預設）時，插件遇到這種地圖，會從 HuskSync 讀取原圖像素，寫進本分流**空白的**對應地圖檔，之後原版就能直接顯示。只會寫入 HuskSync 資料庫裡對應到的那個編號，有內容的地圖一律不動。結果記錄在 `conversions.log`（`BAKED`）。

## 需求

- Paper 1.21.8 或其分支（Leaves、Leaf 等），Java 21
- HuskSync 3.8+（選用；有安裝時會在同步完成後才轉換玩家背包）

## 安裝與使用

1. 把 jar 放進每個分流的 `plugins`，重啟。
2. 預設**不會自動轉換**。先用指令測試：

| 指令 | 說明 |
|---|---|
| `/lmf inspect` | 檢查手上的地圖是否為舊格式，以及會怎麼處理 |
| `/lmf fix` | 轉換手上的地圖 |
| `/lmf scan [玩家]` | 轉換背包和末影箱 |
| `/lmf frames [半徑]` | 轉換周圍展示框裡的地圖（預設 64 格，最多 256） |
| `/lmf stats` | 統計 |
| `/lmf reload` | 重新載入設定 |

權限：`legacymapfix.admin`（預設 OP）

3. 測試沒問題後，在 `config.yml` 打開 `auto-convert`：

```yaml
auto-convert:
  on-sync-complete: true   # HuskSync 同步完成後轉換背包和末影箱
  on-inventory-open: true  # 打開容器時轉換
  on-entities-load: true   # 區塊載入時轉換展示框；啟動時也會掃描已載入的展示框
  on-pickup: true          # 撿起掉落物時轉換
```

## 安全措施

- 原始 `canvas_data` 備份在 `plugins/LegacyMapFix/canvas-backups/`
- 每次轉換都記錄在 `plugins/LegacyMapFix/conversions.log`
- 讀取或寫入失敗時，物品不會被修改

## 工具

`tools/find_legacy_maps.py`：唯讀掃描玩家存檔裡的舊格式地圖，並和各分流的地圖檔比對（兩種像素方向），可用來確認轉置方向與找出原圖。

```bash
python3 tools/find_legacy_maps.py <world_dir> [<world_dir> ...]
```

## 下載與建置

- 正式版：到 [Releases](https://github.com/Lunluns-World/LegacyMapFix/releases) 下載 jar
- 開發版：每次推送到 `main` 都會由 GitHub Actions 編譯，jar 在對應執行紀錄的 Artifacts

自行建置：

```bash
mvn package
```

輸出：`target/LegacyMapFix-<version>.jar`

### 發布新版本

1. 修改 `pom.xml` 的 `<version>`
2. 推送後建立對應的 tag，例如 `git tag v1.1.1 && git push origin v1.1.1`
3. Actions 會編譯並建立 Release，附上 jar（tag 和 `pom.xml` 版本不一致時會失敗）

## 授權

Copyright (C) 2026 Lunlun's World

本程式為自由軟體，依自由軟體基金會發布的 GNU 通用公共授權條款（GPL）**第 2 版或（依你的選擇）任何更新的版本**（`GPL-2.0-or-later`）散布及修改。完整條款見 [LICENSE](LICENSE)。

