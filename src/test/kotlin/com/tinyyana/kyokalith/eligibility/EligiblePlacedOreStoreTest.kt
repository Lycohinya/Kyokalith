package com.tinyyana.kyokalith.eligibility

import com.tinyyana.kyokalith.db.KyokalithDatabase
import java.util.UUID
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EligiblePlacedOreStoreTest {

    private fun sample(x: Int = 10, y: Int = 20, z: Int = 30) = EligiblePlacedOre(
        world = "world",
        x = x,
        y = y,
        z = z,
        epoch = 0,
        oreType = "diamond",
        oreMaterial = "DIAMOND_ORE",
        tokenId = UUID.randomUUID().toString(),
        placedBy = UUID.randomUUID(),
        placedAtMillis = 1_000L,
    )

    @Test
    fun `insert then find round-trips`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = EligiblePlacedOreStore(db)
            val ore = sample()

            store.insert(ore)
            val found = store.find(ore.world, ore.x, ore.y, ore.z)

            assertEquals(ore, found)
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `remove deletes and returns the removed row`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = EligiblePlacedOreStore(db)
            val ore = sample()
            store.insert(ore)

            val removed = store.remove(ore.world, ore.x, ore.y, ore.z)

            assertEquals(ore, removed)
            assertNull(store.find(ore.world, ore.x, ore.y, ore.z))
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `removeInChunk only clears the matching chunk`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = EligiblePlacedOreStore(db)
            val inChunk = sample(x = 16, y = 20, z = 16) // chunk (1,1)
            val outsideChunk = sample(x = 100, y = 20, z = 100) // chunk (6,6)
            store.insert(inChunk)
            store.insert(outsideChunk)

            store.removeInChunk("world", 1, 1)

            assertNull(store.find(inChunk.world, inChunk.x, inChunk.y, inChunk.z))
            assertEquals(outsideChunk, store.find(outsideChunk.world, outsideChunk.x, outsideChunk.y, outsideChunk.z))
        } finally {
            file.deleteIfExists()
        }
    }

    /**
     * 回歸測試(2026-08-29 爆炸 TPS 事故):OreLifecycleListener 會對爆炸 blockList 的每一顆
     * 方塊各叫一次 remove()。舊版每次都開一條新 SQLite 連線 + SELECT,實測 512 顆 = 919ms
     * 同步卡在該 region 的執行緒上。沒有紀錄的座標一律不得碰 DB。
     */
    @Test
    fun `an explosion sized block list touches the database zero times when nothing is placed`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = EligiblePlacedOreStore(db)
            store.loadAll()
            val baseline = db.connectionsOpened

            repeat(512) { i ->
                store.remove("world", i, 64, i)
                store.find("world", i, 64, i)
            }

            assertEquals(baseline, db.connectionsOpened, "爆炸熱路徑不得逐方塊開 DB 連線")
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `loadAll restores rows written by a previous run`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val ore = sample()
            EligiblePlacedOreStore(db).also { it.loadAll() }.insert(ore)

            val reopened = EligiblePlacedOreStore(db)
            reopened.loadAll()

            assertEquals(ore, reopened.find(ore.world, ore.x, ore.y, ore.z))
            assertEquals(1, reopened.count())
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `removeInChunk drops the in-memory rows of that chunk only`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = EligiblePlacedOreStore(db)
            store.loadAll()
            val inside = sample(x = 5, z = 5)
            val outside = sample(x = 100, z = 100)
            store.insert(inside)
            store.insert(outside)

            store.removeInChunk("world", 0, 0)

            assertNull(store.find(inside.world, inside.x, inside.y, inside.z))
            assertEquals(outside, store.find(outside.world, outside.x, outside.y, outside.z))
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `inChunk forgetInMemory and deletePersisted drop only the chosen tokens`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = EligiblePlacedOreStore(db)
            val a = sample(x = 18, z = 40) // chunk (1, 2)
            val b = sample(x = 20, z = 41) // chunk (1, 2)
            val other = sample(x = 100, z = 100)
            listOf(a, b, other).forEach(store::insert)

            assertEquals(setOf(a, b), store.inChunk("world", 1, 2).toSet())
            assertEquals(true, store.forgetInMemory(a))
            store.deletePersisted(listOf(a))

            assertNull(store.find("world", a.x, a.y, a.z))
            val reloaded = EligiblePlacedOreStore(db).apply { loadAll() }
            assertNull(reloaded.find("world", a.x, a.y, a.z))
            assertEquals(b, reloaded.find("world", b.x, b.y, b.z))
            assertEquals(other, reloaded.find("world", other.x, other.y, other.z))
        } finally {
            file.deleteIfExists()
        }
    }
}
