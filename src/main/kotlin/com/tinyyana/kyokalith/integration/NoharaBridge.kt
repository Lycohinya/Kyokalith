package com.tinyyana.kyokalith.integration

import com.tinyyana.kyokalith.KyokalithPlugin
import com.tinyyana.kyokalith.chunk.ChunkCoord
import com.tinyyana.kyokalith.chunk.EpochedChunk
import com.tinyyana.kyokalith.eligibility.EligiblePlacedOre
import org.bukkit.Chunk
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.EventExecutor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Nohara 的 `NoharaChunkRestoredEvent`(RESTORED / ROLLED_BACK)橋接,與 [NatureReviveBridge] 同樣用反射接事件,
 * Nohara 不在時 Kyokalith 照常運作。Nohara 的整合閘門只看「有沒有 Kyokalith 的 listener 掛在這個事件上」。
 *
 * **事件在該 chunk 的 owner region 執行緒上**:這裡只做記憶體操作與 [Chunk] 讀取,所有 SQLite 都丟到專屬的單執行緒
 * executor(順序執行,也就不需要跨 chunk 的鎖)。
 *
 * 與 NatureRevive 整塊再生的差異:Nohara 只改寫跟 donor 不同、而且沒被玩家再改過的格子。所以
 * - **epoch +1**,舊 epoch 的 materialized 鎖定全部作廢(已知礦脈位置不會在還原後原樣重現 = 不能被拿來重複採);
 * - **dirty 旗標搬到新 epoch**,不清掉(沒被改寫的玩家方塊仍然是玩家放的,見 [com.tinyyana.kyokalith.chunk.DirtyPositionStore.carryOver]);
 * - placed token 只丟掉「現在那格已經不是那顆礦」的,其餘保留;
 * - 絕不掃 chunk:token 來自記憶體索引,逐顆讀一個座標。
 *
 * 持久化順序讓每個崩潰點都安全:新 epoch 的 dirty 先落地 → epoch 前進 → 補搬競態中標進舊集合的座標 →
 * 刪舊 epoch 的列。崩潰在中間只會留下沒人引用的舊列(垃圾),不會留下「新地形配舊誘餌狀態」。
 * 任何一步失敗 → 該 chunk 標為 suspended(未知,不決算)並 severe log,等管理員處理,不嘗試硬繼續。
 */
class NoharaBridge(private val plugin: KyokalithPlugin) : Listener {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Kyokalith-NoharaRestore").apply { isDaemon = true }
    }

    /** 目前掛著的 listener;重新註冊(Nohara 熱載入換了 classloader)時先 unregister 舊的。 */
    @Volatile
    private var registered: Listener? = null

    @Volatile
    private var registeredEventClass: Class<*>? = null

    @Volatile
    private var accessors: Accessors? = null

    private val events = AtomicLong()
    private val failures = AtomicLong()
    private val lastByChunk = ConcurrentHashMap<ChunkCoord, RestoreRecord>()

    data class RestoreRecord(
        val opId: Long,
        val kind: String,
        val status: String,
        val oldEpoch: Int,
        val newEpoch: Int,
        val dirtyCarried: Int,
        val staleTokens: Int,
        val atMillis: Long,
    )

    private class Accessors(val getChunk: Method, val getOpId: Method, val getKind: Method)

    val active: Boolean get() = registered != null

    fun eventsSeen(): Long = events.get()

    fun failureCount(): Long = failures.get()

    fun lastRestore(coord: ChunkCoord): RestoreRecord? = lastByChunk[coord]

    /** 掛上事件;Nohara 沒裝或尚未啟用(找不到類別)回 false。已經掛在同一個事件類別上就不重複掛。 */
    fun register(): Boolean {
        // 用 Nohara 自己的 class loader 取事件類別:不依賴跨插件的類別查找,也不需要 softdepend(會經 LycohinyaCore 成環)。
        // Nohara 啟用之後(PluginEnableEvent)才會走到這裡;載入順序上它一律比 Kyokalith 晚。
        val nohara = plugin.server.pluginManager.getPlugin(NOHARA)?.takeIf { it.isEnabled } ?: return false
        val eventClass = runCatching {
            Class.forName(EVENT_CLASS, true, nohara.javaClass.classLoader).asSubclass(Event::class.java)
        }.getOrNull() ?: return false
        if (registeredEventClass === eventClass && registered != null) return true
        val acc = runCatching {
            Accessors(eventClass.getMethod("getChunk"), eventClass.getMethod("getOpId"), eventClass.getMethod("getKind"))
        }.getOrElse {
            plugin.logger.severe("Nohara event class found but accessors missing (${it.message}); bridge inactive")
            return false
        }
        unregister()
        val listener = object : Listener {}
        plugin.server.pluginManager.registerEvent(
            eventClass,
            listener,
            EventPriority.MONITOR,
            EventExecutor { _, event -> handle(event) },
            plugin,
            false,
        )
        accessors = acc
        registeredEventClass = eventClass
        registered = listener
        return true
    }

    private fun unregister() {
        registered?.let { HandlerList.unregisterAll(it) }
        registered = null
        registeredEventClass = null
        accessors = null
    }

    /** Nohara 熱載入/重載會換新的事件類別(新的 HandlerList),舊的註冊對它無效 → 跟著重新註冊。 */
    @EventHandler
    fun onPluginEnable(event: PluginEnableEvent) {
        if (event.plugin.name != NOHARA) return
        val ok = register()
        if (ok) plugin.logger.info("KYOKALITH_NOHARA_BRIDGE registered: listening to NoharaChunkRestoredEvent")
        else plugin.logger.severe("KYOKALITH_NOHARA_BRIDGE NOT registered although Nohara is enabled; Nohara will refuse writes (INTEGRATION_MISSING)")
    }

    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        if (event.plugin.name != NOHARA) return
        unregister()
        plugin.logger.info("KYOKALITH_NOHARA_BRIDGE unregistered: Nohara disabled")
    }

    /** onDisable:把已排進去的還原失效工作做完再讓出(之後才關資料庫)。 */
    fun shutdown() {
        unregister()
        executor.shutdown()
        if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
            plugin.logger.warning("KYOKALITH_NOHARA_SHUTDOWN restore invalidation jobs still pending after 15s")
        }
    }

    // ------------------------------------------------------------------ 事件(region 執行緒:只做記憶體)

    private fun handle(event: Event) {
        val acc = accessors ?: return
        events.incrementAndGet()
        val chunk = runCatching { acc.getChunk.invoke(event) as Chunk }.getOrNull()
        val opId = runCatching { acc.getOpId.invoke(event) as Long }.getOrDefault(-1L)
        val kind = runCatching { acc.getKind.invoke(event) as String }.getOrDefault("UNKNOWN")
        if (chunk == null) {
            failures.incrementAndGet()
            plugin.logger.severe("KYOKALITH_NOHARA_EVENT_UNREADABLE op=$opId kind=$kind: event has no chunk; cannot invalidate")
            return
        }
        val coord = ChunkCoord(chunk.world.name, chunk.x, chunk.z)
        val stale: List<EligiblePlacedOre> = try {
            staleTokens(chunk)
        } catch (t: Throwable) {
            // 讀不出來就保守:全部當作過期丟掉(寧可少給一次 eligible,也不留指向已改寫方塊的 token)
            plugin.logger.warning("KYOKALITH_NOHARA_TOKEN_CHECK_FAILED ${coord.world} ${coord.cx},${coord.cz}: ${t.javaClass.simpleName}; dropping all placed tokens in chunk")
            plugin.eligiblePlacedOreStore.inChunk(coord.world, coord.cx, coord.cz).onEach { plugin.eligiblePlacedOreStore.forgetInMemory(it) }
        }
        try {
            executor.execute { invalidate(coord, opId, kind, stale) }
        } catch (e: RejectedExecutionException) {
            failures.incrementAndGet()
            plugin.suspendedChunkStore.suspendInMemory(coord)
            lastByChunk[coord] = record(opId, kind, "FAILED_REJECTED", 0, 0, 0, stale.size)
            plugin.logger.severe("KYOKALITH_NOHARA_INVALIDATE_REJECTED ${coord.world} ${coord.cx},${coord.cz} op=$opId kind=$kind: shutting down; chunk suspended in memory only")
        }
    }

    /** 這個 chunk 內「那格已經不是 token 記的那顆礦」的 placed token;同時從記憶體索引移除。 */
    private fun staleTokens(chunk: Chunk): List<EligiblePlacedOre> {
        val tokens = plugin.eligiblePlacedOreStore.inChunk(chunk.world.name, chunk.x, chunk.z)
        if (tokens.isEmpty()) return emptyList()
        val stale = tokens.filter { chunk.getBlock(it.x and 15, it.y, it.z and 15).type.name != it.oreMaterial }
        stale.forEach { plugin.eligiblePlacedOreStore.forgetInMemory(it) }
        return stale
    }

    // ------------------------------------------------------------------ 失效工作(專屬執行緒:可以碰 SQLite)

    private fun invalidate(coord: ChunkCoord, opId: Long, kind: String, stale: List<EligiblePlacedOre>) {
        var oldEpoch = 0
        var newEpoch = 0
        var carried = 0
        try {
            oldEpoch = plugin.chunkEpochStore.get(coord)
            val old = EpochedChunk(coord.world, coord.cx, coord.cz, oldEpoch)
            // 新 epoch 的 dirty 先落地(此時 epoch 還沒動;崩潰最多留下沒人引用的列)
            carried += plugin.dirtyPositionStore.carryOver(old, EpochedChunk(coord.world, coord.cx, coord.cz, oldEpoch + 1))
            newEpoch = plugin.chunkEpochStore.increment(coord)
            val fresh = EpochedChunk(coord.world, coord.cx, coord.cz, newEpoch)
            // epoch 切換瞬間才標進舊集合的座標(markDirty 先算 epoch 再寫入,中間可能跨過切換)
            carried += plugin.dirtyPositionStore.carryOver(old, fresh)
            plugin.dirtyPositionStore.clearEpoch(old)
            plugin.materializedVeinStore.clearEpoch(old)
            plugin.eligiblePlacedOreStore.deletePersisted(stale)
            lastByChunk[coord] = record(opId, kind, "OK", oldEpoch, newEpoch, carried, stale.size)
            if (lastByChunk.size > MAX_RECORDS) lastByChunk.keys.take(MAX_RECORDS / 2).forEach { lastByChunk.remove(it) }
            plugin.logger.info("KYOKALITH_NOHARA_INVALIDATED ${coord.world} ${coord.cx},${coord.cz} op=$opId kind=$kind epoch $oldEpoch->$newEpoch dirtyCarried=$carried staleTokens=${stale.size}")
        } catch (t: Throwable) {
            failures.incrementAndGet()
            plugin.suspendedChunkStore.suspendInMemory(coord)
            lastByChunk[coord] = record(opId, kind, "FAILED", oldEpoch, newEpoch, carried, stale.size)
            plugin.logger.severe("KYOKALITH_NOHARA_INVALIDATE_FAILED ${coord.world} ${coord.cx},${coord.cz} op=$opId kind=$kind: ${t.javaClass.simpleName}: ${t.message}; chunk suspended (unknown state), use /kyo resume ${coord.cx} ${coord.cz} after checking")
            runCatching { plugin.suspendedChunkStore.suspend(coord, "Nohara $kind op=$opId invalidation failed") }
                .onFailure { plugin.logger.severe("KYOKALITH_NOHARA_SUSPEND_NOT_PERSISTED ${coord.world} ${coord.cx},${coord.cz}: ${it.message}") }
        }
    }

    private fun record(opId: Long, kind: String, status: String, oldEpoch: Int, newEpoch: Int, carried: Int, stale: Int) =
        RestoreRecord(opId, kind, status, oldEpoch, newEpoch, carried, stale, System.currentTimeMillis())

    companion object {
        const val EVENT_CLASS = "com.tinyyana.nohara.api.NoharaChunkRestoredEvent"
        const val NOHARA = "Nohara"
        private const val MAX_RECORDS = 4096
    }
}
