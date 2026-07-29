package com.walkingrpg.shared.domain.feedback

/**
 * 歩行中フィードバック（振動）の閾値。**調整する数字は全部ここに集める**（issue #12）。
 *
 * design.md §3「振動1回＝『何かが起きた』の印」「1回の散歩で2〜3回だけ」（§5）を
 * そのまま数字にしたもの。判定は [VibrationBudget] が純関数でやるので、
 * ここを変えればテストの入力を変えるだけで挙動が確かめられる
 * （`GrowthConfig` / `MapMatchingConfig` / `HomeArrivalConfig` と同じ流儀）。
 *
 * ## 沈黙は仕様である
 *
 * 上限を超えたぶんは**振動しないだけ**で、イベント自体は記録され、振り返りには全部出る
 * （[WalkEventBus]）。つまりここで調整しているのは「歩行中に何回邪魔するか」であって、
 * 「何を記録するか」ではない。多く鳴らす方向に倒すのは design.md §3
 * （沈黙のデザイン）と正面衝突するので、迷ったら少ない側に振る。
 */
data class WalkFeedbackConfig(
    /**
     * 1回の散歩で振動する回数の上限。
     *
     * 3回：design.md §5「1回の散歩で2〜3回だけ」の上限そのもの。
     * 標準セッション（30分 ≒ 2km、design.md §3）で2〜3回＝10分に1回くらいの密度。
     */
    val maxVibrationsPerWalk: Int = 3,

    /**
     * 前の振動からこの時間が経つまでは、次のイベントで振動しない。
     *
     * 5分：上限回数だけでは**足りない**ので置いている。初めて歩く区域では
     * 通る道が全部「草」に上がる＝出発から数分で上限3回を使い切って、
     * 残り25分が完全な無音になる。それは「2〜3回だけ反応する」ではなく
     * 「最初だけ反応する」で、散歩の後半に何が起きても印が出ない。
     * 標準セッション30分を上限3回で割るとちょうどこの程度の間隔になる。
     *
     * 間隔を空けたぶん振動が2回や1回で終わることはあるが、design.md §3 の
     * 「2〜3回まで」は上限であって下限ではないので、それでよい。
     */
    val minVibrationIntervalMs: Long = 300_000,
) {
    init {
        require(maxVibrationsPerWalk >= 0) { "maxVibrationsPerWalk は0以上（0＝振動を切る）" }
        require(minVibrationIntervalMs >= 0) { "minVibrationIntervalMs は0以上" }
    }

    companion object {
        /** 既定の閾値。UIから触らせる予定はないので、差し替えはDI（`sharedModule`）で行う。 */
        val DEFAULT: WalkFeedbackConfig = WalkFeedbackConfig()
    }
}
