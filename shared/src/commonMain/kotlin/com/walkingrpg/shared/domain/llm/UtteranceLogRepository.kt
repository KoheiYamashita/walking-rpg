package com.walkingrpg.shared.domain.llm

/**
 * パートナーの発話履歴（`utterance_log`）の永続化境界（architecture.md §2「Repository」）。
 *
 * ドメイン層はこのインターフェースだけを知る。SQLDelightのスキーマ変換は
 * データ層（`UtteranceLogRepositoryImpl`）に閉じる。
 *
 * ## 捨てられない表
 * `llm_cache`（導出キャッシュ）と違い、これは**再計算できない追記ログ**。
 * `passage` から「何を言ったか」は導けないので、消すと単調化対策
 * （design.md §5「同じ切り口の再使用を禁止」）そのものが失われる
 * ＝バックアップの対象（architecture.md §6）。
 */
interface UtteranceLogRepository {

    /**
     * その場所で使った切り口を、新しい順に [limit] 件まで返す。
     *
     * @param placeRef 内部キー（`way:<id>` / `species:<id>` / `day`）。
     * @param beforeMs これより前（`said_at < beforeMs`）に言ったぶんだけ。
     *  基準は**その散歩の開始時刻**で、自分自身と自分より後の一言を数に入れないためのもの
     *  （入れると、一度生成したあとに読み側の指紋が変わる）。
     * @param excludeSessionId 除外するセッション（＝いま一言を作る散歩自身）。
     *  [beforeMs] と二重に絞るのは、開始時刻が同じ行が万一あっても自分を数えないため。
     */
    suspend fun recentUtterances(
        placeRef: String,
        beforeMs: Long,
        excludeSessionId: Long,
        limit: Int,
    ): List<UtteranceRecord>

    /**
     * 1セッションぶんの発話を**丸ごと置き換える**（削除→挿入を1トランザクション）。
     *
     * 追記にしないのは、プロンプトを直して同じ散歩の一言を作り直したときに
     * 行が増えないようにするため（`UtteranceLog.sq`）。
     */
    suspend fun replaceSessionUtterances(sessionId: Long, records: List<UtteranceRecord>)

    /** 指定セッションの発話（書き込み順）。 */
    suspend fun utterances(sessionId: Long): List<UtteranceRecord>
}

/**
 * 「いつ・どの場所について・どの切り口で語ったか」の1行。
 *
 * @param placeRef 内部キー（`way:<id>` / `species:<id>` / `day`）。
 *  **プロンプトには絶対に出さない**（`UtteranceLog.sq`）。
 * @param saidAtMs `walk_session.started_at` から導いた時刻。端末時計は読まない
 *  （`codex_progress.discovered_at` と同じ前例）。
 */
data class UtteranceRecord(
    val sessionId: Long,
    val placeRef: String,
    val angle: RemarkAngle,
    val saidAtMs: Long,
)
