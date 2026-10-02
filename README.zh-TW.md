# Kyokalith

[English](README.md)

**不動世界生成的反 X-Ray 礦層。** 原版怎麼生就怎麼生,不混淆封包、不清礦重生、不掃區塊——被實心方塊完全包住的礦,一律當成**誘餌**:X-Ray / freecam / 種子地圖看得到,但那顆方塊的真假**要等它第一次被挖開露出來的瞬間才決定**。

作弊者對著透視裡直接看到的礦一路挖過去,挖到的是它的基底方塊;老實玩家在洞穴壁上看到的礦,永遠是真的。

> 這是為 Lycohinya 伺服器打造、並已在其上長期實戰運行的插件,但反 X-Ray 核心是通用的——任何 Spigot/Paper 26.2 伺服器單獨裝 Kyokalith 都能用。第二個功能(挖礦檢定令牌 `OreCheckTriggerEvent`)是給其他插件接的整合點,沒人接就只是靜靜地不發事件。

## 它到底怎麼運作

傳統反 X-Ray 走兩條路,兩條都有代價:封包混淆(騙不過 freecam、吃 CPU)、或是清空礦再自己重生(要掃區塊,TPS 會死)。Kyokalith 走第三條:

1. **世界生成完全不動。** 原版的礦還在原地。生成時就已經露在外面的礦(洞穴壁、峽谷面)是**真的**,永遠不會被改。
2. **完全被包住的礦是誘餌。** 它存在於世界檔裡,X-Ray 看得到,但跟「這裡有沒有礦」無關。
3. **唯一的觸發點是「方塊消失」。** 玩家挖掉、爆炸、燒掉、活塞推走——Kyokalith 先 snapshot 被移除方塊在事件生效前是否遮蔽,再於同一 tick 檢查它的**六個面鄰居**。Folia 會先證明目前 region 擁有目標與一個 chunk 的讀取半徑;不安全的單方塊/活塞事件取消,foreign explosion entries 則留在世界。
4. **只處理「這次才第一次露出來」的鄰居。** 可見性依 Bukkit `Material.isOccluding` 判定,不再只認空氣、水、岩漿。鐵軌、玻璃、半磚等非遮蔽方塊旁的礦本來就看得到;之後拆掉這些方塊,絕不能讓礦重骰或消失。
5. **決定真假。** 用確定性函數 `f(salt, world, epoch, x, y, z, 基底方塊, 維度)`:命中 → 誘餌變成真礦(或普通基底方塊**變成**礦);沒命中 → 誘餌變回基底方塊(石頭/深板岩/地獄岩)。每次命中都屬於一個可重現、六面連通的 voxel 形狀,方塊數會精確等於設定的 `vein_size`。因為方塊還沒送到客戶端,老實玩家眼中什麼都沒發生。
6. **裸露的原生礦是安全入口,不是只有一格的外殼。** 玩家挖掉、或 TNT 直接炸掉已可見天然礦時,Kyokalith 會一次鎖定一條由私有 salt 決定、大小等於 `vein_size` 的後方礦脈。隱藏路線不沿用原版 component,所以種子地圖仍算不出來;完整結果與終點 frontier 只寫一次,鎖定成員不能再開下一脈。

TNT 會在掉落物產生前同時決算「被炸掉的體積」與「新形成的坑洞表面」。埋藏誘餌不能再靠 TNT 跳過私有 salt 判定,爆炸體積內真正命中的礦仍照原版掉落。每次事件都有硬上限:一般移除只看六個面;單顆 shape 最多鎖 32 格;單個裸礦延續最多寫 `7 × vein_size` 筆(全域上限 224);同一事件所有寫入共用 4,096 筆上限。影響超過 512 格的爆炸會以 O(1) 直接取消。**沒有掃描、沒有排程掃描任務、沒有 `ChunkLoadEvent` 工作。**

> 目前的誘餌模型取代了 1.0 之前的掃描式做法(v0.3):當時用「資料包清礦 + 掃描區塊重生」,121 個 forceload 區塊就把 TPS 壓到 18.9;把掃描整套刪掉之後回到 20.1。**任何「順便掃一下區塊」的想法在這個插件裡都是紅線。**

相鄰 cell 不會合成沒有終點的同礦種礦帶:兩個同礦種候選形狀若六面相連,只保留穩定 `veinId` 較小的那顆。跨礦種重疊也採整顆原子仲裁:較低 `priority` 的候選整顆淘汰,同 priority 才由較低 `veinId` 勝出。每顆存活礦脈因此維持六面連通與 config 指定的精確格數,不會留下 1–2 格殘片。

### 蓋起來的礦不會被重新決算

玩家放下的方塊(以及雪/冰生成、實體放置、活塞推到的目的地)會被標記為 **dirty**。dirty 的方塊永遠不會被材質化,而且**挖掉 dirty 方塊也不會去解析它的鄰居**。

理由:一個玩家放的方塊底下如果藏著東西,那東西在被蓋住之前一定已經露出來過了。這條規則同時擋掉「把誘餌蓋起來、再挖開來跳過解析」的漏洞,也保證「把真礦蓋起來、晚點再挖開,它還在」。活塞把最後一層遮蔽拉走也算「移除」,不能拿活塞偷看未決定的誘餌。

## 需求

| | |
|---|---|
| 伺服器 | **Spigot 或 Paper 26.2,或 Folia 26.1.2**(Folia 最新就是 26.1.2、沒有 26.2,因此 `api-version` 降為 26.1;以 Spigot API 編譯;正式環境跑 Paper) |
| Java | **25** |
| 硬相依 | 無 |
| 軟相依 | NatureRevive(有裝才會接區塊重生事件)、Nohara(有裝才會接野外還原事件),皆反射載入 |

Kotlin stdlib 與 SQLite 驅動由 Bukkit library loader 在啟動時下載,**不 shade 進 jar**。

## 安裝

1. 把 `Kyokalith-<版本>.jar` 丟進 `plugins/`,啟動。
2. 預設 `config.yml` 會生成,11 種礦(含地獄礦)全部啟用,直接可用。
3. 第一次啟動會在 `plugins/Kyokalith/kyokalith.db` 產生一組隨機 `salt`。

> ⚠ **`salt` 產生後絕對不要刪 DB 或重置它。** salt 是「真礦位置與世界種子無關」的來源;重置等於把全世界所有還沒挖開的礦脈重骰一次。

## 升級

設定升級會辨識 schema。一般新 key 仍會合併且不覆蓋舊值,但 v1 config **不會**直接繼承 v2 的 `y_weight_points`:Bukkit 一般的 `copyDefaults` 會把新版曲線跟舊 `y_min`/`y_max` 拼成不合法的混合定義。v1 第一次啟動時,Kyokalith 會寫入 `config_schema_version: 2`,只清空這些繼承來的曲線、寫 warning,並安全沿用舊版 `preferred_y` 三角分布。要啟用本版重新校準的內建曲線,請安裝 release/patch 提供的完整 v2 `config.yml`。隨伺服器出貨的設定使用 `locale: zh_TW`;想改英文請設 `locale: en`。

1.3.3 導入礦脈演算法版本 `3`。第一次啟動時只會失效衍生的 `materialized_positions` 鎖定快取,再記錄 `vein_algorithm_version=3`,讓 8³ cell 與一次性延續鎖不受 v2 稀疏結果影響;**不會**重設 salt、epoch、dirty、eligible、suspended,也不會改動已經寫進世界的方塊。

1.3.4 維持演算法版本 `3`,不清除任何資料表;只修正爆炸當下的認證與事件預算。

## 指令

`/kyokalith`(別名 `/kyo`)。**所有子指令都需要 `kyokalith.admin`。**

| 子指令 | 參數 | 做什麼 | 誰能用 |
|---|---|---|---|
| `stats` | – | 礦種數、eligible 方塊數、暫停區塊數、NatureRevive 與 Nohara 橋接狀態 | 主控台可 |
| `inspect` | `<x> <y> <z> [world]` | 傾印該座標的 epoch、方塊、dirty/暫停旗標、礦脈函數結果 | 主控台可 |
| `chunk` | `<cx> <cz> [world]` | 該區塊的狀態:epoch、暫停旗標、dirty / 鎖定 / placed token 數、最近一次 Nohara 還原(同時寫 log `KYO_CHUNK ...`);只讀 store | 主控台可 |
| `preview` | `[半徑]` 或 `<半徑> <x> <y> <z> [world]` | 暴力掃一個立方體,回報命中數與最多 12 個範例座標 | 短式限玩家 |
| `sample` | `volume [半徑]` | 同上,只回報 `命中 / 掃描` | 限玩家 |
| `resolve` | `<x> <y> <z> [world]` | 對該座標重跑一次首次曝光解析(`f` 是確定性的,重跑安全) | 主控台可 |
| `suspend` | `<cx> <cz> <理由...>` | 暫停該區塊的材質化 | 限玩家 |
| `resume` | `<cx> <cz>` | 解除暫停 | 限玩家 |
| `markeligible` | `[x y z]` | QA 工具:把方塊標 dirty 並寫入一筆 eligible 令牌 | 限玩家 |
| `giveeligible` | `<玩家> <礦種> <1-64>` | QA 工具:發一疊帶 PDC 令牌的礦方塊 | 主控台可 |
| `notify` | `<on\|off>` | 切換「玩家挖到真礦時廣播給有 `kyokalith.admin` 的在線玩家」,寫回 `config.yml` 立即生效 | 主控台可 |

`preview` / `sample` 的半徑夾在 `1..24`——這兩個是**刻意保留的暴力掃描例外**,管理員專用,不在熱路徑上。

## 權限

| 節點 | 預設 | 效果 |
|---|---|---|
| `kyokalith.admin` | `op` | 所有 `/kyo` 子指令 |
| `kyokalith.bypass` | `false` | 持有者挖礦不消耗檢定令牌、不觸發 `OreCheckTriggerEvent`。**注意:誘餌解析照常跑**,這個權限只跳過檢定路徑 |

非生存模式(創造/旁觀/冒險)也不會消耗令牌。

## 設定

`config.yml` 有 `locale`、`config_schema_version`、`database`(檔名、dirty 寫回間隔)與 `ores`(資料驅動的礦種定義,加一種礦不用改程式)。`vein_size_min/max` 是精確目標方塊數(`1..32`),不是半徑或球體體積,也同時限制裸露天然礦的 salt 延續大小。可選的 `y_weight_points` 定義分段線性高度曲線;遷移後的舊 config 會繼續使用 `preferred_y` 三角分布,直到安裝完整 v2 config。

最常被動到的是礦的 `cell_chance` / `density` / `preferred_y`——**這三個直接等於伺服器經濟的水龍頭**。完整欄位說明、計算公式與紅線在 **[docs/CONFIG.zh-TW.md](docs/CONFIG.zh-TW.md)**。

沒有 `/kyo reload`,設定只在 `onEnable` 讀一次——**唯一例外是 `notify_admins_on_ore_find`**,它在每次挖礦事件時即時讀取,`/kyo notify` 切換後不用重啟或重載。

### 訊息 / 語系

管理指令輸出可完全自訂。內建語系:`en`、`zh_TW`。在 `config.yml` 設 `locale`,然後改 `plugins/Kyokalith/lang/` 底下的檔案——刪掉的 key 會回退內建文字。要加自己的語言,把 `lang/en.yml` 複製成 `lang/<名稱>.yml` 翻譯後,設 `locale: <名稱>`。

## 給開發者

Kyokalith 對外只有**一個整合點**:`OreCheckTriggerEvent`——玩家在生存模式挖掉一顆「Kyokalith 自己產生/追蹤過」的礦時同步觸發,可取消,`drops` 可改寫。

管理員給的礦、WorldEdit 貼的礦、商店買的礦**沒有令牌**,不會觸發。絲綢之觸會把令牌搬到 ItemStack 上(PDC),重新放下會搬進 DB,所以一顆礦可以交易、搬運、再挖,但**只會燒掉一次檢定**。

```kotlin
@EventHandler
fun onOreCheck(event: OreCheckTriggerEvent) {
    if ((1..20).random() < 15) return          // 檢定失敗:不動 drops = 保留原版掉落
    val bonus = event.drops.firstOrNull()?.clone() ?: return
    event.drops.add(bonus)                     // 檢定成功:多掉一份
}
```

介面契約、欄位、掉落改寫的精確語意、以及「取消 ≠ 不掉東西」這個反直覺點,見 **[docs/API.zh-TW.md](docs/API.zh-TW.md)**。

## 建置

```bash
./gradlew build      # 編譯 + 單元測試 + 出 jar
./gradlew test       # 只跑單元測試(礦脈函數、註冊表、各 store、訊息表)
./gradlew runServer  # 本機 Paper 26.2 測試伺服器
```

`plugin.yml` 的 `libraries:` 裡的 Kotlin 版本**必須跟 `gradle/libs.versions.toml` 一致**,不然編譯用的 stdlib 跟執行期載入的不是同一份。

## 資料

單一 SQLite 檔 `plugins/Kyokalith/kyokalith.db`(WAL)。存 `salt`、礦脈演算法版本、每區塊的 `epoch`、dirty 位置、已放置的 eligible 礦、暫停中的區塊,以及衍生的 `materialized_positions` 鎖定快取。詳細 schema 與升級行為見 [docs/API.zh-TW.md](docs/API.zh-TW.md#資料表)。

## 授權

[TinyYana Universal Software License (TYUSL) 1.0](LICENSE)——可自由使用、修改、整合、散布(含商業伺服器);但未經書面同意,**不得**把插件本身拿去販售或重新包裝成付費產品/服務。

TinyYana · [tinyyana.com](https://tinyyana.com)
