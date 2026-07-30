package com.walkingrpg.shared.domain.steps

import com.walkingrpg.shared.domain.snapshot.CalendarMonth
import com.walkingrpg.shared.domain.snapshot.MonthRange
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 押し忘れ救済の取り込み（issue #7）。
 *
 * 見たいのは「起動のたびに走らせても壊れない」こと：
 * 何度取り込んでも1日1行に収束し（冪等性。architecture.md §7）、
 * 歩数計から取れなかった日は既にある行を壊さない。
 */
class ImportDailyStepsUseCaseTest {

    private val today = CalendarDay("2026-03-02")
    private val yesterday = CalendarDay("2026-03-01")

    /** 指定した日だけ値を返す歩数計。問い合わせ回数も数える。 */
    private class FakeDailyStepsSource(
        private val values: Map<CalendarDay, DailySteps>,
    ) : DailyStepsSource {
        val askedDays = mutableListOf<CalendarDay>()

        override suspend fun dailySteps(day: CalendarDay): DailySteps? {
            askedDays += day
            return values[day]
        }
    }

    /** 日付をキーにしたインメモリの `step_import`。 */
    private class FakeStepImportRepository : StepImportRepository {
        private val rows = mutableMapOf<CalendarDay, StepImport>()

        override suspend fun upsert(stepImport: StepImport) {
            rows[stepImport.day] = stepImport
        }

        override suspend fun stepImport(day: CalendarDay): StepImport? = rows[day]

        override suspend fun stepImports(): List<StepImport> =
            rows.values.sortedBy { it.day.iso }
    }

    /** 暦の計算はドメイン外（`SystemCalendarDays`）。テストでは固定の2日だけ知っていればよい。 */
    private inner class FixedCalendarDays : CalendarDays {
        override fun today(): CalendarDay = today

        override fun previousDay(day: CalendarDay): CalendarDay {
            assertEquals(today, day, "前日を聞かれるのは今日に対してだけ")
            return yesterday
        }

        // 取り込みは「今日と前日」しか見ない（時刻からの変換・日数の差はパートナーの一言側）。
        override fun day(epochMillis: Long): CalendarDay = fail("取り込みは時刻を見ない")

        override fun daysBetween(from: CalendarDay, until: CalendarDay): Int =
            fail("取り込みは日数の差を見ない")

        // 月の計算（issue #17）も取り込みの関心ではない
        override fun monthOf(day: CalendarDay): CalendarMonth = fail("取り込みは月を見ない")

        override fun previousMonth(month: CalendarMonth): CalendarMonth =
            fail("取り込みは月を見ない")

        override fun monthRange(month: CalendarMonth): MonthRange = fail("取り込みは月を見ない")
    }

    private fun daily(day: CalendarDay, steps: Int, distance: Double? = null) =
        DailySteps(day = day, steps = steps, distanceEstimateMeters = distance)

    private fun useCase(
        source: DailyStepsSource,
        repository: StepImportRepository,
    ) = ImportDailyStepsUseCase(
        source = source,
        repository = repository,
        calendarDays = FixedCalendarDays(),
    )

    @Test
    fun 昨日と今日を取り込む() = runTest {
        val source = FakeDailyStepsSource(
            mapOf(
                yesterday to daily(yesterday, steps = 8_200, distance = 6_100.0),
                today to daily(today, steps = 1_300, distance = 950.0),
            ),
        )
        val repository = FakeStepImportRepository()

        val imported = useCase(source, repository).invoke()

        assertEquals(listOf(yesterday, today), source.askedDays, "問い合わせるのは昨日と今日だけ")
        assertEquals(listOf(yesterday, today), imported.map { it.day })
        assertEquals(
            StepImport(day = yesterday, steps = 8_200, distanceEstimateMeters = 6_100.0),
            repository.stepImport(yesterday),
            "#16 が「昨日の歩数」を日付で引ける形で残る",
        )
    }

    @Test
    fun 何度取り込んでも1日1行に収束する() = runTest {
        val source = FakeDailyStepsSource(
            mapOf(
                yesterday to daily(yesterday, steps = 8_200),
                today to daily(today, steps = 1_300),
            ),
        )
        val repository = FakeStepImportRepository()
        val useCase = useCase(source, repository)

        repeat(3) { useCase() }

        assertEquals(listOf(yesterday, today), repository.stepImports().map { it.day })
    }

    @Test
    fun 後から増えた歩数で上書きされる() = runTest {
        val repository = FakeStepImportRepository()
        // 歩数計側の同期が遅れて、同じ日の値が後から増えることがある
        useCase(FakeDailyStepsSource(mapOf(today to daily(today, steps = 1_300))), repository)()
        useCase(FakeDailyStepsSource(mapOf(today to daily(today, steps = 9_800))), repository)()

        assertEquals(1, repository.stepImports().size)
        assertEquals(9_800, repository.stepImport(today)?.steps)
    }

    @Test
    fun 歩数計から取れなかった日は保存しない() = runTest {
        val source = FakeDailyStepsSource(mapOf(today to daily(today, steps = 1_300)))
        val repository = FakeStepImportRepository()

        val imported = useCase(source, repository).invoke()

        assertEquals(listOf(today), imported.map { it.day }, "取れた日だけ返る")
        assertNull(repository.stepImport(yesterday), "取れなかった日の行は作らない")
    }

    @Test
    fun 取れなくなっても前に取り込んだ行は消えない() = runTest {
        val repository = FakeStepImportRepository()
        useCase(FakeDailyStepsSource(mapOf(yesterday to daily(yesterday, steps = 8_200))), repository)()

        // 権限を切られた・Health Connect を消した想定（全部 null）
        val imported = useCase(FakeDailyStepsSource(emptyMap()), repository).invoke()

        assertTrue(imported.isEmpty())
        assertEquals(8_200, repository.stepImport(yesterday)?.steps, "取れないことで事実は消えない")
    }

    @Test
    fun 距離が取れない歩数計でも歩数だけ残る() = runTest {
        val repository = FakeStepImportRepository()
        useCase(FakeDailyStepsSource(mapOf(today to daily(today, steps = 4_000, distance = null))), repository)()

        assertEquals(
            StepImport(day = today, steps = 4_000, distanceEstimateMeters = null),
            repository.stepImport(today),
        )
    }
}
