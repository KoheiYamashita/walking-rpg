package com.walkingrpg.shared.data.feedback

import com.walkingrpg.shared.data.FakeHaptics
import com.walkingrpg.shared.domain.FakeOsmMasterRepository
import com.walkingrpg.shared.domain.FakePassageRepository
import com.walkingrpg.shared.domain.feedback.WalkEvent
import com.walkingrpg.shared.domain.feedback.WalkFeedbackConfig
import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.matching.PassageRepository
import com.walkingrpg.shared.domain.osm.Way
import com.walkingrpg.shared.domain.testWay
import com.walkingrpg.shared.domain.walk.LocationSample
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
        haptics: FakeHaptics,
        eventBus: InMemoryWalkEventBus,
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

    /** DBが壊れている・まだ作られていない状況の再現。 */
    private class BrokenPassageRepository : PassageRepository {
        override suspend fun replaceSessionPassages(sessionId: Long, passages: List<Passage>) = Unit
        override suspend fun passages(sessionId: Long): List<Passage> = emptyList()
        override suspend fun passCountsByWay(): Map<Long, Int> = error("DBが読めません")
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
