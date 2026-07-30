package com.walkingrpg.shared.data.llm

import com.walkingrpg.shared.domain.llm.LlmFailureKind
import com.walkingrpg.shared.domain.llm.LlmUnavailableException
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OpenAI Chat Completions 実装（[OpenAiLlmClient]）。
 *
 * Anthropic版との違い（エンドポイント・認可方式・systemの置き場所・応答の形）を見る。
 * OpenAI互換のエンドポイント（OpenRouter・ローカルLLM等）もこの実装で通るので、
 * 接続先を名指しする箇所が無いことも併せて確認する。
 */
class OpenAiLlmClientTest {

    @Test
    fun chatCompletionsへBearerで投げる() = runTest {
        val (httpClient, requests) = respondingClient(openAiBody("水面が光る。"))

        OpenAiLlmClient(httpClient).generate(testRequest(), openAiSettings)

        val request = requests.single()
        assertEquals("https://openai.example.invalid/v1/chat/completions", request.url.toString())
        assertEquals("Bearer dummy-key-for-test", request.headers[HttpHeaders.Authorization])
        assertNull(request.headers["x-api-key"])
    }

    @Test
    fun systemはmessagesの一件目に入れる() = runTest {
        val (httpClient, requests) = respondingClient(openAiBody("一言。"))

        OpenAiLlmClient(httpClient).generate(
            testRequest(systemPrompt = "情景だけを書く", userPrompt = "場所の種類: 公園"),
            openAiSettings,
        )

        val body = requests.single().body.toByteArray().decodeToString()
        assertTrue("\"model\":\"test-model\"" in body, body)
        assertTrue("\"max_tokens\":300" in body, body)
        // system → user の順（トップレベルの "system" は使わない）
        assertTrue(
            body.indexOf("\"system\"") < body.indexOf("\"user\""),
            body,
        )
        assertTrue("情景だけを書く" in body, body)
        assertTrue("場所の種類: 公園" in body, body)
    }

    @Test
    fun systemが空ならuserだけ送る() = runTest {
        val (httpClient, requests) = respondingClient(openAiBody("a"))

        OpenAiLlmClient(httpClient).generate(
            testRequest(systemPrompt = "", userPrompt = "ping", maxTokens = 1),
            openAiSettings,
        )

        val body = requests.single().body.toByteArray().decodeToString()
        assertFalse("system" in body, body)
        assertTrue("\"content\":\"ping\"" in body, body)
    }

    @Test
    fun 候補から本文を取り出す() = runTest {
        val (httpClient, _) = respondingClient(openAiBody("水面が光る。"))

        val response = OpenAiLlmClient(httpClient).generate(testRequest(), openAiSettings)

        assertEquals("水面が光る。", response.text)
    }

    @Test
    fun 候補が無ければ形式の問題として扱う() = runTest {
        val (httpClient, _) = respondingClient("{}")

        val error = assertFailsWith<LlmUnavailableException> {
            OpenAiLlmClient(httpClient).generate(testRequest(), openAiSettings)
        }

        assertEquals(LlmFailureKind.MALFORMED_RESPONSE, error.reason)
    }

    @Test
    fun 本文が空なら空応答として扱う() = runTest {
        val (httpClient, _) = respondingClient(openAiBody(""))

        val error = assertFailsWith<LlmUnavailableException> {
            OpenAiLlmClient(httpClient).generate(testRequest(), openAiSettings)
        }

        assertEquals(LlmFailureKind.EMPTY_RESPONSE, error.reason)
    }

    @Test
    fun エラー応答を分類する() = runTest {
        val cases = mapOf(
            HttpStatusCode.Unauthorized to LlmFailureKind.UNAUTHORIZED,
            HttpStatusCode.NotFound to LlmFailureKind.NOT_FOUND,
            HttpStatusCode.TooManyRequests to LlmFailureKind.RATE_LIMITED,
        )

        cases.forEach { (status, expected) ->
            val (httpClient, _) = recordingClient { respondError(status) }
            val error = assertFailsWith<LlmUnavailableException> {
                OpenAiLlmClient(httpClient).generate(testRequest(), openAiSettings)
            }
            assertEquals(expected, error.reason, "$status の分類が違う")
        }
    }

    @Test
    fun 例外にURLとAPIキーを載せない() = runTest {
        val (httpClient, _) = recordingClient { respondError(HttpStatusCode.Unauthorized) }

        val error = assertFailsWith<LlmUnavailableException> {
            OpenAiLlmClient(httpClient).generate(testRequest(), openAiSettings)
        }

        val text = error.message.orEmpty() + error.responseExcerpt.orEmpty()
        assertFalse("dummy-key-for-test" in text, text)
        assertFalse("openai.example.invalid" in text, text)
    }
}
