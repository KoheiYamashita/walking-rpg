package com.walkingrpg.shared.domain.osm

import com.walkingrpg.shared.domain.map.GeoPoint
import kotlin.math.PI
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
}
