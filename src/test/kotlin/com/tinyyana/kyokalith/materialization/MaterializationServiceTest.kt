package com.tinyyana.kyokalith.materialization

import com.tinyyana.kyokalith.chunk.EpochedChunk
import com.tinyyana.kyokalith.chunk.LocalPos
import com.tinyyana.kyokalith.db.KyokalithDatabase
import com.tinyyana.kyokalith.ore.OreRegistry
import com.tinyyana.kyokalith.vein.MaterializedPosition
import com.tinyyana.kyokalith.vein.MaterializedVein
import com.tinyyana.kyokalith.vein.MaterializedVeinStore
import com.tinyyana.kyokalith.vein.OreVeinResolver
import com.tinyyana.kyokalith.vein.VeinPosition
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaterializationServiceTest {

    @Test
    fun `native ore base material is inferred conservatively`() {
        assertEquals(Material.STONE, MaterializationService.nativeOreBase(Material.DIAMOND_ORE))
        assertEquals(Material.DEEPSLATE, MaterializationService.nativeOreBase(Material.DEEPSLATE_DIAMOND_ORE))
        assertEquals(Material.NETHERRACK, MaterializationService.nativeOreBase(Material.NETHER_QUARTZ_ORE))
        assertEquals(Material.NETHERRACK, MaterializationService.nativeOreBase(Material.ANCIENT_DEBRIS))
        assertNull(MaterializationService.nativeOreBase(Material.AMETHYST_BLOCK))
    }

    private val solidNeighbor = NeighborExposure(inRemovedKeys = false, liveTransparent = false)

    /**
     * 回歸守門:把決算搬到事件當下(同一 tick、移除還沒真的套用到世界資料)執行時,
     * 唯一透明的鄰居 liveTransparent 會是 false(世界資料還沒變成空氣),但它屬於本次
     * removedKeys——如果這裡誤判成「不是首次曝露」,誘餌就永遠不會決算,對應「魚骨挖礦
     * 有時候會看到礦物從面前消失」的回報:玩家會先看到用戶端快取畫出的誘餌原貌,
     * 決算又遲遲不追上來把它修正掉。
     */
    @Test
    fun `neighbor pending removal counts as newly exposed even before world state updates`() {
        val neighbors = listOf(
            NeighborExposure(inRemovedKeys = true, liveTransparent = false),
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
        )
        assertTrue(MaterializationService.isNewlyExposed(neighbors))
    }

    /** 移除已生效(next-tick fallback 路徑)時,判定結果必須與「移除前」呼叫一致。 */
    @Test
    fun `neighbor pending removal counts as newly exposed after world state updates too`() {
        val neighbors = listOf(
            NeighborExposure(inRemovedKeys = true, liveTransparent = true),
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
        )
        assertTrue(MaterializationService.isNewlyExposed(neighbors))
    }

    @Test
    fun `breaking a rail above an already visible ore cannot re-resolve it`() {
        val neighbors = listOf(
            NeighborExposure(
                inRemovedKeys = true,
                liveTransparent = true,
                removedWasNonOccluding = true,
            ),
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
        )
        assertFalse(
            MaterializationService.isNewlyExposed(neighbors),
            "鐵軌原本就不遮蔽視線，挖掉它不能把下方已可見礦誤判為首次曝露",
        )
    }

    /**
     * 已經有一個透明面不屬於本次事件(世界生成就曝露,或先前事件已經決算過):
     * 一律當作「事件前就看得到」,不能再被改動,即使本次事件也移除了另一個鄰居。
     */
    @Test
    fun `pre-existing exposure outside this event blocks re-resolution`() {
        val neighbors = listOf(
            NeighborExposure(inRemovedKeys = true, liveTransparent = true),
            NeighborExposure(inRemovedKeys = false, liveTransparent = true),
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
            solidNeighbor,
        )
        assertFalse(MaterializationService.isNewlyExposed(neighbors))
    }

    @Test
    fun `no transparent neighbor at all is not newly exposed`() {
        val neighbors = List(6) { solidNeighbor }
        assertFalse(MaterializationService.isNewlyExposed(neighbors))
    }

    @Test
    fun `visible vanilla ore keeps a bounded continuation and seals its frontier`() {
        val origin = VeinPosition(0, 0, 0)
        val iron = WorldgenContinuationNode("iron", "IRON_ORE", "STONE", exposed = false)
        val plan = MaterializationService.planWorldgenContinuation(origin, 32, lookup = { iron })

        assertEquals(32, plan.vein.size)
        assertTrue(plan.boundary.isNotEmpty(), "超過 vein_size_max 的原版礦必須有明確 stop frontier")
        assertTrue(plan.vein.keys.intersect(plan.boundary.keys).isEmpty())
        assertTrue(plan.vein.size + plan.boundary.size <= 32 * 7, "一次事件最多 32 keep + 192 frontier rows")
        plan.boundary.keys.forEach { boundary ->
            assertTrue(
                plan.vein.keys.any { vein ->
                    kotlin.math.abs(vein.x - boundary.x) +
                        kotlin.math.abs(vein.y - boundary.y) +
                        kotlin.math.abs(vein.z - boundary.z) == 1
                },
                "stop frontier 只能是 keep 礦脈的直接鄰居，不能繼續 BFS 接力",
            )
        }
    }

    @Test
    fun `legacy miss would erase ore behind a visible ore while continuation keeps it`() {
        val config = YamlConfiguration()
        config.set("ores.iron.enabled", true)
        config.set("ores.iron.materials.stone", "IRON_ORE")
        config.set("ores.iron.y_min", -64)
        config.set("ores.iron.y_max", 320)
        config.set("ores.iron.preferred_y", 16)
        config.set("ores.iron.density", 1.0)
        config.set("ores.iron.vein_size_min", 3)
        config.set("ores.iron.vein_size_max", 7)
        config.set("ores.iron.cell_chance", 0.0)
        val resolver = OreVeinResolver(
            "visible-worldgen-regression",
            OreRegistry.load(config.getConfigurationSection("ores")).getOrThrow(),
        )
        val visible = VeinPosition(0, 16, 0)
        val hidden = VeinPosition(1, 16, 0)
        val iron = WorldgenContinuationNode("iron", "IRON_ORE", "STONE", exposed = false)

        assertNull(
            resolver.resolve("world", 0, hidden.x, hidden.y, hidden.z, "STONE"),
            "1.3.2 路徑在 resolver miss 時會把原生隱藏鐵礦改成 STONE",
        )
        val plan = MaterializationService.planWorldgenContinuation(
            visible,
            7,
            mapOf(visible to iron.copy(exposed = true), hidden to iron)::get,
        )
        assertEquals("IRON_ORE", plan.vein.getValue(hidden).material)
    }

    @Test
    fun `worldgen continuation follows ore type across stone boundary but not another ore`() {
        val origin = VeinPosition(-1, 0, 0)
        val nodes = mapOf(
            origin to WorldgenContinuationNode("iron", "IRON_ORE", "STONE", exposed = true),
            VeinPosition(0, 0, 0) to WorldgenContinuationNode("iron", "DEEPSLATE_IRON_ORE", "DEEPSLATE", exposed = false),
            VeinPosition(1, 0, 0) to WorldgenContinuationNode("gold", "GOLD_ORE", "STONE", exposed = true),
        )

        val plan = MaterializationService.planWorldgenContinuation(origin, 9, nodes::get)

        assertEquals(setOf(origin, VeinPosition(0, 0, 0)), plan.vein.keys)
        assertTrue(plan.boundary.isEmpty())
    }

    @Test
    fun `worldgen continuation spatial radius is a hard endpoint`() {
        val origin = VeinPosition(-2, 0, 0)
        val iron = WorldgenContinuationNode("iron", "IRON_ORE", "STONE", exposed = false)
        val chain = (-20..20).associate { VeinPosition(it, 0, 0) to iron }

        val plan = MaterializationService.planWorldgenContinuation(origin, 32, chain::get)

        assertEquals(9, plan.vein.size, "半徑 4 內只可能保留九格直線")
        assertEquals(setOf(VeinPosition(-7, 0, 0), VeinPosition(3, 0, 0)), plan.boundary.keys)
    }

    @Test
    fun `one explosion cannot create unbounded exposure work`() {
        assertFalse(MaterializationListener.shouldCancelExplosion(512))
        assertTrue(MaterializationListener.shouldCancelExplosion(513))
        assertTrue(MaterializationListener.shouldCancelExplosion(10_000))
    }

    @Test
    fun `foreign Folia region explosion entries stay outside exposure work`() {
        val blocks = (0 until 10).toMutableList()

        assertEquals(5, MaterializationListener.retainOwnedBlocks(blocks) { it % 2 == 0 })
        assertEquals(listOf(0, 2, 4, 6, 8), blocks)
    }

    @Test
    fun `accepted explosion ownership checks stay within the fixed cap`() {
        val blocks = (0 until MaterializationListener.MAX_EXPLOSION_BLOCKS_PER_EVENT).toMutableList()
        var ownershipChecks = 0

        val foreign = MaterializationListener.retainOwnedBlocks(blocks) {
            ownershipChecks++
            it % 2 == 0
        }

        assertEquals(256, foreign)
        assertEquals(MaterializationListener.MAX_EXPLOSION_BLOCKS_PER_EVENT, ownershipChecks)
        assertEquals(256, blocks.size)
    }

    private fun lockRows(from: Int, count: Int): Map<MaterializedPosition, MaterializedVein> =
        (from until from + count).associate { i ->
            MaterializedPosition(EpochedChunk("world", 0, 0, 0), LocalPos(i % 16, i, (i / 16) % 16)) to
                MaterializedVein("iron", "v", "IRON_ORE")
        }

    @Test
    fun `one event cannot buffer more than the fixed materialization row budget`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val locks = MaterializationLockBuffer(
                MaterializationService.MAX_MATERIALIZED_ROWS_PER_EVENT,
                MaterializedVeinStore(db),
            )

            assertTrue(locks.add(lockRows(0, 4_000)))
            assertFalse(locks.add(lockRows(4_000, 97)))
            assertTrue(locks.add(lockRows(4_000, 96)))
            assertFalse(locks.add(lockRows(5_000, 1)))
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `re-adding an already buffered position does not consume more budget`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val locks = MaterializationLockBuffer(10, MaterializedVeinStore(db))

            assertTrue(locks.add(lockRows(0, 10)))
            assertTrue(locks.add(lockRows(0, 10)), "同一批座標重複鎖定不該再吃額度")
            assertFalse(locks.add(lockRows(10, 1)))
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `buffered locks are visible to later lookups in the same event before they are flushed`() {
        val file = createTempFile(suffix = ".db")
        try {
            val db = KyokalithDatabase(file.toFile())
            db.init()
            val store = MaterializedVeinStore(db)
            val locks = MaterializationLockBuffer(64, store)
            val chunk = EpochedChunk("world", 0, 0, 0)
            val pos = LocalPos(1, 2, 3)

            assertNull(locks.find(chunk, pos))
            assertTrue(locks.add(mapOf(MaterializedPosition(chunk, pos) to MaterializedVein("iron", "v", "IRON_ORE"))))

            assertEquals("IRON_ORE", locks.find(chunk, pos)?.material, "同一事件內稍早鎖定的座標必須被後續查詢看到")
            assertNull(store.find(chunk, pos), "flush 之前不該落地")

            assertTrue(locks.flush())
            assertEquals("IRON_ORE", store.find(chunk, pos)?.material)
        } finally {
            file.deleteIfExists()
        }
    }

    @Test
    fun `complete resolver shape is locked once without a moving window`() {
        val config = YamlConfiguration()
        config.set("ores.test.enabled", true)
        config.set("ores.test.materials.stone", "IRON_ORE")
        config.set("ores.test.y_min", -64)
        config.set("ores.test.y_max", 64)
        config.set("ores.test.preferred_y", 0)
        config.set("ores.test.density", 1.0)
        config.set("ores.test.vein_size_min", 32)
        config.set("ores.test.vein_size_max", 32)
        config.set("ores.test.cell_chance", 1.0)
        val resolver = OreVeinResolver(
            "lock-relay-regression",
            OreRegistry.load(config.getConfigurationSection("ores")).getOrThrow(),
        )
        var resolved = run {
            for (x in 0 until 32) for (y in -16 until 16) for (z in 0 until 32) {
                resolver.resolveDetailed("world", 0, x, y, z, "STONE")?.let { return@run it }
            }
            error("找不到測試礦脈")
        }

        val locked = resolved.shape.positions
        assertEquals(32, locked.size, "首次命中可一次鎖定完整 shape，不需要玩家繼續挖掘來接力")
        val outside = VeinPosition(
            resolved.shape.positions.maxOf { it.x } + 1,
            resolved.shape.positions.first().y,
            resolved.shape.positions.first().z,
        )
        assertFalse(outside in locked)
    }
}
