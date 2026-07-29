package com.walkingrpg.shared.data.growth

import com.walkingrpg.shared.domain.growth.RecentGrowthRepository
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [RecentGrowthRepository] のプロセス内メモリ実装。
 *
 * DBを使わない理由はインターフェース側のKDocに書いた。DIでは `single` にして、
 * 「再計算する側（散歩終了）」と「読む側（地図）」が同じ1個を見るようにする。
 *
 * `replay = 1` の [MutableSharedFlow] を使うのは、購読開始時に現在値が1回ほしい
 * （画面の初回読み込み）一方で、同じ集合が2回続いたときに2回目を落としたくないから
 * （`StateFlow` は後者ができない）。
 */
internal class InMemoryRecentGrowthRepository : RecentGrowthRepository {

    private val _updates = MutableSharedFlow<Set<Long>>(
        replay = 1,
        extraBufferCapacity = UPDATES_BUFFER,
        // 記録する側（再計算）を購読側の都合で待たせない。地図の読み直しは
        // 最新の1回さえ届けば結果は同じなので、詰まったら古い方から捨ててよい。
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override var stageRaisedWayIds: Set<Long> = emptySet()
        private set

    override val updates: Flow<Set<Long>> = _updates.asSharedFlow()

    init {
        // 一度も散歩していない状態も「現在値」として流す（初回の購読で画面が動き出す）
        _updates.tryEmit(stageRaisedWayIds)
    }

    override fun record(wayIds: Set<Long>) {
        stageRaisedWayIds = wayIds
        _updates.tryEmit(wayIds)
    }

    private companion object {
        const val UPDATES_BUFFER = 8
    }
}
