package com.walkingrpg.shared.domain.llm

import com.walkingrpg.shared.data.FakeWalkSessionRepository
import com.walkingrpg.shared.data.MutableClock
import com.walkingrpg.shared.domain.FakeCodexProgressRepository
import com.walkingrpg.shared.domain.FakeOsmMasterRepository
import com.walkingrpg.shared.domain.FakePassageRepository
import com.walkingrpg.shared.domain.FakeRecentCodexRepository
import com.walkingrpg.shared.domain.codex.CodexCategory
import com.walkingrpg.shared.domain.codex.GetCodexUseCase
import com.walkingrpg.shared.domain.codex.testSpecies
import com.walkingrpg.shared.domain.review.FakeTimeOfDayResolver
import com.walkingrpg.shared.domain.review.GetWalkReviewUseCase
import com.walkingrpg.shared.domain.walk.SessionEndReason
import com.walkingrpg.shared.domain.weather.FakeSessionWeatherRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 振り返りの一言の生成キュー（[GenerateWalkReviewRemarkUseCase]）。
 *
 * 見たいのは4つ：
 * - 対象は**直近の終了済みセッションだけ**（過去全件に課金しない）
 * - キャッシュ済みは投げ直さない（プロンプトが同じなら二度と課金しない）
 * - **従量回線でも投げる**（帰宅直後に効く1リクエストなので待たない）
 * - 放置セッション（`ABANDONED`）と記録中は対象にしない
 */
class GenerateWalkReviewRemarkUseCaseTest {

    private val species = testSpecies("water", CodexCategory.WATER, requiredVisitCount = 3)

    private class Fixture {
        val sessions = FakeWalkSessionRepository()
        val passages = FakePassageRepository()
        val osm = FakeOsmMasterRepository()
        val weather = FakeSessionWeatherRepository()
        val codexProgress = FakeCodexProgressRepository()
        val recentCodex = FakeRecentCodexRepository()
        val cache = FakeLlmCacheRepository()
        val setup = LlmSetupRepository()
        val clock = MutableClock(nowMs = 5_000L)
    }

    private fun useCase(
        fixture: Fixture,
        client: FakeLlmClient,
        unmetered: Boolean = true,
        config: LlmGenerationConfig = LlmGenerationConfig(
            walkReviewSessionLimit = 2,
        ),
    ) = GenerateWalkReviewRemarkUseCase(
        walkSessionRepository = fixture.sessions,
        getWalkReview = GetWalkReviewUseCase(
            walkSessionRepository = fixture.sessions,
            passageRepository = fixture.passages,
            osmMasterRepository = fixture.osm,
            sessionWeatherRepository = fixture.weather,
            getCodex = GetCodexUseCase(
                codexProgressRepository = fixture.codexProgress,
                getSpeciesDescription = GetSpeciesDescriptionUseCase(fixture.cache),
                recentCodexRepository = fixture.recentCodex,
                catalog = listOf(species),
            ),
            catalog = listOf(species),
        ),
        cacheRepository = fixture.cache,
        setupRepository = fixture.setup,
        clients = FakeLlmClientSelector(client),
        networkStatus = FakeNetworkStatus(unmetered = unmetered),
        timeOfDayResolver = FakeTimeOfDayResolver(),
        clock = fixture.clock,
        config = config,
    )

    private fun client(vararg responses: String) = FakeLlmClient(
        responses = responses.toList().ifEmpty { listOf("""{"remark": "おかえり。よく歩いたね。"}""") },
    )

    private suspend fun finishedSession(
        fixture: Fixture,
        startedAtMs: Long,
        reason: SessionEndReason = SessionEndReason.AUTO_ARRIVAL,
    ): Long {
        val id = fixture.sessions.startSession(startedAtMs)
        fixture.sessions.endSession(id, startedAtMs + 30 * 60_000L, reason)
        return id
    }

    @Test
    fun 終了した散歩の一言を生成して保存する() = runTest {
        val fixture = Fixture()
        val sessionId = finishedSession(fixture, startedAtMs = 1_000L)
        val client = client()

        val outcome = useCase(fixture, client).drain()

        assertEquals(1, outcome.generated)
        assertEquals(1, client.requests.size)
        val saved = fixture.cache.rows().values.single()
        assertEquals(LlmTaskKind.WALK_REVIEW_REMARK, saved.kind)
        assertEquals("WALK_REVIEW_REMARK:session:$sessionId", saved.cacheKey)
        assertEquals("おかえり。よく歩いたね。", saved.text)
        assertEquals(5_000L, saved.createdAtMs, "時刻は Clock から（端末時計を直接読まない）")
    }

    @Test
    fun 対象は直近の数件だけ() = runTest {
        val fixture = Fixture()
        finishedSession(fixture, startedAtMs = 1_000L)
        val second = finishedSession(fixture, startedAtMs = 100_000L)
        val third = finishedSession(fixture, startedAtMs = 200_000L)
        val client = client()

        val outcome = useCase(fixture, client).drain()

        assertEquals(2, outcome.generated, "上限は walkReviewSessionLimit（このテストでは2件）")
        assertEquals(
            setOf(
                "WALK_REVIEW_REMARK:session:$third",
                "WALK_REVIEW_REMARK:session:$second",
            ),
            fixture.cache.rows().keys,
            "新しい順に選ぶ（古い散歩には作らない）",
        )
    }

    @Test
    fun キャッシュ済みなら投げ直さない() = runTest {
        val fixture = Fixture()
        finishedSession(fixture, startedAtMs = 1_000L)
        val client = client()
        useCase(fixture, client).drain()

        val outcome = useCase(fixture, client).drain()

        assertEquals(0, outcome.generated)
        assertEquals(1, outcome.cached)
        assertEquals(1, client.requests.size, "2回目は課金しない")
    }

    @Test
    fun 従量回線でも投げる() = runTest {
        // 事前バッチ（POI・図鑑）はWi-Fiを待つが、一言は帰宅直後に効く1リクエストなので投げる
        val fixture = Fixture()
        finishedSession(fixture, startedAtMs = 1_000L)
        val client = client()

        val outcome = useCase(fixture, client, unmetered = false).drain()

        assertEquals(1, outcome.generated)
        assertEquals(null, outcome.skipReason)
    }

    @Test
    fun 設定で従量回線を抑止できる() = runTest {
        val fixture = Fixture()
        finishedSession(fixture, startedAtMs = 1_000L)
        val client = client()

        val outcome = useCase(
            fixture = fixture,
            client = client,
            unmetered = false,
            config = LlmGenerationConfig(requireUnmeteredNetworkForWalkReview = true),
        ).drain()

        assertEquals(LlmSkipReason.METERED_NETWORK, outcome.skipReason)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun 放置セッションには作らない() = runTest {
        // 散歩を始めた瞬間に畳まれる前回の記録。振り返りとして開く前提のものではない
        val fixture = Fixture()
        fixture.sessions.startSession(1_000L)
        fixture.sessions.abandonOpenSessions()
        val client = client()

        val outcome = useCase(fixture, client).drain()

        assertEquals(LlmSkipReason.NOTHING_TO_GENERATE, outcome.skipReason)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun 記録中の散歩には作らない() = runTest {
        val fixture = Fixture()
        fixture.sessions.startSession(1_000L)
        val client = client()

        val outcome = useCase(fixture, client).drain()

        assertEquals(LlmSkipReason.NOTHING_TO_GENERATE, outcome.skipReason)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun 設定が無ければ投げずに見送る() = runTest {
        val fixture = Fixture()
        finishedSession(fixture, startedAtMs = 1_000L)
        val client = client()
        val useCase = GenerateWalkReviewRemarkUseCase(
            walkSessionRepository = fixture.sessions,
            getWalkReview = GetWalkReviewUseCase(
                walkSessionRepository = fixture.sessions,
                passageRepository = fixture.passages,
                osmMasterRepository = fixture.osm,
                sessionWeatherRepository = fixture.weather,
                getCodex = GetCodexUseCase(
                    codexProgressRepository = fixture.codexProgress,
                    getSpeciesDescription = GetSpeciesDescriptionUseCase(fixture.cache),
                    recentCodexRepository = fixture.recentCodex,
                    catalog = listOf(species),
                ),
                catalog = listOf(species),
            ),
            cacheRepository = fixture.cache,
            setupRepository = LlmSetupRepository(settings = null),
            clients = FakeLlmClientSelector(client),
            networkStatus = FakeNetworkStatus(),
            timeOfDayResolver = FakeTimeOfDayResolver(),
            clock = fixture.clock,
        )

        val outcome = useCase.drain()

        assertEquals(LlmSkipReason.NOT_CONFIGURED, outcome.skipReason)
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun 壊れた応答は保存しない() = runTest {
        val fixture = Fixture()
        finishedSession(fixture, startedAtMs = 1_000L)
        val client = client("""{"flavor": "キーが違う"}""")

        val outcome = useCase(fixture, client).drain()

        assertEquals(0, outcome.generated)
        assertEquals(1, outcome.failed)
        assertTrue(fixture.cache.rows().isEmpty(), "次回のドレインでやり直せるよう保存しない")
    }

    @Test
    fun 通信できなければ残りは投げない() = runTest {
        val fixture = Fixture()
        finishedSession(fixture, startedAtMs = 1_000L)
        finishedSession(fixture, startedAtMs = 100_000L)
        val client = FakeLlmClient(
            failure = LlmUnavailableException(LlmFailureKind.UNAUTHORIZED, "キーが無効"),
        )

        val outcome = useCase(fixture, client).drain()

        assertEquals(1, outcome.failed)
        assertEquals(1, outcome.remaining, "同じ結果になる残りは投げない（有料APIを無駄に叩かない）")
        assertEquals(1, client.requests.size)
    }
}
