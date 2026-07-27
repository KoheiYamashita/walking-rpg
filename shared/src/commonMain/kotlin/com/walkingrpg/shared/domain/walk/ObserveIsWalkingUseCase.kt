package com.walkingrpg.shared.domain.walk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 「いま歩行を記録中か」だけを購読する。
 *
 * 画面ON維持（design.md §3「画面はONのまま携行するのが基本」）のように、
 * 画面をまたいで必要になる判断のための最小の購読口。
 * [ObserveWalkRecordingUseCase] と違って経過時間の再計算（1秒ごとのtick）を伴わず、
 * 記録の開始・終了でしか流れない。
 */
class ObserveIsWalkingUseCase(
    private val recorder: WalkRecorder,
) {
    operator fun invoke(): Flow<Boolean> =
        recorder.state.map { it.isRecording }.distinctUntilChanged()
}
