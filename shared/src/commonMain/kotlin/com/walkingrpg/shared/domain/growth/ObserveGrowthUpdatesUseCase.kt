package com.walkingrpg.shared.domain.growth

import kotlinx.coroutines.flow.Flow

/**
 * 「成長が作り直された」ことを購読する。
 *
 * 流れてくる集合そのもの（段階が上がった道のID）よりも、**流れてきたこと**に意味がある：
 * 地図画面はこれを合図に `GetMapSceneUseCase` を引き直せば、開いたままでも
 * 散歩ぶんの色が乗る。購読開始時にも現在値が1回流れる＝画面の初回読み込みも
 * この購読1本で足りる（[RecentGrowthRepository.updates]）。
 */
class ObserveGrowthUpdatesUseCase(
    private val recentGrowthRepository: RecentGrowthRepository,
) {
    operator fun invoke(): Flow<Set<Long>> = recentGrowthRepository.updates
}
