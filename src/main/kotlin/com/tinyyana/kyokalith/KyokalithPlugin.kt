package com.tinyyana.kyokalith

import com.tinyyana.kyokalith.chunk.ChunkEpochStore
import com.tinyyana.kyokalith.chunk.DirtyPositionStore
import com.tinyyana.kyokalith.chunk.SuspendedChunkStore
import com.tinyyana.kyokalith.command.KyoCommand
import com.tinyyana.kyokalith.db.KyokalithDatabase
import com.tinyyana.kyokalith.eligibility.EligiblePlacedOreStore
import com.tinyyana.kyokalith.i18n.Messages
import com.tinyyana.kyokalith.integration.NatureReviveBridge
import com.tinyyana.kyokalith.materialization.MaterializationListener
import com.tinyyana.kyokalith.materialization.MaterializationService
import com.tinyyana.kyokalith.mining.OreEligibilityService
import com.tinyyana.kyokalith.mining.OreLifecycleListener
import com.tinyyana.kyokalith.notify.OreFindNotifyListener
import com.tinyyana.kyokalith.ore.OreRegistry
import com.tinyyana.kyokalith.pdc.EligibleOrePdc
import com.tinyyana.kyokalith.schedule.Schedulers
import com.tinyyana.kyokalith.vein.MaterializedVeinStore
import com.tinyyana.kyokalith.vein.OreVeinResolver
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class KyokalithPlugin : JavaPlugin() {

    lateinit var database: KyokalithDatabase
        private set
    lateinit var oreRegistry: OreRegistry
        private set
    lateinit var chunkEpochStore: ChunkEpochStore
        private set
    lateinit var dirtyPositionStore: DirtyPositionStore
        private set
    lateinit var suspendedChunkStore: SuspendedChunkStore
        private set
    lateinit var eligiblePlacedOreStore: EligiblePlacedOreStore
        private set
    lateinit var eligibleOrePdc: EligibleOrePdc
        private set
    lateinit var oreVeinResolver: OreVeinResolver
        private set
    lateinit var materializedVeinStore: MaterializedVeinStore
        private set
    lateinit var materializationService: MaterializationService
        private set
    lateinit var oreEligibilityService: OreEligibilityService
        private set
    lateinit var messages: Messages
        private set
    var natureReviveBridgeActive: Boolean = false
        private set

    // 挖礦事件在觸發玩家所在 region 的執行緒上處理，/kyo notify 則可能來自主控台或另一名玩家的
    // region 執行緒——兩者常常不是同一條執行緒。YamlConfiguration 底層是普通 HashMap，非執行緒安全，
    // 靠它跨執行緒傳遞這個切換值在 Folia 上沒有可見性保證(單執行緒的 Paper 測試不會踩到)。
    // 用 @Volatile 當唯一真相來源：寫入立刻對所有 region 執行緒可見，讀取也不用每次挖礦事件都碰
    // config 的 map。
    @Volatile
    var notifyOnOreFind: Boolean = false
        private set

    private var cancelDirtyFlush: (() -> Unit)? = null

    fun setNotifyOnOreFind(enabled: Boolean) {
        notifyOnOreFind = enabled
        config.set("notify_admins_on_ore_find", enabled)
        saveConfig()
    }

    override fun onEnable() {
        mergeConfigDefaults()
        Messages.saveBundledTemplates(this)
        messages = Messages.load(this, config.getString("locale", Messages.DEFAULT_LOCALE)!!)
        notifyOnOreFind = config.getBoolean("notify_admins_on_ore_find", false)

        OreRegistry.load(config.getConfigurationSection("ores")).fold(
            onSuccess = { oreRegistry = it },
            onFailure = { e ->
                logger.severe("Failed to load ore config, disabling plugin: ${e.message}")
                server.pluginManager.disablePlugin(this)
                return
            },
        )

        database = KyokalithDatabase(File(dataFolder, config.getString("database.file", "kyokalith.db")!!))
        runCatching { database.init() }.onFailure { e ->
            logger.severe("Database initialization failed, disabling plugin: ${e.message}")
            server.pluginManager.disablePlugin(this)
            return
        }

        chunkEpochStore = ChunkEpochStore(database)
        dirtyPositionStore = DirtyPositionStore(database, logger)
        suspendedChunkStore = SuspendedChunkStore(database)
        eligiblePlacedOreStore = EligiblePlacedOreStore(database)
        eligibleOrePdc = EligibleOrePdc(this)
        oreVeinResolver = OreVeinResolver(database.getMeta("salt") ?: error("database salt missing"), oreRegistry)
        materializedVeinStore = MaterializedVeinStore(database)
        materializationService = MaterializationService(this)
        oreEligibilityService = OreEligibilityService(this)

        val flushIntervalTicks = config.getLong("database.dirty_flush_interval_ticks", 40L)
        // 非同步:這輪工作只有 SQLite 寫入,完全不碰 Bukkit API,沒有理由佔用伺服器/region 執行緒。
        // 2026-08-13 spark 實測這段約佔主執行緒 1%(每輪逐 chunk 各開一條連線 + 各 commit 一次)。
        // DirtyPositionStore 的共享狀態都是 concurrent 容器,寫入路徑另有自己的鎖,
        // onDisable 的最後一次 flushAll 會等進行中的那輪跑完再寫。
        cancelDirtyFlush = Schedulers.asyncTimer(this, flushIntervalTicks, flushIntervalTicks) {
            dirtyPositionStore.flushAll()
        }

        KyoCommand(this).let { cmd ->
            getCommand("kyokalith")?.apply {
                setExecutor(cmd)
                tabCompleter = cmd
            }
        }
        server.pluginManager.registerEvents(MaterializationListener(this, materializationService), this)
        server.pluginManager.registerEvents(OreLifecycleListener(this, oreEligibilityService), this)
        server.pluginManager.registerEvents(OreFindNotifyListener(this), this)
        natureReviveBridgeActive = NatureReviveBridge(this).register()
        logger.info(
            "Kyokalith ${description.version} enabled (decoy model: event-driven exposure resolution, silk/placed token lifecycle, OreCheckTriggerEvent available, no chunk scanning, NatureRevive bridge: ${if (natureReviveBridgeActive) "active" else "inactive"}, scheduler: ${if (Schedulers.isFolia) "Folia regionized" else "Bukkit main thread"})",
        )
    }

    override fun onDisable() {
        cancelDirtyFlush?.invoke()
        if (::dirtyPositionStore.isInitialized) dirtyPositionStore.flushAll()
    }

    private fun mergeConfigDefaults() {
        saveDefaultConfig()
        reloadConfig()
        val previousSchemaVersion = if (config.contains("config_schema_version", true)) {
            config.getInt("config_schema_version")
        } else {
            1
        }
        config.options().copyDefaults(true)
        if (previousSchemaVersion < CONFIG_SCHEMA_VERSION_WITH_Y_CURVES) {
            config.getConfigurationSection("ores")?.getKeys(false)?.forEach { oreType ->
                // Bukkit 的 copyDefaults 會把新 list 塞進舊 ore section，卻保留舊 y_min/y_max，
                // 形成端點不一致的混合設定。空 list 明確覆蓋 inherited default，安全退回
                // preferred_y；patch 內的完整 v2 config 才啟用新曲線。
                config.set("ores.$oreType.y_weight_points", emptyList<Map<String, Any>>())
            }
            logger.warning(
                "Legacy config detected: inherited y_weight_points were disabled; " +
                    "install the bundled v2 config to enable calibrated height curves",
            )
        }
        saveConfig()
    }

    companion object {
        private const val CONFIG_SCHEMA_VERSION_WITH_Y_CURVES = 2
    }
}
