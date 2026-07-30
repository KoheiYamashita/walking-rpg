package com.walkingrpg.shared.domain.codex

import com.walkingrpg.shared.domain.osm.GeoDistance
import com.walkingrpg.shared.domain.osm.Poi
import com.walkingrpg.shared.domain.osm.Way

/**
 * 「どのPOIの近くをどの道が通っているか」の対応（純関数）。
 *
 * 図鑑は `passage`（＝道の通過）から訪問回数を導く一方で、通過には**POIの情報が無い**
 * （architecture.md §4 `passage(session_id, way_id, ts)`）。その隙間を埋めるのがこれ。
 *
 * ## テーブルに持たない
 * POIとwayの対応は**毎回その場で計算する**。永続化しない理由：
 *
 * - `poi` と `way` はどちらも対象圏を取り直すと**全削除して作り直される**マスタ
 *   （`OsmMasterRepository.save`）。対応表を持つと、取り直しのたびに
 *   古いIDを指した行が残る。外部キーを張れば連鎖削除できるが、
 *   そのFKは真実の源（`passage`）側の方針（Passage.sq「way_id には外部キーを張らない」）と
 *   食い違う向きの制約を1つ増やすことになる
 * - 計算が安い。POIは数百件、歩行対象のwayは約220本（design.md §9）で、
 *   1組あたり [GeoDistance.distanceToPathMeters] 1回＝数万回の浮動小数計算にすぎない。
 *   索引を持つ価値が出るのは対象圏が桁で広がってからで、そのときに考えればよい
 *
 * ## 判定
 * POIの代表点（`poi.lat/lon`）から道の折れ線までの最短距離が
 * [CodexConfig.poiProximityMeters] 以内なら「その道はこのPOIの近傍」。
 * 距離の意味と既定値の根拠は [CodexConfig.poiProximityMeters] のKDoc。
 */
data class PoiWayIndex(
    /** POI1件ぶんの近傍。POIのID順（同じ入力から必ず同じ並びが出るように）。 */
    val entries: List<Entry>,
) {

    /** 棚ごとのPOI（空の棚は現れない）。 */
    val entriesByCategory: Map<CodexCategory, List<Entry>> get() = entries.groupBy { it.category }

    /**
     * POI1件と、その近くを通る道。
     *
     * @param nearbyWayIds 近傍の道のID。1つも無いPOI（対象圏の端・車道しか接していない地物）も
     *  [entries] には残す：棚に属するPOIが存在すること自体は事実で、
     *  「道が無いから訪問回数0」と「そもそもPOIが無い」は別の状態だから。
     */
    data class Entry(
        val poiId: String,
        val category: CodexCategory,
        val nearbyWayIds: Set<Long>,
    )

    companion object {
        /**
         * POIマスタとwayマスタから対応を組む。
         *
         * @param pois 安全フィルタを通ったPOI（`OsmMasterRepository.pois()`）。
         * @param ways 対象圏のway（`OsmMasterRepository.ways()`）。
         */
        fun of(
            pois: List<Poi>,
            ways: List<Way>,
            config: CodexConfig = CodexConfig.DEFAULT,
        ): PoiWayIndex = PoiWayIndex(
            entries = pois
                .sortedBy { it.id }
                .map { poi ->
                    Entry(
                        poiId = poi.id,
                        category = poi.kind.toCodexCategory(),
                        nearbyWayIds = ways
                            .filter { way ->
                                GeoDistance.distanceToPathMeters(poi.location, way.geometry) <=
                                    config.poiProximityMeters
                            }
                            .mapTo(mutableSetOf()) { it.id },
                    )
                },
        )
    }
}
