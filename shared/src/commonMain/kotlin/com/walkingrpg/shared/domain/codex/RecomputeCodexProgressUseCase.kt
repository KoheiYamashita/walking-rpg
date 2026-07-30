package com.walkingrpg.shared.domain.codex

import com.walkingrpg.shared.domain.matching.PassageRepository
import com.walkingrpg.shared.domain.osm.OsmMasterRepository

/**
 * `passage` 全件から `codex_progress` を作り直す（architecture.md §4「導出」）。
 *
 * `RecomputeWayGrowthUseCase` と同じ形：セッション単位ではなく毎回全件を引き直す。
 * 「今回の散歩ぶんだけ足す」にすると、過去のセッションを消したり閾値を変えたり
 * 対象圏を取り直したりしたときに古い値が残る。
 *
 * 集計は道×散歩単位の `GROUP BY` 1本（Passage.sq `selectSessionVisitsByWay`）で、
 * POIと道の空間結合（[PoiWayIndex]）もPOI数百件×way約220本（design.md §9）の
 * 純計算にすぎない。何年ぶんの通過でも一瞬で終わる。
 *
 * 何度呼んでも同じ状態になる（[CodexProgressCalculator] が純関数で、保存が全削除→挿入なので）。
 * `codex_progress` を消してから呼んでも結果は同じ＝いつでも捨ててよいキャッシュであることの担保。
 */
class RecomputeCodexProgressUseCase(
    private val passageRepository: PassageRepository,
    private val osmMasterRepository: OsmMasterRepository,
    private val codexProgressRepository: CodexProgressRepository,
    private val config: CodexConfig = CodexConfig.DEFAULT,
) {
    /** 作り直した進捗を種ID順で返す（呼び出し側が新規発見を見せられるように）。 */
    suspend operator fun invoke(): List<CodexProgress> {
        val index = PoiWayIndex.of(
            pois = osmMasterRepository.pois(),
            ways = osmMasterRepository.ways(),
            config = config,
        )
        val progresses = CodexProgressCalculator.progresses(
            index = index,
            sessionVisitsByWay = passageRepository.sessionVisitsByWay(),
            config = config,
        )
        // 0件でも保存する：通過やPOIが全部消えたなら進捗も消えるのが「作り直し」の意味。
        codexProgressRepository.replaceAllProgresses(progresses)
        return progresses
    }
}
