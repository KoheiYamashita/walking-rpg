package com.walkingrpg.shared.domain.review

import kotlinx.coroutines.flow.Flow

/**
 * 「振り返りを開く」意図のやり取り（issue #15）。
 *
 * ViewModel・プラットフォームのエントリポイントはこの3本しか知らない
 * （[PendingReviewRepository] の実装はDIの向こう側。architecture.md §2）。
 */

/** 開くべき振り返りを購読する（`AppViewModel` → `MainNavigation`）。 */
class ObservePendingReviewUseCase(
    private val repository: PendingReviewRepository,
) {
    operator fun invoke(): Flow<Long?> = repository.pending
}

/**
 * 振り返りを開いてほしいと伝える。
 *
 * 呼び口は2つ：散歩の自動終了後（`AppViewModel`）と「おかえり」通知のタップ
 * （Androidは `MainActivity`）。
 */
class RequestPendingReviewUseCase(
    private val repository: PendingReviewRepository,
) {
    operator fun invoke(sessionId: Long) = repository.request(sessionId)
}

/** 開いたので消す（同じ振り返りが繰り返し開かれないように）。 */
class ConsumePendingReviewUseCase(
    private val repository: PendingReviewRepository,
) {
    operator fun invoke() = repository.consume()
}
