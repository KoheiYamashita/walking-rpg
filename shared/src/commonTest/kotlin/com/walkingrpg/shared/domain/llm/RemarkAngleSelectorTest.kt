package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.codex.CodexCategory
import com.walkingrpg.shared.domain.codex.testSpecies
import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.review.ReviewGrownWay
import com.walkingrpg.shared.domain.review.WalkReview
import com.walkingrpg.shared.domain.weather.SessionWeather
import com.walkingrpg.shared.domain.weather.WeatherCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 切り口の選び方（[RemarkAngleSelector]・design.md §5「同じ切り口の再使用を禁止」）。
 *
 * 見たいのは3つ：
 * - **決定的**（乱数・時刻を使わない。同じ入力からは必ず同じ切り口）
 * - 使用済みの切り口を避ける
 * - 全部使い切っても無言にならない（いちばん古いものを再利用する）
 */
class RemarkAngleSelectorTest {

    private val species = testSpecies("water", CodexCategory.WATER, requiredVisitCount = 3)

    private fun review(
        grown: Boolean = false,
        discovered: Boolean = false,
        approached: Boolean = false,
        weather: WeatherCondition? = null,
    ) = WalkReview(
        sessionId = 1L,
        startedAtMs = 1_000L,
        endedAtMs = 2_000L,
        durationMs = 1_000L,
        distanceMeters = 2_300.0,
        weather = weather?.let { SessionWeather(1L, it, 18.0, fetchedAtMs = 1L) },
        grownWays = if (grown) {
            listOf(ReviewGrownWay(1L, null, GrowthStage.SHRUB, GrowthStage.TREE))
        } else {
            emptyList()
        },
        discoveredSpecies = if (discovered) listOf(species) else emptyList(),
        approachedSpecies = if (approached) listOf(species) else emptyList(),
    )

    @Test
    fun 材料がある切り口だけが候補になる() {
        val available = RemarkAngleSelector.availableAngles(review(grown = true))

        assertEquals(
            setOf(RemarkAngle.GROWTH, RemarkAngle.TIME_OF_DAY, RemarkAngle.DISTANCE),
            available,
        )
    }

    @Test
    fun 時間帯と距離は常に候補にある() {
        // 何も起きなかった散歩でも候補が空にならない＝無言にならない
        val available = RemarkAngleSelector.availableAngles(review())

        assertEquals(setOf(RemarkAngle.TIME_OF_DAY, RemarkAngle.DISTANCE), available)
    }

    @Test
    fun 天候不明は候補にしない() {
        val available = RemarkAngleSelector.availableAngles(review(weather = WeatherCondition.UNKNOWN))

        assertTrue(RemarkAngle.WEATHER !in available, "「取れていない」と同じ扱い")
    }

    @Test
    fun 材料があれば優先順の先頭を選ぶ() {
        val available = RemarkAngleSelector.availableAngles(
            review = review(grown = true, discovered = true, approached = true),
            memory = WalkMemory(WalkMemoryDepth.MONTH_AGO, daysAgo = 30),
            stepsMention = StepsMention(StepsAmount.GOOD, daysAgo = 1),
        )

        assertEquals(RemarkAngle.DISCOVERY, RemarkAngleSelector.select(available))
    }

    @Test
    fun 使用済みの切り口は避ける() {
        val available = RemarkAngleSelector.availableAngles(
            review = review(grown = true, discovered = true),
        )

        val selected = RemarkAngleSelector.select(
            available = available,
            lastUsedAtMs = mapOf(RemarkAngle.DISCOVERY to 500L),
        )

        assertEquals(RemarkAngle.GROWTH, selected, "次の優先度に落ちる")
    }

    @Test
    fun 何度呼んでも同じ切り口が出る() {
        val available = RemarkAngleSelector.availableAngles(review(grown = true))
        val used = mapOf(RemarkAngle.GROWTH to 500L)

        val results = List(5) { RemarkAngleSelector.select(available, used) }

        assertEquals(1, results.distinct().size, "決定的（乱数・時刻を使わない）")
    }

    @Test
    fun 全部使い切ったらいちばん古い切り口を再利用する() {
        // 「もう全部言ったから何も言わない」は毎日歩いている人にだけ沈黙を返すことになる
        val available = RemarkAngleSelector.availableAngles(review(grown = true))

        val selected = RemarkAngleSelector.select(
            available = available,
            lastUsedAtMs = mapOf(
                RemarkAngle.GROWTH to 300L,
                RemarkAngle.TIME_OF_DAY to 100L,
                RemarkAngle.DISTANCE to 200L,
            ),
        )

        assertEquals(RemarkAngle.TIME_OF_DAY, selected)
    }

    @Test
    fun 使い切ったうえで同時刻なら優先順で決まる() {
        val available = RemarkAngleSelector.availableAngles(review(grown = true))

        val selected = RemarkAngleSelector.select(
            available = available,
            lastUsedAtMs = mapOf(
                RemarkAngle.GROWTH to 100L,
                RemarkAngle.TIME_OF_DAY to 100L,
                RemarkAngle.DISTANCE to 100L,
            ),
        )

        assertEquals(RemarkAngle.GROWTH, selected, "並びの先頭（決定的）")
    }
}
