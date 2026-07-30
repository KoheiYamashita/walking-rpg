package com.walkingrpg.shared.domain.review

import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.walk.WalkSessionRepository

/**
 * 畳まれた散歩の振り返りを開くよう頼む（`AppViewModel` が再計算のあとに呼ぶ）。
 *
 * ## 放置セッションでは開かない
 * `WalkRecorder.finishedSessions` は畳み方を区別せずに流す（`ObserveFinishedWalksUseCase`）。
 * その中には**散歩を開始した瞬間に畳まれる放置セッション**
 * （[SessionEndReason.ABANDONED]＝前回クラッシュで開いたままだった記録）も混ざる。
 * そのまま振り返りを開くと「散歩に出るボタンを押したら前回の振り返りが開く」という
 * 事故になるので、ここで終わり方を読み直して弾く。
 *
 * 弾かれた散歩の振り返りが失われるわけではない（ホームのセッション一覧から開ける）。
 * 判定をViewModelに書かずここに置くのは、この規則がUIの都合ではなく
 * 「どの散歩が『帰ってきた』と言えるか」というドメインの判断だから。
 */
class RequestReviewForFinishedWalkUseCase(
    private val walkSessionRepository: WalkSessionRepository,
    private val pendingReviewRepository: PendingReviewRepository,
) {
    /** @return 振り返りを開くよう頼んだか（放置セッションなら `false`）。 */
    suspend operator fun invoke(sessionId: Long): Boolean {
        val session = walkSessionRepository.session(sessionId) ?: return false
        if (session.endReason == SessionEndReason.ABANDONED) return false
        pendingReviewRepository.request(sessionId)
        return true
    }
}
