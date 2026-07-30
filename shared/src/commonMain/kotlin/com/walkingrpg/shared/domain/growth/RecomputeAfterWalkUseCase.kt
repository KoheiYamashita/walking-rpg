package com.walkingrpg.shared.domain.growth

import com.walkingrpg.shared.domain.codex.CodexDiff
import com.walkingrpg.shared.domain.codex.CodexProgress
import com.walkingrpg.shared.domain.codex.CodexProgressRepository
import com.walkingrpg.shared.domain.codex.RecentCodexRepository
import com.walkingrpg.shared.domain.codex.RecomputeCodexProgressUseCase
import com.walkingrpg.shared.domain.matching.Passage
import com.walkingrpg.shared.domain.matching.RecomputePassagesUseCase

/**
 * 散歩1回ぶんの導出をまとめて作り直す（architecture.md §5「帰宅後」）。
 *
 * ```
 * セッション終了 → map matching（passage の作り直し）
 *                → 成長の再計算（way_growth）
 *                → 図鑑の再計算（codex_progress）
 * ```
 *
 * 順序に意味がある：`way_growth` と `codex_progress` はどちらも `passage` の集計なので、
 * `passage` を確定させる前に集計しても古い結果しか出ない。成長と図鑑の間には依存が無い
 * （図鑑は `way_growth` を読まない＝`passage` から直に引く）ので、この2つの前後は
 * 入れ替えても結果が変わらない。逆に言うと、これらを別々に呼んでも（passage を先にすれば）
 * 結果は同じ＝どれも冪等なので、途中でアプリが落ちても次にもう一度流せば揃う。
 *
 * 成長・図鑑側が全件の作り直しになるのは [RecomputeWayGrowthUseCase] /
 * [RecomputeCodexProgressUseCase] の理由による。
 *
 * 呼び出し口はセッションの終了イベント（`ObserveFinishedWalksUseCase`）で、
 * 結線しているのは `AppViewModel`（issue #10）。手動停止・自動終了・測位エラー・
 * クラッシュ後の放置セッションのどれでも `WalkRecorder` が同じイベントを出すので、
 * ここは畳まれ方を区別しない。
 *
 * **冪等ではあるが、副作用がふたつある**：[RecentGrowthRepository]（地図の「今回育った道」の
 * 強調）と [RecentCodexRepository]（図鑑の「今回出た種」の強調）への記録。
 * 同じセッションで2回流すと、2回目の差分は空になって強調が消える。
 * 強調は記録ではなく余韻なので、それで構わない。
 */
class RecomputeAfterWalkUseCase(
    private val recomputePassages: RecomputePassagesUseCase,
    private val recomputeWayGrowth: RecomputeWayGrowthUseCase,
    private val recomputeCodexProgress: RecomputeCodexProgressUseCase,
    private val wayGrowthRepository: WayGrowthRepository,
    private val codexProgressRepository: CodexProgressRepository,
    private val recentGrowthRepository: RecentGrowthRepository,
    private val recentCodexRepository: RecentCodexRepository,
) {
    /**
     * @return そのセッションの通過と、作り直した全ての道の成長。
     */
    suspend operator fun invoke(sessionId: Long): Result {
        // 「段階が上がった道」「新しく出た種」はどちらも作り直しの前後を比べて出す
        // （GrowthDiff / CodexDiff のKDoc）ので、上書きされる前に今の状態を控えておく。
        val growthsBefore = wayGrowthRepository.growths()
        val codexBefore = codexProgressRepository.progresses()

        val passages = recomputePassages(sessionId)
        val growths = recomputeWayGrowth()
        val codexProgresses = recomputeCodexProgress()

        val stageRaisedWayIds = GrowthDiff.stageRaisedWayIds(before = growthsBefore, after = growths)
        val discoveredSpeciesIds = CodexDiff.newlyDiscoveredSpeciesIds(
            before = codexBefore,
            after = codexProgresses,
        )
        // 0件でも記録する：前回の散歩の強調をここで消す。
        recentGrowthRepository.record(stageRaisedWayIds)
        recentCodexRepository.record(discoveredSpeciesIds)

        return Result(
            passages = passages,
            growths = growths,
            codexProgresses = codexProgresses,
            stageRaisedWayIds = stageRaisedWayIds,
            discoveredSpeciesIds = discoveredSpeciesIds,
        )
    }

    /**
     * @param growths そのセッションぶんではなく**全ての道**（成長は全件の作り直しなので）。
     * @param codexProgresses 訪問が1回以上ある**全ての種**の進捗（図鑑も全件の作り直しなので）。
     * @param stageRaisedWayIds この再計算で段階が上がった道。地図の強調と、
     *  帰宅後サマリ（「今日は3本の道が育った」）の両方がこれ1つを見る。
     * @param discoveredSpeciesIds この再計算で新しく発見された種。図鑑の強調と
     *  帰宅後サマリがこれを見る。
     */
    data class Result(
        val passages: List<Passage>,
        val growths: List<WayGrowth>,
        val codexProgresses: List<CodexProgress> = emptyList(),
        val stageRaisedWayIds: Set<Long> = emptySet(),
        val discoveredSpeciesIds: Set<String> = emptySet(),
    )
}
