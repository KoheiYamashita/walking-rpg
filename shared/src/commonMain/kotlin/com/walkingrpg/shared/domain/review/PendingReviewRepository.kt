package com.walkingrpg.shared.domain.review

import kotlinx.coroutines.flow.Flow

/**
 * 「これから開くべき振り返り」の置き場（architecture.md §2「Repository」）。
 *
 * 起点が2つあるので、置き場を1つにしてある：
 *
 * - **散歩の自動終了**（`ObserveFinishedWalksUseCase` → 再計算のあと）
 * - **「おかえり」通知のタップ**（`WalkNotifier.notifyHomecoming` が載せた
 *   セッションIDを、プラットフォーム層のエントリポイントがここへ流す）
 *
 * どちらも「振り返りを開いてほしい」以外の情報を持たないので、経路ごとに
 * 別の状態を作らない（作ると「通知で開いたら二重に開く」が起きる）。
 *
 * ## 永続化しない
 * `RecentGrowthRepository` / `WalkEventBus` と同じくプロセス内メモリ。
 * これは「開くべき画面」という一時的な意図で、記録ではない。
 * 永続化すると、次に起動したときに何日も前の振り返りが勝手に開く。
 * 消えても困らない（振り返りはホームのセッション一覧からいつでも開ける）。
 */
interface PendingReviewRepository {

    /**
     * 開くべきセッションID（無ければ `null`）。購読開始時の現在値も1回流れる。
     *
     * 同じIDが続けて要求されたときに落とさない必要はない（開く画面は同じ1枚）ので、
     * ここは素直に「現在の意図」を表す。
     */
    val pending: Flow<Long?>

    /** 振り返りを開いてほしい、と記録する。 */
    fun request(sessionId: Long)

    /** 開いたので消す（開いた画面がもう一度開き直されないように）。 */
    fun consume()
}
