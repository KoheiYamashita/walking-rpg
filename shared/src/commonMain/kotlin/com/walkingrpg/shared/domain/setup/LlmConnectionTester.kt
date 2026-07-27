package com.walkingrpg.shared.domain.setup

/**
 * LLM接続の疎通確認だけを行う境界（architecture.md §2「Repository」）。
 *
 * **本格的な `LlmClient`（生成・リトライ・縮退）は issue #14 の領分。**
 * ここは「入力された設定でリクエストが1回通るか」しか見ない薄い口にしてあるので、
 * #14 で `LlmClient` を入れるときは、このインターフェースの実装を
 * `LlmClient` 側に寄せて差し替えればよい（呼び出し側はセットアップ画面だけ）。
 */
interface LlmConnectionTester {

    /** 例外は投げず、失敗の理由を [LlmConnectionTestResult.Failure] で返す。 */
    suspend fun test(settings: LlmConnectionSettings): LlmConnectionTestResult
}

sealed interface LlmConnectionTestResult {

    data object Success : LlmConnectionTestResult

    data class Failure(
        val reason: LlmConnectionFailure,
        /** サーバが返した本文の先頭など、原因の切り分けに役立つ補足（無ければ `null`）。 */
        val detail: String? = null,
    ) : LlmConnectionTestResult {
        val message: String
            get() = if (detail.isNullOrBlank()) reason.message else "${reason.message}\n$detail"
    }
}

/**
 * 疎通失敗の分類。
 *
 * 文言は「何を直せばいいか」が分かる形にする。HTTPステータスをそのまま出しても
 * ユーザーは直せない（design.md §3の考え方をエラー表示にも適用する）。
 */
enum class LlmConnectionFailure(val message: String) {
    INVALID_INPUT("入力内容に不備があります。"),
    UNAUTHORIZED("APIキーが受け付けられませんでした。キーが正しいか、利用できる状態かを確認してください。"),
    NOT_FOUND("接続先が見つかりませんでした。ベースURLとモデル名を確認してください。"),
    RATE_LIMITED("接続先のレート制限に達しています。しばらく待ってから再試行してください。"),
    SERVER_ERROR("接続先でエラーが発生しています。時間を置いて再試行してください。"),
    TIMEOUT("応答がありませんでした（タイムアウト）。通信状況とベースURLを確認してください。"),
    NETWORK("接続できませんでした。通信状況とベースURLを確認してください。"),
    UNEXPECTED("疎通に失敗しました。"),
}
