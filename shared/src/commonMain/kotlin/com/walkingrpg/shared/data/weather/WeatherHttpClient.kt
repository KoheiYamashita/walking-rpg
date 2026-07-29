package com.walkingrpg.shared.data.weather

import com.walkingrpg.shared.domain.weather.WeatherUnavailableException
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * 天候取得用のHTTPクライアント。
 *
 * OSM取り込み（[com.walkingrpg.shared.data.osm.osmHttpClient]）・LLM疎通
 * （[com.walkingrpg.shared.data.llm.llmHttpClient]）とは方針が違うので分ける：
 *
 * - **リトライしない**：失敗したセッションは行を作らず次回起動時に丸ごとやり直す
 *   （design.md §9「失敗時は次回起動時リトライ」）ので、その場で粘る意味が無い。
 *   帰宅直後に圏外なら、数秒後もたいてい圏外
 * - **タイムアウトは短め**：散歩の終了直後に走るので、ここで長く待つと
 *   帰宅後の画面がもたつく。天候は遅れて付いてよい（architecture.md §5
 *   「数値は即時、文章は遅延OK」と同じ考え方）
 */
internal fun weatherHttpClient(): HttpClient = HttpClient { installWeatherDefaults() }

/** テストからフェイクエンジンを差し込むためのオーバーロード（設定は本番と同一）。 */
internal fun weatherHttpClient(engine: HttpClientEngine): HttpClient =
    HttpClient(engine) { installWeatherDefaults() }

private fun HttpClientConfig<*>.installWeatherDefaults() {
    install(HttpTimeout) {
        requestTimeoutMillis = REQUEST_TIMEOUT_MS
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        socketTimeoutMillis = REQUEST_TIMEOUT_MS
    }
}

private const val REQUEST_TIMEOUT_MS = 15_000L
private const val CONNECT_TIMEOUT_MS = 10_000L

/** 天候プロバイダの応答は版によってフィールドが増えるので、知らないキーは無視する。 */
internal val weatherJson: Json = Json { ignoreUnknownKeys = true }

/**
 * 天候APIをGETして本文を返す。失敗は [WeatherUnavailableException] に揃える。
 *
 * **例外にURL・応答本文・元例外を載せない**のが要点。3プロバイダのうち2つは
 * APIキーをURLのクエリに載せる仕様（OpenWeatherMap の `appid`・Visual Crossing の `key`）で、
 * 座標も同じくクエリに乗る。Ktorの例外は接続先URLをメッセージに含むので、
 * `cause` として繋ぐだけでもキーと位置が例外チェーンに残る。
 * 分かるのは「どのプロバイダで」「HTTPいくつだったか」までに絞る
 * （切り分けにはそれで足りる）。
 *
 * @param providerName 例外メッセージに出す表示名。秘密を含まないこと。
 */
internal suspend fun HttpClient.getWeatherJson(
    url: String,
    providerName: String,
    block: HttpRequestBuilder.() -> Unit = {},
): String {
    val response = try {
        get(url, block)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        throw WeatherUnavailableException("$providerName に接続できませんでした（${error::class.simpleName}）")
    }
    if (!response.status.isSuccess()) {
        throw WeatherUnavailableException(
            "$providerName がエラーを返しました（HTTP ${response.status.value}）",
        )
    }
    return response.bodyAsText()
}

/** 応答は届いたが、欲しい値が入っていなかったとき。 */
internal fun missingValue(providerName: String): Nothing =
    throw WeatherUnavailableException("$providerName の応答に天候が入っていませんでした")

/** キーが要るプロバイダなのに未入力だったとき（#20 の設定画面で入れてもらう）。 */
internal fun missingApiKey(providerName: String): Nothing =
    throw WeatherUnavailableException("$providerName のAPIキーが設定されていません")
