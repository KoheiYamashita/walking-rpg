package com.walkingrpg.shared.domain.growth

/**
 * 再計算の前後を突き合わせて「段階が上がった道」を求める（純関数）。
 *
 * `way_growth` は毎回全件を作り直す（[RecomputeWayGrowthUseCase]）ので、
 * 「今回の散歩で何が育ったか」はテーブルの中には残らない。作り直しの前後を
 * 比べるのがいちばん安いやり方で、これなら差分用の列もテーブルも増えない。
 *
 * 段階は上がるだけ（減衰なし・design.md §4.2）なので、下がる向きは考えない。
 */
object GrowthDiff {

    /**
     * [before] から [after] で段階が上がった道のID。
     *
     * [before] に居なかった道（＝今回はじめて通った道）も「上がった」に含める。
     * 未踏は行が無いことで表す（[WayGrowth] のKDoc）ので、行が生えたこと自体が
     * 「何も無い → 草」という1段の変化だから。
     */
    fun stageRaisedWayIds(before: List<WayGrowth>, after: List<WayGrowth>): Set<Long> =
        stageRaises(before, after).mapTo(mutableSetOf()) { it.wayId }

    /**
     * 段階が上がった道を**前後の段階ごと**に返す（way ID順）。
     *
     * [stageRaisedWayIds] が「どの道が育ったか」だけなのに対し、こちらは
     * 「低木 → 木」まで持つ。振り返り（design.md §3 の 17:08「いつもの道が1段階育った
     * （低木→木）」）が要求する情報がこれで、地図の強調には要らない
     * ＝ID集合の側は残したまま、こちらから導いている（判定の規則を2箇所に書かない）。
     */
    fun stageRaises(before: List<WayGrowth>, after: List<WayGrowth>): List<WayStageRaise> {
        val stageBefore = before.associate { it.wayId to it.stage }
        return after
            .sortedBy { it.wayId }
            .mapNotNull { growth ->
                val previous = stageBefore[growth.wayId]
                if (previous != null && previous >= growth.stage) return@mapNotNull null
                WayStageRaise(wayId = growth.wayId, from = previous, to = growth.stage)
            }
    }
}

/**
 * 1本の道の段階が上がったこと。
 *
 * @param from 上がる前の段階。**未踏だったなら `null`**（未踏は行が無いことで表す＝
 *  [WayGrowth] のKDoc）。呼び出し側は `null` を「何も無い → 草」として言い換える。
 * @param to 上がった先の段階。
 */
data class WayStageRaise(
    val wayId: Long,
    val from: GrowthStage?,
    val to: GrowthStage,
)
