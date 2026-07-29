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
    fun stageRaisedWayIds(before: List<WayGrowth>, after: List<WayGrowth>): Set<Long> {
        val stageBefore = before.associate { it.wayId to it.stage }
        return after
            .filter { growth ->
                val previous = stageBefore[growth.wayId]
                previous == null || previous < growth.stage
            }
            .mapTo(mutableSetOf()) { it.wayId }
    }
}
