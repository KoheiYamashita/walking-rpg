package com.walkingrpg.shared.domain.walk

import kotlinx.coroutines.flow.StateFlow

/**
 * 記録中セッションの実行主体（開始・測位の購読・永続化・停止）。
 *
 * ドメイン層はこのインターフェースだけを持ち、実装（測位API・DBの結線）は
 * データ層に置く。UI層はUseCase越しにしか触らない。
 */
interface WalkRecorder {

    /** 記録の現在状態。UIはこれを見て計測値を表示する。 */
    val state: StateFlow<WalkRecordingState>

    /** 記録開始。すでに記録中なら何もしない。 */
    suspend fun start()

    /** 記録停止・セッション確定。記録中でなければ何もしない。 */
    suspend fun stop()
}
