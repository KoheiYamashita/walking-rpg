package com.walkingrpg.shared.domain.review

import com.walkingrpg.shared.domain.codex.CodexConfig
import com.walkingrpg.shared.domain.codex.CodexDiff
import com.walkingrpg.shared.domain.codex.CodexProgressCalculator
import com.walkingrpg.shared.domain.codex.GetCodexUseCase
import com.walkingrpg.shared.domain.codex.PoiWayIndex
import com.walkingrpg.shared.domain.codex.Species
import com.walkingrpg.shared.domain.codex.SpeciesCatalog
import com.walkingrpg.shared.domain.growth.GrowthConfig
import com.walkingrpg.shared.domain.growth.GrowthDiff
import com.walkingrpg.shared.domain.growth.WayGrowthCalculator
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.matching.PassageRepository
import com.walkingrpg.shared.domain.osm.OsmMasterRepository
import com.walkingrpg.shared.domain.osm.Way
import com.walkingrpg.shared.domain.walk.WalkSessionRepository
import com.walkingrpg.shared.domain.weather.SessionWeatherRepository

/**
 * 1回の散歩の振り返りを組み立てる（**通信しない**）。
 *
 * ```
 * walk_session（時間）
 *   + location_sample（歩いた距離）
 *   + way_growth の引き直し2回（今日育った道）
 *   + codex_progress の引き直し2回（今日の発見・予兆）
 *   + session_weather（あれば1行）
 * ```
 *
 * 材料は全部ローカルなので、圏外でも振り返りは開く
 * （architecture.md §5「数値は即時、文章は遅延OK」の「即時」側そのもの）。
 * パートナーの一言はここでは扱わない：生成の有無で数値サマリが遅れてはいけないので、
 * 文章は別の口（`ObserveWalkReviewRemarkUseCase`）から後から差し込む。
 *
 * ## 導出テーブルを読まずに引き直す
 * `way_growth` / `codex_progress` の**現在値**は「全セッションぶん」なので、
 * そこからは「今日ぶん」が分からない。かわりに `passage` を材料にして
 * 「全部」と「このセッションを除いた全部」の2通りを同じ純関数に通し、差分を取る
 * （[WalkReviewCalculator] のKDoc）。副作用が無いので、同じセッションの振り返りを
 * 何度開いても同じ内容が出る（`RecentGrowthRepository` の余韻とは別物＝
 * あちらは次の散歩で消えるが、振り返りは過去のセッションでも同じものが出る）。
 *
 * 計算量はPOI数百件 × way約220本（design.md §9）の純計算を2回なので、
 * 帰宅直後に開いて待たされる量ではない（`RecomputeCodexProgressUseCase` と同じ根拠）。
 */
class GetWalkReviewUseCase(
    private val walkSessionRepository: WalkSessionRepository,
    private val passageRepository: PassageRepository,
    private val osmMasterRepository: OsmMasterRepository,
    private val sessionWeatherRepository: SessionWeatherRepository,
    private val getCodex: GetCodexUseCase,
    private val growthConfig: GrowthConfig = GrowthConfig.DEFAULT,
    private val codexConfig: CodexConfig = CodexConfig.DEFAULT,
    private val walkDistanceConfig: WalkDistanceConfig = WalkDistanceConfig.DEFAULT,
    /** 対象の種。差し替えられるのはテストのためで、本番は手書きのカタログ全部。 */
    private val catalog: List<Species> = SpeciesCatalog.ALL,
) {
    /**
     * @return その振り返り。**セッションが存在しなければ `null`**
     *  （エクスポートしたDBを入れ替えた後などに古いIDで開かれうる）。
     *  記録中のセッションでも組める（[WalkReview.endedAtMs] が `null` になるだけ）。
     */
    suspend operator fun invoke(sessionId: Long): WalkReview? {
        val session = walkSessionRepository.session(sessionId) ?: return null

        val sessionPassages = passageRepository.passages(sessionId)
        val ways = osmMasterRepository.ways()

        val grownWays = grownWays(sessionPassages, ways)
        val (discovered, approached) = codexHighlights(sessionId)
        val codex = getCodex()

        return WalkReview(
            sessionId = session.id,
            startedAtMs = session.startedAtMs,
            endedAtMs = session.endedAtMs,
            // 記録中の経過を出す口はここには要らない（振り返りは畳んだあとに開くもの）。
            // 開始直後に開かれても0にはならないよう、終了時刻が無ければ開始時刻を使う。
            durationMs = session.durationMs(nowMs = session.endedAtMs ?: session.startedAtMs),
            // 距離は軌跡から出す（way長の合算ではない＝WalkDistanceCalculator のKDoc）。
            distanceMeters = WalkDistanceCalculator.distanceMeters(
                samples = walkSessionRepository.samples(sessionId),
                config = walkDistanceConfig,
            ),
            weather = sessionWeatherRepository.weather(sessionId),
            grownWays = grownWays,
            discoveredSpecies = discovered,
            approachedSpecies = approached,
            codexDiscoveredCount = codex.discoveredCount,
            codexTotalCount = codex.totalCount,
        )
    }

    /** 「このセッションの通過があったから上がった」道だけを、名前を添えて返す。 */
    private suspend fun grownWays(
        sessionPassages: List<Passage>,
        ways: List<Way>,
    ): List<ReviewGrownWay> {
        if (sessionPassages.isEmpty()) return emptyList()

        val allPassCounts = passageRepository.passCountsByWay()
        val after = WayGrowthCalculator.growths(allPassCounts, growthConfig)
        val before = WayGrowthCalculator.growths(
            passCountsByWay = WalkReviewCalculator.passCountsExcluding(
                allPassCounts = allPassCounts,
                sessionPassages = sessionPassages,
            ),
            config = growthConfig,
        )

        // before はこのセッションぶんだけを引いた集計なので、差分に残るのは
        // 必ずこのセッションの通過が押し上げた道。念のため通った道に絞るのは、
        // 他のセッションが並行して記録された（＝ありえないが）場合の保険。
        val walkedWayIds = sessionPassages.mapTo(mutableSetOf()) { it.wayId }
        val nameByWay = ways.associate { it.id to it.name }
        return GrowthDiff.stageRaises(before = before, after = after)
            .filter { it.wayId in walkedWayIds }
            .map { raise -> ReviewGrownWay.of(raise, nameByWay[raise.wayId]) }
    }

    /**
     * そのセッションで新しく発見された種と、予兆が立った種。
     *
     * 図鑑側は道と違って「どのPOIの近くを通ったか」を経由するので、
     * 判定は `CodexProgressCalculator` にそのまま任せる（訪問回数の数え方を複製しない）。
     */
    private suspend fun codexHighlights(sessionId: Long): Pair<List<Species>, List<Species>> {
        val allVisits = passageRepository.sessionVisitsByWay()
        if (allVisits.isEmpty()) return emptyList<Species>() to emptyList()

        val index = PoiWayIndex.of(
            pois = osmMasterRepository.pois(),
            ways = osmMasterRepository.ways(),
            config = codexConfig,
        )
        val after = CodexProgressCalculator.progresses(
            index = index,
            sessionVisitsByWay = allVisits,
            config = codexConfig,
            species = catalog,
        )
        val before = CodexProgressCalculator.progresses(
            index = index,
            sessionVisitsByWay = WalkReviewCalculator.sessionVisitsExcluding(allVisits, sessionId),
            config = codexConfig,
            species = catalog,
        )

        val discoveredIds = CodexDiff.newlyDiscoveredSpeciesIds(before = before, after = after)
        val approachedIds = CodexDiff.newlyForeshadowedSpeciesIds(before = before, after = after)
        // 並びはカタログ順（棚ごと・閾値の近い順）に揃える。集合のままだと表示順が揺れる。
        return catalog.filter { it.id in discoveredIds } to catalog.filter { it.id in approachedIds }
    }
}
