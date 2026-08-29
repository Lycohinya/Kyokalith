package com.tinyyana.kyokalith.vein

import com.tinyyana.kyokalith.ore.OreRegistry
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic calibration probe. Assertions are added after the legacy baseline is recorded;
 * run with `--info` to print the table consumed by the release notes.
 */
class OreDistributionMetricsTest {
    private data class Pos(val x: Int, val y: Int, val z: Int)
    private data class LayerMetric(
        val hitsPer10k: Double,
        val componentMax: Int,
        val componentP50: Int,
        val componentP95: Int,
        val encountersPer10k: Double,
        val veinMax: Int,
        val veinP50: Int,
        val veinP95: Int,
        val nearestP50: Double,
        val nearestP95: Double,
    )
    private data class TunnelMetric(val encountersPer1k: Double, val meanSpacing: Double)
    private data class TntMetric(
        val destroyedHitsPerBlast: Double,
        val surfaceHitsPerBlast: Double,
        val encountersPer100Blasts: Double,
    )

    private fun bundledRegistry(): OreRegistry {
        val stream = javaClass.classLoader.getResourceAsStream("config.yml")
            ?: error("missing bundled config.yml")
        val config = YamlConfiguration.loadConfiguration(InputStreamReader(stream, Charsets.UTF_8))
        return OreRegistry.load(config.getConfigurationSection("ores")).getOrThrow()
    }

    @Test
    fun `shipped distribution stays frequent bounded and density controlled`() {
        val resolver = OreVeinResolver("vein-regression-calibration-v1", bundledRegistry())
        val layers = linkedMapOf(
            "coal" to Triple(192, "STONE", "NORMAL"),
            "iron" to Triple(16, "STONE", "NORMAL"),
            "copper" to Triple(48, "STONE", "NORMAL"),
            "gold" to Triple(-16, "DEEPSLATE", "NORMAL"),
            "redstone" to Triple(-59, "DEEPSLATE", "NORMAL"),
            "lapis" to Triple(0, "STONE", "NORMAL"),
            "diamond" to Triple(-59, "DEEPSLATE", "NORMAL"),
            "emerald" to Triple(240, "STONE", "NORMAL"),
            "nether_quartz" to Triple(60, "NETHERRACK", "NETHER"),
            "nether_gold" to Triple(15, "NETHERRACK", "NETHER"),
            "ancient_debris" to Triple(15, "NETHERRACK", "NETHER"),
        )

        layers.forEach { (oreType, layer) ->
            val (y, base, dimension) = layer
            val metric = layerMetric(resolver, oreType, y, base, dimension)
            println(
                "CURRENT_LAYER|$oreType|y=$y|hits10k=${metric.hitsPer10k.format()}|" +
                    "encounters10k=${metric.encountersPer10k.format()}|" +
                    "vein=max:${metric.veinMax},p50:${metric.veinP50},p95:${metric.veinP95}|" +
                    "component=max:${metric.componentMax},p50:${metric.componentP50},p95:${metric.componentP95}|" +
                    "nearest=p50:${metric.nearestP50.format()},p95:${metric.nearestP95.format()}",
            )
            val configuredMax = bundledRegistry()[oreType]!!.veinSizeMax
            assertTrue(metric.veinMax <= configuredMax, "$oreType 單一 veinId 超過設定上限 $configuredMax")
            assertTrue(metric.componentMax <= configuredMax, "$oreType 同礦種連通元件超過設定上限 $configuredMax")
            assertTrue(metric.encountersPer10k > 0.05, "$oreType 在關鍵層的實際遭遇頻率接近零")
            val densityCeiling = if (oreType == "nether_quartz") 60.0 else 30.0
            assertTrue(
                metric.hitsPer10k < densityCeiling,
                "$oreType 在關鍵層總密度失控:${metric.hitsPer10k}/10k >= $densityCeiling",
            )
        }
        listOf(9, 15, 60).forEach { y ->
            listOf("nether_quartz", "nether_gold", "ancient_debris").forEach { oreType ->
                val metric = layerMetric(resolver, oreType, y, "NETHERRACK", "NETHER")
                println(
                    "CURRENT_NETHER|$oreType|y=$y|hits10k=${metric.hitsPer10k.format()}|" +
                        "encounters10k=${metric.encountersPer10k.format()}",
                )
            }
        }

        volumeMetric(resolver, "NORMAL", "DEEPSLATE", -64, -1, blockCeiling = 1_200)
        volumeMetric(resolver, "NETHER", "NETHERRACK", 0, 63, blockCeiling = 1_800)
    }

    @Test
    fun `shipped ores have bounded player tunnel dry spells`() {
        val resolvers = (0 until 4).map {
            OreVeinResolver("tunnel-encounter-calibration-v1-$it", bundledRegistry())
        }
        val layers = linkedMapOf(
            "coal" to Triple(192, "STONE", "NORMAL"),
            "iron" to Triple(16, "STONE", "NORMAL"),
            "copper" to Triple(48, "STONE", "NORMAL"),
            "gold" to Triple(-16, "DEEPSLATE", "NORMAL"),
            "redstone" to Triple(-59, "DEEPSLATE", "NORMAL"),
            "lapis" to Triple(0, "STONE", "NORMAL"),
            "diamond" to Triple(-59, "DEEPSLATE", "NORMAL"),
            "emerald" to Triple(240, "STONE", "NORMAL"),
            "nether_quartz" to Triple(60, "NETHERRACK", "NETHER"),
            "nether_gold" to Triple(15, "NETHERRACK", "NETHER"),
            "ancient_debris" to Triple(15, "NETHERRACK", "NETHER"),
        )
        val spacingLimits = mapOf(
            "coal" to 250.0,
            "iron" to 250.0,
            "copper" to 300.0,
            "gold" to 500.0,
            "redstone" to 350.0,
            "lapis" to 550.0,
            "diamond" to 750.0,
            "emerald" to 3_000.0,
            "nether_quartz" to 150.0,
            "nether_gold" to 250.0,
            "ancient_debris" to 1_800.0,
        )

        layers.forEach { (oreType, layer) ->
            val metric = tunnelMetric(resolvers, oreType, layer.first, layer.second, layer.third)
            println(
                "TUNNEL|$oreType|y=${layer.first}|encounters1k=${metric.encountersPer1k.format()}|" +
                    "meanSpacing=${metric.meanSpacing.format()}",
            )
            assertTrue(
                metric.meanSpacing <= spacingLimits.getValue(oreType),
                "$oreType 隧道平均空窗 ${metric.meanSpacing.format()}m 超過門檻 ${spacingLimits.getValue(oreType)}m",
            )
        }
        // ancient_debris 的曲線 2026-08-29 起對齊原版,主帶與背景帶的空窗必須分開設門檻。
        // 原版的獄髓就是「Y8~24 找得到,再往上基本上不存在」,拿主帶的 1800m 去要求 Y60
        // 等於強迫這個礦種偏離原版——那正是舊曲線被壓平、主帶只剩原版 0.55x 的原因。
        // Y9(三角帶下緣)仍守 1800m;Y60 只守「沒有整層歸零」,門檻依實測 ~6km 取 9000m。
        val upperBandSpacingLimits = mapOf(9 to 1_800.0, 60 to 9_000.0)
        upperBandSpacingLimits.forEach { (y, limit) ->
            val metric = tunnelMetric(resolvers, "ancient_debris", y, "NETHERRACK", "NETHER")
            println(
                "TUNNEL|ancient_debris|y=$y|encounters1k=${metric.encountersPer1k.format()}|" +
                    "meanSpacing=${metric.meanSpacing.format()}",
            )
            assertTrue(metric.meanSpacing <= limit, "ancient_debris y$y 平均空窗 ${metric.meanSpacing.format()}m 超過門檻 ${limit}m")
        }
    }

    @Test
    fun `shipped TNT crater volume and surface encounters stay within calibrated bands`() {
        val resolvers = (0 until 4).map {
            OreVeinResolver("tnt-encounter-calibration-v1-$it", bundledRegistry())
        }
        val layers = linkedMapOf(
            "coal" to Triple(192, "STONE", "NORMAL"),
            "iron" to Triple(16, "STONE", "NORMAL"),
            "copper" to Triple(48, "STONE", "NORMAL"),
            "gold" to Triple(-16, "DEEPSLATE", "NORMAL"),
            "redstone" to Triple(-59, "DEEPSLATE", "NORMAL"),
            "lapis" to Triple(0, "STONE", "NORMAL"),
            "diamond" to Triple(-59, "DEEPSLATE", "NORMAL"),
            "emerald" to Triple(240, "STONE", "NORMAL"),
            "nether_quartz" to Triple(60, "NETHERRACK", "NETHER"),
            "nether_gold" to Triple(15, "NETHERRACK", "NETHER"),
            "ancient_debris" to Triple(15, "NETHERRACK", "NETHER"),
        )
        val encounterBands = mapOf(
            "coal" to 25.0..50.0,
            "iron" to 20.0..45.0,
            "copper" to 18.0..42.0,
            "gold" to 12.0..32.0,
            "redstone" to 20.0..45.0,
            "lapis" to 13.0..32.0,
            "diamond" to 6.0..18.0,
            "emerald" to 1.0..8.0,
            "nether_quartz" to 45.0..75.0,
            "nether_gold" to 23.0..50.0,
            // 2026-08-29 曲線對齊原版後,Y15 的密度從原版的 0.55x 補回 ~1.0x,遭遇率自然跟著上升
            // (實測 11.7/100 次爆炸)。這條帶是密度校準的衍生觀測值,不是獨立的設計目標——
            // 密度已經被原版基準釘住了,這裡只擋「數量級跑掉」。
            "ancient_debris" to 6.0..18.0,
        )

        layers.forEach { (oreType, layer) ->
            val metric = tntMetric(resolvers, oreType, layer.first, layer.second, layer.third)
            println(
                "TNT|$oreType|y=${layer.first}|destroyedHitsPerBlast=${metric.destroyedHitsPerBlast.format()}|" +
                    "surfaceHitsPerBlast=${metric.surfaceHitsPerBlast.format()}|" +
                    "encountersPer100Blasts=${metric.encountersPer100Blasts.format()}",
            )
            assertTrue(metric.destroyedHitsPerBlast > 0.0, "$oreType TNT 爆炸體積樣本不可完全掛零")
            assertTrue(
                metric.encountersPer100Blasts in encounterBands.getValue(oreType),
                "$oreType TNT 遭遇率 ${metric.encountersPer100Blasts.format()} 超出校準帶 ${encounterBands.getValue(oreType)}",
            )
        }
    }

    private fun tunnelMetric(
        resolvers: List<OreVeinResolver>,
        oreType: String,
        y: Int,
        base: String,
        dimension: String,
        tunnels: Int = 4,
        length: Int = 4_096,
    ): TunnelMetric {
        val veins = HashSet<String>()
        resolvers.forEachIndexed { resolverIndex, resolver ->
            repeat(tunnels) { tunnel ->
                val z = tunnel * 32
                repeat(length) { x ->
                listOf(
                    VeinPosition(x, y, z),
                    VeinPosition(x, y + 1, z),
                    VeinPosition(x, y, z - 1),
                    VeinPosition(x, y + 1, z - 1),
                    VeinPosition(x, y, z + 1),
                    VeinPosition(x, y + 1, z + 1),
                    VeinPosition(x, y - 1, z),
                    VeinPosition(x, y + 2, z),
                ).forEach { position ->
                    val result = resolver.resolve(
                        "tunnel-world",
                        0,
                        position.x,
                        position.y,
                        position.z,
                        base,
                        dimension,
                    )
                    if (result?.oreType == oreType) veins += "$resolverIndex:${result.veinId}"
                }
            }
        }
        }
        val meters = resolvers.size * tunnels * length
        return TunnelMetric(
            encountersPer1k = veins.size.toDouble() / meters * 1_000,
            meanSpacing = if (veins.isEmpty()) Double.POSITIVE_INFINITY else meters.toDouble() / veins.size,
        )
    }

    private fun tntMetric(
        resolvers: List<OreVeinResolver>,
        oreType: String,
        y: Int,
        base: String,
        dimension: String,
        blastsPerSalt: Int = 64,
    ): TntMetric {
        val removedOffsets = buildSet {
            for (dx in -4..4) for (dy in -4..4) for (dz in -4..4) {
                if (dx * dx + dy * dy + dz * dz <= 16) add(Pos(dx, dy, dz))
            }
        }
        val boundaryOffsets = buildSet {
            removedOffsets.forEach { removed ->
                listOf(
                    Pos(1, 0, 0), Pos(-1, 0, 0), Pos(0, 1, 0),
                    Pos(0, -1, 0), Pos(0, 0, 1), Pos(0, 0, -1),
                ).forEach { direction ->
                    val boundary = Pos(
                        removed.x + direction.x,
                        removed.y + direction.y,
                        removed.z + direction.z,
                    )
                    if (boundary !in removedOffsets) add(boundary)
                }
            }
        }
        var destroyedHits = 0
        var surfaceHits = 0
        val veins = HashSet<String>()
        resolvers.forEachIndexed { resolverIndex, resolver ->
            repeat(blastsPerSalt) { blast ->
                val centerX = blast * 12
                fun sample(offset: Pos, destroyed: Boolean) {
                    val result = resolver.resolve(
                        "tnt-world",
                        0,
                        centerX + offset.x,
                        y + offset.y,
                        offset.z,
                        base,
                        dimension,
                    )
                    if (result?.oreType == oreType) {
                        if (destroyed) destroyedHits++ else surfaceHits++
                        veins += "$resolverIndex:${result.veinId}"
                    }
                }
                removedOffsets.forEach { sample(it, destroyed = true) }
                boundaryOffsets.forEach { sample(it, destroyed = false) }
            }
        }
        val blastCount = resolvers.size * blastsPerSalt
        return TntMetric(
            destroyedHitsPerBlast = destroyedHits.toDouble() / blastCount,
            surfaceHitsPerBlast = surfaceHits.toDouble() / blastCount,
            encountersPer100Blasts = veins.size.toDouble() / blastCount * 100.0,
        )
    }

    private fun layerMetric(
        resolver: OreVeinResolver,
        oreType: String,
        y: Int,
        base: String,
        dimension: String,
        side: Int = 320,
    ): LayerMetric {
        val hits = HashSet<Pos>()
        val veinShapes = HashMap<String, VeinShape>()
        for (x in 0 until side) {
            for (z in 0 until side) {
                val resolved = resolver.resolveDetailed("world", 0, x, y, z, base, dimension)
                if (resolved?.result?.oreType == oreType) {
                    hits += Pos(x, y, z)
                    veinShapes[resolved.result.veinId] = resolved.shape
                }
            }
        }
        val components = componentSizes(hits, horizontalOnly = true)
        val sizes = veinShapes.values.map { it.blockCount }.sorted()
        val centers = veinShapes.values.map { shape ->
            shape.positions.map { it.x }.average() to shape.positions.map { it.z }.average()
        }
        val nearest = centers.mapNotNull { a ->
            centers.asSequence()
                .filter { it !== a }
                .minOfOrNull { b -> sqrt((a.first - b.first) * (a.first - b.first) + (a.second - b.second) * (a.second - b.second)) }
        }.sorted()
        return LayerMetric(
            hitsPer10k = hits.size.toDouble() / (side * side) * 10_000,
            componentMax = components.maxOrNull() ?: 0,
            componentP50 = percentile(components, 0.50),
            componentP95 = percentile(components, 0.95),
            encountersPer10k = veinShapes.size.toDouble() / (side * side) * 10_000,
            veinMax = sizes.maxOrNull() ?: 0,
            veinP50 = percentile(sizes, 0.50),
            veinP95 = percentile(sizes, 0.95),
            nearestP50 = percentileDouble(nearest, 0.50),
            nearestP95 = percentileDouble(nearest, 0.95),
        )
    }

    @Test
    fun `legacy spheres reproduce a 613 block quartz chain while current geometry has hard endpoints`() {
        fun sphere(ox: Int, oy: Int, oz: Int, radius: Int): Set<Pos> = buildSet {
            for (x in ox - radius..ox + radius) {
                for (y in oy - radius..oy + radius) {
                    for (z in oz - radius..oz + radius) {
                        val dx = x - ox
                        val dy = y - oy
                        val dz = z - oz
                        if (dx * dx + dy * dy + dz * dz <= radius * radius) add(Pos(x, y, z))
                    }
                }
            }
        }
        val legacy = sphere(1140, 62, 751, 4) +
            sphere(1136, 59, 754, 4) +
            sphere(1139, 69, 751, 3)
        assertEquals(613, componentSizes(legacy, horizontalOnly = false).maxOrNull())

        val resolver = OreVeinResolver("monte-carlo-calibration-salt", bundledRegistry())
        val current = HashSet<Pos>()
        for (x in 1128..1148) for (y in 52..78) for (z in 740..764) {
            if (resolver.resolve("world", 0, x, y, z, "NETHERRACK", "NETHER")?.oreType == "nether_quartz") {
                current += Pos(x, y, z)
            }
        }
        assertTrue(
            componentSizes(current, horizontalOnly = false).maxOrNull() ?: 0 <= 14,
            "同一份 salt/config 在新幾何下不可再形成超過 quartz vein_size_max=14 的連通礦帶",
        )
    }

    @Test
    fun `shipped veins stay followable from any exposed ore instead of leaving one block fragments`() {
        val registry = bundledRegistry()
        val resolver = OreVeinResolver("player-follow-calibration-v1", registry)
        val byVein = HashMap<Pair<String, String>, MutableSet<Pos>>()
        val samples = listOf(
            Triple(-64..-1, "DEEPSLATE", "NORMAL"),
            Triple(0..63, "STONE", "NORMAL"),
            Triple(64..127, "STONE", "NORMAL"),
            Triple(192..255, "STONE", "NORMAL"),
            Triple(0..63, "NETHERRACK", "NETHER"),
        )
        val side = 48 // exactly three cells, so no accepted shape is clipped at an X/Z sample edge
        samples.forEach { (ys, base, dimension) ->
            for (x in 0 until side) for (y in ys) for (z in 0 until side) {
                val result = resolver.resolve("world", 0, x, y, z, base, dimension) ?: continue
                byVein.getOrPut(result.oreType to result.veinId, ::linkedSetOf) += Pos(x, y, z)
            }
        }

        val sizesByOre = HashMap<String, MutableList<Int>>()
        byVein.forEach { (key, positions) ->
            val (oreType, _) = key
            val components = componentSizes(positions, horizontalOnly = false)
            assertEquals(listOf(positions.size), components, "$oreType 的存活 veinId 必須六面連通")
            val definition = registry[oreType]!!
            assertTrue(
                positions.size in definition.veinSizeMin..definition.veinSizeMax,
                "$oreType 的玩家可追挖產量 ${positions.size} 不在設定範圍 " +
                    "${definition.veinSizeMin}..${definition.veinSizeMax}",
            )
            sizesByOre.getOrPut(oreType, ::arrayListOf) += positions.size
        }

        sizesByOre.toSortedMap().forEach { (oreType, unsorted) ->
            val sizes = unsorted.sorted()
            val tinyPercent = sizes.count { it <= 2 }.toDouble() / sizes.size * 100.0
            println(
                "PLAYER_VEIN|$oreType|count=${sizes.size}|max=${sizes.last()}|" +
                    "p50=${percentile(sizes, 0.50)}|p95=${percentile(sizes, 0.95)}|" +
                    "oneOrTwoPct=${tinyPercent.format()}",
            )
        }

        listOf("coal", "iron", "copper", "redstone", "nether_quartz").forEach { oreType ->
            val sizes = sizesByOre[oreType].orEmpty().sorted()
            assertTrue(sizes.isNotEmpty(), "$oreType 玩家視角樣本不可掛零")
            assertTrue(
                sizes.none { it <= 2 },
                "$oreType 不可頻繁出現挖開後只有一兩顆的殘脈",
            )
            assertTrue(percentile(sizes, 0.50) >= 4, "$oreType 的玩家可追挖 P50 必須至少 4 格")
        }
    }

    @Test
    fun `shipped overworld height curves retain their vanilla-like peaks`() {
        val resolver = OreVeinResolver("overworld-height-calibration-v1", bundledRegistry())
        fun hits(
            ore: String,
            y: Int,
            base: String = if (y < 0) "DEEPSLATE" else "STONE",
            side: Int = 160,
        ): Double = layerMetric(resolver, ore, y, base, "NORMAL", side).hitsPer10k

        val checks = linkedMapOf(
            "coal-high-plateau" to (hits("coal", 192) to hits("coal", 32)),
            "iron-low-peak" to (hits("iron", 16) to hits("iron", 96)),
            "iron-high-peak" to (hits("iron", 232) to hits("iron", 96)),
            "copper-48" to (hits("copper", 48) to hits("copper", 0)),
            "gold-minus-16" to (hits("gold", -16) to hits("gold", 16)),
            "redstone-deep" to (hits("redstone", -59) to hits("redstone", 0)),
            "lapis-zero" to (hits("lapis", 0) to hits("lapis", 48)),
            "diamond-deep" to (hits("diamond", -59) to hits("diamond", 0)),
        )
        checks.forEach { (name, pair) ->
            val (peak, offPeak) = pair
            println("HEIGHT_CURVE|$name|peak=${peak.format()}|offPeak=${offPeak.format()}")
            assertTrue(peak > offPeak, "$name 應保持 peak > off-peak，實測 $peak <= $offPeak")
        }

        fun emeraldAcrossEpochs(y: Int): Double = (0 until 16).sumOf { epoch ->
            val epochResolver = OreVeinResolver("emerald-height-calibration-$epoch", bundledRegistry())
            layerMetric(epochResolver, "emerald", y, "STONE", "NORMAL", side = 160).hitsPer10k
        }
        val emeraldPeak = emeraldAcrossEpochs(240)
        val emeraldOffPeak = emeraldAcrossEpochs(64)
        println("HEIGHT_CURVE|emerald-high-16epochs|peak=${emeraldPeak.format()}|offPeak=${emeraldOffPeak.format()}")
        assertTrue(
            emeraldPeak > emeraldOffPeak,
            "emerald 高山帶的 16-epoch 聚合應高於低海拔:$emeraldPeak <= $emeraldOffPeak",
        )
    }

    private fun volumeMetric(
        resolver: OreVeinResolver,
        dimension: String,
        base: String,
        minY: Int,
        maxY: Int,
        side: Int = 64,
        blockCeiling: Int,
    ) {
        val positions = HashMap<Pos, OreResult>()
        val byVein = HashMap<String, Int>()
        for (x in 0 until side) {
            for (y in minY..maxY) {
                for (z in 0 until side) {
                    val result = resolver.resolve("world", 0, x, y, z, base, dimension) ?: continue
                    positions[Pos(x, y, z)] = result
                    byVein.merge(result.veinId, 1, Int::plus)
                }
            }
        }

        val veinSizes = byVein.values.sorted()
        val components = componentSizes(positions.keys, horizontalOnly = false)
        val centers = positions.entries
            .groupBy({ it.value.veinId }, { it.key })
            .values
            .map { vein ->
                Pos(
                    vein.sumOf { it.x } / vein.size,
                    vein.sumOf { it.y } / vein.size,
                    vein.sumOf { it.z } / vein.size,
                )
            }
        val nearest = centers.mapNotNull { a ->
            centers.asSequence()
                .filter { it !== a }
                .minOfOrNull { b ->
                    sqrt(
                        ((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) +
                            (a.z - b.z) * (a.z - b.z)).toDouble(),
                    )
                }
        }.sorted()

        println(
            "CURRENT_VOLUME|$dimension|blocks=${positions.size}|veins=${veinSizes.size}|" +
                "vein=max:${veinSizes.maxOrNull() ?: 0},p50:${percentile(veinSizes, 0.50)}," +
                "p95:${percentile(veinSizes, 0.95)}|component=max:${components.maxOrNull() ?: 0}," +
                "p50:${percentile(components, 0.50)},p95:${percentile(components, 0.95)}|" +
                "nearest=p50:${percentileDouble(nearest, 0.50).format()},p95:${percentileDouble(nearest, 0.95).format()}",
        )
        assertTrue(
            positions.size <= blockCeiling,
            "$dimension 64x64x64 樣本總礦量 ${positions.size} 超過受控上限 $blockCeiling",
        )
    }

    private fun componentSizes(points: Set<Pos>, horizontalOnly: Boolean): List<Int> {
        val remaining = points.toMutableSet()
        val sizes = ArrayList<Int>()
        val directions = if (horizontalOnly) {
            listOf(Pos(1, 0, 0), Pos(-1, 0, 0), Pos(0, 0, 1), Pos(0, 0, -1))
        } else {
            listOf(
                Pos(1, 0, 0), Pos(-1, 0, 0), Pos(0, 1, 0),
                Pos(0, -1, 0), Pos(0, 0, 1), Pos(0, 0, -1),
            )
        }
        while (remaining.isNotEmpty()) {
            val queue = ArrayDeque<Pos>()
            queue += remaining.first()
            remaining.remove(queue.first())
            var size = 0
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                size++
                directions.forEach { d ->
                    val next = Pos(current.x + d.x, current.y + d.y, current.z + d.z)
                    if (remaining.remove(next)) queue += next
                }
            }
            sizes += size
        }
        return sizes.sorted()
    }

    private fun percentile(values: List<Int>, percentile: Double): Int =
        if (values.isEmpty()) 0 else values[(ceil(values.size * percentile).toInt() - 1).coerceAtLeast(0)]

    private fun percentileDouble(values: List<Double>, percentile: Double): Double =
        if (values.isEmpty()) 0.0 else values[(ceil(values.size * percentile).toInt() - 1).coerceAtLeast(0)]

    private fun Double.format(): String = "%.3f".format(java.util.Locale.ROOT, this)
}
