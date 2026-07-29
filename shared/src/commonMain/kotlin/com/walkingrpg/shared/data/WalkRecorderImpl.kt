package com.walkingrpg.shared.data

import com.walkingrpg.shared.domain.Clock
import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.setup.SetupRepository
import com.walkingrpg.shared.domain.walk.HomeArrivalConfig
import com.walkingrpg.shared.domain.walk.HomeArrivalDetector
import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.walk.WalkRecorder
import com.walkingrpg.shared.domain.walk.WalkRecordingState
import com.walkingrpg.shared.domain.walk.WalkSessionRepository
import com.walkingrpg.shared.platform.LocationProvider
import com.walkingrpg.shared.platform.SessionKeeper
import com.walkingrpg.shared.platform.WalkNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 *
 * 終了は自動が主で、手動停止は誤検知時の逃げ道（design.md §3）。
 * 自宅到着の判定そのものは [HomeArrivalDetector]（純関数）に置き、
 * ここは「サンプルを流し込んで、成立したら畳む」だけを持つ。
 */
internal class WalkRecorderImpl(
    private val locationProvider: LocationProvider,
    private val sessionRepository: WalkSessionRepository,
    private val sessionKeeper: SessionKeeper,
    private val setupRepository: SetupRepository,
    private val walkNotifier: WalkNotifier,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val updateIntervalMs: Long = LocationProvider.DEFAULT_INTERVAL_MS,
    private val homeArrivalConfig: HomeArrivalConfig = HomeArrivalConfig.DEFAULT,
) : WalkRecorder {

    private val _state = MutableStateFlow(WalkRecordingState())
    override val state: StateFlow<WalkRecordingState> = _state.asStateFlow()

    /**
     * 畳んだセッションの通知。バッファ付きにして [tryEmit] で出すのは、
     * **記録を畳む処理を購読側の都合で待たせないため**（`suspend fun emit` だと
     * 購読側が詰まっているあいだ mutex を握ったままになる）。
     * 散歩は1回ずつしか終わらないので、バッファが溢れることは実質ない。
     */
    private val _finishedSessions = MutableSharedFlow<Long>(
        extraBufferCapacity = FINISHED_SESSIONS_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val finishedSessions: Flow<Long> = _finishedSessions.asSharedFlow()

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

        // 自宅はセッションの頭で1回だけ読む（測位1件ごとにセキュアストレージを叩かない）。
        // 散歩の途中で自宅を引っ越すことはないので、これで足りる。
        // 読めなくても記録自体は始める：自動終了が効かなくなるだけで、手動停止は使える。
        val home = runCatching { setupRepository.loadHomeAnchor() }.getOrNull()
        var arrival = HomeArrivalDetector(
            // ぼかし半径は表示用なので判定には持ち込まない（HomeArrivalConfig 参照）
            home = home?.let { GeoPoint(latitude = it.latitude, longitude = it.longitude) },
            sessionStartedAtMs = startedAtMs,
            config = homeArrivalConfig,
        )

        collectJob = scope.launch {
            val message = try {
                locationProvider.updates(updateIntervalMs).collect { fix ->
                    val sample = fix.toSample(sessionId)
                    sessionRepository.appendSample(sample)
                    _state.update { it.sampleRecorded(sample) }

                    arrival = arrival.sampleRecorded(sample)
                    // collect の中から自分自身の Job はキャンセルできないので、
                    // 専用のシグナルでストリームを抜けてから畳む
                    if (arrival.isArrived) throw HomecomingSignal(sample.timestampMs)
                }
                // 測位側がストリームを終了した＝これ以上サンプルが来ない
                MESSAGE_STREAM_ENDED
            } catch (homecoming: HomecomingSignal) {
                finishOnHomecoming(sessionId, homecoming.endedAtMs)
                return@launch
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
     * 自宅に着いて止まったので畳む（design.md §3）。
     *
     * 終了時刻は判定が成立したサンプルの時刻。「止まり始めた時刻」の方が帰宅の実態には
     * 近いが、それを終了時刻にすると**終了時刻より後ろのサンプルが同じセッションに残る**
     * （止まっている間もサンプルは全件保存している）。導出（map matching）は
     * セッションのサンプル列を丸ごと見るので、時刻の整合が崩れている方が厄介。
     *
     * 「おかえり」通知はDBを確定させてから出す。通知が先だと、
     * 通知をタップして開いた画面にまだ記録中のセッションが見えることがある。
     */
    private suspend fun finishOnHomecoming(sessionId: Long, endedAtMs: Long) = mutex.withLock {
        // stop() と競り合って先に畳まれていたら何もしない
        if (_state.value.sessionId != sessionId) return@withLock

        collectJob = null
        sessionKeeper.stop()

        val startedAtMs = _state.value.startedAtMs ?: endedAtMs
        sessionRepository.endSession(sessionId, endedAtMs, SessionEndReason.AUTO_ARRIVAL)
        _state.update { it.stopped(endedAtMs) }
        _finishedSessions.tryEmit(sessionId)

        walkNotifier.notifyHomecoming((endedAtMs - startedAtMs).coerceAtLeast(0L))
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
        // エラー終了でも導出はやる：測位が途中で止まっただけで、
        // そこまでのサンプルは真実として残っている（歩いたぶんは育つ）。
        _finishedSessions.tryEmit(sessionId)
    }

    override suspend fun stop(): Unit = mutex.withLock {
        val sessionId = _state.value.sessionId ?: return@withLock

        collectJob?.cancelAndJoin()
        collectJob = null
        sessionKeeper.stop()

        val endedAtMs = clock.nowMillis()
        sessionRepository.endSession(sessionId, endedAtMs, SessionEndReason.MANUAL)
        _state.update { it.stopped(endedAtMs) }
        _finishedSessions.tryEmit(sessionId)
    }

    /**
     * 自宅到着で測位ストリームを抜けるための内部シグナル。エラーではない。
     * [CancellationException] を使わないのは、それだと stop() によるキャンセルと
     * 見分けがつかず、畳む責任がどちらにあるか決められなくなるため。
     */
    private class HomecomingSignal(val endedAtMs: Long) : Throwable()

    private companion object {
        const val FINISHED_SESSIONS_BUFFER = 8

        const val MESSAGE_STREAM_ENDED = "測位が停止しました（権限や位置情報設定を確認してください）"
        const val MESSAGE_UNKNOWN_ERROR = "測位に失敗しました"
    }
}
