package com.walkingrpg.shared.domain.walk

/**
 * 「散歩を終える」。記録を止めてセッションを確定する。
 *
 * 本来の終了は自動検知（design.md §3）だが、スパイクでは手動終了だけを扱う。
 */
class StopWalkSessionUseCase(
    private val recorder: WalkRecorder,
) {
    suspend operator fun invoke() = recorder.stop()
}
