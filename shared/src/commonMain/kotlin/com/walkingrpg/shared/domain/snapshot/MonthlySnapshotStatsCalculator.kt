package com.walkingrpg.shared.domain.snapshot

import com.walkingrpg.shared.domain.codex.CodexProgress
import com.walkingrpg.shared.domain.codex.Species
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.matching.SessionVisit
import com.walkingrpg.shared.domain.osm.Way
import com.walkingrpg.shared.domain.review.WalkReviewCalculator
import com.walkingrpg.shared.domain.walk.WalkSession

/**
 * その月の数値を確定データから引き直す純関数（architecture.md §4「passage から再計算」）。
 *
 * 現在時刻も乱数も使わないので、何度呼んでも・いつ呼んでも同じ結果になる。
 * 焼き込む先（`snapshot.stats_json`）は作り直さないが、**焼く値は再計算できる**
 * ＝月をまたいだ直後に作った1枚と、あとから穴埋めで作った1枚の数値が食い違わない。
 *
 * ## 物差しは既存のものを使い回す
 * 距離は振り返り（design.md §4.5）と同じ [WalkReviewCalculator.distanceMeters] を
 * セッションごとに呼んで足す。月の合計を別の式（測位サンプル間の積み上げ等）で出すと、
 * 振り返りを12回足した数と年の合計が合わなくなる。
 *
 * ## セッションの月の帰属は `started_at`
 * 月末23:50に出て翌月0:10に帰った散歩は**出た月**に入る。「その散歩をどの月の記憶として
 * 覚えているか」は出発時刻で決まるし、`ended_at` は放置セッションで
 * 最後の測位まで巻き戻る（`WalkSessionRepository.abandonOpenSessions`）ので、
 * 月の境界に使うと同じ散歩が集計のたびに月をまたぎうる。
 */
object MonthlySnapshotStatsCalculator {

    /**
     * @param range その月の epoch millis 範囲（`CalendarDays.monthRange`）。
     * @param sessions 全セッション（この関数が [range] で絞る）。
     * @param passagesBySession セッション → そのセッションの通過。当月のセッションぶんだけあればよい。
     * @param ways way マスタ（距離を引くのに使う）。
     * @param sessionVisitsByWay 道 → その道を通った散歩（`PassageRepository.sessionVisitsByWay`）。
     * @param codexProgresses 図鑑の進捗（`codex_progress`）。発見時刻が当月のものを数える。
     * @param catalog 種カタログ。発見した種名の**並びはこの順**。
     */
    fun stats(
        range: MonthRange,
        sessions: List<WalkSession>,
        passagesBySession: Map<Long, List<Passage>>,
        ways: List<Way>,
        sessionVisitsByWay: Map<Long, List<SessionVisit>>,
        codexProgresses: List<CodexProgress>,
        catalog: List<Species>,
    ): MonthlySnapshotStats {
        val monthSessions = sessions.filter { it.startedAtMs in range }

        return MonthlySnapshotStats(
            distanceMeters = monthSessions.sumOf { session ->
                WalkReviewCalculator.distanceMeters(
                    sessionPassages = passagesBySession[session.id].orEmpty(),
                    ways = ways,
                )
            },
            newWayCount = newWayCount(range, sessionVisitsByWay),
            discoveredSpeciesNames = discoveredSpeciesNames(range, codexProgresses, catalog),
            sessionCount = monthSessions.size,
        )
    }

    /**
     * その月に**はじめて通った**道の本数。
     *
     * 「初回通過が当月に入る道」だけを数える＝先月から通っている道は当月には数えない。
     * 判定に使うのは道ごとの最初の訪問時刻（`SessionVisit.firstTimestampMs` の最小値）で、
     * これは図鑑の発見時刻と同じ材料＝新しいクエリを足さずに済む。
     *
     * `way_growth` の「初回踏破で GRASS になった」を数える手もあるが採らない：
     * あちらは閾値（`GrowthConfig`）を触ると意味が変わるのに対し、
     * 「はじめて通った」は歩行ログだけで決まる事実で、あとから定義がぶれない。
     */
    private fun newWayCount(
        range: MonthRange,
        sessionVisitsByWay: Map<Long, List<SessionVisit>>,
    ): Int = sessionVisitsByWay.count { (_, visits) ->
        val firstVisitMs = visits.minOfOrNull { it.firstTimestampMs } ?: return@count false
        firstVisitMs in range
    }

    /**
     * その月に発見した種の名前（カタログ順）。
     *
     * `codex_progress.discovered_at` は端末時計ではなく `passage.ts` 由来
     * （`CodexProgressCalculator`）なので、そのまま月の範囲と突き合わせられる。
     * 導出テーブルを読んでいるが、値の根拠は `passage` 側にあるので
     * 「passage から再計算できる」原則は破れていない。
     *
     * カタログに無いID（種を消したあとに残った古い行）は名前が引けないので落ちる。
     */
    private fun discoveredSpeciesNames(
        range: MonthRange,
        codexProgresses: List<CodexProgress>,
        catalog: List<Species>,
    ): List<String> {
        val discoveredIds = codexProgresses
            .filter { progress -> progress.discoveredAtMs?.let { it in range } == true }
            .mapTo(mutableSetOf()) { it.speciesId }
        if (discoveredIds.isEmpty()) return emptyList()

        return catalog.filter { it.id in discoveredIds }.map { it.name }
    }
}
