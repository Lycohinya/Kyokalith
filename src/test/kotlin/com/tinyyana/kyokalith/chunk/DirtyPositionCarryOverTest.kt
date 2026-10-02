package com.tinyyana.kyokalith.chunk

import com.tinyyana.kyokalith.db.KyokalithDatabase
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Nohara 部分還原後 dirty 旗標要搬到新 epoch(沒被改寫的玩家方塊仍是玩家放的)。 */
class DirtyPositionCarryOverTest {

    private fun withStore(block: (KyokalithDatabase, DirtyPositionStore) -> Unit) {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            block(db, DirtyPositionStore(db))
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `carryOver persists the new epoch before the old one is cleared`() = withStore { db, store ->
        val old = EpochedChunk("world", 4, 5, 0)
        val fresh = EpochedChunk("world", 4, 5, 1)
        store.markDirty(old, LocalPos(1, 2, 3))
        store.markDirty(old, LocalPos(4, 5, 6))
        store.flush(old)

        assertEquals(2, store.carryOver(old, fresh))
        store.clearEpoch(old)

        assertTrue(store.isDirty(fresh, LocalPos(1, 2, 3)))
        assertFalse(store.isDirty(old, LocalPos(1, 2, 3)))
        // 重開後只靠資料庫也要讀得到新 epoch 的旗標,舊 epoch 的列已清掉
        val reloaded = DirtyPositionStore(db)
        assertTrue(reloaded.isDirty(fresh, LocalPos(4, 5, 6)))
        assertFalse(reloaded.isDirty(old, LocalPos(4, 5, 6)))
    }

    @Test
    fun `second pass picks up positions marked into the old set after the first pass`() = withStore { db, store ->
        val old = EpochedChunk("world", 0, 0, 3)
        val fresh = EpochedChunk("world", 0, 0, 4)
        store.markDirty(old, LocalPos(1, 1, 1))
        assertEquals(1, store.carryOver(old, fresh))
        store.markDirty(old, LocalPos(2, 2, 2)) // epoch 切換瞬間才標進舊集合
        assertEquals(1, store.carryOver(old, fresh))
        assertEquals(0, store.carryOver(old, fresh)) // 冪等
        store.clearEpoch(old)

        val reloaded = DirtyPositionStore(db)
        assertTrue(reloaded.isDirty(fresh, LocalPos(1, 1, 1)))
        assertTrue(reloaded.isDirty(fresh, LocalPos(2, 2, 2)))
    }

    @Test
    fun `empty source writes nothing`() = withStore { _, store ->
        assertEquals(0, store.carryOver(EpochedChunk("world", 9, 9, 0), EpochedChunk("world", 9, 9, 1)))
        assertEquals(0, store.count(EpochedChunk("world", 9, 9, 1)))
    }
}
