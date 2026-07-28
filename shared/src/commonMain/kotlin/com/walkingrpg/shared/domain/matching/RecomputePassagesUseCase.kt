package com.walkingrpg.shared.domain.matching

import com.walkingrpg.shared.domain.osm.OsmMasterRepository
import com.walkingrpg.shared.domain.walk.WalkSessionRepository

/**
 * 1セッションぶんの `passage` を作り直す（architecture.md §5「帰宅後」）。
 *
 * 生サンプルは無加工で残っている（issue #7）ので、閾値やアルゴリズムを変えたあとに
 * これを流し直せば結果が置き換わる。何度呼んでも同じ状態になる（[MapMatcher] が
 * 純関数で、保存が全削除→挿入なので）。
 *
 * 呼び出し側（散歩終了時・設定変更後の再計算）は #9 / #10 で結線する。
 */
class RecomputePassagesUseCase(
    private val sessionRepository: WalkSessionRepository,
    private val osmMasterRepository: OsmMasterRepository,
    private val passageRepository: PassageRepository,
    private val config: MapMatchingConfig = MapMatchingConfig.DEFAULT,
) {
    /** 作り直した通過を返す（呼び出し側が件数を見せられるように）。 */
    suspend operator fun invoke(sessionId: Long): List<Passage> {
        val samples = sessionRepository.samples(sessionId)
        val ways = osmMasterRepository.ways()
        val passages = MapMatcher.match(
            sessionId = sessionId,
            samples = samples,
            ways = ways,
            config = config,
        )
        // 通過が0件でも保存する：前回の結果を残さないことが再計算の意味。
        passageRepository.replaceSessionPassages(sessionId, passages)
        return passages
    }
}
