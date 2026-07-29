package com.walkingrpg.shared.domain.walk

/**
 * 自動終了（自宅到着の検知）の閾値。**調整する数字は全部ここに集める**（issue #7）。
 *
 * design.md §3「セッションの区切り」の「自宅付近＋移動停止を検知して『おかえり』を出す」を
 * そのまま数字にしたもの。判定は [HomeArrivalDetector] が純関数でやるので、
 * ここを変えればテストの入力を変えるだけで挙動が確かめられる。
 *
 * ## 設計の前提
 *
 * 誤検知の代償が左右で非対称なことに合わせて、**全体に鈍い**側へ振ってある：
 * - 遅れて終わる（帰宅後しばらく記録が続く）→ 家に着いてからの数分が余計に記録されるだけ。
 *   道の成長は同じ場所に留まっても増えない（[com.walkingrpg.shared.domain.matching.MapMatcher]
 *   が同一wayの連続を1通過に畳む）ので、実害はほぼない。
 *   気になるなら既存の停止ボタンで手動終了すればよい。
 * - 早く終わる（歩いている途中で切れる）→ そこから先の散歩が丸ごと記録から消える。
 *   ユーザーは終了に気づいていないので、後から気づいても手遅れ。
 *
 * したがって「終わらない」より「間違って終わる」を強く避ける。
 */
data class HomeArrivalConfig(
    /**
     * 自宅ジオフェンスの半径。この円の内側にいるときだけ「帰宅の可能性あり」と見る。
     *
     * 80m：屋外のGPS誤差は5〜15m、建物際ではさらに荒れる（design.md §9）。
     * 玄関先・マンションの共用部・駐輪場あたりまで含めたいので、誤差の実用上限に
     * 建物まわりの広がりを足したこの程度が下限になる。小さくすると、家に入った直後の
     * 荒れた測位が圏外に落ちて自動終了が効かない（＝押し忘れが残る）。
     * 大きくしても、あとで見る「停止」とのANDなので自宅前を通過しただけでは終わらない。
     *
     * [com.walkingrpg.shared.domain.setup.HomeAnchor.blurRadiusMeters]（100〜300m）は
     * **表示用のぼかし**であって判定には使わない。あれを流用すると、
     * 300m先のコンビニで立ち止まっただけで「帰宅」になってしまう。
     */
    val homeRadiusMeters: Double = 80.0,

    /**
     * 「止まっている」とみなす移動半径。この円から出たら止まり判定をやり直す。
     *
     * 25m：静止していてもGPSは20m前後ドリフトする（特に屋内・建物際）。
     * これより小さいと「止まっている」が永久に成立せず、自動終了が一度も効かない。
     * 大きくしても、自宅ジオフェンスの内側でしか見ないので誤終了には効かない。
     */
    val stillnessRadiusMeters: Double = 25.0,

    /**
     * この時間ずっと [stillnessRadiusMeters] の中に留まったら「移動停止」とみなす。
     *
     * 2分：家に入って玄関で靴を脱いでいる間に成立する程度に短く、
     * 家の前での立ち話や鍵探しで即成立しない程度に長い。
     * 長くしても失うのは「おかえり」通知が出るまでの数分だけ（上のコメントの非対称性）。
     */
    val stillnessDurationMs: Long = 120_000,

    /**
     * セッション開始からこの時間が経つまでは自動終了しない。
     *
     * 3分：[HomeArrivalDetector] は「一度自宅圏から出る」まで判定を武装しないので、
     * 出発直後に終わることは本来ない。これはその武装条件が**誤って**満たされた場合の保険：
     * 出発直後のGPSは数百m飛ぶことがあり（初回測位が基地局由来）、その1点で
     * 「自宅を出た」と誤認したまま実際には玄関にいる、という並びがありうる。
     * 3分未満で終わる散歩は手動終了で足りる。
     */
    val minSessionDurationMs: Long = 180_000,

    /**
     * これより精度（水平誤差）の悪いサンプルは判定に使わない。
     *
     * 25m：[com.walkingrpg.shared.domain.matching.MapMatchingConfig.maxAccuracyMeters] と同じ値。
     * 誤差が自宅ジオフェンスの半径に迫るサンプルは、圏内/圏外をどちらにも反転させる
     * ＝判定材料にならない。捨てるのは**判定側だけ**で、`location_sample` への保存は
     * 無加工の全件のまま（design.md §9／architecture.md §4：生データは何も捨てない）。
     */
    val maxAccuracyMeters: Double = 25.0,
) {
    init {
        require(homeRadiusMeters > 0) { "homeRadiusMeters は正の値" }
        require(stillnessRadiusMeters > 0) { "stillnessRadiusMeters は正の値" }
        require(stillnessDurationMs > 0) { "stillnessDurationMs は正の値" }
        require(minSessionDurationMs >= 0) { "minSessionDurationMs は0以上" }
        require(maxAccuracyMeters > 0) { "maxAccuracyMeters は正の値" }
    }

    companion object {
        /** 既定の閾値。UIから触らせる予定はないので、差し替えはDI（`sharedModule`）で行う。 */
        val DEFAULT: HomeArrivalConfig = HomeArrivalConfig()
    }
}
