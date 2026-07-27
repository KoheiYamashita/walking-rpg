package com.walkingrpg.shared.data.osm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Overpass応答JSONのパース（データ層）。フィクスチャの座標はすべて架空。 */
class OverpassResponseParserTest {

    @Test
    fun way要素だけが取り出される() {
        val ways = OverpassResponseParser.parseWays(OverpassFixtures.WAYS_JSON)

        // node要素と highway タグの無いwayは候補にならない
        assertEquals(listOf(101L, 102L, 103L), ways.map { it.id })
    }

    @Test
    fun 頂点列とタグが読める() {
        val way = OverpassResponseParser.parseWays(OverpassFixtures.WAYS_JSON).first()

        assertEquals("residential", way.highway)
        assertEquals("架空の一番通り", way.name)
        assertEquals(3, way.geometry.size)
        assertEquals(12.0, way.geometry.first().latitude)
        assertEquals(34.0005, way.geometry.last().longitude)
    }

    @Test
    fun 名前のないwayはnullになる() {
        val way = OverpassResponseParser.parseWays(OverpassFixtures.WAYS_JSON)
            .first { it.id == 102L }

        assertNull(way.name)
    }

    @Test
    fun 対象外のhighwayもパース段階では落とさない() {
        // 何を取り込むかの判断はドメイン層（ImportOsmAreaUseCase）の仕事
        val ways = OverpassResponseParser.parseWays(OverpassFixtures.WAYS_JSON)

        assertTrue(ways.any { it.highway == "service" })
    }

    @Test
    fun nodeとwayとrelationの座標が読める() {
        val pois = OverpassResponseParser.parsePois(OverpassFixtures.POIS_JSON)

        val node = pois.first { it.id == "node/201" }
        assertEquals(12.0006, node.location.latitude)
        assertEquals(34.0006, node.location.longitude)

        // way・relationは out center の重心
        val way = pois.first { it.id == "way/202" }
        assertEquals(12.0011, way.location.latitude)
        val relation = pois.first { it.id == "relation/212" }
        assertEquals(12.0022, relation.location.latitude)
    }

    @Test
    fun IDは要素種別込みで一意になる() {
        val pois = OverpassResponseParser.parsePois(OverpassFixtures.POIS_JSON)

        assertEquals(pois.size, pois.map { it.id }.toSet().size)
        assertTrue(pois.all { it.id.substringBefore('/') in setOf("node", "way", "relation") })
    }

    @Test
    fun タグの無い要素は候補にならない() {
        val pois = OverpassResponseParser.parsePois(OverpassFixtures.POIS_JSON)

        // 211 はwayの構成ノードとして降りてきただけでタグを持たない
        assertTrue(pois.none { it.id == "node/211" })
    }

    @Test
    fun 未知のフィールドがあってもパースできる() {
        // 実際の応答には version / generator / bounds / nodes など未使用のキーが混ざる
        val ways = OverpassResponseParser.parseWays(OverpassFixtures.WAYS_JSON)

        assertTrue(ways.isNotEmpty())
    }

    @Test
    fun 要素が空の応答は空リストになる() {
        val empty = """{"version":0.6,"generator":"Overpass API","elements":[]}"""

        assertEquals(emptyList(), OverpassResponseParser.parseWays(empty))
        assertEquals(emptyList(), OverpassResponseParser.parsePois(empty))
    }
}
