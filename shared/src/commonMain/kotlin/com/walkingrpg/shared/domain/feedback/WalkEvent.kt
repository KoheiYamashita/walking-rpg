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
 * 発生源は道の成長段階アップ（[GrowthStageUp]）と図鑑（[CodexForeshadow] / [CodexDiscovered]、
 * issue #13）で、足すのに要ったのは次の3つだけだった：
 *
 * 1. ここに `data class` を1つ足す
 * 2. それを [WalkEventBus.publish] に流す判定を書く
 * 3. UI側の断片テキストの `when` に1行足す（網羅チェックがコンパイラから来る）
 *
 * 振動とレート制限の側は種別を知らないので、触らなくてよい
 * （振動は1種類のまま＝design.md §3「振動1回＝『何かが起きた』の印」）。
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

    /**
     * 図鑑の出現が近づいた（design.md §4.4「予兆」）。
     *
     * **これも歩行中の見込み**（[CodexForeshadowEstimator]）。確定するのは帰宅後の
     * `RecomputeCodexProgressUseCase` で、そちらが真実の源になる。
     *
     * @param poiId 予兆が出た地点。歩行中の画面には出さない（design.md §3
     *  「中身は帰宅後の振り返りで開く」）が、振り返り（#16）が「どこで何があったか」を
     *  語るのに要る。
     * @param speciesId どの種に近づいたか。**歩行中の画面には絶対に出さない**：
     *  種名が出た時点で予兆ではなく予告になり、残り回数を数字で見せるのと同じことになる。
     */
    data class CodexForeshadow(
        override val sessionId: Long,
        override val timestampMs: Long,
        val poiId: String,
        val speciesId: String,
    ) : WalkEvent

    /**
     * 図鑑の1種が出現した（design.md §4.4「条件を満たせば必ず出る」）。
     *
     * 歩行中はこれも「振動1回」で、何が出たかは帰宅後に図鑑で開く
     * ＝「何が起きたんだろう」を持ち帰らせる（design.md §3）。
     *
     * @param poiId 出現した地点。
     * @param speciesId 出た種。歩行中の画面には出さない（[CodexForeshadow] と同じ理由）。
     */
    data class CodexDiscovered(
        override val sessionId: Long,
        override val timestampMs: Long,
        val poiId: String,
        val speciesId: String,
    ) : WalkEvent
}
