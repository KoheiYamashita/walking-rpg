package com.walkingrpg.shared.data.osm

import com.walkingrpg.shared.domain.map.GeoPoint

/**
 * `way.geometry` 列のシリアライズ形式（`lat,lon;lat,lon;...`）。
 *
 * ドメインモデル⇄DBスキーマの変換はデータ層に閉じる（architecture.md §2）ので、
 * 形式を知っているのはこのファイルだけ。値は `Double.toString()` の表現を
 * そのまま使うため、往復しても座標は変わらない。
 */
internal object WayGeometryCodec {

    private const val POINT_SEPARATOR = ';'
    private const val COORDINATE_SEPARATOR = ','

    fun encode(points: List<GeoPoint>): String =
        points.joinToString(POINT_SEPARATOR.toString()) { point ->
            "${point.latitude}$COORDINATE_SEPARATOR${point.longitude}"
        }

    /** 壊れた点は黙って捨てる（マスタは再取得できるので、1点の欠損で読み出し全体を落とさない）。 */
    fun decode(encoded: String): List<GeoPoint> =
        encoded.split(POINT_SEPARATOR).mapNotNull { chunk ->
            val parts = chunk.split(COORDINATE_SEPARATOR)
            if (parts.size != 2) return@mapNotNull null
            val latitude = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val longitude = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            GeoPoint(latitude = latitude, longitude = longitude)
        }
}
