package com.walkingrpg.shared.data.llm

import com.walkingrpg.shared.domain.setup.LlmConnectionFailure
import com.walkingrpg.shared.domain.setup.LlmConnectionSettings
import com.walkingrpg.shared.domain.setup.LlmConnectionTestResult
import com.walkingrpg.shared.domain.setup.LlmConnectionTester
import com.walkingrpg.shared.domain.setup.LlmFormat
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 疎通テスト用のHTTPクライアント。
 *
 * OSM取り込み（[com.walkingrpg.shared.data.osm.osmHttpClient]）とは方針が違うので分ける：
 * リトライしない（キーが違うのに何度も投げない）、タイムアウトは短め
 * （セットアップ画面で待たせない）。
 */
internal fun llmHttpClient(): HttpClient = HttpClient { installLlmDefaults() }

/** テストからフェイクエンジンを差し込むためのオーバーロード（設定は本番と同一）。 */
internal fun llmHttpClient(engine: HttpClientEngine): HttpClient =
    HttpClient(engine) { installLlmDefaults() }

private fun HttpClientConfig<*>.installLlmDefaults() {
    install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        socketTimeoutMillis = REQUEST_TIMEOUT_MS
    }
}

private const val REQUEST_TIMEOUT_MS = 30_000L
private const val CONNECT_TIMEOUT_MS = 10_000L

/**
 * [LlmConnectionTester] の実装。**選択フォーマットで最小のリクエストを1回投げ、2xxなら成功**。
 *
 * 生成結果は読まない。「キーが通るか」「接続先とモデル名が実在するか」だけが分かればよい。
 * 本格的な `LlmClient`（生成・リトライ・縮退・プロンプト）は issue #14 が入れる。
 * その際はこのクラスを消して、`LlmClient` 側に [LlmConnectionTester] を実装させれば
 * セットアップ画面はそのまま動く。
 */
internal class HttpLlmConnectionTester(
    private val httpClient: HttpClient,
) : LlmConnectionTester {

    override suspend fun test(settings: LlmConnectionSettings): LlmConnectionTestResult {
        val response = try {
            httpClient.post(settings.endpointUrl()) {
                contentType(ContentType.Application.Json)
                applyAuth(settings)
                setBody(settings.minimalRequestBody().toString())
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (timeout: HttpRequestTimeoutException) {
            return LlmConnectionTestResult.Failure(LlmConnectionFailure.TIMEOUT)
        } catch (error: Throwable) {
            // 名前解決・接続拒否・TLSなど。原因の文字列はそのまま出さず種別だけ添える。
            return LlmConnectionTestResult.Failure(
                reason = LlmConnectionFailure.NETWORK,
                detail = error.message?.take(MAX_DETAIL_LENGTH),
            )
        }

        return response.toResult()
    }

    private suspend fun HttpResponse.toResult(): LlmConnectionTestResult {
        val code = status.value
        if (code in 200..299) return LlmConnectionTestResult.Success

        val reason = when {
            code == 401 || code == 403 -> LlmConnectionFailure.UNAUTHORIZED
            code == 404 -> LlmConnectionFailure.NOT_FOUND
            code == 429 -> LlmConnectionFailure.RATE_LIMITED
            code >= 500 -> LlmConnectionFailure.SERVER_ERROR
            else -> LlmConnectionFailure.UNEXPECTED
        }
        // 400系はモデル名の綴り間違いが多く、本文にその旨が書いてあることが多い。
        // 切り分けの手掛かりとして先頭だけ見せる（APIキーは本文に含まれない）。
        val detail = "HTTP $code: ${bodyAsText().take(MAX_DETAIL_LENGTH)}"
        return LlmConnectionTestResult.Failure(reason = reason, detail = detail)
    }

    private companion object {
        const val MAX_DETAIL_LENGTH = 300
    }
}

/**
 * 疎通用のエンドポイント。ベースURLは各社の慣習に合わせて既定値を置いてあるので、
 * フォーマットごとに続きのパスを足すだけでよい
 * （Anthropic: `https://api.anthropic.com` + `/v1/messages`、
 *  OpenAI互換: `https://api.openai.com/v1` + `/chat/completions`）。
 */
internal fun LlmConnectionSettings.endpointUrl(): String {
    val base = baseUrl.trim().trimEnd('/')
    return when (format) {
        LlmFormat.ANTHROPIC -> "$base/v1/messages"
        LlmFormat.OPENAI -> "$base/chat/completions"
    }
}

/**
 * 最小のリクエスト本文。生成結果は捨てるので出力は1トークンで足りる。
 *
 * `model` / `max_tokens` / `messages` はAnthropic・OpenAI Chat Completions で
 * 同じ綴りなので、本文は共通で通る（OpenAI互換エンドポイントも同様）。
 * 実装差で弾かれないよう、これ以上のパラメータは足さない。
 */
internal fun LlmConnectionSettings.minimalRequestBody(): JsonObject = buildJsonObject {
    put("model", model)
    put("max_tokens", 1)
    putJsonArray("messages") {
        addJsonObject {
            put("role", "user")
            put("content", "ping")
        }
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.applyAuth(settings: LlmConnectionSettings) {
    when (settings.format) {
        LlmFormat.ANTHROPIC -> {
            header("x-api-key", settings.apiKey)
            // Anthropic Messages API は必須ヘッダ
            header("anthropic-version", ANTHROPIC_API_VERSION)
        }

        LlmFormat.OPENAI -> header(HttpHeaders.Authorization, "Bearer ${settings.apiKey}")
    }
}

private const val ANTHROPIC_API_VERSION = "2023-06-01"
