package com.walkingrpg.shared.domain.codex

import com.walkingrpg.shared.domain.llm.GetSpeciesDescriptionUseCase

/**
 * 図鑑1枚ぶんの表示内容を組み立てる（**通信しない**）。
 *
 * 材料は3つ：手書きのカタログ（[SpeciesCatalog]）・進捗（`codex_progress`）・
 * 記述文のキャッシュ（`llm_cache`）。どれもローカルなので、圏外でも図鑑は開く
 * （architecture.md §1「記録・成長・判定は通信なしで成立する」）。
 *
 * ## 数字を出さない
 * 返す [CodexEntry] に**訪問回数も残り回数も載せない**。design.md §4.4
 * 「残り回数は数字で見せず、予兆で匂わせる」の実装で、UI層に渡さなければ
 * 「デバッグのつもりで出した数字がそのまま残る」事故が起きない。
 * 出すのは「発見したか」「予兆が出ているか」の2値だけ。
 *
 * ## 未発見の種も並べる
 * 進捗の行が無い種（一度も近くを通っていない棚の種）も、シルエットとして並べる。
 * 図鑑は「あと何が残っているか」が見えないと集める対象にならないので、
 * 枠だけは最初から見せる（中身は名前も記述文も出さない）。
 */
class GetCodexUseCase(
    private val codexProgressRepository: CodexProgressRepository,
    private val getSpeciesDescription: GetSpeciesDescriptionUseCase,
    private val recentCodexRepository: RecentCodexRepository,
    /** 対象の種。差し替えられるのはテストのためで、本番は手書きのカタログ全部。 */
    private val catalog: List<Species> = SpeciesCatalog.ALL,
) {
    suspend operator fun invoke(): Codex {
        val progressBySpecies = codexProgressRepository.progresses().associateBy { it.speciesId }
        val newlyDiscovered = recentCodexRepository.discoveredSpeciesIds

        val entries = catalog.map { species ->
            val progress = progressBySpecies[species.id]
            val isDiscovered = progress?.isDiscovered == true
            CodexEntry(
                species = species,
                isDiscovered = isDiscovered,
                // 発見済みの記述文だけ引く。未発見の種の文章を読み込んでUiStateに載せると、
                // 画面のバグ1つで中身が漏れる（＝10回通う意味が消える）。
                description = if (isDiscovered) getSpeciesDescription(species).text else null,
                foreshadowStage = progress?.foreshadowStage ?: ForeshadowStage.NONE,
                discoveredAtMs = progress?.discoveredAtMs,
                isNewlyDiscovered = species.id in newlyDiscovered,
            )
        }

        return Codex(
            sections = CodexCategory.entries
                .map { category ->
                    CodexSection(
                        category = category,
                        entries = entries.filter { it.species.category == category },
                    )
                }
                // 素材が無い棚（対象圏に農地が無い等）は棚ごと出さない。
                // 空の棚を並べても「この街には無い」ことしか伝わらない
                .filter { it.entries.isNotEmpty() },
        )
    }
}

/** 図鑑1枚ぶん。 */
data class Codex(
    val sections: List<CodexSection> = emptyList(),
) {
    val discoveredCount: Int get() = sections.sumOf { section -> section.entries.count { it.isDiscovered } }
    val totalCount: Int get() = sections.sumOf { it.entries.size }
}

/** 棚1つぶん。 */
data class CodexSection(
    val category: CodexCategory,
    val entries: List<CodexEntry> = emptyList(),
) {
    val discoveredCount: Int get() = entries.count { it.isDiscovered }
}

/**
 * 図鑑の1マス。
 *
 * @param description 発見済みなら記述文（生成済み or 定型文）。未発見なら `null`。
 * @param foreshadowStage 予兆の段。未発見のときだけ意味がある（発見済みは必ず
 *  [ForeshadowStage.NONE]＝[CodexProgress] の不変条件）。
 * @param isNewlyDiscovered 直近の散歩で出たか（[RecentCodexRepository]）。
 *  余韻の強調用で、プロセスが死ねば消える。
 */
data class CodexEntry(
    val species: Species,
    val isDiscovered: Boolean,
    val description: String?,
    val foreshadowStage: ForeshadowStage = ForeshadowStage.NONE,
    val discoveredAtMs: Long? = null,
    val isNewlyDiscovered: Boolean = false,
) {
    /** 未発見で、かつ閾値が近い＝予兆の文章を出す状態か。 */
    val showsForeshadow: Boolean get() = !isDiscovered && foreshadowStage == ForeshadowStage.NEAR
}
