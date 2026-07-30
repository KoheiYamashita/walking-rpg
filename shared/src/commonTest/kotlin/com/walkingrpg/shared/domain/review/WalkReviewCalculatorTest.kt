package com.walkingrpg.shared.domain.review

import com.walkingrpg.shared.domain.codex.CodexCategory
import com.walkingrpg.shared.domain.codex.CodexDiff
import com.walkingrpg.shared.domain.codex.CodexProgressCalculator
import com.walkingrpg.shared.domain.codex.PoiWayIndex
import com.walkingrpg.shared.domain.codex.codexPoi
import com.walkingrpg.shared.domain.codex.testSpecies
import com.walkingrpg.shared.domain.growth.GrowthDiff
import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.growth.WayGrowthCalculator
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.matching.SessionVisit
import com.walkingrpg.shared.domain.matching.SyntheticWalk
import com.walkingrpg.shared.domain.osm.PoiKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「そのセッションが何をもたらしたか」を確定データから引き直す純関数（issue #15）。
 *
 * 見たいのは2つ：
 * - セッションを除いた集計が正しい（＝差分が「今日ぶん」になる）
 * - **冪等・入力順非依存**（architecture.md §7「同じ passage 列 → 必ず同じ導出状態」）
 *
 * 距離はここには無い（`WalkDistanceCalculatorTest`）：way長の合算をやめて
 * GPS軌跡の実移動に変えたので、道の集計とは材料そのものが違う（design.md §11）。
 */
class WalkReviewCalculatorTest {

    private val sessionId = 7L
    private val otherSessionId = 8L

    private fun passage(wayId: Long, minute: Long, sessionId: Long = this.sessionId) = Passage(
        sessionId = sessionId,
        wayId = wayId,
        timestampMs = SyntheticWalk.START_MS + minute * 60_000L,
    )

    @Test
    fun 除外した集計はそのセッションの通過ぶんだけ減る() {
        val allPassCounts = mapOf(1L to 8, 2L to 3, 3L to 1)
        val sessionPassages = listOf(passage(1L, 0), passage(1L, 5), passage(3L, 9))

        val before = WalkReviewCalculator.passCountsExcluding(allPassCounts, sessionPassages)

        // 1本目は2回ぶん減り、3本目は0回になって行が消える（未踏は行が無いことで表す）
        assertEquals(mapOf(1L to 6, 2L to 3), before)
    }

    @Test
    fun 除外は入力の並びに依らない() {
        val allPassCounts = mapOf(1L to 8, 2L to 3)
        val ordered = listOf(passage(1L, 0), passage(2L, 1), passage(1L, 2))

        assertEquals(
            WalkReviewCalculator.passCountsExcluding(allPassCounts, ordered),
            WalkReviewCalculator.passCountsExcluding(allPassCounts, ordered.reversed()),
        )
    }

    @Test
    fun 段階の差分がそのセッションぶんになる() {
        // 低木（8回）の閾値をまたぐ散歩。除外した集計と比べれば「低木→木」は出ない
        val allPassCounts = mapOf(1L to 8, 2L to 1)
        val sessionPassages = listOf(passage(1L, 0), passage(2L, 4))

        val after = WayGrowthCalculator.growths(allPassCounts)
        val before = WayGrowthCalculator.growths(
            WalkReviewCalculator.passCountsExcluding(allPassCounts, sessionPassages),
        )
        val raises = GrowthDiff.stageRaises(before = before, after = after)

        assertEquals(listOf(1L, 2L), raises.map { it.wayId })
        assertEquals(GrowthStage.FLOWER, raises.first { it.wayId == 1L }.from)
        assertEquals(GrowthStage.SHRUB, raises.first { it.wayId == 1L }.to)
        assertEquals(null, raises.first { it.wayId == 2L }.from, "はじめて通った道は未踏から")
    }

    @Test
    fun 過去のセッションでも同じ差分が出る() {
        // 「今日」ではなく「そのセッション」の差分なので、後から散歩を重ねても結果は変わらない
        val allPassCounts = mapOf(1L to 12)
        val sessionPassages = listOf(passage(1L, 0), passage(1L, 6), passage(1L, 9), passage(1L, 12))

        val before = WalkReviewCalculator.passCountsExcluding(allPassCounts, sessionPassages)

        assertEquals(mapOf(1L to 8), before)
        assertTrue(
            GrowthDiff.stageRaises(
                before = WayGrowthCalculator.growths(before),
                after = WayGrowthCalculator.growths(allPassCounts),
            ).isEmpty(),
            "8回→12回はどちらも低木なので、そのセッションでは育っていない",
        )
    }

    @Test
    fun 散歩の除外はそのセッションの訪問だけを抜く() {
        val allVisits = mapOf(
            1L to listOf(
                SessionVisit(sessionId = otherSessionId, firstTimestampMs = 100L),
                SessionVisit(sessionId = sessionId, firstTimestampMs = 200L),
            ),
            2L to listOf(SessionVisit(sessionId = sessionId, firstTimestampMs = 300L)),
        )

        val before = WalkReviewCalculator.sessionVisitsExcluding(allVisits, sessionId)

        assertEquals(
            mapOf(1L to listOf(SessionVisit(otherSessionId, 100L))),
            before,
            "全部そのセッションだった道（2）は行ごと消える",
        )
    }

    @Test
    fun 予兆に近づいた種がそのセッションぶんとして出る() {
        // 3回で出る種。2回目の散歩で予兆（閾値-2回＝1回目から）に入り、3回目で発見
        val poi = codexPoi("node/1", PoiKind.WATER)
        val index = PoiWayIndex.of(pois = listOf(poi), ways = listOf(nearbyWay()))
        val species = listOf(testSpecies("water", CodexCategory.WATER, requiredVisitCount = 3))
        val allVisits = mapOf(
            1L to listOf(
                SessionVisit(sessionId = 1L, firstTimestampMs = 100L),
                SessionVisit(sessionId = 2L, firstTimestampMs = 200L),
            ),
        )

        val after = CodexProgressCalculator.progresses(index, allVisits, species = species)
        val before = CodexProgressCalculator.progresses(
            index = index,
            sessionVisitsByWay = WalkReviewCalculator.sessionVisitsExcluding(allVisits, 2L),
            species = species,
        )

        assertEquals(
            emptySet(),
            CodexDiff.newlyDiscoveredSpeciesIds(before, after),
            "まだ3回目ではないので発見はしていない",
        )
        assertEquals(
            emptySet(),
            CodexDiff.newlyForeshadowedSpeciesIds(before, after),
            "予兆は1回目の散歩で立っているので、2回目の散歩では『近づいた』にならない",
        )
        // 1回目の散歩を除くと、予兆はその散歩で立ったことが分かる
        val beforeFirst = CodexProgressCalculator.progresses(
            index = index,
            sessionVisitsByWay = WalkReviewCalculator.sessionVisitsExcluding(
                allSessionVisits = mapOf(1L to listOf(SessionVisit(1L, 100L))),
                sessionId = 1L,
            ),
            species = species,
        )
        val afterFirst = CodexProgressCalculator.progresses(
            index = index,
            sessionVisitsByWay = mapOf(1L to listOf(SessionVisit(1L, 100L))),
            species = species,
        )
        assertEquals(setOf("water"), CodexDiff.newlyForeshadowedSpeciesIds(beforeFirst, afterFirst))
    }

    /** POIの真横（近傍判定に入る距離）を通る道。 */
    private fun nearbyWay() =
        SyntheticWalk.eastWestWay(id = 1L, northMeters = 10.0, fromEast = -50.0, toEast = 50.0)
}
