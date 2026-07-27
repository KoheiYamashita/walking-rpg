package com.walkingrpg.shared.data.osm

import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.osm.OsmPoiCandidate
import com.walkingrpg.shared.domain.osm.OsmWayCandidate
import kotlinx.serialization.json.Json

/**
 * Overpassの応答JSON → ドメインの候補モデル。
 *
 * パースはデータ層に閉じる（architecture.md §2）。ここでは形の変換だけを行い、
 * 「取り込むかどうか」の判断（安全フィルタ・対象highway）はドメイン層に任せる。
 */
internal object OverpassResponseParser {

    /** Overpassは版によって未知のフィールドが増えるので、知らないキーは無視する。 */
    val json: Json = Json { ignoreUnknownKeys = true }

    fun parseWays(body: String): List<OsmWayCandidate> =
        json.decodeFromString<OverpassResponse>(body).elements
            .filter { it.type == "way" }
            .mapNotNull { element ->
                val highway = element.tags["highway"] ?: return@mapNotNull null
                OsmWayCandidate(
                    id = element.id,
                    name = element.tags["name"],
                    highway = highway,
                    geometry = element.geometry.map { GeoPoint(it.lat, it.lon) },
                )
            }

    fun parsePois(body: String): List<OsmPoiCandidate> =
        json.decodeFromString<OverpassResponse>(body).elements
            .mapNotNull { element ->
                if (element.tags.isEmpty()) return@mapNotNull null
                val location = element.location() ?: return@mapNotNull null
                OsmPoiCandidate(
                    // node/way/relationでID空間が別なので、種別込みで一意にする
                    id = "${element.type}/${element.id}",
                    tags = element.tags,
                    location = location,
                )
            }

    /** node は `lat`/`lon`、way・relation は `out center` の重心を使う。 */
    private fun OverpassElement.location(): GeoPoint? {
        if (lat != null && lon != null) return GeoPoint(lat, lon)
        center?.let { return GeoPoint(it.lat, it.lon) }
        return null
    }
}
