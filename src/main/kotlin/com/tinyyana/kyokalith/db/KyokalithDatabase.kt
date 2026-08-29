package com.tinyyana.kyokalith.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * Schema 見 docs/API.md「資料表」。WAL 是硬需求:dirty positions 是安全關鍵資料,不能因程序中斷整批遺失。
 */
class KyokalithDatabase(private val file: File) {

    /**
     * 已開啟過的連線總數。開一條 SQLite 連線在本機 SSD 上約 0.4ms,加上一次查詢是 1.8–2.1ms;
     * 熱路徑(爆炸 blockList、每次挖礦)只要有「每個方塊一條連線」的寫法就會直接吃掉整個
     * tick——2026-08-29 的爆炸 TPS 事故就是這樣來的。這個計數器讓那件事可以被測試釘住
     * (見 EligiblePlacedOreStoreTest),也給 `/kyo status` 一個可觀測的數字。
     */
    @Volatile
    var connectionsOpened: Long = 0L
        private set

    fun connect(): Connection {
        connectionsOpened++
        val conn = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        // WAL + NORMAL:commit 不再 fsync WAL。落地順序與 all-or-nothing 都不變,
        // 對「插件/伺服器崩潰」仍然是持久的(資料已經交給 OS),只有斷電/OS 崩潰會丟掉
        // 最後幾秒的 commit。這張表存的是純函數 f 的衍生決算快取,真掉了也只是那些座標
        // 在自己下次首次曝露時重算一次同樣的答案;拿這個換掉每次 commit 一次 HDD fsync
        // 是划算的(實測 24ms -> 8ms,見下方 keepAlive 的量測表)。
        conn.createStatement().use { it.execute("PRAGMA synchronous=NORMAL") }
        return conn
    }

    /**
     * 全程握著、永遠不下指令的一條連線。存在的唯一理由是**擋掉 WAL checkpoint**。
     *
     * 這個插件的每個操作都是 `connect().use { ... }`,所以每次 close 幾乎都是「最後一條連線關閉」——
     * SQLite 在那個時候會把整個 WAL checkpoint 回主檔並截斷,而且是同步在關閉的那條執行緒上做。
     * 2026-08-29 在 s01 的實際資料庫複本、實際磁碟(D: 是 SATA HDD,不是 C: 的 NVMe)上量
     * 10 個 row 的 commit:
     *
     * | 組合 | commit |
     * |---|---|
     * | 現況 | 55–90ms |
     * | keep-alive 但沒在該連線上跑語句 | 55–87ms(無效,連線是延遲開啟的) |
     * | keep-alive + 跑一句 | 23–25ms |
     * | 只有 synchronous=NORMAL | 65ms(無效,每次 close 仍 checkpoint) |
     * | keep-alive + 跑一句 + synchronous=NORMAL | **6.5–11.6ms** |
     *
     * 成本跟 row 數幾乎無關(4 筆與 10 筆同價),純粹是 checkpoint 與 fsync。這是爆炸卡頓
     * 在修掉逐方塊連線與逐命中交易之後剩下的最後一塊。
     * **開發機與正式機的磁碟不同,這裡的絕對數字不可直接套用到正式服。**
     *
     * 只要有任何一條連線活著,close 就不會觸發那次 checkpoint;WAL 改由 SQLite 自己的
     * autocheckpoint(預設 1000 頁)在背景分攤。`synchronous` 維持預設的 FULL,不用降級
     * 換效能——量出來 NORMAL 對這個症狀沒有幫助,問題從來不是 fsync。
     *
     * 刻意不藏在 [init] 裡:它會佔住檔案 handle,呼叫端必須知道自己要負責 [close]。
     * (第一版藏在 init 裡,結果所有用暫存檔的測試都刪不掉檔案——那正是「隱性資源」的代價。)
     */
    private var keepAlive: Connection? = null

    /**
     * [close] 是它的配對;正式啟用路徑在 KyokalithPlugin.onEnable/onDisable。
     *
     * **一定要在這條連線上跑一句 SQL。** `DriverManager.getConnection` 是延遲的——沒下過任何
     * 語句之前它根本還沒開啟資料庫檔案,也就擋不住 checkpoint。第一版漏了這一步,線上量到的
     * commit 時間完全沒變(55–87ms),看起來像「keep-alive 沒用」,其實是連線根本沒生效。
     */
    fun openKeepAliveConnection() {
        if (keepAlive != null) return
        val conn = connect()
        conn.createStatement().use { it.executeQuery("SELECT 1").close() }
        keepAlive = conn
    }

    /**
     * 整批共用一條連線與一個 transaction。逐筆各開一條連線時,每筆都要付「開檔 + prepare +
     * commit 的 fsync」;dirty positions 一輪 flush 常常上百個 chunk,那是這個插件在伺服器
     * 執行緒上最貴的一段(2026-08-13 spark:`DirtyPositionStore.persist` 約佔主執行緒 1%)。
     * 失敗一律 rollback 後往外丟,由呼叫端決定重試策略。
     */
    fun <T> inTransaction(block: (Connection) -> T): T =
        connect().use { conn ->
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (error: Exception) {
                runCatching { conn.rollback() }
                throw error
            }
        }

    fun init() {
        connect().use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("PRAGMA journal_mode=WAL")
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS meta (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS chunk_epoch (
                        world TEXT NOT NULL,
                        cx INTEGER NOT NULL,
                        cz INTEGER NOT NULL,
                        epoch INTEGER NOT NULL,
                        PRIMARY KEY(world, cx, cz)
                    )
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS dirty_positions (
                        world TEXT NOT NULL,
                        cx INTEGER NOT NULL,
                        cz INTEGER NOT NULL,
                        epoch INTEGER NOT NULL,
                        data BLOB NOT NULL,
                        PRIMARY KEY(world, cx, cz, epoch)
                    )
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS eligible_placed_ores (
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        epoch INTEGER NOT NULL,
                        ore_type TEXT NOT NULL,
                        ore_material TEXT NOT NULL,
                        token_id TEXT,
                        placed_by TEXT,
                        placed_at INTEGER NOT NULL,
                        PRIMARY KEY(world, x, y, z)
                    )
                    """.trimIndent(),
                )
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS suspended_chunks (
                        world TEXT NOT NULL,
                        cx INTEGER NOT NULL,
                        cz INTEGER NOT NULL,
                        reason TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        PRIMARY KEY(world, cx, cz)
                    )
                    """.trimIndent(),
                )
                // 2026-07-24 新增(純加法,對舊資料庫安全):首次命中礦脈時鎖定的持久化決算結果,
                // 見 MaterializedVeinStore / MaterializationService。只有真正命中礦脈(含其
                // 完整有界形狀成員)或有界原生礦延續鎖才寫入這張表;純 miss 不寫,見
                // MaterializationService 的取捨說明——世界方塊狀態本身已是永久記錄,miss 補寫
                // 一筆不會多一層保障,卻會讓每次挖空石都變成一次 SQLite 寫入。
                st.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS materialized_positions (
                        world TEXT NOT NULL,
                        cx INTEGER NOT NULL,
                        cz INTEGER NOT NULL,
                        epoch INTEGER NOT NULL,
                        lx INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        lz INTEGER NOT NULL,
                        ore_type TEXT,
                        vein_id TEXT,
                        material TEXT NOT NULL,
                        PRIMARY KEY(world, cx, cz, epoch, lx, y, lz)
                    )
                    """.trimIndent(),
                )
            }
        }
        ensureSalt()
        migrateVeinAlgorithm()
    }

    /** PlugMan 熱插拔與 onDisable 必須呼叫:留著連線會讓 jar 換掉之後 SQLite 檔案仍被佔住。 */
    fun close() {
        runCatching { keepAlive?.close() }
        keepAlive = null
    }

    fun getMeta(key: String): String? =
        connect().use { conn ->
            conn.prepareStatement("SELECT value FROM meta WHERE key = ?").use { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("value") else null }
            }
        }

    fun setMeta(key: String, value: String) {
        connect().use { conn ->
            conn.prepareStatement("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)").use { stmt ->
                stmt.setString(1, key)
                stmt.setString(2, value)
                stmt.executeUpdate()
            }
        }
    }

    /** salt 首次啟動時生成;正式服不允許熱重置(§14.1),此類危險指令留給後續階段。 */
    private fun ensureSalt() {
        if (getMeta("salt") != null) return
        setMeta("salt", UUID.randomUUID().toString())
        setMeta("schema_version", "1")
        setMeta("created_at", Instant.now().toString())
    }

    /**
     * materialized_positions 只保存尚未曝露座標的衍生決算快取。幾何演算法換版時只失效這張表;
     * salt、epoch、dirty、eligible、suspended 與已經寫進世界的方塊都不動。
     */
    private fun migrateVeinAlgorithm() {
        connect().use { conn ->
            conn.autoCommit = false
            try {
                val current = conn.prepareStatement("SELECT value FROM meta WHERE key = ?").use { stmt ->
                    stmt.setString(1, VEIN_ALGORITHM_META_KEY)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("value") else null }
                }
                if (current != VEIN_ALGORITHM_VERSION) {
                    conn.createStatement().use { it.executeUpdate("DELETE FROM materialized_positions") }
                    conn.prepareStatement("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)").use { stmt ->
                        stmt.setString(1, VEIN_ALGORITHM_META_KEY)
                        stmt.setString(2, VEIN_ALGORITHM_VERSION)
                        stmt.executeUpdate()
                    }
                }
                conn.commit()
            } catch (error: Exception) {
                conn.rollback()
                throw error
            }
        }
    }

    companion object {
        const val VEIN_ALGORITHM_META_KEY = "vein_algorithm_version"
        const val VEIN_ALGORITHM_VERSION = "3"
    }
}
