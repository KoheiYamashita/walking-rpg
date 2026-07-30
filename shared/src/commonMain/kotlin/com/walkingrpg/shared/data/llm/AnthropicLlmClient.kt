package com.walkingrpg.shared.data.llm

import com.walkingrpg.shared.domain.llm.LlmClient
import com.walkingrpg.shared.domain.llm.LlmFailureKind
import com.walkingrpg.shared.domain.llm.LlmRequest
import com.walkingrpg.shared.domain.llm.LlmResponse
import com.walkingrpg.shared.domain.llm.LlmUnavailableException
import com.walkingrpg.shared.domain.setup.LlmConnectionSettings
import com.walkingrpg.shared.domain.setup.LlmFormat
import io.ktor.client.HttpClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Anthropic Messages API（`POST /v1/messages`）の [LlmClient] 実装。**既定のフォーマット**。
 *
 * design.md §7「Haiku級で十分」に合わせた既定モデルは
 * [LlmFormat.ANTHROPIC] の `defaultModel` に置いてある。
 *
 * ## systemプロンプトはトップレベル
 * Anthropicは `system` を messages とは別のトップレベルのフィールドで受ける
 * （OpenAIは `messages` の1件目に `role: system` を入れる）。この差がフォーマットを
 * 分ける主な理由で、ドメイン層（[LlmRequest]）は差を知らずに
 * 「systemとuserの2本」だけを持つ。
 *
 * 送るパラメータは `model` / `max_tokens` / `system` / `messages` だけに絞る。
 * temperature 等を足すと、OpenAI互換のローカルLLMで弾かれる余地が増えるだけで、
 * 情景の1〜2文には要らない。
 */
internal class AnthropicLlmClient(
    private val httpClient: HttpClient,
) : LlmClient {

    override val format: LlmFormat = LlmFormat.ANTHROPIC

    override suspend fun generate(
        request: LlmRequest,
        settings: LlmConnectionSettings,
    ): LlmResponse {
        val body = buildJsonObject {
            put("model", settings.model)
            put("max_tokens", request.maxTokens)
            // 疎通確認（system無し）でも通るように、空なら送らない
            if (request.systemPrompt.isNotBlank()) put("system", request.systemPrompt)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", request.userPrompt)
                }
            }
        }

        val raw = httpClient.postLlmJson(settings, request, body)
        val blocks = llmJson.decodeOrThrow<AnthropicResponse>(raw, FORMAT_NAME).content
            ?: throw malformed()
        // `content` は複数ブロックに分かれて返ることがある（テキスト以外の型も来うる）。
        // テキストブロックだけを順に繋ぐ。
        val text = blocks.filter { it.type == TEXT_BLOCK }.joinToString("") { it.text.orEmpty() }
        if (text.isBlank()) {
            throw LlmUnavailableException(
                reason = LlmFailureKind.EMPTY_RESPONSE,
                message = "$FORMAT_NAME ${LlmFailureKind.EMPTY_RESPONSE.message}",
            )
        }
        return LlmResponse(text)
    }

    private fun malformed() = LlmUnavailableException(
        reason = LlmFailureKind.MALFORMED_RESPONSE,
        message = "$FORMAT_NAME ${LlmFailureKind.MALFORMED_RESPONSE.message}",
    )

    private companion object {
        const val FORMAT_NAME = "Anthropic Messages:"
        const val TEXT_BLOCK = "text"
    }
}

/**
 * `POST /v1/messages` の応答（必要な部分だけ）。
 *
 * `content` を nullable にしてあるのは、`{}` のような想定外の応答を
 * 「形式が違う」として分類するため（`emptyList` を既定にすると
 * 「本文が空」と区別できなくなる）。
 */
@Serializable
private data class AnthropicResponse(
    val content: List<AnthropicContentBlock>? = null,
)

@Serializable
private data class AnthropicContentBlock(
    @SerialName("type") val type: String? = null,
    val text: String? = null,
)
