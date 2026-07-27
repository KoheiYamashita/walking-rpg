package com.walkingrpg.shared.data.osm

import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.osm.OsmArea
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Overpassクライアントの検証。実際の通信はせず、架空座標のフィクスチャを返す
 * フェイクエンジンで、クエリの中身・2リクエストの投げ分け・リトライを見る。
 */
class OverpassOsmAreaSourceTest {

    private val area = OsmArea(center = GeoPoint(12.0, 34.0), radiusMeters = 500)

    /** リトライ待ちでテストが止まらないよう、待ち時間だけ縮めた設定。 */
    private val config = OverpassConfig(retryDelayMs = 1)

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun source(
        handler: suspend MockRequestHandleScope.(query: String) -> HttpResponseData,
    ): Pair<OverpassOsmAreaSource, MutableList<String>> {
        val queries = mutableListOf<String>()
        val engine = MockEngine { request ->
            val body = request.body.toByteArray().decodeToString()
            // submitForm は application/x-www-form-urlencoded で `data=<クエリ>` を送る
            val query = body.substringAfter("data=").decodeFormValue()
            queries += query
            handler(query)
        }
        return OverpassOsmAreaSource(osmHttpClient(engine, config), config) to queries
    }

    private fun String.decodeFormValue(): String = decodeURLQueryComponent(plusIsSpace = true)

    @Test
    fun wayとPOIを2リクエストで取得する() = runTest {
        val (source, queries) = source { query ->
            val body = if ("out geom" in query) {
                OverpassFixtures.WAYS_JSON
            } else {
                OverpassFixtures.POIS_JSON
            }
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }

        val snapshot = source.fetchArea(area)

        assertEquals(2, queries.size)
        assertEquals(listOf(101L, 102L, 103L, 105L), snapshot.ways.map { it.id })
        assertTrue(snapshot.pois.any { it.id == "way/202" })
    }

    @Test
    fun wayのクエリは歩行対象のhighwayだけを指定し中心と半径を含む() = runTest {
        val (source, queries) = source { respond(EMPTY_RESPONSE, HttpStatusCode.OK, jsonHeaders) }

        source.fetchArea(area)

        val wayQuery = queries.first { "out geom" in it }
        assertTrue("(around:500,12.0,34.0)" in wayQuery, wayQuery)
        listOf("residential", "footway", "unclassified", "pedestrian", "path").forEach {
            assertTrue(it in wayQuery, "$it が対象に入っていない: $wayQuery")
        }
        // service は「歩く道」ではないのでクエリの時点で降りてこない
        assertTrue("service" !in wayQuery, wayQuery)
    }

    @Test
    fun POIのクエリは図鑑カテゴリの候補を含む() = runTest {
        val (source, queries) = source { respond(EMPTY_RESPONSE, HttpStatusCode.OK, jsonHeaders) }

        source.fetchArea(area)

        val poiQuery = queries.first { "out center" in it }
        listOf("leisure", "waterway", "farmland", "level_crossing", "amenity", "shop", "natural")
            .forEach { assertTrue(it in poiQuery, "$it が対象に入っていない: $poiQuery") }
    }

    @Test
    fun サーバエラーは1回だけ再試行する() = runTest {
        var attempts = 0
        val (source, _) = source {
            attempts++
            if (attempts == 1) {
                respondError(HttpStatusCode.GatewayTimeout)
            } else {
                respond(OverpassFixtures.WAYS_JSON, HttpStatusCode.OK, jsonHeaders)
            }
        }

        source.fetchArea(area)

        // 1本目：失敗＋再試行の2回、2本目（POI）：1回
        assertEquals(3, attempts)
    }

    @Test
    fun 部分応答は成功扱いにしない() = runTest {
        // HTTP 200 で返ってくるのでステータスでは弾けない
        val (source, _) = source {
            respond(OverpassFixtures.PARTIAL_RESPONSE_JSON, HttpStatusCode.OK, jsonHeaders)
        }

        assertFailsWith<OverpassPartialResponseException> { source.fetchArea(area) }
    }

    @Test
    fun 再試行しても駄目なら例外になる() = runTest {
        val (source, _) = source { respondError(HttpStatusCode.GatewayTimeout) }

        assertFailsWith<OverpassException> { source.fetchArea(area) }
    }

    private companion object {
        const val EMPTY_RESPONSE = """{"version":0.6,"elements":[]}"""
    }
}
