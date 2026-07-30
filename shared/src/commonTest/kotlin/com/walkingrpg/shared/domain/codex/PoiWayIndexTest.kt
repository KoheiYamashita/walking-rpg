package com.walkingrpg.shared.domain.codex

import com.walkingrpg.shared.domain.matching.SyntheticWalk
import com.walkingrpg.shared.domain.osm.PoiKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * POIと道の空間結合（[PoiWayIndex]）。
 *
 * 見たいのは3つ：
 * - 近傍半径の内と外で結果が分かれること（表と裏の道を取り違えない）
 * - 棚（[CodexCategory]）がPOIの種別から決まること
 * - 近傍の道が1本も無いPOIも残ること（「道が無い」と「POIが無い」は別）
 */
class PoiWayIndexTest {

    /** 原点を東西に走る道（北0m）。 */
    private val mainStreet = SyntheticWalk.eastWestWay(
        id = 1L,
        northMeters = 0.0,
        fromEast = 0.0,
        toEast = 300.0,
    )

    /** 100m北を並走する道。既定の近傍半径（40m）では届かない距離に置く。 */
    private val backStreet = SyntheticWalk.eastWestWay(
        id = 2L,
        northMeters = 100.0,
        fromEast = 0.0,
        toEast = 300.0,
    )

    @Test
    fun 近傍半径の内側の道だけが結びつく() {
        // 本通りから北へ10m（半径40m以内）、裏通りからは90m（外）の位置に公園
        val park = codexPoi("node/1", PoiKind.PARK, northMeters = 10.0, eastMeters = 150.0)

        val index = PoiWayIndex.of(pois = listOf(park), ways = listOf(mainStreet, backStreet))

        assertEquals(setOf(mainStreet.id), index.entries.single().nearbyWayIds)
    }

    @Test
    fun 半径の外の道は結びつかない() {
        // どちらの道からも50m以上離れた位置（本通りから50m北・裏通りから50m南）
        val park = codexPoi("node/1", PoiKind.PARK, northMeters = 50.0, eastMeters = 150.0)

        val index = PoiWayIndex.of(pois = listOf(park), ways = listOf(mainStreet, backStreet))

        assertTrue(index.entries.single().nearbyWayIds.isEmpty(), "近傍の道が無いPOIも行は残る")
        assertEquals(1, index.entries.size)
    }

    @Test
    fun 半径を広げれば両方の道が入る() {
        val park = codexPoi("node/1", PoiKind.PARK, northMeters = 50.0, eastMeters = 150.0)

        val index = PoiWayIndex.of(
            pois = listOf(park),
            ways = listOf(mainStreet, backStreet),
            config = CodexConfig(poiProximityMeters = 60.0),
        )

        assertEquals(setOf(mainStreet.id, backStreet.id), index.entries.single().nearbyWayIds)
    }

    @Test
    fun POIの種別から棚が決まる() {
        val pois = listOf(
            codexPoi("node/1", PoiKind.WATER),
            codexPoi("node/2", PoiKind.SHOP),
            codexPoi("node/3", PoiKind.PUBLIC),
            codexPoi("node/4", PoiKind.LANDMARK),
        )

        val index = PoiWayIndex.of(pois = pois, ways = emptyList())

        assertEquals(
            listOf(
                CodexCategory.WATER,
                // 商店・公共施設・目印はどれも「街の中の地物」＝同じ棚（CodexCategory のKDoc）
                CodexCategory.URBAN,
                CodexCategory.URBAN,
                CodexCategory.URBAN,
            ),
            index.entries.map { it.category },
        )
    }

    @Test
    fun 並びはPOIのID順に揃う() {
        // 同じ入力から必ず同じ並びが出ること（保存順で導出結果が変わらない担保）
        val pois = listOf(
            codexPoi("node/3", PoiKind.PARK),
            codexPoi("node/1", PoiKind.PARK),
            codexPoi("node/2", PoiKind.PARK),
        )

        val index = PoiWayIndex.of(pois = pois, ways = emptyList())

        assertEquals(listOf("node/1", "node/2", "node/3"), index.entries.map { it.poiId })
    }

    @Test
    fun 頂点が1つしかない道は近傍にならない() {
        // OSMの取り込みで壊れた形状（GeoDistance.distanceToPathMeters が無限を返す）でも落ちない
        val brokenWay = mainStreet.copy(id = 9L, geometry = listOf(mainStreet.geometry.first()))
        val park = codexPoi("node/1", PoiKind.PARK)

        val index = PoiWayIndex.of(pois = listOf(park), ways = listOf(brokenWay))

        assertTrue(index.entries.single().nearbyWayIds.isEmpty())
    }
}
