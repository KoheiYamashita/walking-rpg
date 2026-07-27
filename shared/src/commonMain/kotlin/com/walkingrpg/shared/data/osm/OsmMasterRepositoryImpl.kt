package com.walkingrpg.shared.data.osm

import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.osm.OsmMasterCounts
import com.walkingrpg.shared.domain.osm.OsmMasterRepository
import com.walkingrpg.shared.domain.osm.Poi
import com.walkingrpg.shared.domain.osm.PoiKind
import com.walkingrpg.shared.domain.osm.Way
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * [OsmMasterRepository] のSQLDelight実装。
 *
 * 保存は**全削除→挿入を1トランザクション**で行う（マスタの作り直し）。
 * 差分upsertにすると、OSM側で廃止された地物や、安全フィルタで弾かれるように
 * なった地物（例：あとから `access=private` が付いたPOI）が消えずに残り、
 * 「`poi` にあるのは配置してよい場所」という前提が崩れる。中心を移して
 * 取り直したときに旧対象圏のデータが溜まり続ける問題も同時に消える。
 *
 * マスタは真実の源ではなく、いつでも取り直せる（Way.sq / Poi.sq のコメント）ので
 * 作り直して困るものは無い。途中で失敗しても直前の状態のまま残るのは
 * トランザクションの効果。
 */
internal class OsmMasterRepositoryImpl(
    private val database: WalkingRpgDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : OsmMasterRepository {

    private val ways get() = database.wayQueries
    private val pois get() = database.poiQueries

    override suspend fun save(ways: List<Way>, pois: List<Poi>): Unit = withContext(dispatcher) {
        database.transaction {
            this@OsmMasterRepositoryImpl.ways.deleteAllWays()
            this@OsmMasterRepositoryImpl.pois.deleteAllPois()
            ways.forEach { way ->
                this@OsmMasterRepositoryImpl.ways.upsertWay(
                    id = way.id,
                    name = way.name,
                    highway = way.highway,
                    geometry = WayGeometryCodec.encode(way.geometry),
                    length_m = way.lengthMeters,
                )
            }
            pois.forEach { poi ->
                this@OsmMasterRepositoryImpl.pois.upsertPoi(
                    id = poi.id,
                    kind = poi.kind.name,
                    tags = TAGS_JSON.encodeToString(poi.tags),
                    lat = poi.location.latitude,
                    lon = poi.location.longitude,
                )
            }
        }
    }

    override suspend fun counts(): OsmMasterCounts = withContext(dispatcher) {
        OsmMasterCounts(
            wayCount = ways.countWays().executeAsOne().toInt(),
            poiCount = pois.countPois().executeAsOne().toInt(),
        )
    }

    override suspend fun ways(): List<Way> = withContext(dispatcher) {
        ways.selectAllWays().executeAsList().map { row ->
            Way(
                id = row.id,
                name = row.name,
                highway = row.highway,
                geometry = WayGeometryCodec.decode(row.geometry),
                lengthMeters = row.length_m,
            )
        }
    }

    override suspend fun pois(): List<Poi> = withContext(dispatcher) {
        pois.selectAllPois().executeAsList().mapNotNull { row ->
            // 未知の kind は将来の分類追加からのダウングレードでしか起きない。
            // マスタは取り直せるので、読めない行は落として先に進む。
            val kind = PoiKind.entries.firstOrNull { it.name == row.kind } ?: return@mapNotNull null
            Poi(
                id = row.id,
                kind = kind,
                tags = runCatching {
                    TAGS_JSON.decodeFromString<Map<String, String>>(row.tags)
                }.getOrDefault(emptyMap()),
                location = GeoPoint(latitude = row.lat, longitude = row.lon),
            )
        }
    }

    private companion object {
        val TAGS_JSON = Json { ignoreUnknownKeys = true }
    }
}
