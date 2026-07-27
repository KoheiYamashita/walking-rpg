package com.walkingrpg.shared.domain.osm

import com.walkingrpg.shared.domain.map.GeoPoint

/**
 * OSM取り込みのドメインモデル（architecture.md §4「マスタ」）。
 *
 * 純Kotlin。Overpass APIのJSON形式もSQLDelightの行型もここには現れない
 * （変換はどちらもデータ層の仕事）。
 */

/**
 * 取り込み対象の highway 値（design.md §9「歩行対象」）。
 *
 * `service`（駐車場・敷地内通路）は監査で最多だが「歩く道」ではないので入れない。
 * Overpassのクエリ生成もこの集合から作る＝対象の定義はここ1箇所。
 */
val WALKABLE_HIGHWAY_VALUES: Set<String> = setOf(
    "residential",
    "footway",
    "unclassified",
    "pedestrian",
    "path",
)

/**
 * 図鑑・シナリオが使うPOIの分類（design.md §8「図鑑カテゴリ体系」）。
 *
 * 4本柱（公園・水辺・鉄道・農地）＋市街・樹木・寺社・公共・ランドマーク。
 * ここに当てはまらない地物はそもそも取り込まない（分類できないものは素材にならない）。
 */
enum class PoiKind {
    PARK,
    WATER,
    RAILWAY,
    FARMLAND,
    SHRINE,
    PUBLIC,
    SHOP,
    TREE,
    LANDMARK,
}

/**
 * Overpassから取れた生のway1本（安全・対象の判定前）。
 *
 * @param access `access` タグの値。私有地内の通路を弾くために使う（[PoiSafetyFilter]）。
 */
data class OsmWayCandidate(
    val id: Long,
    val name: String?,
    val highway: String,
    val access: String? = null,
    val geometry: List<GeoPoint>,
)

/**
 * Overpassから取れた生の地物1件（安全・分類の判定前）。
 *
 * @param id OSMの要素種別込みの識別子（`node/1` 形式）。node / way / relation で
 *  ID空間が別なので、数値IDだけでは一意にならない。
 */
data class OsmPoiCandidate(
    val id: String,
    val tags: Map<String, String>,
    val location: GeoPoint,
)

/** 対象圏1回ぶんの取得結果（判定前）。 */
data class OsmAreaSnapshot(
    val ways: List<OsmWayCandidate> = emptyList(),
    val pois: List<OsmPoiCandidate> = emptyList(),
)

/** マスタに入るway（architecture.md §4 `way(id, name?, highway, geometry, length_m)`）。 */
data class Way(
    val id: Long,
    val name: String?,
    val highway: String,
    val geometry: List<GeoPoint>,
    val lengthMeters: Double,
)

/**
 * マスタに入るPOI（architecture.md §4 `poi(id, kind, tags, lat, lon)`）。
 * 安全フィルタ（design.md §6）を通ったものだけがこの型になる。
 */
data class Poi(
    val id: String,
    val kind: PoiKind,
    val tags: Map<String, String>,
    val location: GeoPoint,
)

/** 対象圏（中心＋半径）。 */
data class OsmArea(
    val center: GeoPoint,
    val radiusMeters: Int,
)

/**
 * 対象圏の中心（＝現在地）が取れないとき。
 *
 * 権限がない・測位できない状態。当てずっぽうの座標で取りに行っても無関係な
 * マスタが書き込まれるだけなので、通信する前に止める。
 */
class OsmAreaCenterUnavailableException : Exception(
    "現在地が取れないため取り込めません。位置情報の権限を許可して、" +
        "屋外など測位できる場所でもう一度お試しください。",
)

/** 取り込み1回の結果。デバッグUIと監査値の突き合わせに使う。 */
data class OsmImportResult(
    val wayCount: Int,
    val poiCount: Int,
    /** 対象外の highway（service・幹線など）として捨てたway数。 */
    val excludedWayCount: Int,
    /** 安全フィルタ（design.md §6 配置禁止）で捨てたPOI数。 */
    val excludedUnsafePoiCount: Int,
    /** どの図鑑カテゴリにも当てはまらず捨てたPOI数。 */
    val excludedUnclassifiedPoiCount: Int,
) {
    val excludedPoiCount: Int get() = excludedUnsafePoiCount + excludedUnclassifiedPoiCount
}
