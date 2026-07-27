package com.walkingrpg.shared.data.osm

/**
 * Overpass応答のフィクスチャ。
 *
 * **座標はすべて架空**（緯度12度／経度34度あたりの海の上）。
 * 実在の場所・対象圏の座標はリポジトリに入れない（CONTRIBUTING.md）。
 * 形（要素の並び・`out geom` と `out center` の違い・タグの付き方）だけを本物に似せてある。
 */
internal object OverpassFixtures {

    /** `out geom` の応答。歩行対象・対象外・タグ欠けを1件ずつ含む。 */
    val WAYS_JSON = """
        {
          "version": 0.6,
          "generator": "Overpass API (fixture)",
          "elements": [
            {
              "type": "way",
              "id": 101,
              "bounds": { "minlat": 12.0, "minlon": 34.0, "maxlat": 12.001, "maxlon": 34.001 },
              "nodes": [1001, 1002, 1003],
              "geometry": [
                { "lat": 12.0000000, "lon": 34.0000000 },
                { "lat": 12.0005000, "lon": 34.0000000 },
                { "lat": 12.0010000, "lon": 34.0005000 }
              ],
              "tags": { "highway": "residential", "name": "架空の一番通り" }
            },
            {
              "type": "way",
              "id": 102,
              "geometry": [
                { "lat": 12.0020000, "lon": 34.0010000 },
                { "lat": 12.0025000, "lon": 34.0010000 }
              ],
              "tags": { "highway": "footway", "surface": "asphalt" }
            },
            {
              "type": "way",
              "id": 103,
              "geometry": [
                { "lat": 12.0030000, "lon": 34.0020000 },
                { "lat": 12.0035000, "lon": 34.0020000 }
              ],
              "tags": { "highway": "service", "service": "driveway" }
            },
            {
              "type": "node",
              "id": 1001,
              "lat": 12.0000000,
              "lon": 34.0000000
            },
            {
              "type": "way",
              "id": 104,
              "geometry": [
                { "lat": 12.0040000, "lon": 34.0030000 },
                { "lat": 12.0045000, "lon": 34.0030000 }
              ],
              "tags": { "barrier": "fence" }
            },
            {
              "type": "way",
              "id": 105,
              "geometry": [
                { "lat": 12.0050000, "lon": 34.0040000 },
                { "lat": 12.0055000, "lon": 34.0040000 }
              ],
              "tags": { "highway": "footway", "access": "private" }
            }
          ]
        }
    """.trimIndent()

    /**
     * サーバ側タイムアウトで打ち切られた部分応答。
     * **HTTP 200** のまま `remark` と「途中まで」の要素が返る。
     */
    val PARTIAL_RESPONSE_JSON = """
        {
          "version": 0.6,
          "generator": "Overpass API (fixture)",
          "remark": "runtime error: Query timed out in \"query\" at line 2 after 60 seconds.",
          "elements": [
            {
              "type": "way",
              "id": 101,
              "geometry": [
                { "lat": 12.0000000, "lon": 34.0000000 },
                { "lat": 12.0005000, "lon": 34.0000000 }
              ],
              "tags": { "highway": "residential" }
            }
          ]
        }
    """.trimIndent()

    /** `out center` の応答。node（lat/lon）とway（center）が混ざる。 */
    val POIS_JSON = """
        {
          "version": 0.6,
          "generator": "Overpass API (fixture)",
          "elements": [
            {
              "type": "node",
              "id": 201,
              "lat": 12.0006000,
              "lon": 34.0006000,
              "tags": { "natural": "tree" }
            },
            {
              "type": "way",
              "id": 202,
              "center": { "lat": 12.0011000, "lon": 34.0011000 },
              "tags": { "leisure": "park", "name": "架空のせせらぎ公園" }
            },
            {
              "type": "node",
              "id": 203,
              "lat": 12.0012000,
              "lon": 34.0012000,
              "tags": { "railway": "level_crossing" }
            },
            {
              "type": "way",
              "id": 204,
              "center": { "lat": 12.0013000, "lon": 34.0013000 },
              "tags": { "landuse": "farmland" }
            },
            {
              "type": "way",
              "id": 205,
              "center": { "lat": 12.0014000, "lon": 34.0014000 },
              "tags": { "amenity": "school", "name": "架空の第一小学校" }
            },
            {
              "type": "way",
              "id": 206,
              "center": { "lat": 12.0015000, "lon": 34.0015000 },
              "tags": { "building": "house" }
            },
            {
              "type": "node",
              "id": 207,
              "lat": 12.0016000,
              "lon": 34.0016000,
              "tags": { "amenity": "hospital", "name": "架空の総合病院" }
            },
            {
              "type": "node",
              "id": 208,
              "lat": 12.0017000,
              "lon": 34.0017000,
              "tags": { "shop": "convenience", "name": "架空商店" }
            },
            {
              "type": "way",
              "id": 209,
              "center": { "lat": 12.0018000, "lon": 34.0018000 },
              "tags": { "waterway": "stream", "name": "架空川" }
            },
            {
              "type": "node",
              "id": 210,
              "lat": 12.0019000,
              "lon": 34.0019000,
              "tags": { "barrier": "gate" }
            },
            {
              "type": "node",
              "id": 211,
              "lat": 12.0021000,
              "lon": 34.0021000
            },
            {
              "type": "relation",
              "id": 212,
              "center": { "lat": 12.0022000, "lon": 34.0022000 },
              "tags": { "leisure": "park", "name": "架空の広域公園", "type": "multipolygon" }
            }
          ]
        }
    """.trimIndent()
}
