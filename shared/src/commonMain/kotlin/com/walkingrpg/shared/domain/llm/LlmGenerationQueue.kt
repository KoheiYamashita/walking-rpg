package com.walkingrpg.shared.domain.llm

/**
 * 「まだ生成できていないものを見つけて生成する」1種類ぶんの仕事
 * （architecture.md §5「LLM生成キューへ投入」）。
 *
 * ## 永続キューにしない
 * 投入・取り出し・再投入の状態を持つキューを作ると、行の状態（未処理・処理中・失敗）を
 * 正しく戻す責任が増える割に、得られるものが無い：**生成すべきものは
 * いつでも現在の状態から数え直せる**（キャッシュに無い地点＝未生成）。
 * 天候の後付け取得（`FetchMissingSessionWeatherUseCase`）が
 * 「行が無いセッション」を毎回数え直しているのと同じ考え方で、
 * 途中で落ちても・アプリを消しても、次のドレインが同じ結論に辿り着く。
 *
 * 実装は [drain] が**何度呼んでも同じ状態に収束する**こと（冪等）。
 * 失敗は例外にせず [LlmGenerationOutcome] に数として返す：1件の通信失敗で
 * 呼び出し側（帰宅後フロー）を巻き添えにしない。
 */
interface LlmGenerationQueue {

    /** この実装が受け持つ生成タスク。ログ・結果の突き合わせに使う。 */
    val taskKind: LlmTaskKind

    /** 未生成のものを（上限まで）生成して保存する。例外は投げない。 */
    suspend fun drain(): LlmGenerationOutcome
}

/**
 * ドレイン1回の結果。デバッグ表示（#20）と生成の進み具合の確認に使う。
 *
 * @param generated 生成して保存した件数。
 * @param cached すでにキャッシュにあって何もしなかった件数（＝課金しなかった件数）。
 * @param failed 生成を試みて失敗した件数（保存していない＝次回リトライ対象）。
 * @param remaining 上限・打ち切りで今回は見送った件数（次回リトライ対象）。
 * @param skipReason 1件も試みなかった理由（試みたなら `null`）。
 */
data class LlmGenerationOutcome(
    val taskKind: LlmTaskKind,
    val generated: Int = 0,
    val cached: Int = 0,
    val failed: Int = 0,
    val remaining: Int = 0,
    val skipReason: LlmSkipReason? = null,
)

/** 生成を試みなかった理由。どれも異常ではない（次の機会に持ち越すだけ）。 */
enum class LlmSkipReason {
    /** LLMの接続設定がまだ無い（セットアップ未完了）。 */
    NOT_CONFIGURED,

    /** 従量課金の回線だった（[NetworkStatus]）。Wi-Fiに繋がったときにやり直す。 */
    METERED_NETWORK,

    /** 生成すべきものが無かった（全部キャッシュに載っている・素材がまだ無い）。 */
    NOTHING_TO_GENERATE,
}
