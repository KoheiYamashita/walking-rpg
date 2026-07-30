package com.walkingrpg.shared.domain.review

import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.matching.SyntheticWalk
import com.walkingrpg.shared.domain.matching.SyntheticWalk.INTERVAL_MS
import com.walkingrpg.shared.domain.matching.SyntheticWalk.START_MS
import com.walkingrpg.shared.domain.matching.SyntheticWalk.point
import com.walkingrpg.shared.domain.matching.SyntheticWalk.samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「実際に歩いた距離」（[WalkDistanceCalculator]）。
 *
 * 見たいのは4つ：
 * - まっすぐ歩いた距離がそのまま出る
 * - **立ち止まっている間の揺れを積まない**（アンカー方式の存在理由）
 * - 精度の悪いサンプルが距離に混ざらない
 * - 決定的・入力順非依存（architecture.md §7）
 *
 * 座標はすべて架空（[SyntheticWalk]）。
 */
class WalkDistanceCalculatorTest {

    /** 10mずつ東へ [count] 件（アンカー閾値5mをはっきり超える歩き方）。 */
    private fun walkEast(count: Int, stepMeters: Double = 10.0, fromEast: Double = 0.0) =
        List(count) { index -> point(northMeters = 0.0, eastMeters = fromEast + index * stepMeters) }

    private fun distance(points: List<GeoPoint>, accuracyMeters: Double = SyntheticWalk.GOOD_ACCURACY_M) =
        WalkDistanceCalculator.distanceMeters(samples(points, accuracyMeters = accuracyMeters))

    @Test
    fun まっすぐ歩いた距離がそのまま出る() {
        // 10m × 10区間 = 100m
        assertEquals(100.0, distance(walkEast(count = 11)), 0.5)
    }

    @Test
    fun 立ち止まっている間の揺れは距離にならない() {
        // 半径2m弱の範囲をぐるぐる回るだけ（アンカーから5mを一度も超えない）。
        // 素朴に隣接サンプル間を足すと 100件 × 数m ＝ 数百mになる形。
        val jitter = List(100) { index ->
            val north = if (index % 2 == 0) 1.5 else -1.5
            val east = if (index % 4 < 2) 1.0 else -1.0
            point(northMeters = north, eastMeters = east)
        }

        assertEquals(0.0, distance(jitter), "アンカーが動かない＝1mmも増えない")
    }

    @Test
    fun 歩いてから立ち止まっても歩いたぶんだけ残る() {
        val walked = walkEast(count = 6) // 50m
        // 到達地点（東50m）のベンチで10分。1〜2mの揺れが60件続く
        val resting = List(60) { index ->
            point(northMeters = if (index % 2 == 0) 1.5 else -1.5, eastMeters = 50.0)
        }

        assertEquals(50.0, distance(walked + resting), 1.0)
    }

    @Test
    fun 精度の悪いサンプルは距離に入らない() {
        val good = samples(walkEast(count = 6)) // 0〜50m
        // 200m北に飛んだ1件（精度60m）。捨てなければ往復400mが乗る
        val bad = samples(
            points = listOf(point(northMeters = 200.0, eastMeters = 50.0)),
            startMs = START_MS + 6 * INTERVAL_MS,
            accuracyMeters = 60.0,
        )
        val more = samples(
            points = walkEast(count = 6, fromEast = 50.0), // 50〜100m
            startMs = START_MS + 7 * INTERVAL_MS,
        )

        assertEquals(100.0, WalkDistanceCalculator.distanceMeters(good + bad + more), 1.0)
    }

    @Test
    fun 全部精度が悪ければ距離は0() {
        assertEquals(0.0, distance(walkEast(count = 20), accuracyMeters = 40.0))
    }

    @Test
    fun サンプルが1件以下なら距離は0() {
        assertEquals(0.0, WalkDistanceCalculator.distanceMeters(emptyList()))
        assertEquals(0.0, distance(walkEast(count = 1)))
    }

    @Test
    fun 入力の並び順が違っても同じ距離になる() {
        val walk = samples(walkEast(count = 30))

        assertEquals(
            WalkDistanceCalculator.distanceMeters(walk),
            WalkDistanceCalculator.distanceMeters(walk.reversed()),
        )
    }

    @Test
    fun 同じ入力からは必ず同じ距離が出る() {
        val walk = samples(walkEast(count = 30))

        assertEquals(
            WalkDistanceCalculator.distanceMeters(walk),
            WalkDistanceCalculator.distanceMeters(walk),
        )
    }

    @Test
    fun アンカーを0に近づけると揺れが距離に化ける() {
        // この機構が距離を守っていることの裏取り（閾値を無効化すると水増しが復活する）
        val jitter = List(100) { index ->
            point(northMeters = if (index % 2 == 0) 1.5 else -1.5, eastMeters = 0.0)
        }

        val padded = WalkDistanceCalculator.distanceMeters(
            samples = samples(jitter),
            config = WalkDistanceConfig(anchorMoveMeters = 0.1),
        )

        assertTrue(padded > 200.0, "揺れだけで200m以上：素朴な積み上げの水増し（実測は $padded m）")
    }
}
