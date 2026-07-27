package com.walkingrpg.shared.domain.osm

import com.walkingrpg.shared.domain.map.MapCameraRepository

/**
 * 対象圏のOSMマスタを取り込むUseCase（issue #5・architecture.md §5）。
 *
 * 取得 → 判定（歩行対象way／安全フィルタ）→ 変換（way長の算出）→ 保存、の1操作。
 * 判定と変換はこの層の純関数（[PoiSafetyFilter] / [GeoDistance]）で、
 * 通信と永続化はインターフェースの向こう側にある。
 *
 * 中心座標は地図の初期表示位置を使い回す（[MapCameraRepository]）。
 * 実在の座標はリポジトリに置かないので、値はGit管理外のローカル設定から来る。
 *
 * 保存はIDをキーにした置き換えなので、何度実行しても件数は増えない
 * （architecture.md §0「冪等で再計算できる」）。
 */
class ImportOsmAreaUseCase(
    private val areaSource: OsmAreaSource,
    private val masterRepository: OsmMasterRepository,
    private val mapCameraRepository: MapCameraRepository,
) {
    suspend operator fun invoke(radiusMeters: Int = DEFAULT_RADIUS_METERS): OsmImportResult {
        val area = OsmArea(
            center = mapCameraRepository.initialCamera().center,
            radiusMeters = radiusMeters,
        )
        val snapshot = areaSource.fetchArea(area)

        val ways = snapshot.ways
            .filter { it.highway in WALKABLE_HIGHWAY_VALUES && it.geometry.size >= 2 }
            .map { candidate ->
                Way(
                    id = candidate.id,
                    name = candidate.name,
                    highway = candidate.highway,
                    geometry = candidate.geometry,
                    lengthMeters = GeoDistance.pathLengthMeters(candidate.geometry),
                )
            }

        val pois = mutableListOf<Poi>()
        var unsafeCount = 0
        var unclassifiedCount = 0
        snapshot.pois.forEach { candidate ->
            when (val verdict = PoiSafetyFilter.judge(candidate.tags)) {
                is PoiSafetyFilter.Verdict.Accepted -> pois += Poi(
                    id = candidate.id,
                    kind = verdict.kind,
                    tags = candidate.tags,
                    location = candidate.location,
                )

                is PoiSafetyFilter.Verdict.Rejected -> unsafeCount++
                PoiSafetyFilter.Verdict.Unclassified -> unclassifiedCount++
            }
        }

        masterRepository.save(ways = ways, pois = pois)

        return OsmImportResult(
            wayCount = ways.size,
            poiCount = pois.size,
            excludedWayCount = snapshot.ways.size - ways.size,
            excludedUnsafePoiCount = unsafeCount,
            excludedUnclassifiedPoiCount = unclassifiedCount,
        )
    }

    companion object {
        /** MVPの対象圏（design.md §9「MVP対象圏 500m」）。 */
        const val DEFAULT_RADIUS_METERS: Int = 500
    }
}
