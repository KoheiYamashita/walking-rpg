package com.walkingrpg.shared.domain.matching

import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.osm.GeoDistance
import com.walkingrpg.shared.domain.walk.LocationSample

/**
 * 速度フィルタ＝「歩いていない区間」の切り出し（design.md §9「移動手段判定」）。
 *
 * 連続する2サンプルの区間ごとに見かけの速度を出し、超過が
 * [MapMatchingConfig.vehicleMinDurationMs] 以上続いた区間のサンプルを丸ごと落とす。
 * 1区間だけの超過を落とさないのは、それがGPSの飛び（誤測位）の形だから。
 * 判定は「歩いた区間か」だけで、電車かバスか自転車かは区別しない
 * （design.md §11「移動手段判定＝速度フィルタのみ」）。
 *
 * ## なぜ独立した純関数なのか
 * 同じ規則を **map matching（[MapMatcher.match]）と距離
 * （[com.walkingrpg.shared.domain.review.WalkDistanceCalculator]）の両方**が使うから。
 * 散歩の停止を押し忘れてバスに乗ると、道は塗られないのに距離だけ伸びる——という
 * 食い違いは、判定を片方に埋め込んでいると必ず起きる。閾値も判定も1か所に置いて、
 * 「通過にならなかった移動は距離にもならない」を構造で保証する。
 *
 * 決定的・冪等：現在時刻も乱数も読まない。ただし入力は**時刻の昇順**である前提
 * （速度は隣り合うサンプルの差から出すので、並べ替えは呼び出し側の責任）。
 */
internal object VehicleRunFilter {

    /**
     * 乗り物区間を落とし、残った歩行サンプルを**連続した塊ごと**に返す。
     *
     * 塊に分けて返すのは、距離側が乗り物区間をまたいで足さないようにするため。
     * 平坦なリストにしてしまうと「バス停で降りた地点」と「乗った地点」が隣り合い、
     * 落としたはずの乗車距離がその1区間ぶんとして復活する。
     *
     * @param samples 時刻の昇順に並んでいること。空なら空。
     * @return 歩行区間の列（時刻順）。除外が無ければ [samples] 1本ぶんだけの列。
     */
    fun walkingSegments(
        samples: List<LocationSample>,
        maxWalkingSpeedMps: Double,
        vehicleMinDurationMs: Long,
    ): List<List<LocationSample>> {
        if (samples.isEmpty()) return emptyList()
        if (samples.size < 2) return listOf(samples)

        val excluded = excludedFlags(samples, maxWalkingSpeedMps, vehicleMinDurationMs)

        val segments = mutableListOf<List<LocationSample>>()
        var from = 0
        while (from < samples.size) {
            if (excluded[from]) {
                from++
                continue
            }
            var to = from
            while (to + 1 < samples.size && !excluded[to + 1]) to++
            segments += samples.subList(from, to + 1)
            from = to + 1
        }
        return segments
    }

    /**
     * 乗り物区間を落としたサンプル列（塊の切れ目は保たない平坦なリスト）。
     *
     * 切れ目を要らないのは、通過の切り方が時刻の間隔で決まる側
     * （[PassageBoundary]）だけ。距離は [walkingSegments] を使うこと。
     */
    fun dropVehicleRuns(
        samples: List<LocationSample>,
        maxWalkingSpeedMps: Double,
        vehicleMinDurationMs: Long,
    ): List<LocationSample> =
        walkingSegments(samples, maxWalkingSpeedMps, vehicleMinDurationMs).flatten()

    /** `[index] = true` なら乗り物区間のサンプル。 */
    private fun excludedFlags(
        samples: List<LocationSample>,
        maxWalkingSpeedMps: Double,
        vehicleMinDurationMs: Long,
    ): BooleanArray {
        // interval[i] は samples[i] と samples[i + 1] の間
        val isFast = BooleanArray(samples.size - 1) { index ->
            val from = samples[index]
            val to = samples[index + 1]
            val elapsedMs = to.timestampMs - from.timestampMs
            // 同時刻・時刻逆転のサンプルからは速度が出せない（0除算・負の時間）。
            // 端末が同じ時刻で複数返すことは実際にあるので、落とさず「速くない」と見る。
            if (elapsedMs <= 0L) {
                false
            } else {
                val meters = GeoDistance.distanceMeters(from.toGeoPoint(), to.toGeoPoint())
                meters / (elapsedMs / 1000.0) > maxWalkingSpeedMps
            }
        }

        val excluded = BooleanArray(samples.size)
        var index = 0
        while (index < isFast.size) {
            if (!isFast[index]) {
                index++
                continue
            }
            var end = index
            while (end + 1 < isFast.size && isFast[end + 1]) end++
            val durationMs = samples[end + 1].timestampMs - samples[index].timestampMs
            if (durationMs >= vehicleMinDurationMs) {
                // 区間の両端のサンプルも乗り物側にいたので一緒に落とす
                for (target in index..end + 1) excluded[target] = true
            }
            index = end + 1
        }
        return excluded
    }

    private fun LocationSample.toGeoPoint(): GeoPoint =
        GeoPoint(latitude = latitude, longitude = longitude)
}
