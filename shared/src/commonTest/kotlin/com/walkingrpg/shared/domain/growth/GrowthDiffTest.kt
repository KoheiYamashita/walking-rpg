package com.walkingrpg.shared.domain.growth

import kotlin.test.Test
import kotlin.test.assertEquals

/** 「今回の散歩で育った道」の判別（issue #10 の強調の材料）。 */
class GrowthDiffTest {

    private fun growth(wayId: Long, stage: GrowthStage, passCount: Int = 1) =
        WayGrowth(wayId = wayId, passCount = passCount, stage = stage)

    @Test
    fun 段階が上がった道だけを拾う() {
        val before = listOf(
            growth(1L, GrowthStage.GRASS),
            growth(2L, GrowthStage.FLOWER, passCount = 3),
        )
        val after = listOf(
            // 通ったが段階は据え置き
            growth(1L, GrowthStage.GRASS, passCount = 2),
            // 花 → 低木
            growth(2L, GrowthStage.SHRUB, passCount = 8),
        )

        assertEquals(setOf(2L), GrowthDiff.stageRaisedWayIds(before, after))
    }

    @Test
    fun 初めて通った道は上がった扱いになる() {
        val after = listOf(growth(9L, GrowthStage.GRASS))

        assertEquals(setOf(9L), GrowthDiff.stageRaisedWayIds(before = emptyList(), after = after))
    }

    @Test
    fun 何も変わらなければ空になる() {
        val growths = listOf(growth(1L, GrowthStage.TREE, passCount = 20))

        assertEquals(emptySet(), GrowthDiff.stageRaisedWayIds(before = growths, after = growths))
    }

    @Test
    fun 通過が消えて行が減っても上がった扱いにはしない() {
        // 閾値を厳しくして再計算した等で after から道が消えるケース。
        // 段階は上がるだけなので、消えた道は強調の対象ではない。
        val before = listOf(growth(1L, GrowthStage.FLOWER, passCount = 3))

        assertEquals(emptySet(), GrowthDiff.stageRaisedWayIds(before = before, after = emptyList()))
    }
}
