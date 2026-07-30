package com.walkingrpg.shared.data.llm

import com.walkingrpg.shared.domain.llm.LlmClient
import com.walkingrpg.shared.domain.llm.LlmClientSelector
import com.walkingrpg.shared.domain.setup.LlmFormat

/**
 * 設定で選ばれたフォーマットの実装を返す [LlmClientSelector]。
 *
 * 2実装を具体型でコンストラクタに取るのは、DI（Koin）が同じ型の複数定義を
 * 区別できないため（同じ `LlmClient` として2つ登録すると、どれが注入されるか分からない）。
 * ここだけが2実装を名指しする場所になる（天候の `HttpWeatherProviderSelector` と同じ形）。
 *
 * [LlmFormat] に対する `when` なので、フォーマットを増やしたら**コンパイルエラーで**
 * 分岐の追加漏れに気付ける。
 */
internal class HttpLlmClientSelector(
    private val anthropic: AnthropicLlmClient,
    private val openAi: OpenAiLlmClient,
) : LlmClientSelector {

    override fun client(format: LlmFormat): LlmClient = when (format) {
        LlmFormat.ANTHROPIC -> anthropic
        LlmFormat.OPENAI -> openAi
    }
}
