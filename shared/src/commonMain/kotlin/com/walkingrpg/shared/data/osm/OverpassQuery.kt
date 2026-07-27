package com.walkingrpg.shared.data.osm

import com.walkingrpg.shared.domain.osm.OsmArea
import com.walkingrpg.shared.domain.osm.WALKABLE_HIGHWAY_VALUES

/**
 * Overpass QLの組み立て。クエリ言語を知っているのはデータ層だけ（architecture.md §2）。
 *
 * way とPOIで出力指定が違う（`out geom` / `out center`）ため、リクエストは2本に分ける。
 * 1本にまとめると片方の座標が落ちる。
 */
internal object OverpassQuery {

    /**
     * 歩行対象wayの取得。対象の定義（highway値）はドメインの
     * [WALKABLE_HIGHWAY_VALUES] が唯一の出所で、`service` はそこに入っていない
     * ＝クエリの時点で降りてこない。
     */
    fun ways(area: OsmArea, timeoutSeconds: Int): String = buildString {
        appendLine(header(timeoutSeconds))
        appendLine("""way["highway"~"^(${WALKABLE_HIGHWAY_VALUES.sorted().joinToString("|")})$"]${around(area)};""")
        append("out geom;")
    }

    /**
     * 図鑑素材・配置候補の取得。
     *
     * ここは「安全側に絞る」のではなく **PoiSafetyFilter が判定できるだけの候補を取る**。
     * 配置禁止（民家・学校・病院…）の判定はタグを見ないとできないので、
     * `amenity` のように禁止も許可も混じるキーはまとめて取ってドメイン層で振り分ける。
     */
    fun pois(area: OsmArea, timeoutSeconds: Int): String = buildString {
        appendLine(header(timeoutSeconds))
        appendLine("(")
        POI_SELECTORS.forEach { selector ->
            appendLine("  nwr$selector${around(area)};")
        }
        appendLine(");")
        append("out center;")
    }

    private fun header(timeoutSeconds: Int): String = "[out:json][timeout:$timeoutSeconds];"

    private fun around(area: OsmArea): String =
        "(around:${area.radiusMeters},${area.center.latitude},${area.center.longitude})"

    private val POI_SELECTORS: List<String> = listOf(
        // 図鑑4本柱（design.md §9）
        """["leisure"]""",
        """["natural"]""",
        """["waterway"]""",
        """["landuse"~"^(farmland|orchard|allotments|meadow|recreation_ground)$"]""",
        """["railway"~"^(level_crossing|crossing|station|halt|tram_stop)$"]""",
        """["public_transport"="station"]""",
        // 市街・寺社・公共・ランドマーク
        """["amenity"]""",
        """["shop"]""",
        """["historic"]""",
        """["tourism"]""",
    )
}
