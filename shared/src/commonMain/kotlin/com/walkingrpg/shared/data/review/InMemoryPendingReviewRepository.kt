package com.walkingrpg.shared.data.review

import com.walkingrpg.shared.domain.review.PendingReviewRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [PendingReviewRepository] のプロセス内メモリ実装。
 *
 * DBを使わない理由はインターフェース側のKDocに書いた。DIでは `single` にして、
 * 「要求する側（散歩の終了・通知のタップ）」と「読む側（`AppViewModel`）」が
 * 同じ1個を見るようにする。
 *
 * `StateFlow` でよいのは、ここで欲しいのが「いま開くべき画面はどれか」という
 * **現在値**だから（`RecentGrowthRepository` が `SharedFlow` なのは、あちらが
 * 「同じ集合でも再計算が終わったこと」を伝えるためで、意味が違う）。
 */
internal class InMemoryPendingReviewRepository : PendingReviewRepository {

    private val _pending = MutableStateFlow<Long?>(null)

    override val pending: Flow<Long?> = _pending.asStateFlow()

    override fun request(sessionId: Long) {
        _pending.value = sessionId
    }

    override fun consume() {
        _pending.value = null
    }
}
