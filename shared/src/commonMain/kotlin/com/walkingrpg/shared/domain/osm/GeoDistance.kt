package com.walkingrpg.shared.domain.osm

import com.walkingrpg.shared.domain.map.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 球面上の距離計算（ハーバサイン）。
 *
 * way長・「何m歩いたか」・後続の map matching が全部これに乗るので、
 * ドメイン層の純関数として1箇所に置く。楕円体（Vincenty等）は使わない：
 * 数百m〜数kmの散歩の距離では誤差0.5%未満で、実装コストに見合わない。
 */
object GeoDistance {

    /** 地球の平均半径（メートル）。WGS84の平均半径 R1。 */
    const val EARTH_RADIUS_METERS: Double = 6_371_008.8

    /** 2点間の大円距離（メートル）。 */
    fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = from.latitude.toRadians()
        val lat2 = to.latitude.toRadians()
        val deltaLat = (to.latitude - from.latitude).toRadians()
        val deltaLon = (to.longitude - from.longitude).toRadians()

        val h = haversine(deltaLat) + cos(lat1) * cos(lat2) * haversine(deltaLon)
        // 丸め誤差で 1 をわずかに超えると asin が NaN になるので抑える
        return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(min(1.0, h)))
    }

    /**
     * 折れ線の全長（メートル）。点が1つ以下なら 0。
     * OSMのwayは頂点列なので、隣接点の距離を足し上げるだけでよい。
     */
    fun pathLengthMeters(points: List<GeoPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (index in 1 until points.size) {
            total += distanceMeters(points[index - 1], points[index])
        }
        return total
    }

    private fun haversine(radians: Double): Double = sin(radians / 2.0).let { it * it }

    private fun Double.toRadians(): Double = this * PI / 180.0
}
