package com.walkingrpg.shared.domain.osm

import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.walk.CurrentLocationRepository

/**
 * 対象圏のOSMマスタを取り込むUseCase（issue #5・architecture.md §5）。
 *
 * 取得 → 判定（歩行対象way／安全フィルタ）→ 変換（way長の算出）→ 保存、の1操作。
 * 判定と変換はこの層の純関数（[PoiSafetyFilter] / [GeoDistance]）で、
 * 通信と永続化はインターフェースの向こう側にある。
 *
 * **対象圏の中心は現在地のみ**。対象圏は「いま自分がいる場所の周り」であり、
 * それを設定ファイルに書く仕組みを持てば、ユーザー固有の座標をリポジトリに
 * 持ち込む経路ができてしまう（プライバシー方針）。
 *
 * 現在地が取れない（権限がない・測位できない）ときは
 * [OsmAreaCenterUnavailableException] で止める。当てずっぽうの座標で
 * 取りに行っても、無関係な土地のマスタが書き込まれるだけで誰も得をしない。
 *
 * 保存はIDをキーにした置き換えなので、何度実行しても件数は増えない
 * （architecture.md §0「冪等で再計算できる」）。
 */
class ImportOsmAreaUseCase(
    private val areaSource: OsmAreaSource,
    private val masterRepository: OsmMasterRepository,
    private val currentLocationRepository: CurrentLocationRepository,
) {
    suspend operator fun invoke(radiusMeters: Int = DEFAULT_RADIUS_METERS): OsmImportResult {
        val fix = currentLocationRepository.currentFix() ?: throw OsmAreaCenterUnavailableException()
        val center = GeoPoint(latitude = fix.latitude, longitude = fix.longitude)
        val area = OsmArea(center = center, radiusMeters = radiusMeters)
        val snapshot = areaSource.fetchArea(area)

        val ways = snapshot.ways
            .filter { it.isWalkable() }
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

    /**
     * 成長単位（way）として取り込む道か。
     *
     * 種別が歩行対象で、頂点が2つ以上あって、私有地でないこと。
     * `access=private` の道は歩けないので、成長単位にしても通過が記録されない
     * ままマスタに残るだけになる（POI側と同じ判定を使う）。
     */
    private fun OsmWayCandidate.isWalkable(): Boolean =
        highway in WALKABLE_HIGHWAY_VALUES &&
            geometry.size >= 2 &&
            !PoiSafetyFilter.isForbiddenAccess(access)

    companion object {
        /** MVPの対象圏（design.md §9「MVP対象圏 500m」）。 */
        const val DEFAULT_RADIUS_METERS: Int = 500
    }
}
