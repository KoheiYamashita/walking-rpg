package com.walkingrpg.shared.domain.feedback

/**
 * 1回の散歩で振動できる残り（design.md §5「1回の散歩で2〜3回だけ」）。
 *
 * [com.walkingrpg.shared.domain.walk.HomeArrivalDetector] と同じ**純粋な状態機械**：
 * 現在時刻も乱数も使わず、イベントの時刻（＝測位サンプルの時刻）だけで判定する。
 * 端末時計を別に読まないので、同じイベント列からは必ず同じ鳴り方になる。
 *
 * 散歩ごとに作り直す（＝残数は持ち越さない）。持ち越すと「昨日3回鳴ったから今日は無音」
 * のような、ユーザーから見て理由の分からない沈黙が生まれる。
 */
data class VibrationBudget(
    private val config: WalkFeedbackConfig = WalkFeedbackConfig.DEFAULT,
    /** これまでに振動した回数。 */
    val vibratedCount: Int = 0,
    /** 最後に振動したイベントの時刻。まだ振動していなければ `null`。 */
    val lastVibratedAtMs: Long? = null,
) {

    /**
     * イベントが1件起きた。振動してよいかを返し、状態を1つ進める。
     *
     * 「判定」と「消費」を1回の呼び出しにまとめてあるのは、2つに分けると
     * 呼び出し側が消費し忘れて上限が効かなくなるから。
     *
     * 超過したイベントは**何も消費しない**（[Decision.budget] は自分自身）。
     * 沈黙したぶんを残数から引くと、上限を超えるほどイベントが出た散歩では
     * そのあと間隔が空いても二度と鳴らなくなる。
     */
    fun eventOccurred(atMs: Long): Decision {
        val hasBudget = vibratedCount < config.maxVibrationsPerWalk
        // 時刻が巻き戻っているサンプル（端末時計の補正）は「間隔が空いていない」と見る。
        // 鳴らし損ねる側に倒すのは沈黙のデザイン（design.md §3）に沿う方向。
        val isSpaced = lastVibratedAtMs?.let { atMs - it >= config.minVibrationIntervalMs } ?: true

        return if (hasBudget && isSpaced) {
            Decision(
                budget = copy(vibratedCount = vibratedCount + 1, lastVibratedAtMs = atMs),
                shouldVibrate = true,
            )
        } else {
            Decision(budget = this, shouldVibrate = false)
        }
    }

    /**
     * @param budget 進めたあとの残り。呼び出し側はこれで自分の持ち物を置き換える。
     * @param shouldVibrate `false` なら**無音でイベントだけ記録**する（issue #12）。
     */
    data class Decision(
        val budget: VibrationBudget,
        val shouldVibrate: Boolean,
    )
}
