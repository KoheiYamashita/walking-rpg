package com.walkingrpg.shared.domain.growth

import com.walkingrpg.shared.domain.matching.PassageRepository

/**
 * `passage` 全件から `way_growth` を作り直す（architecture.md §4「導出」）。
 *
 * **全導出の再計算コマンド**。セッション単位ではなく毎回全件を引き直すのは、
 * 1本の道が複数のセッションにまたがって育つから＝「今回の散歩ぶんだけ足す」にすると、
 * 過去のセッションを消したり閾値を変えたりしたときに古い値が残る。
 * 全件でも集計はway ID単位の `COUNT` 1本（Passage.sq `selectPassCountsByWay`）で、
 * MVP対象圏は歩行対象の道が約220本（design.md §9）。何年ぶんの通過でも一瞬で終わる。
 *
 * 何度呼んでも同じ状態になる（[WayGrowthCalculator] が純関数で、保存が全削除→挿入なので）。
 * `way_growth` を消してから呼んでも結果は同じ＝いつでも捨ててよいキャッシュであることの担保。
 */
class RecomputeWayGrowthUseCase(
    private val passageRepository: PassageRepository,
    private val wayGrowthRepository: WayGrowthRepository,
    private val config: GrowthConfig = GrowthConfig.DEFAULT,
) {
    /** 作り直した成長をway ID順で返す（呼び出し側が段階の変化を見せられるように）。 */
    suspend operator fun invoke(): List<WayGrowth> {
        val passCounts = passageRepository.passCountsByWay()
        val growths = WayGrowthCalculator.growths(passCounts, config)
        // 0件でも保存する：通過が全部消えたなら成長も消えるのが「作り直し」の意味。
        wayGrowthRepository.replaceAllGrowths(growths)
        return growths
    }
}
