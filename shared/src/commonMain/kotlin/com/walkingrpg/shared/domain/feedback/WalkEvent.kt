package com.walkingrpg.shared.domain.feedback

import com.walkingrpg.shared.domain.growth.GrowthStage

/**
 * 歩行中に「何かが起きた」1件（design.md §3「歩行中のフィードバック」）。
 *
 * 出口は2つあって、扱いが違う：
 *
 * - **歩行中**：振動1回だけ（レート制限あり＝[WalkFeedbackConfig]）。画面に出るのは断片1〜2文まで。
 * - **帰宅後**：振り返りで**全部**開く。レート制限で振動しなかったぶんもここには出る。
 *
 * つまりレート制限がかかるのは「振動」だけで、イベントそのものは常に記録される
 * （[WalkEventBus]）。「何が起きたんだろう」を持ち帰らせるのが狙いなので、
 * 歩行中に中身を出し切ってはいけない。
 *
 * ## 種別の増やし方
 *
 * sealed にしてあるのは、**イベント源を後から足せるようにする**ため（issue #12 の完了条件）。
 * M1時点の発生源は成長段階アップだけ（[GrowthStageUp]）で、
 * 図鑑の予兆（#13）は同じ仕組みに実装を1つ足すだけで乗る：
 *
 * 1. ここに `data class` を1つ足す
 * 2. それを [WalkEventBus.publish] に流す判定を書く
 * 3. UI側の断片テキストの `when` に1行足す（網羅チェックがコンパイラから来る）
 *
 * 振動とレート制限の側は種別を知らないので、触らなくてよい。
 *
 * @property sessionId どの散歩で起きたか。振り返り（#15）はここで引く。
 * @property timestampMs 起点になった測位サンプルの時刻。**端末時計ではなくサンプル側の時刻**
 *  を使う（測位の遅延ぶんだけずれるのを避ける。[com.walkingrpg.shared.domain.walk.HomeArrivalDetector]
 *  と同じ考え方）。レート制限の時間間隔もこの時刻で測る。
 */
sealed interface WalkEvent {

    val sessionId: Long
    val timestampMs: Long

    /**
     * 道の成長段階が上がった（design.md §4.2）。M1時点で唯一のイベント源。
     *
     * **これは歩行中の見込み**であって確定値ではない（[LiveGrowthEstimator]）。
     * 確定するのは帰宅後の再計算（`RecomputeAfterWalkUseCase`）で、
     * そちらが真実の源になる。見込みと確定がずれた場合、正しいのは常に後者。
     *
     * @param stage 上がった先の段階（見込み）。
     */
    data class GrowthStageUp(
        override val sessionId: Long,
        override val timestampMs: Long,
        val wayId: Long,
        val stage: GrowthStage,
    ) : WalkEvent
}
