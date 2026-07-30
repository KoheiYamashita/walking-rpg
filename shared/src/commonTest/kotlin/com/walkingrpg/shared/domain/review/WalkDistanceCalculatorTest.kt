package com.walkingrpg.shared.domain.review

import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.matching.SyntheticWalk
import com.walkingrpg.shared.domain.matching.SyntheticWalk.START_MS
import com.walkingrpg.shared.domain.matching.SyntheticWalk.point
import com.walkingrpg.shared.domain.matching.SyntheticWalk.samples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「実際に歩いた距離」（[WalkDistanceCalculator]）。
 *
 * 見たいのは5つ：
 * - まっすぐ歩いた距離がそのまま出る
 * - **立ち止まっている間の揺れを積まない**（アンカー方式の存在理由）
 * - 精度の悪いサンプルが距離に混ざらない
 * - **乗り物の区間を積まない**（停止を押し忘れてバスに乗った日／速度フィルタの存在理由）
 * - 決定的・入力順非依存（architecture.md §7）
 *
 * 座標はすべて架空（[SyntheticWalk]）。
 */
class WalkDistanceCalculatorTest {

    /** 10mずつ東へ [count] 件（アンカー閾値5mをはっきり超える歩き方）。 */
    private fun walkEast(count: Int, stepMeters: Double = 10.0, fromEast: Double = 0.0) =
        List(count) { index -> point(northMeters = 0.0, eastMeters = fromEast + index * stepMeters) }

    private fun distance(points: List<GeoPoint>, accuracyMeters: Double = SyntheticWalk.GOOD_ACCURACY_M) =
        WalkDistanceCalculator.distanceMeters(
            samples(points, intervalMs = WALK_INTERVAL_MS, accuracyMeters = accuracyMeters),
        )

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
        // 到達地点（東50m）のベンチで数分。1〜2mの揺れが60件続く
        val resting = List(60) { index ->
            point(northMeters = if (index % 2 == 0) 1.5 else -1.5, eastMeters = 50.0)
        }

        assertEquals(50.0, distance(walked + resting), 1.0)
    }

    @Test
    fun 精度の悪いサンプルは距離に入らない() {
        val good = samples(walkEast(count = 6), intervalMs = WALK_INTERVAL_MS) // 0〜50m
        // 200m北に飛んだ1件（精度60m）。捨てなければ往復400mが乗る
        val bad = samples(
            points = listOf(point(northMeters = 200.0, eastMeters = 50.0)),
            startMs = START_MS + 6 * WALK_INTERVAL_MS,
            intervalMs = WALK_INTERVAL_MS,
            accuracyMeters = 60.0,
        )
        val more = samples(
            points = walkEast(count = 6, fromEast = 50.0), // 50〜100m
            startMs = START_MS + 7 * WALK_INTERVAL_MS,
            intervalMs = WALK_INTERVAL_MS,
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
        val walk = samples(walkEast(count = 30), intervalMs = WALK_INTERVAL_MS)

        val forward = WalkDistanceCalculator.distanceMeters(walk)
        assertTrue(forward > 0.0, "そもそも距離が出ていること（速度フィルタで全部落ちていない）")
        assertEquals(forward, WalkDistanceCalculator.distanceMeters(walk.reversed()))
    }

    @Test
    fun 同じ入力からは必ず同じ距離が出る() {
        val walk = samples(walkEast(count = 30), intervalMs = WALK_INTERVAL_MS)

        val first = WalkDistanceCalculator.distanceMeters(walk)
        assertTrue(first > 0.0, "そもそも距離が出ていること（速度フィルタで全部落ちていない）")
        assertEquals(first, WalkDistanceCalculator.distanceMeters(walk))
    }

    @Test
    fun アンカーを0に近づけると揺れが距離に化ける() {
        // この機構が距離を守っていることの裏取り（閾値を無効化すると水増しが復活する）
        val jitter = List(100) { index ->
            point(northMeters = if (index % 2 == 0) 1.5 else -1.5, eastMeters = 0.0)
        }

        val padded = WalkDistanceCalculator.distanceMeters(
            samples = samples(jitter, intervalMs = WALK_INTERVAL_MS),
            config = WalkDistanceConfig(anchorMoveMeters = 0.1),
        )

        assertTrue(padded > 200.0, "揺れだけで200m以上：素朴な積み上げの水増し（実測は $padded m）")
    }

    // --- 速度フィルタ（乗り物区間）------------------------------------------------

    /**
     * 途中でバスに乗った散歩（停止を押し忘れた日）。
     *
     * 歩100m → バス1000m（10 m/s・95秒）→ 歩100m。
     * バスの区間は落ち、**乗る前と降りたあとの徒歩だけ**が残る。
     * 端の10mずつが削れるのは、超過区間の両端のサンプルも乗り物側にいたとみなすため
     * （`VehicleRunFilter`。歩行を1サンプルぶん多めに削る側に倒してある）。
     */
    @Test
    fun バスに乗った区間は距離に積まれない() {
        val walkThere = samples(walkEast(count = 11), intervalMs = WALK_INTERVAL_MS) // 東0〜100m
        val bus = samples(
            points = walkEast(count = 20, stepMeters = 50.0, fromEast = 150.0), // 東150〜1100m
            startMs = START_MS + 11 * WALK_INTERVAL_MS,
            intervalMs = WALK_INTERVAL_MS,
        )
        val walkBack = samples(
            points = walkEast(count = 11, fromEast = 1_110.0), // 東1110〜1210m
            startMs = START_MS + 31 * WALK_INTERVAL_MS,
            intervalMs = WALK_INTERVAL_MS,
        )

        val walked = WalkDistanceCalculator.distanceMeters(walkThere + bus + walkBack)

        assertEquals(190.0, walked, 1.0, "徒歩90m + 徒歩100m。バスの1000mは入らない")

        // 速度フィルタを実質無効にすると乗車ぶんが乗る＝落としているのはこのフィルタ
        val withoutSpeedFilter = WalkDistanceCalculator.distanceMeters(
            samples = walkThere + bus + walkBack,
            config = WalkDistanceConfig(maxWalkingSpeedMps = 1_000.0),
        )
        assertTrue(withoutSpeedFilter > 1_200.0, "フィルタなしなら1.2km超（実測は $withoutSpeedFilter m）")
    }

    /**
     * GPSが1件だけ飛んだ日。乗り物ではないので、歩いたぶんを削ってはいけない。
     *
     * 削りすぎは水増しと同じくらい嘘になる（横断歩道の測位飛びで毎回数十m減る）。
     * だから `VehicleRunFilter` は「超過が [WalkDistanceConfig.vehicleMinDurationMs] 続いたら」
     * を条件にしている。
     */
    @Test
    fun 一瞬の測位飛びでは歩いた距離を削らない() {
        val before = samples(walkEast(count = 11), intervalMs = WALK_INTERVAL_MS) // 東0〜100m
        // 精度は良いのに40m北へ飛んだ1件（＝8 m/s だが5秒しか続かない）
        val spike = samples(
            points = listOf(point(northMeters = 40.0, eastMeters = 100.0)),
            startMs = START_MS + 11 * WALK_INTERVAL_MS,
            intervalMs = WALK_INTERVAL_MS,
        )
        val after = samples(
            points = walkEast(count = 5, fromEast = 110.0), // 東110〜150m
            startMs = START_MS + 12 * WALK_INTERVAL_MS,
            intervalMs = WALK_INTERVAL_MS,
        )
        val walkedOnly = 150.0 // 飛びが無ければ東0〜150m

        val kept = WalkDistanceCalculator.distanceMeters(before + spike + after)

        assertTrue(kept >= walkedOnly, "歩いた150mは丸ごと残る（実測は $kept m）")

        // 「一定時間続いたら」を外すと、飛びの前後にいた徒歩サンプルまで落ちて距離が目減りする
        val overCut = WalkDistanceCalculator.distanceMeters(
            samples = before + spike + after,
            config = WalkDistanceConfig(vehicleMinDurationMs = 0L),
        )
        assertTrue(overCut < walkedOnly, "継続条件が無いと削りすぎる（実測は $overCut m）")
    }

    @Test
    fun 全部が乗り物なら距離は0() {
        // 50m × 5秒 ＝ 10 m/s を200秒ぶん（＝電車・バス）
        val ride = samples(
            points = walkEast(count = 40, stepMeters = 50.0),
            intervalMs = WALK_INTERVAL_MS,
        )

        assertEquals(0.0, WalkDistanceCalculator.distanceMeters(ride))
    }

    @Test
    fun 乗り物区間をまたぐ移動は距離にならない() {
        // 徒歩を挟まず「歩く → 1km先へワープ（バス）→ 歩く」だけを見る。
        // 乗り物のサンプルを平坦に詰めて捨てると、乗る前と降りたあとが隣り合って
        // 落としたはずの1kmが1区間ぶんとして復活する（塊ごとに足している理由）。
        val walked = WalkDistanceCalculator.distanceMeters(
            samples(walkEast(count = 6), intervalMs = WALK_INTERVAL_MS) +
                samples(
                    points = walkEast(count = 12, stepMeters = 50.0, fromEast = 100.0),
                    startMs = START_MS + 6 * WALK_INTERVAL_MS,
                    intervalMs = WALK_INTERVAL_MS,
                ) +
                samples(
                    points = walkEast(count = 6, fromEast = 660.0),
                    startMs = START_MS + 18 * WALK_INTERVAL_MS,
                    intervalMs = WALK_INTERVAL_MS,
                ),
        )

        assertTrue(walked < 120.0, "残るのは徒歩ぶんだけ（実測は $walked m）")
    }

    private companion object {
        /**
         * サンプル間隔。5秒 × 1サンプル10m ＝ 2.0 m/s（早歩き）。
         *
         * 速度フィルタ（[WalkDistanceConfig.maxWalkingSpeedMps] ＝ 3.0 m/s）が入ったので、
         * 「10mずつ動く」だけでは足りず**歩ける速さ**で動かす必要がある。
         */
        const val WALK_INTERVAL_MS: Long = 5_000L
    }
}
