package com.walkingrpg.shared.domain.snapshot

import com.walkingrpg.shared.domain.map.GeoPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/**
 * 緯度経度 → 正方形1枚の中の正規化座標（[0,1]×[0,1]）への写像。純関数。
 *
 * ## なぜ地図SDKに頼らないのか
 * 描くのは抽象レイヤーだけ（[SnapshotScene] のKDoc）なので、必要なのは
 * 「箱の中のどこか」を出す写像1つだけで、タイル座標もズームレベルも要らない。
 * MapLibreのオフスクリーンレンダリング（issue #17 の実装方式の注意）を使わないので、
 * この変換が Android / iOS で1本に揃う＝どちらの端末でも同じ絵が出る。
 *
 * ## 図法は `GeoDistance` と同じ正距円筒
 * 経度方向を `cos(緯度)` で縮める局所平面（`GeoDistance.distanceToSegmentMeters` と同じ式）。
 * 扱う範囲は対象圏（500m〜1km。design.md §9）から、行動圏が広がっても数km四方なので、
 * メルカトルとの差は絵にすると1px 以下。距離計算とここで図法を揃えておくと、
 * 「絵の上で長い道は実際に長い」が成り立つ。
 *
 * ## アスペクト比を守る
 * 縦横のうち**広い方**を基準に正規化して、狭い方を中央に寄せる。箱を正方形に引き伸ばすと
 * 東西に細長い街が丸く見える＝「その月の街の形」が嘘になる。結果として、
 * 狭い方の座標は [0,1] の内側（両端に余白）に収まる。
 */
object SnapshotProjection {

    /** 箱の外周に足す余白の割合（片側あたり、箱の広い方の辺に対する比）。 */
    const val DEFAULT_MARGIN_RATIO: Double = 0.06

    /**
     * 道が1本（あるいは1点に潰れた道）しか無い月でも絵が成立するための最小の広さ（度）。
     *
     * 緯度1度 ≒ 111km なので 0.0005度 ≒ 55m。「家の前を1本だけ歩いた月」が
     * 画面いっぱいの1本線にならず、街の一部として小さく写る程度の広さ。
     */
    const val MIN_SPAN_DEGREES: Double = 0.0005

    /**
     * 全ての道が入る箱（余白込み）。道が1本も無い・形が空なら `null`。
     *
     * 余白は「広い方の辺 × [marginRatio]」を緯度経度の**両方**に同じ量だけ足す
     * （経度側は `cos(緯度)` で割り戻して、絵の上での余白が上下左右で等しくなるようにする）。
     */
    fun bounds(
        ways: List<SnapshotWay>,
        marginRatio: Double = DEFAULT_MARGIN_RATIO,
    ): SnapshotBounds? {
        require(marginRatio >= 0.0) { "marginRatio は0以上" }

        var minLatitude = Double.POSITIVE_INFINITY
        var minLongitude = Double.POSITIVE_INFINITY
        var maxLatitude = Double.NEGATIVE_INFINITY
        var maxLongitude = Double.NEGATIVE_INFINITY

        for (way in ways) {
            for (point in way.shape) {
                if (point.latitude < minLatitude) minLatitude = point.latitude
                if (point.latitude > maxLatitude) maxLatitude = point.latitude
                if (point.longitude < minLongitude) minLongitude = point.longitude
                if (point.longitude > maxLongitude) maxLongitude = point.longitude
            }
        }
        if (minLatitude > maxLatitude) return null

        val centerLatitude = (minLatitude + maxLatitude) / 2.0
        val scaleX = longitudeScale(centerLatitude)

        // 潰れた辺を最小の広さまで押し広げる（中心は動かさない）。
        val latitudePad = max(0.0, MIN_SPAN_DEGREES - (maxLatitude - minLatitude)) / 2.0
        val longitudePad =
            max(0.0, MIN_SPAN_DEGREES - (maxLongitude - minLongitude) * scaleX) / scaleX / 2.0

        // 余白は「絵の上での等距離」。経度側は縮んでいるぶんを割り戻す。
        val spanY = (maxLatitude - minLatitude) + latitudePad * 2.0
        val spanX = ((maxLongitude - minLongitude) + longitudePad * 2.0) * scaleX
        val margin = max(spanX, spanY) * marginRatio

        return SnapshotBounds(
            minLatitude = minLatitude - latitudePad - margin,
            minLongitude = minLongitude - longitudePad - margin / scaleX,
            maxLatitude = maxLatitude + latitudePad + margin,
            maxLongitude = maxLongitude + longitudePad + margin / scaleX,
        )
    }

    /**
     * [point] を [bounds] の中の正規化座標にする。
     *
     * 原点は**左上**（`y = 0` が北）。画像・描画APIの座標系がどれも左上原点で、
     * ここで上下を合わせておかないとレンダラ側が毎回反転を書くことになる。
     *
     * 箱の外の点は [0,1] の外に出る（クランプしない）：描画側が線を切るかどうかを
     * 決められるようにしておく。箱は全ての道を包むように作るので、通常は起きない。
     */
    fun project(point: GeoPoint, bounds: SnapshotBounds): NormalizedPoint {
        val scaleX = longitudeScale(bounds.centerLatitude)
        val spanY = bounds.maxLatitude - bounds.minLatitude
        val spanX = (bounds.maxLongitude - bounds.minLongitude) * scaleX
        val extent = max(spanX, spanY)
        // 完全に潰れた箱（bounds() は作らないが、外から渡されうる）は中央の1点に写す
        if (extent <= 0.0) return NormalizedPoint(x = 0.5, y = 0.5)

        val offsetX = (point.longitude - bounds.centerLongitude) * scaleX / extent
        val offsetY = (point.latitude - bounds.centerLatitude) / extent
        return NormalizedPoint(x = 0.5 + offsetX, y = 0.5 - offsetY)
    }

    /**
     * 経度1度が緯度1度の何倍の長さになるか（正距円筒の東西スケール）。
     *
     * 極付近で0に潰れて0除算になるのを避けるため、下限を置く
     * （生活圏の緯度では効かない。緯度89度でも 0.017 なので下限には当たらない）。
     */
    private fun longitudeScale(latitude: Double): Double =
        max(MIN_LONGITUDE_SCALE, abs(cos(latitude * PI / 180.0)))

    private const val MIN_LONGITUDE_SCALE = 1e-6
}

/**
 * 正規化座標。左上が `(0, 0)`、右下が `(1, 1)`。
 *
 * ピクセルに直すのは描画側（解像度を知っているのはUI層だけ）。
 */
data class NormalizedPoint(val x: Double, val y: Double)
