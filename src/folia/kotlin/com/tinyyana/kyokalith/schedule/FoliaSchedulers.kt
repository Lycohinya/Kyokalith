package com.tinyyana.kyokalith.schedule

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.concurrent.TimeUnit

object FoliaSchedulers {

    fun atRegion(plugin: Plugin, location: Location, task: Runnable) {
        Bukkit.getRegionScheduler().run(plugin, location) { task.run() }
    }

    fun atRegion(plugin: Plugin, world: World, cx: Int, cz: Int, task: Runnable) {
        Bukkit.getRegionScheduler().run(plugin, world, cx, cz) { task.run() }
    }

    fun atRegionNow(plugin: Plugin, location: Location, task: Runnable) {
        if (Bukkit.isOwnedByCurrentRegion(location)) task.run() else atRegion(plugin, location, task)
    }

    fun isOwnedByCurrentRegion(location: Location, squareRadiusChunks: Int): Boolean =
        Bukkit.isOwnedByCurrentRegion(location, squareRadiusChunks)

    fun atEntityNow(plugin: Plugin, entity: Entity, task: Runnable) {
        // 排程 fallback 沒給 retired callback:目標實體在執行前移除(如玩家下線)時任務靜默丟棄,
        // 發送者收不到回饋——已知限制,只影響 Folia 上跨 region 的 giveeligible
        if (Bukkit.isOwnedByCurrentRegion(entity)) task.run() else entity.scheduler.run(plugin, { task.run() }, null)
    }

    fun globalTimer(plugin: Plugin, delay: Long, period: Long, task: Runnable): () -> Unit {
        val handle = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { task.run() }, delay, period)
        return { handle.cancel() }
    }

    /** delay/period 進來是 tick(與 Schedulers 的其餘 API 一致),AsyncScheduler 只吃時間單位,這裡換算。 */
    fun asyncTimer(plugin: Plugin, delay: Long, period: Long, task: Runnable): () -> Unit {
        val handle = Bukkit.getAsyncScheduler().runAtFixedRate(
            plugin,
            { task.run() },
            delay * MILLIS_PER_TICK,
            period * MILLIS_PER_TICK,
            TimeUnit.MILLISECONDS,
        )
        return { handle.cancel() }
    }

    private const val MILLIS_PER_TICK = 50L
}
