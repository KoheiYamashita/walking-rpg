package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.data.FakeWalkSessionRepository
import com.walkingrpg.shared.data.MutableClock
import com.walkingrpg.shared.domain.FakeCodexProgressRepository
import com.walkingrpg.shared.domain.FakeOsmMasterRepository
import com.walkingrpg.shared.domain.FakePassageRepository
import com.walkingrpg.shared.domain.FakeRecentCodexRepository
import com.walkingrpg.shared.domain.codex.CodexCategory
import com.walkingrpg.shared.domain.codex.GetCodexUseCase
import com.walkingrpg.shared.domain.codex.testSpecies
import com.walkingrpg.shared.domain.matching.SessionVisit
import com.walkingrpg.shared.domain.review.FakeTimeOfDayResolver
import com.walkingrpg.shared.domain.review.GetWalkReviewUseCase
import com.walkingrpg.shared.domain.steps.CalendarDay
import com.walkingrpg.shared.domain.steps.StepImport
import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.weather.FakeSessionWeatherRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * プロンプト指紋の安定性（issue #16 のいちばん重い制約）。
 *
 * 作った一言（[GenerateWalkReviewRemarkUseCase]）を、**時間を進めてから**読み直しても
 * （[ObserveWalkReviewRemarkUseCase]）「生成済み」として出ること。
 *
 * ここが崩れると、振り返りを開くたびに定型文が出て裏で作り直しが走る
 * ＝**毎回課金する**。記憶（何日前か）や発話履歴の集合が現在時刻に依存していれば、
 * このテストが落ちる。
 */
class WalkRemarkPromptStabilityTest {

    private val species = testSpecies("water", CodexCategory.WATER, requiredVisitCount = 3)

    private val startedAtMs = noonUtcMs("2026-07-30")

    private class Fixture {
        val sessions = FakeWalkSessionRepository()
        val passages = FakePassageRepository()
        val osm = FakeOsmMasterRepository()
        val weather = FakeSessionWeatherRepository()
        val codexProgress = FakeCodexProgressRepository()
        val recentCodex = FakeRecentCodexRepository()
        val cache = FakeLlmCacheRepository()
        val utterances = FakeUtteranceLogRepository()
        val stepImports = FakeStepImportRepository(
            listOf(StepImport(CalendarDay("2026-07-29"), steps = 8_200, distanceEstimateMeters = null)),
        )
        val clock = MutableClock(nowMs = noonUtcMs("2026-07-30") + 60_000L)
    }

    @Test
    fun 時間が経っても生成済みの一言が使われる() = runTest {
        val fixture = Fixture()
        val sessionId = fixture.sessions.startSession(startedAtMs)
        fixture.sessions.endSession(sessionId, startedAtMs + 30 * 60_000L, SessionEndReason.AUTO_ARRIVAL)
        // ひと月前にも歩いた道（記憶の材料）を置く：記憶が「今日」基準なら指紋が動く
        fixture.passages.sessionVisits = mapOf(
            1L to listOf(
                SessionVisit(sessionId = sessionId, firstTimestampMs = startedAtMs),
                SessionVisit(sessionId = 99L, firstTimestampMs = noonUtcMs("2026-06-25")),
            ),
        )

        val getWalkReview = GetWalkReviewUseCase(
            walkSessionRepository = fixture.sessions,
            passageRepository = fixture.passages,
            osmMasterRepository = fixture.osm,
            sessionWeatherRepository = fixture.weather,
            getCodex = GetCodexUseCase(
                codexProgressRepository = fixture.codexProgress,
                getSpeciesDescription = GetSpeciesDescriptionUseCase(fixture.cache),
                recentCodexRepository = fixture.recentCodex,
                catalog = listOf(species),
            ),
            catalog = listOf(species),
        )
        val context = GetWalkRemarkContextUseCase(
            walkSessionRepository = fixture.sessions,
            passageRepository = fixture.passages,
            stepImportRepository = fixture.stepImports,
            utteranceLogRepository = fixture.utterances,
            timeOfDayResolver = FakeTimeOfDayResolver(),
            calendarDays = UtcCalendarDays(),
        )
        val generate = GenerateWalkReviewRemarkUseCase(
            walkSessionRepository = fixture.sessions,
            getWalkReview = getWalkReview,
            getWalkRemarkContext = context,
            cacheRepository = fixture.cache,
            utteranceLogRepository = fixture.utterances,
            setupRepository = LlmSetupRepository(),
            clients = FakeLlmClientSelector(
                FakeLlmClient(responses = listOf("""{"remark": "この道にも、また来たね。"}""")),
            ),
            networkStatus = FakeNetworkStatus(),
            clock = fixture.clock,
        )
        val observe = ObserveWalkReviewRemarkUseCase(
            cacheRepository = fixture.cache,
            getWalkRemarkContext = context,
        )

        assertEquals(1, generate.drain().generated)

        // 半年後にもう一度開く（端末時計を進める）。材料が現在時刻に依存していれば、
        // ここで指紋が変わって「未生成」に落ちる
        fixture.clock.nowMs = noonUtcMs("2027-01-30")
        val review = requireNotNull(getWalkReview(sessionId))
        val remark = observe(review).first()

        assertTrue(remark.isGenerated, "同じ散歩・同じ材料なら指紋は変わらない")
        assertEquals("この道にも、また来たね。", remark.text)
    }
}
