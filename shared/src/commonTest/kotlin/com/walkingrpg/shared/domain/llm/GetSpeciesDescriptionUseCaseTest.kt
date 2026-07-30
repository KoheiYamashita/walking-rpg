package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.domain.codex.SpeciesCatalog
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 図鑑の記述文の読み出し（[GetSpeciesDescriptionUseCase]）。**通信しない**側。
 *
 * `GetPoiFlavorUseCaseTest` と同じ観点：キャッシュに載っていれば生成済み、
 * 無ければ定型文。どちらでも文章は必ず出る＝図鑑は圏外でも開ける。
 */
class GetSpeciesDescriptionUseCaseTest {

    private val cache = FakeLlmCacheRepository()
    private val useCase = GetSpeciesDescriptionUseCase(cache, LlmGenerationConfig.DEFAULT)

    private val kingfisher = SpeciesCatalog.species("water_kingfisher")!!

    private suspend fun cached(text: String, promptHash: String) {
        cache.save(
            LlmCacheEntry(
                cacheKey = descriptionCacheKey(kingfisher.id),
                kind = LlmTaskKind.SPECIES_DESCRIPTION,
                promptHash = promptHash,
                text = text,
                createdAtMs = 1L,
            ),
        )
    }

    @Test
    fun 生成済みの記述文を返す() = runTest {
        cached("水の上を、まっすぐな線が横切る。", speciesPromptHash(kingfisher, LlmGenerationConfig.DEFAULT))

        val description = useCase(kingfisher)

        assertEquals("水の上を、まっすぐな線が横切る。", description.text)
        assertTrue(description.isGenerated)
    }

    @Test
    fun 未生成なら定型文を返す() = runTest {
        val description = useCase(kingfisher)

        assertEquals(SpeciesDescriptionFallback.text(kingfisher.category), description.text)
        assertFalse(description.isGenerated)
    }

    @Test
    fun プロンプトが変わっている行は無いものとして扱う() = runTest {
        // 古い方針で書かれた文章を出し続けるより定型文のほうが安全
        cached("古い方針で書かれた記述。", "古いプロンプトの指紋")

        val description = useCase(kingfisher)

        assertEquals(SpeciesDescriptionFallback.text(kingfisher.category), description.text)
        assertFalse(description.isGenerated)
    }

    @Test
    fun どの種でも必ず文章が出る() = runTest {
        SpeciesCatalog.ALL.forEach { species ->
            assertTrue(useCase(species).text.isNotBlank(), "${species.id} の文章が空")
        }
    }
}
