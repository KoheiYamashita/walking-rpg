package com.walkingrpg.shared.domain.growth

import com.walkingrpg.shared.domain.matching.Passage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [WayGrowthCalculator] のテスト。
 *
 * 見たいのは「通過を重ねるほど段階が上がる」「同じ通過列なら必ず同じ結果になる」の2点。
 * 閾値そのもの（3/8/20/50）を固定するテストは最小限にして、
 * チューニングでテストが壊れないようにする（守るのは**性質**）。
 */
class WayGrowthCalculatorTest {

    private val config = GrowthConfig.DEFAULT

    private fun passage(wayId: Long, timestampMs: Long) =
        Passage(sessionId = 1L, wayId = wayId, timestampMs = timestampMs)

    @Test
    fun 一度でも通れば草になる() {
        assertEquals(GrowthStage.GRASS, WayGrowthCalculator.stageOf(1, config))
    }

    @Test
    fun 通過を重ねると段階が上がっていく() {
        val stages = (1..config.creaturePassCount).map { WayGrowthCalculator.stageOf(it, config) }

        // 単調非減少＝下がらない（減衰なし。design.md §4.1）
        stages.zipWithNext { previous, next -> assertTrue(next >= previous, "段階は下がらない") }
        assertEquals(GrowthStage.entries.toList(), stages.distinct(), "5段階すべてを順に通る")
    }

    @Test
    fun 段階が上がるほど必要な通過回数の幅が広がる() {
        // 線形インフレ禁止（design.md §4.2「Lv.847の草を作らない」）。
        // 幅が一定以下になったら、それは等間隔＝ただの数字合わせになっている。
        val thresholds = GrowthStage.entries.map { config.minPassCountFor(it) }
        val steps = thresholds.zipWithNext { previous, next -> next - previous }

        steps.zipWithNext { previous, next ->
            assertTrue(next > previous, "次の段階までの幅は前より広い（$steps）")
        }
    }

    @Test
    fun 到達点を超えて通っても段階は生き物のまま() {
        assertEquals(
            GrowthStage.CREATURE,
            WayGrowthCalculator.stageOf(config.creaturePassCount * 10, config),
        )
    }

    @Test
    fun 通過0回の道は成長を持たない() {
        assertFailsWith<IllegalArgumentException> { WayGrowthCalculator.stageOf(0, config) }
        assertTrue(WayGrowthCalculator.growths(mapOf(1L to 0), config).isEmpty())
        assertTrue(WayGrowthCalculator.growthsFrom(emptyList(), config).isEmpty())
    }

    @Test
    fun 同じ道を通った回数だけpass_countが増える() {
        val passages = listOf(
            passage(wayId = 1L, timestampMs = 0),
            passage(wayId = 2L, timestampMs = 100),
            // 同じ散歩で同じ道に戻ってきた通過も1回として数える（design.md §4.1「通過ごと」）
            passage(wayId = 1L, timestampMs = 200),
            passage(wayId = 1L, timestampMs = 300),
        )

        assertEquals(
            listOf(
                WayGrowth(wayId = 1L, passCount = 3, stage = GrowthStage.FLOWER),
                WayGrowth(wayId = 2L, passCount = 1, stage = GrowthStage.GRASS),
            ),
            WayGrowthCalculator.growthsFrom(passages, config),
        )
    }

    @Test
    fun 通過列の並び順が変わっても結果は変わらない() {
        val passages = List(12) { index -> passage(wayId = (index % 3).toLong(), timestampMs = index * 10L) }

        assertEquals(
            WayGrowthCalculator.growthsFrom(passages, config),
            WayGrowthCalculator.growthsFrom(passages.reversed().shuffled(), config),
        )
    }

    @Test
    fun 閾値を変えると段階が引き直される() {
        // way_growth は捨てて作り直せる＝閾値を変えたら過去の通過ぶんもその場で反映される
        val passages = List(5) { index -> passage(wayId = 1L, timestampMs = index * 10L) }
        val easier = GrowthConfig(flowerPassCount = 2, shrubPassCount = 3, treePassCount = 4, creaturePassCount = 5)

        assertEquals(GrowthStage.FLOWER, WayGrowthCalculator.growthsFrom(passages, config).single().stage)
        assertEquals(GrowthStage.CREATURE, WayGrowthCalculator.growthsFrom(passages, easier).single().stage)
    }

    @Test
    fun 閾値が単調増加でない設定は作れない() {
        assertFailsWith<IllegalArgumentException> { GrowthConfig(flowerPassCount = 1) }
        assertFailsWith<IllegalArgumentException> { GrowthConfig(flowerPassCount = 8, shrubPassCount = 8) }
        assertFailsWith<IllegalArgumentException> { GrowthConfig(treePassCount = 100) }
    }
}
