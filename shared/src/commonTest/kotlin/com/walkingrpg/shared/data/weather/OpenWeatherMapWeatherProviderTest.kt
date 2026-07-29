package com.walkingrpg.shared.data.weather

import com.walkingrpg.shared.domain.weather.WeatherCondition
import com.walkingrpg.shared.domain.weather.WeatherQuery
import com.walkingrpg.shared.domain.weather.WeatherUnavailableException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OpenWeatherMap実装（キー必要）。
 *
 * 実際の通信はせず、フェイクエンジンで「応答→共通モデル」の変換と
 * condition id の正規化表を見る。座標は架空のもの。
 */
class OpenWeatherMapWeatherProviderTest {

    private val query = WeatherQuery(
        timestampMs = 1_700_000_000_000L,
        latitude = 12.34,
        longitude = 56.78,
    )

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun provider(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): Pair<OpenWeatherMapWeatherProvider, MutableList<HttpRequestData>> {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            if (status == HttpStatusCode.OK) {
                respond(body, status, jsonHeaders)
            } else {
                respondError(status)
            }
        }
        return OpenWeatherMapWeatherProvider(weatherHttpClient(engine)) to requests
    }

    @Test
    fun 応答を共通モデルに変換する() = runTest {
        val (provider, _) = provider(
            """{"data":[{"dt":1700000000,"temp":13.4,"weather":[{"id":500,"main":"Rain"}]}]}""",
        )

        val observation = provider.observe(query, apiKey = "test-key")

        assertEquals(WeatherCondition.RAIN, observation.condition)
        assertEquals(13.4, observation.temperatureCelsius)
    }

    @Test
    fun 履歴エンドポイントに時刻を秒で渡し気温は摂氏で要求する() = runTest {
        val (provider, requests) = provider(
            """{"data":[{"temp":8.0,"weather":[{"id":800}]}]}""",
        )

        provider.observe(query, apiKey = "test-key")

        val url = requests.single().url
        assertTrue(url.encodedPath.endsWith("/onecall/timemachine"), url.encodedPath)
        assertEquals("1700000000", url.parameters["dt"], "散歩の時刻を訊く（いまの天候ではない）")
        assertEquals("metric", url.parameters["units"])
        assertEquals("test-key", url.parameters["appid"])
    }

    @Test
    fun キーが未入力なら通信せずに未取得として扱う() = runTest {
        val (provider, requests) = provider("""{"data":[{"weather":[{"id":800}]}]}""")

        assertFailsWith<WeatherUnavailableException> { provider.observe(query, apiKey = " ") }
        assertEquals(0, requests.size)
    }

    @Test
    fun エラー応答の例外にキーを載せない() = runTest {
        val (provider, _) = provider("", status = HttpStatusCode.Unauthorized)

        val error = assertFailsWith<WeatherUnavailableException> {
            provider.observe(query, apiKey = "super-secret-key")
        }
        // URLごと出さない（キーも座標もクエリに乗っている）
        assertFalse("super-secret-key" in error.message!!, error.message!!)
        assertFalse("12.34" in error.message!!, error.message!!)
        assertTrue("401" in error.message!!, error.message!!)
    }

    @Test
    fun 応答に天候が無ければ未取得として扱う() = runTest {
        val (provider, _) = provider("""{"data":[]}""")

        assertFailsWith<WeatherUnavailableException> { provider.observe(query, "test-key") }
    }

    @Test
    fun conditionIdを共通の天候に正規化する() {
        // 出典は weatherConditionFromOwmConditionId のKDoc（openweathermap.org の condition codes）
        val expected = mapOf(
            200 to WeatherCondition.THUNDER,
            232 to WeatherCondition.THUNDER,
            300 to WeatherCondition.RAIN,
            321 to WeatherCondition.RAIN,
            500 to WeatherCondition.RAIN,
            531 to WeatherCondition.RAIN,
            600 to WeatherCondition.SNOW,
            622 to WeatherCondition.SNOW,
            701 to WeatherCondition.FOG,
            741 to WeatherCondition.FOG,
            781 to WeatherCondition.FOG,
            800 to WeatherCondition.CLEAR,
            801 to WeatherCondition.CLEAR,
            802 to WeatherCondition.CLOUDY,
            804 to WeatherCondition.CLOUDY,
        )

        expected.forEach { (id, condition) ->
            assertEquals(condition, weatherConditionFromOwmConditionId(id), "OWM $id")
        }
    }

    @Test
    fun 知らないconditionIdは天候不明にする() {
        listOf(0, 100, 400, 700, 900).forEach { id ->
            assertEquals(WeatherCondition.UNKNOWN, weatherConditionFromOwmConditionId(id), "OWM $id")
        }
    }
}
