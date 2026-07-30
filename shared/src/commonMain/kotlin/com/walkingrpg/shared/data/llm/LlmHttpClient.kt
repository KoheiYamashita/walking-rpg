package com.walkingrpg.shared.data.llm

import com.walkingrpg.shared.domain.llm.LlmFailureKind
import com.walkingrpg.shared.domain.llm.LlmRequest
import com.walkingrpg.shared.domain.llm.LlmUnavailableException
import com.walkingrpg.shared.domain.setup.LlmConnectionSettings
import com.walkingrpg.shared.domain.setup.LlmFormat
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.retry
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * LLM呼び出し用のHTTPクライアント。
 *
 * OSM取り込み（[com.walkingrpg.shared.data.osm.osmHttpClient]）・天候
 * （[com.walkingrpg.shared.data.weather.weatherHttpClient]）とは方針が違うので分ける：
 *
 * - **リトライは通信失敗と5xxだけ**：401（キーが違う）を何度も投げてもキーは通らないし、
 *   レート制限を踏みに行くだけ。Ktorの `retryOnExceptionOrServerErrors` は
 *   4xxを再試行しないので、この条件がそのまま満たされる
 * - **疎通確認だけはリトライしない**：ベースURLの綴り間違いで名前解決に失敗している
 *   ときに粘られると、入力を直したいユーザーを待たせるだけ。呼び出し側が
 *   [LlmRequest.allowRetry] を `false` にして1リクエストに落とす（[postLlmJson]）
 * - **タイムアウトは長め**：生成は数秒〜十数秒かかる。ただし待つのは家の中だけで、
 *   散歩中の表示は事前生成分と定型文で成立する（design.md §7）
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
    install(HttpRequestRetry) {
        retryOnExceptionOrServerErrors(maxRetries = MAX_RETRIES)
        delayMillis { retry -> RETRY_DELAY_MS * retry }
    }
}

private const val REQUEST_TIMEOUT_MS = 60_000L
private const val CONNECT_TIMEOUT_MS = 10_000L

/**
 * 再試行は2回まで。事前バッチは数十件を続けて投げるので、1件で粘りすぎると
 * 全体が終わらない（残りは次のドレインで埋まる。`PrebatchPoiFlavorUseCase`）。
 */
private const val MAX_RETRIES = 2
private const val RETRY_DELAY_MS = 1_000L

/** LLM APIの応答は版によってフィールドが増えるので、知らないキーは無視する。 */
internal val llmJson: Json = Json { ignoreUnknownKeys = true }

/**
 * 応答JSONを読む。読めなければ [LlmFailureKind.MALFORMED_RESPONSE] に揃える。
 *
 * 2xxで返ってきたのにJSONとして読めないのは、ベースURLが**LLM APIではない何か**
 * （プロキシのログイン画面など）を指しているときに起きる。例外の生メッセージは
 * 応答本文の断片を含むので載せない。
 *
 * @param formatName 例外メッセージに出す表示名。秘密を含まないこと。
 */
internal inline fun <reified T> Json.decodeOrThrow(raw: String, formatName: String): T = try {
    decodeFromString<T>(raw)
} catch (error: IllegalArgumentException) {
    // kotlinx.serialization の SerializationException は IllegalArgumentException の子
    throw LlmUnavailableException(
        reason = LlmFailureKind.MALFORMED_RESPONSE,
        message = "$formatName ${LlmFailureKind.MALFORMED_RESPONSE.message}",
    )
}

/**
 * 生成リクエストをPOSTして本文（文字列）を返す。失敗は [LlmUnavailableException] に揃える。
 *
 * **例外にURL・元例外を載せない**（`WeatherUnavailableException` と同じ方針）。
 * 応答本文の先頭を載せるのは400 / 404のときだけで、その理由は
 * [LlmUnavailableException] のKDoc。
 *
 * @param body フォーマットごとのリクエストJSON（組み立ては各クライアント）。
 */
internal suspend fun HttpClient.postLlmJson(
    settings: LlmConnectionSettings,
    request: LlmRequest,
    body: JsonObject,
): String {
    val response = try {
        post(settings.endpointUrl()) {
            if (!request.allowRetry) retry { noRetry() }
            contentType(ContentType.Application.Json)
            applyAuth(settings)
            setBody(body.toString())
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        // 名前解決・接続拒否・TLS・タイムアウトなど。生メッセージは載せない
        // （接続先URL・プロキシ設定・証明書の主体名が混ざりうる）。
        throw error.toLlmUnavailable()
    }

    if (!response.status.isSuccess()) throw response.toLlmUnavailable()
    return response.bodyAsText()
}

/**
 * エラー応答（非2xx）を分類する。
 *
 * 400は「モデル名の綴り間違い」、404は「ベースURLのパス違い」が主因で、直し方が
 * 本文に書いてあることが多いので先頭だけ持ち帰る（[LlmUnavailableException] のKDoc）。
 */
private suspend fun HttpResponse.toLlmUnavailable(): LlmUnavailableException {
    val code = status.value
    val reason = when {
        code == 401 || code == 403 -> LlmFailureKind.UNAUTHORIZED
        code == 404 -> LlmFailureKind.NOT_FOUND
        code == 429 -> LlmFailureKind.RATE_LIMITED
        code >= 500 -> LlmFailureKind.SERVER_ERROR
        else -> LlmFailureKind.UNEXPECTED
    }
    val excerpt = if (code == 400 || code == 404) {
        bodyAsText().take(MAX_EXCERPT_LENGTH).ifBlank { null }
    } else {
        null
    }
    return LlmUnavailableException(
        reason = reason,
        message = "LLMの接続先がエラーを返しました（HTTP $code）",
        statusCode = code,
        responseExcerpt = excerpt,
    )
}

private const val MAX_EXCERPT_LENGTH = 300

/**
 * 通信そのものが成立しなかったときの分類。
 *
 * 型ではなく**型名**で判定しているのは、DNS解決失敗やTLSエラーの例外型が
 * プラットフォーム（OkHttp / Darwin）ごとに違い、commonMain から型で捕まえられないため。
 * 分からなければ [LlmFailureKind.NETWORK] に落とす（issue #6 の疎通確認から引き継いだ判定）。
 */
internal fun Throwable.toLlmUnavailable(): LlmUnavailableException {
    val typeNames = generateSequence(this) { current -> current.cause?.takeIf { it !== current } }
        .take(MAX_CAUSE_DEPTH)
        .mapNotNull { it::class.simpleName }
        .joinToString(" ")

    val (reason, hint) = when {
        "UnresolvedAddress" in typeNames || "UnknownHost" in typeNames ->
            LlmFailureKind.NETWORK to "接続先のホスト名を解決できませんでした（DNS）。"

        "Timeout" in typeNames -> LlmFailureKind.TIMEOUT to null

        "SSL" in typeNames || "TLS" in typeNames || "Certificate" in typeNames ->
            LlmFailureKind.NETWORK to "安全な接続（TLS）を確立できませんでした。"

        "Connect" in typeNames || "NoRouteToHost" in typeNames ->
            LlmFailureKind.NETWORK to "接続先に接続できませんでした。"

        else -> LlmFailureKind.NETWORK to null
    }
    return LlmUnavailableException(
        reason = reason,
        message = hint ?: reason.message,
    )
}

/** 原因チェーンをたどる深さ。エンジンが数段ラップすることがあるので数段だけ見る。 */
private const val MAX_CAUSE_DEPTH = 5

/**
 * 生成のエンドポイント。ベースURLは各社の慣習に合わせて既定値を置いてあるので、
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

/** 認可ヘッダ。フォーマットで方式が違う（Anthropicは専用ヘッダ、OpenAI互換はBearer）。 */
internal fun HttpRequestBuilder.applyAuth(settings: LlmConnectionSettings) {
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
