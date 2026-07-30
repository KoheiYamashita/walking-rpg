package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.codex.Species

/**
 * 図鑑の記述文を1件取り出す（**通信しない**）。
 *
 * `GetPoiFlavorUseCase` と同じ形。キャッシュに載っていれば生成済みの文章を、
 * 載っていなければ定型文（[SpeciesDescriptionFallback]）を返す。どちらでも文章は必ず出る
 * ＝図鑑は通信ゼロでも開ける（architecture.md §1）。
 *
 * プロンプトが変わっている（[LlmCacheEntry.matches] が false）行も「無い」扱いにする。
 * 古い方針で書かれた文章を出し続けるより定型文のほうが安全で、
 * 次のドレイン（[PrebatchSpeciesDescriptionUseCase]）が作り直す。
 */
class GetSpeciesDescriptionUseCase(
    private val cacheRepository: LlmCacheRepository,
    private val config: LlmGenerationConfig = LlmGenerationConfig.DEFAULT,
) {
    suspend operator fun invoke(species: Species): SpeciesDescription {
        val entry = cacheRepository
            .entry(descriptionCacheKey(species.id))
            ?.takeIf { it.matches(speciesPromptHash(species, config)) }

        return if (entry == null) {
            SpeciesDescription(
                text = SpeciesDescriptionFallback.text(species.category),
                isGenerated = false,
            )
        } else {
            SpeciesDescription(text = entry.text, isGenerated = true)
        }
    }
}

/**
 * 図鑑の1ページに出す2〜3文。
 *
 * @param isGenerated 生成済みの文章か（`false` なら定型文）。画面では区別しないが、
 *  デバッグ表示（#20）と「どれだけ生成が追いついているか」の確認に使える。
 */
data class SpeciesDescription(
    val text: String,
    val isGenerated: Boolean,
)
