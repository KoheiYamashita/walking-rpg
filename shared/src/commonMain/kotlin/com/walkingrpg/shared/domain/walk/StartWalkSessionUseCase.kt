package com.walkingrpg.shared.domain.walk

/**
 * 「散歩に出る」。記録を開始する（design.md §3「開始は手動」）。
 *
 * 役割規約（architecture.md §2）：UseCaseは1操作1クラス・純Kotlin。
 */
class StartWalkSessionUseCase(
    private val recorder: WalkRecorder,
) {
    suspend operator fun invoke() = recorder.start()
}
