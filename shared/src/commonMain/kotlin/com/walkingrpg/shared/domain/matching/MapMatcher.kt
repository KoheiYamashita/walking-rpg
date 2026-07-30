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
 * 3. **スナップ**：残ったサンプルを最寄りのwayへ（上限距離あり）。ただし直前のwayには
 *    粘着する（[MapMatchingConfig.hysteresisMarginMeters]）
 * 4. **連続性チェック**：単発の飛びをノイズとして均し（[MapMatchingConfig.minRunSamples]）、
 *    同じwayへの連続を1つの通過に畳む
 *
 * 精度フィルタを先に置くのは、誤差の大きいサンプルが速度計算を汚さないため
 * （50m飛んだ1件で、その前後2区間が「乗り物」に見えてしまう）。
 *
 * ## 境界の振動に効く3つの機構（役割が違うので全部要る）
 * 実散歩のログでは、並走する2本のfootwayと交差点で通過が水増しされた。効かせる場所が違う：
 *
 * - **ヒステリシス**（3のスナップ時）：札そのものを揺らさない。並走2本の**中間**で
 *   ふらつくケースに効く。1サンプル単位で判断する唯一の機構
 * - **ノイズ均し**（[smoothNoise]）：短すぎる塊を消す。単発〜数件の飛びに効くが、
 *   前後が同じwayでなければ「どの道でもない」に落とすだけなので、
 *   交差点で本当にBの上を通った数秒間は残る
 * - **再通過の併合**（[toPassages]）：札は正しいまま、**数え方**を直す。
 *   A→B→A が短時間で起きたときにAを2回にしない
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
        val labels = label(walking, ways, config)
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

    /** サンプル列を1件ずつ道に貼る。直前の札を持ち回るので**順に**処理する。 */
    private fun label(
        samples: List<LocationSample>,
        ways: List<Way>,
        config: MapMatchingConfig,
    ): List<Long?> {
        val labels = ArrayList<Long?>(samples.size)
        var previousWayId: Long? = null
        for (sample in samples) {
            val wayId = snapWayId(sample.toGeoPoint(), ways, config, previousWayId)
            labels += wayId
            // どの道でもないサンプルでは粘着先を捨てない（木の下で1点外しただけで
            // 並走する道に乗り換えてしまわないように）。
            if (wayId != null) previousWayId = wayId
        }
        return labels
    }

    /**
     * サンプルを道に貼る。[MapMatchingConfig.maxSnapDistanceMeters] を超えたら
     * `null`（どの道でもない）。
     *
     * ## ヒステリシス（現在の道への粘着）
     * 直前に乗っていた [previousWayId] もスナップ距離内にいるなら、新しいwayが
     * [MapMatchingConfig.hysteresisMarginMeters] 以上**明確に近い**ときだけ乗り換える。
     * 並走する2本の中間でGPSがふらつくと、素の最寄り判定では数秒ごとに札が入れ替わり、
     * 片方しか歩いていないのに両方が通過になる（実散歩で観測）。
     *
     * 「いま乗っている道を歩き続けている」方が「1サンプルおきに隣の道へ乗り換える」より
     * 圧倒的にありそう、という事前確率をそのまま閾値にしたもの。
     *
     * ## 同点の扱い
     * 最寄りが同点のときはway IDの小さい方を採る。実際に同点になることはまず無いが、
     * 順序に依存しない規則を決めておかないと冪等性が崩れる。
     *
     * `internal` で開けてあるのは、歩行中の見込み判定
     * （[com.walkingrpg.shared.domain.feedback.LiveGrowthEstimator]）が**同じ規則**で
     * スナップするため。ここを写して持たせると、閾値を変えたときに
     * 歩行中と帰宅後で乗る道が食い違う。
     *
     * @param previousWayId 直前に乗っていた道。`null`（散歩の頭）なら素の最寄り。
     */
    internal fun snapWayId(
        point: GeoPoint,
        ways: List<Way>,
        config: MapMatchingConfig,
        previousWayId: Long? = null,
    ): Long? {
        val nearest = nearestSnap(point, ways, config) ?: return null
        if (previousWayId == null || nearest.wayId == previousWayId) return nearest.wayId

        // 直前の道がマスタから消えた（対象圏を取り直した）場合は粘着しようがない
        val previousGeometry = ways.firstOrNull { it.id == previousWayId }?.geometry
            ?: return nearest.wayId
        val previousDistance = GeoDistance.distanceToPathMeters(point, previousGeometry)
        // 直前の道からもう離れてしまったなら、粘着させる理由が無い（普通に曲がった）
        if (previousDistance > config.maxSnapDistanceMeters) return nearest.wayId

        val isClearlyCloser = nearest.distanceMeters < previousDistance - config.hysteresisMarginMeters
        return if (isClearlyCloser) nearest.wayId else previousWayId
    }

    /** 最寄りwayとその距離。圏内に1本も無ければ `null`。 */
    private fun nearestSnap(point: GeoPoint, ways: List<Way>, config: MapMatchingConfig): Snap? {
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
        return bestId?.let { Snap(wayId = it, distanceMeters = bestDistance) }
    }

    private data class Snap(val wayId: Long, val distanceMeters: Double)

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
     *
     * ヒステリシス（[snapWayId]）を入れたあとも残す：あちらは**乗り換えの判断**を
     * 渋らせるだけで、粘着を突破した飛び（本当に隣の道の方がはっきり近い1点）は
     * そのまま札になる。それを消すのはこちらの仕事。
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
     * 貼った札を通過に畳む。切れ目の規則そのものは [PassageBoundary]（歩行中の見込みと共有）。
     *
     * 道ごとに「最後にスナップした時刻」を持ち回るのは、**別の道を挟んで戻ってきた**
     * ケースを見分けるため。交差点で横断する道に数秒だけ乗ってすぐ戻る形
     * （[MapMatchingConfig.revisitMergeGapMs] 未満）は1回の通過に併合する。
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
        val lastSeenByWay = mutableMapOf<Long, Long>()
        var currentWayId: Long? = null

        labels.forEachIndexed { index, wayId ->
            if (wayId == null) return@forEachIndexed
            val timestampMs = samples[index].timestampMs
            val isNewPassage = PassageBoundary.isNewPassage(
                wayId = wayId,
                timestampMs = timestampMs,
                currentWayId = currentWayId,
                lastSeenOnWayMs = lastSeenByWay[wayId],
                config = config,
            )
            if (isNewPassage) {
                passages += Passage(sessionId = sessionId, wayId = wayId, timestampMs = timestampMs)
            }
            lastSeenByWay[wayId] = timestampMs
            currentWayId = wayId
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
