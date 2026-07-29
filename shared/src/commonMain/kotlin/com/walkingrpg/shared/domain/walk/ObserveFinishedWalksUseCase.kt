package com.walkingrpg.shared.domain.walk

import kotlinx.coroutines.flow.Flow

/**
 * 畳まれたセッションのIDを購読する（architecture.md §5「帰宅後」の起点）。
 *
 * 散歩が終わったら導出（`passage` → `way_growth`）を作り直す、という結線を
 * UI層に1本だけ引くための口。畳み方（手動・自宅到着・測位エラー）は
 * [WalkRecorder.finishedSessions] が吸収しているので、購読側は区別しない。
 */
class ObserveFinishedWalksUseCase(
    private val recorder: WalkRecorder,
) {
    operator fun invoke(): Flow<Long> = recorder.finishedSessions
}
