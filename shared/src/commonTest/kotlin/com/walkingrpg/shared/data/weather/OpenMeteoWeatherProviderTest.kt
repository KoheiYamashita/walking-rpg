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
import kotlin.test.assertTrue

/**
 * Open-Meteo実装（キー不要・既定のプロバイダ）。
 *
 * 実際の通信はせず、フェイクエンジンで「応答→共通モデル」の変換と、
 * WMO weather code の正規化表を見る。座標は架空のもの。
 */
class OpenMeteoWeatherProviderTest {

    private val query = WeatherQuery(
        // 2023-11-14T22:13:20Z（UTC）。時刻の丸め方を見たいので中途半端な分秒にしてある
        timestampMs = 1_700_000_000_000L,
        latitude = 12.34,
        longitude = 56.78,
    )

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun provider(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): Pair<OpenMeteoWeatherProvider, MutableList<HttpRequestData>> {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            if (status == HttpStatusCode.OK) {
                respond(body, status, jsonHeaders)
            } else {
                respondError(status)
            }
        }
        return OpenMeteoWeatherProvider(weatherHttpClient(engine)) to requests
    }

    @Test
    fun 応答を共通モデルに変換する() = runTest {
        val (provider, _) = provider(
            """{"hourly":{"time":["2023-11-14T22:00"],"weather_code":[61],"temperature_2m":[13.4]}}""",
        )

        val observation = provider.observe(query, apiKey = "")

        assertEquals(WeatherCondition.RAIN, observation.condition)
        assertEquals(13.4, observation.temperatureCelsius)
    }

    @Test
    fun 時刻はUTCの正時で座標とともにクエリに乗る() = runTest {
        val (provider, requests) = provider(
            """{"hourly":{"time":["2023-11-14T22:00"],"weather_code":[0],"temperature_2m":[8.0]}}""",
        )

        provider.observe(query, apiKey = "")

        val parameters = requests.single().url.parameters
        assertEquals("12.34", parameters["latitude"])
        assertEquals("56.78", parameters["longitude"])
        assertEquals("2023-11-14T22:00", parameters["start_hour"])
        assertEquals("2023-11-14T22:00", parameters["end_hour"])
        assertEquals("UTC", parameters["timezone"], "端末のタイムゾーンは送らない")
    }

    @Test
    fun 気温が無くても天候だけで成立する() = runTest {
        val (provider, _) = provider("""{"hourly":{"weather_code":[3],"temperature_2m":[]}}""")

        val observation = provider.observe(query, apiKey = "")

        assertEquals(WeatherCondition.CLOUDY, observation.condition)
        assertEquals(null, observation.temperatureCelsius)
    }

    @Test
    fun 取得可能期間の外は値が無いので未取得として扱う() = runTest {
        // 範囲外を指定したときの応答（配列が空）
        val (provider, _) = provider("""{"hourly":{"time":[],"weather_code":[],"temperature_2m":[]}}""")

        assertFailsWith<WeatherUnavailableException> { provider.observe(query, apiKey = "") }
    }

    @Test
    fun エラー応答は未取得として扱う() = runTest {
        val (provider, _) = provider("", status = HttpStatusCode.BadGateway)

        val error = assertFailsWith<WeatherUnavailableException> { provider.observe(query, "") }
        assertTrue("502" in error.message!!, error.message!!)
    }

    @Test
    fun WMOコードを共通の天候に正規化する() {
        // 出典は weatherConditionFromWmoCode のKDoc（WMO code table 4677 の Open-Meteo 簡約版）
        val expected = mapOf(
            0 to WeatherCondition.CLEAR,
            1 to WeatherCondition.CLEAR,
            2 to WeatherCondition.CLOUDY,
            3 to WeatherCondition.CLOUDY,
            45 to WeatherCondition.FOG,
            48 to WeatherCondition.FOG,
            51 to WeatherCondition.RAIN,
            55 to WeatherCondition.RAIN,
            57 to WeatherCondition.RAIN,
            61 to WeatherCondition.RAIN,
            65 to WeatherCondition.RAIN,
            67 to WeatherCondition.RAIN,
            80 to WeatherCondition.RAIN,
            82 to WeatherCondition.RAIN,
            71 to WeatherCondition.SNOW,
            75 to WeatherCondition.SNOW,
            77 to WeatherCondition.SNOW,
            85 to WeatherCondition.SNOW,
            86 to WeatherCondition.SNOW,
            95 to WeatherCondition.THUNDER,
            96 to WeatherCondition.THUNDER,
            99 to WeatherCondition.THUNDER,
        )

        expected.forEach { (code, condition) ->
            assertEquals(condition, weatherConditionFromWmoCode(code), "WMO $code")
        }
    }

    @Test
    fun 知らないWMOコードは天候不明にする() {
        // 表に無い値が来ても落とさない（相手の仕様が増えたときに壊れない）
        listOf(-1, 4, 30, 100).forEach { code ->
            assertEquals(WeatherCondition.UNKNOWN, weatherConditionFromWmoCode(code), "WMO $code")
        }
    }
}
