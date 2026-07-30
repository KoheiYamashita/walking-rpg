package com.walkingrpg.shared.domain.codex

import com.walkingrpg.shared.domain.matching.SessionVisit
import com.walkingrpg.shared.domain.matching.SyntheticWalk
import com.walkingrpg.shared.domain.osm.PoiKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CodexProgressCalculator] のテスト。図鑑の心臓部。
 *
 * 見たいのは：
 * - **冪等**：同じ通過列 → 必ず同じ図鑑状態（architecture.md §7「この設計の背骨」）
 * - 閾値ちょうどで発見、その手前で予兆（design.md §4.4「出現は決定的」）
 * - 訪問は**散歩単位で重複排除**する（同じ川を往復しても1回）
 * - 棚の回数は**POI単位の最大値**（近所の公園を1回ずつ回っても近づかない）
 * - 発見時刻は端末時計ではなく `passage.ts` から出る
 */
class CodexProgressCalculatorTest {

    /** 水辺の棚に3回・10回の2種（近い閾値と遠い閾値を混ぜる）。 */
    private val skimmer = testSpecies("skimmer", CodexCategory.WATER, requiredVisitCount = 3)
    private val kingfisher = testSpecies("kingfisher", CodexCategory.WATER, requiredVisitCount = 10)
    private val species = listOf(skimmer, kingfisher)

    private val config = CodexConfig.DEFAULT

    /** 1本の道が1つの水辺POIの近傍にあるだけの、いちばん単純な形。 */
    private fun singleRiverIndex(poiId: String = "node/1") = PoiWayIndex(
        entries = listOf(
            PoiWayIndex.Entry(
                poiId = poiId,
                category = CodexCategory.WATER,
                nearbyWayIds = setOf(RIVERSIDE_WAY),
            ),
        ),
    )

    private fun progresses(
        index: PoiWayIndex,
        sessionVisitsByWay: Map<Long, List<SessionVisit>>,
    ) = CodexProgressCalculator.progresses(
        index = index,
        sessionVisitsByWay = sessionVisitsByWay,
        config = config,
        species = species,
    )

    @Test
    fun 同じ通過列からは必ず同じ図鑑状態が出る() {
        // architecture.md §7 の冪等性テスト。時刻も乱数も入力にしていないことの担保
        val index = PoiWayIndex.of(
            pois = listOf(codexPoi("node/1", PoiKind.WATER, northMeters = 10.0)),
            ways = listOf(
                SyntheticWalk.eastWestWay(RIVERSIDE_WAY, northMeters = 0.0, fromEast = 0.0, toEast = 300.0),
            ),
        )
        val visits = mapOf(RIVERSIDE_WAY to visits(1L to 0L, 2L to 60L, 3L to 120L))

        val first = progresses(index, visits)
        val second = progresses(index, visits)
        // 順番を崩した入力でも同じ結果（保存順・読み出し順に依らない）
        val shuffled = progresses(
            index,
            mapOf(RIVERSIDE_WAY to visits(3L to 120L, 1L to 0L, 2L to 60L)),
        )

        assertEquals(first, second)
        assertEquals(first, shuffled)
    }

    @Test
    fun 閾値ちょうどで発見される() {
        val visits = mapOf(RIVERSIDE_WAY to visits(1L to 0L, 2L to 60L, 3L to 120L))

        val progress = progresses(singleRiverIndex(), visits).single { it.speciesId == skimmer.id }

        assertTrue(progress.isDiscovered, "3回目の散歩で必ず出る（確率は使わない）")
        assertEquals(3, progress.visitCount)
        assertEquals(ForeshadowStage.NONE, progress.foreshadowStage, "出たあとに予兆は出さない")
    }

    @Test
    fun 閾値の1つ手前では発見されない() {
        val visits = mapOf(RIVERSIDE_WAY to visits(1L to 0L, 2L to 60L))

        val progress = progresses(singleRiverIndex(), visits).single { it.speciesId == skimmer.id }

        assertNull(progress.discoveredAtMs)
        assertEquals(ForeshadowStage.NEAR, progress.foreshadowStage)
    }

    @Test
    fun 予兆は閾値の手前から立つ() {
        // 先行ぶん（既定2回）に入るまで何も出さない＝残り回数が漏れない
        val stages = (1..10).map { visitCount ->
            val visits = mapOf(
                RIVERSIDE_WAY to visits(*Array(visitCount) { index -> (index + 1).toLong() to index * 60L }),
            )
            progresses(singleRiverIndex(), visits).single { it.speciesId == kingfisher.id }
        }

        // 10回で出る種なので、8回目・9回目だけ予兆、10回目で発見
        assertEquals(
            List(7) { ForeshadowStage.NONE } + listOf(ForeshadowStage.NEAR, ForeshadowStage.NEAR) +
                listOf(ForeshadowStage.NONE),
            stages.map { it.foreshadowStage },
        )
        assertEquals(
            List(9) { false } + listOf(true),
            stages.map { it.isDiscovered },
        )
    }

    @Test
    fun 同じ散歩で何度通っても訪問は1回() {
        // 同じ川沿いを往復した散歩。design.md §4.4 の「10回通って」は10回の散歩
        val index = PoiWayIndex(
            entries = listOf(
                PoiWayIndex.Entry(
                    poiId = "node/1",
                    category = CodexCategory.WATER,
                    // 同じPOIの近傍に道が2本ある（往路と復路が別のwayに乗った状況も含む）
                    nearbyWayIds = setOf(RIVERSIDE_WAY, RIVERSIDE_WAY_2),
                ),
            ),
        )
        val visits = mapOf(
            RIVERSIDE_WAY to visits(1L to 10L, 1L to 30L),
            RIVERSIDE_WAY_2 to visits(1L to 20L),
        )

        val progress = progresses(index, visits).single { it.speciesId == skimmer.id }

        assertEquals(1, progress.visitCount, "1回の散歩は1訪問")
    }

    @Test
    fun 棚の訪問回数はPOI単位の最大値() {
        // 3つの公園を1回ずつ回った散歩3回。合算なら9回だが、通い詰めてはいないので3回
        val index = PoiWayIndex(
            entries = listOf(
                PoiWayIndex.Entry("node/1", CodexCategory.WATER, setOf(1L)),
                PoiWayIndex.Entry("node/2", CodexCategory.WATER, setOf(2L)),
                PoiWayIndex.Entry("node/3", CodexCategory.WATER, setOf(3L)),
            ),
        )
        val spreadOut = mapOf(
            1L to visits(1L to 0L),
            2L to visits(2L to 60L),
            3L to visits(3L to 120L),
        )

        val progress = progresses(index, spreadOut).single { it.speciesId == skimmer.id }

        assertEquals(1, progress.visitCount, "いちばん通ったPOIでも1回")
        assertTrue(!progress.isDiscovered, "散歩3回でも同じ場所に通っていなければ出ない")
    }

    @Test
    fun 同じPOIに通えば棚の回数が伸びる() {
        val index = PoiWayIndex(
            entries = listOf(
                PoiWayIndex.Entry("node/1", CodexCategory.WATER, setOf(1L)),
                PoiWayIndex.Entry("node/2", CodexCategory.WATER, setOf(2L)),
            ),
        )
        val visits = mapOf(
            1L to visits(1L to 0L, 2L to 60L, 3L to 120L),
            2L to visits(1L to 5L),
        )

        val progress = progresses(index, visits).single { it.speciesId == skimmer.id }

        assertEquals(3, progress.visitCount)
        assertTrue(progress.isDiscovered)
    }

    @Test
    fun 発見時刻は閾値に到達した散歩の通過時刻になる() {
        val visits = mapOf(RIVERSIDE_WAY to visits(1L to 0L, 2L to 60L, 3L to 120L, 4L to 180L))

        val progress = progresses(singleRiverIndex(), visits).single { it.speciesId == skimmer.id }

        // 3回目の散歩の時刻。4回目に上書きされない＝再計算しても発見日は動かない
        assertEquals(SyntheticWalk.START_MS + 120L * 60_000L, progress.discoveredAtMs)
    }

    @Test
    fun 発見時刻は同じ棚のPOIのうち最も早く閾値に届いた方を採る() {
        val index = PoiWayIndex(
            entries = listOf(
                // node/2 の方が後に並んでいるが、先に3回目へ到達している
                PoiWayIndex.Entry("node/1", CodexCategory.WATER, setOf(1L)),
                PoiWayIndex.Entry("node/2", CodexCategory.WATER, setOf(2L)),
            ),
        )
        val visits = mapOf(
            1L to visits(1L to 0L, 3L to 200L, 5L to 400L),
            2L to visits(2L to 100L, 4L to 150L, 6L to 300L),
        )

        val progress = progresses(index, visits).single { it.speciesId == skimmer.id }

        assertEquals(SyntheticWalk.START_MS + 300L * 60_000L, progress.discoveredAtMs)
    }

    @Test
    fun 訪問0回の種は行を作らない() {
        // 未発見の枠は SpeciesCatalog 側にあるので、キャッシュに0回の行は要らない
        assertTrue(progresses(singleRiverIndex(), emptyMap()).isEmpty())
        assertTrue(progresses(PoiWayIndex(entries = emptyList()), emptyMap()).isEmpty())
    }

    @Test
    fun 別の棚の訪問は影響しない() {
        val index = PoiWayIndex(
            entries = listOf(
                PoiWayIndex.Entry("node/1", CodexCategory.PARK, setOf(RIVERSIDE_WAY)),
            ),
        )
        val visits = mapOf(RIVERSIDE_WAY to visits(1L to 0L, 2L to 60L, 3L to 120L))

        // 水辺の種しか渡していないので、公園を3回通っても何も起きない
        assertTrue(progresses(index, visits).isEmpty())
    }

    @Test
    fun 並びは種ID順に揃う() {
        val visits = mapOf(RIVERSIDE_WAY to visits(1L to 0L, 2L to 60L, 3L to 120L))

        val result = progresses(singleRiverIndex(), visits)

        assertEquals(listOf(kingfisher.id, skimmer.id), result.map { it.speciesId })
    }

    @Test
    fun POIごとの訪問回数を数えられる() {
        // 歩行中の判定（CodexForeshadowEstimator）が出発時に読む入口
        val index = PoiWayIndex(
            entries = listOf(
                PoiWayIndex.Entry("node/1", CodexCategory.WATER, setOf(1L)),
                PoiWayIndex.Entry("node/2", CodexCategory.WATER, setOf(2L)),
                PoiWayIndex.Entry("node/3", CodexCategory.WATER, emptySet()),
            ),
        )
        val visits = mapOf(
            1L to visits(1L to 0L, 2L to 60L),
            2L to visits(1L to 5L),
        )

        assertEquals(
            mapOf("node/1" to 2, "node/2" to 1, "node/3" to 0),
            CodexProgressCalculator.visitCountsByPoi(index, visits),
        )
    }

    private companion object {
        const val RIVERSIDE_WAY = 11L
        const val RIVERSIDE_WAY_2 = 12L
    }
}
