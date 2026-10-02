package com.tinyyana.kyokalith.eligibility

import com.tinyyana.kyokalith.db.KyokalithDatabase
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * eligible_placed_ores:玩家放置的 qualified 礦物座標(token 生命週期見 docs/API.md)。
 * 座標唯一(world, x, y, z)——同座標重複放置會覆蓋前一筆紀錄。
 *
 * 整張表在 [loadAll] 一次載進記憶體,之後讀取路徑([find]、[remove] 的存在性判定)完全不碰 DB,
 * 寫入則 write-through。這張表只裝「玩家親手放回世界的 eligible 礦」,正式服實測是個位數筆,
 * 全量常駐的記憶體成本可以忽略。
 *
 * 這樣做的理由是熱路徑成本,不是方便:舊版每次查詢都 `DriverManager.getConnection()` 開一條新
 * SQLite 連線,而 [com.tinyyana.kyokalith.mining.OreLifecycleListener] 的爆炸處理會對 blockList
 * **每一顆方塊**各叫一次 [remove]。2026-08-29 實測(本機 SSD、表內 4 筆):每顆方塊約 1.8–2.1ms,
 * 一次床爆炸(約80顆)= 172ms、打到 512 顆上限 = 919ms,全部同步發生在該 region 的執行緒上——
 * 這就是「用 TNT/床炸礦會讓該 region TPS 下滑」的主因。同一條路徑也在每次玩家挖礦時觸發
 * ([com.tinyyana.kyokalith.mining.OreEligibilityService.find]),違反 KYOKALITH_SPEC §15.1
 * 「熱路徑無 DB I/O」。
 */
class EligiblePlacedOreStore(private val db: KyokalithDatabase) {

    private data class PositionKey(val world: String, val x: Int, val y: Int, val z: Int)

    private val cache = ConcurrentHashMap<PositionKey, EligiblePlacedOre>()

    /** 啟動時呼叫一次;失敗必須讓插件停用,半載入的快取會讓 [find] 漏報既有 token。 */
    fun loadAll() {
        val rows = db.connect().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT * FROM eligible_placed_ores").use { rs ->
                    val list = ArrayList<EligiblePlacedOre>()
                    while (rs.next()) list += rs.toEligiblePlacedOre()
                    list
                }
            }
        }
        cache.clear()
        rows.forEach { cache[it.key()] = it }
    }

    fun insert(ore: EligiblePlacedOre) {
        db.connect().use { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO eligible_placed_ores
                    (world, x, y, z, epoch, ore_type, ore_material, token_id, placed_by, placed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, ore.world)
                stmt.setInt(2, ore.x)
                stmt.setInt(3, ore.y)
                stmt.setInt(4, ore.z)
                stmt.setInt(5, ore.epoch)
                stmt.setString(6, ore.oreType)
                stmt.setString(7, ore.oreMaterial)
                stmt.setString(8, ore.tokenId)
                stmt.setString(9, ore.placedBy?.toString())
                stmt.setLong(10, ore.placedAtMillis)
                stmt.executeUpdate()
            }
        }
        cache[ore.key()] = ore
    }

    fun find(world: String, x: Int, y: Int, z: Int): EligiblePlacedOre? = cache[PositionKey(world, x, y, z)]

    /**
     * 沒有紀錄就完全不碰 DB——爆炸的 blockList 幾乎每一顆都是這條路徑,這是本方法存在
     * 記憶體索引的主要理由。
     */
    fun remove(world: String, x: Int, y: Int, z: Int): EligiblePlacedOre? {
        val key = PositionKey(world, x, y, z)
        val existing = cache[key] ?: return null
        db.connect().use { conn ->
            conn.prepareStatement(
                "DELETE FROM eligible_placed_ores WHERE world = ? AND x = ? AND y = ? AND z = ?",
            ).use { stmt ->
                stmt.setString(1, world)
                stmt.setInt(2, x)
                stmt.setInt(3, y)
                stmt.setInt(4, z)
                stmt.executeUpdate()
            }
        }
        cache.remove(key)
        return existing
    }

    /** NatureRevive 再生 chunk 後,刪除該 chunk 內的 placed eligible ores(§13.2)。 */
    fun removeInChunk(world: String, cx: Int, cz: Int) {
        db.connect().use { conn ->
            conn.prepareStatement(
                "DELETE FROM eligible_placed_ores WHERE world = ? AND (x >> 4) = ? AND (z >> 4) = ?",
            ).use { stmt ->
                stmt.setString(1, world)
                stmt.setInt(2, cx)
                stmt.setInt(3, cz)
                stmt.executeUpdate()
            }
        }
        cache.keys.removeIf { it.world == world && (it.x shr 4) == cx && (it.z shr 4) == cz }
    }

    /** 記憶體快照:該 chunk 內所有 placed token(不碰 DB)。 */
    fun inChunk(world: String, cx: Int, cz: Int): List<EligiblePlacedOre> =
        cache.values.filter { it.world == world && (it.x shr 4) == cx && (it.z shr 4) == cz }

    /** 只從記憶體移除這一筆(同一個 token 才移);DB 刪除由 [deletePersisted] 在非 tick 執行緒補。 */
    fun forgetInMemory(ore: EligiblePlacedOre): Boolean = cache.remove(ore.key(), ore)

    /** 一個 transaction 刪掉這批 token 的 DB 列;只能在非 tick 執行緒呼叫。 */
    fun deletePersisted(ores: List<EligiblePlacedOre>) {
        if (ores.isEmpty()) return
        db.inTransaction { conn ->
            conn.prepareStatement(
                "DELETE FROM eligible_placed_ores WHERE world = ? AND x = ? AND y = ? AND z = ? AND token_id IS ?",
            ).use { stmt ->
                ores.forEach { ore ->
                    stmt.setString(1, ore.world)
                    stmt.setInt(2, ore.x)
                    stmt.setInt(3, ore.y)
                    stmt.setInt(4, ore.z)
                    stmt.setString(5, ore.tokenId)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    fun count(): Int = cache.size

    private fun EligiblePlacedOre.key() = PositionKey(world, x, y, z)

    private fun ResultSet.toEligiblePlacedOre() = EligiblePlacedOre(
        world = getString("world"),
        x = getInt("x"),
        y = getInt("y"),
        z = getInt("z"),
        epoch = getInt("epoch"),
        oreType = getString("ore_type"),
        oreMaterial = getString("ore_material"),
        tokenId = getString("token_id"),
        placedBy = getString("placed_by")?.let { UUID.fromString(it) },
        placedAtMillis = getLong("placed_at"),
    )
}
