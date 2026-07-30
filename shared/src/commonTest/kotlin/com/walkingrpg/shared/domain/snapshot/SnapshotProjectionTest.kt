package com.walkingrpg.shared.domain.snapshot

import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.matching.SyntheticWalk
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 緯度経度 → 1枚の中の座標（[SnapshotProjection]）。
 *
 * 見たいのは3つ：
 * - 箱の中に収まること（＝描いた線が画像の外に出ない）
 * - **アスペクト比が守られること**（東西に細長い街が丸くならない）
 * - 潰れた入力（道1本・同じ点だけ）でも絵が成立すること
 *
 * 座標は架空の原点からのメートル（`SyntheticWalk`）で組む＝距離の意図が読める。
 */
class SnapshotProjectionTest {

    private fun way(vararg northEast: Pair<Double, Double>) = SnapshotWay(
        shape = northEast.map { (north, east) -> SyntheticWalk.point(north, east) },
        stage = GrowthStage.GRASS,
    )

    @Test
    fun 道が無ければ箱は作られない() {
        assertNull(SnapshotProjection.bounds(emptyList()))
        assertNull(SnapshotProjection.bounds(listOf(SnapshotWay(emptyList(), GrowthStage.GRASS))))
    }

    @Test
    fun 全ての点が箱の中に収まる() {
        val ways = listOf(
            way(0.0 to 0.0, 0.0 to 300.0),
            way(0.0 to 300.0, 200.0 to 300.0),
            way(200.0 to 300.0, 200.0 to -100.0),
        )
        val bounds = SnapshotProjection.bounds(ways)!!

        ways.flatMap { it.shape }.forEach { point ->
            val projected = SnapshotProjection.project(point, bounds)
            assertTrue(projected.x in 0.0..1.0, "x が外に出た: ${projected.x}")
            assertTrue(projected.y in 0.0..1.0, "y が外に出た: ${projected.y}")
        }
    }

    @Test
    fun 余白のぶんだけ端から離れる() {
        val ways = listOf(way(0.0 to 0.0, 0.0 to 400.0))
        val bounds = SnapshotProjection.bounds(ways, marginRatio = 0.1)!!

        val west = SnapshotProjection.project(SyntheticWalk.point(0.0, 0.0), bounds)
        val east = SnapshotProjection.project(SyntheticWalk.point(0.0, 400.0), bounds)

        // 余白 10%（片側）なので、東西いっぱいの道でも 0.1 手前から 0.9 手前まで
        assertTrue(west.x > 0.05, "西端が詰まっている: ${west.x}")
        assertTrue(east.x < 0.95, "東端が詰まっている: ${east.x}")
        // 中央に対して対称（片側だけ余白が付いていない、を弾く）
        assertAlmostEquals(expected = 1.0, actual = west.x + east.x, tolerance = 1e-9)
    }

    @Test
    fun 北が上になる() {
        val ways = listOf(way(0.0 to 0.0, 300.0 to 0.0))
        val bounds = SnapshotProjection.bounds(ways)!!

        val south = SnapshotProjection.project(SyntheticWalk.point(0.0, 0.0), bounds)
        val north = SnapshotProjection.project(SyntheticWalk.point(300.0, 0.0), bounds)

        assertTrue(north.y < south.y, "北の方が上（y が小さい）: north=${north.y} south=${south.y}")
    }

    @Test
    fun 東西に細長い街は縦に潰れず余白が付く() {
        // 東西 800m × 南北 100m。正方形に引き伸ばすとこの形が失われる
        val ways = listOf(
            way(0.0 to 0.0, 0.0 to 800.0),
            way(100.0 to 0.0, 100.0 to 800.0),
        )
        val bounds = SnapshotProjection.bounds(ways, marginRatio = 0.0)!!

        val southWest = SnapshotProjection.project(SyntheticWalk.point(0.0, 0.0), bounds)
        val southEast = SnapshotProjection.project(SyntheticWalk.point(0.0, 800.0), bounds)
        val northWest = SnapshotProjection.project(SyntheticWalk.point(100.0, 0.0), bounds)

        // 広い方（東西）は端から端までを使う
        assertAlmostEquals(expected = 0.0, actual = southWest.x, tolerance = 1e-6)
        assertAlmostEquals(expected = 1.0, actual = southEast.x, tolerance = 1e-6)
        // 狭い方（南北）は 100/800 ぶんだけを中央で使う＝上下に余白が残る
        assertAlmostEquals(
            expected = 100.0 / 800.0,
            actual = southWest.y - northWest.y,
            tolerance = 1e-3,
        )
        assertTrue(northWest.y > 0.3, "上に余白が無い: ${northWest.y}")
    }

    @Test
    fun 縦横の縮尺が同じなので同じ長さは同じ画素数になる() {
        // 300m の東西の道と 300m の南北の道。絵の上での長さが揃っていないと
        // 「絵の上で長い道は実際に長い」が壊れる
        val ways = listOf(
            way(0.0 to 0.0, 0.0 to 300.0),
            way(0.0 to 0.0, 300.0 to 0.0),
        )
        val bounds = SnapshotProjection.bounds(ways)!!

        val origin = SnapshotProjection.project(SyntheticWalk.point(0.0, 0.0), bounds)
        val east = SnapshotProjection.project(SyntheticWalk.point(0.0, 300.0), bounds)
        val north = SnapshotProjection.project(SyntheticWalk.point(300.0, 0.0), bounds)

        assertAlmostEquals(
            expected = east.x - origin.x,
            actual = origin.y - north.y,
            // 正距円筒なので、この規模（300m）では 0.1% も違わない
            tolerance = 1e-3,
        )
    }

    @Test
    fun `1本しかない道も潰れず中央に収まる`() {
        val bounds = SnapshotProjection.bounds(listOf(way(0.0 to 0.0, 0.0 to 40.0)))!!
        val west = SnapshotProjection.project(SyntheticWalk.point(0.0, 0.0), bounds)
        val east = SnapshotProjection.project(SyntheticWalk.point(0.0, 40.0), bounds)

        assertTrue(west.x in 0.0..1.0 && east.x in 0.0..1.0)
        // 最小の広さ（MIN_SPAN_DEGREES ≒ 55m）まで押し広げるので、40mの道は画面いっぱいにならない
        assertTrue(east.x - west.x < 0.9, "1本の道が画面いっぱいになっている: ${east.x - west.x}")
        assertAlmostEquals(expected = 0.5, actual = (west.x + east.x) / 2.0, tolerance = 1e-9)
        assertAlmostEquals(expected = 0.5, actual = west.y, tolerance = 1e-9)
    }

    @Test
    fun 同じ点だけの道でも箱が作れる() {
        // マスタが壊れて頂点が重複している場合（描画側は線として捨てるが、箱は作れる必要がある）
        val bounds = SnapshotProjection.bounds(listOf(way(0.0 to 0.0, 0.0 to 0.0)))!!
        val projected = SnapshotProjection.project(SyntheticWalk.point(0.0, 0.0), bounds)

        assertAlmostEquals(expected = 0.5, actual = projected.x, tolerance = 1e-9)
        assertAlmostEquals(expected = 0.5, actual = projected.y, tolerance = 1e-9)
    }

    @Test
    fun 潰れた箱を渡されたら中央の1点になる() {
        // bounds() は作らない形だが、外から組まれても落ちないこと
        val flat = SnapshotBounds(
            minLatitude = 35.0,
            minLongitude = 139.0,
            maxLatitude = 35.0,
            maxLongitude = 139.0,
        )
        assertEquals(NormalizedPoint(0.5, 0.5), SnapshotProjection.project(SyntheticWalk.ORIGIN, flat))
    }

    private fun assertAlmostEquals(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "期待 $expected に対して $actual（許容 $tolerance）",
        )
    }
}
