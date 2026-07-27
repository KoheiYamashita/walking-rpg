package com.walkingrpg.shared.domain.osm

import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.map.MapCamera
import com.walkingrpg.shared.domain.map.MapCameraRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 取り込みUseCaseの検証。座標はすべて架空。
 *
 * ここで見るのは「取得結果をどう振り分けるか」だけで、
 * 通信もDBもフェイクに置き換える（ドメイン層は純Kotlin）。
 */
class ImportOsmAreaUseCaseTest {

    private val center = GeoPoint(latitude = 12.0, longitude = 34.0)

    private class FakeAreaSource(private val snapshot: OsmAreaSnapshot) : OsmAreaSource {
        var requestedArea: OsmArea? = null

        override suspend fun fetchArea(area: OsmArea): OsmAreaSnapshot {
            requestedArea = area
            return snapshot
        }
    }

    /** 「同じIDは置き換える」だけを持つDBのフェイク（実装の冪等性は .sq 側のテストで見る）。 */
    private class FakeMasterRepository : OsmMasterRepository {
        private val wayById = linkedMapOf<Long, Way>()
        private val poiById = linkedMapOf<String, Poi>()
        var saveCount = 0

        override suspend fun save(ways: List<Way>, pois: List<Poi>) {
            saveCount++
            ways.forEach { wayById[it.id] = it }
            pois.forEach { poiById[it.id] = it }
        }

        override suspend fun counts() = OsmMasterCounts(wayById.size, poiById.size)
        override suspend fun ways() = wayById.values.toList()
        override suspend fun pois() = poiById.values.toList()
    }

    private inner class FakeCameraRepository : MapCameraRepository {
        override suspend fun initialCamera() = MapCamera(center = center, zoom = 15.0)
    }

    private fun way(id: Long, highway: String, name: String? = null) = OsmWayCandidate(
        id = id,
        name = name,
        highway = highway,
        geometry = listOf(
            GeoPoint(center.latitude, center.longitude),
            GeoPoint(center.latitude, center.longitude + 0.001),
        ),
    )

    private fun poi(id: String, vararg tags: Pair<String, String>) = OsmPoiCandidate(
        id = id,
        tags = mapOf(*tags),
        location = GeoPoint(center.latitude + 0.0005, center.longitude + 0.0005),
    )

    private fun useCase(snapshot: OsmAreaSnapshot, repository: FakeMasterRepository) =
        ImportOsmAreaUseCase(
            areaSource = FakeAreaSource(snapshot),
            masterRepository = repository,
            mapCameraRepository = FakeCameraRepository(),
        )

    private val snapshot = OsmAreaSnapshot(
        ways = listOf(
            way(1, "residential", name = "架空の通り"),
            way(2, "footway"),
            way(3, "service"),
            way(4, "primary"),
            way(5, "path"),
        ),
        pois = listOf(
            poi("node/1", "leisure" to "park"),
            poi("node/2", "railway" to "level_crossing"),
            poi("way/3", "building" to "house"),
            poi("way/4", "amenity" to "school"),
            poi("node/5", "barrier" to "gate"),
        ),
    )

    @Test
    fun 歩行対象のwayだけが取り込まれる() = runTest {
        val repository = FakeMasterRepository()

        val result = useCase(snapshot, repository)()

        assertEquals(3, result.wayCount)
        assertEquals(2, result.excludedWayCount)
        assertEquals(
            listOf(1L, 2L, 5L),
            repository.ways().map { it.id }.sorted(),
        )
    }

    @Test
    fun 配置禁止のPOIは取り込み時点で落ちる() = runTest {
        val repository = FakeMasterRepository()

        val result = useCase(snapshot, repository)()

        assertEquals(2, result.poiCount)
        assertEquals(2, result.excludedUnsafePoiCount)
        assertEquals(1, result.excludedUnclassifiedPoiCount)
        assertEquals(3, result.excludedPoiCount)
        assertEquals(listOf(PoiKind.PARK, PoiKind.RAILWAY), repository.pois().map { it.kind })
    }

    @Test
    fun way長がgeometryから算出される() = runTest {
        val repository = FakeMasterRepository()

        useCase(snapshot, repository)()

        // 経度0.001度ぶん。緯度12度なので110m前後になる
        val lengths = repository.ways().map { it.lengthMeters }
        assertTrue(lengths.all { it in 100.0..120.0 }, "算出された長さ: $lengths")
    }

    @Test
    fun 再実行しても件数が増えない() = runTest {
        val repository = FakeMasterRepository()
        val useCase = useCase(snapshot, repository)

        val first = useCase()
        val second = useCase()

        assertEquals(first, second)
        assertEquals(2, repository.saveCount)
        assertEquals(OsmMasterCounts(wayCount = 3, poiCount = 2), repository.counts())
    }

    @Test
    fun 中心と半径は対象圏としてそのまま渡る() = runTest {
        val source = FakeAreaSource(snapshot)
        val useCase = ImportOsmAreaUseCase(source, FakeMasterRepository(), FakeCameraRepository())

        useCase(radiusMeters = 800)

        assertEquals(OsmArea(center = center, radiusMeters = 800), source.requestedArea)
    }

    @Test
    fun 頂点が足りないwayは捨てる() = runTest {
        val repository = FakeMasterRepository()
        val broken = OsmAreaSnapshot(
            ways = listOf(way(9, "footway").copy(geometry = listOf(GeoPoint(12.0, 34.0)))),
        )

        val result = useCase(broken, repository)()

        assertEquals(0, result.wayCount)
        assertEquals(1, result.excludedWayCount)
    }
}
