package com.walkingrpg.shared.data.llm

import com.walkingrpg.shared.domain.llm.LlmClient
import com.walkingrpg.shared.domain.llm.LlmFailureKind
import com.walkingrpg.shared.domain.llm.LlmRequest
import com.walkingrpg.shared.domain.llm.LlmResponse
import com.walkingrpg.shared.domain.llm.LlmUnavailableException
import com.walkingrpg.shared.domain.setup.LlmConnectionSettings
import com.walkingrpg.shared.domain.setup.LlmFormat
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * OpenAI Chat Completions（`POST /chat/completions`）の [LlmClient] 実装。
 *
 * **OpenAI互換のエンドポイント（OpenRouter・ローカルLLM等）もこれで通る**
 * （architecture.md §5「OpenAI互換エンドポイントがそのまま使える」）。
 * ベースURLとモデル名はユーザー設定なので、この実装は接続先を名指ししない。
 *
 * ## systemは messages の1件目
 * Anthropicがトップレベルの `system` で受けるのに対し、こちらは `messages` の
 * 1件目に `role: system` を入れる（[AnthropicLlmClient] のKDoc）。
 *
 * `max_tokens` を使っているのは、互換エンドポイントで最も広く通る綴りだから
 * （OpenAI本家は `max_completion_tokens` を推しているが、`max_tokens` も受ける。
 * 一方でローカルLLMのサーバは `max_completion_tokens` を知らないことが多い）。
 */
internal class OpenAiLlmClient(
    private val httpClient: HttpClient,
) : LlmClient {

    override val format: LlmFormat = LlmFormat.OPENAI

    override suspend fun generate(
        request: LlmRequest,
        settings: LlmConnectionSettings,
    ): LlmResponse {
        val body = buildJsonObject {
            put("model", settings.model)
            put("max_tokens", request.maxTokens)
            putJsonArray("messages") {
                // 疎通確認（system無し）でも通るように、空なら送らない
                if (request.systemPrompt.isNotBlank()) {
                    addJsonObject {
                        put("role", "system")
                        put("content", request.systemPrompt)
                    }
                }
                addJsonObject {
                    put("role", "user")
                    put("content", request.userPrompt)
                }
            }
        }

        val raw = httpClient.postLlmJson(settings, request, body)
        val choices = llmJson.decodeOrThrow<OpenAiResponse>(raw, FORMAT_NAME).choices
            ?: throw failure(LlmFailureKind.MALFORMED_RESPONSE)
        // 1リクエスト1候補（n は送っていない）。念のため先頭だけを見る。
        val text = choices.firstOrNull()?.message?.content
            ?: throw failure(LlmFailureKind.MALFORMED_RESPONSE)
        if (text.isBlank()) throw failure(LlmFailureKind.EMPTY_RESPONSE)
        return LlmResponse(text)
    }

    private fun failure(reason: LlmFailureKind) = LlmUnavailableException(
        reason = reason,
        message = "$FORMAT_NAME ${reason.message}",
    )

    private companion object {
        const val FORMAT_NAME = "OpenAI Chat Completions:"
    }
}

/**
 * `POST /chat/completions` の応答（必要な部分だけ）。
 *
 * `choices` を nullable にしてあるのは、`{}` のような想定外の応答を
 * 「形式が違う」として分類するため（Anthropic側の応答型と同じ理由）。
 */
@Serializable
private data class OpenAiResponse(
    val choices: List<OpenAiChoice>? = null,
)

@Serializable
private data class OpenAiChoice(
    val message: OpenAiMessage? = null,
)

@Serializable
private data class OpenAiMessage(
    val content: String? = null,
)
