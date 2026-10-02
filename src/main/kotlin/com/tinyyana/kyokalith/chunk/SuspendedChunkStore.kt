package com.tinyyana.kyokalith.chunk

import com.tinyyana.kyokalith.db.KyokalithDatabase
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * suspended chunk:dirty flush 失敗等異常時暫停該 chunk 的實體化——dirty 旗標可能沒落地,
 * 繼續決算就可能動到玩家蓋過/看過的方塊,寧可少換礦也不冒誤決算風險。
 * Phase 1 只提供儲存層;實際在 dirty flush 失敗時呼叫 suspend() 屬於後續事件整合階段。
 */
class SuspendedChunkStore(private val db: KyokalithDatabase) {
    private val cache = ConcurrentHashMap<ChunkCoord, Boolean>()

    fun suspend(coord: ChunkCoord, reason: String) {
        db.connect().use { conn ->
            conn.prepareStatement(
                "INSERT OR REPLACE INTO suspended_chunks(world, cx, cz, reason, created_at) VALUES (?, ?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, coord.world)
                stmt.setInt(2, coord.cx)
                stmt.setInt(3, coord.cz)
                stmt.setString(4, reason)
                stmt.setLong(5, Instant.now().toEpochMilli())
                stmt.executeUpdate()
            }
        }
        cache[coord] = true
    }

    /**
     * 只改記憶體、不碰 SQLite:給「在 region 執行緒上發現出錯、需要立刻擋住該 chunk」的路徑用,
     * 持久化由呼叫端稍後在非 tick 執行緒補 [suspend]。重開服後這個旗標不會保留。
     */
    fun suspendInMemory(coord: ChunkCoord) {
        cache[coord] = true
    }

    fun resume(coord: ChunkCoord) {
        db.connect().use { conn ->
            conn.prepareStatement(
                "DELETE FROM suspended_chunks WHERE world = ? AND cx = ? AND cz = ?",
            ).use { stmt ->
                stmt.setString(1, coord.world)
                stmt.setInt(2, coord.cx)
                stmt.setInt(3, coord.cz)
                stmt.executeUpdate()
            }
        }
        cache[coord] = false
    }

    fun isSuspended(coord: ChunkCoord): Boolean = cache.getOrPut(coord) { querySuspended(coord) }

    private fun querySuspended(coord: ChunkCoord): Boolean =
        db.connect().use { conn ->
            conn.prepareStatement(
                "SELECT 1 FROM suspended_chunks WHERE world = ? AND cx = ? AND cz = ?",
            ).use { stmt ->
                stmt.setString(1, coord.world)
                stmt.setInt(2, coord.cx)
                stmt.setInt(3, coord.cz)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }

    fun count(): Int =
        db.connect().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) AS c FROM suspended_chunks").use { rs ->
                    if (rs.next()) rs.getInt("c") else 0
                }
            }
        }
}
