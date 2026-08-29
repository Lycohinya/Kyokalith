package com.tinyyana.kyokalith.materialization

import com.tinyyana.kyokalith.KyokalithPlugin
import com.tinyyana.kyokalith.schedule.Schedulers
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent

/**
 * 把「天然方塊消失」的事件收斂到 MaterializationService.resolveRemovedSnapshots,
 * 爆炸則走 resolveExplosionSnapshots，在掉落前連同 blockList 內方塊一起認證；
 * 「方塊被放入/生成」的事件收斂到 markDirty。
 *
 * 決算優先在事件當下、同一 tick 內(用 [Schedulers.atRegionNow])執行:目前執行緒已經
 * 擁有目標座標(Folia 上的 region owner、或 Spigot/Paper 的主執行緒——事件觸發時一律成立)
 * 就直接跑。Folia handler 先驗證 target + 1 chunk 的讀取半徑都由目前 region 擁有;
 * 不安全的單方塊/活塞事件取消,foreign explosion entries 留在世界。這條路徑之所以安全,是因為
 * MaterializationService.isNewlyExposed 只靠事件本身的 removedKeys 判定「即將透明」,
 * 不需要世界資料已經反映移除;拖到下一 tick 才決算,會讓誘餌真面目先被畫給客戶端看一次
 * (世界生成的埋藏誘餌本來就在 chunk 封包裡,移除擋住它的方塊後,用戶端會馬上依快取資料
 * 算面剔除、直接顯示誘餌原貌),下一 tick 才修正成真礦或石頭時就會被玩家看到「礦物消失/
 * 變成別的礦」的閃爍——這正是「魚骨挖礦看到礦物從面前消失」回報的根因。
 *
 * 所有會觸發 resolveRemoved 的 handler 都掛在 [org.bukkit.event.EventPriority.MONITOR]:
 * 同一 tick 執行代表我們是在事件的移除**生效前**讀取 removedKeys,所以必須確定沒有其他
 * 插件會在我們之後才取消事件——MONITOR 保證輪到我們時 isCancelled() 已是最終結果,
 * `ignoreCancelled = true` 才能可靠地擋掉「插件事後取消,但我們已經誤決算鄰居」的情況。
 */
class MaterializationListener(
    private val plugin: KyokalithPlugin,
    private val materialization: MaterializationService,
) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun guardBlockBreakRegion(event: BlockBreakEvent) {
        if (!ownsExposureNeighborhood(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val snapshot = RemovedBlockSnapshot(event.block, event.block.type.isOccluding)
        runOwnedRegionNow(listOf(event.block)) {
            if (!materialization.resolvePlayerBreak(snapshot)) {
                event.isCancelled = true
                plugin.logger.severe("Cancelled block break because the bounded materialization lock could not be persisted")
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        // 所有玩家放置都標 dirty:放置的位置永不再進決算(f 是確定性的,防的不是重骰,
        // 是「已被玩家看過/蓋過的方塊不該再被改動」),也讓「挖開自己放的方塊」不觸發鄰居決算,
        // 這是蓋住可見礦再挖開時礦不消失的保護的一半(另一半在 resolveRemoved 的 dirty 閘)
        materialization.markDirty(event.blockPlaced)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockForm(event: BlockFormEvent) {
        if (event.newState.type in MaterializationService.BASE_BLOCKS) {
            markDirtyLater(event.block)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (event.to.isAir) {
            resolveNow(listOf(event.block)) {
                event.isCancelled = true
                logPersistenceFailure("entity block change")
            }
        } else if (event.to in MaterializationService.BASE_BLOCKS) {
            markDirtyLater(event.block)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun guardEntityChangeRegion(event: EntityChangeBlockEvent) {
        if (event.to.isAir && !ownsExposureNeighborhood(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        resolveExplosionNow(event.blockList().toList()) {
            event.isCancelled = true
            logPersistenceFailure("entity explosion")
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun capEntityExplosion(event: EntityExplodeEvent) {
        if (shouldCancelExplosion(event.blockList().size)) {
            event.isCancelled = true
            plugin.logger.warning(
                "Cancelled explosion with ${event.blockList().size} blocks: " +
                    "the fixed exposure cap is $MAX_EXPLOSION_BLOCKS_PER_EVENT",
            )
            return
        }
        filterExplosion(event.blockList())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        resolveExplosionNow(event.blockList().toList()) {
            event.isCancelled = true
            logPersistenceFailure("block explosion")
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun capBlockExplosion(event: BlockExplodeEvent) {
        if (shouldCancelExplosion(event.blockList().size)) {
            event.isCancelled = true
            plugin.logger.warning(
                "Cancelled explosion with ${event.blockList().size} blocks: " +
                    "the fixed exposure cap is $MAX_EXPLOSION_BLOCKS_PER_EVENT",
            )
            return
        }
        filterExplosion(event.blockList())
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        resolveNow(listOf(event.block)) {
            event.isCancelled = true
            logPersistenceFailure("block burn")
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun guardBlockBurnRegion(event: BlockBurnEvent) {
        if (!ownsExposureNeighborhood(event.block)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun guardPistonExtendRegion(event: BlockPistonExtendEvent) {
        if (!ownsPistonNeighborhood(event.blocks, event.direction)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        onPistonMove(event.blocks, event.direction) {
            event.isCancelled = true
            logPersistenceFailure("piston extend")
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun guardPistonRetractRegion(event: BlockPistonRetractEvent) {
        if (!ownsPistonNeighborhood(event.blocks, event.direction)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        onPistonMove(event.blocks, event.direction) {
            event.isCancelled = true
            logPersistenceFailure("piston retract")
        }
    }

    /**
     * 活塞搬移:舊座標視為消失觸發決算(擋住「用活塞抽走覆蓋方塊、不觸發決算就看到誘餌」),
     * 新座標視為機制放置標 dirty。被其他搬移方塊回填的舊座標不透明,決算自然不會誤判。
     */
    private fun onPistonMove(moved: List<Block>, direction: BlockFace, onFailure: () -> Unit) {
        if (moved.isEmpty()) return
        val destinations = moved.map { it.getRelative(direction) }
        val snapshots = moved.map { RemovedBlockSnapshot(it, it.type.isOccluding) }
        runOwnedRegionNow(moved) {
            if (!materialization.resolveRemovedSnapshots(snapshots)) {
                onFailure()
                return@runOwnedRegionNow
            }
            destinations.forEach { materialization.markDirty(it) }
        }
    }

    private fun resolveNow(removed: List<Block>, onFailure: () -> Unit) {
        if (removed.isEmpty()) return
        val snapshots = removed.map { RemovedBlockSnapshot(it, it.type.isOccluding) }
        runOwnedRegionNow(removed) {
            if (!materialization.resolveRemovedSnapshots(snapshots)) onFailure()
        }
    }

    private fun resolveExplosionNow(removed: List<Block>, onFailure: () -> Unit) {
        if (removed.isEmpty()) return
        val snapshots = removed.map { RemovedBlockSnapshot(it, it.type.isOccluding) }
        runOwnedRegionNow(removed) {
            // 爆炸是這個插件唯一一次在單一事件裡決算數百個座標的路徑,也是 2026-08-29 TPS 事故的
            // 現場。決算全程同步佔住 region 執行緒,所以超過門檻就留下可查的證據——沒有這行,
            // 「炸礦會卡」只能靠玩家體感回報,查起來要從頭重搭一次量測。
            val startedNanos = System.nanoTime()
            val ok = materialization.resolveExplosionSnapshots(snapshots)
            val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000.0
            if (elapsedMs > SLOW_EXPLOSION_WARN_MS) {
                plugin.logger.warning(
                    "Explosion resolution took %.1fms for %d blocks at %s %d %d %d".format(
                        elapsedMs,
                        snapshots.size,
                        removed.first().world.name,
                        removed.first().x,
                        removed.first().y,
                        removed.first().z,
                    ),
                )
            }
            if (!ok) onFailure()
        }
    }

    private fun logPersistenceFailure(eventName: String) {
        plugin.logger.severe("Cancelled $eventName because the bounded materialization lock could not be persisted")
    }

    /** 這裡沒有「即將透明」的 removedKeys 可以信任(只是標 dirty),維持原本的下一 tick 語意即可。 */
    private fun markDirtyLater(block: Block) {
        Schedulers.atRegion(plugin, block.location) { materialization.markDirty(block) }
    }

    private fun filterExplosion(blocks: MutableList<Block>) {
        val crossRegion = retainOwnedBlocks(blocks, ::ownsExposureNeighborhood)
        if (crossRegion > 0) {
            plugin.logger.warning(
                "Explosion block list was reduced to ${blocks.size}: " +
                    "$crossRegion cross-region entries stayed in the world",
            )
        }
    }

    private fun ownsPistonNeighborhood(blocks: List<Block>, direction: BlockFace): Boolean =
        blocks.all {
            ownsExposureNeighborhood(it) &&
                Schedulers.isOwnedByCurrentRegion(
                    it.location.add(direction.modX.toDouble(), direction.modY.toDouble(), direction.modZ.toDouble()),
                    EXPOSURE_READ_RADIUS_CHUNKS,
                )
        }

    /** 六面曝露、8³完整 shape 與半徑4+一層 frontier 都落在 target 相鄰一個 chunk 內。 */
    private fun ownsExposureNeighborhood(block: Block): Boolean =
        Schedulers.isOwnedByCurrentRegion(block.location, EXPOSURE_READ_RADIUS_CHUNKS)

    /** HIGHEST ownership guard 已驗證整批讀取半徑;這裡在同一 tick 的 owning region 執行。 */
    private fun runOwnedRegionNow(blocks: List<Block>, task: () -> Unit) {
        Schedulers.atRegionNow(plugin, blocks.first().location) { task() }
    }

    companion object {
        const val MAX_EXPLOSION_BLOCKS_PER_EVENT = 512

        /** 一次爆炸決算超過這個時間就留 log:一個 tick 是 50ms,超過就是玩家看得到的卡頓。 */
        const val SLOW_EXPLOSION_WARN_MS = 25.0
        private const val EXPOSURE_READ_RADIUS_CHUNKS = 1

        fun shouldCancelExplosion(size: Int): Boolean = size > MAX_EXPLOSION_BLOCKS_PER_EVENT

        /** Keeps foreign Folia-region entries in the world instead of reading them from the wrong thread. */
        fun <T> retainOwnedBlocks(blocks: MutableList<T>, owns: (T) -> Boolean): Int {
            val before = blocks.size
            blocks.removeAll { !owns(it) }
            return before - blocks.size
        }

    }
}
