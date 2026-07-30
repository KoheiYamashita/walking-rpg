package com.walkingrpg.app.ui.codex

import com.walkingrpg.shared.domain.codex.CodexCategory
import com.walkingrpg.shared.domain.codex.CodexProgress
import com.walkingrpg.shared.domain.codex.CodexProgressRepository
import com.walkingrpg.shared.domain.codex.ForeshadowStage
import com.walkingrpg.shared.domain.codex.GetCodexUseCase
import com.walkingrpg.shared.domain.codex.ObserveCodexUpdatesUseCase
import com.walkingrpg.shared.domain.codex.RecentCodexRepository
import com.walkingrpg.shared.domain.codex.Species
import com.walkingrpg.shared.domain.llm.GetSpeciesDescriptionUseCase
import com.walkingrpg.shared.domain.llm.LlmCacheEntry
import com.walkingrpg.shared.domain.llm.LlmCacheRepository
import com.walkingrpg.shared.domain.llm.LlmTaskKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 図鑑画面の状態の組み立て（issue #13）。
 *
 * 見たいのは2つ：
 * - 散歩から帰って再計算が走ったら、開いたままでも発見が乗ること
 * - **未発見の種の中身が UiState に入らないこと**（画面のバグ1つで漏れないための壁）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CodexViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        // viewModelScope は Dispatchers.Main を使うのでテスト用に差し替える
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val nearSpecies = Species(
        id = "water_near",
        name = "テストトンボ",
        category = CodexCategory.WATER,
        requiredVisitCount = 3,
        foreshadowText = "水面の上を何かが行き来している。",
    )
    private val farSpecies = Species(
        id = "water_far",
        name = "テストカワセミ",
        category = CodexCategory.WATER,
        requiredVisitCount = 10,
        foreshadowText = "青い羽根が落ちている。",
    )

    private class FakeCodexProgressRepository : CodexProgressRepository {
        var progresses: List<CodexProgress> = emptyList()

        /** 図鑑が何回組み立て直されたか（読み直しが走ったことの目印）。 */
        var readCount = 0
            private set

        override suspend fun replaceAllProgresses(progresses: List<CodexProgress>) {
            this.progresses = progresses
        }

        override suspend fun progresses(): List<CodexProgress> {
            readCount++
            return progresses
        }

        override suspend fun progress(speciesId: String): CodexProgress? =
            progresses.firstOrNull { it.speciesId == speciesId }
    }

    /** 本実装（`InMemoryRecentCodexRepository`）と同じく、同じ集合でも毎回流す。 */
    private class FakeRecentCodexRepository : RecentCodexRepository {
        private val _updates = MutableSharedFlow<Set<String>>(
            replay = 1,
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        override var discoveredSpeciesIds: Set<String> = emptySet()
            private set

        override val updates: Flow<Set<String>> = _updates.asSharedFlow()

        init {
            _updates.tryEmit(emptySet())
        }

        override fun record(speciesIds: Set<String>) {
            discoveredSpeciesIds = speciesIds
            _updates.tryEmit(speciesIds)
        }
    }

    /** 記述文は「未生成なら定型文」で足りるので、キャッシュは常に空でよい。 */
    private class EmptyLlmCacheRepository : LlmCacheRepository {
        override suspend fun entry(cacheKey: String): LlmCacheEntry? = null
        override suspend fun entries(kind: LlmTaskKind): Map<String, LlmCacheEntry> = emptyMap()
        override suspend fun save(entry: LlmCacheEntry) = Unit
    }

    private class Fixture {
        val codex = FakeCodexProgressRepository()
        val recentCodex = FakeRecentCodexRepository()
    }

    private fun viewModel(fixture: Fixture, catalog: List<Species>) = CodexViewModel(
        getCodex = GetCodexUseCase(
            codexProgressRepository = fixture.codex,
            getSpeciesDescription = GetSpeciesDescriptionUseCase(EmptyLlmCacheRepository()),
            recentCodexRepository = fixture.recentCodex,
            catalog = catalog,
        ),
        observeCodexUpdates = ObserveCodexUpdatesUseCase(fixture.recentCodex),
    )

    @Test
    fun 購読開始で図鑑が読み込まれる() = runTest(dispatcher) {
        val fixture = Fixture()
        val viewModel = viewModel(fixture, listOf(nearSpecies, farSpecies))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals(2, viewModel.uiState.value.codex.totalCount)
        assertEquals(1, fixture.codex.readCount)
    }

    @Test
    fun 散歩後の再計算で図鑑を開いたまま発見が乗る() = runTest(dispatcher) {
        val fixture = Fixture()
        val viewModel = viewModel(fixture, listOf(nearSpecies, farSpecies))
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.codex.discoveredCount)

        // 散歩から帰ってきた（RecomputeAfterWalkUseCase 相当）
        fixture.codex.progresses = listOf(
            CodexProgress(
                speciesId = nearSpecies.id,
                visitCount = 3,
                foreshadowStage = ForeshadowStage.NONE,
                discoveredAtMs = 500L,
            ),
        )
        fixture.recentCodex.record(setOf(nearSpecies.id))
        advanceUntilIdle()

        val entry = viewModel.uiState.value.codex.sections
            .single { it.category == CodexCategory.WATER }
            .entries
            .single { it.species.id == nearSpecies.id }
        assertTrue(entry.isDiscovered)
        assertTrue(entry.isNewlyDiscovered, "今回出た種は強調される")
        assertEquals(1, viewModel.uiState.value.codex.discoveredCount)
    }

    @Test
    fun 発見が0件の散歩でも読み直す() = runTest(dispatcher) {
        // 予兆の段だけが進んだ散歩でも図鑑の見え方は変わる（RecentCodexRepository のKDoc）
        val fixture = Fixture()
        viewModel(fixture, listOf(nearSpecies))
        advanceUntilIdle()
        assertEquals(1, fixture.codex.readCount)

        fixture.recentCodex.record(emptySet())
        advanceUntilIdle()
        assertEquals(2, fixture.codex.readCount)

        fixture.recentCodex.record(emptySet())
        advanceUntilIdle()
        assertEquals(3, fixture.codex.readCount, "同じ集合が続いても読み直す")
    }

    @Test
    fun 未発見の種の記述文は状態に載らない() = runTest(dispatcher) {
        val fixture = Fixture()
        fixture.codex.progresses = listOf(
            CodexProgress(
                speciesId = farSpecies.id,
                visitCount = 8,
                foreshadowStage = ForeshadowStage.NEAR,
            ),
        )
        val viewModel = viewModel(fixture, listOf(nearSpecies, farSpecies))
        advanceUntilIdle()

        val entries = viewModel.uiState.value.codex.sections.flatMap { it.entries }
        entries.filterNot { it.isDiscovered }.forEach { entry ->
            assertNull(entry.description, "${entry.species.id} の記述文が載っている")
        }
        assertTrue(
            entries.single { it.species.id == farSpecies.id }.showsForeshadow,
            "予兆は出す（残り回数は数字で見せない代わり）",
        )
    }
}
