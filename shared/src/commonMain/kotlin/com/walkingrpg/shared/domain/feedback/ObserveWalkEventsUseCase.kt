package com.walkingrpg.shared.domain.feedback

import kotlinx.coroutines.flow.Flow

/**
 * 歩行中に起きたイベントを購読する（design.md §3「信号待ちなどで見た場合も、
 * 出るのは1〜2文の断片だけ」）。
 *
 * UI層はこれだけを見る。振動はUIを経由せず `WalkFeedbackImpl` が直接鳴らすので、
 * 画面を開いていなくても・ポケットに入れていても印は届く。
 * 逆にこの購読は「見た人にだけ1〜2文」を出すための口で、
 * 画面を見ていなければ何も起きない。
 */
class ObserveWalkEventsUseCase(
    private val eventBus: WalkEventBus,
) {
    operator fun invoke(): Flow<WalkEvent> = eventBus.events
}
