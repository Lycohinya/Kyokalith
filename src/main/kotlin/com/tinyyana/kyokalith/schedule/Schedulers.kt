package com.tinyyana.kyokalith.schedule

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin

object Schedulers {

    val isFolia: Boolean = runCatching {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
    }.isSuccess

    /** 延後到下一個 tick,在擁有該座標的執行緒上跑;dirty 標記與 fallback 工作使用。 */
    fun atRegion(plugin: Plugin, location: Location, task: Runnable) {
        if (isFolia) FoliaSchedulers.atRegion(plugin, location, task) else Bukkit.getScheduler().runTask(plugin, task)
    }

    fun atRegion(plugin: Plugin, world: World, cx: Int, cz: Int, task: Runnable) {
        if (isFolia) FoliaSchedulers.atRegion(plugin, world, cx, cz, task) else Bukkit.getScheduler().runTask(plugin, task)
    }

    /**
     * 當前執行緒已擁有該座標就當場跑,否則才排到擁有者 region。指令路徑用這個:
     * RCON 的回應緩衝在指令 dispatch 結束就送出,延後一 tick 的回覆永遠到不了 client。
     * Spigot/Paper 的指令本來就在主執行緒,當場跑讓回覆(含 RCON)與 1.1.0 完全一致。
     */
    fun atRegionNow(plugin: Plugin, location: Location, task: Runnable) {
        when {
            isFolia -> FoliaSchedulers.atRegionNow(plugin, location, task)
            Bukkit.isPrimaryThread() -> task.run()
            else -> Bukkit.getScheduler().runTask(plugin, task)
        }
    }

    /**
     * Paper/Spigot 的主執行緒擁有整個世界；Folia 則必須明確確認目前 region 同時擁有
     * 目標 chunk 與周圍讀取半徑，不能因事件通常有 buffer 就假設一定安全。
     */
    fun isOwnedByCurrentRegion(location: Location, squareRadiusChunks: Int = 0): Boolean =
        !isFolia || FoliaSchedulers.isOwnedByCurrentRegion(location, squareRadiusChunks)

    /** [atRegionNow] 的 entity 版:資料擁有者是實體所在 region(如塞背包)。 */
    fun atEntityNow(plugin: Plugin, entity: Entity, task: Runnable) {
        when {
            isFolia -> FoliaSchedulers.atEntityNow(plugin, entity, task)
            Bukkit.isPrimaryThread() -> task.run()
            else -> Bukkit.getScheduler().runTask(plugin, task)
        }
    }

    fun globalTimer(plugin: Plugin, delay: Long, period: Long, task: Runnable): () -> Unit {
        val d = delay.coerceAtLeast(1L)
        val p = period.coerceAtLeast(1L)
        if (isFolia) return FoliaSchedulers.globalTimer(plugin, d, p, task)
        val id = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, task, d, p)
        return { Bukkit.getScheduler().cancelTask(id) }
    }

    /**
     * 純 I/O、不碰任何 Bukkit API 的週期工作用這個(目前只有 dirty positions 落地)。
     * 週期單位一律是 tick,與 [globalTimer] 一致;Folia 的 AsyncScheduler 吃時間單位,由橋接層換算。
     */
    fun asyncTimer(plugin: Plugin, delay: Long, period: Long, task: Runnable): () -> Unit {
        val d = delay.coerceAtLeast(1L)
        val p = period.coerceAtLeast(1L)
        if (isFolia) return FoliaSchedulers.asyncTimer(plugin, d, p, task)
        val handle = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, d, p)
        return { handle.cancel() }
    }
}
