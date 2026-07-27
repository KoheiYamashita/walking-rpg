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
     */
    suspend fun abandonOpenSessions(endedAtMs: Long)

    /** セッション一覧（新しい順）。DBの変更に追従する。 */
    fun observeSessions(): Flow<List<WalkSessionSummary>>

    /** 指定セッションのサンプルを時刻順に返す。 */
    suspend fun samples(sessionId: Long): List<LocationSample>

    /** 指定セッション（存在しなければnull）。 */
    suspend fun session(sessionId: Long): WalkSession?
}
