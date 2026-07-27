package com.walkingrpg.shared.data.llm

import com.walkingrpg.shared.domain.setup.LlmConnectionFailure
import com.walkingrpg.shared.domain.setup.LlmConnectionSettings
import com.walkingrpg.shared.domain.setup.LlmConnectionTestResult
import com.walkingrpg.shared.domain.setup.LlmFormat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 疎通テスターの検証。実際の通信はせず、フェイクエンジンで
 * 「どこに何を投げるか」と「失敗をどう分類するか」を見る。
 *
 * APIキーは架空の文字列（実キーはコードにもテストにも書かない）。
 */
class HttpLlmConnectionTesterTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val anthropic = LlmConnectionSettings(
        format = LlmFormat.ANTHROPIC,
        baseUrl = "https://anthropic.example.invalid",
        model = "test-model",
        apiKey = "dummy-key-for-test",
    )

    private val openAi = LlmConnectionSettings(
        format = LlmFormat.OPENAI,
        baseUrl = "https://openai.example.invalid/v1",
        model = "test-model",
        apiKey = "dummy-key-for-test",
    )

    private fun tester(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Pair<HttpLlmConnectionTester, MutableList<HttpRequestData>> {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        return HttpLlmConnectionTester(llmHttpClient(engine)) to requests
    }

    @Test
    fun 二xxなら成功() = runTest {
        val (tester, _) = tester { respond("{}", HttpStatusCode.OK, jsonHeaders) }

        assertEquals(LlmConnectionTestResult.Success, tester.test(anthropic))
    }

    @Test
    fun Anthropicはmessagesへ投げ必須ヘッダを付ける() = runTest {
        val (tester, requests) = tester { respond("{}", HttpStatusCode.OK, jsonHeaders) }

        tester.test(anthropic)

        val request = requests.single()
        assertEquals(
            "https://anthropic.example.invalid/v1/messages",
            request.url.toString(),
        )
        assertEquals("dummy-key-for-test", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        // 認可方式が混ざっていないこと
        assertNull(request.headers[HttpHeaders.Authorization])

        val body = request.body.toByteArray().decodeToString()
        assertTrue("\"model\":\"test-model\"" in body, body)
        assertTrue("\"max_tokens\":1" in body, body)
    }

    @Test
    fun OpenAIはchatCompletionsへBearerで投げる() = runTest {
        val (tester, requests) = tester { respond("{}", HttpStatusCode.OK, jsonHeaders) }

        tester.test(openAi)

        val request = requests.single()
        assertEquals(
            "https://openai.example.invalid/v1/chat/completions",
            request.url.toString(),
        )
        assertEquals("Bearer dummy-key-for-test", request.headers[HttpHeaders.Authorization])
        assertNull(request.headers["x-api-key"])
    }

    @Test
    fun ベースURLの末尾スラッシュを二重にしない() = runTest {
        val (tester, requests) = tester { respond("{}", HttpStatusCode.OK, jsonHeaders) }

        tester.test(anthropic.copy(baseUrl = "https://anthropic.example.invalid/"))

        assertEquals(
            "https://anthropic.example.invalid/v1/messages",
            requests.single().url.toString(),
        )
    }

    @Test
    fun 認証エラーはキーの問題として伝える() = runTest {
        val (tester, _) = tester { respondError(HttpStatusCode.Unauthorized) }

        val result = tester.test(anthropic)

        assertIs<LlmConnectionTestResult.Failure>(result)
        assertEquals(LlmConnectionFailure.UNAUTHORIZED, result.reason)
        assertTrue("HTTP 401" in result.message, result.message)
    }

    @Test
    fun 各ステータスを人間向けの分類に落とす() = runTest {
        val cases = mapOf(
            HttpStatusCode.Forbidden to LlmConnectionFailure.UNAUTHORIZED,
            HttpStatusCode.NotFound to LlmConnectionFailure.NOT_FOUND,
            HttpStatusCode.TooManyRequests to LlmConnectionFailure.RATE_LIMITED,
            HttpStatusCode.InternalServerError to LlmConnectionFailure.SERVER_ERROR,
            HttpStatusCode.BadRequest to LlmConnectionFailure.UNEXPECTED,
        )

        cases.forEach { (status, expected) ->
            val (tester, _) = tester { respondError(status) }
            val result = tester.test(anthropic)
            assertIs<LlmConnectionTestResult.Failure>(result)
            assertEquals(expected, result.reason, "$status の分類が違う")
        }
    }

    @Test
    fun 通信できない場合はネットワーク不達として扱う() = runTest {
        val (tester, _) = tester { throw IllegalStateException("接続できません") }

        val result = tester.test(anthropic)

        assertIs<LlmConnectionTestResult.Failure>(result)
        assertEquals(LlmConnectionFailure.NETWORK, result.reason)
    }

    @Test
    fun 失敗しても再試行しない() = runTest {
        // キーが違うのに何度も投げない（レート制限を踏みに行かない）
        val (tester, requests) = tester { respondError(HttpStatusCode.ServiceUnavailable) }

        tester.test(anthropic)

        assertEquals(1, requests.size)
    }
}
