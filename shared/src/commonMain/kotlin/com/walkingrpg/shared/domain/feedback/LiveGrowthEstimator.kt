package com.walkingrpg.shared.domain.feedback

import com.walkingrpg.shared.domain.growth.GrowthConfig
import com.walkingrpg.shared.domain.growth.WayGrowthCalculator
import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.matching.MapMatcher
import com.walkingrpg.shared.domain.matching.MapMatchingConfig
import com.walkingrpg.shared.domain.matching.PassageBoundary
import com.walkingrpg.shared.domain.osm.GeoDistance
import com.walkingrpg.shared.domain.osm.Way
import com.walkingrpg.shared.domain.walk.LocationSample

/**
 * 歩行中に成長段階アップを**見込みで**検知する（design.md §3・architecture.md §5）。
 *
 * ## なぜ「見込み」なのか（issue #12 の設計判断）
 *
 * 確定の経路は帰宅後にしかない：セッションが終わってから
 * `RecomputePassagesUseCase`（`passage` の作り直し）→ `RecomputeWayGrowthUseCase`
 * （`way_growth` の作り直し）が走る。これはセッションのサンプル列を**丸ごと**見る
 * 冪等な処理で、途中経過を書き込む設計になっていない。
 *
 * 一方 architecture.md §5 の歩行中フローは
 * 「map matching → passage 記録 → 判定 → イベント発生なら振動1回」と書いてある。
 * この2つを両立させるために、歩行中は**導出テーブルを一切書かない**軽量な判定を
 * 別に回す（issue #12 の選択肢 a）。理由：
 *
 * - 歩行中に `passage` を書くと、帰宅後の作り直しと二重に書くことになる。
 *   作り直しはセッション単位の全削除→挿入なので結果は揃うが、そのあいだ
 *   「歩行中の暫定値」と「確定値」が同じテーブルに混在する時間ができる。
 *   減衰なし設計の背骨（冪等な再計算）を、余韻の演出のために危険に晒す価値はない。
 * - 部分再計算を定期的に回す案（選択肢 b）は、そのたびにセッション全部の
 *   サンプルを読み直す＝歩くほど重くなる。歩行中は電池が最優先の制約
 *   （architecture.md §8）なので、サンプル1件あたり一定コストのこちらを採る。
 *
 * **見込みが外れても壊れるものはない**。帰宅後の再計算が必ず上書きするので、
 * 真実は常にそちらにある。歩行中の振動は「何かが起きた」の印であって値ではない
 * （design.md §3「それ以上の情報は出さない」）ので、多少ずれても体験は成立する。
 *
 * ## 判定
 *
 * サンプルを1件ずつ畳んでいく**純粋な状態機械**。[MapMatcher] の簡略版で、
 * スナップ規則（[MapMatcher.snapWayId]）と通過の切れ目
 * （[com.walkingrpg.shared.domain.matching.PassageBoundary]）は本計算と共有する
 * ＝閾値を変えたときに歩行中と帰宅後がずれない。
 *
 * 1. 精度フィルタ（[MapMatchingConfig.maxAccuracyMeters]）
 * 2. 速度フィルタ（後述。本計算とは形が違う）
 * 3. wayへスナップ（[MapMatcher.snapWayId]。直前のwayへの粘着も同じ規則で効く）
 * 4. 同じ通過の中で [MapMatchingConfig.minRunSamples] 件たまったら「1回通った」と見込む
 * 5. 見込みの通過回数で段階が上がるなら [WalkEvent.GrowthStageUp] を1件返す
 *
 * ### 本計算との違い（意図的な簡略化）
 *
 * - **速度フィルタ**：本計算は「速い区間が30秒続いたら乗り物」と後ろ向きに判定するが、
 *   歩行中は先を見られない。ここでは直前の採用サンプルからの瞬間速度が
 *   [MapMatchingConfig.maxWalkingSpeedMps] を超えたサンプルを1件ずつ捨てる。
 *   GPSの飛び1点も一緒に落ちるが、落ちて困るのは振動1回ぶんだけ（記録は無傷）。
 * - **ノイズ均し**：本計算は前後の塊を見て単発の飛びを埋め戻す。ここでは
 *   「[MapMatchingConfig.minRunSamples] 件たまるまで数えない」だけにする。
 *   埋め戻しの代わりに"数え控える"側に倒すので、鳴りすぎない方向にずれる。
 */
data class LiveGrowthEstimator(
    /** 判定中の散歩。返すイベントに乗せる。 */
    private val sessionId: Long,
    /** 対象圏のwayマスタ。散歩の頭で1回だけ読む（歩いている途中で増えない）。 */
    private val ways: List<Way>,
    /**
     * 道ごとの通過回数の見込み。初期値は散歩開始時点の確定値
     * （`PassageRepository.passCountsByWay()`）で、通過を見込むたびに1ずつ増える。
     */
    private val passCounts: Map<Long, Int>,
    private val matchingConfig: MapMatchingConfig = MapMatchingConfig.DEFAULT,
    private val growthConfig: GrowthConfig = GrowthConfig.DEFAULT,
    /** 速度フィルタの基準。判定に使えなかったサンプルでは進めない。 */
    private val lastAcceptedSample: LocationSample? = null,
    /** いま乗っているway（スナップできていなければ直前のway）。 */
    val currentWayId: Long? = null,
    /**
     * 道ごとの「いま開いている通過」。
     *
     * 単一の塊ではなく**道ごと**に持つのは、交差点で A→B→A と数秒で戻る形を
     * Aの1回に併合するため（`PassageBoundary`）。Bに寄り道しているあいだも
     * Aの塊は開いたままなので、戻ってきたときに数え直さない。
     * 1回の散歩で通る道は20〜40本（`GrowthConfig`）なので、増え続けても軽い。
     */
    private val openRuns: Map<Long, OpenRun> = emptyMap(),
) {

    /**
     * サンプルを1件畳む。判定に使えないサンプルは黙って無視する。
     *
     * @return 進めた状態と、そこで段階が上がったなら [WalkEvent.GrowthStageUp] を1件。
     *  1件のサンプルから2件以上のイベントは出ない（1サンプルは1本の道にしか乗らない）。
     */
    fun sampleRecorded(sample: LocationSample): Update {
        if (sample.accuracyMeters > matchingConfig.maxAccuracyMeters) return Update(this)
        if (isTooFast(sample)) return Update(this)

        val accepted = copy(lastAcceptedSample = sample)
        // 直前のwayへの粘着（ヒステリシス）も本計算と同じ規則で効かせる。
        val wayId = MapMatcher.snapWayId(sample.toGeoPoint(), ways, matchingConfig, currentWayId)
        // どの道でもないサンプルは塊を切らない（本計算と同じ：木の下で1点外しただけで
        // 1本の道が2回通ったことにならないように）
        if (wayId == null) return Update(accepted)

        // 「ここから新しい通過か」は本計算と同じ判定を通す（PassageBoundary）。
        val previous = openRuns[wayId]
        val isNewPassage = PassageBoundary.isNewPassage(
            wayId = wayId,
            timestampMs = sample.timestampMs,
            currentWayId = currentWayId,
            lastSeenOnWayMs = previous?.lastSeenMs,
            config = matchingConfig,
        )
        val run = if (isNewPassage || previous == null) {
            OpenRun(lastSeenMs = sample.timestampMs, samples = 1, isCounted = false)
        } else {
            previous.copy(lastSeenMs = sample.timestampMs, samples = previous.samples + 1)
        }
        val advanced = accepted.copy(
            currentWayId = wayId,
            openRuns = openRuns + (wayId to run),
        )
        if (run.isCounted || run.samples < matchingConfig.minRunSamples) return Update(advanced)

        // ここで初めて「この道を1回通った」と見込む。
        val before = passCounts[wayId] ?: 0
        val after = before + 1
        val counted = advanced.copy(
            passCounts = passCounts + (wayId to after),
            openRuns = advanced.openRuns + (wayId to run.copy(isCounted = true)),
        )

        // 0回の道には段階が無い（WayGrowth のKDoc）ので、初回は必ず「上がった」になる。
        val stageBefore = before
            .takeIf { it >= GrowthConfig.GRASS_PASS_COUNT }
            ?.let { WayGrowthCalculator.stageOf(it, growthConfig) }
        val stageAfter = WayGrowthCalculator.stageOf(after, growthConfig)
        val event = if (stageAfter != stageBefore) {
            WalkEvent.GrowthStageUp(
                sessionId = sessionId,
                timestampMs = sample.timestampMs,
                wayId = wayId,
                stage = stageAfter,
            )
        } else {
            null
        }
        return Update(counted, event)
    }

    /**
     * 歩行ではありえない速度で飛んだサンプルか。
     *
     * 基準を「最後に採用したサンプル」に置くので、乗り物で移動しているあいだは
     * 基準が置き去りになったまま判定が続く（＝乗っているあいだは全件落ちる）。
     * 降りてしばらく歩けば、時間の経過で見かけの速度が下がって自然に復帰する。
     */
    private fun isTooFast(sample: LocationSample): Boolean {
        val previous = lastAcceptedSample ?: return false
        val elapsedMs = sample.timestampMs - previous.timestampMs
        // 同時刻・時刻逆転からは速度が出せない（本計算と同じく「速くない」と見る）
        if (elapsedMs <= 0L) return false
        val meters = GeoDistance.distanceMeters(previous.toGeoPoint(), sample.toGeoPoint())
        return meters / (elapsedMs / 1000.0) > matchingConfig.maxWalkingSpeedMps
    }

    private fun LocationSample.toGeoPoint(): GeoPoint =
        GeoPoint(latitude = latitude, longitude = longitude)

    /**
     * 1本の道の「いま開いている通過」。
     *
     * @param lastSeenMs その道に最後にスナップした時刻（通過の切れ目の判定材料）。
     * @param samples その通過でスナップした件数（[MapMatchingConfig.minRunSamples] と比べる）。
     * @param isCounted その通過をすでに1回として数えたか（1通過1回だけ数える）。
     */
    data class OpenRun(
        val lastSeenMs: Long,
        val samples: Int,
        val isCounted: Boolean,
    )

    /** サンプル1件ぶんの結果。 */
    data class Update(
        val estimator: LiveGrowthEstimator,
        val event: WalkEvent.GrowthStageUp? = null,
    )
}
