package com.walkingrpg.shared.domain.feedback

import com.walkingrpg.shared.domain.codex.CodexCategory
import com.walkingrpg.shared.domain.codex.CodexConfig
import com.walkingrpg.shared.domain.codex.Species
import com.walkingrpg.shared.domain.codex.SpeciesCatalog
import com.walkingrpg.shared.domain.codex.toCodexCategory
import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.matching.MapMatchingConfig
import com.walkingrpg.shared.domain.osm.GeoDistance
import com.walkingrpg.shared.domain.osm.Poi
import com.walkingrpg.shared.domain.walk.LocationSample

/**
 * 歩行中に図鑑の予兆・出現を**見込みで**検知する（design.md §3・§4.4）。
 *
 * [LiveGrowthEstimator] と同型の**純粋な状態機械**。設計判断もそちらと同じで、
 * 歩行中は導出テーブル（`codex_progress`）を一切書かない：確定するのは帰宅後の
 * `RecomputeCodexProgressUseCase` で、見込みが外れても壊れるものはない
 * （歩行中の振動は「何かが起きた」の印であって値ではない。design.md §3）。
 *
 * ## 判定
 *
 * 1. 精度フィルタ（[MapMatchingConfig.maxAccuracyMeters]。判定できないサンプルは無視）
 * 2. 測位点から [CodexConfig.poiProximityMeters] 以内のPOIを「この散歩で訪問した」と立てる。
 *    **同じPOIは1回だけ**（同じ川沿いを往復しても1回。確定側の数え方と同じ＝
 *    `CodexProgressCalculator` のKDoc「散歩単位で重複排除する」）
 * 3. 棚の訪問回数（POI単位の最大値）が1つ増えたら、その回数が
 *    出現の閾値ちょうどなら [WalkEvent.CodexDiscovered]、
 *    予兆の閾値ちょうど（閾値 - [CodexConfig.foreshadowLeadVisits]）なら
 *    [WalkEvent.CodexForeshadow] を1件返す
 *
 * ### 本計算との違い（意図的な簡略化）
 *
 * - **道ではなくPOIとの距離で測る**。確定側は「近傍wayを通ったか」で数えるが、
 *   歩行中に同じことをすると測位1件ごとに空間結合（`PoiWayIndex` 相当）が要る。
 *   測位点とPOIの距離を直に測れば、POI数ぶんの距離計算1回で済む。
 *   「POIの近くの道を歩いた」と「POIの近くを歩いた」は実質同じことなので、
 *   ずれるのは境界の数mだけ
 * - **速度フィルタを持たない**。電車で通り過ぎたPOIも訪問扱いになるが、
 *   確定側（`passage` 経由）は乗り物区間を落とすので、残るのは振動1回の誤発火だけ。
 *   その1回もレート制限（[VibrationBudget]）の中に収まる
 * - **1サンプルから返すイベントは1件**。交差点で複数の棚のPOIが同時に近傍に入ることは
 *   あるが、そこで2回鳴らすのは design.md §3（沈黙のデザイン）に反する。
 *   訪問そのものは全部立てるので、取りこぼした側は帰宅後の確定計算で必ず出る
 */
data class CodexForeshadowEstimator(
    /** 判定中の散歩。返すイベントに乗せる。 */
    private val sessionId: Long,
    /** 対象圏のPOIマスタ。散歩の頭で1回だけ読む（歩いている途中で増えない）。 */
    private val pois: List<Poi>,
    /**
     * POIごとの訪問回数。初期値は散歩開始時点の確定値
     * （`CodexProgressCalculator.visitCountsByPoi`）で、以降は更新しない
     * （この散歩ぶんの+1は [visitCountsByCategory] 側に積む）。
     */
    private val visitCountsByPoi: Map<String, Int>,
    private val species: List<Species> = SpeciesCatalog.ALL,
    private val config: CodexConfig = CodexConfig.DEFAULT,
    /** 精度フィルタの基準だけを借りる（判定に使えないサンプルでは進めない）。 */
    private val matchingConfig: MapMatchingConfig = MapMatchingConfig.DEFAULT,
    /** この散歩で既に訪問を立てたPOI（同じPOIを二度数えないための印）。 */
    private val visitedPoiIds: Set<String> = emptySet(),
    /**
     * 棚ごとの訪問回数の見込み（POI単位の最大値）。
     * 初期値は [visitCountsByPoi] から畳んだ確定値で、訪問を立てるたびに増える。
     */
    private val visitCountsByCategory: Map<CodexCategory, Int> =
        categoryCounts(pois, visitCountsByPoi),
) {

    /**
     * サンプルを1件畳む。判定に使えないサンプルは黙って無視する。
     *
     * @return 進めた状態と、閾値ちょうどに届いたなら図鑑のイベントを1件（上記「本計算との違い」）。
     */
    fun sampleRecorded(sample: LocationSample): Update {
        if (sample.accuracyMeters > matchingConfig.maxAccuracyMeters) return Update(this)

        val point = GeoPoint(latitude = sample.latitude, longitude = sample.longitude)
        // 並びを固定するのは、同じサンプル列から必ず同じイベントが出るようにするため
        // （複数のPOIが同時に近傍に入ったときにどれを鳴らすかが決まる）。
        val arrived = pois
            .filter { poi ->
                poi.id !in visitedPoiIds &&
                    GeoDistance.distanceMeters(point, poi.location) <= config.poiProximityMeters
            }
            .sortedBy { it.id }
        if (arrived.isEmpty()) return Update(this)

        var counts = visitCountsByCategory
        var event: WalkEvent? = null
        for (poi in arrived) {
            val category = poi.kind.toCodexCategory()
            val before = counts[category] ?: 0
            // 棚の回数はPOI単位の最大値なので、いま訪問したPOIの回数+1で更新を試みる。
            // 既に他のPOIで先を行っている棚では増えない（＝何も起きない）。
            val after = maxOf(before, (visitCountsByPoi[poi.id] ?: 0) + 1)
            if (after <= before) continue
            counts = counts + (category to after)
            if (event == null) event = eventFor(category, after, poi.id, sample.timestampMs)
        }

        return Update(
            estimator = copy(
                visitedPoiIds = visitedPoiIds + arrived.map { it.id },
                visitCountsByCategory = counts,
            ),
            event = event,
        )
    }

    /**
     * 棚の訪問回数が [visitCount] になったときに出すイベント（無ければ `null`）。
     *
     * 回数は1ずつしか増えない（[sampleRecorded]）ので「ちょうど」で判定して取りこぼさない。
     * 既に発見済みの種は閾値を通り過ぎているため、ここには二度と一致しない
     * ＝「もう出た種の出現」が再発火しない。
     *
     * 出現と予兆が同時に当たることはある（閾値が2つ違いの種が同じ棚にある場合）ので、
     * **出現を先に見る**：嬉しさの大きい方を落とさない。
     */
    private fun eventFor(
        category: CodexCategory,
        visitCount: Int,
        poiId: String,
        timestampMs: Long,
    ): WalkEvent? {
        val inCategory = species.filter { it.category == category }.sortedBy { it.requiredVisitCount }

        inCategory.firstOrNull { it.requiredVisitCount == visitCount }?.let { discovered ->
            return WalkEvent.CodexDiscovered(
                sessionId = sessionId,
                timestampMs = timestampMs,
                poiId = poiId,
                speciesId = discovered.id,
            )
        }

        // 閾値が予兆の先行ぶんより小さい種（設定を緩めた場合）には予兆を出さない：
        // 訪問0回で予兆＝行く前から匂っていることになる。
        val near = inCategory.firstOrNull {
            it.requiredVisitCount - config.foreshadowLeadVisits == visitCount && visitCount >= 1
        } ?: return null

        return WalkEvent.CodexForeshadow(
            sessionId = sessionId,
            timestampMs = timestampMs,
            poiId = poiId,
            speciesId = near.id,
        )
    }

    /** サンプル1件ぶんの結果。 */
    data class Update(
        val estimator: CodexForeshadowEstimator,
        val event: WalkEvent? = null,
    )

    private companion object {

        /** POIごとの回数を棚ごとの最大値に畳む（`CodexProgressCalculator` と同じ数え方）。 */
        fun categoryCounts(
            pois: List<Poi>,
            visitCountsByPoi: Map<String, Int>,
        ): Map<CodexCategory, Int> = pois
            .groupBy { it.kind.toCodexCategory() }
            .mapValues { (_, inCategory) ->
                inCategory.maxOf { visitCountsByPoi[it.id] ?: 0 }
            }
    }
}
