package com.walkingrpg.shared.domain.review

import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.matching.SessionVisit
import com.walkingrpg.shared.domain.osm.Way

/**
 * 「そのセッションが何をもたらしたか」を確定データから引き直す純関数
 * （architecture.md §4「導出テーブルはすべて passage から再計算できること」）。
 *
 * ## なぜ差分をどこにも保存しないのか
 * 「今日育った道」「今日近づいた種」は、**全通過の集計**と
 * **そのセッションの通過を除いた集計**を比べれば必ず出る。だから
 * `walk_session` に列を足す必要も、歩行中のイベントを永続化する必要もない
 * （`WalkEventBus` のKDoc「永続化しない」）。見込みを保存すると、確定値と
 * 食い違ったときにどちらが本当か決められない表が残る。
 *
 * ここに置いてあるのは「入力を作る」ぶんだけで、判定そのものは既存の純関数
 * （`WayGrowthCalculator` / `CodexProgressCalculator`）を**同じ物差しのまま2回呼ぶ**
 * ことで行う。集計の規則を振り返り用にもう一本書くと、地図・図鑑と食い違う。
 *
 * 現在時刻も乱数も使わないので、何度呼んでも・いつ呼んでも同じ結果になる。
 */
object WalkReviewCalculator {

    /**
     * 「今日の道」＝そのセッションで通ったwayの長さの合計（メートル）。
     *
     * **同じ道を何度通っても1本ぶん**（way IDで重複排除する）。測位サンプル間の
     * 距離を積み上げないのは、GPSの揺れがそのまま距離の水増しになるから。
     * design.md §3 の「今日の道2.3km」はこの数字。
     *
     * マスタに無いway（対象圏を取り直したあとに残った古い通過）は長さが引けないので
     * 落とす＝距離が少し短く出るだけで、次の再取り込みで揃う。
     */
    fun distanceMeters(sessionPassages: List<Passage>, ways: List<Way>): Double {
        if (sessionPassages.isEmpty()) return 0.0
        val lengthByWay = ways.associate { it.id to it.lengthMeters }
        return sessionPassages
            .mapTo(mutableSetOf()) { it.wayId }
            .sumOf { wayId -> lengthByWay[wayId] ?: 0.0 }
    }

    /**
     * 全通過の道ごとの回数から、そのセッションぶんを引く（＝「その散歩が無かった世界」）。
     *
     * `way_growth` は通過ごとに数える（design.md §4.1）ので、引くのは行数そのもの。
     * 0以下になった道は落とす（未踏は行が無いことで表す＝`WayGrowth` のKDoc）。
     *
     * @param allPassCounts `PassageRepository.passCountsByWay()` の結果（全セッション）。
     * @param sessionPassages `PassageRepository.passages(sessionId)` の結果。
     */
    fun passCountsExcluding(
        allPassCounts: Map<Long, Int>,
        sessionPassages: List<Passage>,
    ): Map<Long, Int> {
        if (sessionPassages.isEmpty()) return allPassCounts
        val sessionCounts = sessionPassages.groupingBy { it.wayId }.eachCount()
        return allPassCounts
            .mapValues { (wayId, count) -> count - sessionCounts.getOrElse(wayId) { 0 } }
            .filterValues { it >= 1 }
    }

    /**
     * 全通過の「道 → その道を通った散歩」から、そのセッションを除く。
     *
     * 図鑑は散歩の回数で数える（design.md §4.4）ので、除くのは**そのセッションの1件**だけ。
     * 除いた結果1件も残らない道は落とす（`PassageRepository.sessionVisitsByWay` の
     * 「通過が1件も無い道は現れない」に合わせる）。
     *
     * @param allSessionVisits `PassageRepository.sessionVisitsByWay()` の結果（全セッション）。
     */
    fun sessionVisitsExcluding(
        allSessionVisits: Map<Long, List<SessionVisit>>,
        sessionId: Long,
    ): Map<Long, List<SessionVisit>> = allSessionVisits
        .mapValues { (_, visits) -> visits.filterNot { it.sessionId == sessionId } }
        .filterValues { it.isNotEmpty() }
}
