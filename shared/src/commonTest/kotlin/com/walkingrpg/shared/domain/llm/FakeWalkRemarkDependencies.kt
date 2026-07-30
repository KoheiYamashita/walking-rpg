package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.steps.CalendarDay
import com.walkingrpg.shared.domain.steps.CalendarDays
import com.walkingrpg.shared.domain.steps.StepImport
import com.walkingrpg.shared.domain.steps.StepImportRepository
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * パートナーの一言（issue #16）の材料まわりのテスト用差し替え。
 *
 * 手書きのフェイクにしてあるのは既存の流儀どおり（`FakeLlmDependencies` のKDoc）。
 * 見たいのは「UseCaseが何をどの順で読み書きするか」だけなので、最小の形にする。
 */

/** `utterance_log` のインメモリ版。置き換えの回数まで見られるようにしてある。 */
internal class FakeUtteranceLogRepository(
    records: List<UtteranceRecord> = emptyList(),
) : UtteranceLogRepository {

    private val rows = records.toMutableList()

    /** `replaceSessionUtterances` の呼び出し回数（定型文の散歩で書かないことの確認用）。 */
    var replaceCount = 0
        private set

    fun all(): List<UtteranceRecord> = rows.toList()

    override suspend fun recentUtterances(
        placeRef: String,
        beforeMs: Long,
        excludeSessionId: Long,
        limit: Int,
    ): List<UtteranceRecord> = rows
        .filter { it.placeRef == placeRef }
        .filter { it.saidAtMs < beforeMs }
        .filter { it.sessionId != excludeSessionId }
        .sortedByDescending { it.saidAtMs }
        .take(limit)

    override suspend fun replaceSessionUtterances(
        sessionId: Long,
        records: List<UtteranceRecord>,
    ) {
        replaceCount++
        rows.removeAll { it.sessionId == sessionId }
        rows += records
    }

    override suspend fun utterances(sessionId: Long): List<UtteranceRecord> =
        rows.filter { it.sessionId == sessionId }
}

/** `step_import` のインメモリ版（日付が主キー＝1日1行）。 */
internal class FakeStepImportRepository(
    imports: List<StepImport> = emptyList(),
) : StepImportRepository {

    private val rows = imports.associateByTo(mutableMapOf()) { it.day }

    override suspend fun upsert(stepImport: StepImport) {
        rows[stepImport.day] = stepImport
    }

    override suspend fun stepImport(day: CalendarDay): StepImport? = rows[day]

    override suspend fun stepImports(): List<StepImport> = rows.values.sortedBy { it.day.iso }
}

/**
 * 暦日の計算だけを行う [CalendarDays]（UTC固定）。
 *
 * 本実装（`SystemCalendarDays`）は端末のタイムゾーンを読むが、テストで見たいのは
 * 「時刻→暦日」「暦日の差」の扱いなので、ずれない固定のゾーンで足りる。
 * [today] を持たせていないのは、一言の材料が**現在時刻を使わない**ことの裏返し：
 * ここが呼ばれたらテストが落ちる（＝指紋が時間で変わる実装を検出する）。
 */
internal class UtcCalendarDays : CalendarDays {

    override fun today(): CalendarDay =
        error("一言の材料に「今日」は使わない（そのセッションの時刻から見た過去だけ）")

    override fun previousDay(day: CalendarDay): CalendarDay =
        LocalDate.parse(day.iso).minus(1, DateTimeUnit.DAY).let { CalendarDay(it.toString()) }

    override fun day(epochMillis: Long): CalendarDay = CalendarDay(
        Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.UTC).date.toString(),
    )

    override fun daysBetween(from: CalendarDay, until: CalendarDay): Int =
        LocalDate.parse(from.iso).daysUntil(LocalDate.parse(until.iso))
}

/** その日のUTC正午（暦日の境界に寄らない時刻）。 */
internal fun noonUtcMs(day: String): Long =
    LocalDate.parse(day).toEpochDays() * 24L * 60L * 60L * 1_000L + 12L * 60L * 60L * 1_000L
