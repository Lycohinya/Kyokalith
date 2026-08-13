package com.tinyyana.kyokalith.chunk

import com.tinyyana.kyokalith.db.KyokalithDatabase
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * flushAll 從「每個 chunk 一條連線 + 一次 commit」改成「整批一個 transaction」之後
 * (2026-08-13 效能優化,spark 實測原本約佔主執行緒 1%),要保住的行為:
 * 全部落地、佇列清空、失敗整批重排,以及 clearEpoch 之後不會被 in-flight 的批次寫回去。
 */
class DirtyPositionBatchFlushTest {

    private fun tempDir(): File = Files.createTempDirectory("kyokalith-batch").toFile()

    private fun chunk(i: Int) = EpochedChunk("world", i, i * 2, 0)

    @Test
    fun `a batch of chunks all land in one flush`() {
        val dir = tempDir()
        try {
            val dbFile = File(dir, "test.db")
            val store = DirtyPositionStore(KyokalithDatabase(dbFile).apply { init() })
            val chunks = (0 until 200).map(::chunk)
            chunks.forEachIndexed { i, c -> store.markDirty(c, LocalPos(i % 16, 60 + i, (i / 16) % 16)) }

            store.flushAll()

            val reloaded = DirtyPositionStore(KyokalithDatabase(dbFile))
            chunks.forEachIndexed { i, c ->
                assertTrue(
                    reloaded.isDirty(c, LocalPos(i % 16, 60 + i, (i / 16) % 16)),
                    "第 $i 個 chunk 的 dirty 位置沒有落地",
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `flushAll on an empty queue is a no-op`() {
        val dir = tempDir()
        try {
            val store = DirtyPositionStore(KyokalithDatabase(File(dir, "test.db")).apply { init() })
            store.flushAll()
            store.flushAll()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a second flush without new marks writes nothing but keeps the data`() {
        val dir = tempDir()
        try {
            val dbFile = File(dir, "test.db")
            val store = DirtyPositionStore(KyokalithDatabase(dbFile).apply { init() })
            val c = chunk(1)
            store.markDirty(c, LocalPos(1, 64, 1))
            store.flushAll()
            store.flushAll()

            assertTrue(DirtyPositionStore(KyokalithDatabase(dbFile)).isDirty(c, LocalPos(1, 64, 1)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `clearEpoch wins over an already flushed batch`() {
        val dir = tempDir()
        try {
            val dbFile = File(dir, "test.db")
            val store = DirtyPositionStore(KyokalithDatabase(dbFile).apply { init() })
            val c = chunk(3)
            store.markDirty(c, LocalPos(2, 70, 3))
            store.flushAll()

            store.clearEpoch(c)
            store.flushAll()

            val reloaded = DirtyPositionStore(KyokalithDatabase(dbFile))
            assertFalse(reloaded.isDirty(c, LocalPos(2, 70, 3)), "清掉的 epoch 不該被後續 flush 寫回來")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `positions marked while a flush is running are kept for the next cycle`() {
        val dir = tempDir()
        try {
            val dbFile = File(dir, "test.db")
            val store = DirtyPositionStore(KyokalithDatabase(dbFile).apply { init() })
            val c = chunk(7)
            store.markDirty(c, LocalPos(0, 65, 0))
            store.flushAll()
            store.markDirty(c, LocalPos(0, 66, 0))
            store.flushAll()

            val reloaded = DirtyPositionStore(KyokalithDatabase(dbFile))
            assertTrue(reloaded.isDirty(c, LocalPos(0, 65, 0)))
            assertTrue(reloaded.isDirty(c, LocalPos(0, 66, 0)))
            assertEquals(2, listOf(LocalPos(0, 65, 0), LocalPos(0, 66, 0)).count { reloaded.isDirty(c, it) })
        } finally {
            dir.deleteRecursively()
        }
    }
}
