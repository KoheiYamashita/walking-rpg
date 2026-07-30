package com.walkingrpg.shared.domain.review

import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.osm.GeoDistance
import com.walkingrpg.shared.domain.walk.LocationSample

/**
 * 「実際に歩いた距離」を測位サンプル列から出す純関数（design.md §4.5）。
 *
 * ## なぜway長の合算をやめたのか
 * 以前は「そのセッションで通ったwayの長さの合計」を距離としていた。これは
 * **少しでも触れた道を全長ぶん数える**ので、実測（19.2分・1078サンプル）では
 * 実移動 約1.45km に対して 2.55km と、およそ2倍に膨らんだ。
 * 交差点で数mだけかすめた長い通りが1本ぶん丸ごと乗るのが主因で、
 * 閾値をどう触っても構造的に直らない（design.md §11 の決定事項ログ）。
 *
 * way長の合算は「どれだけの街を塗ったか」の物差しとしては正しいが、
 * 「今日どれだけ歩いたか」ではない。振り返り・月次スナップショットが答えるのは後者なので、
 * 距離は軌跡そのものから出す。
 *
 * ## ジッタを積まないための2段
 * 1. **精度フィルタ**：[WalkDistanceConfig.maxAccuracyMeters] より誤差の大きいサンプルは捨てる
 * 2. **アンカー方式**：直前の**アンカー**から [WalkDistanceConfig.anchorMoveMeters] 以上
 *    離れたサンプルだけを採用し、そのぶんを足してアンカーを移す
 *
 * 2が肝。隣り合うサンプル間の距離を素朴に足すと、信号待ちで立ち止まっている20分ぶんの
 * GPSの揺れ（1件あたり数m）がそのまま距離になる。アンカー方式は「動いた」と言える量を
 * 超えるまで何も足さないので、停止中は距離が増えない。
 *
 * ## 決定的・冪等
 * 現在時刻も乱数も読まない。入力の並び順にも依存しない（内部で時刻順に並べ替える）ので、
 * 同じセッションの距離を何度出しても同じ数字になる
 * （`MapMatcher` と同じ原則＝architecture.md §4「導出は再計算できること」）。
 */
object WalkDistanceCalculator {

    /**
     * [samples] の軌跡から実移動距離（メートル）を出す。
     *
     * @param samples 時刻順でなくてもよい（内部で並べ替える）。空なら 0.0。
     */
    fun distanceMeters(
        samples: List<LocationSample>,
        config: WalkDistanceConfig = WalkDistanceConfig.DEFAULT,
    ): Double {
        // 同時刻のサンプル（端末は実際に複数返す）で並びがぶれないよう、
        // MapMatcher と同じ「時刻 → 緯度 → 経度」の安定した順に揃える。
        val ordered = samples
            .filter { it.accuracyMeters <= config.maxAccuracyMeters }
            .sortedWith(compareBy({ it.timestampMs }, { it.latitude }, { it.longitude }))
        if (ordered.size < 2) return 0.0

        var anchor = ordered.first().toGeoPoint()
        var total = 0.0
        for (index in 1 until ordered.size) {
            val point = ordered[index].toGeoPoint()
            val moved = GeoDistance.distanceMeters(anchor, point)
            // アンカーから動ききっていないサンプルは「揺れ」とみなして捨てる。
            // アンカーを更新しないので、揺れが続くかぎり距離は増えない。
            if (moved < config.anchorMoveMeters) continue
            total += moved
            anchor = point
        }
        return total
    }

    private fun LocationSample.toGeoPoint(): GeoPoint =
        GeoPoint(latitude = latitude, longitude = longitude)
}
