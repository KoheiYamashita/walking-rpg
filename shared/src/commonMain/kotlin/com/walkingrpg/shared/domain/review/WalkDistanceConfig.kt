package com.walkingrpg.shared.domain.review

import com.walkingrpg.shared.domain.matching.MapMatchingConfig

/**
 * 「実際に歩いた距離」の閾値。**調整する数字は全部ここに集める**（`MapMatchingConfig` と同じ流儀）。
 *
 * 距離は `location_sample` から何度でも出し直せる（[WalkDistanceCalculator] は純関数）ので、
 * ここを変えれば過去の散歩の距離もそのまま変わる。永続化された距離の列はどこにも無い
 * ＝閾値の調整で古い記録と新しい記録の物差しが食い違うことはない。
 *
 * 例外は月次スナップショット（`snapshot.stats_json`）だけで、あちらは
 * 生成済みの月を作り直さない（design.md §4.5「一度作った月は書き換えない」）。
 */
data class WalkDistanceConfig(
    /**
     * これより精度（水平誤差）の悪いサンプルは距離の計算に使わない。
     *
     * 25m：map matching の [com.walkingrpg.shared.domain.matching.MapMatchingConfig.maxAccuracyMeters]
     * と同じ値にしてある。「どの道にいたか分からないサンプル」は「どれだけ動いたかも
     * 分からないサンプル」なので、片方だけ通して距離に混ぜると
     * 「今日の道」と「歩いた距離」が別々のサンプル集合から出ることになる。
     */
    val maxAccuracyMeters: Double = 25.0,

    /**
     * 前回のアンカーからこれ以上動いたときだけ距離を足し、アンカーを更新する。
     *
     * 5m：静止していてもGPSは1〜3m程度ふらつく。1Hzで20分立ち止まると
     * 1200件 × 数m＝数kmの「歩いていない距離」が積み上がるので、
     * **隣り合うサンプル間の距離を素朴に足す方式は採らない**。
     * アンカー方式なら、揺らぎがこの半径に収まっているあいだ距離は1mmも増えない。
     *
     * 実測（1Hz・精度中央値3.2m）の20分の散歩で、素朴な積み上げが実移動を2割ほど
     * 上回るのに対し、5mのアンカーはほぼ一致する。大きくしすぎると、
     * ゆるいカーブが弦で近似されて距離が目減りする（10mだと曲がりくねった遊歩道で効く）。
     */
    val anchorMoveMeters: Double = 5.0,

    /**
     * これを超える速度が続く区間は乗り物とみなし、距離に積まない。
     *
     * 値を書かずに [MapMatchingConfig] から**借りている**のが肝。判定そのものも
     * `VehicleRunFilter`（map matching と共有の純関数）に任せていて、
     * ここはその入口でしかない。数字を写すと、片方だけ調整したときに
     * 「道は塗られていないのに距離だけ伸びた散歩」が生まれる
     * （散歩の停止を押し忘れてバスに乗った日がまさにその形）。
     */
    val maxWalkingSpeedMps: Double = MapMatchingConfig.DEFAULT.maxWalkingSpeedMps,

    /**
     * 速度超過がこの時間続いたら乗り物として距離から除外する。
     *
     * [maxWalkingSpeedMps] と同じ理由で [MapMatchingConfig] から借りる。
     * 「一定時間続いたら」なのは、1区間だけの超過がGPSの飛び（誤測位）の形だから：
     * 横断歩道で一瞬飛んだだけで前後の徒歩ぶんまで削ってしまうと、距離が過小になる。
     */
    val vehicleMinDurationMs: Long = MapMatchingConfig.DEFAULT.vehicleMinDurationMs,
) {
    init {
        require(maxAccuracyMeters > 0) { "maxAccuracyMeters は正の値" }
        require(anchorMoveMeters > 0) { "anchorMoveMeters は正の値" }
        require(maxWalkingSpeedMps > 0) { "maxWalkingSpeedMps は正の値" }
        require(vehicleMinDurationMs >= 0) { "vehicleMinDurationMs は0以上" }
    }

    companion object {
        /** 既定の閾値。UIから触らせる予定はないので、差し替えはDI（`sharedModule`）で行う。 */
        val DEFAULT: WalkDistanceConfig = WalkDistanceConfig()
    }
}
