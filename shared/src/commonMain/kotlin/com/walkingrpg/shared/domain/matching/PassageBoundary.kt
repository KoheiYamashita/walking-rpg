package com.walkingrpg.shared.domain.matching

/**
 * 「ここから新しい通過が始まるか」の唯一の判定（design.md §11「成長の入力は通過ごと」）。
 *
 * [MapMatcher.toPassages]（確定）と
 * [com.walkingrpg.shared.domain.feedback.LiveGrowthEstimator]（歩行中の見込み）が
 * **同じ規則**で数えるために切り出してある。ここを写して持たせると、
 * 閾値を変えたときに歩行中の振動と帰宅後の振り返りで回数が食い違う。
 *
 * 規則は3つだけ：
 * 1. その道に**はじめて**乗ったなら新しい通過
 * 2. 同じ道でも [MapMatchingConfig.maxPassageGapMs] 以上スナップが空いたら新しい通過
 *    （途中で測位が落ちた・遠回りして戻ってきた）
 * 3. 別の道を挟んで戻ってきた場合、[MapMatchingConfig.revisitMergeGapMs] 未満なら
 *    **同じ通過の続き**。交差点で横断する道に数秒スナップしてすぐ戻る形を
 *    2回と数えないため（実散歩で最も多い水増しの形）
 */
internal object PassageBoundary {

    /**
     * @param wayId いまスナップしている道。
     * @param timestampMs そのサンプルの時刻。
     * @param currentWayId 直前にスナップしていた道（無ければ `null`）。
     * @param lastSeenOnWayMs [wayId] に最後にスナップした時刻（はじめてなら `null`）。
     */
    fun isNewPassage(
        wayId: Long,
        timestampMs: Long,
        currentWayId: Long?,
        lastSeenOnWayMs: Long?,
        config: MapMatchingConfig,
    ): Boolean = when {
        lastSeenOnWayMs == null -> true
        timestampMs - lastSeenOnWayMs > config.maxPassageGapMs -> true
        // 乗り続けているあいだは、どれだけ長く歩いても1回の通過
        wayId == currentWayId -> false
        // 別の道を挟んで戻ってきた。往復（＝2回）と交差点の振動（＝1回）はここで分かれる
        else -> timestampMs - lastSeenOnWayMs >= config.revisitMergeGapMs
    }
}
