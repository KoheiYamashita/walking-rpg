package com.walkingrpg.shared.domain.matching

/**
 * map matching の閾値。**調整する数字は全部ここに集める**。
 *
 * `passage` は `location_sample` から何度でも作り直せる（Passage.sq）ので、
 * ここを変えて再計算すれば結果が変わってよい。逆に言うと、生サンプル側では
 * 何も捨てない（issue #7 で無加工保存済み）＝判断を導出側に置くための設定。
 *
 * 既定値の根拠は各プロパティのコメントに書く。実散歩のログで合わなければ、
 * ここだけ触って GPSリプレイテストを回す（`MapMatchingReplayTest`）。
 */
data class MapMatchingConfig(
    /**
     * これより精度（水平誤差）の悪いサンプルは捨てる。
     *
     * 25m：屋外のGPS精度は5〜15m、ビル街ではさらに荒れる（design.md §9）。
     * 誤差が [maxSnapDistanceMeters] を大きく超えるサンプルは「どの道にいたか」の
     * 情報をほとんど持たないので、スナップさせても誤塗りの種にしかならない。
     * 一方で厳しくしすぎると、木陰や建物際で列が細切れになって通過が途切れる。
     */
    val maxAccuracyMeters: Double = 25.0,

    /**
     * これを超える速度で移動している区間は乗り物とみなす候補にする。
     *
     * 3.0 m/s（10.8 km/h）：早歩きが1.4〜2.0 m/s、小走りでも3 m/s前後なので、
     * 継続的に超えるなら自転車・バス・電車。design.md §9 の方針どおり
     * **速度フィルタだけ**で、加速度や路線データを使った厳密判定はしない
     * （開始が手動＋自分用アプリなので、意図的なチートは自己矛盾するだけ）。
     */
    val maxWalkingSpeedMps: Double = 3.0,

    /**
     * 速度超過がこの時間続いたら、その区間を乗り物として除外する。
     *
     * 30秒：GPSは一瞬だけ数十m飛ぶことがあり、1区間の速度超過は誤測位の可能性が高い。
     * 逆に電車・バスは必ず数十秒〜数分続くので、この長さで両者は分かれる。
     * 短すぎると横断歩道での測位飛びだけで歩行区間が落ちる。
     */
    val vehicleMinDurationMs: Long = 30_000,

    /**
     * サンプルからway（線分）までの距離がこれを超えたら、どの道にも乗せない。
     *
     * 20m：GPS誤差の実用上限（15m）＋道幅の余裕。これ以上広げると、
     * 平行する裏道や建物の中まで拾って誤塗りが始まる。歩道橋・私有地の通路など
     * マスタに無い場所を歩いた場合も、ここで素直に「不明」に落ちる。
     */
    val maxSnapDistanceMeters: Double = 20.0,

    /**
     * 同じwayへの連続スナップがこの数に満たない区間はノイズとして扱う。
     *
     * 2件：1件だけ隣の道に飛ぶのはGPS誤差の典型（交差点・並行する道）。
     * 前後が同じwayなら埋め戻し、そうでなければ捨てる（[MapMatcher] 参照）。
     * 測位は1〜2秒間隔なので、実際に通った道なら歩行速度でも必ず複数件のる。
     */
    val minRunSamples: Int = 2,

    /**
     * 同じwayでも、前のスナップからこれ以上間が空いたら別の通過として数える。
     *
     * 60秒：立ち止まり・信号待ちで通過が割れない程度に長く、
     * 「一周して同じ道に戻ってきた」を1回に潰さない程度に短い長さ。
     */
    val maxPassageGapMs: Long = 60_000,

    /**
     * 直前に乗っていたwayへの「粘着」の強さ。新しいwayがこれ以上近くなければ乗り換えない。
     *
     * 5m：並走する2本のfootway（実測の対象圏では歩道と車道側の歩行者通路が
     * 5〜10m間隔で並ぶ）の中間でGPSがふらつくと、最寄り判定だけでは数秒ごとに
     * A→B→A と札が入れ替わる。実散歩のログでは14秒のあいだに並走2本の両方が
     * 通過として数えられた。「いま乗っている道を続けて歩いている」方が
     * 「1サンプルおきに隣の道へ乗り換える」より圧倒的にありそうなので、
     * **乗り換えには明確な差を要求する**。
     *
     * 5mはGPSの1サンプルぶんのふらつき（水平誤差3〜5m）と同程度。これ未満だと
     * 粘着が効かず、大きくしすぎると本当に曲がったときの乗り換えが遅れて
     * 交差点の手前ぶんが元の道に食われる（0にすればこの機構は無効）。
     */
    val hysteresisMarginMeters: Double = 5.0,

    /**
     * 別のwayを挟んで同じwayに戻ってきたとき、これ未満の間隔なら同じ通過に併合する。
     *
     * 60秒：交差点でAを歩いている途中、横断する道Bに数秒だけスナップして
     * すぐAに戻る形が実散歩で何度も起きた。素直に数えるとAが2回になるが、
     * 実際には1回通っただけ。[hysteresisMarginMeters] で減らしても、
     * 交差点の中心では本当にBの方が近いので残る（ラベルは正しく、数え方の問題）。
     *
     * 60秒（＝[maxPassageGapMs] と同じ）にしてあるのは、「往復したら2回」の設計
     * （design.md §11「成長の入力は通過ごと」）を守るため：徒歩で1分あれば
     * 80m前後は離れられるので、1分以上空いてから戻ってきたなら本当に往復している。
     * 短くすると交差点の振動が残り、長くすると近所を周回する散歩で回数が減る。
     */
    val revisitMergeGapMs: Long = 60_000,
) {
    init {
        require(maxAccuracyMeters > 0) { "maxAccuracyMeters は正の値" }
        require(maxWalkingSpeedMps > 0) { "maxWalkingSpeedMps は正の値" }
        require(vehicleMinDurationMs >= 0) { "vehicleMinDurationMs は0以上" }
        require(maxSnapDistanceMeters > 0) { "maxSnapDistanceMeters は正の値" }
        require(minRunSamples >= 1) { "minRunSamples は1以上" }
        require(maxPassageGapMs >= 0) { "maxPassageGapMs は0以上" }
        require(hysteresisMarginMeters >= 0) { "hysteresisMarginMeters は0以上" }
        require(revisitMergeGapMs >= 0) { "revisitMergeGapMs は0以上" }
    }

    companion object {
        /** 既定の閾値。UIから触らせる予定はないので、差し替えはDI（`sharedModule`）で行う。 */
        val DEFAULT: MapMatchingConfig = MapMatchingConfig()
    }
}
