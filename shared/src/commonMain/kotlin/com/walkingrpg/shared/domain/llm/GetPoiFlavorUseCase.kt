package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.osm.Poi

/**
 * 地点フレーバーを1件取り出す（**通信しない**）。
 *
 * 散歩中に呼ばれる側の口。design.md §7「路上でLLMを待たせたら負け」なので、
 * ここでは生成を試みない：キャッシュに載っていれば生成済みの文章を、
 * 載っていなければ定型文（[PoiFlavorFallback]）を返す。どちらでも文章は必ず出る
 * ＝散歩は通信ゼロでも成立する（architecture.md §1）。
 *
 * プロンプトが変わっている（[LlmCacheEntry.matches] が false）行も「無い」扱いにする。
 * 古い方針で書かれた文章を出し続けるより定型文のほうが安全で、
 * 次のドレイン（[PrebatchPoiFlavorUseCase]）が作り直す。
 */
class GetPoiFlavorUseCase(
    private val cacheRepository: LlmCacheRepository,
    private val config: LlmGenerationConfig = LlmGenerationConfig.DEFAULT,
) {
    suspend operator fun invoke(poi: Poi): PoiFlavor {
        val facts = PoiFlavorFacts.of(poi)
        val promptHash = PoiFlavorPromptBuilder
            .request(facts, config.poiFlavorMaxTokens)
            .promptHash()
        val entry = cacheRepository
            .entry(llmCacheKey(LlmTaskKind.POI_FLAVOR, poiFlavorLogicalKey(poi.id)))
            ?.takeIf { it.matches(promptHash) }

        return if (entry == null) {
            PoiFlavor(text = PoiFlavorFallback.text(facts.kind), isGenerated = false)
        } else {
            PoiFlavor(text = entry.text, isGenerated = true)
        }
    }
}

/**
 * 表示する1〜2文。
 *
 * @param isGenerated 生成済みの文章か（`false` なら定型文）。画面では区別しないが、
 *  デバッグ表示（#20）と「どれだけ生成が追いついているか」の確認に使える。
 */
data class PoiFlavor(
    val text: String,
    val isGenerated: Boolean,
)
