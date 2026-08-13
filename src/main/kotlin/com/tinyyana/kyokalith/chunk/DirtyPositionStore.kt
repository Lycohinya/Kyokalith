package com.tinyyana.kyokalith.chunk

import com.tinyyana.kyokalith.db.KyokalithDatabase
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Logger
import kotlin.concurrent.withLock

/** chunk 內局部座標,x/z 為 0..15。 */
data class LocalPos(val lx: Int, val y: Int, val lz: Int)

data class EpochedChunk(val world: String, val cx: Int, val cz: Int, val epoch: Int)

/**
 * dirty positions:被玩家或機制重新填入的天然座標,永不實體化礦物。
 *
 * ponytail: 編碼用分號分隔字串,不用 BitSet/Roaring bitmap;
 * 若日後 dirty 量體證明是效能瓶頸,再換更精簡的編碼。
 */
class DirtyPositionStore(
    private val db: KyokalithDatabase,
    private val logger: Logger = Logger.getLogger(DirtyPositionStore::class.java.name),
) {
    private val loaded = ConcurrentHashMap<EpochedChunk, MutableSet<LocalPos>>()
    private val pendingFlush = ConcurrentHashMap.newKeySet<EpochedChunk>()

    /** 寫入路徑互斥(flush / flushAll / clearEpoch);讀取路徑(markDirty / isDirty)不進這把鎖。 */
    private val writeLock = ReentrantLock()

    fun markDirty(chunk: EpochedChunk, pos: LocalPos) {
        loadIfAbsent(chunk).add(pos)
        pendingFlush.add(chunk)
    }

    fun isDirty(chunk: EpochedChunk, pos: LocalPos): Boolean = loadIfAbsent(chunk).contains(pos)

    /** chunk unload / plugin disable 時強制呼叫(§10.4)。 */
    fun flush(chunk: EpochedChunk) = writeLock.withLock {
        if (!pendingFlush.remove(chunk)) return
        val positions = loaded[chunk] ?: return
        try {
            persist(chunk, positions)
        } catch (e: Exception) {
            // 失敗必須放回佇列:dirty flag 沒落地會讓蓋挖漏洞重開(CONFIG.md 紅線),下輪重試
            pendingFlush.add(chunk)
            throw e
        }
    }

    /**
     * 整批寫在同一個 transaction 裡:原本逐 chunk 各開一條連線、各 commit 一次,一輪上百個
     * chunk 就是上百次開檔與 fsync(2026-08-13 spark 實測約佔主執行緒 1%)。這個方法現在跑在
     * 非同步執行緒上(見 KyokalithPlugin 的排程),所有共享狀態都是 concurrent 容器,
     * 寫入路徑再由 [writeLock] 互斥,onDisable 的最後一次 flush 會等待進行中的那輪跑完。
     */
    fun flushAll() = writeLock.withLock {
        // 先把整批從佇列取下來再寫:期間進來的 markDirty 會把 chunk 重新排回佇列,
        // 下一輪再寫一次(至少一次語意),不會因為「寫到一半又被標髒」而漏掉
        val claimed = pendingFlush.toList().filter { pendingFlush.remove(it) }
        if (claimed.isEmpty()) return
        val batch = claimed.mapNotNull { chunk -> loaded[chunk]?.let { chunk to encode(it) } }
        try {
            persistBatch(batch)
        } catch (e: Exception) {
            // 整批失敗(例如 SQLITE_BUSY)退回逐 chunk 重寫:一個 chunk 的問題不能讓其餘
            // chunk 這輪全部跳過,這是本方法從 1.x 起就有的保證,批次化不能把它弄丟
            logger.warning("dirty positions batch flush failed for ${batch.size} chunks, retrying one by one: $e")
            claimed.forEach { pendingFlush.add(it) }
            claimed.forEach { chunk ->
                try {
                    flush(chunk)
                } catch (single: Exception) {
                    logger.warning("dirty positions flush failed for $chunk, requeued for next cycle: $single")
                }
            }
        }
    }

    /**
     * NatureRevive 再生後,舊 epoch 的 dirty positions 可刪除(§10.3)。
     * 跟 flush 走同一把鎖:flushAll 改成非同步之後,「清掉舊 epoch」與「把同一個 chunk 寫回去」
     * 不再天然互斥(以前兩者都在主執行緒),沒有這把鎖會讓已刪除的 epoch 被 in-flight 的 flush 重新寫回。
     */
    fun clearEpoch(chunk: EpochedChunk) = writeLock.withLock {
        loaded.remove(chunk)
        pendingFlush.remove(chunk)
        db.connect().use { conn ->
            conn.prepareStatement(
                "DELETE FROM dirty_positions WHERE world = ? AND cx = ? AND cz = ? AND epoch = ?",
            ).use { stmt ->
                stmt.setString(1, chunk.world)
                stmt.setInt(2, chunk.cx)
                stmt.setInt(3, chunk.cz)
                stmt.setInt(4, chunk.epoch)
                stmt.executeUpdate()
            }
        }
    }

    private fun loadIfAbsent(chunk: EpochedChunk): MutableSet<LocalPos> =
        loaded.getOrPut(chunk) { ConcurrentHashMap.newKeySet<LocalPos>().apply { addAll(queryPositions(chunk)) } }

    private fun queryPositions(chunk: EpochedChunk): Set<LocalPos> =
        db.connect().use { conn ->
            conn.prepareStatement(
                "SELECT data FROM dirty_positions WHERE world = ? AND cx = ? AND cz = ? AND epoch = ?",
            ).use { stmt ->
                stmt.setString(1, chunk.world)
                stmt.setInt(2, chunk.cx)
                stmt.setInt(3, chunk.cz)
                stmt.setInt(4, chunk.epoch)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) emptySet() else decode(rs.getBytes("data"))
                }
            }
        }

    private fun persist(chunk: EpochedChunk, positions: Set<LocalPos>) {
        db.connect().use { conn ->
            conn.prepareStatement(UPSERT_SQL).use { stmt ->
                bind(stmt, chunk, encode(positions))
                stmt.executeUpdate()
            }
        }
    }

    private fun persistBatch(batch: List<Pair<EpochedChunk, ByteArray>>) {
        if (batch.isEmpty()) return
        db.inTransaction { conn ->
            conn.prepareStatement(UPSERT_SQL).use { stmt ->
                batch.forEach { (chunk, data) ->
                    bind(stmt, chunk, data)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    private fun bind(stmt: java.sql.PreparedStatement, chunk: EpochedChunk, data: ByteArray) {
        stmt.setString(1, chunk.world)
        stmt.setInt(2, chunk.cx)
        stmt.setInt(3, chunk.cz)
        stmt.setInt(4, chunk.epoch)
        stmt.setBytes(5, data)
    }

    companion object {
        private const val UPSERT_SQL =
            "INSERT OR REPLACE INTO dirty_positions(world, cx, cz, epoch, data) VALUES (?, ?, ?, ?, ?)"

        fun encode(positions: Set<LocalPos>): ByteArray =
            positions.joinToString(";") { "${it.lx},${it.y},${it.lz}" }.toByteArray(Charsets.UTF_8)

        fun decode(raw: ByteArray?): Set<LocalPos> {
            if (raw == null || raw.isEmpty()) return emptySet()
            return String(raw, Charsets.UTF_8).split(';').mapNotNull { entry ->
                val parts = entry.split(',')
                if (parts.size != 3) return@mapNotNull null
                runCatching { LocalPos(parts[0].toInt(), parts[1].toInt(), parts[2].toInt()) }.getOrNull()
            }.toSet()
        }
    }
}
