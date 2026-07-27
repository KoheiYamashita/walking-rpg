package com.walkingrpg.shared.data.osm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Overpass API（`[out:json]`）の応答。データ層に閉じるDTOで、ドメインには漏らさない。
 *
 * 応答は要素の配列1本で、種別（node / way / relation）と出力指定（`out geom` /
 * `out center`）によって座標の入り方が変わる：
 * - node → `lat` / `lon`
 * - way・relation に `out center` → `center.lat` / `center.lon`
 * - way に `out geom` → `geometry` に頂点列
 */
@Serializable
internal data class OverpassResponse(
    val elements: List<OverpassElement> = emptyList(),
    /**
     * サーバ側で問題が起きたときの注記。
     *
     * Overpassはサーバ側タイムアウト・メモリ超過でも **HTTP 200 のまま**
     * ここに理由を入れた「途中まで」の応答を返す。これを正常扱いすると
     * 欠けたマスタで作り直してしまうので、非nullなら取り込みを中断する。
     */
    val remark: String? = null,
)

@Serializable
internal data class OverpassElement(
    val type: String,
    val id: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val center: OverpassLatLon? = null,
    val geometry: List<OverpassLatLon> = emptyList(),
    val tags: Map<String, String> = emptyMap(),
)

@Serializable
internal data class OverpassLatLon(
    @SerialName("lat") val lat: Double,
    @SerialName("lon") val lon: Double,
)
