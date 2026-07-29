package com.walkingrpg.shared.data

import com.walkingrpg.shared.data.feedback.InMemoryWalkEventBus
import com.walkingrpg.shared.data.feedback.WalkFeedbackImpl
import com.walkingrpg.shared.domain.FakeOsmMasterRepository
import com.walkingrpg.shared.domain.FakePassageRepository
import com.walkingrpg.shared.domain.feedback.WalkEvent
import com.walkingrpg.shared.domain.feedback.WalkFeedback
import com.walkingrpg.shared.domain.feedback.WalkFeedbackConfig
import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.setup.DEFAULT_HOME_BLUR_RADIUS_METERS
import com.walkingrpg.shared.domain.setup.HomeAnchor
import com.walkingrpg.shared.domain.testWay
import com.walkingrpg.shared.domain.walk.LocationFix
import com.walkingrpg.shared.domain.walk.LocationUnavailableException
import com.walkingrpg.shared.domain.walk.SessionEndReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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

    private fun fix(ts: Long, accuracy: Double = 5.0, metersFromHome: Double = 0.0) = LocationFix(
        timestampMs = ts,
        // 自宅から真北に離れた点（自宅未登録のテストでは座標は使われない）
        latitude = HOME_LATITUDE + metersFromHome / METERS_PER_DEGREE_LATITUDE,
        longitude = HOME_LONGITUDE,
        accuracyMeters = accuracy,
    )

    private fun TestScope.recorder(
        provider: FakeLocationProvider = FakeLocationProvider(),
        repository: FakeWalkSessionRepository = FakeWalkSessionRepository(),
        keeper: FakeSessionKeeper = FakeSessionKeeper(),
        clock: MutableClock = MutableClock(1_000L),
        // 既定は自宅未登録＝自動終了なし。ここを見ないテストが自動終了に巻き込まれない
        setup: FakeSetupRepository = FakeSetupRepository(),
        notifier: FakeWalkNotifier = FakeWalkNotifier(),
        feedback: WalkFeedback = FakeWalkFeedback(),
    ) = WalkRecorderImpl(
        locationProvider = provider,
        sessionRepository = repository,
        sessionKeeper = keeper,
        setupRepository = setup,
        walkNotifier = notifier,
        walkFeedback = feedback,
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

    // --- 終了イベント（issue #10：散歩終了 → 導出の作り直しの起点） ---

    /**
     * [WalkRecorderImpl.finishedSessions] を購読して溜める。
     * 過去のイベントは溜まらない（バッファ付きSharedFlow）ので、記録を始める前に購読する。
     */
    private fun TestScope.collectFinishedSessions(recorder: WalkRecorderImpl): List<Long> {
        val finished = mutableListOf<Long>()
        backgroundScope.launch { recorder.finishedSessions.collect { finished += it } }
        runCurrent()
        return finished
    }

    @Test
    fun 手動停止でセッションIDが流れる() = runTest {
        val repository = FakeWalkSessionRepository()
        val recorder = recorder(repository = repository)
        val finished = collectFinishedSessions(recorder)

        recorder.start()
        runCurrent()
        recorder.stop()
        runCurrent()

        assertEquals(listOf(repository.sessions.single().id), finished)
    }

    @Test
    fun 自動終了でセッションIDが流れる() = runTest {
        val provider = FakeLocationProvider()
        val repository = FakeWalkSessionRepository()
        val recorder = recorder(
            provider = provider,
            repository = repository,
            clock = MutableClock(0L),
            setup = FakeSetupRepository(homeAnchor = HOME),
        )
        val finished = collectFinishedSessions(recorder)

        recorder.start()
        runCurrent()
        homecomingFixes().forEach { provider.fixes.emit(it) }
        runCurrent()

        assertEquals(SessionEndReason.AUTO_ARRIVAL, repository.sessions.single().endReason)
        assertEquals(listOf(repository.sessions.single().id), finished)
    }

    @Test
    fun 測位が止まって畳んだときもセッションIDが流れる() = runTest {
        // 歩いたぶんのサンプルは残っているので、エラー終了でも導出はやる
        val provider = FakeLocationProvider(finiteFixes = listOf(fix(ts = 1_100L)))
        val repository = FakeWalkSessionRepository()
        val recorder = recorder(provider = provider, repository = repository)
        val finished = collectFinishedSessions(recorder)

        recorder.start()
        runCurrent()

        assertEquals(SessionEndReason.LOCATION_ERROR, repository.sessions.single().endReason)
        assertEquals(listOf(repository.sessions.single().id), finished)
    }

    @Test
    fun 開きっぱなしのセッションを畳んだときもセッションIDが流れる() = runTest {
        // クラッシュで残った散歩。サンプルは真実として残っているので導出はやる。
        // passage の作り直しはセッション単位なので、ここで拾い損ねると
        // その散歩は二度と way_growth に反映されない
        val repository = FakeWalkSessionRepository()
        val abandonedId = repository.startSession(startedAtMs = 0L)
        repository.appendSample(fix(ts = 100L).toSample(abandonedId))
        val recorder = recorder(repository = repository)
        val finished = collectFinishedSessions(recorder)

        recorder.start()
        runCurrent()

        assertEquals(
            SessionEndReason.ABANDONED,
            repository.sessions.first { it.id == abandonedId }.endReason,
        )
        assertEquals(listOf(abandonedId), finished)
    }

    @Test
    fun 開きっぱなしが無ければ何も流れない() = runTest {
        val recorder = recorder()
        val finished = collectFinishedSessions(recorder)

        recorder.start()
        runCurrent()

        // 始めたばかりのセッションを終了扱いにしない
        assertEquals(emptyList(), finished)
    }

    @Test
    fun 二重に畳んでもセッションIDは1回しか流れない() = runTest {
        val provider = FakeLocationProvider(finiteFixes = listOf(fix(ts = 1_100L)))
        val repository = FakeWalkSessionRepository()
        val recorder = recorder(provider = provider, repository = repository)
        val finished = collectFinishedSessions(recorder)

        recorder.start()
        runCurrent()
        // ストリーム終了で畳んだあとの手動停止（何もしない経路）
        recorder.stop()
        runCurrent()

        assertEquals(1, finished.size)
    }

    // --- 自動終了（design.md §3「自宅付近＋移動停止を検知して『おかえり』を出す」） ---

    /** 出発 → 遠出 → 帰宅して玄関で2分静止、という並び。 */
    private fun homecomingFixes(): List<LocationFix> = listOf(
        fix(ts = 0, metersFromHome = 0.0),
        fix(ts = 60_000, metersFromHome = 400.0),
        fix(ts = 1_200_000, metersFromHome = 5.0),
        fix(ts = 1_320_000, metersFromHome = 5.0),
    )

    @Test
    fun 自宅に着いて止まったら自動終了して通知を出す() = runTest {
        val provider = FakeLocationProvider()
        val repository = FakeWalkSessionRepository()
        val keeper = FakeSessionKeeper()
        val notifier = FakeWalkNotifier()
        val recorder = recorder(
            provider = provider,
            repository = repository,
            keeper = keeper,
            clock = MutableClock(0L),
            setup = FakeSetupRepository(homeAnchor = HOME),
            notifier = notifier,
        )

        recorder.start()
        runCurrent()
        homecomingFixes().forEach { provider.fixes.emit(it) }
        runCurrent()

        val session = repository.sessions.single()
        assertEquals(SessionEndReason.AUTO_ARRIVAL, session.endReason)
        // 終了時刻は判定が成立したサンプルの時刻（端末時計ではない）
        assertEquals(1_320_000L, session.endedAtMs)
        assertFalse(recorder.state.value.isRecording)
        assertNull(recorder.state.value.error)
        // 保険（Foreground Service）も畳む
        assertEquals(1, keeper.stopped)
        // 「おかえり」は1回だけ、セッションの長さつきで
        assertEquals(listOf(1_320_000L), notifier.homecomings)
    }

    @Test
    fun 自動終了したあとのサンプルは記録されない() = runTest {
        val provider = FakeLocationProvider()
        val repository = FakeWalkSessionRepository()
        val recorder = recorder(
            provider = provider,
            repository = repository,
            clock = MutableClock(0L),
            setup = FakeSetupRepository(homeAnchor = HOME),
        )

        recorder.start()
        runCurrent()
        homecomingFixes().forEach { provider.fixes.emit(it) }
        runCurrent()
        provider.fixes.emit(fix(ts = 1_400_000, metersFromHome = 5.0))
        runCurrent()

        val session = repository.sessions.single()
        assertEquals(4, repository.samplesOf(session.id).size)
    }

    @Test
    fun 自宅が未登録なら自動終了しない() = runTest {
        val provider = FakeLocationProvider()
        val repository = FakeWalkSessionRepository()
        val notifier = FakeWalkNotifier()
        val recorder = recorder(
            provider = provider,
            repository = repository,
            clock = MutableClock(0L),
            setup = FakeSetupRepository(homeAnchor = null),
            notifier = notifier,
        )

        recorder.start()
        runCurrent()
        homecomingFixes().forEach { provider.fixes.emit(it) }
        runCurrent()

        assertTrue(recorder.state.value.isRecording)
        assertNull(repository.sessions.single().endReason)
        assertTrue(notifier.homecomings.isEmpty())
    }

    @Test
    fun 自宅が読めなくても記録は始まる() = runTest {
        val provider = FakeLocationProvider()
        val repository = FakeWalkSessionRepository()
        val recorder = recorder(
            provider = provider,
            repository = repository,
            clock = MutableClock(0L),
            setup = FakeSetupRepository(homeAnchorFailure = IllegalStateException("鍵が壊れています")),
        )

        recorder.start()
        runCurrent()
        homecomingFixes().forEach { provider.fixes.emit(it) }
        runCurrent()

        // 自動終了は効かないが、記録そのものは続く（手動停止で畳める）
        assertTrue(recorder.state.value.isRecording)
        assertEquals(4, repository.samplesOf(repository.sessions.single().id).size)
    }

    @Test
    fun 自動終了のあとに手動停止しても二重に畳まない() = runTest {
        val provider = FakeLocationProvider()
        val repository = FakeWalkSessionRepository()
        val keeper = FakeSessionKeeper()
        val notifier = FakeWalkNotifier()
        val recorder = recorder(
            provider = provider,
            repository = repository,
            keeper = keeper,
            clock = MutableClock(0L),
            setup = FakeSetupRepository(homeAnchor = HOME),
            notifier = notifier,
        )

        recorder.start()
        runCurrent()
        homecomingFixes().forEach { provider.fixes.emit(it) }
        runCurrent()
        recorder.stop()

        val session = repository.sessions.single()
        assertEquals(SessionEndReason.AUTO_ARRIVAL, session.endReason)
        assertEquals(1_320_000L, session.endedAtMs)
        assertEquals(1, keeper.stopped)
        assertEquals(1, notifier.homecomings.size)
    }

    // --- 歩行中フィードバック（issue #12：記録 → 見込み判定 → 振動の結線） ---

    @Test
    fun 段階アップ相当のサンプル列で振動が1回だけ来る() = runTest {
        val provider = FakeLocationProvider()
        val haptics = FakeHaptics()
        val eventBus = InMemoryWalkEventBus()
        val feedback = WalkFeedbackImpl(
            // 自宅から真北100mまでが1本の道（testWay の既定形状＝HOME からの直線）
            osmMasterRepository = FakeOsmMasterRepository(ways = listOf(testWay(id = 1L))),
            // 未踏の道：1回通れば必ず草に上がる（GrowthConfig.GRASS_PASS_COUNT）
            passageRepository = FakePassageRepository(),
            eventBus = eventBus,
            haptics = haptics,
        )
        val recorder = recorder(provider = provider, feedback = feedback)

        recorder.start()
        runCurrent()
        // 5秒ごとに5m進む（＝1 m/s、歩行速度）。2件目でスナップの塊が成立する
        repeat(4) { index ->
            provider.fixes.emit(
                fix(ts = 1_000L + index * 5_000L, metersFromHome = index * 5.0),
            )
        }
        runCurrent()

        // 同じ道を歩き続けても通過は1回＝振動も1回
        assertEquals(1, haptics.vibrations)
        val sessionId = recorder.state.value.sessionId
        val events = eventBus.eventsOf(assertNotNull(sessionId))
        assertEquals(1, events.size)
        assertEquals(1L, (events.single() as WalkEvent.GrowthStageUp).wayId)
    }

    @Test
    fun 振動の上限を超えたぶんは無音でイベントだけ記録される() = runTest {
        val provider = FakeLocationProvider()
        val haptics = FakeHaptics()
        val eventBus = InMemoryWalkEventBus()
        val feedback = WalkFeedbackImpl(
            // 自宅から真北へ2本つながった道（0〜111m と 111〜222m）。どちらも未踏なので、
            // 通れば必ず草に上がる＝1回の散歩でイベントが2件出る
            osmMasterRepository = FakeOsmMasterRepository(
                ways = listOf(testWay(id = 1L), NEXT_WAY),
            ),
            passageRepository = FakePassageRepository(),
            eventBus = eventBus,
            haptics = haptics,
            // 1回だけ鳴らす設定にして、2回目以降が無音になることを見る
            feedbackConfig = WalkFeedbackConfig(
                maxVibrationsPerWalk = 1,
                minVibrationIntervalMs = 0L,
            ),
        )
        val recorder = recorder(provider = provider, feedback = feedback)

        recorder.start()
        runCurrent()
        // 1本目を歩いてから2本目へ移る（60秒で145m＝2.4 m/s、歩行速度の範囲）
        listOf(0.0, 5.0, 150.0, 155.0).forEachIndexed { index, meters ->
            provider.fixes.emit(fix(ts = 1_000L + index * 60_000L, metersFromHome = meters))
        }
        runCurrent()

        val events = eventBus.eventsOf(assertNotNull(recorder.state.value.sessionId))
        assertEquals(2, events.size, "イベントは全部記録される（振り返りには全部出る）")
        assertEquals(1, haptics.vibrations, "振動は上限まで")
    }

    @Test
    fun 記録開始と各サンプルがフィードバックに流れる() = runTest {
        val provider = FakeLocationProvider()
        val feedback = FakeWalkFeedback()
        val recorder = recorder(provider = provider, feedback = feedback)

        recorder.start()
        runCurrent()
        provider.fixes.emit(fix(ts = 1_100L))
        provider.fixes.emit(fix(ts = 1_200L))
        runCurrent()

        assertEquals(listOf(recorder.state.value.sessionId), feedback.startedSessions)
        assertEquals(2, feedback.samples.size)
    }

    private companion object {
        const val HOME_LATITUDE = 35.0
        const val HOME_LONGITUDE = 139.0

        /** 子午線上の1度の長さ（m）。真北にずらすので経度のスケールを考えなくてよい。 */
        const val METERS_PER_DEGREE_LATITUDE = 111_194.9

        val HOME = HomeAnchor(
            latitude = HOME_LATITUDE,
            longitude = HOME_LONGITUDE,
            blurRadiusMeters = DEFAULT_HOME_BLUR_RADIUS_METERS,
        )

        /** `testWay` の既定形状（自宅から真北111m）の続き。111〜222mの区間。 */
        val NEXT_WAY = testWay(
            id = 2L,
            points = listOf(
                GeoPoint(latitude = 35.001, longitude = HOME_LONGITUDE),
                GeoPoint(latitude = 35.002, longitude = HOME_LONGITUDE),
            ),
        )
    }
}
