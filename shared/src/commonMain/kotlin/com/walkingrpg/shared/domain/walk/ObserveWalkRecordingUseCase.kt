package com.walkingrpg.shared.domain.walk

import com.walkingrpg.shared.domain.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * 記録中の計測値を購読する（スパイクの表示用：経過時間・サンプル数・最新精度）。
 *
 * 経過時間は「今」に依存するため、記録中だけ [tickMs] 間隔で [Clock] を読み直して
 * スナップショットを作り直す。UseCaseが時刻を直接読まないよう [Clock] を注入する
 * （architecture.md §2）。
 */
class ObserveWalkRecordingUseCase(
    private val recorder: WalkRecorder,
    private val clock: Clock,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    operator fun invoke(tickMs: Long = DEFAULT_TICK_MS): Flow<WalkRecordingSnapshot> =
        recorder.state.flatMapLatest { state ->
            flow {
                do {
                    emit(state.snapshotAt(clock.nowMillis()))
                    if (state.isRecording) delay(tickMs)
                } while (state.isRecording)
            }
        }

    companion object {
        const val DEFAULT_TICK_MS: Long = 1_000L
    }
}
