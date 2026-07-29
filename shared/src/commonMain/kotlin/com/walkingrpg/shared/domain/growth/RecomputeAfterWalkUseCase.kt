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
 * 呼び出し口はセッションの終了イベント（`ObserveFinishedWalksUseCase`）で、
 * 結線しているのは `AppViewModel`（issue #10）。手動停止・自動終了・測位エラー・
 * クラッシュ後の放置セッションのどれでも `WalkRecorder` が同じイベントを出すので、
 * ここは畳まれ方を区別しない。
 *
 * **冪等ではあるが、副作用がひとつある**：[RecentGrowthRepository] への記録
 * （地図の「今回育った道」の強調）。同じセッションで2回流すと、2回目の差分は
 * 空になって強調が消える。強調は記録ではなく余韻なので、それで構わない。
 */
class RecomputeAfterWalkUseCase(
    private val recomputePassages: RecomputePassagesUseCase,
    private val recomputeWayGrowth: RecomputeWayGrowthUseCase,
    private val wayGrowthRepository: WayGrowthRepository,
    private val recentGrowthRepository: RecentGrowthRepository,
) {
    /**
     * @return そのセッションの通過と、作り直した全ての道の成長。
     */
    suspend operator fun invoke(sessionId: Long): Result {
        // 「段階が上がった道」は作り直しの前後を比べて出す（GrowthDiff のKDoc）ので、
        // 上書きされる前にいまの成長を控えておく。
        val before = wayGrowthRepository.growths()

        val passages = recomputePassages(sessionId)
        val growths = recomputeWayGrowth()

        val stageRaisedWayIds = GrowthDiff.stageRaisedWayIds(before = before, after = growths)
        // 0件でも記録する：前回の散歩の強調をここで消す。
        recentGrowthRepository.record(stageRaisedWayIds)

        return Result(
            passages = passages,
            growths = growths,
            stageRaisedWayIds = stageRaisedWayIds,
        )
    }

    /**
     * @param growths そのセッションぶんではなく**全ての道**（成長は全件の作り直しなので）。
     * @param stageRaisedWayIds この再計算で段階が上がった道。地図の強調と、
     *  帰宅後サマリ（「今日は3本の道が育った」）の両方がこれ1つを見る。
     */
    data class Result(
        val passages: List<Passage>,
        val growths: List<WayGrowth>,
        val stageRaisedWayIds: Set<Long> = emptySet(),
    )
}
