package com.tinyyana.kyokalith.command

import com.tinyyana.kyokalith.KyokalithPlugin
import com.tinyyana.kyokalith.chunk.ChunkCoord
import com.tinyyana.kyokalith.chunk.EpochedChunk
import com.tinyyana.kyokalith.chunk.LocalPos
import com.tinyyana.kyokalith.eligibility.EligiblePlacedOre
import com.tinyyana.kyokalith.materialization.MaterializationService
import com.tinyyana.kyokalith.schedule.Schedulers
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Instant
import java.util.UUID

/** 管理用最小指令:stats + Phase 2/3 的預覽、抽樣與 QA token 操作。文字全走 lang/。 */
class KyoCommand(private val plugin: KyokalithPlugin) : CommandExecutor, TabCompleter {

    private fun m(key: String, vararg args: Pair<String, Any?>) = plugin.messages.get(key, *args)

    // Now 變體而非 atRegion:指令回覆若延後一 tick,RCON client 會永遠收不到(回應緩衝已 flush)
    private fun atBlock(location: Location, task: (Block) -> Unit) {
        Schedulers.atRegionNow(plugin, location) { task(location.block) }
    }

    private fun atBlock(world: World, x: Int, y: Int, z: Int, task: (Block) -> Unit) =
        atBlock(Location(world, x.toDouble(), y.toDouble(), z.toDouble()), task)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("kyokalith.admin")) {
            sender.sendMessage(m("no-permission"))
            return true
        }
        return when (args.firstOrNull()?.lowercase()) {
            "stats" -> stats(sender)
            "inspect" -> inspect(sender, args.drop(1))
            "chunk" -> chunkStatus(sender, args.drop(1))
            "preview" -> preview(sender, args.drop(1))
            "sample" -> sample(sender, args.drop(1))
            "markeligible" -> markEligible(sender, args.drop(1))
            "giveeligible" -> giveEligible(sender, args.drop(1))
            "suspend" -> suspend(sender, args.drop(1))
            "resume" -> resume(sender, args.drop(1))
            "resolve" -> resolve(sender, args.drop(1))
            "notify" -> notify(sender, args.drop(1))
            else -> {
                sender.sendMessage(m("usage-root"))
                true
            }
        }
    }

    private fun stats(sender: CommandSender): Boolean {
        val oreCount = plugin.oreRegistry.enabled().size
        val totalOreCount = plugin.oreRegistry.all().size
        val placedEligibleCount = plugin.eligiblePlacedOreStore.count()
        val suspendedCount = plugin.suspendedChunkStore.count()

        sender.sendMessage(
            listOf(
                m("stats-header"),
                m("stats-ores", "enabled" to oreCount, "total" to totalOreCount),
                m("stats-placed-eligible", "count" to placedEligibleCount),
                m("stats-suspended", "count" to suspendedCount),
                m("stats-db-connections", "count" to plugin.database.connectionsOpened),
                m("stats-vein-tools"),
                m("stats-exposure"),
                m("stats-token-drops"),
                m("stats-decoy-layer"),
                m("stats-nature-revive", "state" to m(if (plugin.natureReviveBridgeActive) "state-active" else "state-inactive")),
                m(
                    "stats-nohara",
                    "state" to m(if (plugin.noharaBridge.active) "state-active" else "state-inactive"),
                    "events" to plugin.noharaBridge.eventsSeen(),
                    "failed" to plugin.noharaBridge.failureCount(),
                ),
            ).joinToString("\n"),
        )
        return true
    }

    private fun inspect(sender: CommandSender, args: List<String>): Boolean {
        if (args.size !in 3..4) {
            sender.sendMessage(m("usage-inspect"))
            return true
        }
        val x = args[0].toIntOrNull()
        val y = args[1].toIntOrNull()
        val z = args[2].toIntOrNull()
        if (x == null || y == null || z == null) {
            sender.sendMessage(m("coords-not-int"))
            return true
        }
        val world = resolveWorld(sender, args.getOrNull(3)) ?: return true
        atBlock(world, x, y, z) { block ->
            val coord = chunkCoord(world, x, z)
            val epoch = plugin.chunkEpochStore.get(coord)
            val epoched = EpochedChunk(world.name, coord.cx, coord.cz, epoch)
            val local = LocalPos(Math.floorMod(x, 16), y, Math.floorMod(z, 16))
            val placed = plugin.eligiblePlacedOreStore.find(world.name, x, y, z)
            // 已是礦物的座標要用基底材質問 f,否則永遠 none,無法確認天然 eligible 的 f-match
            val baseName = MaterializationService.nativeOreBase(block.type)?.name ?: block.type.name
            val result = plugin.oreVeinResolver.resolve(world.name, epoch, x, y, z, baseName, world.environment.name)
            val locked = plugin.materializedVeinStore.find(epoched, local)

            sender.sendMessage(
                listOf(
                    m("inspect-header", "x" to x, "y" to y, "z" to z),
                    m("inspect-world-epoch", "world" to world.name, "epoch" to epoch),
                    m("inspect-block", "block" to block.type.name),
                    m("inspect-dirty", "dirty" to plugin.dirtyPositionStore.isDirty(epoched, local)),
                    m("inspect-suspended", "suspended" to plugin.suspendedChunkStore.isSuspended(coord)),
                    m("inspect-placed", "ore" to (placed?.oreType ?: m("none"))),
                    m(
                        "inspect-vein",
                        "result" to (result?.let { "${it.oreType} -> ${it.material} (priority ${it.priority}, vein ${it.veinId})" } ?: m("none")),
                    ),
                    m(
                        "inspect-locked",
                        "state" to (locked?.let { "${it.oreType ?: m("none")} -> ${it.material}" } ?: m("none")),
                    ),
                ).joinToString("\n"),
            )
        }
        return true
    }

    /** `/kyo preview [radius]`(玩家,以自身為中心)或 `/kyo preview <radius> <x> <y> <z> [world]`(主控台可用)。 */
    private fun preview(sender: CommandSender, args: List<String>): Boolean {
        val radius = parseRadius(sender, args.firstOrNull(), 8) ?: return true
        val center = if (args.size >= 4) {
            val x = args[1].toIntOrNull()
            val y = args[2].toIntOrNull()
            val z = args[3].toIntOrNull()
            if (x == null || y == null || z == null) {
                sender.sendMessage(m("coords-not-int"))
                return true
            }
            val world = resolveWorld(sender, args.getOrNull(4)) ?: return true
            Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        } else {
            val player = sender as? Player ?: return playerOnly(sender)
            player.location
        }
        atBlock(center) { block ->
            val hits = collectHits(block, radius, limit = 12)
            sender.sendMessage(m("preview-hits", "radius" to radius, "total" to hits.total))
            hits.examples.forEach {
                sender.sendMessage(m("preview-example", "x" to it.x, "y" to it.y, "z" to it.z, "ore" to it.oreType, "material" to it.material))
            }
            if (hits.total > hits.examples.size) {
                sender.sendMessage(m("preview-omitted", "count" to hits.total - hits.examples.size))
            }
        }
        return true
    }

    private fun sample(sender: CommandSender, args: List<String>): Boolean {
        val player = sender as? Player ?: return playerOnly(sender)
        if (args.firstOrNull()?.lowercase() != "volume") {
            sender.sendMessage(m("usage-sample"))
            return true
        }
        val radius = parseRadius(sender, args.getOrNull(1), 8) ?: return true
        atBlock(player.location) { block ->
            val hits = collectHits(block, radius, limit = 0)
            sender.sendMessage(m("sample-result", "radius" to radius, "total" to hits.total, "scanned" to hits.scanned))
        }
        return true
    }

    private fun markEligible(sender: CommandSender, args: List<String>): Boolean {
        val player = sender as? Player ?: return playerOnly(sender)
        // 玩家指令跑在自己的 region 上,瞄準 8 格內必是同 region,所以射線可以就地算
        val location = when (args.size) {
            0 -> player.getTargetBlockExact(8)?.location ?: run {
                sender.sendMessage(m("markeligible-look-at"))
                return true
            }
            3 -> {
                val x = args[0].toIntOrNull()
                val y = args[1].toIntOrNull()
                val z = args[2].toIntOrNull()
                if (x == null || y == null || z == null) {
                    sender.sendMessage(m("coords-not-int"))
                    return true
                }
                Location(player.world, x.toDouble(), y.toDouble(), z.toDouble())
            }
            else -> {
                sender.sendMessage(m("usage-markeligible"))
                return true
            }
        }
        atBlock(location) { block ->
            val ore = oreForMaterial(block.type) ?: run {
                sender.sendMessage(m("not-enabled-ore", "material" to block.type.name))
                return@atBlock
            }
            val epoch = epochAt(block.world, block.x, block.z)
            val coord = chunkCoord(block.world, block.x, block.z)
            val epoched = EpochedChunk(block.world.name, coord.cx, coord.cz, epoch)
            val local = LocalPos(Math.floorMod(block.x, 16), block.y, Math.floorMod(block.z, 16))
            plugin.dirtyPositionStore.markDirty(epoched, local)
            plugin.dirtyPositionStore.flush(epoched)
            plugin.eligiblePlacedOreStore.insert(
                EligiblePlacedOre(
                    world = block.world.name,
                    x = block.x,
                    y = block.y,
                    z = block.z,
                    epoch = epoch,
                    oreType = ore.oreType,
                    oreMaterial = block.type.name,
                    tokenId = UUID.randomUUID().toString(),
                    placedBy = player.uniqueId,
                    placedAtMillis = Instant.now().toEpochMilli(),
                ),
            )
            sender.sendMessage(m("marked-eligible", "ore" to ore.oreType, "x" to block.x, "y" to block.y, "z" to block.z))
        }
        return true
    }

    private fun giveEligible(sender: CommandSender, args: List<String>): Boolean {
        if (args.size != 3) {
            sender.sendMessage(m("usage-giveeligible"))
            return true
        }
        val target = plugin.server.getPlayerExact(args[0]) ?: run {
            sender.sendMessage(m("player-not-online", "name" to args[0]))
            return true
        }
        val ore = plugin.oreRegistry[args[1]]?.takeIf { it.enabled } ?: run {
            sender.sendMessage(m("unknown-ore", "ore" to args[1]))
            return true
        }
        val amount = args[2].toIntOrNull()
        if (amount == null || amount !in 1..64) {
            sender.sendMessage(m("amount-range"))
            return true
        }
        val materialName = ore.stoneMaterial ?: ore.deepslateMaterial ?: run {
            sender.sendMessage(m("ore-no-material", "ore" to ore.oreType))
            return true
        }
        val material = Material.matchMaterial(materialName) ?: run {
            sender.sendMessage(m("unknown-material", "material" to materialName))
            return true
        }
        // 背包是收禮者的 region 資料,不是發送者的:Folia 上從主控台或別的玩家執行緒直接塞會拋例外
        Schedulers.atEntityNow(plugin, target) {
            val item = plugin.eligibleOrePdc.tag(ItemStack(material, amount), ore.oreType, target.world.name, 0)
            val leftovers = target.inventory.addItem(item)
            leftovers.values.forEach { target.world.dropItemNaturally(target.location, it) }
            sender.sendMessage(m("gave-eligible", "player" to target.name, "amount" to amount, "ore" to ore.oreType))
        }
        return true
    }

    /**
     * `/kyo chunk <cx> <cz> [world]`:該 chunk 的 Kyokalith 狀態,只讀 store、不碰世界方塊,所以在任何執行緒
     * (含 Folia 主控台/RCON)都能當場回覆。同一行也寫進 log(`KYO_CHUNK ...`),給腳本從 latest.log 取證。
     */
    private fun chunkStatus(sender: CommandSender, args: List<String>): Boolean {
        if (args.size !in 2..3) {
            sender.sendMessage(m("usage-chunk"))
            return true
        }
        val cx = args[0].toIntOrNull()
        val cz = args[1].toIntOrNull()
        if (cx == null || cz == null) {
            sender.sendMessage(m("chunk-coords-not-int"))
            return true
        }
        val world = resolveWorld(sender, args.getOrNull(2)) ?: return true
        val coord = ChunkCoord(world.name, cx, cz)
        val epoch = plugin.chunkEpochStore.get(coord)
        val epoched = EpochedChunk(coord.world, cx, cz, epoch)
        val suspended = plugin.suspendedChunkStore.isSuspended(coord)
        val dirty = plugin.dirtyPositionStore.count(epoched)
        val locked = plugin.materializedVeinStore.count(epoched)
        val placed = plugin.eligiblePlacedOreStore.inChunk(coord.world, cx, cz).size
        val last = plugin.noharaBridge.lastRestore(coord)
        val lastText = last?.let {
            "${it.status} ${it.kind} op=${it.opId} epoch ${it.oldEpoch}->${it.newEpoch} dirtyCarried=${it.dirtyCarried} staleTokens=${it.staleTokens}"
        } ?: m("none")
        sender.sendMessage(
            listOf(
                m("chunk-header", "world" to coord.world, "cx" to cx, "cz" to cz),
                m("chunk-epoch", "epoch" to epoch, "suspended" to suspended),
                m("chunk-counts", "dirty" to dirty, "locked" to locked, "placed" to placed),
                m("chunk-nohara", "last" to lastText),
            ).joinToString("\n"),
        )
        plugin.logger.info(
            "KYO_CHUNK world=${coord.world} cx=$cx cz=$cz epoch=$epoch suspended=$suspended dirty=$dirty locked=$locked placed=$placed " +
                "nohara=${last?.let { "${it.status}/${it.kind}/op${it.opId}/${it.oldEpoch}->${it.newEpoch}/carried${it.dirtyCarried}" } ?: "none"}",
        )
        return true
    }

    private fun suspend(sender: CommandSender, args: List<String>): Boolean {
        val player = sender as? Player ?: return playerOnly(sender)
        if (args.size < 3) {
            sender.sendMessage(m("usage-suspend"))
            return true
        }
        val cx = args[0].toIntOrNull()
        val cz = args[1].toIntOrNull()
        if (cx == null || cz == null) {
            sender.sendMessage(m("chunk-coords-not-int"))
            return true
        }
        val coord = ChunkCoord(player.world.name, cx, cz)
        plugin.suspendedChunkStore.suspend(coord, args.drop(2).joinToString(" "))
        sender.sendMessage(m("suspend-ok", "world" to coord.world, "cx" to cx, "cz" to cz))
        return true
    }

    private fun resume(sender: CommandSender, args: List<String>): Boolean {
        val player = sender as? Player ?: return playerOnly(sender)
        if (args.size != 2) {
            sender.sendMessage(m("usage-resume"))
            return true
        }
        val cx = args[0].toIntOrNull()
        val cz = args[1].toIntOrNull()
        if (cx == null || cz == null) {
            sender.sendMessage(m("chunk-coords-not-int"))
            return true
        }
        val coord = ChunkCoord(player.world.name, cx, cz)
        plugin.suspendedChunkStore.resume(coord)
        sender.sendMessage(m("resume-ok", "world" to coord.world, "cx" to cx, "cz" to cz))
        return true
    }

    /**
     * 手動把某座標當成「剛剛消失的方塊」重跑一次首次曝露決算,走與事件監聽完全相同的
     * `MaterializationService.resolveRemoved` 路徑。用途:維運排錯(懷疑漏了曝露事件時,
     * 對已挖開的座標補決算;決定性 f 保證冪等),以及無真人玩家環境下的行為驗證。
     */
    private fun resolve(sender: CommandSender, args: List<String>): Boolean {
        if (args.size !in 3..4) {
            sender.sendMessage(m("usage-resolve"))
            return true
        }
        val x = args[0].toIntOrNull()
        val y = args[1].toIntOrNull()
        val z = args[2].toIntOrNull()
        if (x == null || y == null || z == null) {
            sender.sendMessage(m("coords-not-int"))
            return true
        }
        val world = resolveWorld(sender, args.getOrNull(3)) ?: return true
        atBlock(world, x, y, z) { block ->
            plugin.materializationService.resolveRemoved(listOf(block))
            sender.sendMessage(m("resolve-ok", "x" to x, "y" to y, "z" to z))
        }
        return true
    }

    /** `/kyo notify <on|off>`:切換玩家挖到真礦時是否廣播給有 kyokalith.admin 權限的人。 */
    private fun notify(sender: CommandSender, args: List<String>): Boolean {
        val enabled = when (args.firstOrNull()?.lowercase()) {
            "on" -> true
            "off" -> false
            else -> {
                sender.sendMessage(m("usage-notify"))
                return true
            }
        }
        plugin.setNotifyOnOreFind(enabled)
        sender.sendMessage(m(if (enabled) "notify-on" else "notify-off"))
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("kyokalith.admin")) return emptyList()
        if (args.size == 1) return SUBCOMMANDS.matching(args[0])
        // i 是「正在補的第幾個子參數」(1-based);args.last() 是打到一半的字
        val i = args.size - 1
        val suggestions = when (args[0].lowercase()) {
            "inspect", "resolve" -> when (i) {
                in 1..3 -> coordSuggestion(sender, i)
                4 -> worldNames()
                else -> emptyList()
            }
            "preview" -> when (i) {
                1 -> RADIUS_SUGGESTIONS
                in 2..4 -> coordSuggestion(sender, i - 1)
                5 -> worldNames()
                else -> emptyList()
            }
            "sample" -> when (i) {
                1 -> listOf("volume")
                2 -> RADIUS_SUGGESTIONS
                else -> emptyList()
            }
            "markeligible" -> if (i in 1..3) coordSuggestion(sender, i) else emptyList()
            "giveeligible" -> when (i) {
                1 -> plugin.server.onlinePlayers.map { it.name }
                2 -> plugin.oreRegistry.enabled().map { it.oreType }
                3 -> AMOUNT_SUGGESTIONS
                else -> emptyList()
            }
            "suspend", "resume" -> chunkCoordSuggestion(sender, i)
            "chunk" -> if (i == 3) worldNames() else chunkCoordSuggestion(sender, i)
            "notify" -> if (i == 1) listOf("on", "off") else emptyList()
            else -> emptyList()
        }
        return suggestions.matching(args.last())
    }

    /** 座標補全:優先玩家瞄準的方塊(8 格內),否則自身所在方塊;主控台不補。 */
    private fun coordSuggestion(sender: CommandSender, axis: Int): List<String> {
        val player = sender as? Player ?: return emptyList()
        val block = player.getTargetBlockExact(8) ?: player.location.block
        return listOf(
            when (axis) {
                1 -> block.x
                2 -> block.y
                else -> block.z
            }.toString(),
        )
    }

    private fun chunkCoordSuggestion(sender: CommandSender, i: Int): List<String> {
        if (i > 2) return emptyList() // suspend 的第 3 個參數起是自由文字 reason
        val chunk = (sender as? Player)?.location?.chunk ?: return emptyList()
        return listOf((if (i == 1) chunk.x else chunk.z).toString())
    }

    private fun worldNames(): List<String> = plugin.server.worlds.map { it.name }

    private fun List<String>.matching(prefix: String): List<String> =
        filter { it.startsWith(prefix, ignoreCase = true) }

    /** 主控台/RCON 沒有自身世界:優先用明確參數,其次玩家所在世界,最後伺服器第一個世界。 */
    private fun resolveWorld(sender: CommandSender, name: String?): World? {
        if (name != null) {
            val world = plugin.server.getWorld(name)
            if (world == null) sender.sendMessage(m("unknown-world", "name" to name))
            return world
        }
        val world = (sender as? Player)?.world ?: plugin.server.worlds.firstOrNull()
        if (world == null) sender.sendMessage(m("no-world"))
        return world
    }

    private fun collectHits(center: Block, radius: Int, limit: Int): HitSummary {
        val world = center.world
        var scanned = 0
        var total = 0
        val examples = mutableListOf<HitExample>()
        val minY = maxOf(world.minHeight, center.y - radius)
        val maxY = minOf(world.maxHeight - 1, center.y + radius)
        for (x in center.x - radius..center.x + radius) {
            for (y in minY..maxY) {
                for (z in center.z - radius..center.z + radius) {
                    val coord = chunkCoord(world, x, z)
                    if (!world.isChunkLoaded(coord.cx, coord.cz)) continue
                    val block = world.getBlockAt(x, y, z)
                    scanned++
                    val epoch = plugin.chunkEpochStore.get(coord)
                    val result = plugin.oreVeinResolver.resolve(world.name, epoch, x, y, z, block.type.name, world.environment.name) ?: continue
                    total++
                    if (examples.size < limit) {
                        examples += HitExample(x, y, z, result.oreType, result.material)
                    }
                }
            }
        }
        return HitSummary(scanned, total, examples)
    }

    private fun parseRadius(sender: CommandSender, raw: String?, default: Int): Int? {
        val radius = raw?.toIntOrNull() ?: default
        if (radius !in 1..24) {
            sender.sendMessage(m("radius-range"))
            return null
        }
        return radius
    }

    private fun playerOnly(sender: CommandSender): Boolean {
        sender.sendMessage(m("player-only"))
        return true
    }

    private fun chunkCoord(world: World, x: Int, z: Int): ChunkCoord =
        ChunkCoord(world.name, Math.floorDiv(x, 16), Math.floorDiv(z, 16))

    private fun epochAt(world: World, x: Int, z: Int): Int = plugin.chunkEpochStore.get(chunkCoord(world, x, z))

    private fun oreForMaterial(material: Material) =
        plugin.oreRegistry.enabled().firstOrNull { it.stoneMaterial == material.name || it.deepslateMaterial == material.name }

    private data class HitExample(val x: Int, val y: Int, val z: Int, val oreType: String, val material: String)

    private data class HitSummary(val scanned: Int, val total: Int, val examples: List<HitExample>)

    private companion object {
        val SUBCOMMANDS = listOf("stats", "inspect", "chunk", "preview", "sample", "markeligible", "giveeligible", "suspend", "resume", "resolve", "notify")
        val RADIUS_SUGGESTIONS = listOf("8", "16", "24")
        val AMOUNT_SUGGESTIONS = listOf("1", "16", "64")
    }
}
