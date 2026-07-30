package com.walkingrpg.shared.domain.codex

import com.walkingrpg.shared.domain.FakeCodexProgressRepository
import com.walkingrpg.shared.domain.FakeRecentCodexRepository
import com.walkingrpg.shared.domain.llm.FakeLlmCacheRepository
import com.walkingrpg.shared.domain.llm.GetSpeciesDescriptionUseCase
import com.walkingrpg.shared.domain.llm.LlmCacheEntry
import com.walkingrpg.shared.domain.llm.LlmGenerationConfig
import com.walkingrpg.shared.domain.llm.LlmTaskKind
import com.walkingrpg.shared.domain.llm.SpeciesDescriptionFallback
import com.walkingrpg.shared.domain.llm.llmCacheKey
import com.walkingrpg.shared.domain.llm.speciesDescriptionLogicalKey
import com.walkingrpg.shared.domain.llm.speciesPromptHash
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 図鑑1枚ぶんの組み立て（[GetCodexUseCase]）。
 *
 * 見たいのは：
 * - 未発見の種も枠として並ぶ（あと何が残っているかは見える）
 * - **未発見の種の名前も記述文も出さない**（design.md §4.4「予兆で匂わせる」）
 * - 素材が無い棚は出さない
 * - 発見済みの記述文は生成済み→定型文の順で必ず出る（圏外でも開ける）
 */
class GetCodexUseCaseTest {

    private val nearSpecies = testSpecies("water_near", CodexCategory.WATER, requiredVisitCount = 3)
    private val farSpecies = testSpecies("water_far", CodexCategory.WATER, requiredVisitCount = 10)
    private val parkSpecies = testSpecies("park_one", CodexCategory.PARK, requiredVisitCount = 4)
    private val catalog = listOf(nearSpecies, farSpecies, parkSpecies)

    private val codex = FakeCodexProgressRepository()
    private val cache = FakeLlmCacheRepository()
    private val recentCodex = FakeRecentCodexRepository()

    private fun useCase(recent: FakeRecentCodexRepository = recentCodex) = GetCodexUseCase(
        codexProgressRepository = codex,
        getSpeciesDescription = GetSpeciesDescriptionUseCase(cache, LlmGenerationConfig.DEFAULT),
        recentCodexRepository = recent,
        catalog = catalog,
    )

    private suspend fun given(vararg progresses: CodexProgress) {
        codex.replaceAllProgresses(progresses.toList())
    }

    @Test
    fun 未発見の種も枠として並ぶ() = runTest {
        given()

        val result = useCase().invoke()

        assertEquals(3, result.totalCount)
        assertEquals(0, result.discoveredCount)
        assertTrue(result.sections.flatMap { it.entries }.none { it.isDiscovered })
    }

    @Test
    fun 未発見の種の記述文は読み込まない() = runTest {
        // UiStateに載せた時点で画面のバグ1つで中身が漏れる（10回通う意味が消える）
        given(
            CodexProgress(
                speciesId = farSpecies.id,
                visitCount = 8,
                foreshadowStage = ForeshadowStage.NEAR,
            ),
        )

        val entry = useCase().invoke()
            .sections.flatMap { it.entries }
            .single { it.species.id == farSpecies.id }

        assertNull(entry.description)
        assertTrue(entry.showsForeshadow, "予兆だけは出す")
    }

    @Test
    fun 発見済みなら生成済みの記述文が出る() = runTest {
        cache.save(
            LlmCacheEntry(
                cacheKey = llmCacheKey(
                    LlmTaskKind.SPECIES_DESCRIPTION,
                    speciesDescriptionLogicalKey(nearSpecies.id),
                ),
                kind = LlmTaskKind.SPECIES_DESCRIPTION,
                promptHash = promptHashOf(nearSpecies),
                text = "水の上を、まっすぐな線が横切る。",
                createdAtMs = 1L,
            ),
        )
        given(
            CodexProgress(
                speciesId = nearSpecies.id,
                visitCount = 3,
                foreshadowStage = ForeshadowStage.NONE,
                discoveredAtMs = 500L,
            ),
        )

        val entry = useCase().invoke()
            .sections.flatMap { it.entries }
            .single { it.species.id == nearSpecies.id }

        assertTrue(entry.isDiscovered)
        assertEquals("水の上を、まっすぐな線が横切る。", entry.description)
        assertEquals(500L, entry.discoveredAtMs)
    }

    @Test
    fun 生成できていなくても定型文で図鑑は開ける() = runTest {
        given(
            CodexProgress(
                speciesId = nearSpecies.id,
                visitCount = 3,
                foreshadowStage = ForeshadowStage.NONE,
                discoveredAtMs = 500L,
            ),
        )

        val entry = useCase().invoke()
            .sections.flatMap { it.entries }
            .single { it.species.id == nearSpecies.id }

        assertEquals(SpeciesDescriptionFallback.text(CodexCategory.WATER), entry.description)
    }

    @Test
    fun 素材が無い棚は出さない() = runTest {
        given()

        val categories = useCase().invoke().sections.map { it.category }

        // カタログに水辺と公園しか無いので、鉄道・農地・樹木・寺社・市街の棚は出ない
        assertEquals(listOf(CodexCategory.PARK, CodexCategory.WATER), categories)
    }

    @Test
    fun 直近の散歩で出た種には印が付く() = runTest {
        given(
            CodexProgress(
                speciesId = nearSpecies.id,
                visitCount = 3,
                foreshadowStage = ForeshadowStage.NONE,
                discoveredAtMs = 500L,
            ),
        )
        val recent = FakeRecentCodexRepository(initial = setOf(nearSpecies.id))

        val entries = useCase(recent).invoke().sections.flatMap { it.entries }

        assertTrue(entries.single { it.species.id == nearSpecies.id }.isNewlyDiscovered)
        assertTrue(entries.none { it.species.id == farSpecies.id && it.isNewlyDiscovered })
    }

    @Test
    fun 知らない種の行は無視される() = runTest {
        // カタログから種を外したあとの古い行（codex_progress は捨てて作り直せる）
        given(
            CodexProgress(
                speciesId = "retired_species",
                visitCount = 5,
                foreshadowStage = ForeshadowStage.NONE,
                discoveredAtMs = 500L,
            ),
        )

        val result = useCase().invoke()

        assertEquals(0, result.discoveredCount)
        assertEquals(3, result.totalCount, "並ぶのはカタログにある種だけ")
    }

    private fun promptHashOf(species: Species) =
        speciesPromptHash(species, LlmGenerationConfig.DEFAULT)
}
