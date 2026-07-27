package com.walkingrpg.shared.data.osm

/**
 * Overpass APIの接続設定。
 *
 * エンドポイントはひとまず公式ミラーの定数だが、差し替えられる位置に置いてある
 * （DIで別インスタンスを注入すればよい）。将来ミラーを選ばせる・自前インスタンスを
 * 指すことになっても、変更はこのデータクラスの生成箇所だけで済む。
 *
 * @param userAgent Overpassの利用規約が求める識別子。個人を特定する情報は入れない。
 */
data class OverpassConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val userAgent: String = DEFAULT_USER_AGENT,
    /** 1リクエストの上限。Overpass側の `[timeout:]` より少し長く取る。 */
    val requestTimeoutMs: Long = 90_000,
    /** サーバ側の `[timeout:]`（秒）。混雑時はここで打ち切られる。 */
    val queryTimeoutSeconds: Int = 60,
    /** 失敗時の再試行回数。混雑（5xx）が主因なので1回だけ粘る。 */
    val maxRetries: Int = 1,
    /** 再試行までの待ち時間。混んでいる相手に間髪入れず投げ直さないため。 */
    val retryDelayMs: Long = 2_000,
) {
    companion object {
        const val DEFAULT_BASE_URL: String = "https://overpass-api.de/api/interpreter"
        const val DEFAULT_USER_AGENT: String = "walking-rpg/0.1 (personal hobby app)"
    }
}
