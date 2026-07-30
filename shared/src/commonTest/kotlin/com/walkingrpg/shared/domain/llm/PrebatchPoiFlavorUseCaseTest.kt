package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.data.MutableClock
import com.walkingrpg.shared.domain.osm.Poi
import com.walkingrpg.shared.domain.osm.PoiKind
import com.walkingrpg.shared.domain.setup.LlmFormat
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 地点フレーバーの事前バッチ（[PrebatchPoiFlavorUseCase]）。
 *
 * 見たいのは5つ：
 * - **キャッシュにあるものは二度生成しない**（＝二度課金しない）
 * - 従量課金の回線・未設定では1件も投げない
 * - 件数上限を守る（残りは次回へ持ち越す）
 * - 読めない応答は**保存しない**（＝定型文で凌いで次回リトライ）
 * - 設定・通信の失敗は run ごと打ち切る
 */
class PrebatchPoiFlavorUseCaseTest {

    private val clock = MutableClock(NOW_MS)
    private val cache = FakeLlmCacheRepository()
    private val master = PoiOnlyOsmMasterRepository()

    private fun useCase(
        client: LlmClient = FakeLlmClient(),
        settings: LlmSetupRepository = LlmSetupRepository(),
        networkStatus: NetworkStatus = FakeNetworkStatus(unmetered = true),
        config: LlmGenerationConfig = LlmGenerationConfig.DEFAULT,
    ) = PrebatchPoiFlavorUseCase(
        osmMasterRepository = master,
        cacheRepository = cache,
        setupRepository = settings,
        clients = FakeLlmClientSelector(client),
        networkStatus = networkStatus,
        clock = clock,
        config = config,
    )

    private fun cacheKeyOf(poi: Poi) =
        llmCacheKey(LlmTaskKind.POI_FLAVOR, poiFlavorLogicalKey(poi.id))

    private fun promptHashOf(poi: Poi, config: LlmGenerationConfig = LlmGenerationConfig.DEFAULT) =
        PoiFlavorPromptBuilder
            .request(PoiFlavorFacts.of(poi), config.poiFlavorMaxTokens)
            .promptHash()

    @Test
    fun 未生成の地点だけ生成して保存する() = runTest {
        val park = testPoi("node/1", PoiKind.PARK, name = "テスト公園")
        val water = testPoi("node/2", PoiKind.WATER)
        master.pois = listOf(park, water)
        val client = FakeLlmClient(responses = listOf("""{"flavor": "一。"}""", """{"flavor": "二。"}"""))

        val outcome = useCase(client).drain()

        assertEquals(2, outcome.generated)
        assertEquals(0, outcome.failed)
        assertEquals(2, client.requests.size)
        assertEquals(
            mapOf(cacheKeyOf(park) to "一。", cacheKeyOf(water) to "二。"),
            cache.rows().mapValues { it.value.text },
        )
        assertEquals(NOW_MS, cache.saved.first().createdAtMs)
        assertEquals(LlmTaskKind.POI_FLAVOR, cache.saved.first().kind)
    }

    @Test
    fun キャッシュにあれば再生成しない() = runTest {
        val poi = testPoi("node/1")
        master.pois = listOf(poi)
        cache.save(
            LlmCacheEntry(
                cacheKey = cacheKeyOf(poi),
                kind = LlmTaskKind.POI_FLAVOR,
                promptHash = promptHashOf(poi),
                text = "既にある一言。",
                createdAtMs = 1L,
            ),
        )
        val client = FakeLlmClient()

        val outcome = useCase(client).drain()

        assertEquals(0, outcome.generated)
        assertEquals(1, outcome.cached)
        assertEquals(0, client.requests.size, "有料APIを二度叩かない")
    }

    @Test
    fun 二度流しても同じ状態に収束する() = runTest {
        master.pois = listOf(testPoi("node/1"), testPoi("node/2"))
        val client = FakeLlmClient()
        val useCase = useCase(client)

        useCase.drain()
        val second = useCase.drain()

        assertEquals(2, client.requests.size, "2回目は1件も投げない")
        assertEquals(0, second.generated)
        assertEquals(2, second.cached)
        assertEquals(2, cache.rows().size)
    }

    @Test
    fun プロンプトが変わっていれば作り直す() = runTest {
        val poi = testPoi("node/1")
        master.pois = listOf(poi)
        cache.save(
            LlmCacheEntry(
                cacheKey = cacheKeyOf(poi),
                kind = LlmTaskKind.POI_FLAVOR,
                promptHash = "古いプロンプトの指紋",
                text = "古い方針で書かれた一言。",
                createdAtMs = 1L,
            ),
        )
        val client = FakeLlmClient(responses = listOf("""{"flavor": "新しい一言。"}"""))

        val outcome = useCase(client).drain()

        assertEquals(1, outcome.generated)
        assertEquals("新しい一言。", cache.rows().getValue(cacheKeyOf(poi)).text)
        assertEquals(promptHashOf(poi), cache.rows().getValue(cacheKeyOf(poi)).promptHash)
        assertEquals(1, cache.rows().size, "作り直しは置き換え（古い行を残さない）")
    }

    @Test
    fun 従量課金の回線では一件も投げない() = runTest {
        master.pois = listOf(testPoi("node/1"))
        val client = FakeLlmClient()
        val settings = LlmSetupRepository()

        val outcome = useCase(
            client = client,
            settings = settings,
            networkStatus = FakeNetworkStatus(unmetered = false),
        ).drain()

        assertEquals(LlmSkipReason.METERED_NETWORK, outcome.skipReason)
        assertEquals(0, client.requests.size)
        // 投げないと決まっているのにマスタもセキュアストレージも触らない
        assertEquals(0, settings.loadCount)
    }

    @Test
    fun 回線を問わず走らせる設定にもできる() = runTest {
        master.pois = listOf(testPoi("node/1"))
        val client = FakeLlmClient()

        val outcome = useCase(
            client = client,
            networkStatus = FakeNetworkStatus(unmetered = false),
            config = LlmGenerationConfig.DEFAULT.copy(requireUnmeteredNetwork = false),
        ).drain()

        assertEquals(1, outcome.generated)
    }

    @Test
    fun 接続設定が無ければ生成しない() = runTest {
        master.pois = listOf(testPoi("node/1"))
        val client = FakeLlmClient()

        val outcome = useCase(client, settings = LlmSetupRepository(settings = null)).drain()

        assertEquals(LlmSkipReason.NOT_CONFIGURED, outcome.skipReason)
        assertEquals(0, client.requests.size)
    }

    @Test
    fun 生成する相手が居なければ設定も読まない() = runTest {
        master.pois = listOf(testPoi("node/1"))
        cache.save(
            LlmCacheEntry(
                cacheKey = cacheKeyOf(master.pois.single()),
                kind = LlmTaskKind.POI_FLAVOR,
                promptHash = promptHashOf(master.pois.single()),
                text = "ある。",
                createdAtMs = 1L,
            ),
        )
        val settings = LlmSetupRepository()

        useCase(settings = settings).drain()

        // 全部生成済みなのにセキュアストレージ（APIキー）を触らない
        assertEquals(0, settings.loadCount)
    }

    @Test
    fun POIマスタが空なら何もしない() = runTest {
        val client = FakeLlmClient()

        val outcome = useCase(client).drain()

        assertEquals(LlmSkipReason.NOTHING_TO_GENERATE, outcome.skipReason)
        assertEquals(0, client.requests.size)
    }

    @Test
    fun 一回の上限を超えたぶんは次回へ持ち越す() = runTest {
        master.pois = (1..5).map { testPoi("node/$it") }
        val client = FakeLlmClient()

        val outcome = useCase(
            client = client,
            config = LlmGenerationConfig.DEFAULT.copy(maxGenerationsPerRun = 2),
        ).drain()

        assertEquals(2, outcome.generated)
        assertEquals(3, outcome.remaining)
        assertEquals(2, client.requests.size)
        assertEquals(2, cache.rows().size, "残りは行を作らない＝次回の対象として残る")
    }

    @Test
    fun 読めない応答は保存しない() = runTest {
        master.pois = listOf(testPoi("node/1"), testPoi("node/2"))
        // JSONになっていない応答（＝定型文で凌ぐしかない）
        val client = FakeLlmClient(responses = listOf("木陰が涼しい。"))

        val outcome = useCase(client).drain()

        assertEquals(0, outcome.generated)
        assertEquals(2, outcome.failed, "形の問題は次の地点も試す")
        assertTrue(cache.rows().isEmpty(), "崩れた文章をキャッシュに焼き付けない")
    }

    @Test
    fun 形の失敗が続けば見切る() = runTest {
        master.pois = (1..10).map { testPoi("node/$it") }
        val client = FakeLlmClient(responses = listOf("読めない応答"))

        val outcome = useCase(
            client = client,
            config = LlmGenerationConfig.DEFAULT.copy(maxFailuresPerRun = 3),
        ).drain()

        assertEquals(3, outcome.failed)
        assertEquals(7, outcome.remaining)
        assertEquals(3, client.requests.size)
    }

    @Test
    fun キーが通らなければその場で打ち切る() = runTest {
        master.pois = (1..5).map { testPoi("node/$it") }
        val client = FakeLlmClient(
            failure = LlmUnavailableException(
                reason = LlmFailureKind.UNAUTHORIZED,
                message = LlmFailureKind.UNAUTHORIZED.message,
            ),
        )

        val outcome = useCase(client).drain()

        assertEquals(1, client.requests.size, "次の地点でも同じ結果なので投げない")
        assertEquals(1, outcome.failed)
        assertEquals(4, outcome.remaining)
        assertTrue(cache.rows().isEmpty())
    }

    @Test
    fun 圏外でも例外を投げずに数として返す() = runTest {
        master.pois = listOf(testPoi("node/1"))
        val client = FakeLlmClient(
            failure = LlmUnavailableException(
                reason = LlmFailureKind.NETWORK,
                message = LlmFailureKind.NETWORK.message,
            ),
        )

        val outcome = useCase(client).drain()

        assertEquals(0, outcome.generated)
        assertEquals(1, outcome.failed)
    }

    @Test
    fun 設定されたフォーマットの実装に投げる() = runTest {
        master.pois = listOf(testPoi("node/1"))
        val anthropic = FakeLlmClient(format = LlmFormat.ANTHROPIC)
        val openAi = FakeLlmClient(format = LlmFormat.OPENAI)
        val useCase = PrebatchPoiFlavorUseCase(
            osmMasterRepository = master,
            cacheRepository = cache,
            setupRepository = LlmSetupRepository(testLlmSettings(LlmFormat.OPENAI)),
            clients = FakeLlmClientSelector(
                mapOf(LlmFormat.ANTHROPIC to anthropic, LlmFormat.OPENAI to openAi),
            ),
            networkStatus = FakeNetworkStatus(),
            clock = clock,
        )

        useCase.drain()

        assertEquals(0, anthropic.requests.size)
        assertEquals(1, openAi.requests.size)
        assertEquals(
            "dummy-key-for-test",
            openAi.settings.single().apiKey,
            "APIキーは選んだ実装にだけ渡す",
        )
    }

    @Test
    fun 出力上限は設定値を使う() = runTest {
        master.pois = listOf(testPoi("node/1"))
        val client = FakeLlmClient()

        useCase(client, config = LlmGenerationConfig.DEFAULT.copy(poiFlavorMaxTokens = 123)).drain()

        assertEquals(123, client.requests.single().maxTokens)
        assertTrue(client.requests.single().allowRetry, "家の中で走るので粘ってよい")
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
    }
}
