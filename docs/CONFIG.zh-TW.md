# Kyokalith 設定參考

[English](CONFIG.md)

`plugins/Kyokalith/config.yml`。**沒有 `/kyo reload`**——設定只在 `onEnable` 讀一次,改完要重開伺服器。

設定驗證是 **fail-fast**:任何一個礦定義不合法(沒有材質、`y_min > y_max`、`cell_chance` 超出 0..1、`ores:` 整段空的),**插件會直接自我停用**,而不是帶著壞設定跑。看到 `Kyokalith` 沒 enable,先看 log 第一行。

---

## `config_schema_version`

| Key | 型別 | 目前版本 | 說明 |
|---|---|---|---|
| `config_schema_version` | Int | `2` | YAML 設定格式版本;跟 DB 的 `schema_version`、`vein_algorithm_version` 是不同東西 |

啟動時會先用 `contains(path, true)` 只讀檔案本身的版本,再合併 defaults。沒有這個 key 就視為 v1。

這個順序是 v1 → v2 安全升級的關鍵。Bukkit `copyDefaults(true)` 原本會把所有新版 `y_weight_points` 清單塞進舊 ore section,卻保留該伺服器原本的 `y_min`/`y_max`;曲線端點不一致時,fail-fast 驗證就會把插件停用。偵測到 v1 時,Kyokalith 會:

1. 合併一般的新預設 key;
2. 把每種礦繼承來的 `ores.<type>.y_weight_points` 改成空清單;
3. 儲存 `config_schema_version: 2`;
4. 寫出 `Legacy config detected...` warning,並沿用該礦原本的 `preferred_y` 三角分布。

這能保證舊 config 原地升級時正常啟用,但**不代表已套用新版內建校準曲線**。要使用那些曲線,請安裝 release/patch 提供的完整 v2 `config.yml`;不要只把 `y_weight_points` 貼進舊 Y 範圍。

---

## `locale`

| Key | 型別 | 預設 | 說明 |
|---|---|---|---|
| `locale` | String | `zh_TW` | 管理指令輸出語系。內建:`en`、`zh_TW` |

語系檔在 `plugins/Kyokalith/lang/<locale>.yml`,逐 key 覆蓋內建文字——刪掉的 key 回退內建預設(語系缺的 key 回退英文)。要加語言:把 `lang/en.yml` 複製成 `lang/<名稱>.yml` 翻譯後,設 `locale: <名稱>`。色碼用 `&`,`{佔位符}` 由插件代入。

---

## `notify_admins_on_ore_find`

| Key | 型別 | 預設 | 說明 |
|---|---|---|---|
| `notify_admins_on_ore_find` | Boolean | `false` | 開啟後,玩家挖到真礦(非誘餌)時廣播給所有持有 `kyokalith.admin` 的在線玩家 |

純觀察用的管理便利功能,跟誘餌決算本身無關(監聽自己的 `OreCheckTriggerEvent`,MONITOR 優先度,不影響判定)。用 `/kyo notify <on|off>` 切換,會寫回這個 key 並立即生效——是整份 config 裡**唯一**不需要重啟/重載就能生效的設定(其餘都只在 `onEnable` 讀一次)。

---

## `database`

| Key | 型別 | 預設 | 說明 |
|---|---|---|---|
| `database.file` | String | `kyokalith.db` | SQLite 檔名,相對於 `plugins/Kyokalith/` |
| `database.dirty_flush_interval_ticks` | Long | `40` | dirty 位置寫回 DB 的間隔,單位 tick(40 = 2 秒) |

> 🔴 **`dirty_flush_interval_ticks` 是紅線,兩邊都不能亂調。**
>
> 寫回任務跑在**非同步排程**上(1.4.2 起;Folia 上是 AsyncScheduler),一輪待寫區塊共用一條 JDBC 連線與一個 transaction 做批次 `INSERT OR REPLACE`。這輪工作完全不碰 Bukkit API,寫入路徑(flush / flushAll / clearEpoch)由 store 自己的鎖互斥,`onDisable` 的最後一次 flush 會等進行中的那輪跑完。
>
> - **調太小(例如 `1`)**:每 tick 一次 SQLite 批次寫入,雖然不在 ticking 執行緒上,磁碟與鎖的壓力仍然是實打實的。小於 `1` 會被夾成 `1`。
> - **調太大**:當機時遺失的 dirty 位置變多——而遺失 dirty 旗標**是正確性/漏洞問題**,不只是資料掉了:被玩家蓋住的方塊會重新變成「可首次曝光解析」,蓋起來再挖開的漏洞就回來了。
>
> 預設 40 是兩邊之間的平衡點,除非你知道自己在做什麼,不要動。

---

## `ores`

資料驅動。加一種礦、拿掉一種礦、改深度分布,**都不用改程式碼**。

```yaml
ores:
  diamond:
    enabled: true
    materials:
      stone: DIAMOND_ORE
      deepslate: DEEPSLATE_DIAMOND_ORE
    dimension: NORMAL
    y_min: -63
    y_max: 16
    preferred_y: -59
    y_weight_points:
      - [-63, 1.0]
      - [16, 0.0]
    density: 1.0
    vein_size_min: 1
    vein_size_max: 4
    cell_chance: 0.06
```

| Key | 型別 | 預設 | 說明 |
|---|---|---|---|
| `enabled` | Boolean | `true` | `false` = Kyokalith 完全無視這種礦:誘餌保持原版、挖掘保持原版、不發檢定事件 |
| `materials.stone` | Material | 必填(至少一個) | 基礎方塊是 `STONE` **或 `NETHERRACK`** 時要生成的礦方塊 |
| `materials.deepslate` | Material | – | 基礎方塊是 `DEEPSLATE` 時的礦方塊。**不會 fallback 到 `stone`**——沒設就是深板岩層不出這種礦 |
| `dimension` | `NORMAL` / `NETHER` / `THE_END` | `NORMAL` | 只在這個維度解析。**地獄礦一定要明寫 `NETHER`** |
| `y_min` / `y_max` | Int | `0` | 硬性範圍,超出永遠不解析 |
| `preferred_y` | Int | `0` | 舊版三角形 Y 權重的峰值;只有沒設定 `y_weight_points` 時才使用 |
| `y_weight_points` | `[y, weight]` 清單 | – | 可選的分段線性 Y 曲線。依 Y 排序、點與點之間線性插值,超出首尾點時沿用端點權重 |
| `density` | Double | `1.0` | 乘在 `cell_chance` 上的倍率 |
| `vein_size_min` / `vein_size_max` | Int 1–32 | `1` / `1` | 單顆決定性、六面連通礦脈的精確目標方塊數;每個 `veinId` 都受這個值嚴格限制 |
| `cell_chance` | Double 0.0–1.0 | 必填 | 一個 8×8×8 的 cell 生出礦脈原點的機率(還沒乘 `density` 與 Y 權重) |
| `priority` | Int | `0` | 支援本次查詢 base material 的不同礦種形狀重疊時做整顆原子仲裁:較低 priority 的候選整顆淘汰,同 priority 才由較低 `veinId` 勝出;存活礦脈保留完整連通形狀與精確格數。相鄰同礦候選也抑制較高的穩定 `veinId`。`/kyo inspect` 會顯示 |

### 實際命中機率

```
啟用機率 = clamp(cell_chance × density × yWeight(y), 0, 1)
```

有設定 `y_weight_points` 時,`yWeight` 使用該分段線性曲線;沒設定時保留舊版三角形 fallback:`preferred_y` 處為 1.0,往 `y_min` / `y_max` 兩端線性掉到 0。

### 🔴 紅線

**`vein_size` 控制一次遭遇的產量,不是遭遇頻率。** 它是 1 到 32 的精確目標格數,形狀固定可重現且六面連通。想讓玩家更常遇到礦,要一起量測總密度後調 `cell_chance`、`density` 與 Y 曲線,不要把單脈放大。

**不要單獨照抄 1.3.3 之前的 `cell_chance`。** 一個16³ cell等於八個8³ cell,數字不能直接比較。內建11礦的 `cell_chance`、`vein_size`與Y曲線是一整組量測結果;回歸測試會同時守住隧道間距與64³總量上限。

**同礦種接觸候選不會合併。** 系統會決定性檢查相鄰 cell;兩顆同礦形狀接觸時只保留較低的穩定 `veinId`。這是生成契約,不是可調機率。

**跨礦種重疊不會切出殘片。** 仲裁單位是整顆候選,不是逐座標:敗者整顆消失,勝者仍是六面連通且精確等於 `vein_size`。

**`dimension` 沒設 = 只在主世界。** (舊版 config 的註解說「不設 = 所有維度都會命中」,那是錯的——程式預設 `NORMAL` 並做精確比對。)

**`cell_chance` / `density` 直接等於伺服器的礦產水龍頭。** 設計目標是:玩家挖隧道(碰到的都是誘餌)感受到的礦密度,要跟原版在洞穴壁上看到的密度差不多。改這兩個數字之前先想清楚你要的是哪一種經濟。

**`salt` 不在 config 裡,而且不能重置。** 它在 DB 的 `meta` 表,第一次啟動時隨機生成。重置 salt = 全世界所有還沒挖開的礦脈重骰。

---

## 內建的礦

主世界:`coal` `iron` `copper` `gold` `redstone` `lapis` `diamond` `emerald`
地獄(`dimension: NETHER`):`nether_quartz` `nether_gold` `ancient_debris`

全部 `enabled: true`、`density: 1.0`。

## 加一種新礦

```yaml
ores:
  my_custom_ore:
    enabled: true
    materials:
      stone: EMERALD_ORE            # 基礎方塊是石頭/地獄岩時出這個
      deepslate: DEEPSLATE_EMERALD_ORE
    dimension: NORMAL
    y_min: -16
    y_max: 80
    preferred_y: 32                 # 分布峰值
    y_weight_points:                # 可選;刪除即使用舊版三角形 fallback
      - [-16, 0.0]
      - [32, 1.0]
      - [80, 0.0]
    density: 1.0
    vein_size_min: 1
    vein_size_max: 3                # 每顆接受的礦脈精確為 1..3 格
    cell_chance: 0.02
```

存檔、重開伺服器。`/kyo stats` 看礦種數有沒有 +1,`/kyo preview 16` 站在目標高度看命中密度合不合預期。

## 驗證改動

```
/kyo sample volume 24      # 站在目標 Y,看 命中/掃描 比例
/kyo preview 16            # 看實際命中的座標與礦種
/kyo inspect <x> <y> <z>   # 單點:epoch、dirty、礦脈函數結果
```

這三個是暴力掃描,半徑夾在 1..24,**只有管理員能用**,不要寫進自動化腳本裡反覆跑。
