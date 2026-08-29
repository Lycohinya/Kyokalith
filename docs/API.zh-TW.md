# Kyokalith 開發者參考

[English](API.md)

Kyokalith 對外只有**一個**整合點:`OreCheckTriggerEvent`。沒有 `ServicesManager` 註冊、沒有 `-api` artifact、沒有介面層——刻意的。要接的東西只有一件,就用 Bukkit 原生的事件機制。

---

## `OreCheckTriggerEvent`

`com.tinyyana.kyokalith.event.OreCheckTriggerEvent` — 標準 Bukkit `Event`,實作 `Cancellable`,**同步觸發**。

### 什麼時候會發

玩家挖掉一顆礦,而且**全部條件成立**時:

- 玩家在**生存模式**(創造/旁觀/冒險不發)
- 玩家**沒有** `kyokalith.bypass`
- 這顆礦帶有 Kyokalith 的**資格令牌**(下面說明)
- **不是**絲綢之觸(絲綢之觸走另一條路:令牌搬到 ItemStack 上)
- 原版掉落物非空

爆炸、活塞、燒毀**不會**觸發——`TriggerSource` 目前只有 `PLAYER_BREAK` 一個值。爆炸只會把 DB 裡的 eligible 紀錄清掉。

### 欄位

```kotlin
class OreCheckTriggerEvent(
    val player: Player,
    val blockLocation: Location,
    val oreMaterial: Material,
    val oreType: String,                    // config.yml 裡的礦種 id,例如 "diamond"
    val tool: ItemStack,                    // clone,改它沒用
    val fortuneLevel: Int,
    val drops: MutableList<ItemStack>,      // ← 唯一可以改的東西
    val triggerSource: TriggerSource,       // PLAYER_BREAK
    val eligibilitySource: EligibilitySource, // NATURAL_BLOCK / PLACED_BLOCK / WORLDGEN_EXPOSED
) : Event(), Cancellable
```

### ⚠ 掉落改寫的語意(反直覺,先讀這段)

**「取消事件」不等於「不掉東西」,而是「保留原版掉落」。**

判定邏輯是這樣:

| 你做的事 | 結果 |
|---|---|
| `event.isCancelled = true` | **保留原版掉落**,Kyokalith 完全不插手 |
| 完全不動 `drops` | **保留原版掉落**(內容跟原本一樣 = 視為沒改) |
| 修改 `drops`(加/刪/換) | 原本掉出來的所有 item entity 被 `remove()`,然後把 `drops` 裡每個非空氣、`amount > 0` 的 stack 用 `dropItemNaturally` 重新丟在方塊位置 |

所以要「這次挖礦什麼都不掉」,是 `event.drops.clear()`,**不是** `event.isCancelled = true`。

### 範例

```kotlin
class OreCheckListener : Listener {
    @EventHandler
    fun onOreCheck(event: OreCheckTriggerEvent) {
        val total = (1..20).random() + bonusOf(event.player)
        when {
            total >= 20 -> {                       // 大成功:雙倍
                val bonus = event.drops.map { it.clone() }
                event.drops.addAll(bonus)
            }
            total >= 15 -> {                       // 成功:多一顆
                event.drops.firstOrNull()?.clone()?.let { event.drops.add(it) }
            }
            else -> return                         // 失敗:不動 = 原版掉落
        }
    }
}
```

`plugin.yml` 裡把 Kyokalith 放 `softdepend`,程式碼用**反射**取事件類別,就能做到「沒裝 Kyokalith 也能跑」——最初的使用端就是這樣接的,沒有編譯期相依。

---

## 資格令牌(Eligibility Token)

這是「哪顆礦能觸發檢定」的規則。**不是所有礦方塊都能。**

| 這顆礦怎麼來的 | 有令牌? |
|---|---|
| Kyokalith 首次曝光解析時產生的 | ✅ 有(`NATURAL_BLOCK`) |
| 玩家用絲綢之觸挖走、再放回去的 | ✅ 有(`PLACED_BLOCK`) |
| 其他任何目前站著的真礦、屬於已啟用礦種(例如世界生成時就露在洞穴壁上的) | ✅ 有(`WORLDGEN_EXPOSED`) |
| 管理員 `/give`、WorldEdit 貼的、商店買的,或任何玩家放置的方塊 | ❌ 沒有(放置任何方塊都會把該座標標成 dirty) |

`WORLDGEN_EXPOSED` 存在的理由:已經曝露的礦對 xray 本來就沒有情報價值,排除它只是讓獎勵類插件少了合法的檢定機會,擋不到任何作弊者。

**一顆礦只會燒掉一次檢定。** 生命週期:

```
首次曝光解析 → 方塊帶令牌
   ├─ 一般挖掘 ────→ 觸發 OreCheckTriggerEvent,令牌消耗
   └─ 絲綢之觸挖掘 → 令牌搬到 ItemStack 的 PDC 上,不觸發
         └─ 放回世界 → 令牌搬進 eligible_placed_ores 表
               └─ 再挖 → 觸發事件(eligibilitySource = PLACED_BLOCK),令牌消耗
```

所以帶令牌的礦可以交易、搬運、囤起來、再挖,但**檢定只會發生一次**。

**ItemStack 上的 PDC**(namespace `kyokalith`):`eligible` (BYTE) / `ore_type` (STRING) / `origin_world` (STRING) / `origin_epoch` (INTEGER)。ItemStack 上**不含** per-item `token_id`——舊版每次標記都塞一組新亂數 UUID,導致任兩個本應相同的礦物 meta 永遠不同,背包裡靜默無法堆疊(每個 Silk Touch 挖出的礦物都各自佔一疊、疊上限 1)。現在同來源礦物可正常堆疊;`token_id` 仍保留在 `eligible_placed_ores` 資料表當 debug 欄位,放置當下才重新產生。

**沒有免費重骰**:`BlockBreakEvent` 時會先暫存一筆 `PendingBreak` 並排一個下一 tick 的回收器。如果 `BlockDropItemEvent` 從來沒到(例如被別的插件把掉落擋掉了),令牌**照樣消耗**,並寫一行 `fine` 級的 log。

---

## 確定性礦脈函數 `f`

```
f(salt, world, epoch, oreType, cellX, cellY, cellZ) -> 命中/不命中
```

- **純函數**:splitmix64 雜湊,不讀世界狀態、不讀 DB。
- **8³ 的 cell**,每種礦在每個 cell 最多一個候選。較小 cell 恢復遭遇頻率;接受的候選仍會長成固定可重現、六面連通的 voxel 形狀,方塊數精確等於 config 的 `vein_size`(1–32)。
- **同礦種不串鏈**:相鄰 cell 若產生彼此接觸的同礦形狀,較高的穩定 `veinId` 整顆被抑制。
- **跨礦種整顆原子仲裁**:只在支援本次查詢 base material 的礦種之間比較;兩種礦的形狀只要有任何重疊,較低 priority 的候選整顆淘汰,同 priority 才由較低 `veinId` 勝出。存活 `veinId` 因此保留完整六面連通形狀與 config 指定的精確格數,不會被另一種礦切成 1–2 格殘片。
- **冪等**:同樣的輸入永遠同樣的輸出。這就是 `/kyo resolve` 可以安全重跑的原因——你懷疑某個座標漏掉了事件,直接對它重跑一次,結果一定跟「當初應該發生的」一致。
- **`salt` 讓真礦位置與世界種子脫鉤**:種子地圖網站算得出原版礦在哪,但算不出 Kyokalith 的礦在哪。

**`epoch`**:每個區塊一個計數器。區塊被重生(NatureRevive)時 `epoch += 1`,等於**只對那個區塊**重骰 `f`,不影響世界其他地方。

**Cell 快取**:有界 LRU 20,000 筆,key 含 `epoch`,所以區塊重生後舊項目自然老化掉,不需要失效掃描。快取掉了對正確性沒有影響(純函數重算就好)。

### 首次曝露時鎖定礦脈形狀(`materialized_positions`)

固定 salt/epoch/config 下 `f` 本身就是決定性的。第一次命中時會把接受礦脈的**完整 shape**(最多32格)一次持久化;之後這些座標真正首次曝露時直接讀鎖定結果,不再呼叫 `f`。沒有移動視窗,也就沒有靠持續挖掘推進鎖定的接力。

這個機制刻意維持有界、刻意不做任何事:

- **每次合成礦命中都是常數工作量**:最多 31 個額外座標,絕不掃整條礦脈或整個 chunk。就算相鄰候選材質相同,鎖定也不能跨到另一個 `veinId`。
- **絕不對未曝露的座標呼叫 setBlock。** 鎖定只是 SQLite(或記憶體快取)裡的一筆紀錄,寫的是「這個座標未來真正首次曝露時該套用什麼材質」——不是 `Block.setType`。一個「已鎖定但還沒曝露」的座標,不管是對客戶端還是對 xray 使用者,跟任何其他還埋著的誘餌沒有任何差別:世界資料裡的材質相同,傳給客戶端的封包位元組相同。只有觸發座標本身——這一個 tick 真正正在首次曝露決算的那一個——才會被呼叫 `setType`。
- **miss 一律不記錄。** 普通 miss 不寫入 `materialized_positions`,避免普通挖石頭增加同步 DB I/O。

### 裸露原生礦的延續

生成時已可見的天然礦沒有合成 `veinId`,但不能只是單格外殼。玩家第一次直接挖它時,Kyokalith 只把該格當作可信入口,由私有 salt 長出新的後方延續:

- 目標大小使用該礦設定的 `vein_size`;隱藏順序由 salt 排名,不沿用可由 world seed 預測的原版 component;
- 六面連通 growth 同時受 Chebyshev 半徑4、已載入/目前 region 擁有的方塊、以及 `vein_size_max` 限制;
- 入選格與直接相鄰的原生同礦 stop frontier 一次鎖定;任何 keep/stop 鎖定成員都不能再開下一脈;
- 最多寫入 `vein_size + 6 × vein_size` 筆(全域最大224),可跨已載入 chunk;1.5.0 起與同一事件產生的其他鎖定一起緩衝,在事件結尾用單一 SQLite transaction 一次寫入;
- 規劃階段絕不 `setType`;每格只在自己真正首次曝露時才套用 keep 或 stop 結果;
- transaction 失敗時全批 rollback、記錄例外，並在未上鎖誘餌曝露前取消本次可取消的破壞／爆炸／燃燒／實體改方塊／活塞事件。

---

## 資料表

`plugins/Kyokalith/kyokalith.db`,SQLite,`journal_mode=WAL`。沒有連線池:真的走到資料庫的操作會開一條新連線。開一條約 0.4ms,連線加一次查詢約 1.8–2.1ms,所以**熱路徑一律不得逐座標開連線**——一次爆炸在單一事件內最多決算 512 個方塊,1.5.0 之前逐方塊開連線的代價是 919ms 卡在該 region 的執行緒上。兩條規則把它擋在熱路徑外:

- `eligible_placed_ores` 整張表常駐記憶體(`onEnable` 載入一次,之後 write-through)。查詢與「沒有紀錄的座標」的移除完全不碰 SQLite。爆炸的 blockList 與每次玩家挖礦走的就是這條路徑。
- 同一事件產生的所有實體化鎖定會緩衝起來一次寫入(`MaterializationLockBuffer`),不是每命中一次礦脈就開一次交易。

另外兩條規則負責把**每次 commit 的成本**擋在熱路徑外,而且缺一不可:

- **全程保留一條連線**,`onEnable` 開、`onDisable` 關。它開完跑一句之後就不再下任何指令,存在的唯一目的是讓每個操作的 close 不會是「最後一條連線關閉」——那會讓 SQLite 同步把整個 WAL checkpoint 回主檔,就在那條執行緒上。注意 `DriverManager.getConnection` 是延遲的:一條從沒跑過語句的 keep-alive 連線根本還沒開檔,什麼都擋不住。
- **每條連線都設 `synchronous=NORMAL`**。WAL 模式下這會取消每次 commit 的 fsync。對「插件或伺服器崩潰」仍然是持久的(§9.4 在意的就是這件事),只有斷電或 OS 崩潰會丟掉最後幾秒。風險上限由這張表的性質決定——它是純函數的衍生決算快取,掉了也只是那些座標在自己下次首次曝露時重算出同樣的答案。唯一可能算出不同結果的是原生礦延續的形狀,而且不論如何都不會提前揭露任何埋藏方塊。

在 s01 實際資料庫的複本、實際磁碟上量 10 個 row 的 commit:原本 55–90ms、只有 keep-alive 23–25ms、只有 `synchronous=NORMAL` 65ms、兩者都有 **6.5–11.6ms**。成本跟 row 數幾乎無關——貴的是 checkpoint 與 fsync,不是 insert。

`KyokalithDatabase.connectionsOpened` 會計數所有開過的連線,並顯示在 `/kyo stats`;回歸測試釘住「512 顆方塊的爆炸清單、什麼都沒放置時,開啟連線數必須是 0」。

```sql
meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)
  -- salt(隨機 UUID,不能重置)、schema_version、created_at

chunk_epoch(world, cx, cz, epoch, PRIMARY KEY(world, cx, cz))

dirty_positions(world, cx, cz, epoch, data BLOB, PRIMARY KEY(world, cx, cz, epoch))
  -- data 不是 bitset,是 UTF-8 文字 "lx,y,lz;lx,y,lz;…"
  -- 刻意的:量沒大到需要壓縮編碼,可讀性優先

eligible_placed_ores(world, x, y, z, epoch, ore_type, ore_material,
                     token_id, placed_by, placed_at, PRIMARY KEY(world, x, y, z))

suspended_chunks(world, cx, cz, reason, created_at, PRIMARY KEY(world, cx, cz))

materialized_positions(world, cx, cz, epoch, lx, y, lz, ore_type, vein_id, material,
                       PRIMARY KEY(world, cx, cz, epoch, lx, y, lz))
  -- 作為已接受同一 veinId 命中與有界裸礦 keep/stop 計畫的衍生鎖定快取;普通 miss 不寫入。NatureRevive 重生時
  -- 逐 chunk 清除,做法與 dirty_positions 相同。
```

生成演算法另有 metadata 版本。v1.3.3 第一次啟動時,若 DB 不是 `vein_algorithm_version = 3`,會在同一交易中**只刪除** `materialized_positions`,再寫入版本3。這會在8³ cell與一次性裸礦延續生效前失效v2稀疏鎖定,但不改 `salt`、chunk epoch、dirty positions、eligible placed ores、suspended chunks 或其他 metadata。

---

## Config schema migration

`config_schema_version` 管 YAML 格式,跟 DB `schema_version`、`vein_algorithm_version` 都是獨立版本。

`KyokalithPlugin.mergeConfigDefaults()` 必須在 `copyDefaults(true)` 前,用 `config.contains("config_schema_version", true)` 讀檔案自己擁有的值;沒有就視為 v1。升級 v1 時,Bukkit defaults 已讓新版 `y_weight_points` 可見,但檔案仍保留舊 Y 範圍。Kyokalith 會把這些繼承路徑明確寫成空清單,再存為 schema 2;`OreRegistry` 因此讀不到曲線,安全退回舊版 `preferred_y` 三角分布。

這是相容性 migration,不是校準 migration。只有完整出貨的 v2 config 可以啟用內建分段曲線,因為它的 Y 範圍、曲線端點、density、遭遇率與精確礦脈大小是一整組量測結果。

---

## 首次曝露事件契約

曝露面依 Bukkit `Material.isOccluding` 判定:回傳 false 就代表不遮蔽。這刻意涵蓋鐵軌、玻璃、半磚等空氣/水/岩漿以外的方塊;旁邊的礦早已對玩家可見,之後移除覆蓋方塊時絕不能重新決算。

所有移除事件 listener 都會在 `MONITOR`、Bukkit 實際套用移除前捕捉 `RemovedBlockSnapshot(block, wasOccluding)`,並在同一 tick 決算。Folia 會先驗證目標加一個 chunk 讀取半徑都由目前 region 擁有;不安全的單方塊/活塞事件取消,foreign explosion entries 從事件清單移除並留在世界。事件前 snapshot 保證已可見礦不會被誤判成首次曝露。

爆炸另有固定硬上限。實體爆炸或方塊爆炸的 `blockList` 超過512筆時,會在 `HIGHEST` 以O(1)取消整個事件;接受的事件不裁切。`MONITOR` 先依座標排序、先鎖所有已可見天然礦入口,再於Bukkit產生掉落物前同時決算被炸掉的體積與新坑洞表面。埋藏誘餌因此不能靠TNT直接炸掉來跳過認證。eligible lifecycle消費同一份最終有界清單。

---

## 效能契約(改動前先讀)

**每個事件都有硬上限。** 一般移除看六個面;合成礦命中最多鎖32格;單個裸礦入口最多規劃32個keep加192個直接frontier。同一事件的所有鎖定批次共用4096筆寫入上限;超過就取消事件,且不套用任何尚待變更的方塊材質。普通miss仍然不寫入。爆炸超過512筆會在迭代前取消。工作在同一tick、擁有完整讀取半徑的執行緒上跑——Spigot/Paper是主執行緒,Folia是經驗證的owning region;foreign Folia entries留在世界,不建立後續工作。永遠不要改成async。

**不准做的事:**

- ❌ 掃描區塊(任何形式:生成時掃、`ChunkLoadEvent` 掃、排程掃、shell 掃)
- ❌ 在事件路徑上查 DB
- ❌ 遍歷全體玩家

1.0 之前的掃描式模型(v0.3)forceload 了 121 個區塊,把 TPS 壓到 **18.9**;刪掉整套掃描(含 23 個資料包 `configured_feature` JSON)之後是 **20.1**。這不是理論上的顧慮,是實測過的。

唯一保留的暴力掃描是 `/kyo preview` 和 `/kyo sample`——管理員專用、半徑夾在 1..24、跳過未載入的區塊。**這是刻意的例外,不是可以擴大的先例。**

---

## NatureRevive 橋接

反射載入 `engineer.skyouo.plugins.naturerevive.spigot.events.ChunkRegenEvent`,註冊在 `MONITOR`。區塊重生時:

```
暫停該區塊 → epoch += 1 → 丟掉舊 epoch 的 dirty 位置
           → 丟掉該區塊的 eligible 紀錄 → 解除暫停
```

不需要重新掃描:重生本身就把原版礦放回去了 = 一層全新的誘餌,`epoch + 1` 只對那個區塊重骰 `f`。

**橋接拋例外的話,區塊會被刻意留在暫停狀態**(fail-closed)——寧可那個區塊完全不材質化,也不要在不一致的狀態下運作。

---

## 已知的粗糙處

老實說在前面,免得你以為是自己看錯:

- `/kyo giveeligible` 產生的 ItemStack `originEpoch` 寫死 `0`。QA 工具夠用,但不要拿它當「正式發放令牌」的手段。
- `EligibleOrePdc.clear()` 有定義但沒人呼叫。
- 監聽器與 `KyoCommand` **沒有自動化測試**(沒有 MockBukkit / 伺服器測試框架)。有測試的是礦脈函數、註冊表、三個 store、首次曝光的判定邏輯,以及訊息表。
