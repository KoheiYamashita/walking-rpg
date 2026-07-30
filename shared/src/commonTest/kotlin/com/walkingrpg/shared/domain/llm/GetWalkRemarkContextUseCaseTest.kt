package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.data.FakeWalkSessionRepository
import com.walkingrpg.shared.domain.FakePassageRepository
import com.walkingrpg.shared.domain.codex.CodexCategory
import com.walkingrpg.shared.domain.codex.testSpecies
import com.walkingrpg.shared.domain.growth.GrowthStage
import com.walkingrpg.shared.domain.matching.SessionVisit
import com.walkingrpg.shared.domain.review.FakeTimeOfDayResolver
import com.walkingrpg.shared.domain.review.ReviewGrownWay
import com.walkingrpg.shared.domain.review.WalkReview
import com.walkingrpg.shared.domain.steps.CalendarDay
import com.walkingrpg.shared.domain.steps.StepImport
import com.walkingrpg.shared.domain.walk.SessionEndReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 一言の材料の調達（[GetWalkRemarkContextUseCase]）。
 *
 * ここがこの機能のいちばん壊れやすい所なので、見たいのは指紋の安定に直結する性質：
 * - 生成側と読み側で**同じ材料**（＝同じプロンプト指紋）になる
 * - `utterance_log` の除外集合は「自分より前・別のセッション」だけ
 * - 歩数の言及はセッションが無い日・その散歩の日より前だけ
 */
class GetWalkRemarkContextUseCaseTest {

    private val species = testSpecies("water", CodexCategory.WATER, requiredVisitCount = 3)

    private val sessionId = 10L
    private val startedAtMs = noonUtcMs("2026-07-30")

    private class Fixture {
        val sessions = FakeWalkSessionRepository()
        val passages = FakePassageRepository()
        var stepImports = FakeStepImportRepository()
        var utterances = FakeUtteranceLogRepository()
    }

    private fun useCase(fixture: Fixture) = GetWalkRemarkContextUseCase(
        walkSessionRepository = fixture.sessions,
        passageRepository = fixture.passages,
        stepImportRepository = fixture.stepImports,
        utteranceLogRepository = fixture.utterances,
        timeOfDayResolver = FakeTimeOfDayResolver(),
        calendarDays = UtcCalendarDays(),
    )

    private fun review(
        grown: Boolean = false,
        discovered: Boolean = false,
    ) = WalkReview(
        sessionId = sessionId,
        startedAtMs = startedAtMs,
        endedAtMs = startedAtMs + 30 * 60_000L,
        durationMs = 30 * 60_000L,
        distanceMeters = 2_300.0,
        grownWays = if (grown) {
            listOf(ReviewGrownWay(1L, null, GrowthStage.SHRUB, GrowthStage.TREE))
        } else {
            emptyList()
        },
        discoveredSpecies = if (discovered) listOf(species) else emptyList(),
    )

    /** 「この散歩でway 1を通った。ひと月前にも通っていた」状態。 */
    private fun monthOldWay(fixture: Fixture) {
        fixture.passages.sessionVisits = mapOf(
            1L to listOf(
                SessionVisit(sessionId = sessionId, firstTimestampMs = startedAtMs),
                SessionVisit(sessionId = 1L, firstTimestampMs = noonUtcMs("2026-06-25")),
            ),
        )
    }

    @Test
    fun 記憶がある道を切り口と発話履歴のキーにする() = runTest {
        val fixture = Fixture()
        monthOldWay(fixture)

        val context = useCase(fixture)(review())

        assertEquals(WalkMemoryDepth.MONTH_AGO, context.memory?.depth)
        assertEquals(35, context.memory?.daysAgo)
        assertEquals("way:1", context.placeRef)
        assertEquals(RemarkAngle.MEMORY, context.angle, "記憶は日常の材料より優先")
    }

    @Test
    fun 発見があった散歩は種を発話履歴のキーにする() = runTest {
        val fixture = Fixture()
        monthOldWay(fixture)

        val context = useCase(fixture)(review(discovered = true))

        assertEquals("species:water", context.placeRef)
        assertEquals(RemarkAngle.DISCOVERY, context.angle)
    }

    @Test
    fun 材料が無ければ日単位のキーになる() = runTest {
        val fixture = Fixture()

        val context = useCase(fixture)(review())

        assertEquals("day", context.placeRef)
        assertNull(context.memory)
        assertNull(context.stepsMention)
    }

    @Test
    fun 生成側と読み側で同じ材料になる() = runTest {
        // これが崩れると、生成済みの一言が毎回「未生成」に見えて作り直される（＝毎回課金）
        val fixture = Fixture()
        monthOldWay(fixture)
        fixture.stepImports = FakeStepImportRepository(
            listOf(StepImport(CalendarDay("2026-07-29"), steps = 8_200, distanceEstimateMeters = null)),
        )
        val review = review(grown = true)

        val generateSide = useCase(fixture)(review)
        val observeSide = useCase(fixture)(review)

        assertEquals(generateSide, observeSide)
        assertEquals(
            WalkReviewRemarkPromptBuilder.promptHash(
                facts = WalkReviewRemarkFacts.of(review, generateSide),
                maxTokens = 400,
            ),
            WalkReviewRemarkPromptBuilder.promptHash(
                facts = WalkReviewRemarkFacts.of(review, observeSide),
                maxTokens = 400,
            ),
        )
    }

    @Test
    fun 発話履歴の除外集合は自分より前だけ() = runTest {
        val fixture = Fixture()
        monthOldWay(fixture)
        fixture.utterances = FakeUtteranceLogRepository(
            listOf(
                // 過去の散歩で way:1 について記憶を語った（＝避ける）
                UtteranceRecord(1L, "way:1", RemarkAngle.MEMORY, saidAtMs = startedAtMs - 86_400_000L),
                // この散歩自身の行（生成済み。数に入れてはいけない）
                UtteranceRecord(sessionId, "way:1", RemarkAngle.GROWTH, saidAtMs = startedAtMs),
                // これから先の散歩の行（過去のログを開いたときに存在しうる）
                UtteranceRecord(11L, "way:1", RemarkAngle.TIME_OF_DAY, saidAtMs = startedAtMs + 86_400_000L),
            ),
        )

        val context = useCase(fixture)(review(grown = true))

        assertEquals(
            RemarkAngle.GROWTH,
            context.angle,
            "避けるのは過去のMEMORYだけ（自分自身のGROWTHと未来のTIME_OF_DAYは数えない）",
        )
    }

    @Test
    fun 歩数だけ残っている日に言及する() = runTest {
        val fixture = Fixture()
        fixture.stepImports = FakeStepImportRepository(
            listOf(StepImport(CalendarDay("2026-07-29"), steps = 8_200, distanceEstimateMeters = null)),
        )

        val context = useCase(fixture)(review())

        assertEquals(StepsMention(StepsAmount.GOOD, daysAgo = 1), context.stepsMention)
        assertEquals(RemarkAngle.STEPS, context.angle)
    }

    @Test
    fun セッションがある日は押し忘れではない() = runTest {
        val fixture = Fixture()
        fixture.stepImports = FakeStepImportRepository(
            listOf(StepImport(CalendarDay("2026-07-29"), steps = 8_200, distanceEstimateMeters = null)),
        )
        // 前日は散歩の記録がある（開始を押している）
        val previous = fixture.sessions.startSession(noonUtcMs("2026-07-29"))
        fixture.sessions.endSession(previous, noonUtcMs("2026-07-29") + 60_000L, SessionEndReason.MANUAL)

        val context = useCase(fixture)(review())

        assertNull(context.stepsMention, "歩数を持ち出すのは記録が無い日だけ")
    }

    @Test
    fun 当日の歩数には言及しない() = runTest {
        val fixture = Fixture()
        fixture.stepImports = FakeStepImportRepository(
            listOf(StepImport(CalendarDay("2026-07-30"), steps = 9_000, distanceEstimateMeters = null)),
        )

        assertNull(useCase(fixture)(review()).stepsMention, "その散歩の日ぶんは押し忘れではない")
    }

    @Test
    fun 古すぎる日と少なすぎる歩数には言及しない() = runTest {
        val fixture = Fixture()
        fixture.stepImports = FakeStepImportRepository(
            listOf(
                // 範囲外（既定は2日前まで）
                StepImport(CalendarDay("2026-07-20"), steps = 12_000, distanceEstimateMeters = null),
                // 買い物ぶんの歩数（既定は2000歩から）
                StepImport(CalendarDay("2026-07-29"), steps = 300, distanceEstimateMeters = null),
            ),
        )

        assertNull(useCase(fixture)(review()).stepsMention)
    }

    @Test
    fun 押し忘れの日が複数あれば近い日を採る() = runTest {
        val fixture = Fixture()
        fixture.stepImports = FakeStepImportRepository(
            listOf(
                StepImport(CalendarDay("2026-07-28"), steps = 12_000, distanceEstimateMeters = null),
                StepImport(CalendarDay("2026-07-29"), steps = 3_000, distanceEstimateMeters = null),
            ),
        )

        val mention = useCase(fixture)(review()).stepsMention

        assertEquals(StepsMention(StepsAmount.SOME, daysAgo = 1), mention)
    }

    @Test
    fun 歩数の数値はプロンプトに出ない() = runTest {
        val fixture = Fixture()
        fixture.stepImports = FakeStepImportRepository(
            listOf(StepImport(CalendarDay("2026-07-29"), steps = 8_231, distanceEstimateMeters = 6_100.0)),
        )
        val review = review()

        val prompt = WalkReviewRemarkPromptBuilder.userPrompt(
            WalkReviewRemarkFacts.of(review, useCase(fixture)(review)),
        )

        assertTrue(prompt.contains("けっこう歩いた"), prompt)
        listOf("8231", "8,231", "6100").forEach { forbidden ->
            assertTrue(!prompt.contains(forbidden), "$forbidden が乗っている: $prompt")
        }
    }

    @Test
    fun ブランクの日数はどこにも入らない() = runTest {
        // 「前回から何日空いたか」は構造的に持たない（GetWalkRemarkContextUseCase のKDoc）
        val fixture = Fixture()
        monthOldWay(fixture)

        val context = useCase(fixture)(review())
        val prompt = WalkReviewRemarkPromptBuilder.userPrompt(
            WalkReviewRemarkFacts.of(review(), context),
        )

        listOf("空い", "ぶり", "間隔", "前回").forEach { forbidden ->
            assertTrue(!prompt.contains(forbidden), "$forbidden が乗っている: $prompt")
        }
    }
}
