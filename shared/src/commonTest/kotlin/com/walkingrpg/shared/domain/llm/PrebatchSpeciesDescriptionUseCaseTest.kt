package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.data.MutableClock
import com.walkingrpg.shared.domain.codex.CodexCategory
import com.walkingrpg.shared.domain.codex.Species
import com.walkingrpg.shared.domain.codex.SpeciesCatalog
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 図鑑の記述文の事前バッチ（[PrebatchSpeciesDescriptionUseCase]）。
 *
 * `PrebatchPoiFlavorUseCaseTest` と同じ観点を見る：
 * - **キャッシュにあるものは二度生成しない**（＝二度課金しない）
 * - 従量課金の回線・未設定では1件も投げない
 * - 読めない応答は**保存しない**（＝定型文で凌いで次回リトライ）
 * - 設定・通信の失敗は run ごと打ち切る
 *
 * 加えて、材料が手書きのカタログであること（種が有限なので1回で埋まる）を見る。
 */
class PrebatchSpeciesDescriptionUseCaseTest {

    private val clock = MutableClock(NOW_MS)
    private val cache = FakeLlmCacheRepository()

    private val skimmer = Species(
        id = "test_skimmer",
        name = "テストトンボ",
        category = CodexCategory.WATER,
        requiredVisitCount = 3,
        foreshadowText = "何かが行き来している。",
    )
    private val owl = Species(
        id = "test_owl",
        name = "テストフクロウ",
        category = CodexCategory.SHRINE,
        requiredVisitCount = 10,
        foreshadowText = "羽根が一枚。",
    )

    private fun useCase(
        client: LlmClient = FakeLlmClient(responses = listOf("""{"description": "生成された記述。"}""")),
        settings: LlmSetupRepository = LlmSetupRepository(),
        networkStatus: NetworkStatus = FakeNetworkStatus(unmetered = true),
        config: LlmGenerationConfig = LlmGenerationConfig.DEFAULT,
        species: List<Species> = listOf(skimmer, owl),
    ) = PrebatchSpeciesDescriptionUseCase(
        cacheRepository = cache,
        setupRepository = settings,
        clients = FakeLlmClientSelector(client),
        networkStatus = networkStatus,
        clock = clock,
        config = config,
        species = species,
    )

    @Test
    fun 未生成の種だけ生成して保存する() = runTest {
        val client = FakeLlmClient(
            responses = listOf("""{"description": "一。"}""", """{"description": "二。"}"""),
        )

        val outcome = useCase(client).drain()

        assertEquals(LlmTaskKind.SPECIES_DESCRIPTION, outcome.taskKind)
        assertEquals(2, outcome.generated)
        assertEquals(0, outcome.failed)
        assertEquals(
            mapOf(
                descriptionCacheKey(skimmer.id) to "一。",
                descriptionCacheKey(owl.id) to "二。",
            ),
            cache.rows().mapValues { it.value.text },
        )
        assertEquals(NOW_MS, cache.saved.first().createdAtMs)
        assertEquals(LlmTaskKind.SPECIES_DESCRIPTION, cache.saved.first().kind)
    }

    @Test
    fun キャッシュにあれば再生成しない() = runTest {
        cache.save(
            LlmCacheEntry(
                cacheKey = descriptionCacheKey(skimmer.id),
                kind = LlmTaskKind.SPECIES_DESCRIPTION,
                promptHash = speciesPromptHash(skimmer, LlmGenerationConfig.DEFAULT),
                text = "既にある記述。",
                createdAtMs = 1L,
            ),
        )
        val client = FakeLlmClient()

        val outcome = useCase(client, species = listOf(skimmer)).drain()

        assertEquals(0, outcome.generated)
        assertEquals(1, outcome.cached)
        assertEquals(0, client.requests.size, "有料APIを二度叩かない")
    }

    @Test
    fun プロンプトが変わっていれば作り直す() = runTest {
        cache.save(
            LlmCacheEntry(
                cacheKey = descriptionCacheKey(skimmer.id),
                kind = LlmTaskKind.SPECIES_DESCRIPTION,
                promptHash = "古いプロンプトの指紋",
                text = "古い記述。",
                createdAtMs = 1L,
            ),
        )
        val client = FakeLlmClient(responses = listOf("""{"description": "新しい記述。"}"""))

        val outcome = useCase(client, species = listOf(skimmer)).drain()

        assertEquals(1, outcome.generated)
        assertEquals("新しい記述。", cache.rows().getValue(descriptionCacheKey(skimmer.id)).text)
    }

    @Test
    fun 従量課金の回線では1件も投げない() = runTest {
        val client = FakeLlmClient()

        val outcome = useCase(client, networkStatus = FakeNetworkStatus(unmetered = false)).drain()

        assertEquals(LlmSkipReason.METERED_NETWORK, outcome.skipReason)
        assertEquals(0, client.requests.size)
    }

    @Test
    fun 接続設定が無ければ1件も投げない() = runTest {
        val client = FakeLlmClient()

        val outcome = useCase(client, settings = LlmSetupRepository(settings = null)).drain()

        assertEquals(LlmSkipReason.NOT_CONFIGURED, outcome.skipReason)
        assertEquals(0, client.requests.size)
    }

    @Test
    fun 対象の種が無ければ何もしない() = runTest {
        val outcome = useCase(species = emptyList()).drain()

        assertEquals(LlmSkipReason.NOTHING_TO_GENERATE, outcome.skipReason)
    }

    @Test
    fun 読めない応答は保存しない() = runTest {
        // 崩れた文章をキャッシュに焼き付けると、二度と直らないまま図鑑に出る
        val client = FakeLlmClient(responses = listOf("説明の文章だけが返ってきた"))

        val outcome = useCase(client, species = listOf(skimmer)).drain()

        assertEquals(0, outcome.generated)
        assertEquals(1, outcome.failed)
        assertTrue(cache.rows().isEmpty(), "次回のドレインでまた未生成として拾われる")
    }

    @Test
    fun 通信の失敗はrunごと打ち切る() = runTest {
        val client = FakeLlmClient(
            failure = LlmUnavailableException(
                reason = LlmFailureKind.UNAUTHORIZED,
                message = "キーが無効",
            ),
        )

        val outcome = useCase(client).drain()

        assertEquals(1, client.requests.size, "次の種でも同じ結果なので投げない")
        assertEquals(1, outcome.failed)
        assertEquals(1, outcome.remaining, "残りは次回へ持ち越す")
    }

    @Test
    fun 件数上限を守る() = runTest {
        val client = FakeLlmClient(responses = listOf("""{"description": "一。"}"""))

        val outcome = useCase(client, config = LlmGenerationConfig(maxGenerationsPerRun = 1)).drain()

        assertEquals(1, outcome.generated)
        assertEquals(1, outcome.remaining)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun 本番のカタログは1回のドレインで埋まる() = runTest {
        // 種は有限（十数件）なので、既定の上限40件に収まる＝初回で全部揃う
        assertTrue(
            SpeciesCatalog.ALL.size <= LlmGenerationConfig.DEFAULT_MAX_GENERATIONS_PER_RUN,
            "カタログが上限を超えた（${SpeciesCatalog.ALL.size}件）",
        )

        val client = FakeLlmClient(responses = listOf("""{"description": "記述。"}"""))
        val outcome = useCase(client, species = SpeciesCatalog.ALL).drain()

        assertEquals(SpeciesCatalog.ALL.size, outcome.generated)
        assertEquals(0, outcome.remaining)
    }

    @Test
    fun 出力上限は設定から渡る() = runTest {
        val client = FakeLlmClient(responses = listOf("""{"description": "記述。"}"""))

        useCase(
            client,
            config = LlmGenerationConfig(speciesDescriptionMaxTokens = 123),
            species = listOf(skimmer),
        ).drain()

        assertEquals(123, client.requests.single().maxTokens)
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
    }
}
