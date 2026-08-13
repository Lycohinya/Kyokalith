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

    fun connect(): Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

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
