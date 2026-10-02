package com.tinyyana.kyokalith.vein

import com.tinyyana.kyokalith.chunk.EpochedChunk
import com.tinyyana.kyokalith.chunk.LocalPos
import com.tinyyana.kyokalith.db.KyokalithDatabase
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 一個座標已鎖定的礦脈決算結果。`material` 是該座標未來真正曝露時應該 setType 成的材質。
 * 新演算法只批次鎖定同一個 veinId 的 hit；nullable 欄位仍保留，讓舊 schema 與既有讀取
 * API 相容。`oreType`/`veinId` 也提供 `/kyo inspect` 除錯使用。
 */
data class MaterializedVein(val oreType: String?, val veinId: String?, val material: String)
data class MaterializedPosition(val chunk: EpochedChunk, val pos: LocalPos)

/**
 * materialized_positions 的記憶體快取 + 持久化層。讀取路徑比照 [com.tinyyana.kyokalith.chunk.DirtyPositionStore]
 * 的既有慣例:整個 chunk 懶載入進記憶體一次,之後同 chunk 內的查詢全部走記憶體,不逐座標
 * 開新 DB connection——首次曝露決算的熱路徑因此只在「真的命中礦脈」時才有 DB 寫入,單純
 * miss 完全不觸碰這張表(見 MaterializationService 的取捨說明,對應 §15.1「熱路徑無 DB I/O」)。
 */
class MaterializedVeinStore(private val db: KyokalithDatabase) {
    private val loaded = ConcurrentHashMap<EpochedChunk, MutableMap<LocalPos, MaterializedVein>>()

    fun find(chunk: EpochedChunk, pos: LocalPos): MaterializedVein? = loadIfAbsent(chunk)[pos]

    /**
     * 批次 upsert,單一 SQL transaction:全部成功才提交並更新記憶體快取,任何一筆失敗就
     * rollback、回傳 false——呼叫端據此判斷要不要放棄這一批鄰域鎖定(見
     * MaterializationService.resolveAndLock)。`entries` 內所有座標必須屬於同一個 [chunk]
     * (呼叫端負責過濾跨 chunk 的鄰居,見 collectNeighborhood 的 sameChunk 檢查)。
     */
    fun upsertAll(chunk: EpochedChunk, entries: Map<LocalPos, MaterializedVein>): Boolean {
        return upsertAll(entries.mapKeys { (pos, _) -> MaterializedPosition(chunk, pos) })
    }

    /** 跨 chunk 的同一批鎖定仍共用一個 transaction，避免只寫入半顆礦脈。 */
    fun upsertAll(entries: Map<MaterializedPosition, MaterializedVein>): Boolean {
        if (entries.isEmpty()) return true
        val failure = runCatching {
            db.connect().use { conn ->
                conn.autoCommit = false
                try {
                    conn.prepareStatement(
                        """
                        INSERT OR REPLACE INTO materialized_positions
                            (world, cx, cz, epoch, lx, y, lz, ore_type, vein_id, material)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { stmt ->
                        entries.forEach { (position, vein) ->
                            stmt.setString(1, position.chunk.world)
                            stmt.setInt(2, position.chunk.cx)
                            stmt.setInt(3, position.chunk.cz)
                            stmt.setInt(4, position.chunk.epoch)
                            stmt.setInt(5, position.pos.lx)
                            stmt.setInt(6, position.pos.y)
                            stmt.setInt(7, position.pos.lz)
                            stmt.setString(8, vein.oreType)
                            stmt.setString(9, vein.veinId)
                            stmt.setString(10, vein.material)
                            stmt.addBatch()
                        }
                        stmt.executeBatch()
                    }
                    conn.commit()
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                }
            }
        }.exceptionOrNull()
        if (failure == null) {
            entries.entries.groupBy { it.key.chunk }.forEach { (chunk, chunkEntries) ->
                loadIfAbsent(chunk).putAll(chunkEntries.associate { it.key.pos to it.value })
            }
        } else {
            LOGGER.log(Level.SEVERE, "Failed to persist bounded materialization lock; transaction rolled back", failure)
        }
        return failure == null
    }

    /** NatureRevive 再生後,舊 epoch 的鎖定結果可清除,否則新一輪誘餌會被舊決定卡住(比照 DirtyPositionStore.clearEpoch)。 */
    fun clearEpoch(chunk: EpochedChunk) {
        loaded.remove(chunk)
        db.connect().use { conn ->
            conn.prepareStatement(
                "DELETE FROM materialized_positions WHERE world = ? AND cx = ? AND cz = ? AND epoch = ?",
            ).use { stmt ->
                stmt.setString(1, chunk.world)
                stmt.setInt(2, chunk.cx)
                stmt.setInt(3, chunk.cz)
                stmt.setInt(4, chunk.epoch)
                stmt.executeUpdate()
            }
        }
    }

    private fun loadIfAbsent(chunk: EpochedChunk): MutableMap<LocalPos, MaterializedVein> =
        loaded.getOrPut(chunk) { queryChunk(chunk).toMutableMap() }

    private fun queryChunk(chunk: EpochedChunk): Map<LocalPos, MaterializedVein> =
        db.connect().use { conn ->
            conn.prepareStatement(
                "SELECT lx, y, lz, ore_type, vein_id, material FROM materialized_positions WHERE world = ? AND cx = ? AND cz = ? AND epoch = ?",
            ).use { stmt ->
                stmt.setString(1, chunk.world)
                stmt.setInt(2, chunk.cx)
                stmt.setInt(3, chunk.cz)
                stmt.setInt(4, chunk.epoch)
                stmt.executeQuery().use { rs ->
                    val map = HashMap<LocalPos, MaterializedVein>()
                    while (rs.next()) {
                        val pos = LocalPos(rs.getInt("lx"), rs.getInt("y"), rs.getInt("lz"))
                        map[pos] = MaterializedVein(rs.getString("ore_type"), rs.getString("vein_id"), rs.getString("material"))
                    }
                    map
                }
            }
        }

    /** 診斷用:該(區塊, epoch)目前已鎖定的座標數。 */
    fun count(chunk: EpochedChunk): Int = loadIfAbsent(chunk).size

    fun count(): Int =
        db.connect().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) AS c FROM materialized_positions").use { rs ->
                    if (rs.next()) rs.getInt("c") else 0
                }
            }
        }

    companion object {
        private val LOGGER: Logger = Logger.getLogger(MaterializedVeinStore::class.java.name)
    }
}
