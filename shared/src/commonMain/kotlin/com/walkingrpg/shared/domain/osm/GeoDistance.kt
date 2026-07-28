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

    /**
     * 点と線分の最短距離（メートル）。map matching のスナップ距離に使う。
     *
     * 線分の端より外に落ちる場合は近い方の端点までの距離になる（線分であって直線ではない）。
     *
     * 計算は [point] を原点に取った局所平面（正距円筒図法）で行う：
     * 球面上で「線分への垂線の足」を厳密に解くのは大円の交差計算になって重いが、
     * 扱う距離はスナップ上限（数十m）とwayの1セグメント（長くても数百m）なので、
     * 緯度差による経度スケールの変化は無視できる。この規模では誤差はmm〜cm。
     */
    fun distanceToSegmentMeters(point: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
        // 原点（point）から見た端点の平面座標。x = 東方向、y = 北方向（メートル）。
        val scaleX = cos(point.latitude.toRadians()) * EARTH_RADIUS_METERS * PI / 180.0
        val scaleY = EARTH_RADIUS_METERS * PI / 180.0
        val startX = (start.longitude - point.longitude) * scaleX
        val startY = (start.latitude - point.latitude) * scaleY
        val endX = (end.longitude - point.longitude) * scaleX
        val endY = (end.latitude - point.latitude) * scaleY

        val deltaX = endX - startX
        val deltaY = endY - startY
        val lengthSquared = deltaX * deltaX + deltaY * deltaY
        // 長さ0のセグメント（同一頂点が連続するway）は点として扱う
        if (lengthSquared == 0.0) return distanceMeters(point, start)

        // 原点を線分へ射影した位置（0..1にクランプ＝線分の外へは出さない）
        val t = ((-startX * deltaX - startY * deltaY) / lengthSquared).coerceIn(0.0, 1.0)
        val footX = startX + t * deltaX
        val footY = startY + t * deltaY
        return sqrt(footX * footX + footY * footY)
    }

    /**
     * 点と折れ線の最短距離（メートル）。点が1つ以下の折れ線は
     * [Double.POSITIVE_INFINITY]（＝スナップ先にならない）。
     */
    fun distanceToPathMeters(point: GeoPoint, path: List<GeoPoint>): Double {
        if (path.size < 2) return Double.POSITIVE_INFINITY
        var best = Double.POSITIVE_INFINITY
        for (index in 1 until path.size) {
            val distance = distanceToSegmentMeters(point, path[index - 1], path[index])
            if (distance < best) best = distance
        }
        return best
    }

    private fun haversine(radians: Double): Double = sin(radians / 2.0).let { it * it }

    private fun Double.toRadians(): Double = this * PI / 180.0
}
