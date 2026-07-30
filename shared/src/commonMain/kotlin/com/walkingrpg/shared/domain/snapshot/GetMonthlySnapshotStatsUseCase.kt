package com.walkingrpg.shared.domain.snapshot

import com.walkingrpg.shared.domain.codex.CodexProgressRepository
import com.walkingrpg.shared.domain.codex.Species
import com.walkingrpg.shared.domain.codex.SpeciesCatalog
import com.walkingrpg.shared.domain.matching.PassageRepository
import com.walkingrpg.shared.domain.osm.OsmMasterRepository
import com.walkingrpg.shared.domain.steps.CalendarDays
import com.walkingrpg.shared.domain.walk.WalkSessionRepository

/**
 * その月の数値（[MonthlySnapshotStats]）を組み立てるUseCase。**通信しない。**
 *
 * 材料を調達するだけで、数え方は純関数 [MonthlySnapshotStatsCalculator] に閉じている
 * （振り返りの `GetWalkReviewUseCase` × `WalkReviewCalculator` と同じ2段構え）。
 *
 * 月の境界（月初〜翌月初の epoch millis）は暦の知識なので [CalendarDays] に預ける
 * ＝このUseCaseは現在時刻も暦も読まない。
 */
class GetMonthlySnapshotStatsUseCase(
    private val walkSessionRepository: WalkSessionRepository,
    private val passageRepository: PassageRepository,
    private val osmMasterRepository: OsmMasterRepository,
    private val codexProgressRepository: CodexProgressRepository,
    private val calendarDays: CalendarDays,
    /** 対象の種。差し替えられるのはテストのためで、本番は手書きのカタログ全部。 */
    private val catalog: List<Species> = SpeciesCatalog.ALL,
) {
    suspend operator fun invoke(month: CalendarMonth): MonthlySnapshotStats {
        val range = calendarDays.monthRange(month)
        // 絞り込みはSQL側（selectSessionsStartedBetween）。全件を舐めずに当月ぶんだけ引く
        val sessions = walkSessionRepository.sessionsStartedBetween(range.fromMs, range.untilMs)

        return MonthlySnapshotStatsCalculator.stats(
            range = range,
            sessions = sessions,
            passagesBySession = sessions.associate { it.id to passageRepository.passages(it.id) },
            ways = osmMasterRepository.ways(),
            sessionVisitsByWay = passageRepository.sessionVisitsByWay(),
            codexProgresses = codexProgressRepository.progresses(),
            catalog = catalog,
        )
    }
}
