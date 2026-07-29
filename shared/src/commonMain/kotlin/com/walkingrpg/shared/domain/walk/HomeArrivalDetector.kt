package com.walkingrpg.shared.domain.walk

import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.osm.GeoDistance

/**
 * 自動終了の判定（design.md §3「自宅付近＋移動停止を検知して『おかえり』を出す」）。
 *
 * [WalkRecordingState] と同じく**純粋な状態機械**：現在時刻も乱数も測位APIも使わず、
 * サンプルを1件ずつ畳んでいくだけ。時刻はサンプルが持っているものだけを見る
 * （端末時計を別に読むと、測位の遅延ぶんだけ「止まっている時間」が水増しされる）。
 *
 * ## 判定の骨格
 *
 * 1. **武装**：セッション開始時点ではまだ自宅にいるので、そのまま自宅判定を回すと
 *    最初のサンプルで即終了する。一度**自宅圏の外へ出る**まで判定を始めない
 *    （[hasLeftHome]）。さらに保険として [HomeArrivalConfig.minSessionDurationMs] を置く。
 * 2. **自宅ジオフェンス**：自宅から [HomeArrivalConfig.homeRadiusMeters] 以内にいるあいだだけ、
 *    止まり判定を進める。圏外に出たら止まり判定はリセットする。
 *    これで「信号待ち」「公園のベンチ」のような**自宅圏外の停止では終わらない**
 *    ＝design.md §3 の AND がそのまま実装になっている。
 * 3. **移動停止**：圏内の1点を基準に、そこから [HomeArrivalConfig.stillnessRadiusMeters] を
 *    超えて動いたら基準を置き直す。超えないまま
 *    [HomeArrivalConfig.stillnessDurationMs] 経ったら「帰宅して止まった」とみなす。
 *
 * ## 自宅が未登録のとき
 *
 * [home] が `null` なら**自動終了しない**。design.md §3 の条件が「自宅付近＋移動停止」の
 * ANDである以上、片方が欠けたら成立させようがない。停止検知だけで終わらせる案もあるが、
 * それは「昼食で30分座った」を帰宅として散歩を打ち切る仕様であり、
 * 誤終了＝記録の欠落という取り返しのつかない側に倒れる。自宅未登録は
 * セットアップで後回しにできる（SetupStep.HOME）ので、その間は手動終了で足りる。
 */
data class HomeArrivalDetector(
    /** 自宅座標。未登録なら `null`（＝自動終了しない）。**端末内でしか扱わない値**。 */
    private val home: GeoPoint?,
    /** セッション開始時刻。[HomeArrivalConfig.minSessionDurationMs] の基準。 */
    private val sessionStartedAtMs: Long,
    private val config: HomeArrivalConfig = HomeArrivalConfig.DEFAULT,
    /** 一度でも自宅圏の外に出たか。出るまで判定は武装しない。 */
    val hasLeftHome: Boolean = false,
    /** 止まり判定の基準点（自宅圏内で最後に「動いた」サンプル）。圏外では `null`。 */
    val stillSince: LocationSample? = null,
    /** 自動終了の条件が揃った。以降は何を渡しても変わらない。 */
    val isArrived: Boolean = false,
) {

    /**
     * サンプルを1件畳む。判定に使えないサンプル（精度が悪い）は黙って無視する。
     *
     * 無視したサンプルで [stillSince] を進めないのは意図的：誤差50mの1点で
     * 「25m動いた」ことにされると、実際には玄関で止まっているのに止まり判定が
     * 延々やり直しになり、自動終了が一生成立しなくなる。
     */
    fun sampleRecorded(sample: LocationSample): HomeArrivalDetector {
        if (home == null || isArrived) return this
        if (sample.accuracyMeters > config.maxAccuracyMeters) return this

        val point = GeoPoint(latitude = sample.latitude, longitude = sample.longitude)
        val isNearHome = GeoDistance.distanceMeters(point, home) <= config.homeRadiusMeters

        if (!hasLeftHome) {
            // 出発直後。自宅圏を抜けた時点で武装する（抜けた瞬間は当然圏外なので止まり判定は無い）
            return if (isNearHome) this else copy(hasLeftHome = true)
        }
        // 自宅圏の外での停止は帰宅ではない（信号待ち・ベンチ・店内）
        if (!isNearHome) return copy(stillSince = null)

        val anchor = stillSince ?: return copy(stillSince = sample)
        val anchorPoint = GeoPoint(latitude = anchor.latitude, longitude = anchor.longitude)
        if (GeoDistance.distanceMeters(point, anchorPoint) > config.stillnessRadiusMeters) {
            // まだ動いている（自宅前を通り過ぎる・庭で作業する等）。基準を置き直す
            return copy(stillSince = sample)
        }

        val stillForMs = sample.timestampMs - anchor.timestampMs
        val walkedForMs = sample.timestampMs - sessionStartedAtMs
        val arrived = stillForMs >= config.stillnessDurationMs &&
            walkedForMs >= config.minSessionDurationMs
        return if (arrived) copy(isArrived = true) else this
    }
}
