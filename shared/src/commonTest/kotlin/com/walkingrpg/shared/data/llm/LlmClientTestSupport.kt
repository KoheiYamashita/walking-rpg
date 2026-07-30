package com.walkingrpg.shared.data.llm

import com.walkingrpg.shared.domain.llm.LlmRequest
import com.walkingrpg.shared.domain.setup.LlmConnectionSettings
import com.walkingrpg.shared.domain.setup.LlmFormat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/**
 * `data/llm` のテスト用の足場。実際の通信はせず、フェイクエンジンで
 * 「どこに何を投げるか」と「応答をどう読むか」を見る。
 *
 * APIキー・ベースURLは架空の値（実キーはコードにもテストにも書かない）。
 */

internal val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

internal val anthropicSettings = LlmConnectionSettings(
    format = LlmFormat.ANTHROPIC,
    baseUrl = "https://anthropic.example.invalid",
    model = "test-model",
    apiKey = "dummy-key-for-test",
)

internal val openAiSettings = LlmConnectionSettings(
    format = LlmFormat.OPENAI,
    baseUrl = "https://openai.example.invalid/v1",
    model = "test-model",
    apiKey = "dummy-key-for-test",
)

/** 地点フレーバー相当の依頼（内容は文言に依存しない最小形）。 */
internal fun testRequest(
    systemPrompt: String = "system-instruction",
    userPrompt: String = "user-facts",
    maxTokens: Int = 300,
    allowRetry: Boolean = true,
) = LlmRequest(
    systemPrompt = systemPrompt,
    userPrompt = userPrompt,
    maxTokens = maxTokens,
    allowRetry = allowRetry,
)

/**
 * 本番と同じ設定（[llmHttpClient]）のクライアントと、投げられたリクエストの記録。
 *
 * 設定を共有するのが要点：タイムアウト・リトライの方針がテストと本番でずれると、
 * 「テストは通るが端末では粘りすぎる」を見逃す。
 */
internal fun recordingClient(
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): Pair<HttpClient, MutableList<HttpRequestData>> {
    val requests = mutableListOf<HttpRequestData>()
    val engine = MockEngine { request ->
        requests += request
        handler(request)
    }
    return llmHttpClient(engine) to requests
}

/** 2xxのJSON応答を1つ返すだけのクライアント。 */
internal fun respondingClient(body: String): Pair<HttpClient, MutableList<HttpRequestData>> =
    recordingClient { respond(body, HttpStatusCode.OK, jsonHeaders) }

/** Anthropic Messages API の成功応答（テキストブロック1つ）。 */
internal fun anthropicBody(text: String): String =
    """{"id":"msg_1","type":"message","content":[{"type":"text","text":"$text"}]}"""

/** OpenAI Chat Completions の成功応答（候補1つ）。 */
internal fun openAiBody(text: String): String =
    """{"id":"cmpl_1","choices":[{"index":0,"message":{"role":"assistant","content":"$text"}}]}"""
