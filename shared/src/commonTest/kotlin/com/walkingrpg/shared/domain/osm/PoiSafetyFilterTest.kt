package com.walkingrpg.shared.domain.osm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 安全フィルタ（design.md §6）の検証。
 *
 * ここが壊れると民家や学校にシナリオが置かれる。取り込み時点の防波堤なので、
 * 配置禁止リストの各項目に最低1件のテストを当てる。
 */
class PoiSafetyFilterTest {

    private fun accepted(vararg tags: Pair<String, String>): PoiKind {
        val verdict = PoiSafetyFilter.judge(mapOf(*tags))
        assertIs<PoiSafetyFilter.Verdict.Accepted>(verdict, "配置可能のはず: ${mapOf(*tags)}")
        return verdict.kind
    }

    private fun assertRejected(vararg tags: Pair<String, String>) {
        val verdict = PoiSafetyFilter.judge(mapOf(*tags))
        assertIs<PoiSafetyFilter.Verdict.Rejected>(verdict, "配置禁止のはず: ${mapOf(*tags)}")
    }

    // --- 配置禁止（design.md §6）------------------------------------------------

    @Test
    fun 民家は除外される() {
        assertRejected("building" to "house")
        assertRejected("building" to "apartments")
        assertRejected("landuse" to "residential")
    }

    @Test
    fun 学校は除外される() {
        assertRejected("amenity" to "school")
        assertRejected("amenity" to "kindergarten")
        assertRejected("building" to "school")
    }

    @Test
    fun 病院は除外される() {
        assertRejected("amenity" to "hospital")
        assertRejected("amenity" to "clinic")
        assertRejected("healthcare" to "centre")
    }

    @Test
    fun 私有地は除外される() {
        assertRejected("leisure" to "park", "access" to "private")
        assertRejected("amenity" to "library", "access" to "no")
    }

    @Test
    fun 線路そのものは除外される() {
        assertRejected("railway" to "rail")
        assertRejected("railway" to "subway")
        assertRejected("railway" to "platform")
    }

    @Test
    fun 危険箇所は除外される() {
        assertRejected("natural" to "cliff")
        assertRejected("amenity" to "fuel")
        assertRejected("power" to "tower")
        assertRejected("hazard" to "landslide")
        assertRejected("waterway" to "weir")
        assertRejected("highway" to "construction")
    }

    @Test
    fun 禁止判定は分類より優先される() {
        // 公園として分類できるタグが付いていても、私有地なら入れない
        assertRejected("leisure" to "park", "landuse" to "residential")
        assertEquals("landuse=residential", PoiSafetyFilter.forbiddenReason(
            mapOf("leisure" to "park", "landuse" to "residential"),
        ))
    }

    // --- 配置可能・分類 ---------------------------------------------------------

    @Test
    fun 図鑑4本柱が分類される() {
        assertEquals(PoiKind.PARK, accepted("leisure" to "park"))
        assertEquals(PoiKind.PARK, accepted("leisure" to "playground"))
        assertEquals(PoiKind.WATER, accepted("waterway" to "stream"))
        assertEquals(PoiKind.WATER, accepted("natural" to "water"))
        assertEquals(PoiKind.RAILWAY, accepted("railway" to "level_crossing"))
        assertEquals(PoiKind.FARMLAND, accepted("landuse" to "farmland"))
    }

    @Test
    fun 市街と樹木と寺社と公共が分類される() {
        assertEquals(PoiKind.SHOP, accepted("shop" to "convenience"))
        assertEquals(PoiKind.SHOP, accepted("amenity" to "cafe"))
        assertEquals(PoiKind.TREE, accepted("natural" to "tree"))
        assertEquals(PoiKind.SHRINE, accepted("historic" to "wayside_shrine"))
        assertEquals(PoiKind.SHRINE, accepted("amenity" to "place_of_worship", "religion" to "shinto"))
        assertEquals(PoiKind.PUBLIC, accepted("amenity" to "library"))
        assertEquals(PoiKind.PUBLIC, accepted("amenity" to "police"))
        assertEquals(PoiKind.LANDMARK, accepted("tourism" to "artwork"))
    }

    @Test
    fun 駅は配置可能() {
        assertEquals(PoiKind.RAILWAY, accepted("railway" to "station"))
        assertEquals(PoiKind.RAILWAY, accepted("public_transport" to "station"))
    }

    @Test
    fun 分類できない地物は取り込まない() {
        assertEquals(PoiSafetyFilter.Verdict.Unclassified, PoiSafetyFilter.judge(emptyMap()))
        assertEquals(
            PoiSafetyFilter.Verdict.Unclassified,
            PoiSafetyFilter.judge(mapOf("name" to "何か")),
        )
        assertNull(PoiSafetyFilter.classify(mapOf("barrier" to "gate")))
    }

    @Test
    fun 通常のアクセス値は禁止にならない() {
        assertTrue(PoiSafetyFilter.forbiddenReason(mapOf("access" to "yes")) == null)
        assertTrue(PoiSafetyFilter.forbiddenReason(mapOf("access" to "permissive")) == null)
    }
}
