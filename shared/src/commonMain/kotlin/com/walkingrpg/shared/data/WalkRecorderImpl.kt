package com.walkingrpg.shared.data

import com.walkingrpg.shared.domain.Clock
import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.walk.WalkRecorder
import com.walkingrpg.shared.domain.walk.WalkRecordingState
import com.walkingrpg.shared.domain.walk.WalkSessionRepository
import com.walkingrpg.shared.platform.LocationProvider
import com.walkingrpg.shared.platform.SessionKeeper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [WalkRecorder] の実装。測位ストリーム（platform層）と永続化（data層）を結線する。
 *
 * 収集はアプリ全体スコープの子コルーチンで回す。画面を離れても記録は続き、
 * 誤って画面オフ・ロックされた場合は [SessionKeeper]（Android: Foreground Service）が保険になる。
 *
 * スパイクでは精度フィルタをかけず、取得できたサンプルを全件そのまま残す
 * （欠落率とばらつきを測るのが目的。フィルタは #8 以降で map matching と一緒に決める）。
 */
internal class WalkRecorderImpl(
    private val locationProvider: LocationProvider,
    private val sessionRepository: WalkSessionRepository,
    private val sessionKeeper: SessionKeeper,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val updateIntervalMs: Long = LocationProvider.DEFAULT_INTERVAL_MS,
) : WalkRecorder {

    private val _state = MutableStateFlow(WalkRecordingState())
    override val state: StateFlow<WalkRecordingState> = _state.asStateFlow()

    /** start/stop の割り込みを直列化する。 */
    private val mutex = Mutex()
    private var collectJob: Job? = null

    override suspend fun start(): Unit = mutex.withLock {
        if (_state.value.isRecording) return@withLock

        // 前回アプリが落ちて開きっぱなしのセッションがあれば畳んでおく
        // （終了時刻は最後のサンプルの時刻。決めるのはリポジトリ側）
        sessionRepository.abandonOpenSessions()

        val startedAtMs = clock.nowMillis()
        val sessionId = sessionRepository.startSession(startedAtMs)
        _state.value = WalkRecordingState().started(sessionId, startedAtMs)
        sessionKeeper.start()

        collectJob = scope.launch {
            val message = try {
                locationProvider.updates(updateIntervalMs).collect { fix ->
                    val sample = fix.toSample(sessionId)
                    sessionRepository.appendSample(sample)
                    _state.update { it.sampleRecorded(sample) }
                }
                // 測位側がストリームを終了した＝これ以上サンプルが来ない
                MESSAGE_STREAM_ENDED
            } catch (cancellation: CancellationException) {
                // stop() によるキャンセル。畳むのは stop() の仕事
                throw cancellation
            } catch (error: Throwable) {
                error.message ?: MESSAGE_UNKNOWN_ERROR
            }
            finishAfterStreamEnded(sessionId, message)
        }
    }

    /**
     * 測位ストリームが終わった（＝もうサンプルが来ない）ときの後始末。
     *
     * ここで畳まないと、記録中表示と [SessionKeeper]（Foreground Service）が
     * 手動停止まで無期限に残り、DBのセッションも開きっぱなしになる。
     * 終了時刻は最後に取れたサンプルの時刻（1件も無ければ現在時刻）で、
     * 「実際に記録できていたところまで」をセッションの長さにする。
     * エラーメッセージは状態に残して、なぜ止まったかを画面に出せるようにする。
     */
    private suspend fun finishAfterStreamEnded(sessionId: Long, message: String) = mutex.withLock {
        // すでに stop() で畳まれている／別セッションが始まっているなら何もしない
        if (_state.value.sessionId != sessionId) return@withLock

        collectJob = null
        sessionKeeper.stop()

        val endedAtMs = _state.value.lastSample?.timestampMs ?: clock.nowMillis()
        sessionRepository.endSession(sessionId, endedAtMs, SessionEndReason.LOCATION_ERROR)
        _state.update { it.stopped(endedAtMs).errored(message) }
    }

    override suspend fun stop(): Unit = mutex.withLock {
        val sessionId = _state.value.sessionId ?: return@withLock

        collectJob?.cancelAndJoin()
        collectJob = null
        sessionKeeper.stop()

        val endedAtMs = clock.nowMillis()
        sessionRepository.endSession(sessionId, endedAtMs, SessionEndReason.MANUAL)
        _state.update { it.stopped(endedAtMs) }
    }

    private companion object {
        const val MESSAGE_STREAM_ENDED = "測位が停止しました（権限や位置情報設定を確認してください）"
        const val MESSAGE_UNKNOWN_ERROR = "測位に失敗しました"
    }
}
