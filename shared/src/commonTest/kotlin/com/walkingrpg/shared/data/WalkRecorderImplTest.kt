package com.walkingrpg.shared.data

import com.walkingrpg.shared.domain.walk.LocationFix
import com.walkingrpg.shared.domain.walk.LocationUnavailableException
import com.walkingrpg.shared.domain.walk.SessionEndReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 測位ストリームと永続化の結線（issue #2 の記録本体）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class WalkRecorderImplTest {

    private fun fix(ts: Long, accuracy: Double = 5.0) = LocationFix(
        timestampMs = ts,
        latitude = 0.0,
        longitude = 0.0,
        accuracyMeters = accuracy,
    )

    private fun TestScope.recorder(
        provider: FakeLocationProvider = FakeLocationProvider(),
        repository: FakeWalkSessionRepository = FakeWalkSessionRepository(),
        keeper: FakeSessionKeeper = FakeSessionKeeper(),
        clock: MutableClock = MutableClock(1_000L),
    ) = WalkRecorderImpl(
        locationProvider = provider,
        sessionRepository = repository,
        sessionKeeper = keeper,
        clock = clock,
        scope = backgroundScope,
    )

    @Test
    fun 開始でセッションが作られ保険も起動する() = runTest {
        val repository = FakeWalkSessionRepository()
        val keeper = FakeSessionKeeper()
        val recorder = recorder(repository = repository, keeper = keeper)

        recorder.start()

        assertTrue(recorder.state.value.isRecording)
        assertEquals(1, repository.sessions.size)
        assertEquals(1_000L, repository.sessions.single().startedAtMs)
        assertEquals(1, keeper.started)
    }

    @Test
    fun 開始時に開きっぱなしのセッションを畳む() = runTest {
        val repository = FakeWalkSessionRepository()
        repository.startSession(startedAtMs = 0L)
        val recorder = recorder(repository = repository)

        recorder.start()

        val abandoned = repository.sessions.first()
        assertEquals(SessionEndReason.ABANDONED, abandoned.endReason)
        assertEquals(2, repository.sessions.size)
    }

    @Test
    fun 放置セッションの終了時刻は最後のサンプルの時刻になる() = runTest {
        val repository = FakeWalkSessionRepository()
        val abandonedId = repository.startSession(startedAtMs = 0L)
        repository.appendSample(fix(ts = 100L).toSample(abandonedId))
        repository.appendSample(fix(ts = 500L).toSample(abandonedId))
        // 数日後に起動した想定
        val recorder = recorder(repository = repository, clock = MutableClock(500_000_000L))

        recorder.start()

        assertEquals(500L, repository.sessions.first().endedAtMs)
    }

    @Test
    fun サンプルの無い放置セッションの終了時刻は開始時刻になる() = runTest {
        val repository = FakeWalkSessionRepository()
        repository.startSession(startedAtMs = 42L)
        val recorder = recorder(repository = repository, clock = MutableClock(500_000_000L))

        recorder.start()

        assertEquals(42L, repository.sessions.first().endedAtMs)
    }

    @Test
    fun 二重に開始してもセッションは増えない() = runTest {
        val repository = FakeWalkSessionRepository()
        val recorder = recorder(repository = repository)

        recorder.start()
        recorder.start()

        assertEquals(1, repository.sessions.size)
    }

    @Test
    fun 受け取ったサンプルが永続化され状態に反映される() = runTest {
        val provider = FakeLocationProvider()
        val repository = FakeWalkSessionRepository()
        val recorder = recorder(provider = provider, repository = repository)

        recorder.start()
        runCurrent()
        provider.fixes.emit(fix(ts = 1_100L))
        provider.fixes.emit(fix(ts = 1_200L, accuracy = 3.0))
        runCurrent()

        val sessionId = repository.sessions.single().id
        assertEquals(2, repository.samplesOf(sessionId).size)
        assertEquals(2, recorder.state.value.sampleCount)
        assertEquals(3.0, recorder.state.value.lastSample?.accuracyMeters)
    }

    @Test
    fun 停止でセッションが確定し保険も止まる() = runTest {
        val provider = FakeLocationProvider()
        val repository = FakeWalkSessionRepository()
        val keeper = FakeSessionKeeper()
        val clock = MutableClock(1_000L)
        val recorder = recorder(provider, repository, keeper, clock)

        recorder.start()
        runCurrent()
        clock.nowMs = 1_800_000L
        recorder.stop()

        val session = repository.sessions.single()
        assertEquals(1_800_000L, session.endedAtMs)
        assertEquals(SessionEndReason.MANUAL, session.endReason)
        assertFalse(recorder.state.value.isRecording)
        assertEquals(1, keeper.stopped)
    }

    @Test
    fun 停止後のサンプルは記録されない() = runTest {
        val provider = FakeLocationProvider()
        val repository = FakeWalkSessionRepository()
        val recorder = recorder(provider = provider, repository = repository)

        recorder.start()
        runCurrent()
        provider.fixes.emit(fix(ts = 1_100L))
        runCurrent()
        recorder.stop()
        provider.fixes.emit(fix(ts = 1_200L))
        runCurrent()

        val sessionId = repository.sessions.single().id
        assertEquals(1, repository.samplesOf(sessionId).size)
    }

    @Test
    fun 記録していないときの停止は何もしない() = runTest {
        val repository = FakeWalkSessionRepository()
        val keeper = FakeSessionKeeper()
        val recorder = recorder(repository = repository, keeper = keeper)

        recorder.stop()

        assertEquals(0, repository.sessions.size)
        assertEquals(0, keeper.stopped)
    }

    @Test
    fun 測位に失敗したらセッションを畳んでエラーを残す() = runTest {
        val provider = FakeLocationProvider(
            failure = LocationUnavailableException("位置情報の権限がありません"),
        )
        val repository = FakeWalkSessionRepository()
        val keeper = FakeSessionKeeper()
        val recorder = recorder(provider = provider, repository = repository, keeper = keeper)

        recorder.start()
        runCurrent()

        // エラー内容は画面に出したいので状態に残す
        assertEquals("位置情報の権限がありません", recorder.state.value.error)
        // 「記録中」表示とForeground Serviceを残さない
        assertFalse(recorder.state.value.isRecording)
        assertEquals(1, keeper.stopped)

        val session = repository.sessions.single()
        assertEquals(SessionEndReason.LOCATION_ERROR, session.endReason)
        // サンプルが1件も無いので現在時刻で畳む
        assertEquals(1_000L, session.endedAtMs)
    }

    @Test
    fun 測位ストリームが終了したら最後のサンプル時刻でセッションを畳む() = runTest {
        val provider = FakeLocationProvider(finiteFixes = listOf(fix(ts = 1_100L), fix(ts = 1_200L)))
        val repository = FakeWalkSessionRepository()
        val keeper = FakeSessionKeeper()
        val clock = MutableClock(1_000L)
        val recorder = recorder(provider, repository, keeper, clock)

        recorder.start()
        clock.nowMs = 9_999_999L
        runCurrent()

        val session = repository.sessions.single()
        assertEquals(1_200L, session.endedAtMs)
        assertEquals(SessionEndReason.LOCATION_ERROR, session.endReason)
        assertEquals(2, repository.samplesOf(session.id).size)
        assertFalse(recorder.state.value.isRecording)
        assertEquals(1, keeper.stopped)
        assertNotNull(recorder.state.value.error)
    }

    @Test
    fun ストリーム終了後に再開できる() = runTest {
        val provider = FakeLocationProvider(finiteFixes = listOf(fix(ts = 1_100L)))
        val repository = FakeWalkSessionRepository()
        val recorder = recorder(provider = provider, repository = repository)

        recorder.start()
        runCurrent()
        recorder.start()
        runCurrent()

        assertEquals(2, repository.sessions.size)
        // 畳んだセッションを再度 ABANDONED で上書きしない
        assertEquals(SessionEndReason.LOCATION_ERROR, repository.sessions.first().endReason)
    }

    @Test
    fun ストリーム終了後の停止は二重に畳まない() = runTest {
        val provider = FakeLocationProvider(finiteFixes = listOf(fix(ts = 1_100L)))
        val repository = FakeWalkSessionRepository()
        val keeper = FakeSessionKeeper()
        val recorder = recorder(provider = provider, repository = repository, keeper = keeper)

        recorder.start()
        runCurrent()
        recorder.stop()

        assertEquals(1_100L, repository.sessions.single().endedAtMs)
        assertEquals(SessionEndReason.LOCATION_ERROR, repository.sessions.single().endReason)
        assertEquals(1, keeper.stopped)
    }

    @Test
    fun 測位間隔は1秒から3秒の範囲で要求する() = runTest {
        val provider = FakeLocationProvider()
        val recorder = recorder(provider = provider)

        recorder.start()
        runCurrent()

        val interval = assertNotNull(provider.requestedIntervalMs)
        assertTrue(interval in 1_000L..3_000L, "測位間隔が想定外: $interval")
    }

    @Test
    fun 開始直後はエラーが残っていない() = runTest {
        val recorder = recorder()

        recorder.start()

        assertNull(recorder.state.value.error)
    }
}
