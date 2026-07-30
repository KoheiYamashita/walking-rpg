package com.walkingrpg.shared.domain.walk

import kotlinx.coroutines.flow.Flow

/**
 * 散歩ログの永続化境界（architecture.md §2「Repository」）。
 *
 * ドメイン層はこのインターフェースだけを知る。SQLDelightのスキーマ変換は
 * データ層（`WalkSessionRepositoryImpl`）に閉じる。
 */
interface WalkSessionRepository {

    /** セッションを開始し、採番されたIDを返す。 */
    suspend fun startSession(startedAtMs: Long): Long

    /** 測位サンプルを1件追記する。 */
    suspend fun appendSample(sample: LocationSample)

    /** セッションを確定する。 */
    suspend fun endSession(sessionId: Long, endedAtMs: Long, reason: SessionEndReason)

    /**
     * 開きっぱなしのセッション（強制終了・クラッシュで残ったもの）を
     * [SessionEndReason.ABANDONED] として畳む。記録済みサンプルは消さない。
     *
     * 終了時刻は「そのセッションで最後に取れたサンプルの時刻」（1件も無ければ開始時刻）。
     * 畳む時点の現在時刻ではないので、何日後に起動しても継続時間は汚れない。
     *
     * @return 畳んだセッションのID（開きっぱなしが無ければ空）。呼び出し側がこのIDで
     *  導出（`passage` → `way_growth`）を作り直せるように返す：クラッシュで畳まれた散歩も
     *  サンプルは真実として残っているので、育ちに反映されなければならない。
     *  `passage` の作り直しはセッション単位なので、ここで拾い損ねると
     *  **後続の散歩では二度と救われない**。
     */
    suspend fun abandonOpenSessions(): List<Long>

    /** セッション一覧（新しい順）。DBの変更に追従する。 */
    fun observeSessions(): Flow<List<WalkSessionSummary>>

    /** 指定セッションのサンプルを時刻順に返す。 */
    suspend fun samples(sessionId: Long): List<LocationSample>

    /** 指定セッション（存在しなければnull）。 */
    suspend fun session(sessionId: Long): WalkSession?

    /**
     * 終了済みのセッションを新しい順に [limit] 件まで返す。
     *
     * 「帰宅直後に効くもの」を作る側（振り返りの一言の生成キュー＝
     * `GenerateWalkReviewRemarkUseCase`）の入口。[observeSessions] と分けているのは、
     * あちらが画面用に全件＋サンプル数を流すのに対し、こちらは
     * **課金する処理の対象を直近だけに絞る**ためのものだから。
     */
    suspend fun recentFinishedSessions(limit: Int): List<WalkSession>
}
