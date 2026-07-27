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
