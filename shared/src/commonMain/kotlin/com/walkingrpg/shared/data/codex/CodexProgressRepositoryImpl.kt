package com.walkingrpg.shared.data.codex

import com.walkingrpg.shared.data.db.WalkingRpgDatabase
import com.walkingrpg.shared.domain.codex.CodexProgress
import com.walkingrpg.shared.domain.codex.CodexProgressRepository
import com.walkingrpg.shared.domain.codex.ForeshadowStage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [CodexProgressRepository] のSQLDelight実装。
 *
 * 保存は**全削除→挿入を1トランザクション**で行う（CodexProgress.sq）。
 * 途中で失敗しても直前の状態のまま残る＝「半分だけ新しい閾値で計算された図鑑」は残らない。
 */
internal class CodexProgressRepositoryImpl(
    private val database: WalkingRpgDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : CodexProgressRepository {

    private val progresses get() = database.codexProgressQueries

    override suspend fun replaceAllProgresses(
        progresses: List<CodexProgress>,
    ): Unit = withContext(dispatcher) {
        database.transaction {
            this@CodexProgressRepositoryImpl.progresses.deleteAllCodexProgress()
            progresses.forEach { progress ->
                this@CodexProgressRepositoryImpl.progresses.insertCodexProgress(
                    species_id = progress.speciesId,
                    visit_count = progress.visitCount.toLong(),
                    foreshadow_stage = progress.foreshadowStage.level.toLong(),
                    discovered_at = progress.discoveredAtMs,
                )
            }
        }
    }

    override suspend fun progresses(): List<CodexProgress> = withContext(dispatcher) {
        progresses.selectAllCodexProgress().executeAsList().mapNotNull { row ->
            toCodexProgress(row.species_id, row.visit_count, row.foreshadow_stage, row.discovered_at)
        }
    }

    override suspend fun progress(speciesId: String): CodexProgress? = withContext(dispatcher) {
        progresses.selectCodexProgress(speciesId).executeAsOneOrNull()?.let { row ->
            toCodexProgress(row.species_id, row.visit_count, row.foreshadow_stage, row.discovered_at)
        }
    }
}

/**
 * 読めない行（訪問0回・発見済みなのに予兆が立っている行）は**無かったことにする**。
 *
 * `codex_progress` はいつ捨ててもよい導出キャッシュ（CodexProgress.sq）なので、
 * 壊れた行に合わせて表示側で例外を捌くより、次の再計算で正しい行に入れ替わるのを待つ方がよい
 * （`WayGrowthRepositoryImpl` と同じ方針）。真実は `passage` にあるので、これで失われるものは無い。
 *
 * 知らない予兆の段は [ForeshadowStage.NONE] に丸める（段を減らしたあとの古い行）。
 * 発見済みの行では予兆を必ず落とす：[CodexProgress] の不変条件に反する行を読んでも
 * 例外にせず、「発見済み」という強い情報の方を残す。
 */
private fun toCodexProgress(
    speciesId: String,
    visitCount: Long,
    foreshadowStageLevel: Long,
    discoveredAtMs: Long?,
): CodexProgress? {
    if (visitCount < 1L) return null
    return CodexProgress(
        speciesId = speciesId,
        visitCount = visitCount.toInt(),
        foreshadowStage = if (discoveredAtMs != null) {
            ForeshadowStage.NONE
        } else {
            ForeshadowStage.fromLevel(foreshadowStageLevel)
        },
        discoveredAtMs = discoveredAtMs,
    )
}
