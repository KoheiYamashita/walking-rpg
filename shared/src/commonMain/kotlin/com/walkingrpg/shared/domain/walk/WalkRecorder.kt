package com.walkingrpg.shared.domain.walk

import kotlinx.coroutines.flow.Flow
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

    /**
     * セッションが畳まれた（DBに終了時刻が入った）ときにそのIDを1回だけ流す。
     *
     * 手動停止・自宅到着による自動終了・測位ストリームの終了（エラー）の**3経路とも**
     * ここに来る。畳み方ごとに導出（map matching → 成長）の呼び出しを書き分けると、
     * 経路が増えるたびに書き漏らす。[state] の `isRecording` の立ち下がりでも
     * 代用できそうに見えるが、それだと「どのセッションが終わったか」が取れない
     * （すでに `sessionId` は `null` に落ちている）。
     *
     * 過去のイベントは溜めない。購読していないあいだに終わったセッションの再計算は、
     * 冪等なので次に流したときに揃う（`RecomputeAfterWalkUseCase`）。
     */
    val finishedSessions: Flow<Long>

    /** 記録開始。すでに記録中なら何もしない。 */
    suspend fun start()

    /** 記録停止・セッション確定。記録中でなければ何もしない。 */
    suspend fun stop()
}
