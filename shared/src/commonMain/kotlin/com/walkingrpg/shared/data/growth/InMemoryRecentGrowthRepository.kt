package com.walkingrpg.shared.data.growth

import com.walkingrpg.shared.domain.growth.RecentGrowthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [RecentGrowthRepository] のプロセス内メモリ実装。
 *
 * DBを使わない理由はインターフェース側のKDocに書いた。DIでは `single` にして、
 * 「再計算する側（散歩終了）」と「読む側（地図）」が同じ1個を見るようにする。
 */
internal class InMemoryRecentGrowthRepository : RecentGrowthRepository {

    private val _stageRaisedWayIds = MutableStateFlow<Set<Long>>(emptySet())
    override val stageRaisedWayIds: StateFlow<Set<Long>> = _stageRaisedWayIds.asStateFlow()

    override fun record(wayIds: Set<Long>) {
        _stageRaisedWayIds.value = wayIds
    }
}
