package com.walkingrpg.shared.data.osm

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent

/**
 * Overpass取り込み用のHTTPクライアント。
 *
 * エンジン（OkHttp / Darwin）は各プラットフォームの依存から解決されるので、
 * ここではプラグインの設定だけを共通で持つ。
 */
internal fun osmHttpClient(config: OverpassConfig): HttpClient =
    HttpClient { installOsmDefaults(config) }

/** テストからフェイクエンジンを差し込むためのオーバーロード（設定は本番と同一）。 */
internal fun osmHttpClient(engine: HttpClientEngine, config: OverpassConfig): HttpClient =
    HttpClient(engine) { installOsmDefaults(config) }

/**
 * - **User-Agent**：Overpassの利用規約が求める（無記名だとブロックされうる）
 * - **タイムアウト**：混雑時に無限に待たない
 * - **リトライ**：Overpassの失敗は混雑（5xx）が主因で、間を置けば通ることが多い。
 *   何度も粘るのは公開インスタンスに対して行儀が悪いので既定は1回だけ
 */
private fun HttpClientConfig<*>.installOsmDefaults(config: OverpassConfig) {
    install(UserAgent) {
        agent = config.userAgent
    }
    install(HttpTimeout) {
        requestTimeoutMillis = config.requestTimeoutMs
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        socketTimeoutMillis = config.requestTimeoutMs
    }
    install(HttpRequestRetry) {
        retryOnExceptionOrServerErrors(maxRetries = config.maxRetries)
        delayMillis { retry -> config.retryDelayMs * retry }
    }
}

private const val CONNECT_TIMEOUT_MS = 15_000L
