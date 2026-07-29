package com.walkingrpg.shared.domain.matching

import com.walkingrpg.shared.domain.map.GeoPoint
import com.walkingrpg.shared.domain.osm.GeoDistance
import com.walkingrpg.shared.domain.osm.Way
import com.walkingrpg.shared.domain.walk.LocationSample

/**
 * 測位サンプル列 → 通過（`passage`）の変換（design.md §4.1「GPS誤差は道へのスナップで吸収」）。
 *
 * ドメイン層の心臓部。**純関数**で、現在時刻も乱数も使わない：
 * 同じサンプル列・同じwayマスタ・同じ閾値なら必ず同じ [Passage] 列になる（冪等）。
 * これが「状態 = 歩行ログの累積」（design.md §4.1）を成り立たせている前提で、
 * 導出テーブルをいつ捨てて作り直しても結果が変わらないことの根拠でもある。
 *
 * パイプライン（順序に意味がある）：
 * 1. **精度フィルタ**：誤差の大きいサンプルを落とす（[MapMatchingConfig.maxAccuracyMeters]）
 * 2. **速度フィルタ**：歩行ではありえない速度が続く区間＝電車・バスを落とす
 * 3. **最寄りway探索**：残ったサンプルを最寄りのwayへスナップ（上限距離あり）
 * 4. **連続性チェック**：単発の飛びをノイズとして均し、同じwayへの連続を1つの通過に畳む
 *
 * 精度フィルタを先に置くのは、誤差の大きいサンプルが速度計算を汚さないため
 * （50m飛んだ1件で、その前後2区間が「乗り物」に見えてしまう）。
 */
object MapMatcher {

    /**
     * [sessionId] のサンプル列から通過を作る。結果は時刻の昇順。
     *
     * @param samples 時刻順でなくてもよい（内部で並べ替える）。
     * @param ways 対象圏のwayマスタ。空なら通過も空。
     */
    fun match(
        sessionId: Long,
        samples: List<LocationSample>,
        ways: List<Way>,
        config: MapMatchingConfig = MapMatchingConfig.DEFAULT,
    ): List<Passage> {
        if (samples.isEmpty() || ways.isEmpty()) return emptyList()

        // ID順の安定ソートを挟むのは、同時刻のサンプルやスナップ距離の同点で
        // 入力の並び順によって結果が変わらないようにするため（冪等の担保）。
        val ordered = samples.sortedWith(compareBy({ it.timestampMs }, { it.latitude }, { it.longitude }))

        val accurate = ordered.filter { it.accuracyMeters <= config.maxAccuracyMeters }
        val walking = dropVehicleRuns(accurate, config)
        val labels = walking.map { sample -> nearestWayId(sample.toGeoPoint(), ways, config) }
        val smoothed = smoothNoise(labels, config.minRunSamples)

        return toPassages(sessionId, walking, smoothed, config)
    }

    /**
     * 速度フィルタ（design.md §9「移動手段判定」）。
     *
     * 連続する2サンプルの区間ごとに速度を見て、超過が
     * [MapMatchingConfig.vehicleMinDurationMs] 以上続いた区間のサンプルを丸ごと落とす。
     * 1区間だけの超過を落とさないのは、それがGPSの飛び（誤測位）の形だから。
     * 判定は「歩いた区間か」だけで、電車かバスか自転車かは区別しない。
     */
    private fun dropVehicleRuns(
        samples: List<LocationSample>,
        config: MapMatchingConfig,
    ): List<LocationSample> {
        if (samples.size < 2) return samples

        // interval[i] は samples[i] と samples[i + 1] の間
        val isFast = BooleanArray(samples.size - 1) { index ->
            val from = samples[index]
            val to = samples[index + 1]
            val elapsedMs = to.timestampMs - from.timestampMs
            // 同時刻・時刻逆転のサンプルからは速度が出せない（0除算・負の時間）。
            // 端末が同じ時刻で複数返すことは実際にあるので、落とさず「速くない」と見る。
            if (elapsedMs <= 0L) {
                false
            } else {
                val meters = GeoDistance.distanceMeters(from.toGeoPoint(), to.toGeoPoint())
                meters / (elapsedMs / 1000.0) > config.maxWalkingSpeedMps
            }
        }

        val excluded = BooleanArray(samples.size)
        var index = 0
        while (index < isFast.size) {
            if (!isFast[index]) {
                index++
                continue
            }
            var end = index
            while (end + 1 < isFast.size && isFast[end + 1]) end++
            val durationMs = samples[end + 1].timestampMs - samples[index].timestampMs
            if (durationMs >= config.vehicleMinDurationMs) {
                // 区間の両端のサンプルも乗り物側にいたので一緒に落とす
                for (target in index..end + 1) excluded[target] = true
            }
            index = end + 1
        }

        return samples.filterIndexed { position, _ -> !excluded[position] }
    }

    /**
     * 最寄りway探索。[MapMatchingConfig.maxSnapDistanceMeters] を超えたら `null`（どの道でもない）。
     *
     * 同点のときはway IDの小さい方を採る。実際に同点になることはまず無いが、
     * 順序に依存しない規則を決めておかないと冪等性が崩れる。
     *
     * `internal` で開けてあるのは、歩行中の見込み判定
     * （[com.walkingrpg.shared.domain.feedback.LiveGrowthEstimator]）が**同じ規則**で
     * スナップするため。ここを写して持たせると、閾値を変えたときに
     * 歩行中と帰宅後で乗る道が食い違う。
     */
    internal fun nearestWayId(point: GeoPoint, ways: List<Way>, config: MapMatchingConfig): Long? {
        var bestId: Long? = null
        var bestDistance = config.maxSnapDistanceMeters
        for (way in ways) {
            val distance = GeoDistance.distanceToPathMeters(point, way.geometry)
            if (distance > bestDistance) continue
            val current = bestId
            if (current == null || distance < bestDistance || way.id < current) {
                bestId = way.id
                bestDistance = distance
            }
        }
        return bestId
    }

    /**
     * 連続性チェック。同じway IDが続く塊（run）を単位に、短すぎる塊を均す。
     *
     * - 前後の塊が**同じway**なら、その塊も同じwayだったとみなす
     *   （並行する道や交差点でのGPS誤差で1点だけ飛ぶ、いちばん多い誤り方）
     * - そうでなければ「どの道でもない」に落とす
     *   （曲がり角で隣の道をかすめただけ＝実際には歩いていない道を塗らせない）
     *
     * 判定材料は必ず**元の**塊の並びを見る。均した結果を次の判定に使うと、
     * 端から順に効果が伝播して入力の並び方に依存しはじめる。
     */
    private fun smoothNoise(labels: List<Long?>, minRunSamples: Int): List<Long?> {
        if (labels.isEmpty()) return labels
        val runs = buildRuns(labels)
        val resolved = runs.map { it.wayId }.toMutableList()

        runs.forEachIndexed { runIndex, run ->
            if (run.wayId == null || run.size >= minRunSamples) return@forEachIndexed
            val previous = runs.take(runIndex).lastOrNull { it.wayId != null }?.wayId
            val next = runs.drop(runIndex + 1).firstOrNull { it.wayId != null }?.wayId
            resolved[runIndex] = if (previous != null && previous == next) previous else null
        }

        val smoothed = arrayOfNulls<Long>(labels.size)
        runs.forEachIndexed { runIndex, run ->
            for (position in run.from until run.from + run.size) smoothed[position] = resolved[runIndex]
        }
        return smoothed.toList()
    }

    /**
     * 同じwayへの連続スナップを1つの通過に畳む。
     *
     * - wayが変われば別の通過
     * - 同じwayでも [MapMatchingConfig.maxPassageGapMs] 以上間が空いたら別の通過
     *   （途中で測位が落ちた・遠回りして戻ってきた）
     *
     * 「どの道でもない」サンプルは通過を切らない。切ってしまうと、木の下で
     * スナップを1点外しただけで1本の道が2回通ったことになる（水増し）。
     */
    private fun toPassages(
        sessionId: Long,
        samples: List<LocationSample>,
        labels: List<Long?>,
        config: MapMatchingConfig,
    ): List<Passage> {
        val passages = mutableListOf<Passage>()
        var currentWayId: Long? = null
        var lastTimestampMs = 0L

        labels.forEachIndexed { index, wayId ->
            if (wayId == null) return@forEachIndexed
            val timestampMs = samples[index].timestampMs
            val isNewPassage = wayId != currentWayId ||
                timestampMs - lastTimestampMs > config.maxPassageGapMs
            if (isNewPassage) {
                passages += Passage(sessionId = sessionId, wayId = wayId, timestampMs = timestampMs)
            }
            currentWayId = wayId
            lastTimestampMs = timestampMs
        }
        return passages
    }

    /** 同じ値が続く区間。[from] は元のリストでの開始位置。 */
    private data class Run(val wayId: Long?, val from: Int, val size: Int)

    private fun buildRuns(labels: List<Long?>): List<Run> {
        val runs = mutableListOf<Run>()
        var from = 0
        while (from < labels.size) {
            var to = from
            while (to + 1 < labels.size && labels[to + 1] == labels[from]) to++
            runs += Run(wayId = labels[from], from = from, size = to - from + 1)
            from = to + 1
        }
        return runs
    }

    private fun LocationSample.toGeoPoint(): GeoPoint =
        GeoPoint(latitude = latitude, longitude = longitude)
}
