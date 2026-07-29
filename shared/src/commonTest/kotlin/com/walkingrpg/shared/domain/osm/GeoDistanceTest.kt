package com.walkingrpg.shared.domain.osm

import com.walkingrpg.shared.domain.map.GeoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * way長の算出（ハーバサイン）の検証。
 * 座標はすべて架空（実在の場所はリポジトリに置かない）。
 */
class GeoDistanceTest {

    /** 赤道上の経度1度 = 大円の1/360。ハーバサインの絶対値がずれていないかの基準。 */
    private val oneDegreeMeters = 2.0 * PI * GeoDistance.EARTH_RADIUS_METERS / 360.0

    @Test
    fun 赤道上の経度1度は大円の360分の1になる() {
        val distance = GeoDistance.distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))

        assertEquals(oneDegreeMeters, distance, absoluteTolerance = 0.5)
    }

    @Test
    fun 緯度1度はどの経度でも同じ長さになる() {
        val atZero = GeoDistance.distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0))
        val atFar = GeoDistance.distanceMeters(GeoPoint(40.0, 120.0), GeoPoint(41.0, 120.0))

        assertEquals(oneDegreeMeters, atZero, absoluteTolerance = 0.5)
        assertEquals(oneDegreeMeters, atFar, absoluteTolerance = 0.5)
    }

    @Test
    fun 高緯度では経度1度が短くなる() {
        val atEquator = GeoDistance.distanceMeters(GeoPoint(0.0, 10.0), GeoPoint(0.0, 11.0))
        val atSixty = GeoDistance.distanceMeters(GeoPoint(60.0, 10.0), GeoPoint(60.0, 11.0))

        // cos(60°) = 0.5
        assertEquals(atEquator / 2.0, atSixty, absoluteTolerance = 1.0)
    }

    @Test
    fun 同一地点の距離はゼロ() {
        val point = GeoPoint(12.34, 56.78)

        assertEquals(0.0, GeoDistance.distanceMeters(point, point))
    }

    @Test
    fun 距離は向きによらない() {
        val from = GeoPoint(12.3400, 56.7800)
        val to = GeoPoint(12.3405, 56.7810)

        assertEquals(
            GeoDistance.distanceMeters(from, to),
            GeoDistance.distanceMeters(to, from),
            absoluteTolerance = 1e-9,
        )
    }

    @Test
    fun 折れ線の全長は各区間の和になる() {
        val points = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 0.001),
            GeoPoint(0.001, 0.001),
        )

        val expected = GeoDistance.distanceMeters(points[0], points[1]) +
            GeoDistance.distanceMeters(points[1], points[2])

        assertEquals(expected, GeoDistance.pathLengthMeters(points), absoluteTolerance = 1e-9)
        // 赤道付近の0.001度はおよそ111m。桁がずれていないことも見る
        assertTrue(GeoDistance.pathLengthMeters(points) in 200.0..250.0)
    }

    @Test
    fun 点が足りない折れ線の全長はゼロ() {
        assertEquals(0.0, GeoDistance.pathLengthMeters(emptyList()))
        assertEquals(0.0, GeoDistance.pathLengthMeters(listOf(GeoPoint(12.34, 56.78))))
    }

    // --- 点と線分の距離（map matching のスナップに使う） ---

    /** 緯度1度ぶんのメートル。テストの距離をメートルで書くための換算。 */
    private val metersPerDegreeLatitude = oneDegreeMeters

    private fun offset(base: GeoPoint, northMeters: Double, eastMeters: Double): GeoPoint = GeoPoint(
        latitude = base.latitude + northMeters / metersPerDegreeLatitude,
        // 経度1度の長さは緯度によって cos 倍に縮む
        longitude = base.longitude + eastMeters / (metersPerDegreeLatitude * cos(base.latitude * PI / 180.0)),
    )

    @Test
    fun 線分の真横の距離は垂線の長さになる() {
        val start = GeoPoint(0.0, 0.0)
        val end = offset(start, northMeters = 0.0, eastMeters = 100.0)
        val point = offset(start, northMeters = 10.0, eastMeters = 50.0)

        assertEquals(10.0, GeoDistance.distanceToSegmentMeters(point, start, end), absoluteTolerance = 0.05)
    }

    @Test
    fun 線分の外側では端点までの距離になる() {
        val start = GeoPoint(0.0, 0.0)
        val end = offset(start, northMeters = 0.0, eastMeters = 100.0)
        // 線分の東端よりさらに30m東（直線への垂線ではなく端点までの距離）
        val point = offset(start, northMeters = 0.0, eastMeters = 130.0)

        assertEquals(30.0, GeoDistance.distanceToSegmentMeters(point, start, end), absoluteTolerance = 0.05)
    }

    @Test
    fun 長さゼロの線分は点として扱われる() {
        val start = GeoPoint(35.0, 139.0)
        val point = offset(start, northMeters = 5.0, eastMeters = 0.0)

        assertEquals(5.0, GeoDistance.distanceToSegmentMeters(point, start, start), absoluteTolerance = 0.05)
    }

    @Test
    fun 折れ線の距離は最も近い区間の距離になる() {
        val corner = GeoPoint(35.0, 139.0)
        val path = listOf(
            offset(corner, northMeters = 0.0, eastMeters = -100.0),
            corner,
            offset(corner, northMeters = 100.0, eastMeters = 0.0),
        )
        // 角から北へ50m・東へ8m（＝南北の区間に8m）
        val point = offset(corner, northMeters = 50.0, eastMeters = 8.0)

        assertEquals(8.0, GeoDistance.distanceToPathMeters(point, path), absoluteTolerance = 0.05)
    }

    @Test
    fun 点が足りない折れ線にはスナップできない() {
        val point = GeoPoint(35.0, 139.0)

        assertEquals(Double.POSITIVE_INFINITY, GeoDistance.distanceToPathMeters(point, emptyList()))
        assertEquals(Double.POSITIVE_INFINITY, GeoDistance.distanceToPathMeters(point, listOf(point)))
    }
}
