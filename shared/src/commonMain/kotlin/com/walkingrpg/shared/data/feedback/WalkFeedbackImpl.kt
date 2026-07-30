package com.walkingrpg.shared.data.feedback

import com.walkingrpg.shared.domain.codex.CodexConfig
import com.walkingrpg.shared.domain.codex.CodexProgressCalculator
import com.walkingrpg.shared.domain.codex.PoiWayIndex
import com.walkingrpg.shared.domain.feedback.CodexForeshadowEstimator
import com.walkingrpg.shared.domain.feedback.LiveGrowthEstimator
import com.walkingrpg.shared.domain.feedback.VibrationBudget
import com.walkingrpg.shared.domain.feedback.WalkEvent
import com.walkingrpg.shared.domain.feedback.WalkEventBus
import com.walkingrpg.shared.domain.feedback.WalkFeedback
import com.walkingrpg.shared.domain.feedback.WalkFeedbackConfig
import com.walkingrpg.shared.domain.growth.GrowthConfig
import com.walkingrpg.shared.domain.matching.MapMatchingConfig
import com.walkingrpg.shared.domain.matching.PassageRepository
import com.walkingrpg.shared.domain.osm.OsmMasterRepository
import com.walkingrpg.shared.domain.walk.LocationSample
import com.walkingrpg.shared.platform.Haptics
import kotlinx.coroutines.CancellationException

/**
 * [WalkFeedback] の実装。歩行中の見込み判定（domain）と、振動（platform）・
 * イベントの記録（[WalkEventBus]）を結線する。
 *
 * 判定そのものは持たない：段階アップの見込みは [LiveGrowthEstimator]、
 * 図鑑の予兆・出現の見込みは [CodexForeshadowEstimator]、振動の回数制限は
 * [VibrationBudget] という純関数側にあり、ここは「読んで・畳んで・鳴らす」だけ
 * （`WalkRecorderImpl` と `HomeArrivalDetector` の関係と同じ）。
 *
 * ## イベント種別の増やし方
 *
 * 判定器をもう1つ足して、[sampleRecorded] で同じように畳み、出てきたイベントを
 * [publish] に渡すだけでよい（issue #13 の図鑑がまさにこの形で乗った）。
 * 振動・レート制限・画面の断片は種別を知らないので、そのまま効く。
 *
 * ## 判定器はそれぞれ独立に黙る
 *
 * 土台（wayマスタ・POIマスタ・通過）が読めなかった判定器は `null` のままになり、
 * もう一方はそのまま動く。どちらも読めなくても記録（`location_sample`）は無関係に続き、
 * 帰宅後の再計算も普通に走る。
 */
internal class WalkFeedbackImpl(
    private val osmMasterRepository: OsmMasterRepository,
    private val passageRepository: PassageRepository,
    private val eventBus: WalkEventBus,
    private val haptics: Haptics,
    private val matchingConfig: MapMatchingConfig = MapMatchingConfig.DEFAULT,
    private val growthConfig: GrowthConfig = GrowthConfig.DEFAULT,
    private val codexConfig: CodexConfig = CodexConfig.DEFAULT,
    private val feedbackConfig: WalkFeedbackConfig = WalkFeedbackConfig.DEFAULT,
) : WalkFeedback {

    private var growthEstimator: LiveGrowthEstimator? = null
    private var codexEstimator: CodexForeshadowEstimator? = null
    private var budget = VibrationBudget(feedbackConfig)

    /**
     * マスタと現在の通過を散歩の頭で1回だけ読む。
     * 測位1件ごとにDBを叩かないのが目的（`WalkRecorderImpl` が自宅を頭で1回だけ読むのと同じ）。
     *
     * 図鑑側は「POI × way の空間結合」と「道 × 散歩の集計」まで頭でやってしまう
     * （[PoiWayIndex] / [CodexProgressCalculator.visitCountsByPoi]）。POI数百件 ×
     * way約220本（design.md §9）の純計算なので出発時の一瞬で終わり、そのぶん
     * 歩いているあいだはPOIとの距離計算だけになる。
     *
     * 読めなくても散歩は始める。マスタ未取り込み・DBエラーはどちらも
     * 「振動しない散歩」で済ませてよく、記録を止める理由にはならない。
     */
    override suspend fun walkStarted(sessionId: Long) {
        budget = VibrationBudget(feedbackConfig)
        growthEstimator = try {
            LiveGrowthEstimator(
                sessionId = sessionId,
                ways = osmMasterRepository.ways(),
                passCounts = passageRepository.passCountsByWay(),
                matchingConfig = matchingConfig,
                growthConfig = growthConfig,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            null
        }
        codexEstimator = try {
            val pois = osmMasterRepository.pois()
            CodexForeshadowEstimator(
                sessionId = sessionId,
                pois = pois,
                visitCountsByPoi = CodexProgressCalculator.visitCountsByPoi(
                    index = PoiWayIndex.of(
                        pois = pois,
                        ways = osmMasterRepository.ways(),
                        config = codexConfig,
                    ),
                    sessionVisitsByWay = passageRepository.sessionVisitsByWay(),
                ),
                config = codexConfig,
                matchingConfig = matchingConfig,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            null
        }
    }

    /**
     * 測位1件ぶんの判定。[walkStarted] と同じく、**何があっても例外を外に出さない**
     * （[WalkFeedback] の契約）。
     *
     * ここで投げると `WalkRecorderImpl` の catch-all に落ちて、測位そのものは
     * 健全なのにセッションが `LOCATION_ERROR` で畳まれる。演出（振動・画面の断片）の
     * 失敗が記録を止めるのは本末転倒なので、飲んで黙る。
     */
    override suspend fun sampleRecorded(sample: LocationSample) {
        try {
            // 判定（純関数）が返った時点で状態を進める。**下の副作用が失敗しても戻さない**：
            // 戻すと次のサンプルで同じ通過をもう一度検知して、同じイベントが二度出る
            // （振動も二度鳴る）。イベントを取りこぼすほうが、二重に鳴らすより害が小さい。
            val events = buildList {
                growthEstimator?.let { estimator ->
                    val update = estimator.sampleRecorded(sample)
                    growthEstimator = update.estimator
                    update.event?.let(::add)
                }
                codexEstimator?.let { estimator ->
                    val update = estimator.sampleRecorded(sample)
                    codexEstimator = update.estimator
                    update.event?.let(::add)
                }
            }

            events.forEach(::publish)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            // 進めた状態はそのまま残す＝この散歩のフィードバックは次のサンプルから普通に続く。
            // 一度の失敗で散歩まるごと黙らせない（土台が作れなかった場合＝判定器が
            // null のときだけ、その散歩は最初から黙る。walkStarted 参照）。
        }
    }

    /**
     * 1件ぶんの記録と振動。
     *
     * 記録は無条件（レート制限がかかるのは振動だけ＝振り返りには全部出る。[WalkEvent] のKDoc）。
     * 種別で扱いを変えない：図鑑の出現も道の成長も「何かが起きた」の印1回でしかない
     * （design.md §3「それ以上の情報は出さない」）。
     */
    private fun publish(event: WalkEvent) {
        eventBus.publish(event)

        val decision = budget.eventOccurred(event.timestampMs)
        // 残数は振動の前に減らす。鳴らす側が失敗したときに「消費しなかった」ことにすると、
        // 壊れた端末で残数が減らないまま毎回試し続けることになる。
        budget = decision.budget
        if (decision.shouldVibrate) haptics.vibrateOnce()
    }
}
