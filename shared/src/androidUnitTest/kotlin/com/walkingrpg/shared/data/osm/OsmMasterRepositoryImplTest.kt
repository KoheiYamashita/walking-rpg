package com.walkingrpg.shared.data.osm

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.osm.OsmMasterCounts
import com.walkingrpg.shared.domain.osm.Poi
import com.walkingrpg.shared.domain.osm.PoiKind
import com.walkingrpg.shared.domain.osm.Way
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Way.sq` / `Poi.sq` を実際のSQLite（インメモリ）で検証する。
 * 見たいのは「取り込みがマスタを作り直すか」＝再実行で件数が増えず、
 * 前回の残骸も残らないこと。座標はすべて架空。
 */
class OsmMasterRepositoryImplTest {

    private fun repository(): OsmMasterRepositoryImpl {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        WalkingRpgDatabase.Schema.create(driver)
        return OsmMasterRepositoryImpl(WalkingRpgDatabase(driver))
    }

    private fun way(id: Long, name: String? = null, lengthMeters: Double = 120.5) = Way(
        id = id,
        name = name,
        highway = "residential",
        geometry = listOf(GeoPoint(12.0, 34.0), GeoPoint(12.0005, 34.0005)),
        lengthMeters = lengthMeters,
    )

    private fun poi(id: String, kind: PoiKind = PoiKind.PARK) = Poi(
        id = id,
        kind = kind,
        tags = mapOf("leisure" to "park", "name" to "架空の公園"),
        location = GeoPoint(12.0011, 34.0011),
    )

    @Test
    fun 同じ対象圏を2回取り込んでも件数が増えない() = runTest {
        val repository = repository()
        val ways = listOf(way(1), way(2))
        val pois = listOf(poi("node/1"), poi("way/2"))

        repository.save(ways, pois)
        repository.save(ways, pois)

        assertEquals(OsmMasterCounts(wayCount = 2, poiCount = 2), repository.counts())
    }

    @Test
    fun 再取り込みで内容が最新に置き換わる() = runTest {
        val repository = repository()

        repository.save(listOf(way(1, name = null, lengthMeters = 100.0)), emptyList())
        repository.save(listOf(way(1, name = "架空の通り", lengthMeters = 180.0)), emptyList())

        val stored = repository.ways().single()
        assertEquals("架空の通り", stored.name)
        assertEquals(180.0, stored.lengthMeters)
    }

    @Test
    fun geometryは往復しても座標が変わらない() = runTest {
        val repository = repository()
        val original = way(1)

        repository.save(listOf(original), emptyList())

        assertEquals(original.geometry, repository.ways().single().geometry)
    }

    @Test
    fun POIのタグと分類が往復する() = runTest {
        val repository = repository()
        val original = poi("node/1", kind = PoiKind.RAILWAY)

        repository.save(emptyList(), listOf(original))

        assertEquals(original, repository.pois().single())
    }

    @Test
    fun 要素種別が違えば同じ数値IDでも別のPOIになる() = runTest {
        val repository = repository()

        repository.save(emptyList(), listOf(poi("node/1"), poi("way/1")))

        assertEquals(2, repository.counts().poiCount)
    }

    @Test
    fun 取り込みに含まれなくなった地物は消える() = runTest {
        val repository = repository()
        repository.save(listOf(way(1), way(2)), listOf(poi("node/1"), poi("node/2")))

        // OSM側で廃止された、あるいは配置禁止タグが付いて弾かれるようになった想定
        repository.save(listOf(way(1)), listOf(poi("node/1")))

        assertEquals(listOf(1L), repository.ways().map { it.id })
        assertEquals(listOf("node/1"), repository.pois().map { it.id })
    }

    @Test
    fun 中心を移して取り直すと旧対象圏のデータが残らない() = runTest {
        val repository = repository()
        repository.save(listOf(way(1), way(2)), listOf(poi("node/1")))

        // 引っ越し・旅行先での取り込み。まったく別のway・POIが返ってくる
        repository.save(listOf(way(10), way(11)), listOf(poi("node/10")))

        assertEquals(listOf(10L, 11L), repository.ways().map { it.id })
        assertEquals(listOf("node/10"), repository.pois().map { it.id })
        assertEquals(OsmMasterCounts(wayCount = 2, poiCount = 1), repository.counts())
    }
}
