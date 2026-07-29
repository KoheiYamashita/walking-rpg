package com.walkingrpg.shared.data.feedback

import com.walkingrpg.shared.domain.feedback.LiveGrowthEstimator
import com.walkingrpg.shared.domain.feedback.VibrationBudget
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
 * 振動の回数制限は [VibrationBudget] という純関数側にあり、ここは
 * 「読んで・畳んで・鳴らす」だけ（`WalkRecorderImpl` と `HomeArrivalDetector` の関係と同じ）。
 *
 * ## イベント種別の増やし方（#13 の図鑑予兆はここに乗る）
 *
 * 判定器をもう1つ足して、[sampleRecorded] で同じように畳み、出てきたイベントを
 * [publish] に渡すだけでよい。振動・レート制限・画面の断片は種別を知らないので、
 * そのまま効く。
 */
internal class WalkFeedbackImpl(
    private val osmMasterRepository: OsmMasterRepository,
    private val passageRepository: PassageRepository,
    private val eventBus: WalkEventBus,
    private val haptics: Haptics,
    private val matchingConfig: MapMatchingConfig = MapMatchingConfig.DEFAULT,
    private val growthConfig: GrowthConfig = GrowthConfig.DEFAULT,
    private val feedbackConfig: WalkFeedbackConfig = WalkFeedbackConfig.DEFAULT,
) : WalkFeedback {

    /**
     * 判定の土台が作れなかった散歩では `null` のまま＝フィードバックだけが黙る。
     * 記録（`location_sample`）は無関係に続き、帰宅後の再計算も普通に走る。
     */
    private var estimator: LiveGrowthEstimator? = null
    private var budget = VibrationBudget(feedbackConfig)

    /**
     * wayマスタと現在の通過回数を散歩の頭で1回だけ読む。
     * 測位1件ごとにDBを叩かないのが目的（`WalkRecorderImpl` が自宅を頭で1回だけ読むのと同じ）。
     *
     * 読めなくても散歩は始める。マスタ未取り込み・DBエラーはどちらも
     * 「振動しない散歩」で済ませてよく、記録を止める理由にはならない。
     */
    override suspend fun walkStarted(sessionId: Long) {
        budget = VibrationBudget(feedbackConfig)
        estimator = try {
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
    }

    override suspend fun sampleRecorded(sample: LocationSample) {
        val current = estimator ?: return
        val update = current.sampleRecorded(sample)
        estimator = update.estimator

        val event = update.event ?: return
        // 記録は無条件（レート制限がかかるのは振動だけ＝振り返りには全部出る）。
        eventBus.publish(event)

        val decision = budget.eventOccurred(event.timestampMs)
        budget = decision.budget
        if (decision.shouldVibrate) haptics.vibrateOnce()
    }
}
