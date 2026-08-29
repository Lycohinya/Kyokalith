package com.tinyyana.kyokalith.vein

import com.tinyyana.kyokalith.ore.OreRegistry
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 出貨 config 的固定 Y 層密度守門。礦脈大小、連通元件、間距與 distinct encounters
 * 由 [OreDistributionMetricsTest] 覆蓋;這裡只負責 Y9/Y15/Y60 的 hits/10k 與稀有度比例。
 *
 * f 是純函數(固定 salt + 座標 → 固定結果),這裡的「Monte Carlo」抽樣本身是完全決定性、
 * 可重現的,不是真隨機——同一份 config.yml 永遠量出同一組數字,沒有測試 flaky 的疑慮,
 * 門檻可以設得比較貼近實測值。
 *
 * 這個測試檔用的抽樣方法量到的實際數字(salt="monte-carlo-calibration-salt",side=600,
 * 即 360,000 個座標):
 * - 校準前(cell_chance=0.006, vein_size_max=2):ancient_debris y8=0.14/10k、y15=0.056/10k、
 *   y22=0.0/10k——y8-22 這段實質上是零,這是玩家回報「Y9 附近幾乎挖不到資源」的根因。
 * 1.3.3 改用 8³ cell 後，玩家隧道遭遇率由 [OreDistributionMetricsTest] 另行守門；
 * 這裡的密度上限也同步改為新量測範圍，避免拿 1.3.2 的低密度門檻凍結舊回歸。
 */
class NetherOreDensityMonteCarloTest {

    private fun bundledOreRegistry(): OreRegistry {
        val stream = javaClass.classLoader.getResourceAsStream("config.yml")
            ?: error("找不到 classpath 上的 config.yml——這個測試要量的就是實際出貨的設定")
        val config = YamlConfiguration.loadConfiguration(InputStreamReader(stream, Charsets.UTF_8))
        return OreRegistry.load(config.getConfigurationSection("ores")).getOrThrow()
    }

    /** 在固定 Y、一個 [side] x [side] 的 X/Z 方格內,量測某個礦種贏得決算的座標數,換算成每萬格命中數。 */
    private fun hitsPer10k(resolver: OreVeinResolver, oreType: String, y: Int, side: Int = 600): Double {
        var hits = 0
        for (x in 0 until side) {
            for (z in 0 until side) {
                val result = resolver.resolve("world", 0, x, y, z, "NETHERRACK", "NETHER")
                if (result?.oreType == oreType) hits++
            }
        }
        return hits.toDouble() / (side.toLong() * side.toLong()) * 10_000
    }

    @Test
    fun `ancient_debris is clearly rarer than nether_quartz's peak but no longer functionally absent at y15`() {
        val resolver = OreVeinResolver("monte-carlo-calibration-salt", bundledOreRegistry())

        val ancientDebrisAtY15 = hitsPer10k(resolver, "ancient_debris", y = 15)
        val quartzAtOwnPeak = hitsPer10k(resolver, "nether_quartz", y = 60)

        // 迴歸守門:舊設定(cell_chance=0.006, vein_size_max=2)在同樣的量測方法下測出來是
        // 0.0~0.05/10k,等於完全挖不到;下限抓在明顯高於那個量級的地方,擋住「校準被誤改回
        // 接近零」的迴歸。上限確保 ancient_debris 仍然比常見礦種本身的密度峰值稀有得多。
        assertTrue(
            ancientDebrisAtY15 > 0.2,
            "ancient_debris 在 y15 的密度是 $ancientDebrisAtY15 /10k,太接近零(舊 bug 的量級是 0~0.05)",
        )
        assertTrue(
            ancientDebrisAtY15 < quartzAtOwnPeak,
            "ancient_debris 在 y15 的密度($ancientDebrisAtY15/10k)應該低於 nether_quartz 自己的密度峰值" +
                "($quartzAtOwnPeak/10k)——遠古殘骸該是遠比常見地獄礦稀有的資源",
        )
    }

    @Test
    fun `ancient_debris density in the y8-22 band is non-trivial across the whole band, not just at preferred_y`() {
        val resolver = OreVeinResolver("monte-carlo-calibration-salt", bundledOreRegistry())

        listOf(8, 10, 12, 15, 18, 20, 22).forEach { y ->
            val density = hitsPer10k(resolver, "ancient_debris", y)
            assertTrue(density > 0.1, "y=$y 的 ancient_debris 密度是 $density/10k,y8-22 這段不該接近零")
        }
    }

    /**
     * 不動 y_min=10 的迴歸守門:確認 quartz/nether_gold 在自己的 y_min 附近整體不是異常掛零。
     * 用 y10..y20 一整段的加總,而不是單一個 Y 值——三角權重在單一 Y-cell-band(16 格一段)
     * 內本來就會因為抽樣噪音而偶爾測出單點掛零,不代表設定異常;聚合一個範圍才穩定。
     */
    @Test
    fun `nether_quartz and nether_gold are not anomalously dead near their own y_min`() {
        val resolver = OreVeinResolver("monte-carlo-calibration-salt", bundledOreRegistry())
        val yBand = 10..20

        val quartzTotal = yBand.sumOf { hitsPer10k(resolver, "nether_quartz", it) }
        val goldTotal = yBand.sumOf { hitsPer10k(resolver, "nether_gold", it) }

        assertTrue(quartzTotal > 0.0, "nether_quartz 在 y10-20 整段掛零,可能有異常")
        assertTrue(goldTotal > 0.0, "nether_gold 在 y10-20 整段掛零,可能有異常")
    }

    /**
     * 1.3.3 用較密的 8³ 候選與較小礦脈恢復遭遇率；0.6% 仍是嚴格的方塊密度上限。
     */
    @Test
    fun `nether_quartz uniform band density is nontrivial and controlled`() {
        val resolver = OreVeinResolver("monte-carlo-calibration-salt", bundledOreRegistry())

        val quartzAtY15 = hitsPer10k(resolver, "nether_quartz", y = 15)
        val quartzAtY60 = hitsPer10k(resolver, "nether_quartz", y = 60)

        assertTrue(
            quartzAtY15 in 20.0..60.0 && quartzAtY60 in 20.0..60.0,
            "nether_quartz 的均勻帶失控:y15=$quartzAtY15/10k,y60=$quartzAtY60/10k",
        )
    }

    @Test
    fun `nether_gold stays present across its uniform band without overtaking quartz`() {
        val resolver = OreVeinResolver("monte-carlo-calibration-salt", bundledOreRegistry())

        val quartzAtY15 = hitsPer10k(resolver, "nether_quartz", y = 15)
        val quartzAtY60 = hitsPer10k(resolver, "nether_quartz", y = 60)
        val goldAtY15 = hitsPer10k(resolver, "nether_gold", y = 15)
        val goldAtY60 = hitsPer10k(resolver, "nether_gold", y = 60)

        assertTrue(
            goldAtY15 in 8.0..35.0 && goldAtY60 in 8.0..35.0,
            "nether_gold 的均勻帶失控:y15=$goldAtY15/10k,y60=$goldAtY60/10k",
        )
        assertTrue(
            goldAtY15 < quartzAtY15 && goldAtY60 < quartzAtY60,
            "nether_gold 應比同層 quartz 稀有:y15=$goldAtY15/$quartzAtY15,y60=$goldAtY60/$quartzAtY60",
        )
    }

    @Test
    fun `y9 y15 y60 preserve nether ranges and ancient debris rarity`() {
        val resolver = OreVeinResolver("monte-carlo-calibration-salt", bundledOreRegistry())

        assertTrue(hitsPer10k(resolver, "nether_quartz", y = 9) == 0.0, "quartz 在 y_min=10 下方不可出現")
        assertTrue(hitsPer10k(resolver, "nether_gold", y = 9) == 0.0, "nether_gold 在 y_min=10 下方不可出現")
        // 2026-08-29 起 ancient_debris 的曲線對齊原版(Y8~24 三角峰在 Y16 + Y8~119 極稀薄均勻背景),
        // 所以「一個門檻套三個 Y」已經沒有意義:原版在這三層的密度本來就差一個數量級以上。
        // 換算:hits/10k x 256/10000 = 每 chunk 每層方塊數。原版基準(minecraft.wiki/w/Ancient_Debris
        // 的兩個 cluster 推算)Y16 峰值約 0.123/chunk/層 = 4.8/10k,Y25 以上背景約 0.0054 = 0.21/10k。
        // 各帶都留約 ±50% 餘裕:守的是「數量級沒跑掉」,不是把當下實測值凍結成規格。
        val debrisBands = mapOf(
            9 to 0.2..2.5,   // 三角帶下緣,原版此處也已明顯稀薄
            15 to 2.0..6.0,  // 緊鄰 Y16 峰值,玩家實際開礦道的高度
            60 to 0.05..1.0, // 均勻背景帶;原版在這裡就是幾乎挖不到,不可拿主帶的門檻套它
        )
        debrisBands.forEach { (y, band) ->
            val debris = hitsPer10k(resolver, "ancient_debris", y)
            assertTrue(debris in band, "ancient_debris y=$y 密度 $debris/10k 超出對齊原版後的校準帶 $band")
        }
    }
}
