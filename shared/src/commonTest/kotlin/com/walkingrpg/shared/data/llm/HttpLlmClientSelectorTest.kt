package com.walkingrpg.shared.data.llm

import com.walkingrpg.shared.domain.setup.LlmFormat
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * フォーマットの振り分け（[HttpLlmClientSelector]）。
 *
 * issue #14 の完了条件「**同一タスクをAnthropic / OpenAIで切り替えられる**」の確認。
 * 同じ依頼（プロンプトも上限も同一）を設定のフォーマットだけ変えて流し、
 * 呼び出し側が差を知らないまま両方に届くことを見る。
 */
class HttpLlmClientSelectorTest {

    @Test
    fun 設定のフォーマットで実装が切り替わる() = runTest {
        assertEquals(LlmFormat.ANTHROPIC, selector().client(LlmFormat.ANTHROPIC).format)
        assertEquals(LlmFormat.OPENAI, selector().client(LlmFormat.OPENAI).format)
    }

    @Test
    fun 同じ依頼をどちらのフォーマットでも投げられる() = runTest {
        val request = testRequest(systemPrompt = "情景だけを書く", userPrompt = "場所の種類: 公園")

        val (anthropicClient, anthropicRequests) = respondingClient(anthropicBody("晴れている。"))
        val (openAiClient, openAiRequests) = respondingClient(openAiBody("晴れている。"))
        val anthropic = AnthropicLlmClient(anthropicClient)
        val openAi = OpenAiLlmClient(openAiClient)
        val selector = HttpLlmClientSelector(anthropic, openAi)

        val fromAnthropic = selector
            .client(anthropicSettings.format)
            .generate(request, anthropicSettings)
        val fromOpenAi = selector
            .client(openAiSettings.format)
            .generate(request, openAiSettings)

        // 呼び出し側から見た結果は同じ形（LlmResponse.text）
        assertEquals("晴れている。", fromAnthropic.text)
        assertEquals("晴れている。", fromOpenAi.text)

        // 送り先と認可方式はフォーマットごとに違う
        assertEquals(
            "https://anthropic.example.invalid/v1/messages",
            anthropicRequests.single().url.toString(),
        )
        assertEquals(
            "https://openai.example.invalid/v1/chat/completions",
            openAiRequests.single().url.toString(),
        )
        // どちらの本文にも同じ事実（プロンプト）が入っている
        listOf(anthropicRequests.single(), openAiRequests.single()).forEach { request ->
            val body = request.bodyText()
            assertTrue("情景だけを書く" in body, body)
            assertTrue("場所の種類: 公園" in body, body)
        }
    }

    private fun selector(): HttpLlmClientSelector {
        val (anthropicClient, _) = respondingClient(anthropicBody("a"))
        val (openAiClient, _) = respondingClient(openAiBody("a"))
        return HttpLlmClientSelector(
            anthropic = AnthropicLlmClient(anthropicClient),
            openAi = OpenAiLlmClient(openAiClient),
        )
    }

    private suspend fun HttpRequestData.bodyText(): String = body.toByteArray().decodeToString()
}
