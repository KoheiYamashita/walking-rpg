package com.walkingrpg.shared.domain.codex

import kotlinx.coroutines.flow.Flow

/**
 * 「図鑑が作り直された」ことを購読する。
 *
 * `ObserveGrowthUpdatesUseCase`（地図）と同じ役目：流れてくる集合そのもの
 * （新しく発見された種のID）よりも、**流れてきたこと**に意味がある。
 * 図鑑画面はこれを合図に [GetCodexUseCase] を引き直せば、開いたままでも
 * 散歩ぶんの発見と予兆が乗る。購読開始時にも現在値が1回流れる＝画面の初回読み込みも
 * この購読1本で足りる（[RecentCodexRepository.updates]）。
 */
class ObserveCodexUpdatesUseCase(
    private val recentCodexRepository: RecentCodexRepository,
) {
    operator fun invoke(): Flow<Set<String>> = recentCodexRepository.updates
}
