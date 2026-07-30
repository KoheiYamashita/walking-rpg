package com.walkingrpg.app.ui.review

import com.walkingrpg.shared.domain.codex.CodexProgress
import com.walkingrpg.shared.domain.codex.CodexProgressRepository
import com.walkingrpg.shared.domain.codex.RecentCodexRepository
import com.walkingrpg.shared.domain.llm.LlmCacheEntry
import com.walkingrpg.shared.domain.llm.LlmCacheRepository
import com.walkingrpg.shared.domain.llm.LlmTaskKind
import com.walkingrpg.shared.domain.llm.UtteranceLogRepository
import com.walkingrpg.shared.domain.llm.UtteranceRecord
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.matching.PassageRepository
import com.walkingrpg.shared.domain.matching.SessionVisit
import com.walkingrpg.shared.domain.osm.OsmMasterCounts
import com.walkingrpg.shared.domain.osm.OsmMasterRepository
import com.walkingrpg.shared.domain.osm.Poi
import com.walkingrpg.shared.domain.osm.Way
import com.walkingrpg.shared.domain.snapshot.CalendarMonth
import com.walkingrpg.shared.domain.snapshot.MonthRange
import com.walkingrpg.shared.domain.steps.CalendarDay
import com.walkingrpg.shared.domain.steps.CalendarDays
import com.walkingrpg.shared.domain.steps.StepImport
import com.walkingrpg.shared.domain.steps.StepImportRepository
import com.walkingrpg.shared.domain.walk.LocationSample
import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.walk.WalkSession
import com.walkingrpg.shared.domain.walk.WalkSessionRepository
import com.walkingrpg.shared.domain.walk.WalkSessionSummary
import com.walkingrpg.shared.domain.weather.SessionWeather
import com.walkingrpg.shared.domain.weather.SessionWeatherRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * 振り返り画面のテスト用差し替え。
 *
 * shared 側のフェイク（`FakeDerivationRepositories` 等）は shared のテストソースセットに
 * 閉じているので、UI層のテストには**必要な口だけ**を持つ最小の実装を置く。
 * 振り返りの中身の正しさは shared 側（`GetWalkReviewUseCaseTest`）が見ているので、
 * ここでは「数値が出て、あとから文章と天候が届く」ことだけを作れれば足りる。
 *
 * 保存で購読が流れる形（[MutableStateFlow]）にしてあるのは、実装（SQLDelightの
 * `asFlow()`）が書き込みのたびにクエリを流し直すのと同じ動きにするため。
 */

/** セッション1件だけを持つ [WalkSessionRepository]。 */
internal class SingleSessionRepository(
    private val session: WalkSession?,
    /** そのセッションの軌跡。距離は way長の合算ではなくここから出る（`WalkDistanceCalculator`）。 */
    private val samples: List<LocationSample> = emptyList(),
) : WalkSessionRepository {

    override suspend fun session(sessionId: Long): WalkSession? =
        session?.takeIf { it.id == sessionId }

    override suspend fun startSession(startedAtMs: Long): Long = notUsed()
    override suspend fun appendSample(sample: LocationSample) = notUsed()
    override suspend fun endSession(sessionId: Long, endedAtMs: Long, reason: SessionEndReason) =
        notUsed()

    override suspend fun abandonOpenSessions(): List<Long> = notUsed()
    override fun observeSessions(): Flow<List<WalkSessionSummary>> = emptyFlow()
    override suspend fun samples(sessionId: Long): List<LocationSample> =
        samples.takeIf { session?.id == sessionId }.orEmpty()
    override suspend fun recentFinishedSessions(limit: Int): List<WalkSession> = listOfNotNull(session)

    // 押し忘れの判定（issue #16）が引く口。この1件だけを持っているので範囲で絞って返す
    override suspend fun sessionsStartedBetween(fromMs: Long, untilMs: Long): List<WalkSession> =
        listOfNotNull(session).filter { it.startedAtMs >= fromMs && it.startedAtMs < untilMs }

    override suspend fun oldestSessionStartedAtMs(): Long? = session?.startedAtMs

    private fun notUsed(): Nothing = error("振り返り画面では使わない")
}

/** 歩数の取り込みが1件も無い `step_import`（振り返り画面では材料にしない）。 */
internal class EmptyStepImportRepository : StepImportRepository {
    override suspend fun upsert(stepImport: StepImport) = Unit
    override suspend fun stepImport(day: CalendarDay): StepImport? = null
    override suspend fun stepImports(): List<StepImport> = emptyList()
}

/** 発話履歴のインメモリ版（issue #16）。 */
internal class FakeUtteranceLogRepository : UtteranceLogRepository {

    private val rows = mutableListOf<UtteranceRecord>()

    override suspend fun recentUtterances(
        placeRef: String,
        beforeMs: Long,
        excludeSessionId: Long,
        limit: Int,
    ): List<UtteranceRecord> = rows
        .filter { it.placeRef == placeRef && it.saidAtMs < beforeMs && it.sessionId != excludeSessionId }
        .sortedByDescending { it.saidAtMs }
        .take(limit)

    override suspend fun replaceSessionUtterances(
        sessionId: Long,
        records: List<UtteranceRecord>,
    ) {
        rows.removeAll { it.sessionId == sessionId }
        rows += records
    }

    override suspend fun utterances(sessionId: Long): List<UtteranceRecord> =
        rows.filter { it.sessionId == sessionId }
}

/**
 * 暦日の計算（UTC固定）。
 *
 * 画面のテストで見たいのは差し込みの順序なので、ずれない固定のゾーンで足りる。
 * `today()` を持たせていないのは、一言の材料が現在時刻を使わないことの裏返し
 * （`GetWalkRemarkContextUseCase` のKDoc）。
 */
internal class UtcCalendarDays : CalendarDays {

    override fun today(): CalendarDay = error("一言の材料に「今日」は使わない")

    override fun previousDay(day: CalendarDay): CalendarDay =
        LocalDate.parse(day.iso).minus(1, DateTimeUnit.DAY).let { CalendarDay(it.toString()) }

    override fun day(epochMillis: Long): CalendarDay = CalendarDay(
        Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.UTC).date.toString(),
    )

    override fun daysBetween(from: CalendarDay, until: CalendarDay): Int =
        LocalDate.parse(from.iso).daysUntil(LocalDate.parse(until.iso))

    override fun monthOf(day: CalendarDay): CalendarMonth = CalendarMonth(day.iso.take(7))

    override fun previousMonth(month: CalendarMonth): CalendarMonth =
        LocalDate.parse("${'$'}{month.iso}-01")
            .minus(1, DateTimeUnit.MONTH)
            .let { CalendarMonth(it.toString().take(7)) }

    override fun monthRange(month: CalendarMonth): MonthRange {
        val firstDay = LocalDate.parse("${'$'}{month.iso}-01")
        return MonthRange(
            fromMs = firstDay.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
            untilMs = firstDay.plus(1, DateTimeUnit.MONTH)
                .atStartOfDayIn(TimeZone.UTC)
                .toEpochMilliseconds(),
        )
    }
}

/** 通過を直接置ける [PassageRepository]。 */
internal class StubPassageRepository(
    private val sessionPassages: Map<Long, List<Passage>> = emptyMap(),
    private val passCounts: Map<Long, Int> = emptyMap(),
) : PassageRepository {

    override suspend fun replaceSessionPassages(sessionId: Long, passages: List<Passage>) = Unit
    override suspend fun passages(sessionId: Long): List<Passage> =
        sessionPassages[sessionId].orEmpty()

    override suspend fun passCountsByWay(): Map<Long, Int> = passCounts
    override suspend fun sessionVisitsByWay(): Map<Long, List<SessionVisit>> = emptyMap()
}

/** wayマスタだけを持つ [OsmMasterRepository]。 */
internal class StubOsmMasterRepository(
    private val ways: List<Way> = emptyList(),
) : OsmMasterRepository {

    override suspend fun save(ways: List<Way>, pois: List<Poi>) = Unit
    override suspend fun counts(): OsmMasterCounts = OsmMasterCounts(ways.size, 0)
    override suspend fun ways(): List<Way> = ways
    override suspend fun pois(): List<Poi> = emptyList()
}

/** `llm_cache` のインメモリ版（保存で購読が流れる）。 */
internal class FakeLlmCacheRepository : LlmCacheRepository {

    private val rows = MutableStateFlow<Map<String, LlmCacheEntry>>(emptyMap())

    override suspend fun entry(cacheKey: String): LlmCacheEntry? = rows.value[cacheKey]

    override fun observe(cacheKey: String): Flow<LlmCacheEntry?> = rows.map { it[cacheKey] }

    override suspend fun entries(kind: LlmTaskKind): Map<String, LlmCacheEntry> =
        rows.value.filterValues { it.kind == kind }

    override suspend fun save(entry: LlmCacheEntry) {
        rows.value = rows.value + (entry.cacheKey to entry)
    }
}

/** `session_weather` のインメモリ版。行が無い＝未取得。 */
internal class FakeSessionWeatherRepository : SessionWeatherRepository {

    private val rows = MutableStateFlow<Map<Long, SessionWeather>>(emptyMap())

    override suspend fun save(weather: SessionWeather) {
        rows.value = rows.value + (weather.sessionId to weather)
    }

    override suspend fun weather(sessionId: Long): SessionWeather? = rows.value[sessionId]

    override fun observe(sessionId: Long): Flow<SessionWeather?> = rows.map { it[sessionId] }

    override suspend fun sessionIdsWithoutWeather(): List<Long> = emptyList()
}

/** 進捗が空の [CodexProgressRepository]（図鑑の総数だけがカタログから出る）。 */
internal class EmptyCodexProgressRepository : CodexProgressRepository {
    override suspend fun replaceAllProgresses(progresses: List<CodexProgress>) = Unit
    override suspend fun progresses(): List<CodexProgress> = emptyList()
    override suspend fun progress(speciesId: String): CodexProgress? = null
}

/** 余韻の強調を持たない [RecentCodexRepository]。 */
internal class EmptyRecentCodexRepository : RecentCodexRepository {
    override val discoveredSpeciesIds: Set<String> = emptySet()
    override val updates: Flow<Set<String>> = emptyFlow()
    override fun record(speciesIds: Set<String>) = Unit
}
