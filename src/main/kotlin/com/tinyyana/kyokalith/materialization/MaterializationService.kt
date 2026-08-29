package com.tinyyana.kyokalith.materialization

import com.tinyyana.kyokalith.KyokalithPlugin
import com.tinyyana.kyokalith.chunk.ChunkCoord
import com.tinyyana.kyokalith.chunk.EpochedChunk
import com.tinyyana.kyokalith.chunk.LocalPos
import com.tinyyana.kyokalith.vein.MaterializedVein
import com.tinyyana.kyokalith.vein.MaterializedVeinStore
import com.tinyyana.kyokalith.vein.MaterializedPosition
import com.tinyyana.kyokalith.vein.ResolvedVein
import com.tinyyana.kyokalith.vein.VeinPosition
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace

/** 候選方塊的一個鄰居面，以及事件前是否早已透過非遮蔽方塊看得見。 */
data class NeighborExposure(
    val inRemovedKeys: Boolean,
    val liveTransparent: Boolean,
    val removedWasNonOccluding: Boolean = false,
)

/** 事件當下保存的移除前狀態；排到 Folia region 後也不會把原本的鐵軌誤讀成空氣。 */
data class RemovedBlockSnapshot(val block: Block, val wasOccluding: Boolean)

data class WorldgenContinuationNode(
    val oreType: String?,
    val material: String,
    val baseMaterial: String,
    val exposed: Boolean,
)

data class WorldgenContinuationPlan(
    val vein: Map<VeinPosition, WorldgenContinuationNode>,
    val boundary: Map<VeinPosition, WorldgenContinuationNode>,
)

/**
 * 單一事件內所有鎖定 row 的緩衝區 + 固定寫入上限。
 *
 * 舊版每命中一次礦脈就各開一條 SQLite 連線、各 commit 一次;2026-08-29 實測(本機 SSD)
 * **每次命中約 15ms**,一次爆炸命中 30 次就是 458ms 卡在該 region 的執行緒上。同一批改成單
 * 一 transaction 後同樣 30 次只要 54ms(8.5×)。爆炸會在一個事件裡連續決算數十到數百個座標,
 * 是這條路徑唯一會大量觸發的來源,所以緩衝的粒度取「一個事件」。
 *
 * [find] 把尚未落地的本次鎖定當成已鎖定回報,讓同一事件內後續座標看到的狀態與舊版
 * 「每次命中立刻寫入並更新快取」完全一致;差別只在整批是全有全無:任何一筆失敗就整批
 * rollback、呼叫端一個方塊都不改(舊版會留下已 commit 的前半批)。
 */
internal class MaterializationLockBuffer(
    private var remainingRows: Int,
    private val store: MaterializedVeinStore,
) {
    init {
        require(remainingRows >= 0)
    }

    private val pending = LinkedHashMap<MaterializedPosition, MaterializedVein>()

    fun find(chunk: EpochedChunk, pos: LocalPos): MaterializedVein? =
        pending[MaterializedPosition(chunk, pos)] ?: store.find(chunk, pos)

    /** false = 超過本次事件的固定 row 上限,呼叫端必須保守停手。 */
    fun add(entries: Map<MaterializedPosition, MaterializedVein>): Boolean {
        val newRows = entries.keys.count { it !in pending }
        if (newRows > remainingRows) return false
        remainingRows -= newRows
        pending.putAll(entries)
        return true
    }

    /** 整批一次落地;false 代表這次事件不得改動任何方塊。 */
    fun flush(): Boolean = store.upsertAll(pending)
}

/**
 * 曝露時決算(誘餌模型)。
 *
 * 原版世界生成的礦物保留在世界資料中當「誘餌」:透視看得到,但完全埋藏的誘餌在
 * 首次曝露的那一刻才由決定性礦脈函數決算——f 命中換成真礦,未命中的誘餌換回基底石。
 * 已曝露過的方塊(世界生成就露出的洞穴壁、先前決算過的斷面)永不改動,因此正常
 * 玩家看得到的礦全部是真的,誘餌只會騙到隔著實心方塊偷看的透視。
 *
 * 全程沒有 chunk 掃描、沒有排程任務、沒有 ChunkLoadEvent 處理;只在主執行緒由
 * 方塊消失事件觸發。一般事件只看每個消失方塊的6鄰居；爆炸另決算有界blockList本身，
 * 並受512方塊與4096筆鎖定row雙重硬上限。
 */
class MaterializationService(private val plugin: KyokalithPlugin) {

    /**
     * removed:同一事件中消失(或被活塞搬離)的天然方塊。呼叫時機可以是移除「生效之前」
     * (事件當下、由 MONITOR 優先權的 listener 同一 tick 呼叫,見 MaterializationListener)
     * 或生效之後(next-tick fallback);newlyExposed 只靠 removedKeys 本身判定「即將透明」,
     * 不需要世界資料已經反映移除,兩種呼叫時機結果一致。dirty 的 removed 座標(玩家放置過)
     * 不觸發決算:它蓋住的東西在被蓋住之前必然已經曝露過,再挖開不能改變它——這就是
     * 「玩家把看到的礦蓋起來、之後再挖開,礦不會消失」的保護。
     */
    fun resolveRemoved(removed: Collection<Block>) {
        resolveRemovedSnapshots(removed.map { RemovedBlockSnapshot(it, it.type.isOccluding) })
    }

    /** 只有玩家親手挖掉事件前已可見的原生礦，才可建立一次性的原版礦脈延續鎖。 */
    fun resolvePlayerBreak(snapshot: RemovedBlockSnapshot): Boolean =
        resolveRemovedSnapshots(listOf(snapshot), allowWorldgenContinuation = true)

    /**
     * TNT/床/終界水晶等爆炸會同時首次曝露與直接移除方塊。除了坑洞表面，也必須在掉落物
     * 產生前驗證 blockList 內的石頭與原生礦；否則埋藏誘餌可被透視後直接炸成掉落物。
     */
    fun resolveExplosionSnapshots(removed: Collection<RemovedBlockSnapshot>): Boolean =
        resolveRemovedSnapshots(
            removed,
            allowWorldgenContinuation = true,
            resolveRemovedOrigins = true,
        )

    fun resolveRemovedSnapshots(
        removed: Collection<RemovedBlockSnapshot>,
        allowWorldgenContinuation: Boolean = false,
        resolveRemovedOrigins: Boolean = false,
    ): Boolean {
        if (removed.isEmpty()) return true
        val ordered = removed.sortedWith(
            compareBy<RemovedBlockSnapshot>({ it.block.world.name }, { it.block.x }, { it.block.y }, { it.block.z }),
        )
        val removedKeys = ordered.mapTo(HashSet()) { PosKey(it.block.x, it.block.y, it.block.z) }
        val alreadyExposingKeys = ordered.asSequence()
            .filterNot { it.wasOccluding }
            .mapTo(HashSet()) { PosKey(it.block.x, it.block.y, it.block.z) }
        val active = ordered.filterNot { isDirty(it.block) }
        val visited = HashSet<PosKey>()
        val pending = LinkedHashMap<PosKey, PendingTypeChange>()
        val locks = MaterializationLockBuffer(MAX_MATERIALIZED_ROWS_PER_EVENT, plugin.materializedVeinStore)
        val trustedAnchors = HashSet<PosKey>()

        if (allowWorldgenContinuation) {
            for (snapshot in active) {
                val origin = snapshot.block
                when (activateWorldgenContinuation(origin, locks)) {
                    AnchorResult.FAILED -> return false
                    AnchorResult.LOCKED -> trustedAnchors += PosKey(origin.x, origin.y, origin.z)
                    AnchorResult.NOT_ANCHOR -> Unit
                }
            }
        }
        if (resolveRemovedOrigins) {
            for (snapshot in active) {
                val origin = snapshot.block
                if (PosKey(origin.x, origin.y, origin.z) !in trustedAnchors &&
                    !resolveRemovedOrigin(origin, pending, locks)
                ) {
                    return false
                }
            }
        }
        for (snapshot in active) {
            val origin = snapshot.block
            for (face in NEIGHBORS) {
                val nx = origin.x + face.modX
                val ny = origin.y + face.modY
                val nz = origin.z + face.modZ
                val neighbor = blockIfLoaded(origin.world, nx, ny, nz) ?: continue
                val key = PosKey(nx, ny, nz)
                if (key in removedKeys || !visited.add(key)) continue
                if (!resolveIfNewlyExposed(neighbor, removedKeys, alreadyExposingKeys, pending, locks)) return false
            }
        }
        // 決算結果必須先完整落地才可以改動方塊(§9.4);整批失敗就一個方塊都不動。
        if (!locks.flush()) return false
        pending.values.forEach { change ->
            if (change.block.type != change.target) change.block.setType(change.target, false)
        }
        return true
    }

    /**
     * 世界生成時已裸露的原生礦只是一個可信入口。第一次挖它時，由私有 salt 排序的
     * 六面連通 growth 建立 vein_size 格延續；不沿用可由 world seed 預測的原版礦形。
     * 原生同礦 frontier 另鎖成終點，後續任何已鎖格都不能再開新錨點。未曝露格只寫鎖、
     * 不 setType；整批跨 chunk 共用單一 transaction。
     */
    private fun activateWorldgenContinuation(
        origin: Block,
        locks: MaterializationLockBuffer,
    ): AnchorResult {
        val rootMaterial = origin.type
        val oreType = plugin.oreRegistry.oreTypeForEnabledMaterial(rootMaterial.name) ?: return AnchorResult.NOT_ANCHOR
        if (!hasAnyExposedFace(origin)) return AnchorResult.NOT_ANCHOR

        val rootPosition = materializedPosition(origin) ?: return AnchorResult.NOT_ANCHOR
        if (locks.find(rootPosition.chunk, rootPosition.pos) != null) {
            return AnchorResult.NOT_ANCHOR
        }
        val originPos = VeinPosition(origin.x, origin.y, origin.z)
        val targetBlocks = plugin.oreVeinResolver.worldgenContinuationSize(
            origin.world.name,
            rootPosition.chunk.epoch,
            oreType,
            originPos,
        )
        val blocks = HashMap<VeinPosition, Block>()
        val plan = planWorldgenContinuation(
            originPos,
            targetBlocks,
            { position ->
            val block = blockIfLoaded(origin.world, position.x, position.y, position.z) ?: return@planWorldgenContinuation null
            val positionKey = materializedPosition(block) ?: return@planWorldgenContinuation null
            if (plugin.suspendedChunkStore.isSuspended(positionKey.chunk.coord())) return@planWorldgenContinuation null
            if (plugin.dirtyPositionStore.isDirty(positionKey.chunk, positionKey.pos)) return@planWorldgenContinuation null
            if (locks.find(positionKey.chunk, positionKey.pos) != null) {
                return@planWorldgenContinuation null
            }
            val material = block.type
            val nodeOreType = plugin.oreRegistry.oreTypeForEnabledMaterial(material.name)
            val base = if (material in BASE_BLOCKS) material else {
                if (nodeOreType == null) return@planWorldgenContinuation null
                nativeOreBase(material) ?: return@planWorldgenContinuation null
            }
            blocks[position] = block
            WorldgenContinuationNode(nodeOreType, material.name, base.name, hasAnyExposedFace(block))
            },
            { position ->
                plugin.oreVeinResolver.worldgenContinuationRank(
                    origin.world.name,
                    rootPosition.chunk.epoch,
                    oreType,
                    originPos,
                    position,
                )
            },
        )
        if (plan.vein.size == 1 && plan.boundary.isEmpty()) return AnchorResult.LOCKED

        val veinId = "worldgen:${origin.world.name}:${rootPosition.chunk.epoch}:${origin.x}:${origin.y}:${origin.z}"
        val entries = LinkedHashMap<MaterializedPosition, MaterializedVein>()
        plan.vein.forEach { (position, node) ->
            val key = materializedPosition(blocks.getValue(position)) ?: return AnchorResult.FAILED
            val material = if (position == originPos || node.exposed) {
                node.material
            } else {
                plugin.oreRegistry.materialForBase(oreType, node.baseMaterial) ?: return AnchorResult.FAILED
            }
            entries[key] = MaterializedVein(oreType, veinId, material)
        }
        plan.boundary.forEach { (position, node) ->
            val key = materializedPosition(blocks.getValue(position)) ?: return AnchorResult.FAILED
            entries[key] = MaterializedVein(null, null, if (node.exposed) node.material else node.baseMaterial)
        }
        check(entries.size <= targetBlocks * 7) { "worldgen continuation lock exceeded fixed boundary cap" }
        return if (locks.add(entries)) AnchorResult.LOCKED else AnchorResult.FAILED
    }

    /** 玩家放置/機制生成的座標永不實體化,之後挖開它也不觸發鄰居決算(§10)。 */
    fun markDirty(block: Block) {
        plugin.dirtyPositionStore.markDirty(epochedChunk(block), localPos(block))
    }

    /**
     * 只決算「本次事件才首次曝露」的方塊:它的每一個透明面都必須指向本次 removed
     * 集合。已有其他透明面 = 事件前就看得到(世界生成曝露或先前決算過),一律不動,
     * 避免可見牆面憑空長礦或真礦被抹掉。
     *
     * 決算前先查 [MaterializedVeinStore][com.tinyyana.kyokalith.vein.MaterializedVeinStore]
     * 有沒有這個座標已鎖定的結果——有就直接套用(不重算、不重寫);沒有才呼叫礦脈函數即時
     * 決算,見 [resolveAndLock]。**這裡的持久化只決定「下次曝露到這個座標時該長什麼」,
     * 對還沒曝露的座標本身永遠不呼叫 `setType`——只有正在處理的 [block](本次事件當下才
     * 真正首次曝露的那一個)才會被 setType,鄰域鎖定的其他座標只寫進資料庫/記憶體快取,
     * 物理世界方塊維持原狀不變,直到它們自己未來真正首次曝露的那一刻。**
     */
    private fun resolveIfNewlyExposed(
        block: Block,
        removedKeys: Set<PosKey>,
        alreadyExposingKeys: Set<PosKey>,
        pending: MutableMap<PosKey, PendingTypeChange>,
        locks: MaterializationLockBuffer,
    ): Boolean {
        val current = block.type
        val decoyBase = if (current in BASE_BLOCKS) null else {
            if (!plugin.oreRegistry.isEnabledOreMaterial(current.name)) return true
            nativeOreBase(current) ?: return true
        }
        val base = decoyBase ?: current
        if (!newlyExposed(block, removedKeys, alreadyExposingKeys)) return true

        val coord = ChunkCoord(block.world.name, Math.floorDiv(block.x, 16), Math.floorDiv(block.z, 16))
        if (plugin.suspendedChunkStore.isSuspended(coord)) return true
        if (isDirty(block)) return true

        val epoch = plugin.chunkEpochStore.get(coord)
        val epoched = EpochedChunk(coord.world, coord.cx, coord.cz, epoch)
        val local = localPos(block)

        val lockedMaterial = locks.find(epoched, local)?.material
        val target = (
            if (lockedMaterial != null) {
                Material.matchMaterial(lockedMaterial)
            } else {
                resolveAndLock(block, base, decoyBase, epoched, locks)
            }
            ) ?: return false
        if (target != current) pending[PosKey(block.x, block.y, block.z)] = PendingTypeChange(block, target)
        return true
    }

    /**
     * 爆炸 blockList 內的候選方塊會在事件返回後直接消失，沒有下一次「首次曝露」可補救。
     * 已可見原生礦由 continuation 保留；埋藏原生礦與基底則依私有 salt 決算，miss 在掉落前
     * 改回基底。dirty/非候選材質維持原樣。
     */
    private fun resolveRemovedOrigin(
        block: Block,
        pending: MutableMap<PosKey, PendingTypeChange>,
        locks: MaterializationLockBuffer,
    ): Boolean {
        val current = block.type
        val decoyBase = if (current in BASE_BLOCKS) null else {
            if (!plugin.oreRegistry.isEnabledOreMaterial(current.name)) return true
            nativeOreBase(current) ?: return true
        }
        val base = decoyBase ?: current
        val coord = ChunkCoord(block.world.name, Math.floorDiv(block.x, 16), Math.floorDiv(block.z, 16))
        if (plugin.suspendedChunkStore.isSuspended(coord)) return true

        val epoched = EpochedChunk(coord.world, coord.cx, coord.cz, plugin.chunkEpochStore.get(coord))
        val lockedMaterial = locks.find(epoched, localPos(block))?.material
        val target = (
            if (lockedMaterial != null) {
                Material.matchMaterial(lockedMaterial)
            } else {
                resolveAndLock(block, base, decoyBase, epoched, locks)
            }
            ) ?: return false
        if (target != current) pending[PosKey(block.x, block.y, block.z)] = PendingTypeChange(block, target)
        return true
    }

    /**
     * 沒有鎖定紀錄的座標:呼叫礦脈函數即時決算。
     *
     * miss(候選 shape 不存在,f 對這個座標沒有任何礦種命中)不寫入 materialized_positions——
     * 世界方塊狀態本身已經是永久記錄(`isNewlyExposed` 保證同一座標不會被決算第二次),
     * 補一筆 miss 記錄不會多一層保障,卻會讓每次挖空石都變成一次同步 SQLite 寫入,直接
     * 打破 §15.1「熱路徑無 DB I/O」的紅線(這系統裡大多數的挖掘都是 miss)。
     *
     * hit 則一次鎖定觸發座標與完整有界 shape 內、屬於同一顆礦脈、尚未曝露且不與其他
     * 決算表衝突的座標,一次性批次寫入(單一 SQL
     * transaction,見 [MaterializedVeinStore.upsertAll][com.tinyyana.kyokalith.vein.MaterializedVeinStore.upsertAll])。
     * 寫入失敗時整批保守停手,回傳 null 讓呼叫端完全不改動方塊
     * (KYOKALITH_SPEC.md §9.4:決算了卻沒完整記錄成功,會破壞「不得
     * 再次決算」的保證,所以寧可這次不套用,也不能決算後不落地)。
     *
     * 注意:批次裡的每一筆都只寫入資料庫/記憶體快取,
     * 不會對任何尚未曝露的鄰居呼叫 `setType`——回傳值只給觸發座標 [block] 本身使用。
     */
    private fun resolveAndLock(
        block: Block,
        base: Material,
        decoyBase: Material?,
        epoched: EpochedChunk,
        locks: MaterializationLockBuffer,
    ): Material? {
        val world = block.world
        val detailed = plugin.oreVeinResolver.resolveDetailed(
            world.name, epoched.epoch, block.x, block.y, block.z, base.name, world.environment.name,
        ) ?: return decoyBase ?: base // miss:不寫資料庫,直接回傳基底材質

        val triggerMaterial = Material.matchMaterial(detailed.result.material) ?: return decoyBase ?: base
        val triggerLocal = localPos(block)
        val entries = LinkedHashMap<LocalPos, MaterializedVein>()
        entries[triggerLocal] = MaterializedVein(detailed.result.oreType, detailed.result.veinId, detailed.result.material)
        entries.putAll(collectShape(block, detailed, epoched, locks))

        val positioned = entries.mapKeys { (pos, _) -> MaterializedPosition(epoched, pos) }
        return if (locks.add(positioned)) triggerMaterial else null
    }

    /**
     * 一次遍歷完整 shape(最多 32 格)，不靠玩家繼續挖掘時滑動 5×5×5 視窗接力。
     * 只鎖定同時滿足下列全部條件:
     *
     * 1. 落在贏得觸發座標的那顆完整形狀([ResolvedVein.shape])內,且 veinId 相同。
     * 2. 與觸發座標同一個 chunk。resolver 的 8³ cell 與 chunk 邊界對齊，正常形狀不會
     *    跨界；這道 guard 保證未來幾何調整也不會展開成多 chunk/epoch 批次。
     * 3. 在世界高度範圍內、目前是可決算材質(base block 或已啟用礦種的誘餌材質)。
     * 4. 未曾曝露過,包含世界生成當下就曝露的情況(六個面目前都不透明)。
     * 5. 不在 dirty positions、還沒被鎖定過。
     *
     * 通過的鄰居各自呼叫一次 `resolve()`(不是直接沿用觸發座標的結果)——鄰居自己的
     * base 材質、Y 邊界、跨礦種優先序都可能與觸發座標不同,必須獨立決算;只有 veinId
     * 仍與觸發點相同才寫入,重疊礦種或相鄰 cell 不會被接進本輪鎖定。
     */
    private fun collectShape(
        origin: Block,
        detailed: ResolvedVein,
        epoched: EpochedChunk,
        locks: MaterializationLockBuffer,
    ): Map<LocalPos, MaterializedVein> {
        val world = origin.world
        val originCoord = ChunkCoord(world.name, Math.floorDiv(origin.x, 16), Math.floorDiv(origin.z, 16))
        val result = LinkedHashMap<LocalPos, MaterializedVein>()
        for (position in detailed.shape.positions) {
            val nx = position.x
            val ny = position.y
            val nz = position.z
            if (nx == origin.x && ny == origin.y && nz == origin.z) continue
            if (ny < world.minHeight || ny >= world.maxHeight) continue
            val nCoord = ChunkCoord(world.name, Math.floorDiv(nx, 16), Math.floorDiv(nz, 16))
            if (nCoord != originCoord) continue // 跨 chunk 留給該 chunk 未來自己的首次曝露事件

            val nLocal = LocalPos(Math.floorMod(nx, 16), ny, Math.floorMod(nz, 16))
            if (locks.find(epoched, nLocal) != null) continue // 已鎖定過(含本次事件稍早鎖的)
            if (plugin.dirtyPositionStore.isDirty(epoched, nLocal)) continue

            val nBlock = world.getBlockAt(nx, ny, nz)
            if (hasAnyExposedFace(nBlock)) continue // 已曝露過(含世界生成當下),不可改動

            val nCurrent = nBlock.type
            val nDecoyBase = if (nCurrent in BASE_BLOCKS) null else {
                if (!plugin.oreRegistry.isEnabledOreMaterial(nCurrent.name)) continue
                nativeOreBase(nCurrent) ?: continue
            }
            val nBase = nDecoyBase ?: nCurrent
            val nResolved = plugin.oreVeinResolver.resolve(
                world.name, epoched.epoch, nx, ny, nz, nBase.name, world.environment.name,
            )
            if (nResolved?.veinId != detailed.result.veinId) continue
            result[nLocal] = MaterializedVein(nResolved.oreType, nResolved.veinId, nResolved.material)
        }
        return result
    }

    /** 目前世界狀態下,這個座標是否已經有任一面透明(=事件前就看得到,不可再改動)。 */
    private fun hasAnyExposedFace(block: Block): Boolean =
        NEIGHBORS.any { face ->
            blockIfLoaded(block.world, block.x + face.modX, block.y + face.modY, block.z + face.modZ)
                ?.type
                ?.let(::isExposureMaterial) == true
        }

    /**
     * 讀鄰居目前的即時方塊狀態,轉成與呼叫時機無關的 [NeighborExposure],交給純函數
     * [isNewlyExposed] 判定。liveTransparent 讀的是「呼叫當下」的世界狀態——若在移除生效前
     * 呼叫(同一 tick 同步執行),origin 位置的 liveTransparent 會是 false,但 inRemovedKeys
     * 為 true 仍會被算成「即將透明」,判定結果與等到下一 tick 呼叫時完全一致。
     */
    private fun newlyExposed(
        block: Block,
        removedKeys: Set<PosKey>,
        alreadyExposingKeys: Set<PosKey>,
    ): Boolean {
        val neighbors = NEIGHBORS.map { face ->
            val nx = block.x + face.modX
            val ny = block.y + face.modY
            val nz = block.z + face.modZ
            val key = PosKey(nx, ny, nz)
            val liveTransparent = blockIfLoaded(block.world, nx, ny, nz)
                ?.type
                ?.let(::isExposureMaterial) == true
            NeighborExposure(
                inRemovedKeys = key in removedKeys,
                liveTransparent = liveTransparent,
                removedWasNonOccluding = key in alreadyExposingKeys,
            )
        }
        return isNewlyExposed(neighbors)
    }

    /** 未載入 chunk 視為實心且直接跳過,絕不因曝露檢查 force-load 周邊 chunk。 */
    private fun blockIfLoaded(world: org.bukkit.World, x: Int, y: Int, z: Int): Block? {
        if (y < world.minHeight || y >= world.maxHeight) return null
        if (!world.isChunkLoaded(Math.floorDiv(x, 16), Math.floorDiv(z, 16))) return null
        return world.getBlockAt(x, y, z)
    }

    private fun isDirty(block: Block): Boolean =
        plugin.dirtyPositionStore.isDirty(epochedChunk(block), localPos(block))

    private fun epochedChunk(block: Block): EpochedChunk {
        val coord = ChunkCoord(block.world.name, Math.floorDiv(block.x, 16), Math.floorDiv(block.z, 16))
        return EpochedChunk(coord.world, coord.cx, coord.cz, plugin.chunkEpochStore.get(coord))
    }

    private fun localPos(block: Block): LocalPos =
        LocalPos(Math.floorMod(block.x, 16), block.y, Math.floorMod(block.z, 16))

    private fun materializedPosition(block: Block): MaterializedPosition? {
        val coord = ChunkCoord(block.world.name, Math.floorDiv(block.x, 16), Math.floorDiv(block.z, 16))
        if (plugin.suspendedChunkStore.isSuspended(coord)) return null
        val chunk = EpochedChunk(coord.world, coord.cx, coord.cz, plugin.chunkEpochStore.get(coord))
        return MaterializedPosition(chunk, localPos(block))
    }

    private fun EpochedChunk.coord(): ChunkCoord = ChunkCoord(world, cx, cz)

    private data class PosKey(val x: Int, val y: Int, val z: Int)
    private data class PendingTypeChange(val block: Block, val target: Material)
    private enum class AnchorResult { NOT_ANCHOR, LOCKED, FAILED }

    companion object {
        val BASE_BLOCKS: Set<Material> = setOf(Material.STONE, Material.DEEPSLATE, Material.NETHERRACK)
        const val MAX_MATERIALIZED_ROWS_PER_EVENT = 4096

        private val NEIGHBORS = listOf(
            BlockFace.UP,
            BlockFace.DOWN,
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST,
        )

        fun nativeOreBase(material: Material): Material? {
            val name = material.name
            return when {
                name.startsWith("DEEPSLATE_") -> Material.DEEPSLATE
                name.startsWith("NETHER_") || name == "ANCIENT_DEBRIS" -> Material.NETHERRACK
                name.endsWith("_ORE") -> Material.STONE
                else -> null
            }
        }

        /** 非完整遮蔽方塊旁的礦早已可見，之後移除鐵軌、玻璃或半磚都不可重新決算。 */
        fun isExposureMaterial(material: Material): Boolean = !material.isOccluding

        /**
         * 固定上限 frontier growth：vein 最多 maxBlocks；rank 由呼叫端的私有 salt 決定。
         * 只檢查每個入選格的六個面，所以原生同礦 boundary 最多 6 * maxBlocks；
         * boundary 不再展開，正是阻止下一格重新 seed 的硬終點。
         */
        fun planWorldgenContinuation(
            origin: VeinPosition,
            maxBlocks: Int,
            lookup: (VeinPosition) -> WorldgenContinuationNode?,
            rank: (VeinPosition) -> Long = { position ->
                ((position.x.toLong() * 73_856_093L) xor
                    (position.y.toLong() * 19_349_663L) xor
                    (position.z.toLong() * 83_492_791L))
            },
        ): WorldgenContinuationPlan {
            require(maxBlocks >= 1)
            val root = lookup(origin) ?: return WorldgenContinuationPlan(emptyMap(), emptyMap())
            val vein = linkedMapOf(origin to root)
            val frontier = linkedMapOf<VeinPosition, WorldgenContinuationNode>()
            fun addFrontier(current: VeinPosition) {
                BLOCK_NEIGHBORS.forEach { (dx, dy, dz) ->
                    val next = VeinPosition(current.x + dx, current.y + dy, current.z + dz)
                    if (next in vein || next in frontier) return@forEach
                    val node = lookup(next) ?: return@forEach
                    val insideRadius =
                        kotlin.math.abs(next.x - origin.x) <= WORLDGEN_CONTINUATION_RADIUS &&
                            kotlin.math.abs(next.y - origin.y) <= WORLDGEN_CONTINUATION_RADIUS &&
                            kotlin.math.abs(next.z - origin.z) <= WORLDGEN_CONTINUATION_RADIUS
                    if (!insideRadius) return@forEach
                    if (node.exposed && node.oreType != root.oreType) return@forEach
                    frontier[next] = node
                }
            }
            addFrontier(origin)
            while (vein.size < maxBlocks && frontier.isNotEmpty()) {
                val next = frontier.keys.minWith(compareBy<VeinPosition>(rank).thenBy { it.x }.thenBy { it.y }.thenBy { it.z })
                val node = frontier.remove(next)!!
                vein[next] = node
                addFrontier(next)
            }
            val boundary = linkedMapOf<VeinPosition, WorldgenContinuationNode>()
            vein.keys.forEach { current ->
                BLOCK_NEIGHBORS.forEach { (dx, dy, dz) ->
                    val next = VeinPosition(current.x + dx, current.y + dy, current.z + dz)
                    if (next in vein || next in boundary) return@forEach
                    val node = lookup(next) ?: return@forEach
                    if (node.oreType == root.oreType) boundary[next] = node
                }
            }
            return WorldgenContinuationPlan(vein, boundary)
        }

        /**
         * 純判定,不碰 Bukkit Block、不依賴呼叫時機:6 個鄰居中,只要有任一透明鄰居
         * 不屬於本次事件(inRemovedKeys=false 卻 liveTransparent=true),代表事件前就已經
         * 曝露過,一律不動,回傳 false。屬於本次事件的鄰居一律當作「即將/已經透明」計入
         * opened,不需要 liveTransparent 為真——這讓決算可以在移除實際生效「之前」
         * (事件當下、同一 tick)安全呼叫,而不必等到下一 tick 才能看見「移除已生效」的世界
         * 狀態,消除兩者之間的可見閃爍窗口(誘餌原貌被畫出來、下一 tick 才修正成真礦或石頭)。
         */
        fun isNewlyExposed(neighbors: List<NeighborExposure>): Boolean {
            var opened = 0
            neighbors.forEach { neighbor ->
                if (neighbor.removedWasNonOccluding) return false
                if (neighbor.inRemovedKeys || neighbor.liveTransparent) {
                    if (!neighbor.inRemovedKeys) return false
                    opened++
                }
            }
            return opened > 0
        }

        private val BLOCK_NEIGHBORS = listOf(
            Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 1, 0),
            Triple(0, -1, 0), Triple(0, 0, 1), Triple(0, 0, -1),
        )
        const val WORLDGEN_CONTINUATION_RADIUS = 4
    }
}
