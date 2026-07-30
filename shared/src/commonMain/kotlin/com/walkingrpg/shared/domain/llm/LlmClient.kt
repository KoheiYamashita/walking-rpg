package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.setup.LlmConnectionSettings
import com.walkingrpg.shared.domain.setup.LlmFormat

/**
 * LLM生成の境界（architecture.md §1「`LlmClient`抽象＋2フォーマット実装」）。
 *
 * ドメイン層はこのインターフェースしか知らない。Anthropic Messages API と
 * OpenAI Chat Completions のURL・ヘッダ・ボディ・応答形式の違いは
 * データ層（`data/llm` の2実装）に閉じる。
 *
 * 実装は2つ（design.md §9「Anthropic と OpenAI の2フォーマットのみ実装」）。
 * どちらを使うかはユーザーの設定（`LlmConnectionSettings.format`）で決まり、
 * その振り分けは [LlmClientSelector] が行う。
 */
interface LlmClient {

    /** この実装が受け持つフォーマット。[LlmClientSelector] の対応付けに使う。 */
    val format: LlmFormat

    /**
     * [request] を1回投げて本文を返す。
     *
     * ## 設定を引数で渡す理由
     * 接続設定（キー・ベースURL・モデル名）はユーザーが設定画面でいつでも変えられるので、
     * 実装が起動時に握ってしまうと古い設定で投げ続けることになる。呼び出し側が
     * `SetupRepository.loadLlmConnection()` で**都度読んで**渡す形にしてある
     * （天候の `WeatherProvider.observe(query, apiKey)` と同じ流儀）。
     * 選ぶ実装も設定の [LlmFormat] で決まるので、どうせ呼び出し側が設定を手にしている。
     *
     * @param settings [format] と同じフォーマットの設定を渡すこと（[LlmClientSelector] 経由なら自動で揃う）。
     *  [LlmConnectionSettings.apiKey] は秘密なので、実装は**例外・ログにこの値を出さない**。
     * @throws LlmUnavailableException 通信・応答の不備で生成できなかったとき。
     *  呼び出し側はこれを「まだ生成できていない」として扱い、定型文で凌いで次回リトライする
     *  （design.md §7「失敗・圏外なら定型文＋事前生成分で成立する」）。
     */
    suspend fun generate(request: LlmRequest, settings: LlmConnectionSettings): LlmResponse
}

/**
 * 設定で選ばれたフォーマットの実装を解決する。
 *
 * ドメイン層が2実装の存在を知らずに「設定で選ばれた実装」へ辿り着くための1枚
 * （天候の `WeatherProviderSelector` と同じ役目）。実装（`HttpLlmClientSelector`）は
 * [LlmFormat] に対する `when` なので、フォーマットを増やしたらコンパイルエラーで気付ける。
 */
interface LlmClientSelector {
    fun client(format: LlmFormat): LlmClient
}

/**
 * 生成できなかったとき（未設定・通信失敗・エラー応答・応答に本文が無い）。
 *
 * **原因の詳細（URL・APIキー・元例外）は載せない**：`cause` として繋ぐだけでも、
 * Ktorの例外メッセージ経由で接続先URLやプロキシ設定が例外チェーンに残る
 * （`WeatherUnavailableException` と同じ方針）。
 *
 * ## [responseExcerpt] だけは例外扱い
 * 応答本文の先頭を載せるのは **400 / 404 のときだけ**。この2つは
 * 「モデル名の綴り間違い」「ベースURLのパス違い」が主因で、直し方が本文に書いてある
 * ことが多く、セットアップ画面でそれを見せられないとユーザーは詰む（issue #6 からの引き継ぎ）。
 * 401 / 403 を対象から外しているのは、OpenAI互換のエンドポイントが
 * 「Incorrect API key provided: sk-abc***」のように**キーの一部を本文に echo する**ため。
 * 伏せられてはいるが、わざわざ画面とログに流す価値は無い。
 *
 * @param reason 呼び出し側が挙動を決めるための分類（[LlmFailureKind]）。
 * @param statusCode HTTPステータス（通信そのものが成立しなかったときは `null`）。
 * @param responseExcerpt 400 / 404 のときの応答本文の先頭。それ以外は `null`。
 */
class LlmUnavailableException(
    val reason: LlmFailureKind,
    message: String,
    val statusCode: Int? = null,
    val responseExcerpt: String? = null,
) : Exception(message)

/**
 * 生成に失敗した理由の分類。
 *
 * 呼び出し側はこれを見て「このまま次の地点も試すか、run ごと打ち切るか」を決める
 * （`PrebatchPoiFlavorUseCase` の `stopsBatch`）。文言は疎通確認の画面表示にも使うので、
 * 「何を直せばいいか」が分かる形にしておく（`LlmConnectionFailure` と同じ考え方）。
 */
enum class LlmFailureKind(val message: String) {
    /** 接続設定がまだ無い（セットアップ未完了・設定を消した）。生成そのものを試みていない。 */
    NOT_CONFIGURED("LLMの接続設定がありません。"),

    UNAUTHORIZED("APIキーが受け付けられませんでした。"),
    NOT_FOUND("接続先が見つかりませんでした。ベースURLとモデル名を確認してください。"),
    RATE_LIMITED("接続先のレート制限に達しています。"),
    SERVER_ERROR("接続先でエラーが発生しています。"),
    TIMEOUT("応答がありませんでした（タイムアウト）。"),
    NETWORK("接続できませんでした。"),

    /** 2xxで返ってきたが、本文の取り出し先（Anthropicの `content[]` / OpenAIの `choices[]`）が無い。 */
    MALFORMED_RESPONSE("応答の形式が想定と違いました。"),

    /** 本文の取り出し先はあったが空だった（出力上限で打ち切られた・モデルが黙った）。 */
    EMPTY_RESPONSE("応答に本文が入っていませんでした。"),

    UNEXPECTED("生成に失敗しました。"),
}
