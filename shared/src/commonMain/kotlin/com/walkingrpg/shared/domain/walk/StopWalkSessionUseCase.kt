package com.walkingrpg.shared.domain.walk

/**
 * 「散歩を終える」。記録を止めてセッションを確定する。
 *
 * 通常の終了は自動検知（design.md §3、[HomeArrivalDetector]）で、こちらはその逃げ道：
 * 自宅を登録していない・判定が効かない・途中で切り上げたい、といった場合に使う。
 */
class StopWalkSessionUseCase(
    private val recorder: WalkRecorder,
) {
    suspend operator fun invoke() = recorder.stop()
}
