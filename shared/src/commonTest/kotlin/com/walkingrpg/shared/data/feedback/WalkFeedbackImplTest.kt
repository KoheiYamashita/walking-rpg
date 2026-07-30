package com.walkingrpg.shared.data.feedback

import com.walkingrpg.shared.data.FakeHaptics
import com.walkingrpg.shared.domain.FakeOsmMasterRepository
import com.walkingrpg.shared.domain.FakePassageRepository
import com.walkingrpg.shared.domain.feedback.WalkEvent
import com.walkingrpg.shared.domain.feedback.WalkEventBus
import com.walkingrpg.shared.domain.feedback.WalkFeedbackConfig
import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.matching.PassageRepository
import com.walkingrpg.shared.domain.matching.SessionVisit
import com.walkingrpg.shared.domain.osm.Way
import com.walkingrpg.shared.domain.testWay
import com.walkingrpg.shared.domain.walk.LocationSample
import com.walkingrpg.shared.platform.Haptics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 歩行中フィードバックの結線（issue #12）：見込み判定 → イベント記録 → 振動。
 *
 * 判定そのものは `LiveGrowthEstimatorTest`、回数制限は `VibrationBudgetTest` が見る。
 * ここで見るのは「記録は全件・振動は上限まで」と「壊れても黙るだけ」。
 */
class WalkFeedbackImplTest {

    private fun sample(ts: Long, meters: Double) = LocationSample(
        sessionId = SESSION_ID,
        timestampMs = ts,
        latitude = ORIGIN_LATITUDE + meters / METERS_PER_DEGREE_LATITUDE,
        longitude = ORIGIN_LONGITUDE,
        accuracyMeters = 5.0,
    )

    private fun feedback(
        haptics: Haptics,
        eventBus: WalkEventBus,
        passageRepository: PassageRepository = FakePassageRepository(),
        config: WalkFeedbackConfig = WalkFeedbackConfig.DEFAULT,
        ways: List<Way> = listOf(testWay(id = 1L), NEXT_WAY),
    ) = WalkFeedbackImpl(
        osmMasterRepository = FakeOsmMasterRepository(ways = ways),
        passageRepository = passageRepository,
        eventBus = eventBus,
        haptics = haptics,
        feedbackConfig = config,
    )

    /** 1本目（0〜111m）を歩いてから2本目（111〜222m）へ移る。どちらも未踏なら草が2回。 */
    private suspend fun WalkFeedbackImpl.walkTwoWays() {
        walkStarted(SESSION_ID)
        listOf(0.0, 5.0, 150.0, 155.0).forEachIndexed { index, meters ->
            sampleRecorded(sample(ts = index * 60_000L, meters = meters))
        }
    }

    @Test
    fun 段階アップでイベントが記録され振動する() = runTest {
        val haptics = FakeHaptics()
        val eventBus = InMemoryWalkEventBus()

        feedback(haptics, eventBus).apply {
            walkStarted(SESSION_ID)
            sampleRecorded(sample(ts = 0L, meters = 0.0))
            sampleRecorded(sample(ts = 5_000L, meters = 5.0))
        }

        assertEquals(1, haptics.vibrations)
        val event = eventBus.eventsOf(SESSION_ID).single()
        assertEquals(1L, (event as WalkEvent.GrowthStageUp).wayId)
    }

    @Test
    fun 上限を超えたぶんは無音でイベントだけ記録される() = runTest {
        val haptics = FakeHaptics()
        val eventBus = InMemoryWalkEventBus()

        feedback(
            haptics = haptics,
            eventBus = eventBus,
            config = WalkFeedbackConfig(maxVibrationsPerWalk = 1, minVibrationIntervalMs = 0L),
        ).walkTwoWays()

        assertEquals(2, eventBus.eventsOf(SESSION_ID).size, "振り返りには全部出る")
        assertEquals(1, haptics.vibrations, "振動は上限まで")
    }

    @Test
    fun 散歩をまたぐと振動の残数は戻る() = runTest {
        val haptics = FakeHaptics()
        val eventBus = InMemoryWalkEventBus()
        val target = feedback(
            haptics = haptics,
            eventBus = eventBus,
            config = WalkFeedbackConfig(maxVibrationsPerWalk = 1, minVibrationIntervalMs = 0L),
        )

        target.walkTwoWays()
        // 次の散歩（未踏の道はもう無いが、見込みの通過回数も作り直されるので同じことが起きる）
        target.walkTwoWays()

        assertEquals(2, haptics.vibrations, "散歩ごとに1回ずつ")
    }

    @Test
    fun 通過回数が読めない散歩は黙るだけで例外にならない() = runTest {
        val haptics = FakeHaptics()
        val eventBus = InMemoryWalkEventBus()

        feedback(
            haptics = haptics,
            eventBus = eventBus,
            passageRepository = BrokenPassageRepository(),
        ).walkTwoWays()

        assertEquals(0, haptics.vibrations)
        assertTrue(eventBus.eventsOf(SESSION_ID).isEmpty())
    }

    @Test
    fun 対象圏のwayが無ければ何も起きない() = runTest {
        val haptics = FakeHaptics()
        val eventBus = InMemoryWalkEventBus()

        feedback(haptics = haptics, eventBus = eventBus, ways = emptyList()).walkTwoWays()

        assertEquals(0, haptics.vibrations)
        assertTrue(eventBus.eventsOf(SESSION_ID).isEmpty())
    }

    @Test
    fun イベントの記録が失敗しても記録の収集に例外を伝えない() = runTest {
        val haptics = FakeHaptics()

        // 投げると WalkRecorderImpl の catch-all に落ちて、測位は健全なのに
        // セッションが LOCATION_ERROR で畳まれる（WalkFeedback の契約）
        feedback(haptics = haptics, eventBus = BrokenWalkEventBus()).walkTwoWays()

        assertEquals(0, haptics.vibrations, "イベントを記録できなければ鳴らさない")
    }

    @Test
    fun 振動が失敗しても記録の収集に例外を伝えない() = runTest {
        val eventBus = InMemoryWalkEventBus()

        feedback(haptics = BrokenHaptics(), eventBus = eventBus).walkTwoWays()

        // 鳴らせなくてもイベントは残る（振り返りには出る）
        assertEquals(2, eventBus.eventsOf(SESSION_ID).size)
    }

    @Test
    fun 一度の失敗でその散歩のフィードバックを黙らせない() = runTest {
        val haptics = FakeHaptics()
        val eventBus = InMemoryWalkEventBus()
        // 1本目の道のイベントだけ失敗させる
        val flaky = FlakyWalkEventBus(eventBus, failOnEventIndex = 0)

        feedback(haptics = haptics, eventBus = flaky).walkTwoWays()

        // 1件目は落ちて記録も振動もされないが、2件目は普通に通る
        assertEquals(1, eventBus.eventsOf(SESSION_ID).size)
        assertEquals(1, haptics.vibrations)
    }

    /** 記録先が壊れている状況の再現（`publish` は歩行中に毎サンプル呼ばれる）。 */
    private class BrokenWalkEventBus : WalkEventBus {
        override val events: Flow<WalkEvent> = emptyFlow()
        override fun publish(event: WalkEvent): Unit = error("イベントを記録できません")
        override fun eventsOf(sessionId: Long): List<WalkEvent> = emptyList()
    }

    /** 何件目かだけ失敗する記録先（失敗のあとも判定が続くことの確認用）。 */
    private class FlakyWalkEventBus(
        private val delegate: WalkEventBus,
        private val failOnEventIndex: Int,
    ) : WalkEventBus {
        private var published = 0

        override val events: Flow<WalkEvent> get() = delegate.events

        override fun publish(event: WalkEvent) {
            val index = published++
            if (index == failOnEventIndex) error("イベントを記録できません")
            delegate.publish(event)
        }

        override fun eventsOf(sessionId: Long): List<WalkEvent> = delegate.eventsOf(sessionId)
    }

    /** 振動モーターの呼び出しが投げる端末の再現（本来 Haptics 実装は投げない契約）。 */
    private class BrokenHaptics : Haptics {
        override fun vibrateOnce(): Unit = error("振動させられません")
    }

    /** DBが壊れている・まだ作られていない状況の再現。 */
    private class BrokenPassageRepository : PassageRepository {
        override suspend fun replaceSessionPassages(sessionId: Long, passages: List<Passage>) = Unit
        override suspend fun passages(sessionId: Long): List<Passage> = emptyList()
        override suspend fun passCountsByWay(): Map<Long, Int> = error("DBが読めません")
        override suspend fun sessionVisitsByWay(): Map<Long, List<SessionVisit>> =
            error("DBが読めません")
    }

    private companion object {
        const val SESSION_ID = 3L

        const val ORIGIN_LATITUDE = 35.0
        const val ORIGIN_LONGITUDE = 139.0
        const val METERS_PER_DEGREE_LATITUDE = 111_194.9

        /** `testWay` の既定形状（原点から真北111m）の続き。111〜222mの区間。 */
        val NEXT_WAY = testWay(
            id = 2L,
            points = listOf(
                GeoPoint(latitude = 35.001, longitude = ORIGIN_LONGITUDE),
                GeoPoint(latitude = 35.002, longitude = ORIGIN_LONGITUDE),
            ),
        )
    }
}
