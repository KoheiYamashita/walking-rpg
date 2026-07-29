package com.walkingrpg.shared.domain.growth

import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.matching.RecomputePassagesUseCase

/**
 * 散歩1回ぶんの導出をまとめて作り直す（architecture.md §5「帰宅後」）。
 *
 * ```
 * セッション終了 → map matching（passage の作り直し） → 成長の再計算（way_growth）
 * ```
 *
 * 順序に意味がある：`way_growth` は `passage` の集計なので、`passage` を確定させる前に
 * 集計しても古い結果しか出ない。逆に言うと、この2つを別々に呼んでも（順に呼びさえすれば）
 * 結果は同じ＝どちらも冪等なので、途中でアプリが落ちても次にもう一度流せば揃う。
 *
 * 成長側だけ全件の作り直しになるのは [RecomputeWayGrowthUseCase] の理由による。
 *
 * UI（散歩終了時の呼び出し・帰宅後サマリの表示）への結線は #10 で行う。
 */
class RecomputeAfterWalkUseCase(
    private val recomputePassages: RecomputePassagesUseCase,
    private val recomputeWayGrowth: RecomputeWayGrowthUseCase,
) {
    /**
     * @return そのセッションの通過と、作り直した全ての道の成長。
     */
    suspend operator fun invoke(sessionId: Long): Result {
        val passages = recomputePassages(sessionId)
        val growths = recomputeWayGrowth()
        return Result(passages = passages, growths = growths)
    }

    /** @param growths そのセッションぶんではなく**全ての道**（成長は全件の作り直しなので）。 */
    data class Result(
        val passages: List<Passage>,
        val growths: List<WayGrowth>,
    )
}
