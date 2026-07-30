package com.walkingrpg.shared.domain.codex

import com.walkingrpg.shared.domain.matching.SessionVisit

/**
 * 通過（`passage`）→ 図鑑の進捗（`codex_progress`）の変換（design.md §4.4）。
 *
 * **純関数**で、現在時刻も乱数も使わない：同じ通過列・同じ閾値なら必ず同じ結果になる。
 * `WayGrowthCalculator` と同じ形で、「前の進捗」を入力に取らないのが要
 * ＝二重に足す・足し忘れるという事故が起きえない。導出テーブルを消して
 * [RecomputeCodexProgressUseCase] を流し直せば、いつでも同じ状態が再生する
 * （architecture.md §4「導出テーブルはすべて passage から再計算できること」）。
 *
 * ## 出現は決定的（design.md §4.4）
 * 乱数を使わない。条件を満たせば必ず出る。確率の外れは損失感＝ガチャの再発明で、
 * design.md §2 に抵触する。だからこのファイルには乱数源がそもそも注入されていない。
 *
 * ## 訪問回数の定義
 * ```
 * POI 1件の訪問回数 = そのPOIの近傍way（PoiWayIndex）を1本以上通った散歩の数
 * 棚の訪問回数     = その棚に属するPOIの訪問回数の最大値
 * ```
 *
 * - **散歩単位で重複排除する**：同じ散歩で同じ川沿いを往復しても1回。
 *   design.md §4.4 の「同じ川に**10回通って**」は10回の散歩であって、
 *   1回の散歩での通過数ではない（道の成長＝`way_growth` はそちらを数える）
 * - **棚の中で合算せず最大値を採る**：合算にすると「近所の公園15箇所を1回ずつ」でも
 *   「同じ公園に15回」でも同じ数になり、**通い詰めることの意味が消える**。
 *   design.md §4.4「同じ場所に通うほど近づく → 行動範囲の狭さが有利になる中核メカニクス」
 *   はPOI単位でしか成立しない
 *
 * ## 発見時刻
 * `discovered_at` は「閾値に到達した散歩で、そのPOIの近傍を最初に通った時刻」。
 * 棚の中で複数のPOIが閾値に届いているなら、**いちばん早く届いたPOIの時刻**を採る
 * （順序に依らない＝入力の並びが変わっても同じ値になる）。
 *
 * 端末時計を読まないのは冪等性のため（architecture.md §7）。「再計算した時刻」を入れると、
 * 導出テーブルを捨てて作り直すたびに発見日が今日にずれて、振り返り（design.md §4.5）が嘘になる。
 */
object CodexProgressCalculator {

    /**
     * 進捗を作る。結果は種ID順（保存順で結果が変わらないように）。
     *
     * 訪問回数0の種は結果に現れない（[CodexProgress] のKDoc：0回の種は行を作らない）。
     *
     * @param index POIと道の対応（[PoiWayIndex.of] で組む）。
     * @param sessionVisitsByWay 道 → その道を通った散歩（`PassageRepository.sessionVisitsByWay`）。
     * @param species 対象の種。既定は手書きのカタログ全部。
     */
    fun progresses(
        index: PoiWayIndex,
        sessionVisitsByWay: Map<Long, List<SessionVisit>>,
        config: CodexConfig = CodexConfig.DEFAULT,
        species: List<Species> = SpeciesCatalog.ALL,
    ): List<CodexProgress> {
        val visitTimesByCategory = visitTimestampsByCategory(index, sessionVisitsByWay)

        return species
            .sortedBy { it.id }
            .mapNotNull { one ->
                progress(
                    species = one,
                    poiVisitTimestamps = visitTimesByCategory[one.category].orEmpty(),
                    config = config,
                )
            }
    }

    /**
     * POIごとの訪問回数（POI ID → 回数）。回数0のPOIも入る。
     *
     * 歩行中の予兆判定（[com.walkingrpg.shared.domain.feedback.CodexForeshadowEstimator]）が
     * 出発時点の確定値としてこれを読む。判定の入口を共有しておくことで、
     * 「歩行中の見込み」と「帰宅後の確定」が数え方でずれない。
     */
    fun visitCountsByPoi(
        index: PoiWayIndex,
        sessionVisitsByWay: Map<Long, List<SessionVisit>>,
    ): Map<String, Int> = index.entries.associate { entry ->
        entry.poiId to visitTimestamps(entry, sessionVisitsByWay).size
    }

    /**
     * 棚 → その棚のPOIごとの訪問時刻列（各POIの中は時刻の昇順）。
     *
     * POI単位に分けたまま持つのは、訪問回数が**POI単位の最大値**（上記「訪問回数の定義」）で
     * あるうえに、発見時刻もそのPOIの列から引くため。畳んでしまうとどちらも出せない。
     */
    private fun visitTimestampsByCategory(
        index: PoiWayIndex,
        sessionVisitsByWay: Map<Long, List<SessionVisit>>,
    ): Map<CodexCategory, List<List<Long>>> = index.entriesByCategory
        .mapValues { (_, entries) -> entries.map { visitTimestamps(it, sessionVisitsByWay) } }

    /**
     * POI1件の訪問時刻列（散歩ごとに1つ、昇順）。
     *
     * 同じ散歩で近傍の道を何本通っても1回に畳む（上記「散歩単位で重複排除する」）。
     * 畳んだときの時刻は、その散歩で近傍を最初に通った時刻。
     */
    private fun visitTimestamps(
        entry: PoiWayIndex.Entry,
        sessionVisitsByWay: Map<Long, List<SessionVisit>>,
    ): List<Long> = entry.nearbyWayIds
        .flatMap { wayId -> sessionVisitsByWay[wayId].orEmpty() }
        .groupBy { it.sessionId }
        .map { (_, visits) -> visits.minOf { it.firstTimestampMs } }
        .sorted()

    /**
     * 1種ぶんの進捗。訪問0回なら `null`（行を作らない）。
     *
     * @param poiVisitTimestamps その棚のPOIごとの訪問時刻列。
     */
    private fun progress(
        species: Species,
        poiVisitTimestamps: List<List<Long>>,
        config: CodexConfig,
    ): CodexProgress? {
        val visitCount = poiVisitTimestamps.maxOfOrNull { it.size } ?: 0
        if (visitCount < 1) return null

        // 閾値に届いたPOIのうち、いちばん早く届いたものの時刻（上記「発見時刻」）。
        // 届いたPOIが無ければ未発見。
        val discoveredAtMs = poiVisitTimestamps
            .mapNotNull { timestamps -> timestamps.getOrNull(species.requiredVisitCount - 1) }
            .minOrNull()

        return CodexProgress(
            speciesId = species.id,
            visitCount = visitCount,
            foreshadowStage = when {
                // 出たあとに「もう近い」は出さない
                discoveredAtMs != null -> ForeshadowStage.NONE
                visitCount >= species.requiredVisitCount - config.foreshadowLeadVisits ->
                    ForeshadowStage.NEAR

                else -> ForeshadowStage.NONE
            },
            discoveredAtMs = discoveredAtMs,
        )
    }
}
