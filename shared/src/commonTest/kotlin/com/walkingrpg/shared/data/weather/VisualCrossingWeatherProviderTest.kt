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
 * Visual Crossing実装（キー必要）。
 *
 * 実際の通信はせず、フェイクエンジンで「応答→共通モデル」の変換と
 * icon の正規化表を見る。座標は架空のもの。
 */
class VisualCrossingWeatherProviderTest {

    private val query = WeatherQuery(
        timestampMs = 1_700_000_000_000L,
        latitude = 12.34,
        longitude = 56.78,
    )

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun provider(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): Pair<VisualCrossingWeatherProvider, MutableList<HttpRequestData>> {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            if (status == HttpStatusCode.OK) {
                respond(body, status, jsonHeaders)
            } else {
                respondError(status)
            }
        }
        return VisualCrossingWeatherProvider(weatherHttpClient(engine)) to requests
    }

    @Test
    fun 応答を共通モデルに変換する() = runTest {
        val (provider, _) = provider(
            """{"days":[{"hours":[{"datetimeEpoch":1700000400,"temp":13.4,"icon":"rain"}]}]}""",
        )

        val observation = provider.observe(query, apiKey = "test-key")

        assertEquals(WeatherCondition.RAIN, observation.condition)
        assertEquals(13.4, observation.temperatureCelsius)
    }

    @Test
    fun 地点と時刻はパスに乗せ時刻はepoch秒で送る() = runTest {
        val (provider, requests) = provider(
            """{"days":[{"hours":[{"temp":8.0,"icon":"clear-day"}]}]}""",
        )

        provider.observe(query, apiKey = "test-key")

        val url = requests.single().url
        assertTrue(url.encodedPath.endsWith("/12.34,56.78/1700000000"), url.encodedPath)
        assertEquals("metric", url.parameters["unitGroup"])
        // 欲しいのは散歩をした時刻の天候。呼び出し時点の実況（current）ではない
        assertEquals("hours", url.parameters["include"])
        assertTrue("datetimeEpoch" in url.parameters["elements"]!!, "どの時刻の値かを突き合わせる")
        assertEquals("test-key", url.parameters["key"])
    }

    @Test
    fun 実況ではなく散歩した時刻の時間別を採る() = runTest {
        // 後日リトライの再現：currentConditions は「いま」の晴れ、
        // hours には散歩をした時刻の雨が入っている
        val (provider, _) = provider(
            """{
                "currentConditions":{"datetimeEpoch":1700600000,"temp":22.0,"icon":"clear-day"},
                "days":[{"temp":5.0,"icon":"cloudy","hours":[
                    {"datetimeEpoch":1699996800,"temp":9.0,"icon":"cloudy"},
                    {"datetimeEpoch":1700000400,"temp":2.5,"icon":"snow"}
                ]}]
            }""",
        )

        val observation = provider.observe(query, apiKey = "test-key")

        assertEquals(WeatherCondition.SNOW, observation.condition, "問い合わせた時刻に最も近い1時間")
        assertEquals(2.5, observation.temperatureCelsius)
    }

    @Test
    fun 時間別に時刻が無ければ先頭を採る() = runTest {
        // 単一時刻の問い合わせなら返る時間別はその1件だけなので、先頭で合っている
        val (provider, _) = provider(
            """{"days":[{"temp":5.0,"icon":"cloudy","hours":[{"temp":2.5,"icon":"snow"}]}]}""",
        )

        val observation = provider.observe(query, apiKey = "test-key")

        assertEquals(WeatherCondition.SNOW, observation.condition)
        assertEquals(2.5, observation.temperatureCelsius)
    }

    @Test
    fun 時間別が無ければ日別に落とす() = runTest {
        val (provider, _) = provider(
            """{"currentConditions":{"temp":22.0,"icon":"clear-day"},
                "days":[{"temp":5.0,"icon":"cloudy"}]}""",
        )

        val observation = provider.observe(query, apiKey = "test-key")

        assertEquals(WeatherCondition.CLOUDY, observation.condition, "実況より日別が先")
        assertEquals(5.0, observation.temperatureCelsius)
    }

    @Test
    fun 時間別も日別も無ければ最後の砦としてcurrentConditionsを使う() = runTest {
        val (provider, _) = provider("""{"currentConditions":{"temp":22.0,"icon":"clear-day"}}""")

        val observation = provider.observe(query, apiKey = "test-key")

        assertEquals(WeatherCondition.CLEAR, observation.condition)
        assertEquals(22.0, observation.temperatureCelsius)
    }

    @Test
    fun キーが未入力なら通信せずに未取得として扱う() = runTest {
        val (provider, requests) = provider("""{"days":[{"hours":[{"icon":"clear-day"}]}]}""")

        assertFailsWith<WeatherUnavailableException> { provider.observe(query, apiKey = "") }
        assertEquals(0, requests.size)
    }

    @Test
    fun エラー応答の例外にキーを載せない() = runTest {
        val (provider, _) = provider("", status = HttpStatusCode.TooManyRequests)

        val error = assertFailsWith<WeatherUnavailableException> {
            provider.observe(query, apiKey = "super-secret-key")
        }
        assertFalse("super-secret-key" in error.message!!, error.message!!)
        assertFalse("12.34" in error.message!!, error.message!!)
        assertTrue("429" in error.message!!, error.message!!)
    }

    @Test
    fun 応答に天候が無ければ未取得として扱う() = runTest {
        val (provider, _) = provider("""{"days":[]}""")

        assertFailsWith<WeatherUnavailableException> { provider.observe(query, "test-key") }
    }

    @Test
    fun iconを共通の天候に正規化する() {
        // 出典は weatherConditionFromVisualCrossingIcon のKDoc（icon set 2）
        val expected = mapOf(
            "snow" to WeatherCondition.SNOW,
            "snow-showers-day" to WeatherCondition.SNOW,
            "snow-showers-night" to WeatherCondition.SNOW,
            "thunder-rain" to WeatherCondition.THUNDER,
            "thunder-showers-day" to WeatherCondition.THUNDER,
            "thunder-showers-night" to WeatherCondition.THUNDER,
            "rain" to WeatherCondition.RAIN,
            "showers-day" to WeatherCondition.RAIN,
            "showers-night" to WeatherCondition.RAIN,
            "fog" to WeatherCondition.FOG,
            "cloudy" to WeatherCondition.CLOUDY,
            "partly-cloudy-day" to WeatherCondition.CLOUDY,
            "partly-cloudy-night" to WeatherCondition.CLOUDY,
            "clear-day" to WeatherCondition.CLEAR,
            "clear-night" to WeatherCondition.CLEAR,
        )

        expected.forEach { (icon, condition) ->
            assertEquals(condition, weatherConditionFromVisualCrossingIcon(icon), icon)
        }
    }

    @Test
    fun 風と未知のiconは天候不明にする() {
        // wind は共通モデルに枠が無い。晴れとも曇りとも決めず不明にする（KDoc参照）
        listOf("wind", "hail", "").forEach { icon ->
            assertEquals(
                WeatherCondition.UNKNOWN,
                weatherConditionFromVisualCrossingIcon(icon),
                icon,
            )
        }
    }
}
