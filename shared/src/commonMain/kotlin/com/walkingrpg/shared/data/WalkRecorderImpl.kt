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
        sessionRepository.abandonOpenSessions(clock.nowMillis())

        val startedAtMs = clock.nowMillis()
        val sessionId = sessionRepository.startSession(startedAtMs)
        _state.value = WalkRecordingState().started(sessionId, startedAtMs)
        sessionKeeper.start()

        collectJob = scope.launch {
            try {
                locationProvider.updates(updateIntervalMs).collect { fix ->
                    val sample = fix.toSample(sessionId)
                    sessionRepository.appendSample(sample)
                    _state.update { it.sampleRecorded(sample) }
                }
                // 測位側がストリームを終了した＝これ以上サンプルが来ない
                _state.update { it.errored(MESSAGE_STREAM_ENDED) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.update { it.errored(error.message ?: MESSAGE_UNKNOWN_ERROR) }
            }
        }
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
