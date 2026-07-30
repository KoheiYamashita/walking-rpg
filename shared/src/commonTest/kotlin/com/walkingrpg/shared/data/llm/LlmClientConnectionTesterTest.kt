package com.walkingrpg.shared.data.llm

import com.walkingrpg.shared.domain.llm.LlmClient
import com.walkingrpg.shared.domain.llm.LlmClientSelector
import com.walkingrpg.shared.domain.setup.LlmConnectionFailure
import com.walkingrpg.shared.domain.setup.LlmConnectionTestResult
import com.walkingrpg.shared.domain.setup.LlmFormat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 疎通確認（[LlmClientConnectionTester]）。
 *
 * issue #6 の `HttpLlmConnectionTester` を置き換えたもの。**セットアップ画面の振る舞いを
 * 変えていない**ことがここの主題：2xxなら成功、失敗は「何を直せばいいか」が分かる分類に落ちる。
 * 加えて、確認に使う経路が生成と同じ（[LlmClient]）ことを見る。
 */
class LlmClientConnectionTesterTest {

    private fun tester(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Pair<LlmClientConnectionTester, MutableList<HttpRequestData>> {
        val (httpClient, requests) = recordingClient(handler)
        return LlmClientConnectionTester(selectorOf(httpClient)) to requests
    }

    private fun selectorOf(httpClient: HttpClient): LlmClientSelector = HttpLlmClientSelector(
        anthropic = AnthropicLlmClient(httpClient),
        openAi = OpenAiLlmClient(httpClient),
    )

    @Test
    fun 生成できれば成功() = runTest {
        val (tester, _) = tester { respond(anthropicBody("p"), HttpStatusCode.OK, jsonHeaders) }

        assertEquals(LlmConnectionTestResult.Success, tester.test(anthropicSettings))
    }

    @Test
    fun 本文が空でも成功() = runTest {
        // max_tokens=1 なので1トークンも出ないことは普通に起きる。
        // 2xxが返っている＝キー・URL・モデル名は通った
        val (tester, _) = tester { respond("""{"content":[]}""", HttpStatusCode.OK, jsonHeaders) }

        assertEquals(LlmConnectionTestResult.Success, tester.test(anthropicSettings))
    }

    @Test
    fun 生成経路と同じ形の最小リクエストを一度だけ投げる() = runTest {
        val (tester, requests) = tester { respond(anthropicBody("p"), HttpStatusCode.OK, jsonHeaders) }

        tester.test(anthropicSettings)

        val request = requests.single()
        assertEquals("https://anthropic.example.invalid/v1/messages", request.url.toString())
        assertEquals("dummy-key-for-test", request.headers["x-api-key"])
        val body = request.body.toByteArray().decodeToString()
        assertTrue("\"max_tokens\":1" in body, body)
        assertFalse("\"system\"" in body, body)
    }

    @Test
    fun OpenAIを選べばそちらへ投げる() = runTest {
        val (tester, requests) = tester {
            respond(openAiBody("p"), HttpStatusCode.OK, jsonHeaders)
        }

        assertEquals(LlmConnectionTestResult.Success, tester.test(openAiSettings))
        assertEquals(
            "https://openai.example.invalid/v1/chat/completions",
            requests.single().url.toString(),
        )
    }

    @Test
    fun 認証エラーはキーの問題として伝える() = runTest {
        val (tester, _) = tester { respondError(HttpStatusCode.Unauthorized) }

        val result = tester.test(anthropicSettings)

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
            HttpStatusCode.BadRequest to LlmConnectionFailure.UNEXPECTED,
        )

        cases.forEach { (status, expected) ->
            val (tester, _) = tester { respondError(status) }
            val result = tester.test(anthropicSettings)
            assertIs<LlmConnectionTestResult.Failure>(result)
            assertEquals(expected, result.reason, "$status の分類が違う")
        }
    }

    @Test
    fun モデル名の綴り間違いは応答本文の先頭で切り分けられる() = runTest {
        val (tester, _) = tester {
            respond("""{"error":{"message":"model not found"}}""", HttpStatusCode.BadRequest)
        }

        val result = tester.test(anthropicSettings)

        assertIs<LlmConnectionTestResult.Failure>(result)
        assertTrue("model not found" in result.message, result.message)
    }

    @Test
    fun LLMでない接続先は失敗にする() = runTest {
        // 2xxでもJSONの形が違う（社内プロキシのログイン画面など）。ここを通すと
        // 「セットアップは通ったのに文章が一切生成されない」状態でプレイが始まる
        val (tester, _) = tester { respond("<html>login</html>", HttpStatusCode.OK, jsonHeaders) }

        val result = tester.test(anthropicSettings)

        assertIs<LlmConnectionTestResult.Failure>(result)
        assertEquals(LlmConnectionFailure.UNEXPECTED, result.reason)
    }

    @Test
    fun 通信できない場合はネットワーク不達として扱う() = runTest {
        val (tester, _) = tester { throw IllegalStateException("接続できません") }

        val result = tester.test(anthropicSettings)

        assertIs<LlmConnectionTestResult.Failure>(result)
        assertEquals(LlmConnectionFailure.NETWORK, result.reason)
    }

    @Test
    fun 例外の生メッセージは画面に出さない() = runTest {
        // 例外メッセージには接続先URLやプロキシ設定など環境固有の文字列が混ざりうる
        val raw = "Failed to connect to proxy.internal.example/10.0.0.1:8080"
        val (tester, _) = tester { throw IllegalStateException(raw) }

        val result = tester.test(anthropicSettings)

        assertIs<LlmConnectionTestResult.Failure>(result)
        assertFalse(raw in result.message, result.message)
        assertFalse("10.0.0.1" in result.message, result.message)
    }

    @Test
    fun 失敗しても再試行しない() = runTest {
        // ベースURLの綴り間違いで待たせない（allowRetry = false）
        val (tester, requests) = tester { respondError(HttpStatusCode.ServiceUnavailable) }

        val result = tester.test(anthropicSettings)

        assertIs<LlmConnectionTestResult.Failure>(result)
        assertEquals(LlmConnectionFailure.SERVER_ERROR, result.reason)
        assertEquals(1, requests.size)
    }

    @Test
    fun フォーマットの取り違えを起こさない() = runTest {
        // 設定がOpenAIなのにAnthropic実装に投げてしまうと、疎通は通るのに生成で落ちる
        val (httpClient, requests) = respondingClient(openAiBody("p"))
        val tester = LlmClientConnectionTester(selectorOf(httpClient))

        tester.test(openAiSettings.copy(format = LlmFormat.OPENAI))

        assertTrue(
            requests.single().url.toString().endsWith("/chat/completions"),
            requests.single().url.toString(),
        )
    }
}
