package com.walkingrpg.shared.domain.matching

import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.osm.GeoDistance
import com.walkingrpg.shared.domain.osm.Way
import com.walkingrpg.shared.domain.walk.LocationSample
import kotlin.math.PI
import kotlin.math.cos

/**
 * 合成データ用のヘルパー。
 *
 * 座標をそのまま書くと「12m離れた平行な道」のような**距離の意図**が読めなくなるので、
 * 原点からのメートル（北・東）で地物とサンプルを組み立てる。原点は架空の地点。
 */
internal object SyntheticWalk {

    /** 架空の原点（実在の自宅・対象圏とは無関係）。 */
    val ORIGIN: GeoPoint = GeoPoint(latitude = 35.700000, longitude = 139.700000)

    const val SESSION_ID: Long = 42L
    const val START_MS: Long = 1_767_225_600_000L
    const val INTERVAL_MS: Long = 2_000L

    /** 一般的なGPS精度（design.md §9「5〜15m」）。閾値25mを下回る。 */
    const val GOOD_ACCURACY_M: Double = 8.0

    private const val METERS_PER_DEGREE_LATITUDE = GeoDistance.EARTH_RADIUS_METERS * PI / 180.0

    /** 原点から北[northMeters]・東[eastMeters]の地点。 */
    fun point(northMeters: Double, eastMeters: Double): GeoPoint {
        val metersPerDegreeLongitude = METERS_PER_DEGREE_LATITUDE * cos(ORIGIN.latitude * PI / 180.0)
        return GeoPoint(
            latitude = ORIGIN.latitude + northMeters / METERS_PER_DEGREE_LATITUDE,
            longitude = ORIGIN.longitude + eastMeters / metersPerDegreeLongitude,
        )
    }

    /** 2点を結ぶ直線のway。 */
    fun straightWay(id: Long, from: GeoPoint, to: GeoPoint): Way = Way(
        id = id,
        name = null,
        highway = "residential",
        geometry = listOf(from, to),
        lengthMeters = GeoDistance.distanceMeters(from, to),
    )

    /** 東西の道（北位置 [northMeters]、東 [fromEast]〜[toEast]）。 */
    fun eastWestWay(id: Long, northMeters: Double, fromEast: Double, toEast: Double): Way =
        straightWay(id, point(northMeters, fromEast), point(northMeters, toEast))

    /** 南北の道（東位置 [eastMeters]、北 [fromNorth]〜[toNorth]）。 */
    fun northSouthWay(id: Long, eastMeters: Double, fromNorth: Double, toNorth: Double): Way =
        straightWay(id, point(fromNorth, eastMeters), point(toNorth, eastMeters))

    /**
     * 位置の列を等間隔のサンプル列にする。
     * 時刻は [START_MS] から [INTERVAL_MS] ずつ。精度は既定で [GOOD_ACCURACY_M]。
     */
    fun samples(
        points: List<GeoPoint>,
        startMs: Long = START_MS,
        intervalMs: Long = INTERVAL_MS,
        accuracyMeters: Double = GOOD_ACCURACY_M,
    ): List<LocationSample> = points.mapIndexed { index, point ->
        LocationSample(
            sessionId = SESSION_ID,
            timestampMs = startMs + index * intervalMs,
            latitude = point.latitude,
            longitude = point.longitude,
            accuracyMeters = accuracyMeters,
        )
    }
}
